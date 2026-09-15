package com.nedmah.textlector.ui.presentation.import_from

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private const val FICTIONBOOK_MIME = "application/x-fictionbook+xml"
private const val ZIP_MIME = "application/zip"

@Composable
actual fun rememberFileLauncher(
    onResult: (uri: String?, mimeType: String) -> Unit
): (mimeType: String) -> Unit {
    var currentMimeType = remember { "application/pdf" }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        onResult(uri?.toString(), currentMimeType)
    }

    return { mimeType ->
        currentMimeType = mimeType
        val mimeTypes = if (mimeType == FICTIONBOOK_MIME) {
            arrayOf(FICTIONBOOK_MIME, ZIP_MIME)
        } else {
            arrayOf(mimeType)
        }
        launcher.launch(mimeTypes)
    }
}
