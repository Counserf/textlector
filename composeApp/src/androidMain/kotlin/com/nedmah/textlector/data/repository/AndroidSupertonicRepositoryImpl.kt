package com.nedmah.textlector.data.repository

import com.nedmah.supertonic_kmp.api.DownloadState
import com.nedmah.supertonic_kmp.api.SupertonicTts
import com.nedmah.textlector.domain.model.SupertonicModelState
import com.nedmah.textlector.domain.repository.SupertonicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AndroidSupertonicRepositoryImpl(
    private val tts: SupertonicTts
) : SupertonicRepository {

    override val downloadState: StateFlow<SupertonicModelState> = tts.downloadState
        .map { it.toSupertonicModelState() }
        .stateIn(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            started = SharingStarted.Eagerly,
            initialValue = tts.downloadState.value.toSupertonicModelState()
        )

    override fun download(): Flow<SupertonicModelState> =
        tts.download().map { it.toSupertonicModelState() }

    override fun deleteModel() = tts.deleteModel()

    private fun DownloadState.toSupertonicModelState(): SupertonicModelState = when (this) {
        is DownloadState.NotDownloaded -> SupertonicModelState.NotDownloaded
        is DownloadState.Downloading -> SupertonicModelState.Downloading(this.bytesDownloaded, this.totalBytes)
        is DownloadState.Ready -> SupertonicModelState.Ready
        is DownloadState.Error -> SupertonicModelState.Error(this.cause.message ?: "Unknown error")
    }

}