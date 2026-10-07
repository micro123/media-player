package io.github.micro123.mediaplayer.ui

import io.github.micro123.mediaplayer.core.MediaItem

data class ClipSelection(val media: MediaItem? = null, val startMs: Long? = null,
    val endMs: Long? = null, val exporting: Boolean = false, val preparing: Boolean = false, val actualStartMs: Long? = null)
