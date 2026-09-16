package com.nedmah.textlector.ui.presentation.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nedmah.textlector.common.platform.logging.CrashReporter
import com.nedmah.textlector.common.platform.logging.TtsDiagnosticLog
import com.nedmah.textlector.common.platform.tts.RemotePlaybackController
import com.nedmah.textlector.common.platform.tts.RemotePlaybackHandler
import com.nedmah.textlector.common.platform.tts.TtsEngine
import com.nedmah.textlector.domain.model.TtsEngineType
import com.nedmah.textlector.domain.model.VoiceGender
import com.nedmah.textlector.domain.model.VoiceRegistry
import com.nedmah.textlector.domain.usecase.GetDocumentUseCase
import com.nedmah.textlector.domain.usecase.GetParagraphsUseCase
import com.nedmah.textlector.domain.usecase.GetPreferencesUseCase
import com.nedmah.textlector.domain.usecase.SaveProgressUseCase
import com.nedmah.textlector.domain.usecase.UpdateLastOpenedUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private const val PLAYER_LOGS = true

private fun playerLog(message: String) {
    if (PLAYER_LOGS) println("[PlayerVM] $message")
    TtsDiagnosticLog.append("Player", message)
}

class PlayerViewModel(
    private val getDocumentUseCase: GetDocumentUseCase,
    private val getParagraphsUseCase: GetParagraphsUseCase,
    private val saveProgressUseCase: SaveProgressUseCase,
    private val updateLastOpenedUseCase: UpdateLastOpenedUseCase,
    private val getPreferencesUseCase: GetPreferencesUseCase,
    private val ttsEngine: TtsEngine,
    private val isBufferingFlow: Flow<Boolean>,
    private val remotePlaybackController: RemotePlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerState())
    val state = _state.asStateFlow()

    private val _effect = Channel<PlayerEffect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    private var saveProgressJob: Job? = null
    private var playbackJob: Job? = null
    private var loadDocumentJob: Job? = null
    private var currentUtteranceId: Int = 0

    init {
        remotePlaybackController.bind(object : RemotePlaybackHandler {
            override fun play() = this@PlayerViewModel.play()
            override fun pause() = this@PlayerViewModel.pause()
            override fun next() = navigateParagraph(+1)
            override fun previous() = navigateParagraph(-1)
        })
        observePreferences()
        observeRemotePlaybackState()
    }

    fun onIntent(intent: PlayerIntent) {
        when (intent) {
            is PlayerIntent.LoadDocument -> loadDocument(intent.documentId)
            PlayerIntent.Play -> play()
            PlayerIntent.Pause -> pause()
            PlayerIntent.NextParagraph -> navigateParagraph(+1)
            PlayerIntent.PreviousParagraph -> navigateParagraph(-1)
            is PlayerIntent.SeekToParagraph -> seekTo(intent.index)
            is PlayerIntent.ChangeSpeed -> changeSpeed(intent.speed)
            PlayerIntent.Stop -> stop()
        }
    }

    private fun observeRemotePlaybackState() {
        viewModelScope.launch {
            _state.collect { current ->
                val title = current.document?.title ?: "TextLector"
                val subtitle = if (current.paragraphs.isNotEmpty()) {
                    "Отрывок ${current.currentParagraphIndex + 1} из ${current.paragraphs.size} · ${current.activeModelLabel}"
                } else {
                    current.activeModelLabel
                }
                remotePlaybackController.update(current.isPlaying, title, subtitle)
            }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            getPreferencesUseCase().collect { prefs ->
                val voiceModel = VoiceRegistry.getById(prefs.resolveVoiceId())
                val modelLabel = when (prefs.engineType) {
                    TtsEngineType.SYSTEM -> "Системный TTS · ${prefs.language.uppercase()}"
                    TtsEngineType.PIPER -> "Piper · ${voiceModel.displayName}"
                    TtsEngineType.SUPERTONIC -> {
                        val voice = if (prefs.speechVoice == VoiceGender.MALE) "M1" else "F1"
                        "Supertonic v3 · $voice · ${prefs.language.uppercase()}"
                    }
                }

                playerLog("preferences: engine=${prefs.engineType}, voice=${prefs.resolveVoiceId()}, speed=${prefs.speechSpeed}")
                _state.update {
                    it.copy(
                        playbackSpeed = prefs.speechSpeed,
                        engineType = prefs.engineType,
                        activeModelLabel = modelLabel
                    )
                }
            }
        }

        viewModelScope.launch {
            isBufferingFlow.collect { buffering ->
                playerLog("isBuffering=$buffering")
                _state.update { it.copy(isBuffering = buffering) }
            }
        }

        viewModelScope.launch {
            ttsEngine.engineChanged.collect {
                val wasPlaying = _state.value.isPlaying
                playerLog("engineChanged, wasPlaying=$wasPlaying")
                if (wasPlaying) {
                    currentUtteranceId++
                    playbackJob?.cancel()
                    ttsEngine.stop()
                    _state.update { it.copy(isPlaying = false, isBuffering = false, errorMessage = null) }
                }
                if (wasPlaying) play()
            }
        }
    }

    private fun loadDocument(documentId: String) {
        if (_state.value.document?.id == documentId) return

        CrashReporter.setKey("document_id", documentId)
        CrashReporter.log("loadDocument: $documentId", tag = "PlayerViewModel")
        playerLog("loadDocument=$documentId")

        loadDocumentJob?.cancel()
        pause()

        loadDocumentJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            updateLastOpenedUseCase(documentId)

            launch {
                getDocumentUseCase(documentId).collect { document ->
                    if (document == null) {
                        _effect.send(PlayerEffect.ShowError("Document not found"))
                        _state.update { it.copy(errorMessage = "Документ не найден") }
                        playerLog("document not found")
                        return@collect
                    }
                    _state.update {
                        it.copy(document = document, currentParagraphIndex = document.lastParagraphIndex)
                    }
                }
            }

            launch {
                getParagraphsUseCase(documentId)
                    .first()
                    .let { paragraphs ->
                        playerLog("setPlaylist paragraphs=${paragraphs.size}, prepared=${paragraphs.count { it.ttsText != null }}")
                        ttsEngine.setPlaylist(paragraphs)
                        _state.update { it.copy(paragraphs = paragraphs, isLoading = false) }
                    }
            }
        }
    }

    private fun play() {
        if (!_state.value.isLoaded) {
            playerLog("play aborted: not loaded")
            return
        }
        if (_state.value.isLoading) {
            playerLog("play aborted: loading")
            return
        }

        val currentIndex = _state.value.currentParagraphIndex
        playerLog("play index=$currentIndex speed=${_state.value.playbackSpeed} model=${_state.value.activeModelLabel}")
        CrashReporter.log("play: index=$currentIndex", tag = "PlayerViewModel")

        val utteranceId = ++currentUtteranceId
        playbackJob?.cancel()
        ttsEngine.stop()
        _state.update { it.copy(isPlaying = true, errorMessage = null) }

        playbackJob = viewModelScope.launch {
            try {
                ttsEngine.speak(currentIndex, _state.value.playbackSpeed)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    playerLog("play cancelled index=$currentIndex")
                    return@launch
                }
                val message = e.message?.takeIf { it.isNotBlank() } ?: "Ошибка генерации аудио"
                playerLog("play error index=$currentIndex: $message")
                CrashReporter.recordException(e, "speak failed at index=$currentIndex")
                _state.update {
                    it.copy(isPlaying = false, isBuffering = false, errorMessage = message)
                }
                return@launch
            }

            playerLog("play finished index=$currentIndex utterance=$utteranceId")
            if (utteranceId == currentUtteranceId) navigateParagraph(+1)
        }
    }

    private fun pause() {
        playerLog("pause utterance=$currentUtteranceId")
        currentUtteranceId++
        playbackJob?.cancel()
        ttsEngine.stop()
        _state.update { it.copy(isPlaying = false, isBuffering = false) }
    }

    private fun stop() {
        playerLog("stop")
        pause()
        scheduleSaveProgress(_state.value.currentParagraphIndex)
    }

    private fun navigateParagraph(delta: Int) {
        val current = _state.value
        if (current.paragraphs.isEmpty()) return

        val newIndex = current.currentParagraphIndex + delta
        val wasPlaying = current.isPlaying

        if (newIndex > current.paragraphs.lastIndex) {
            pause()
            viewModelScope.launch { _effect.send(PlayerEffect.PlaybackFinished) }
            return
        }

        val safeIndex = newIndex.coerceIn(0, current.paragraphs.lastIndex)
        currentUtteranceId++
        playbackJob?.cancel()
        playerLog("navigate ${current.currentParagraphIndex} -> $safeIndex, wasPlaying=$wasPlaying")

        _state.update { it.copy(currentParagraphIndex = safeIndex, errorMessage = null) }
        scheduleSaveProgress(safeIndex)
        if (wasPlaying) play()
    }

    private fun seekTo(index: Int) {
        if (_state.value.paragraphs.isEmpty()) return
        val safeIndex = index.coerceIn(0, _state.value.paragraphs.lastIndex)
        val wasPlaying = _state.value.isPlaying

        currentUtteranceId++
        _state.update { it.copy(currentParagraphIndex = safeIndex, errorMessage = null) }
        scheduleSaveProgress(safeIndex)
        playerLog("seekTo=$safeIndex, wasPlaying=$wasPlaying")

        if (wasPlaying) play()
    }

    private fun changeSpeed(speed: Float) {
        playerLog("speed ${_state.value.playbackSpeed} -> $speed")
        _state.update { it.copy(playbackSpeed = speed, errorMessage = null) }
        if (_state.value.isPlaying) {
            pause()
            play()
        }
    }

    private fun scheduleSaveProgress(index: Int) {
        saveProgressJob?.cancel()
        saveProgressJob = viewModelScope.launch {
            delay(3_000L)
            val documentId = _state.value.document?.id ?: return@launch
            saveProgressUseCase(documentId, index)
        }
    }

    override fun onCleared() {
        remotePlaybackController.unbind()
        ttsEngine.shutdown()
        super.onCleared()
    }
}