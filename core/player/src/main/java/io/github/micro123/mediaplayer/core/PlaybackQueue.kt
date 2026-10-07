package io.github.micro123.mediaplayer.core

/** Source-independent queue: providers resolve inputs; the queue keeps stable media identities. */
data class PlaybackQueue(val items: List<MediaItem> = emptyList(), val index: Int = -1) {
    val current: MediaItem? get() = items.getOrNull(index)
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index >= 0 && index < items.lastIndex

    /** Preserve the playing occurrence even when the same URI appears more than once. */
    fun move(from: Int, to: Int): PlaybackQueue {
        if (from !in items.indices || to !in items.indices || from == to) return this
        val selected = when {
            index == from -> to
            from < index && to >= index -> index - 1
            from > index && to <= index -> index + 1
            else -> index
        }
        return copy(items = items.toMutableList().apply { add(to, removeAt(from)) }, index = selected)
    }
}
