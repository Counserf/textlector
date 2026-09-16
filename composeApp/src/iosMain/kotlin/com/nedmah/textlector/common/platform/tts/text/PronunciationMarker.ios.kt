package com.nedmah.textlector.common.platform.tts.text

import com.nedmah.textlector.di.IosEngineHolder

actual class PronunciationMarker actual constructor() {
    private val common = NeuralTextPreprocessor()

    actual suspend fun prepare(text: String, language: String): String {
        val normalized = common.process(text, language)
        return IosEngineHolder.pronunciationEnhancer?.enhance(normalized, language) ?: normalized
    }

    actual fun releaseResources() {
        IosEngineHolder.pronunciationEnhancer?.releaseResources()
    }
}