package io.github.micro123.mediaplayer.core

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the selection without pretending that a native playback engine is installed. */
class UnavailablePlaybackEngine : PlaybackEngine {
    private val mutableState = MutableStateFlow(unavailableState())
    override val state = mutableState.asStateFlow()
    private var released = false

    override fun load(media: MediaItem, startPositionMs: Long) {
        if (!released) mutableState.value = unavailableState(media)
    }

    override fun setSpeed(speed: Double) = Unit
    override fun setVideoAspectRatio(ratio: Double) = Unit

    override fun attachSurface(surface: Surface?) = Unit
    override fun updateSurfaceSize(width: Int, height: Int) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit

    override fun stop() {
        if (!released) mutableState.value = unavailableState()
    }

    override fun release() {
        if (released) return
        released = true
        mutableState.value = unavailableState()
    }

    private fun unavailableState(media: MediaItem? = null) = PlaybackState(
        media = media,
        status = PlaybackStatus.UNAVAILABLE,
    )
}
