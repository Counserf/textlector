package com.nedmah.textlector.common.platform.tts

import com.nedmah.textlector.domain.model.Paragraph
import com.nedmah.textlector.domain.model.VoiceModel

// Desktop/JVM TTS is not implemented yet. Keep this stub API-compatible so
// common tests can compile and run against shared text preprocessing logic.
class JvmTtsEngine : TtsEngine {

    private var paragraphs: List<Paragraph> = emptyList()

    override suspend fun loadVoice(model: VoiceModel) = Unit

    override fun setPlaylist(paragraphs: List<Paragraph>) {
        this.paragraphs = paragraphs
    }

    override suspend fun speak(index: Int, speed: Float) {
        if (index !in paragraphs.indices) return
        // stub
    }

    override fun stop() = Unit

    override fun shutdown() = Unit
}
