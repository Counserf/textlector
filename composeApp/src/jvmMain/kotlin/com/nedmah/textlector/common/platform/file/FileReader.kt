package com.nedmah.textlector.common.platform.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File

actual class FileReader {
    actual suspend fun readText(uri: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                FileSystem.SYSTEM
                    .source(uri.toPath())
                    .buffer()
                    .use { it.readUtf8() }
            }
        }

    actual suspend fun readPdf(
        uri: String,
        onProgress: (current: Int, total: Int) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            PDDocument.load(File(uri)).use { document ->
                val total = document.numberOfPages.coerceAtLeast(1)
                onProgress(0, total)
                val text = PDFTextStripper().getText(document)
                onProgress(total, total)
                text
            }
        }
    }

    actual suspend fun readBytes(uri: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching { File(uri).readBytes() }
        }
}
