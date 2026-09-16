package com.nedmah.textlector.common.platform.tts

actual class TtsAudioCache actual constructor() {
    actual suspend fun load(key: String): ByteArray? = null
    actual suspend fun save(key: String, audio: ByteArray) = Unit
    actual suspend fun exists(key: String): Boolean = false
}