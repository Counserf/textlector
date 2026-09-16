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

        // Some notation needs its surrounding context while it is still numeric
        // (clock time and calendar years). The generic normalizer then expands
        // the remaining integers/cases, after which we can repair grammatical
        // gender using the following noun.
        val contextualNumbers = RussianContextNumberNormalizer.normalizeRaw(text)
        val normalizedNumbers = RussianNumberNormalizer.normalize(contextualNumbers)
        val agreedNumbers = RussianContextNumberNormalizer.repairAgreement(normalizedNumbers)
        return RussianPronunciationRules.apply(agreedNumbers)
    }
}
