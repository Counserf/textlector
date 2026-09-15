package com.nedmah.textlector.ui.presentation.import_from

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject

private const val FICTIONBOOK_MIME = "application/x-fictionbook+xml"
private const val ZIP_MIME = "application/zip"

@Composable
actual fun rememberFileLauncher(
    onResult: (uri: String?, mimeType: String) -> Unit
): (mimeType: String) -> Unit {

    val delegateHolder = remember { mutableListOf<NSObject>() }

    return { mimeType ->
        val primaryType = UTType.typeWithMIMEType(mimeType)
        val types = if (mimeType == FICTIONBOOK_MIME) {
            listOfNotNull(primaryType, UTType.typeWithMIMEType(ZIP_MIME))
        } else {
            listOfNotNull(primaryType)
        }

        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = types,
            asCopy = true
        )

        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(
                controller: UIDocumentPickerViewController,
                didPickDocumentsAtURLs: List<*>
            ) {
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
                onResult(url?.path, mimeType)
                delegateHolder.clear()
            }
            override fun documentPickerWasCancelled(
                controller: UIDocumentPickerViewController
            ) {
                onResult(null, mimeType)
                delegateHolder.clear()
            }
        }

        delegateHolder.clear()
        delegateHolder.add(delegate)
        picker.delegate = delegate

        UIApplication.sharedApplication.keyWindow
            ?.rootViewController
            ?.presentViewController(picker, animated = true, completion = null)
    }
}
