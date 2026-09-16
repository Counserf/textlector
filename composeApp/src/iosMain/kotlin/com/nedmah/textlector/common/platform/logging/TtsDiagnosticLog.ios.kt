package com.nedmah.textlector.common.platform.logging

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSLock
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIPasteboard

actual object TtsDiagnosticLog {
    private val fs = FileSystem.SYSTEM
    private val lock = NSLock()
    private val root = (
        NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true
        ).firstOrNull()?.toString() ?: "."
    ).plus("/TextLectorDiagnostics").toPath()
    private val file = root / "tts-diagnostic.log"

    @OptIn(ExperimentalTime::class)
    actual fun append(tag: String, message: String) {
        lock.lock()
        try {
            fs.createDirectories(root)
            val previous = if (fs.exists(file)) {
                runCatching { fs.read(file) { readUtf8() } }.getOrDefault("")
            } else ""
            val line = "${Clock.System.now().toEpochMilliseconds()} [$tag] $message"
            val lines = (previous.lineSequence().filter { it.isNotBlank() }.toList() + line).takeLast(500)
            fs.write(file) { writeUtf8(lines.joinToString("\n")) }
        } finally {
            lock.unlock()
        }
    }

    actual fun read(): String {
        lock.lock()
        return try {
            if (!fs.exists(file)) "TTS log is empty"
            else runCatching { fs.read(file) { readUtf8() } }.getOrDefault("TTS log read failed")
        } finally {
            lock.unlock()
        }
    }

    actual fun clear() {
        lock.lock()
        try {
            if (fs.exists(file)) fs.delete(file)
        } finally {
            lock.unlock()
        }
    }

    actual fun copyToClipboard(): Boolean = runCatching {
        UIPasteboard.generalPasteboard.string = read()
        true
    }.getOrDefault(false)
}