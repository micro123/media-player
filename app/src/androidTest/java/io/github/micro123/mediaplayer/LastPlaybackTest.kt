package io.github.micro123.mediaplayer

import android.net.Uri
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.micro123.mediaplayer.core.*
import io.github.micro123.mediaplayer.data.*
import io.github.micro123.mediaplayer.ui.PlayerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Fake providers count opens. Restoring/displaying the saved entry must perform none. */
@RunWith(AndroidJUnit4::class)
class LastPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as PlayerApplication).container

    @Test fun directorySelectionIncludesFilteredSiblingsAndRestoresQueueIndexAndProgress() = runBlocking {
        Assume.assumeTrue("Directory test uses the already granted all-files access", container.mediaBrowser.access().allFiles)
        val folder = File(context.getExternalFilesDir(null), "replay-fixture-${System.nanoTime()}").apply { mkdirs() }
        val originals = Originals()
        val models = mutableListOf<Harness>()
        try {
            listOf("episode-10.mp4", "episode-2.mp4", "song.mp3").forEach { File(folder, it).writeText("fake-engine-only") }
            val first = Harness(MediaSourceKind.LOCAL).also { models += it }
            first.ready()
            main { first.player.setFileSort(BrowseSort.NAME, false); first.player.browseStorage() }
            await("storage root available") { !first.player.library.value.isLoading }
            main { first.player.enterFolder(FolderEntry(Uri.fromFile(folder).toString(), folder.name, folder.canonicalPath)) }
            await("self-owned directory listed") { first.player.library.value.entries.count { it.media != null } == 3 }
            val selected = first.player.library.value.entries.first { it.name == "episode-10.mp4" }.media!!
            // The UI may have filtered to just one result; the queue still includes the directory.
            main { first.player.playLocalDirectory(listOf(selected), 0) }
            await("complete directory queue") { first.player.queue.value.items.size == 3 && first.player.playback.value.status == PlaybackStatus.PLAYING }
            assertEquals(listOf("episode-2.mp4", "episode-10.mp4", "song.mp3"), first.player.queue.value.items.map { it.displayName })
            assertEquals(1, first.player.queue.value.index)
            assertEquals(1, first.opens.get())
            main { first.player.seekTo(12000); first.player.stopPlayback() }
            await("directory replay persisted") { runBlocking { PlaybackStore(context).readLastPlayback() }?.index == 1 }
            assertEquals(folder.name, PlaybackStore(context).readLastPlayback()!!.name)
            await("progress saved") { runBlocking { PlaybackStore(context).readBookmark(selected.uri) }?.positionMs == 12000L }
            first.close()
            val restarted = Harness(MediaSourceKind.LOCAL).also { models += it }
            restarted.ready()
            assertEquals(0, restarted.opens.get())
            assertEquals(0, restarted.listings.get())
            assertEquals(3, restarted.player.lastPlayback.value!!.items.size)
            main { restarted.player.replayLastPlayback() }
            await("quick replay starts saved item") { restarted.player.playback.value.status == PlaybackStatus.PLAYING }
            assertEquals(selected.uri, restarted.player.playback.value.media!!.uri)
            assertEquals(12000L, restarted.player.playback.value.positionMs)
            assertEquals(1, restarted.player.queue.value.index)
            assertEquals(1, restarted.opens.get())
            main { restarted.player.moveQueueItem(1, -1) }
            await("replay tracks reordered occurrence") { runBlocking { PlaybackStore(context).readLastPlayback() }?.index == 0 }
            assertEquals(selected.uri, PlaybackStore(context).readLastPlayback()!!.current!!.uri)
        } finally {
            models.forEach { it.close() }
            folder.listFiles().orEmpty().forEach { PlaybackStore(context).writeBookmark(Uri.fromFile(it.canonicalFile).toString(), 0, 0) }
            folder.deleteRecursively()
            originals.restore()
        }
    }

    @Test fun offlineLastQueueRemainsWithoutValidationAndOnlyLatestAttemptIsRetained() = runBlocking {
        val originals = Originals()
        val models = mutableListOf<Harness>()
        val media = List(3) { MediaItem("smb://replay-fixture.invalid/Series/episode-$it.mp4", "episode-$it.mp4", "video/mp4", null) }
        try {
            val snapshot = LastPlayback("离线网络目录", media, 1)
            PlaybackStore(context).writeLastPlayback(snapshot)
            val first = Harness(MediaSourceKind.SMB).also { models += it }
            first.offline = true
            first.ready()
            assertEquals(snapshot, first.player.lastPlayback.value)
            assertEquals(0, first.opens.get()); assertEquals(0, first.listings.get())
            main { first.player.replayLastPlayback() }
            await("selected file attempted only on replay") { first.opens.get() == 1 }
            assertEquals(0, first.listings.get())
            assertEquals(snapshot, PlaybackStore(context).readLastPlayback())
            assertEquals(3, first.player.queue.value.items.size)
            first.close()
            val restarted = Harness(MediaSourceKind.SMB).also { models += it }
            restarted.offline = true
            restarted.ready()
            assertEquals(snapshot, restarted.player.lastPlayback.value)
            assertEquals(0, restarted.opens.get()); assertEquals(0, restarted.listings.get())
            val latest = media[2]
            main { restarted.player.playList(listOf(latest)) }
            await("failed latest attempt replaces single record") { runBlocking { PlaybackStore(context).readLastPlayback() }?.current?.uri == latest.uri }
            assertEquals(listOf(latest), PlaybackStore(context).readLastPlayback()!!.items)
            assertEquals(1, restarted.opens.get()); assertEquals(0, restarted.listings.get())
        } finally {
            models.forEach { it.close() }
            media.forEach { PlaybackStore(context).writeBookmark(it.uri, 0, 0) }
            originals.restore()
        }
    }

    private inner class Originals {
        private val store = PlaybackStore(context)
        private val preferences = store.readPreferences()
        private val queue = runBlocking { store.readQueue() }
        private val replay = runBlocking { store.readLastPlayback() }
        private val recent = runBlocking { container.mediaRepository.readRecent() }
        suspend fun restore() {
            Thread.sleep(200)
            store.writePreferences(preferences); store.writeQueue(queue); store.writeLastPlayback(replay)
            container.mediaRepository.writeRecent(recent)
        }
    }

    private inner class Harness(kind: MediaSourceKind) {
        val opens = AtomicInteger()
        val listings = AtomicInteger()
        @Volatile var offline = false
        private val holder = ViewModelStore()
        private val states = MutableStateFlow(PlaybackState())
        private val engine = object : PlaybackEngine {
            override val state = states
            override fun load(media: MediaItem, startPositionMs: Long) { states.value = PlaybackState(media, PlaybackStatus.PLAYING, startPositionMs, 40000, seekable = true) }
            override fun play() { states.value = states.value.copy(status = PlaybackStatus.PLAYING) }
            override fun pause() { states.value = states.value.copy(status = PlaybackStatus.PAUSED) }
            override fun seekTo(positionMs: Long) { states.value = states.value.copy(positionMs = positionMs) }
            override fun stop() { states.value = PlaybackState() }
            override fun release() = stop()
            override fun setSpeed(speed: Double) {}
            override fun setVideoAspectRatio(ratio: Double) {}
            override fun attachSurface(surface: Surface?) {}
            override fun updateSurfaceSize(width: Int, height: Int) {}
        }
        private val provider = object : MediaSourceProvider {
            override val kind = kind
            override val capabilities = SourceCapabilities(true, true)
            override suspend fun resolve(address: String): MediaItem {
                opens.incrementAndGet()
                check(!offline) { "fixture offline" }
                return MediaItem(address, Uri.parse(address).lastPathSegment.orEmpty(), mimeFromName(address), null)
            }
            override suspend fun list(address: String): List<SourceEntry> { listings.incrementAndGet(); error("Replay must never enumerate a directory") }
        }
        val player: PlayerViewModel
        init {
            val repository = MediaRepository(context, listOf(provider))
            var model: PlayerViewModel? = null
            main {
                model = PlayerViewModel(repository, engine, SavedStateHandle(), container.mediaBrowser, PlaybackStore(context), container.bookmarks,
                    PlaylistRepository(context, repository, container.network), container.clips)
                holder.put("replay", requireNotNull(model))
            }
            player = requireNotNull(model)
        }
        fun ready() = await("model restores without validating media") { !player.library.value.isLoading }
        fun close() = main { player.stopPlayback(); holder.clear() }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(label: String, predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < until) { if (predicate()) return; Thread.sleep(80) }
        assertTrue(label, predicate())
    }
}
