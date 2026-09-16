package com.nedmah.textlector.common.platform.tts

import com.nedmah.textlector.common.platform.tts.text.PronunciationMarker
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    private val _states = MutableStateFlow<Map<String, BookProcessingState>>(emptyMap())
    val states: StateFlow<Map<String, BookProcessingState>> = _states.asStateFlow()

    private val markupJobs = mutableMapOf<String, Job>()
    private val audioJobs = mutableMapOf<String, Job>()

    fun stateFor(documentId: String): Flow<BookProcessingState> =
        states.map { it[documentId] ?: BookProcessingState(documentId = documentId) }
            .distinctUntilChanged()

    fun startMarkup(documentId: String) {
        if (markupJobs[documentId]?.isActive == true) return
        markupJobs[documentId] = scope.launch {
            runCatching {
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
                    val prepared = marker.prepare(paragraph.text, language)
                    paragraphRepository.updateTtsText(paragraph.id, prepared).getOrThrow()
                    ready += paragraph.index
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
            }.onFailure { e ->
                update(documentId) {
                    it.copy(markupRunning = false, markupCurrentIndex = null, error = e.message ?: "Ошибка разметки")
                }
            }
        }
    }

    fun startAudioGeneration(documentId: String) {
        if (audioJobs[documentId]?.isActive == true) return
        startMarkup(documentId)
        audioJobs[documentId] = scope.launch(Dispatchers.IO) {
            runCatching {
                val prefs = preferencesRepository.getPreferences().first()
                if (!engine.canPreGenerate()) error("Для предгенерации выберите Piper или Supertonic")

                val initial = paragraphRepository.getParagraphsByDocumentId(documentId).first()
                val ready = mutableSetOf<Int>()
                for (paragraph in initial) {
                    if (engine.isParagraphAudioReady(paragraph, prefs.speechSpeed)) ready += paragraph.index
                }
                update(documentId) {
                    it.copy(
                        totalParagraphs = initial.size,
                        audioRunning = ready.size < initial.size,
                        audioDone = ready.size,
                        audioReadyIndices = ready.toSet(),
                        error = null
                    )
                }

                for (index in initial.indices) {
                    val paragraph = paragraphRepository.getParagraphsByDocumentId(documentId)
                        .first { list -> list.getOrNull(index)?.ttsText != null }[index]

                    if (paragraph.index in ready || engine.isParagraphAudioReady(paragraph, prefs.speechSpeed)) {
                        ready += paragraph.index
                        update(documentId) {
                            it.copy(audioDone = ready.size, audioReadyIndices = ready.toSet())
                        }
                        continue
                    }

                    update(documentId) { it.copy(audioCurrentIndex = paragraph.index, audioRunning = true) }
                    if (!engine.preGenerateParagraph(paragraph, prefs.speechSpeed)) {
                        error("Не удалось сгенерировать абзац ${paragraph.index + 1}")
                    }
                    ready += paragraph.index
                    update(documentId) {
                        it.copy(audioDone = ready.size, audioReadyIndices = ready.toSet())
                    }
                }

                update(documentId) {
                    it.copy(
                        audioDone = initial.size,
                        audioReadyIndices = initial.map { p -> p.index }.toSet(),
                        audioCurrentIndex = null,
                        audioRunning = false
                    )
                }
            }.onFailure { e ->
                update(documentId) {
                    it.copy(audioRunning = false, audioCurrentIndex = null, error = e.message ?: "Ошибка генерации аудио")
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
        val current = _states.value
        val previous = current[documentId] ?: BookProcessingState(documentId = documentId)
        _states.value = current + (documentId to transform(previous))
    }
}
