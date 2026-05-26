package com.nedmah.textlector.domain.model

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

data class ProcessedDocument(
    val document: Document,
    val paragraphs: List<Paragraph>
)

data class Document(
    val id: String,           // UUID
    val title: String,
    val sourceType: SourceType,
    val createdAt: Long,
    val isFavorite: Boolean,
    val wordCount: Int,
    val estimatedReadingMinutes: Int,
    val lastOpenedAt: Long,
    val totalParagraphs: Int,
    val lastParagraphIndex: Int // for progress
)

@OptIn(ExperimentalObjCName::class)
@ObjCName("LectorParagraph")
data class Paragraph(
    val id : String,
    val documentId : String,
    val index : Int,
    val text : String,
)

sealed class SourceType{
    data object Manual : SourceType()
    data object Txt : SourceType()
    data object Pdf : SourceType()

    data object Epub : SourceType()

    data object Fb2 : SourceType()
    data class Url(val url : String) : SourceType()
    data object Docx : SourceType() // v2
    data object Camera : SourceType() // v2
}

enum class DocumentSortOrder { LAST_OPENED, CREATED_AT}

data class UserPreferences(
    val speechSpeed: Float,  // 0,5f - 2f
    val speechVoice: VoiceGender,
    val fontSize: Int,
    val isDarkMode: Boolean?,
    val language: String,
    val engineType: TtsEngineType = TtsEngineType.SYSTEM,
) {
    fun resolveVoiceId(): VoiceId =
        when (this.language) {
            "ru" if this.speechVoice == VoiceGender.MALE -> VoiceId.RU_MALE
            "ru" if this.speechVoice == VoiceGender.FEMALE -> VoiceId.RU_FEMALE
            "en" if this.speechVoice == VoiceGender.MALE -> VoiceId.EN_MALE
            else -> VoiceId.EN_FEMALE
        }

    val useSherpaEngine get() = engineType == TtsEngineType.PIPER
    val useSupertonicEngine get() = engineType == TtsEngineType.SUPERTONIC
}

enum class VoiceGender { MALE, FEMALE}

enum class TtsEngineType { SYSTEM, PIPER, SUPERTONIC }