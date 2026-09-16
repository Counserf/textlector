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

        // Calendar dates and clock/year notation must be resolved while their
        // punctuation/numeric structure is still available. The generic number
        // normalizer then expands the remaining integers and governed cases.
        val normalizedDates = RussianDateNormalizer.normalize(text)
        val contextualNumbers = RussianContextNumberNormalizer.normalizeRaw(normalizedDates)
        val normalizedNumbers = RussianNumberNormalizer.normalize(contextualNumbers)
        val agreedNumbers = RussianContextNumberNormalizer.repairAgreement(normalizedNumbers)
        return RussianPronunciationRules.apply(agreedNumbers)
    }
}
