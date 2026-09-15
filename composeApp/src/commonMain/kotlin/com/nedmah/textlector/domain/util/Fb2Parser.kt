package com.nedmah.textlector.domain.util

import com.fleeksoft.ksoup.Ksoup
import com.nedmah.textlector.common.platform.file.decodeWithCharset
import no.synth.kmpzip.zip.ZipInputStream

object Fb2Parser {

    data class Fb2Result(val title: String, val text: String)

    fun parse(bytes: ByteArray): Fb2Result {
        val fb2Bytes = extractFb2IfZipped(bytes)
        val charset = detectCharset(fb2Bytes)
        val xmlString = fb2Bytes.decodeWithCharset(charset)

        val doc = Ksoup.parse(xmlString)

        val title = doc.selectFirst("description title-info book-title")
            ?.text()
            ?.trim()
            ?: "Untitled"

        val text = doc.select("body p")
            .joinToString("\n\n") { it.text().trim() }
            .trim()

        if (text.isBlank()) error("FB2 has no readable content")

        return Fb2Result(title = title, text = text)
    }

    /**
     * FB2 books are commonly distributed as *.fb2.zip. If the input is a ZIP,
     * extract the first FB2 entry and feed it to the normal FB2 parser.
     */
    private fun extractFb2IfZipped(bytes: ByteArray): ByteArray {
        val looksLikeZip = bytes.size >= 2 &&
            bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte()

        if (!looksLikeZip) return bytes

        ZipInputStream(bytes).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".fb2", ignoreCase = true)) {
                    return zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }

        error("ZIP archive does not contain an FB2 file")
    }

    private fun detectCharset(bytes: ByteArray): String {
        val header = bytes.take(200)
            .map { it.toInt().and(0xFF).toChar() }
            .joinToString("")

        val match = Regex("""encoding=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(header)

        return match?.groupValues?.get(1) ?: "UTF-8"
    }
}
