package com.tang.player.ui

import com.tang.player.core.MediaItem

data class ClipSelection(val media: MediaItem? = null, val startMs: Long? = null,
    val endMs: Long? = null, val exporting: Boolean = false, val preparing: Boolean = false, val actualStartMs: Long? = null)
