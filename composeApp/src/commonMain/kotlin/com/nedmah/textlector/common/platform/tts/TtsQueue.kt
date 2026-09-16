@file:OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)

package com.nedmah.textlector.common.platform.tts

import com.nedmah.textlector.common.platform.logging.TtsDiagnosticLog
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
private const val AUDIO_CACHE_PIPELINE_VERSION = 4

private fun ttsLog(message: String) {
    if (TTS_QUEUE_LOGS) println("[TtsQueue ${Clock.System.now().toEpochMilliseconds() % 100_000}ms] $message")
    TtsDiagnosticLog.append("TtsQueue", message)
}

class TtsQueue(
    val engine: SherpaOnnxTtsEngine,
    val bufferSize: Int = 1,
    private val cacheNamespace: String,
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
                val preparedText = paragraph.ttsText
                if (preparedText == null) {
                    // Markup runs independently and will update the playlist via DB flow.
                    // Never synthesize raw text here: that would persist a bad WAV.
                    ttsLog("paragraph[$i]: prefetch skipped, pronunciation markup not ready")
                    break
                }

                val key = cacheKey(paragraph, preparedText, speed)
                val disk = audioCache.load(key)
                if (disk != null) {
                    ttsLog("paragraph[$i]: CACHE FILE HIT, size=${disk.size}b")
                    continue
                }

                val (deferred, shouldGenerate) = acquireSlot(i)
                if (!shouldGenerate) continue

                val startMs = Clock.System.now().toEpochMilliseconds()
                try {
                    val audio = generationMutex.withLock {
                        ttsLog("paragraph[$i]: generate start, prepared=true, chars=${preparedText.length}")
                        engine.generate(preparedText, speed)
                    }
                    if (audio.isEmpty()) {
                        deferred.cancel()
                        ttsLog("paragraph[$i]: empty audio")
                        break
                    }
                    audioCache.save(key, audio)
                    val elapsed = Clock.System.now().toEpochMilliseconds() - startMs
                    if (generationId.load() == capturedGeneration) {
                        deferred.complete(audio)
                        ttsLog("paragraph[$i]: ready in ${elapsed}ms, saved=${audio.size}b")
                    } else {
                        deferred.cancel()
                        break
                    }
                } catch (e: CancellationException) {
                    deferred.cancel()
                    ttsLog("paragraph[$i]: cancelled")
                    break
                } catch (e: Exception) {
                    deferred.completeExceptionally(e)
                    ttsLog("paragraph[$i]: error=${e.message}")
                }
            }
        }
    }

    suspend fun getAudio(index: Int, paragraph: Paragraph, speed: Float): ByteArray {
        val preparedText = requirePreparedText(paragraph)
        val key = cacheKey(paragraph, preparedText, speed)
        audioCache.load(key)?.let {
            ttsLog("getAudio($index): CACHE FILE HIT, size=${it.size}b")
            return it
        }

        val (deferred, shouldGenerate) = acquireSlot(index)
        if (shouldGenerate) {
            ttsLog("getAudio($index): CACHE MISS")
            val capturedGeneration = generationId.load()
            try {
                val audio = generationMutex.withLock {
                    ttsLog("getAudio($index): generate start, prepared=true, chars=${preparedText.length}")
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
                ttsLog("getAudio($index): generated and saved ${audio.size}b")
                return audio
            } catch (e: Exception) {
                if (!deferred.isCompleted) deferred.completeExceptionally(e)
                ttsLog("getAudio($index): error=${e.message}")
                throw e
            }
        }

        return try {
            ttsLog("getAudio($index): waiting existing generation")
            val audio = deferred.await()
            mutex.withLock { pending.remove(index) }
            audio
        } catch (e: Exception) {
            mutex.withLock { pending.remove(index) }
            ttsLog("getAudio($index): wait error=${e.message}")
            throw e
        }
    }

    suspend fun preGenerate(paragraph: Paragraph, speed: Float): Boolean {
        val preparedText = requirePreparedText(paragraph)
        val key = cacheKey(paragraph, preparedText, speed)
        if (audioCache.exists(key)) {
            ttsLog("preGenerate(${paragraph.index}): CACHE FILE HIT")
            return true
        }
        ttsLog("preGenerate(${paragraph.index}): start, prepared=true, chars=${preparedText.length}")
        val audio = generationMutex.withLock { engine.generate(preparedText, speed) }
        if (audio.isEmpty()) {
            ttsLog("preGenerate(${paragraph.index}): empty audio")
            return false
        }
        audioCache.save(key, audio)
        ttsLog("preGenerate(${paragraph.index}): saved ${audio.size}b")
        return true
    }

    suspend fun isPersistentlyCached(paragraph: Paragraph, speed: Float): Boolean {
        val preparedText = paragraph.ttsText ?: return false
        return audioCache.exists(cacheKey(paragraph, preparedText, speed))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getCachedAudio(index: Int): ByteArray? {
        val deferred = pending[index] ?: return null
        if (!deferred.isCompleted || deferred.isCancelled) return null
        return runCatching { deferred.getCompleted() }.getOrNull()
    }

    fun clear() {
        generationId.addAndFetch(1)
        ttsLog("clear()")
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

    private fun requirePreparedText(paragraph: Paragraph): String =
        paragraph.ttsText ?: error(
            "Отрывок ${paragraph.index + 1} ещё не прошёл разметку произношения"
        )

    private fun cacheKey(paragraph: Paragraph, preparedText: String, speed: Float): String =
        "${paragraph.documentId}_${paragraph.id}_${cacheNamespace}_s${(speed * 1000f).toInt()}" +
            "_t${stableTextHash(preparedText)}_v$AUDIO_CACHE_PIPELINE_VERSION"

    /** Deterministic cross-platform hash used only for persistent cache invalidation. */
    private fun stableTextHash(text: String): String {
        var hash = 0
        for (ch in text) hash = 31 * hash + ch.code
        return hash.toString()
    }

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
