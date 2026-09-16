package com.nedmah.textlector.common.platform.tts.text

/**
 * Language-aware text preparation that runs immediately before local neural TTS.
 *
 * The document stored in the library is never modified: only the text sent to
 * Piper/Supertonic is normalized. This lets us safely expand numbers and add
 * pronunciation hints without changing what the user sees in the reader.
 */
class NeuralTextPreprocessor {

    fun process(text: String, language: String): String {
        if (!language.lowercase().startsWith("ru")) return text

        val normalizedNumbers = RussianNumberNormalizer.normalize(text)
        return RussianPronunciationRules.apply(normalizedNumbers)
    }
}
