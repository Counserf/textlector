package com.nedmah.textlector.ui.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nedmah.textlector.common.platform.tts.TtsEngine
import com.nedmah.textlector.domain.model.ModelState
import com.nedmah.textlector.domain.model.VoiceId
import com.nedmah.textlector.domain.model.VoiceRegistry
import com.nedmah.textlector.domain.repository.SupertonicRepository
import com.nedmah.textlector.domain.repository.VoiceModelRepository
import com.nedmah.textlector.domain.usecase.GetPreferencesUseCase
import com.nedmah.textlector.domain.usecase.UpdatePreferencesUseCase
import com.nedmah.textlector.domain.usecase.voice_model.DeleteVoiceModelUseCase
import com.nedmah.textlector.domain.usecase.voice_model.DownloadVoiceModelUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val getPreferencesUseCase: GetPreferencesUseCase,
    private val updatePreferencesUseCase: UpdatePreferencesUseCase,
    private val downloadVoiceModelUseCase: DownloadVoiceModelUseCase,
    private val deleteVoiceModelUseCase: DeleteVoiceModelUseCase,
    private val modelRepository: VoiceModelRepository,
    private val supertonicRepository: SupertonicRepository,
    private val ttsEngine: TtsEngine
) : ViewModel() {

    private val _state = MutableStateFlow(_root_ide_package_.com.nedmah.textlector.ui.presentation.settings.SettingsState())
    val state = _state.asStateFlow()

    private var voiceStateJob: Job? = null
    private var downloadJob: Job? = null


    init {
        observePreferences()
        observeSupertonicState()
    }

    fun onIntent(intent: com.nedmah.textlector.ui.presentation.settings.SettingsIntent) {
        when (intent) {
            is com.nedmah.textlector.ui.presentation.settings.SettingsIntent.SetSpeed ->
                viewModelScope.launch { updatePreferencesUseCase.setSpeed(intent.speed) }

            is com.nedmah.textlector.ui.presentation.settings.SettingsIntent.SetFontSize ->
                viewModelScope.launch { updatePreferencesUseCase.setFontSize(intent.size) }

            is com.nedmah.textlector.ui.presentation.settings.SettingsIntent.SetDarkMode ->
                viewModelScope.launch { updatePreferencesUseCase.setDarkMode(intent.enabled) }

            is com.nedmah.textlector.ui.presentation.settings.SettingsIntent.SetVoice ->
                viewModelScope.launch { updatePreferencesUseCase.setVoice(intent.gender) }

            is com.nedmah.textlector.ui.presentation.settings.SettingsIntent.SetLanguage ->
                viewModelScope.launch { updatePreferencesUseCase.setLanguage(intent.language) }

            is SettingsIntent.SetAudioEngine ->
                viewModelScope.launch { updatePreferencesUseCase.setEngineType(intent.type) }

            is SettingsIntent.DownloadSherpaVoice -> downloadSherpaVoice()
            is SettingsIntent.DeleteSherpaVoice -> deleteSherpaVoice()
            is SettingsIntent.DownloadSupertonic -> downloadSupertonic()
            is SettingsIntent.DeleteSupertonic -> deleteSupertonic()
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            getPreferencesUseCase().collect { prefs ->
                _state.update { it.copy(preferences = prefs) }
                observeVoiceState(prefs.resolveVoiceId())
            }
        }
    }

    private fun observeSupertonicState() {
        viewModelScope.launch {
            supertonicRepository.downloadState.collect { downloadState ->
                _state.update { it.copy(supertonicDownloadState = downloadState) }
            }
        }
    }

    private fun observeVoiceState(id: VoiceId) {
        voiceStateJob?.cancel()
        voiceStateJob = viewModelScope.launch {
            modelRepository.getModelState(id).collect { modelState ->
                _state.update { it.copy(currentVoiceState = modelState) }
            }
        }
    }

    private fun downloadSherpaVoice() {
        if (downloadJob?.isActive == true) return
        val id = currentVoiceId() ?: return
        downloadJob = viewModelScope.launch {
            downloadVoiceModelUseCase(id).collect { modelState ->
                _state.update { it.copy(currentVoiceState = modelState) }

                if (modelState is ModelState.Ready) {
                    val model = VoiceRegistry.getById(id)
                    ttsEngine.loadVoice(model)
                }
            }
        }
    }

    private fun deleteSherpaVoice() {
        viewModelScope.launch {
            val id = currentVoiceId() ?: return@launch
            deleteVoiceModelUseCase(id)
        }
    }

    private fun downloadSupertonic(){
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            supertonicRepository.download().collect { downloadState ->
                _state.update { it.copy(supertonicDownloadState = downloadState) }
            }
        }
    }

    private fun deleteSupertonic() {
        viewModelScope.launch {
            supertonicRepository.deleteModel()
        }
    }

    private fun currentVoiceId(): VoiceId {
        val prefs = _state.value.preferences
        return prefs.resolveVoiceId()
    }

}