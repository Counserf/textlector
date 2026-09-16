package com.nedmah.textlector.common.platform.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

actual class TtsAudioCache actual constructor() {
    private val fs = FileSystem.SYSTEM
    private val root = (
        NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true
        ).firstOrNull()?.toString() ?: "."
    ).plus("/TextLectorTTSCache").toPath()

    init {
        runCatching { fs.createDirectories(root) }
    }

    actual suspend fun load(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val path = root / safeName(key)
        if (!fs.exists(path)) return@withContext null
        runCatching { fs.read(path) { readByteArray() } }.getOrNull()
    }

    actual suspend fun save(key: String, audio: ByteArray): Unit = withContext(Dispatchers.IO) {
        if (audio.isNotEmpty()) {
            fs.createDirectories(root)
            fs.write(root / safeName(key)) { write(audio) }
        }
        Unit
    }

    actual suspend fun exists(key: String): Boolean = withContext(Dispatchers.IO) {
        fs.exists(root / safeName(key))
    }

    private fun safeName(key: String): String =
        key.map { ch -> if (ch.isLetterOrDigit() || ch in "-_.") ch else '_' }.joinToString("") + ".wav"
}
