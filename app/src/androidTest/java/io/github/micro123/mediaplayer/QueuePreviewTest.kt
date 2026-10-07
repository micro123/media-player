package io.github.micro123.mediaplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.micro123.mediaplayer.core.*
import io.github.micro123.mediaplayer.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QueuePreviewTest {
    @Test fun localCoversVideoFramesAndFactsSurviveMemoryCacheAndProcessReload() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "queue-preview-fixture-${System.nanoTime()}").apply { mkdirs() }
        val cache = File(root, "thumbnails")
        try {
            fun fixture(asset: String, video: Boolean): MediaItem {
                val file = File(root, asset)
                instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
                return MediaItem(android.net.Uri.fromFile(file).toString(), asset, if (video) "video/mp4" else "audio/mpeg", file.length())
            }
            val music = fixture("music-tags-test.mp3", false)
            val video = fixture("feature-test-video.mp4", true)
            val source = AndroidPlaybackSourceResolver(context)
            val repository = QueuePreviewRepository(AudioMetadataRepository(source), VideoPreviewRepository(source), cache)
            val audio = repository.read(music)
            assertNotNull(audio.artwork); assertTrue(audio.durationMs >= 44000); assertTrue(audio.subtitle.isNotBlank()); assertTrue(audio.bitrate > 0)
            assertEquals(music.sizeBytes, audio.sizeBytes); assertSame(audio, repository.read(music))
            val picture = repository.read(video)
            assertNotNull(picture.artwork); assertTrue(picture.durationMs >= 39000); assertTrue(picture.width > 0 && picture.height > 0)
            assertEquals(video.sizeBytes, picture.sizeBytes)
            val unreadable = PlaybackSourceResolver { error("Cache hit must not open media again") }
            val reloaded = QueuePreviewRepository(AudioMetadataRepository(unreadable), VideoPreviewRepository(unreadable), cache)
            val cachedAudio = reloaded.read(music)
            assertNotNull(cachedAudio.artwork); assertEquals(audio.durationMs, cachedAudio.durationMs); assertEquals(audio.subtitle, cachedAudio.subtitle)
            val cachedVideo = reloaded.read(video)
            assertNotNull(cachedVideo.artwork); assertEquals(picture.width, cachedVideo.width); assertEquals(picture.height, cachedVideo.height)
            assertTrue(cache.listFiles().orEmpty().all { it.name.matches(Regex("[a-f0-9]{64}\\.(json|thumb)")) })
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptedDiskArtworkIsRebuiltAndUnavailableMediaNeverBlocksQueue() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "queue-preview-recovery-${System.nanoTime()}").apply { mkdirs() }
        try {
            val file = File(root, "audio.mp3")
            instrumentation.context.assets.open("music-tags-test.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
            val media = MediaItem(android.net.Uri.fromFile(file).toString(), "audio.mp3", "audio/mpeg", file.length())
            val cache = File(root, "thumbnails")
            val resolver = AndroidPlaybackSourceResolver(context)
            fun repository() = QueuePreviewRepository(AudioMetadataRepository(resolver), VideoPreviewRepository(resolver), cache)
            assertNotNull(repository().read(media).artwork)
            cache.listFiles()!!.single { it.extension == "json" }.writeText("invalid cache metadata")
            assertNotNull(repository().read(media).artwork)
            val http = repository().read(MediaItem("https://fixture.invalid/radio.mp3", "radio.mp3", "audio/mpeg", null))
            assertNull(http.artwork); assertEquals(0, http.durationMs); assertEquals("MP3", queueMediaInfo(media.copy(sizeBytes = null), http))
        } finally { root.deleteRecursively() }
    }
}
