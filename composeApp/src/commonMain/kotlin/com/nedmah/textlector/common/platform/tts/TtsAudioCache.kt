package com.nedmah.textlector.common.platform.tts

expect class TtsAudioCache() {
    suspend fun load(key: String): ByteArray?
    suspend fun save(key: String, audio: ByteArray)
    suspend fun exists(key: String): Boolean
}