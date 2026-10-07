package io.github.micro123.mediaplayer.core

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * Playback boundary. Commands and native resource cleanup run on the engine's worker.
 *
 * Call methods on the main thread. Marshal native events onto that thread before
 * publishing state. Surface ownership stays with the view; null detaches it.
 * release() must be idempotent and detach the surface before freeing native resources.
 */
interface PlaybackEngine {
    val state: StateFlow<PlaybackState>

    fun load(media: MediaItem, startPositionMs: Long = 0)
    fun setSpeed(speed: Double)
    /** -1 restores the file's original display aspect ratio. */
    fun setVideoAspectRatio(ratio: Double)
    fun attachSurface(surface: Surface?)
    fun updateSurfaceSize(width: Int, height: Int)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun stop()
    fun release()
}
