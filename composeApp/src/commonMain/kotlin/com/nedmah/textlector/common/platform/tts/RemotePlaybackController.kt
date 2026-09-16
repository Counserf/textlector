package com.nedmah.textlector.common.platform.tts

interface RemotePlaybackHandler {
    fun play()
    fun pause()
    fun next()
    fun previous()
}

interface RemotePlaybackController {
    fun bind(handler: RemotePlaybackHandler)
    fun update(isPlaying: Boolean, title: String, subtitle: String)
    fun unbind()
}

object NoopRemotePlaybackController : RemotePlaybackController {
    override fun bind(handler: RemotePlaybackHandler) = Unit
    override fun update(isPlaying: Boolean, title: String, subtitle: String) = Unit
    override fun unbind() = Unit
}