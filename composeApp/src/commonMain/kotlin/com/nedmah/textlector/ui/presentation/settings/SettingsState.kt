package com.nedmah.textlector.ui.presentation.settings

import com.nedmah.textlector.domain.model.ModelState
import com.nedmah.textlector.domain.model.SupertonicModelState
import com.nedmah.textlector.domain.model.TtsEngineType
import com.nedmah.textlector.domain.model.UserPreferences
import com.nedmah.textlector.domain.model.VoiceGender

data class SettingsState(
    val preferences: UserPreferences = UserPreferences(
        speechSpeed = 1f,
        speechVoice = VoiceGender.MALE,
        fontSize = 16,
        isDarkMode = null,
        language = "en",
        engineType = TtsEngineType.SYSTEM
    ),
    val currentVoiceState: ModelState = ModelState.NotDownloaded,
    val supertonicDownloadState: SupertonicModelState = SupertonicModelState.NotDownloaded,
) {
    val engineType get() = preferences.engineType
}