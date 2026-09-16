package com.nedmah.textlector.common.platform.tts.text

/** Platform-aware pronunciation preparation used by the background book worker. */
expect class PronunciationMarker() {
    suspend fun prepare(text: String, language: String): String
}

/** Optional native enhancer. iOS uses it for RUAccent homographs and stress dictionaries. */
interface NativePronunciationEnhancer {
    fun enhance(text: String, language: String): String
}