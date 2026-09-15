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

@Composable
actual fun rememberFileLauncher(
    onResult: (uri: String?, mimeType: String) -> Unit
): (mimeType: String) -> Unit {

    val delegateHolder = remember { mutableListOf<NSObject>() }

    return { mimeType ->
        val types = if (mimeType == FICTIONBOOK_MIME) {
            listOfNotNull(
                UTType.typeWithFilenameExtension("fb2"),
                UTType.typeWithFilenameExtension("zip"),
                UTType.typeWithMIMEType(FICTIONBOOK_MIME)
            ).distinctBy { it.identifier }
        } else {
            listOfNotNull(UTType.typeWithMIMEType(mimeType))
        }

        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = types,
            asCopy = true
        )
        picker.shouldShowFileExtensions = true

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
