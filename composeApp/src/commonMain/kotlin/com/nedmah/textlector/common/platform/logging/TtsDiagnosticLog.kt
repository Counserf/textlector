package com.nedmah.textlector.common.platform.logging

expect object TtsDiagnosticLog {
    fun append(tag: String, message: String)
    fun read(): String
    fun clear()
    fun copyToClipboard(): Boolean
}