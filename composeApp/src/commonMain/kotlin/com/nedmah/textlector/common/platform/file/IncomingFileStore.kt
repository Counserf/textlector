package com.nedmah.textlector.common.platform.file

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small cross-platform handoff used by the iOS shell when a document is opened
 * from Files / Share Sheet. The Compose navigation consumes the pending path.
 */
object IncomingFileStore {
    private val _pendingPath = MutableStateFlow<String?>(null)
    val pendingPath = _pendingPath.asStateFlow()

    fun submit(path: String) {
        _pendingPath.value = path
    }

    fun consume() {
        _pendingPath.value = null
    }
}
