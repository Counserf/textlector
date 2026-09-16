package com.nedmah.textlector.common.platform.logging

actual object CrashReporter {
    actual fun log(message: String, tag: String) {
        TtsDiagnosticLog.append(tag, message)
    }

    actual fun recordException(e: Throwable, message: String) {
        TtsDiagnosticLog.append(
            "Exception",
            "$message | ${e::class.simpleName}: ${e.message}\n${e.stackTraceToString()}"
        )
    }

    actual fun setKey(key: String, value: String) {
        TtsDiagnosticLog.append("Context", "$key=$value")
    }
}
