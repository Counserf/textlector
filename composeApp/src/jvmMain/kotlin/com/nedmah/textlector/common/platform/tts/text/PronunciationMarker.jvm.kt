package com.nedmah.textlector.common.platform.tts.text

actual class PronunciationMarker actual constructor() {
    private val common = NeuralTextPreprocessor()

    actual suspend fun prepare(text: String, language: String): String =
        common.process(text, language)

    actual fun releaseResources() = Unit
}