package com.tang.player.data

import com.tang.player.core.MediaItem
import com.tang.player.core.OpenedMediaSource
import com.tang.player.core.PlaybackSourceResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioMetadataRepositoryTest {
    @Test fun liveHttpMetadataDoesNotOpenAnotherConnection() = runBlocking {
        val repository = AudioMetadataRepository(PlaybackSourceResolver { error("No second HTTP connection should be opened") })
        val tags = repository.read(MediaItem("https://fixture.invalid/radio.mp3", "测试电台", "audio/mpeg", null))
        assertEquals("测试电台", tags.title); assertNull(tags.cover)
    }
    @Test fun inaccessibleMediaReturnsSafeFilenameFallback() = runBlocking {
        val repository = AudioMetadataRepository(PlaybackSourceResolver { throw IllegalStateException("private-server-information") })
        val tags = repository.read(MediaItem("smb://fixture/Audio/song.mp3", "song.mp3", "audio/mpeg", null))
        assertEquals("song.mp3", tags.title); assertEquals("", tags.artist); assertNull(tags.cover)
    }
    @Test fun unsupportedDescriptorStillClosesItsSource() = runBlocking {
        var closed = 0
        val repository = AudioMetadataRepository(PlaybackSourceResolver { OpenedMediaSource("unsupported") { closed++ } })
        repository.read(MediaItem("file:///fixture/song.mp3", "song.mp3", "audio/mpeg", null))
        assertEquals(1, closed)
    }
}
