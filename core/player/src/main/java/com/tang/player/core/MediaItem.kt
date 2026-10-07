package com.tang.player.core

/** Stable local document/file URI or network URL, never a temporary native fd:// input. */
data class MediaItem(
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
) {
    val sourceKind: MediaSourceKind get() = mediaSourceKind(uri)
    val isVideo: Boolean
        get() = mimeType?.startsWith("video/") == true
}
