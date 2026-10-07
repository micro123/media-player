package io.github.micro123.mediaplayer.core

enum class PlaybackStatus {
    UNAVAILABLE, IDLE, BUFFERING, PLAYING, PAUSED, ENDED, ERROR,
}

data class PlaybackState(
    val media: MediaItem? = null,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val errorMessage: String? = null,
    val seekable: Boolean = false,
    val speed: Double = 1.0,
    val videoAspectRatio: Double = 16.0 / 9.0,
    val avSyncMs: Double? = null,
    val hardwareDecoder: String? = null,
) {
    val canControl: Boolean
        get() = media != null && status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED, PlaybackStatus.ENDED)
}
