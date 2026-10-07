package io.github.micro123.mediaplayer.data

import io.github.micro123.mediaplayer.core.MediaItem
import io.github.micro123.mediaplayer.core.PlaybackQueue
import org.junit.Assert.*
import org.junit.Test

class QueueMediaInfoTest {
    @Test fun mediaFactsShowOnlyAvailableFields() {
        val audio = MediaItem("file:///music.flac", "music.flac", "audio/flac", null)
        assertEquals("01:05 · FLAC · 320 kbps · 1.5 MB", queueMediaInfo(audio, QueuePreview(durationMs = 65000, bitrate = 320000, sizeBytes = 1572864)))
        val video = MediaItem("smb://fixture/video.mp4", "video.mp4", "video/mp4", 12L * 1024 * 1024)
        assertEquals("00:40 · MP4 · 1920×1080 · 12.0 MB", queueMediaInfo(video, QueuePreview(durationMs = 40000, width = 1920, height = 1080)))
        assertEquals("MP4 · 12.0 MB", queueMediaInfo(video, QueuePreview()))
        assertEquals("1:00:00 · FLAC", queueMediaInfo(audio, QueuePreview(durationMs = 3600000)))
    }
    @Test fun reorderingPreservesPlayingOccurrenceWhenUrisRepeat() {
        val a = MediaItem("file:///a.mp3", "a", "audio/mpeg", null)
        val b = MediaItem("file:///b.mp3", "b", "audio/mpeg", null)
        val queue = PlaybackQueue(listOf(a, b, a), 2)
        assertEquals(2, queue.move(0, 1).index)
        assertEquals(0, queue.move(2, 0).index)
        assertEquals(1, queue.move(0, 1).move(0, 2).index)
        assertEquals(-1, queue.copy(index = -1).move(0, 2).index)
        assertSame(queue, queue.move(-1, 1))
    }
}
