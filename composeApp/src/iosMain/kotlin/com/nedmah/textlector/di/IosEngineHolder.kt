package com.nedmah.textlector.di

import com.nedmah.supertonic_kmp.api.SupertonicTts
import com.nedmah.textlector.common.platform.file.FileDownloader
import com.nedmah.textlector.common.platform.file.PdfPageExtractor
import com.nedmah.textlector.common.platform.file.TarExtractor
import com.nedmah.textlector.common.platform.ocr.OcrEngine
import com.nedmah.textlector.common.platform.tts.SherpaOnnxTtsEngine

object IosEngineHolder {
    var sherpaEngine: SherpaOnnxTtsEngine? = null
    var supertonicEngine: SherpaOnnxTtsEngine? = null
    var supertonicTts: SupertonicTts? = null
    var tarExtractor: TarExtractor? = null
    var fileDownloader : FileDownloader? = null
    var pdfExtractor: PdfPageExtractor? = null
    var ocrEngine: OcrEngine? = null
}