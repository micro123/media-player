package com.tang.player.ui

data class VideoSeekPreview(val mediaUri: String, val originalPositionMs: Long, val targetPositionMs: Long,
    val wasPlaying: Boolean, val cancelled: Boolean = false)
