package com.nedmah.textlector.common.platform.tts

import com.nedmah.textlector.common.platform.logging.CrashReporter
import com.nedmah.textlector.common.platform.tts.text.NeuralTextPreprocessor
import com.nedmah.textlector.domain.model.Paragraph
import com.nedmah.textlector.domain.model.TtsEngineType
import com.nedmah.textlector.domain.model.UserPreferences
import com.nedmah.textlector.domain.model.VoiceModel
import com.nedmah.textlector.domain.model.VoiceRegistry
import com.nedmah.textlector.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

private const val ENGINE_LOGS = true
private fun log(message: String) {
    if (ENGINE_LOGS) println("[SwitchableTtsEngine] $message")
}

/**
 * Single entry point for TTS in the app.
 *
 * Piper and Supertonic share one language-aware pronunciation preprocessing
 * layer. The original paragraph remains untouched; only the text supplied to
 * neural generation gets number expansion and stress/homograph hints.
 */
class SwitchableTtsEngine(
    private val nativeEngine: TtsEngine,
    private val sherpaEngine: SherpaOnnxTtsEngine,
    private val supertonicEngine: SherpaOnnxTtsEngine,
    private val preferencesRepository: PreferencesRepository
) : TtsEngine {

    private val _engineChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val engineChanged: Flow<Unit> = _engineChanged

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: Flow<Boolean> = _isBuffering.asStateFlow()

    @Volatile
    private var active: TtsEngine = nativeEngine

    @Volatile
    private var currentLanguage: String = "ru"

    private var currentVoiceKey: String? = null
    private var ttsQueue: TtsQueue? = null
    private var paragraphs: List<Paragraph> = emptyList()
    private val neuralTextPreprocessor = NeuralTextPreprocessor()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch(Dispatchers.IO) {
            preferencesRepository.getPreferences().collect { prefs ->
                handleEngineSwitch(prefs)
            }
        }
    }

    override suspend fun loadVoice(model: VoiceModel) {
        log("loadVoice: ${model.id}")
        (active as? SherpaOnnxTtsEngine)?.loadVoice(model)
    }

    override fun setPlaylist(paragraphs: List<Paragraph>) {
        log("setPlaylist: ${paragraphs.size} paragraphs")
        this.paragraphs = paragraphs
        nativeEngine.setPlaylist(paragraphs)
        sherpaEngine.setPlaylist(paragraphs)
        supertonicEngine.setPlaylist(paragraphs)
    }

    override suspend fun speak(index: Int, speed: Float) {
        val queue = ttsQueue
        log("speak: index=$index, speed=$speed, queue=${when {
            queue != null && active === supertonicEngine -> "Supertonic"
            queue != null -> "Piper"
            else -> "Native"
        }}, paragraphs=${paragraphs.size}")

        if (queue != null) {
            if (index >= paragraphs.size) {
                log("speak: index out of bounds! index=$index, size=${paragraphs.size}")
                return
            }

            val cached = queue.getCachedAudio(index)
            log("speak: cache ${if (cached != null) "HIT" else "MISS"} for index=$index")
            if (cached == null) _isBuffering.emit(true)

            val audio = try {
                queue.getAudio(index, paragraphs[index].text, speed)
            } catch (e: Exception) {
                log("speak: getAudio failed — ${e.message}")
                withContext(NonCancellable) { _isBuffering.emit(false) }
                throw e
            } finally {
                withContext(NonCancellable) { _isBuffering.emit(false) }
            }

            log("speak: audio ready, size=${audio.size}b, starting prefetch and playback")
            queue.prefetchAhead(index, paragraphs, speed)
            (active as SherpaOnnxTtsEngine).playAudio(audio)
            log("speak: playAudio finished for index=$index")
        } else {
            log("speak: native path for index=$index")
            nativeEngine.speak(index, speed)
            log("speak: native finished for index=$index")
        }
    }

    override fun stop() {
        log("stop() called, active=${when (active) {
            sherpaEngine -> "Piper"
            supertonicEngine -> "Supertonic"
            else -> "Native"
        }}")
        ttsQueue?.clear()
        active.stop()
    }

    override fun shutdown() {
        log("shutdown()")
        active.stop()
        scope.coroutineContext[Job]?.cancel()
        nativeEngine.shutdown()
        sherpaEngine.shutdown()
        supertonicEngine.shutdown()
    }

    private fun createNeuralQueue(engine: SherpaOnnxTtsEngine): TtsQueue =
        TtsQueue(
            engine = engine,
            preprocess = { rawText ->
                val prepared = neuralTextPreprocessor.process(rawText, currentLanguage)
                if (prepared != rawText) {
                    log("pronunciation preprocessor changed ${rawText.length} chars -> ${prepared.length} chars")
                }
                prepared
            }
        )

    private suspend fun handleEngineSwitch(prefs: UserPreferences) {
        val previousLanguage = currentLanguage
        currentLanguage = prefs.language

        val targetEngine = when (prefs.engineType) {
            TtsEngineType.SYSTEM -> nativeEngine
            TtsEngineType.PIPER -> sherpaEngine
            TtsEngineType.SUPERTONIC -> supertonicEngine
        }

        val resolvedVoice = prefs.resolveVoiceId()
        val voiceKey = resolvedVoice.name
        val languageChanged = previousLanguage != currentLanguage
        val voiceChanged = currentVoiceKey != voiceKey

        // Same engine, but language/voice changes must invalidate prefetched audio.
        if (active === targetEngine) {
            if (targetEngine is SherpaOnnxTtsEngine) {
                if (voiceChanged) {
                    targetEngine.loadVoice(VoiceRegistry.getById(resolvedVoice))
                }
                if (ttsQueue == null || languageChanged || voiceChanged) {
                    ttsQueue?.shutdown()
                    ttsQueue = createNeuralQueue(targetEngine)
                }
                currentVoiceKey = voiceKey
            } else {
                currentVoiceKey = null
            }
            return
        }

        log("switching to ${prefs.engineType}...")
        active.stop()
        active = targetEngine

        if (targetEngine is SherpaOnnxTtsEngine) {
            val model = VoiceRegistry.getById(resolvedVoice)
            targetEngine.loadVoice(model)
            ttsQueue?.shutdown()
            ttsQueue = createNeuralQueue(targetEngine)
            currentVoiceKey = voiceKey
        } else {
            ttsQueue?.shutdown()
            ttsQueue = null
            currentVoiceKey = null
        }

        CrashReporter.log("Engine switched to ${prefs.engineType}", tag = "SwitchableTtsEngine")
        _engineChanged.tryEmit(Unit)
    }
}
