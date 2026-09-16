@file:OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)

package com.nedmah.textlector.common.platform.tts

import com.nedmah.textlector.domain.model.Paragraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TTS_QUEUE_LOGS = true

private fun ttsLog(message: String) {
    if (TTS_QUEUE_LOGS) println("[TtsQueue ${Clock.System.now().toEpochMilliseconds() % 100_000}ms] $message")
}

class TtsQueue(
    val engine: SherpaOnnxTtsEngine,
    val bufferSize: Int = 1,
    private val cacheNamespace: String,
    private val preprocess: suspend (String) -> String = { it },
    private val audioCache: TtsAudioCache = TtsAudioCache(),
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val generationMutex = Mutex()
    private val pending = mutableMapOf<Int, CompletableDeferred<ByteArray>>()
    private val generationId = AtomicInt(0)

    fun prefetchAhead(currentIndex: Int, paragraphs: List<Paragraph>, speed: Float) {
        scope.launch {
            val capturedGeneration = generationId.load()
            evictStale(currentIndex)
            val from = currentIndex + 1
            val until = minOf(from + bufferSize, paragraphs.size)

            if (from >= paragraphs.size) return@launch
            ttsLog("prefetchAhead($currentIndex): [$from, ${until - 1}]")

            for (i in from until until) {
                if (generationId.load() != capturedGeneration) break
                val paragraph = paragraphs[i]
                val disk = audioCache.load(cacheKey(paragraph, speed))
                if (disk != null) {
                    ttsLog("  paragraph[$i]: CACHE FILE HIT, size=${disk.size}b")
                    continue
                }

                val (deferred, shouldGenerate) = acquireSlot(i)
                if (!shouldGenerate) continue

                val startMs = Clock.System.now().toEpochMilliseconds()
                try {
                    val audio = generationMutex.withLock {
                        val preparedText = paragraph.ttsText ?: preprocess(paragraph.text)
                        engine.generate(preparedText, speed)
                    }
                    if (audio.isEmpty()) {
                        deferred.cancel()
                        break
                    }
                    audioCache.save(cacheKey(paragraph, speed), audio)
                    val elapsed = Clock.System.now().toEpochMilliseconds() - startMs
                    if (generationId.load() == capturedGeneration) {
                        deferred.complete(audio)
                        ttsLog("  paragraph[$i]: ready in ${elapsed}ms, saved=${audio.size}b")
                    } else {
                        deferred.cancel()
                        break
                    }
                } catch (e: CancellationException) {
                    deferred.cancel()
                    break
                } catch (e: Exception) {
                    deferred.completeExceptionally(e)
                    ttsLog("  paragraph[$i]: error — ${e.message}")
                }
            }
        }
    }

    suspend fun getAudio(index: Int, paragraph: Paragraph, speed: Float): ByteArray {
        val key = cacheKey(paragraph, speed)
        audioCache.load(key)?.let {
            ttsLog("getAudio($index): CACHE FILE HIT, size=${it.size}b")
            return it
        }

        val (deferred, shouldGenerate) = acquireSlot(index)
        if (shouldGenerate) {
            ttsLog("getAudio($index): CACHE MISS — generating")
            val capturedGeneration = generationId.load()
            try {
                val audio = generationMutex.withLock {
                    val preparedText = paragraph.ttsText ?: preprocess(paragraph.text)
                    engine.generate(preparedText, speed)
                }
                if (audio.isEmpty()) {
                    deferred.cancel()
                    throw CancellationException("generate() returned empty audio")
                }
                if (generationId.load() != capturedGeneration) {
                    deferred.cancel()
                    throw CancellationException("Generation invalidated by clear()")
                }
                audioCache.save(key, audio)
                deferred.complete(audio)
                mutex.withLock { pending.remove(index) }
                return audio
            } catch (e: Exception) {
                if (!deferred.isCompleted) deferred.completeExceptionally(e)
                throw e
            }
        }

        return try {
            val audio = deferred.await()
            mutex.withLock { pending.remove(index) }
            audio
        } catch (e: Exception) {
            mutex.withLock { pending.remove(index) }
            throw e
        }
    }

    suspend fun preGenerate(paragraph: Paragraph, speed: Float): Boolean {
        val key = cacheKey(paragraph, speed)
        if (audioCache.exists(key)) return true
        val preparedText = paragraph.ttsText ?: preprocess(paragraph.text)
        val audio = generationMutex.withLock { engine.generate(preparedText, speed) }
        if (audio.isEmpty()) return false
        audioCache.save(key, audio)
        return true
    }

    suspend fun isPersistentlyCached(paragraph: Paragraph, speed: Float): Boolean =
        audioCache.exists(cacheKey(paragraph, speed))

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getCachedAudio(index: Int): ByteArray? {
        val deferred = pending[index] ?: return null
        if (!deferred.isCompleted || deferred.isCancelled) return null
        return runCatching { deferred.getCompleted() }.getOrNull()
    }

    fun clear() {
        generationId.addAndFetch(1)
        scope.launch {
            mutex.withLock {
                pending.values.forEach { it.cancel() }
                pending.clear()
            }
        }
    }

    suspend fun clearSync() {
        generationId.addAndFetch(1)
        mutex.withLock {
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }

    fun shutdown() {
        scope.coroutineContext[Job]?.cancel()
        clear()
    }

    private fun cacheKey(paragraph: Paragraph, speed: Float): String =
        "${paragraph.documentId}_${paragraph.id}_${cacheNamespace}_s${(speed * 1000f).toInt()}_v2"

    private suspend fun acquireSlot(index: Int): Pair<CompletableDeferred<ByteArray>, Boolean> =
        mutex.withLock {
            val existing = pending[index]
            if (existing != null && !existing.isCancelled) {
                existing to false
            } else {
                val fresh = CompletableDeferred<ByteArray>()
                pending[index] = fresh
                fresh to true
            }
        }

    private suspend fun evictStale(currentIndex: Int) {
        mutex.withLock {
            pending.keys.filter { it < currentIndex }.forEach { key ->
                pending[key]?.cancel()
                pending.remove(key)
            }
        }
    }
}
