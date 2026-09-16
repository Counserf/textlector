package com.nedmah.textlector.common.platform.logging

actual object TtsDiagnosticLog {
    private val lines = ArrayDeque<String>()

    actual fun append(tag: String, message: String) {
        if (lines.size >= 500) lines.removeFirst()
        lines.addLast("[$tag] $message")
    }

    actual fun read(): String = lines.joinToString("\n").ifBlank { "TTS log is empty" }
    actual fun clear() = lines.clear()
    actual fun copyToClipboard(): Boolean = false
}