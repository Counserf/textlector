package com.nedmah.textlector.ui.presentation.player

import com.nedmah.textlector.domain.model.Document
import com.nedmah.textlector.domain.model.Paragraph
import com.nedmah.textlector.domain.model.TtsEngineType

data class PlayerState(
    val document: Document? = null,
    val paragraphs: List<Paragraph> = emptyList(),
    val currentParagraphIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val isBuffering: Boolean = false, // generating audio
    val playbackSpeed: Float = 1f,
    val engineType: TtsEngineType = TtsEngineType.SYSTEM,
    val activeModelLabel: String = "Системный TTS",
    val errorMessage: String? = null,
) {
    val currentParagraph: Paragraph?
        get() = paragraphs.getOrNull(currentParagraphIndex)

    val progress: Float
        get() = when {
            paragraphs.isEmpty() -> 0f
            paragraphs.size == 1 -> 0f
            else -> currentParagraphIndex.toFloat() / (paragraphs.size - 1)
        }

    val elapsedMinutes: Float
        get() {
            val total = (document?.estimatedReadingMinutes ?: 0) / playbackSpeed
            return total * progress
        }

    val remainingMinutes: Float
        get() {
            val total = (document?.estimatedReadingMinutes ?: 0) / playbackSpeed
            return (total - elapsedMinutes).coerceAtLeast(0f)
        }

    val isLoaded: Boolean
        get() = document != null && paragraphs.isNotEmpty()
}
