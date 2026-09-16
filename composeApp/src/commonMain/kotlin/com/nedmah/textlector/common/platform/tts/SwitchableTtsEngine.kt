package com.nedmah.textlector.common.platform.tts

import com.nedmah.textlector.common.platform.logging.CrashReporter
import com.nedmah.textlector.common.platform.logging.TtsDiagnosticLog
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

private const val ENGINE_LOGS = true
private fun log(message: String) {
    if (ENGINE_LOGS) println("[SwitchableTtsEngine] $message")
    TtsDiagnosticLog.append("Engine", message)
}

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

    @Volatile private var active: TtsEngine = nativeEngine
    @Volatile private var currentLanguage: String = "ru"

    private var currentVoiceKey: String? = null
    private var currentVoiceModel: VoiceModel? = null
    private var loadedVoiceSignature: String? = null
    private var currentEngineType: TtsEngineType = TtsEngineType.SYSTEM
    private var ttsQueue: TtsQueue? = null
    private var paragraphs: List<Paragraph> = emptyList()
    private val voiceLoadMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch(Dispatchers.IO) {
            preferencesRepository.getPreferences().collect { prefs -> handleEngineSwitch(prefs) }
        }
    }

    override suspend fun loadVoice(model: VoiceModel) {
        currentVoiceModel = model
        currentVoiceKey = model.id.name
        loadedVoiceSignature = null
        ensureNeuralVoiceLoaded()
    }

    override fun setPlaylist(paragraphs: List<Paragraph>) {
        log("setPlaylist: ${paragraphs.size} paragraphs, prepared=${paragraphs.count { it.ttsText != null }}")
        this.paragraphs = paragraphs
        nativeEngine.setPlaylist(paragraphs)
        sherpaEngine.setPlaylist(paragraphs)
        supertonicEngine.setPlaylist(paragraphs)
    }

    override suspend fun speak(index: Int, speed: Float) {
        val queue = ttsQueue
        log("speak: index=$index, speed=$speed, engine=$currentEngineType, paragraphs=${paragraphs.size}")

        if (queue != null) {
            if (index !in paragraphs.indices) return
            val paragraph = paragraphs[index]

            // This check intentionally happens BEFORE loading Piper/Supertonic.
            // RUAccent can still be marking the book; loading both ONNX stacks at
            // once caused the memory spike that originally crashed iOS playback.
            if (paragraph.ttsText == null) {
                log("speak blocked: paragraph=$index pronunciation markup not ready")
                error("Отрывок ${index + 1} ещё не прошёл разметку произношения")
            }

            ensureNeuralVoiceLoaded()

            val cached = queue.getCachedAudio(index)
            if (cached == null) _isBuffering.emit(true)

            val audio = try {
                queue.getAudio(index, paragraph, speed)
            } catch (e: Exception) {
                withContext(NonCancellable) { _isBuffering.emit(false) }
                throw e
            } finally {
                withContext(NonCancellable) { _isBuffering.emit(false) }
            }

            queue.prefetchAhead(index, paragraphs, speed)
            (active as SherpaOnnxTtsEngine).playAudio(audio)
        } else {
            nativeEngine.speak(index, speed)
        }
    }

    suspend fun preGenerateParagraph(paragraph: Paragraph, speed: Float): Boolean {
        val queue = ttsQueue ?: return false
        if (paragraph.ttsText == null) {
            log("preGenerate blocked: paragraph=${paragraph.index} pronunciation markup not ready")
            return false
        }
        ensureNeuralVoiceLoaded()
        return queue.preGenerate(paragraph, speed)
    }

    suspend fun isParagraphAudioReady(paragraph: Paragraph, speed: Float): Boolean {
        val queue = ttsQueue ?: return false
        return queue.isPersistentlyCached(paragraph, speed)
    }

    fun canPreGenerate(): Boolean = ttsQueue != null

    override fun stop() {
        log("stop() active=$currentEngineType")
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

    private suspend fun ensureNeuralVoiceLoaded() {
        val engine = active as? SherpaOnnxTtsEngine ?: return
        val model = currentVoiceModel ?: return
        val signature = "${currentEngineType}_${model.id}"
        if (loadedVoiceSignature == signature) return

        voiceLoadMutex.withLock {
            if (loadedVoiceSignature == signature) return@withLock
            log("lazy load voice: $signature")
            engine.loadVoice(model)
            loadedVoiceSignature = signature
            log("voice ready: $signature")
        }
    }

    private fun createNeuralQueue(engine: SherpaOnnxTtsEngine): TtsQueue {
        val engineKey = if (engine === supertonicEngine) "supertonic" else "piper"
        val namespace = "${engineKey}_${currentVoiceKey ?: "voice"}_${currentLanguage}"
        return TtsQueue(
            engine = engine,
            cacheNamespace = namespace
        )
    }

    private suspend fun handleEngineSwitch(prefs: UserPreferences) {
        val previousLanguage = currentLanguage
        val previousEngine = currentEngineType
        currentLanguage = prefs.language
        currentEngineType = prefs.engineType

        val targetEngine = when (prefs.engineType) {
            TtsEngineType.SYSTEM -> nativeEngine
            TtsEngineType.PIPER -> sherpaEngine
            TtsEngineType.SUPERTONIC -> supertonicEngine
        }

        val resolvedVoice = prefs.resolveVoiceId()
        val model = VoiceRegistry.getById(resolvedVoice)
        val voiceKey = resolvedVoice.name
        val languageChanged = previousLanguage != currentLanguage
        val voiceChanged = currentVoiceKey != voiceKey
        val engineChanged = active !== targetEngine || previousEngine != currentEngineType

        currentVoiceModel = model
        if (engineChanged || voiceChanged) loadedVoiceSignature = null

        if (active === targetEngine) {
            if (targetEngine is SherpaOnnxTtsEngine) {
                if (ttsQueue == null || languageChanged || voiceChanged) {
                    currentVoiceKey = voiceKey
                    ttsQueue?.shutdown()
                    ttsQueue = createNeuralQueue(targetEngine)
                } else {
                    currentVoiceKey = voiceKey
                }
            } else {
                currentVoiceKey = null
                ttsQueue?.shutdown()
                ttsQueue = null
            }
            return
        }

        log("switching to ${prefs.engineType}; voice will load lazily")
        active.stop()
        active = targetEngine

        if (targetEngine is SherpaOnnxTtsEngine) {
            currentVoiceKey = voiceKey
            ttsQueue?.shutdown()
            ttsQueue = createNeuralQueue(targetEngine)
        } else {
            ttsQueue?.shutdown()
            ttsQueue = null
            currentVoiceKey = null
        }

        CrashReporter.log("Engine switched to ${prefs.engineType}", tag = "SwitchableTtsEngine")
        _engineChanged.tryEmit(Unit)
    }
}
