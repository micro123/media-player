package com.tang.player.core

/** Source-independent queue: providers resolve inputs; the queue keeps stable media identities. */
data class PlaybackQueue(val items: List<MediaItem> = emptyList(), val index: Int = -1) {
    val current: MediaItem? get() = items.getOrNull(index)
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index >= 0 && index < items.lastIndex
}
