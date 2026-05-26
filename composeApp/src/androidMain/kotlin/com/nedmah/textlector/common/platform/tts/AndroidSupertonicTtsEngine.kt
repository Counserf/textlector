package com.nedmah.textlector.common.platform.tts

import android.content.Context
import android.media.AudioTrack
import com.nedmah.supertonic_kmp.api.SupertonicConfig
import com.nedmah.supertonic_kmp.api.SupertonicTts
import com.nedmah.supertonic_kmp.api.SupertonicVoice
import com.nedmah.textlector.common.platform.logging.CrashReporter
import com.nedmah.textlector.domain.model.Paragraph
import com.nedmah.textlector.domain.model.VoiceGender
import com.nedmah.textlector.domain.model.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSupertonicTtsEngine(
    private val context: Context
) : SherpaOnnxTtsEngine {


    private var tts: SupertonicTts? = null
    private var audioTrack: AudioTrack? = null


    private var paragraphs: List<Paragraph> = emptyList()
    private var currentLang: String = "en"
    private var currentVoice: SupertonicVoice = SupertonicVoice.M1

    private fun requireTts(): SupertonicTts =
        tts ?: buildSupertonicTts().also { tts = it }

    // voice model is built for piper here, but all we need is gender and language
    override suspend fun loadVoice(model: VoiceModel) {
        currentLang = model.language
        currentVoice = when (model.gender) {
            VoiceGender.MALE -> SupertonicVoice.M1
            VoiceGender.FEMALE -> SupertonicVoice.F1
        }
        tts?.close()
        tts = null
    }

    override suspend fun generate(text: String, speed: Float): ByteArray {
        TODO("Not yet implemented")
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
        TODO("Not yet implemented")
    }

    override fun stop() {
        TODO("Not yet implemented")
    }

    override fun shutdown() {
        TODO("Not yet implemented")
    }

    private fun buildSupertonicTts(): SupertonicTts {

        val config = SupertonicConfig(
            storageDir = context.filesDir.path + "/supertonic",
            defaultLang = "ru",
            defaultVoice = SupertonicVoice.M1,
            inferenceSteps = 8
        )

        return try {
            SupertonicTts(config = config)
        } catch (t: Throwable) {
            CrashReporter.recordException(t, t.message ?: "Unable to build supertonic tts")
            throw t
        }
    }
}