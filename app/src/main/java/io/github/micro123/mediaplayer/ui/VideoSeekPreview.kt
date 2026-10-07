package io.github.micro123.mediaplayer.ui

data class VideoSeekPreview(val mediaUri: String, val originalPositionMs: Long, val targetPositionMs: Long,
    val wasPlaying: Boolean, val cancelled: Boolean = false)
