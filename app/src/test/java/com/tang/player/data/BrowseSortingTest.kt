package com.tang.player.data

import org.junit.Assert.*
import org.junit.Test

class BrowseSortingTest {
    private data class Entry(val name: String, val directory: Boolean = false, val size: Long? = null)
    private fun sort(items: List<Entry>, mode: BrowseSort, reverse: Boolean = false) =
        sortBrowseItems(items, mode, reverse, { it.name }, { it.directory }, { it.size }).map { it.name }
    @Test fun naturalNamesAndDirectoriesStayFirstInEitherDirection() {
        val items = listOf(Entry("episode10.mp4"), Entry("episode2.mp4"), Entry("Z", true), Entry("A", true))
        assertEquals(listOf("A", "Z", "episode2.mp4", "episode10.mp4"), sort(items, BrowseSort.NAME))
        assertEquals(listOf("Z", "A", "episode10.mp4", "episode2.mp4"), sort(items, BrowseSort.NAME, true))
    }
    @Test fun sizeOrderingKeepsUnknownAtEndAndBreaksTiesNaturally() {
        val items = listOf(Entry("folder", true), Entry("unknown.mp4"), Entry("large.mp4", size = 500), Entry("episode10.mp4", size = 30), Entry("episode2.mp4", size = 30))
        assertEquals(listOf("folder", "episode2.mp4", "episode10.mp4", "large.mp4", "unknown.mp4"), sort(items, BrowseSort.SIZE))
        assertEquals(listOf("folder", "large.mp4", "episode10.mp4", "episode2.mp4", "unknown.mp4"), sort(items, BrowseSort.SIZE, true))
    }
    @Test fun extensionsIgnoreCaseAndSortBothDirections() {
        val items = listOf(Entry("video.MP4"), Entry("episode2.mkv"), Entry("audio.mp3"))
        assertEquals(listOf("episode2.mkv", "audio.mp3", "video.MP4"), sort(items, BrowseSort.TYPE))
        assertEquals(listOf("video.MP4", "audio.mp3", "episode2.mkv"), sort(items, BrowseSort.TYPE, true))
    }
    @Test fun unknownSizesStillUseDeterministicNaturalNameOrder() {
        val items = listOf(Entry("episode10.mp4"), Entry("episode2.mp4"), Entry("known.mp4", size = 1))
        assertEquals(listOf("known.mp4", "episode2.mp4", "episode10.mp4"), sort(items, BrowseSort.SIZE))
        assertEquals(listOf("known.mp4", "episode10.mp4", "episode2.mp4"), sort(items, BrowseSort.SIZE, true))
    }
}
