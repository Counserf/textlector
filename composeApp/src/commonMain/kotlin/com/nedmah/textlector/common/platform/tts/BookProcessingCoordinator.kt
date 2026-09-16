package com.nedmah.textlector.common.platform.tts

import com.nedmah.textlector.common.platform.logging.TtsDiagnosticLog
import com.nedmah.textlector.common.platform.tts.text.PronunciationMarker
import com.nedmah.textlector.domain.model.Paragraph
import com.nedmah.textlector.domain.repository.ParagraphRepository
import com.nedmah.textlector.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BookProcessingState(
    val documentId: String = "",
    val totalParagraphs: Int = 0,
    val markupDone: Int = 0,
    val markupReadyIndices: Set<Int> = emptySet(),
    val markupCurrentIndex: Int? = null,
    val markupRunning: Boolean = false,
    val audioDone: Int = 0,
    val audioReadyIndices: Set<Int> = emptySet(),
    val audioCurrentIndex: Int? = null,
    val audioRunning: Boolean = false,
    val audioWaitingForMarkup: Boolean = false,
    val error: String? = null,
) {
    val markupComplete: Boolean get() = totalParagraphs > 0 && markupDone >= totalParagraphs
    val audioComplete: Boolean get() = totalParagraphs > 0 && audioDone >= totalParagraphs
}

class BookProcessingCoordinator(
    private val paragraphRepository: ParagraphRepository,
    private val preferencesRepository: PreferencesRepository,
    private val engine: SwitchableTtsEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val marker = PronunciationMarker()
    private val markerMutex = Mutex()
    private val _states = MutableStateFlow<Map<String, BookProcessingState>>(emptyMap())
    val states: StateFlow<Map<String, BookProcessingState>> = _states.asStateFlow()

    private val markupJobs = mutableMapOf<String, Job>()
    private val audioJobs = mutableMapOf<String, Job>()

    fun stateFor(documentId: String): Flow<BookProcessingState> =
        states.map { it[documentId] ?: BookProcessingState(documentId = documentId) }
            .distinctUntilChanged()

    /**
     * Returns only a paragraph that has already passed the pronunciation pipeline.
     * Neural playback uses this instead of falling back to raw text, so an early
     * Play can never create a persistent WAV with missing RUAccent/number markup.
     */
    suspend fun awaitPreparedParagraph(documentId: String, index: Int): Paragraph {
        startMarkup(documentId)
        TtsDiagnosticLog.append("Markup", "await paragraph=$index document=$documentId")

        val (paragraphs, state) = combine(
            paragraphRepository.getParagraphsByDocumentId(documentId),
            stateFor(documentId)
        ) { currentParagraphs, currentState -> currentParagraphs to currentState }
            .first { (currentParagraphs, currentState) ->
                currentParagraphs.getOrNull(index)?.ttsText != null ||
                    (!currentState.markupRunning && currentState.error != null)
            }

        val paragraph = paragraphs.getOrNull(index)
            ?: error("Отрывок ${index + 1} не найден")
        if (paragraph.ttsText == null) {
            error(state.error ?: "Не удалось подготовить отрывок ${index + 1} для озвучки")
        }

        TtsDiagnosticLog.append("Markup", "await ready paragraph=$index document=$documentId")
        return paragraph
    }

    fun startMarkup(documentId: String) {
        if (markupJobs[documentId]?.isActive == true) return
        markupJobs[documentId] = scope.launch {
            markerMutex.withLock {
                TtsDiagnosticLog.append("Markup", "start document=$documentId")
                try {
                    val language = preferencesRepository.getPreferences().first().language
                    val paragraphs = paragraphRepository.getParagraphsByDocumentId(documentId).first()
                    val ready = paragraphs.filter { it.ttsText != null }.map { it.index }.toMutableSet()
                    update(documentId) {
                        it.copy(
                            totalParagraphs = paragraphs.size,
                            markupDone = ready.size,
                            markupReadyIndices = ready.toSet(),
                            markupRunning = ready.size < paragraphs.size,
                            error = null
                        )
                    }

                    for (paragraph in paragraphs) {
                        if (paragraph.index in ready) continue
                        update(documentId) { it.copy(markupCurrentIndex = paragraph.index, markupRunning = true) }
                        TtsDiagnosticLog.append("Markup", "paragraph=${paragraph.index} chars=${paragraph.text.length} start")
                        val prepared = marker.prepare(paragraph.text, language)
                        paragraphRepository.updateTtsText(paragraph.id, prepared).getOrThrow()
                        ready += paragraph.index
                        TtsDiagnosticLog.append("Markup", "paragraph=${paragraph.index} ready chars=${prepared.length}")
                        update(documentId) {
                            it.copy(markupDone = ready.size, markupReadyIndices = ready.toSet())
                        }
                    }

                    update(documentId) {
                        it.copy(
                            markupDone = paragraphs.size,
                            markupReadyIndices = paragraphs.map { p -> p.index }.toSet(),
                            markupCurrentIndex = null,
                            markupRunning = false
                        )
                    }
                    TtsDiagnosticLog.append("Markup", "complete document=$documentId total=${paragraphs.size}")
                } catch (e: Exception) {
                    TtsDiagnosticLog.append("Markup", "error document=$documentId message=${e.message}")
                    update(documentId) {
                        it.copy(markupRunning = false, markupCurrentIndex = null, error = e.message ?: "Ошибка разметки")
                    }
                } finally {
                    marker.releaseResources()
                    TtsDiagnosticLog.append("Markup", "resources released document=$documentId")
                }
            }
        }
    }

    fun startAudioGeneration(documentId: String) {
        if (audioJobs[documentId]?.isActive == true) return
        startMarkup(documentId)
        audioJobs[documentId] = scope.launch(Dispatchers.IO) {
            try {
                val prefs = preferencesRepository.getPreferences().first()
                if (!engine.canPreGenerate()) error("Для предгенерации выберите Piper или Supertonic")

                val initial = paragraphRepository.getParagraphsByDocumentId(documentId).first()
                update(documentId) {
                    it.copy(
                        totalParagraphs = initial.size,
                        audioRunning = true,
                        audioWaitingForMarkup = initial.any { p -> p.ttsText == null },
                        error = null
                    )
                }
                TtsDiagnosticLog.append("AudioJob", "queued document=$documentId total=${initial.size}")

                // Do not keep RUAccent and the neural TTS model doing heavy work at
                // the same time. Generate can be requested immediately after import;
                // it stays queued until all persisted pronunciation markup is ready.
                markupJobs[documentId]?.join()
                val paragraphs = paragraphRepository.getParagraphsByDocumentId(documentId).first()
                if (paragraphs.any { it.ttsText == null }) {
                    error("Разметка завершилась не полностью")
                }
                update(documentId) { it.copy(audioWaitingForMarkup = false) }

                val ready = mutableSetOf<Int>()
                for (paragraph in paragraphs) {
                    if (engine.isParagraphAudioReady(paragraph, prefs.speechSpeed)) ready += paragraph.index
                }
                update(documentId) {
                    it.copy(audioDone = ready.size, audioReadyIndices = ready.toSet())
                }

                for (paragraph in paragraphs) {
                    if (paragraph.index in ready || engine.isParagraphAudioReady(paragraph, prefs.speechSpeed)) {
                        ready += paragraph.index
                        update(documentId) { it.copy(audioDone = ready.size, audioReadyIndices = ready.toSet()) }
                        continue
                    }

                    update(documentId) { it.copy(audioCurrentIndex = paragraph.index, audioRunning = true) }
                    TtsDiagnosticLog.append("AudioJob", "paragraph=${paragraph.index} generate start")
                    if (!engine.preGenerateParagraph(paragraph, prefs.speechSpeed)) {
                        error("Не удалось сгенерировать абзац ${paragraph.index + 1}")
                    }
                    ready += paragraph.index
                    TtsDiagnosticLog.append("AudioJob", "paragraph=${paragraph.index} ready")
                    update(documentId) { it.copy(audioDone = ready.size, audioReadyIndices = ready.toSet()) }
                }

                update(documentId) {
                    it.copy(
                        audioDone = paragraphs.size,
                        audioReadyIndices = paragraphs.map { p -> p.index }.toSet(),
                        audioCurrentIndex = null,
                        audioRunning = false,
                        audioWaitingForMarkup = false
                    )
                }
                TtsDiagnosticLog.append("AudioJob", "complete document=$documentId total=${paragraphs.size}")
            } catch (e: Exception) {
                TtsDiagnosticLog.append("AudioJob", "error document=$documentId message=${e.message}")
                update(documentId) {
                    it.copy(
                        audioRunning = false,
                        audioWaitingForMarkup = false,
                        audioCurrentIndex = null,
                        error = e.message ?: "Ошибка генерации аудио"
                    )
                }
            }
        }
    }

    fun refresh(documentId: String) {
        scope.launch(Dispatchers.IO) {
            val prefs = preferencesRepository.getPreferences().first()
            val paragraphs = paragraphRepository.getParagraphsByDocumentId(documentId).first()
            val marked = paragraphs.filter { it.ttsText != null }.map { it.index }.toSet()
            val audio = mutableSetOf<Int>()
            if (engine.canPreGenerate()) {
                for (paragraph in paragraphs) {
                    if (engine.isParagraphAudioReady(paragraph, prefs.speechSpeed)) audio += paragraph.index
                }
            }
            update(documentId) {
                it.copy(
                    totalParagraphs = paragraphs.size,
                    markupDone = marked.size,
                    markupReadyIndices = marked,
                    audioDone = audio.size,
                    audioReadyIndices = audio
                )
            }
        }
    }

    private fun update(documentId: String, transform: (BookProcessingState) -> BookProcessingState) {
        _states.update { current ->
            val previous = current[documentId] ?: BookProcessingState(documentId = documentId)
            current + (documentId to transform(previous))
        }
    }
}
