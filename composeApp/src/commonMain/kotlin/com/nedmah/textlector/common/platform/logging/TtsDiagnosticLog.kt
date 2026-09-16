package com.nedmah.textlector.common.platform.logging

expect object TtsDiagnosticLog {
    fun append(tag: String, message: String)
    fun read(): String
    fun clear()
    fun copyToClipboard(): Boolean
    fun export(): Boolean
}

interface DiagnosticLogExporter {
    fun export(logText: String): Boolean
}
