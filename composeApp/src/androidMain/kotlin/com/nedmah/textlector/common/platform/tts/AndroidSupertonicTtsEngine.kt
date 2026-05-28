package com.nedmah.textlector.common.platform.tts

import android.content.Context
import android.media.AudioTrack
import com.nedmah.supertonic_kmp.api.GenerateResult
import com.nedmah.supertonic_kmp.api.SupertonicConfig
import com.nedmah.supertonic_kmp.api.SupertonicTts
import com.nedmah.supertonic_kmp.api.SupertonicVoice
import com.nedmah.textlector.common.platform.logging.CrashReporter
import com.nedmah.textlector.domain.model.Paragraph
import com.nedmah.textlector.domain.model.VoiceGender
import com.nedmah.textlector.domain.model.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class AndroidSupertonicTtsEngine(
    private val tts: SupertonicTts
) : SherpaOnnxTtsEngine {


    private var audioTrack: AudioTrack? = null

    private var paragraphs: List<Paragraph> = emptyList()
    private var currentLang: String = "en"
    private var currentVoice: SupertonicVoice = SupertonicVoice.M1

    // VoiceModel class is made for piper here, but all we need is gender and language
    override suspend fun loadVoice(model: VoiceModel) {
        currentLang = model.language
        currentVoice = when (model.gender) {
            VoiceGender.MALE -> SupertonicVoice.M1
            VoiceGender.FEMALE -> SupertonicVoice.F1
        }
    }

    override suspend fun generate(text: String, speed: Float): ByteArray = withContext(Dispatchers.IO) {
        try {
            val result = tts.generate(
                text = text,
                lang = currentLang,
                voice = currentVoice,
                speed = speed,
            )
            when (result) {
                is GenerateResult.Success -> result.wav
                else -> {
                    CrashReporter.log("Supertonic generate failed: $result", tag = "SupertonicEngine")
                    ByteArray(0)
                }
            }
        } catch (e: Exception) {
            CrashReporter.recordException(e, "Supertonic generate failed")
            ByteArray(0)
        }
    }

    override suspend fun playAudio(audio: ByteArray) {
        withContext(Dispatchers.IO) {
            AndroidTrackPlayer.play(audio) { track ->
                audioTrack?.release()
                audioTrack = track
            }
        }
    }

    override fun setPlaylist(paragraphs: List<Paragraph>) {
        this.paragraphs = paragraphs
    }

    override suspend fun speak(index: Int, speed: Float) {
        val text = paragraphs.getOrNull(index)?.text ?: return
        val audio = generate(text, speed)
        if (audio.isNotEmpty()) playAudio(audio)
    }

    override fun stop() {
        tts.stop()
        AndroidTrackPlayer.stop(audioTrack)
    }

    override fun shutdown() {
        audioTrack?.release()
        audioTrack = null
        tts.stop()
        tts.close()
    }
}