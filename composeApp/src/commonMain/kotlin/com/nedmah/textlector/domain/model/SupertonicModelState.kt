package com.nedmah.textlector.domain.model

sealed class SupertonicModelState {
    object NotDownloaded : SupertonicModelState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : SupertonicModelState()
    object Ready : SupertonicModelState()
    data class Error(val message: String) : SupertonicModelState()
}