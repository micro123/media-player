package io.github.micro123.mediaplayer

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.micro123.mediaplayer.core.*
import io.github.micro123.mediaplayer.data.*
import io.github.micro123.mediaplayer.data.network.*
import io.github.micro123.mediaplayer.ui.PlayerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileDescriptor
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.flow.MutableStateFlow

@RunWith(AndroidJUnit4::class)
class NetworkSourcesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as PlayerApplication).container
    private val ownedBookmarks = mutableListOf<String>()
    private fun profile(address: String, credentials: NetworkCredentials = NetworkCredentials()): NetworkProfile = runBlocking {
        val bookmark = SavedBookmark(name = "network-test-${System.nanoTime()}", kind = BookmarkKind.LOCATION, address = address)
        ownedBookmarks += bookmark.id
        container.network.credentials.save(NetworkProfile(bookmark.id, address, credentials))
        container.bookmarks.save(bookmark)
        NetworkProfile(bookmark.id, address, credentials)
    }
    @org.junit.After fun cleanup() = runBlocking {
        ownedBookmarks.forEach { container.bookmarks.remove(it); container.network.credentials.remove(it) }
        // A caller may remove a precisely identified orphan from an earlier fixture UI run.
        InstrumentationRegistry.getArguments().getString("cleanupTestCredentialId")?.let { id ->
            val profile = container.network.credentials.read(id)
            if (profile != null) {
                check(profile.address == "smb://test-nas/share" && profile.credentials.guest &&
                    container.bookmarks.items.value.none { it.id == id }) { "Refusing to remove a non-fixture profile" }
                container.network.credentials.remove(id)
            }
        }
        Unit // JUnit lifecycle methods must have a JVM void return type.
    }
    @Test fun smbAuthenticationCryptoAndClientWorkOnAndroid() {
        val provider = com.hierynomus.security.bc.BCSecurityProvider()
        val digest = provider.getDigest("MD4")
        digest.update("password".toByteArray(Charsets.UTF_16LE))
        assertEquals("8846f7eaee8fb117ad06bdd830b7586c", digest.digest().joinToString("") { "%02x".format(it) })
        val config = com.hierynomus.smbj.SmbConfig.builder().withSecurityProvider(provider)
            .withAuthenticators(com.hierynomus.smbj.auth.NtlmAuthenticator.Factory()).build()
        assertEquals(1, config.supportedAuthenticators.size)
        com.hierynomus.smbj.SMBClient(config).use { assertNotNull(it.serverList) }
    }
    @Test fun credentialsAreEncryptedAndUpdateDeleteRoundTrip() {
        val password = "secret-${System.nanoTime()}"
        val saved = profile("smb://player-fixture.invalid/Private", NetworkCredentials(false, "test-user", password, "TEST"))
        val loaded = container.network.credentials.read(saved.id)!!
        assertEquals(password, loaded.credentials.password); assertEquals("TEST", loaded.credentials.domain)
        val prefs = context.getSharedPreferences("network_credentials", 0).all.toString()
        assertFalse(prefs.contains(password)); assertFalse(prefs.contains("test-user")); assertFalse(prefs.contains("player-fixture.invalid"))
        container.network.credentials.save(saved.copy(credentials = saved.credentials.copy(password = "changed")))
        assertEquals("changed", container.network.credentials.read(saved.id)!!.credentials.password)
        container.network.credentials.remove(saved.id); assertNull(container.network.credentials.read(saved.id))
    }
    private class BytesHandle(private val bytes: ByteArray, private val closed: AtomicInteger,
        val offsets: MutableList<Long> = CopyOnWriteArrayList(), private val shortReads: Boolean = true) : RemoteReadHandle {
        override val size = bytes.size.toLong()
        override fun read(offset: Long, bytes: ByteArray, bufferOffset: Int, length: Int): Int {
            offsets += offset
            if (offset >= size) return 0
            val count = minOf(length, (size - offset).toInt(), if (shortReads) 997 else Int.MAX_VALUE)
            this.bytes.copyInto(bytes, bufferOffset, offset.toInt(), offset.toInt() + count); return count
        }
        override fun close() { closed.incrementAndGet() }
    }
    @Test fun proxyDescriptorSupportsShortReadsSeekAndExactlyOnceClose() {
        val bytes = ByteArray(512 * 1024) { (it % 251).toByte() }; val closed = AtomicInteger()
        val resolver = RemotePlaybackSourceResolver(context, { BytesHandle(bytes, closed) })
        val opened = resolver.open(MediaItem("smb://fixture/Movies/pattern.bin", "pattern", "video/*", bytes.size.toLong()))
        val dup = ParcelFileDescriptor.fromFd(opened.input.substringAfter("fd://").toInt())
        try {
            val out = ByteArray(8192)
            assertEquals(8192, Os.pread(dup.fileDescriptor, out, 0, out.size, 262147))
            assertArrayEquals(bytes.copyOfRange(262147, 262147 + out.size), out)
            assertEquals(17, Os.pread(dup.fileDescriptor, out, 0, out.size, bytes.size.toLong() - 17))
            assertEquals(0, Os.pread(dup.fileDescriptor, out, 0, out.size, bytes.size.toLong()))
            assertEquals(71L, Os.lseek(dup.fileDescriptor, 71L, OsConstants.SEEK_SET))
            assertEquals(37, Os.read(dup.fileDescriptor, out, 0, 37))
            assertArrayEquals(bytes.copyOfRange(71, 108), out.copyOf(37))
        } finally { dup.close(); opened.close(); opened.close() }
        await("proxy close") { closed.get() == 1 }
    }
    @Test fun nativePlayerCanSeekAndResumeThroughRemoteDescriptor() {
        val closed = AtomicInteger(); val offsets = CopyOnWriteArrayList<Long>()
        val video = instrumentation.context.assets.open("feature-test-video.mp4").use { it.readBytes() }
        val resolver = RemotePlaybackSourceResolver(context, { BytesHandle(video, closed, offsets) })
        var engine: MpvPlaybackEngine? = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            instrumentation.runOnMainSync { engine = MpvPlaybackEngine(context, resolver) }
            val player = requireNotNull(engine)
            instrumentation.runOnMainSync { player.load(MediaItem("nfs://fixture/export/video.mp4", "remote fixture", "video/mp4", video.size.toLong()), 3000) }
            await("remote native playback") { player.state.value.status == PlaybackStatus.PLAYING && player.state.value.seekable && player.state.value.positionMs >= 2900 }
            instrumentation.runOnMainSync { player.pause(); player.seekTo(1000) }
            await("remote reverse seek") { player.state.value.status == PlaybackStatus.PAUSED && player.state.value.positionMs in 800..1300 }
            assertTrue(offsets.any { it > 0 })
            instrumentation.runOnMainSync { player.stop() }
            await("native proxy closed") { closed.get() == 1 }
        } finally { instrumentation.runOnMainSync { engine?.release() }; scenario.close() }
    }
    @Test fun providersFilterSortAndImportRemoteM3uRelativePaths() = runBlocking {
        val closed = AtomicInteger()
        val transport = object : RemoteTransport {
            override fun list(address: RemoteAddress, profile: NetworkProfile) = listOf(RemoteFileInfo("episode-10.mp4", false, 10),
                RemoteFileInfo("episode-2.mp4", false, 20), RemoteFileInfo("Season", true), RemoteFileInfo("notes.txt", false), RemoteFileInfo("list.m3u", false, 30))
            override fun stat(address: RemoteAddress, profile: NetworkProfile) = RemoteFileInfo(address.parts.last(), false, 42)
            override fun open(address: RemoteAddress, profile: NetworkProfile) = BytesHandle("#EXTM3U\n#EXTINF:-1,第一集\nepisode-2.mp4\nSeason/episode-10.mp4".toByteArray(), closed)
        }
        val network = NetworkRepository(context, container.bookmarks, mapOf(MediaSourceKind.SMB to transport, MediaSourceKind.NFS to transport))
        val repository = MediaRepository(context, network.providers)
        profile("nfs://player-fixture.invalid/export")
        for (root in listOf("smb://player-fixture.invalid/Movies", "nfs://player-fixture.invalid/export")) {
            val entries = repository.list(root)
            assertEquals(listOf("Season", "episode-2.mp4", "episode-10.mp4", "list.m3u"), entries.map { it.name })
            assertTrue(entries.first().directory); assertTrue(entries[1].media!!.isVideo)
            val imported = PlaylistRepository(context, repository, network).read("$root/list.m3u")
            assertEquals(2, imported.items.size); assertEquals("第一集", imported.items.first().displayName)
            assertEquals("$root/Season/episode-10.mp4", imported.items.last().uri)
        }
        assertEquals(2, closed.get())
    }
    @Test fun browserNavigationQueueStopsAndRetryPreserveDirectory() = runBlocking {
        val store = PlaybackStore(context)
        val oldQueue = store.readQueue(); val repositoryForRecent = MediaRepository(context)
        val oldRecent = repositoryForRecent.readRecent()
        var failList = false
        val transport = object : RemoteTransport {
            override fun list(address: RemoteAddress, profile: NetworkProfile): List<RemoteFileInfo> {
                if (failList) throw RemoteAccessException("fixture disconnected")
                return listOf(RemoteFileInfo("Season", true), RemoteFileInfo("episode-10.mp4", false), RemoteFileInfo("episode-2.mp4", false))
            }
            override fun stat(address: RemoteAddress, profile: NetworkProfile) = RemoteFileInfo(address.parts.last(), false, 42)
            override fun open(address: RemoteAddress, profile: NetworkProfile): RemoteReadHandle = error("fake engine does not read")
        }
        val network = NetworkRepository(context, container.bookmarks, mapOf(MediaSourceKind.SMB to transport))
        val repository = MediaRepository(context, network.providers)
        val states = MutableStateFlow(PlaybackState())
        val engine = object : PlaybackEngine {
            override val state = states
            override fun load(media: MediaItem, startPositionMs: Long) { states.value = PlaybackState(media = media, status = PlaybackStatus.PLAYING, positionMs = startPositionMs, durationMs = 10000, seekable = true) }
            override fun setSpeed(speed: Double) { }
            override fun setVideoAspectRatio(ratio: Double) { }
            override fun attachSurface(surface: android.view.Surface?) { }
            override fun updateSurfaceSize(width: Int, height: Int) { }
            override fun play() { states.value = states.value.copy(status = PlaybackStatus.PLAYING) }
            override fun pause() { states.value = states.value.copy(status = PlaybackStatus.PAUSED) }
            override fun seekTo(positionMs: Long) { states.value = states.value.copy(positionMs = positionMs) }
            override fun stop() { states.value = PlaybackState() }
            override fun release() { stop() }
        }
        val models = ViewModelStore(); var vm: PlayerViewModel? = null
        val root = "smb://player-fixture.invalid/Movies"
        val originalProfile = profile(root, NetworkCredentials(false, "folder-fixture-user", "folder-fixture-password"))
        val folderName = "folder-test-${System.nanoTime()}"
        val localFixture = java.io.File(context.getExternalFilesDir(null), "files-bookmark-${System.nanoTime()}")
        try {
            instrumentation.runOnMainSync {
                vm = PlayerViewModel(repository, engine, SavedStateHandle(), container.mediaBrowser, store, container.bookmarks,
                    PlaylistRepository(context, repository, network), container.clips, network)
                models.put("network-flow-test", requireNotNull(vm))
            }
            val player = requireNotNull(vm)
            await("fake model initialized") { !player.library.value.isLoading }
            instrumentation.runOnMainSync { repeat(2) { player.openNetwork(root) } }
            await("root listed") { player.remoteBrowser.value?.loading == false }
            assertEquals(1, player.selectedTab.value)
            assertEquals(io.github.micro123.mediaplayer.ui.FileView.NETWORK, player.fileView.value)
            instrumentation.runOnMainSync { player.openRemoteEntry(player.remoteBrowser.value!!.entries.first()) }
            await("nested listed") { player.remoteBrowser.value?.current == "$root/Season" && player.remoteBrowser.value?.loading == false }
            instrumentation.runOnMainSync { player.saveCurrentFolderBookmark(folderName) }
            await("folder bookmark persisted") { container.bookmarks.items.value.any { it.name == folderName } }
            val folderBookmark = container.bookmarks.items.value.single { it.name == folderName }
            ownedBookmarks += folderBookmark.id
            assertEquals(BookmarkKind.FOLDER, folderBookmark.kind)
            assertEquals("$root/Season", folderBookmark.address)
            assertEquals(originalProfile.credentials, network.credentials.read(folderBookmark.id)!!.credentials)
            assertEquals(root, network.credentials.read(folderBookmark.id)!!.address)
            assertEquals(folderBookmark, BookmarkRepository(context).items.value.single { it.id == folderBookmark.id })
            instrumentation.runOnMainSync { player.openRemoteEntry(player.remoteBrowser.value!!.entries.first { it.name == "episode-2.mp4" }) }
            await("fake media opened") { states.value.status == PlaybackStatus.PLAYING }
            assertEquals(listOf("episode-2.mp4", "episode-10.mp4"), player.queue.value.items.map { it.displayName })
            assertTrue(player.fullScreen.value)
            instrumentation.runOnMainSync { player.exitVideo() }
            assertEquals(PlaybackStatus.IDLE, states.value.status)
            assertEquals("$root/Season", player.remoteBrowser.value!!.current)
            assertEquals(1, player.selectedTab.value)
            failList = true
            instrumentation.runOnMainSync { player.refreshRemote() }
            await("disconnect reported") { player.remoteBrowser.value?.error != null }
            assertEquals("$root/Season", player.remoteBrowser.value!!.current)
            failList = false
            instrumentation.runOnMainSync { player.refreshRemote() }
            await("retry recovered") { player.remoteBrowser.value?.loading == false && player.remoteBrowser.value?.error == null }
            instrumentation.runOnMainSync { player.upRemote() }
            await("parent restored") { player.remoteBrowser.value?.current == root && player.remoteBrowser.value?.loading == false }
            instrumentation.runOnMainSync { player.upRemote() }
            assertNull(player.remoteBrowser.value)
            assertEquals(io.github.micro123.mediaplayer.ui.FileView.LOCATIONS, player.fileView.value)
            container.bookmarks.remove(originalProfile.id); container.network.credentials.remove(originalProfile.id)
            // Removing a server shortcut must not invalidate a separately saved folder's credentials.
            instrumentation.runOnMainSync { player.openBookmark(folderBookmark) }
            await("folder shortcut opened") { player.remoteBrowser.value?.current == folderBookmark.address && player.remoteBrowser.value?.loading == false }
            assertEquals(originalProfile.credentials, network.profileFor(folderBookmark.address).credentials)
            instrumentation.runOnMainSync { player.renameBookmark(folderBookmark, "$folderName-renamed") }
            await("folder renamed") { container.bookmarks.items.value.any { it.id == folderBookmark.id && it.name == "$folderName-renamed" } }
            assertNotNull(network.credentials.read(folderBookmark.id))
            instrumentation.runOnMainSync { player.upRemote() }
            assertEquals(io.github.micro123.mediaplayer.ui.FileView.LOCATIONS, player.fileView.value)
            if (container.mediaBrowser.access().allFiles) {
                check(localFixture.mkdir())
                java.io.File(localFixture, "local-fixture.mp4").outputStream().use { out ->
                    instrumentation.context.assets.open("feature-test-video.mp4").use { it.copyTo(out) }
                }
                val localBookmark = SavedBookmark(name = "local-$folderName", kind = BookmarkKind.FOLDER,
                    address = android.net.Uri.fromFile(localFixture).toString())
                container.bookmarks.save(localBookmark); ownedBookmarks += localBookmark.id
                instrumentation.runOnMainSync { player.openBookmark(localBookmark) }
                await("local bookmark opened") { player.library.value.folderPath.lastOrNull()?.documentId == localFixture.canonicalPath &&
                    !player.library.value.isLoading && player.library.value.entries.any { it.name == "local-fixture.mp4" } }
                assertEquals(io.github.micro123.mediaplayer.ui.FileView.LOCAL, player.fileView.value)
                assertEquals(localFixture.parentFile!!.canonicalPath, player.library.value.folderPath.dropLast(1).last().documentId)
                instrumentation.runOnMainSync { player.saveCurrentFolderBookmark("saved-local-$folderName") }
                await("local current folder saved") { container.bookmarks.items.value.any { it.name == "saved-local-$folderName" } }
                val savedLocal = container.bookmarks.items.value.single { it.name == "saved-local-$folderName" }
                ownedBookmarks += savedLocal.id
                assertEquals(localBookmark.address, savedLocal.address)
                assertEquals(savedLocal, BookmarkRepository(context).items.value.single { it.id == savedLocal.id })
                instrumentation.runOnMainSync { player.showFileLocations(); player.openBookmark(savedLocal) }
                await("saved local restored") { player.fileView.value == io.github.micro123.mediaplayer.ui.FileView.LOCAL && !player.library.value.isLoading }
            }
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            Thread.sleep(350)
            store.writeQueue(oldQueue); repositoryForRecent.writeRecent(oldRecent)
            store.writeBookmark("$root/Season/episode-2.mp4", 0, 0)
            localFixture.deleteRecursively()
        }
    }
    @Test fun realSambaAndNfsServerBrowseReadAndNativePlayback() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("networkTestHost")
        assumeTrue("temporary network test server not configured", host != null)
        // Explicit environment skip before touching persistent app data.
        try { Socket().use { it.connect(InetSocketAddress(host, 1445), 3000) } }
        catch (error: Exception) { assumeTrue("device socket unavailable: ${error.javaClass.simpleName}: ${error.message}", false) }
        for ((transport, root) in listOf(SmbTransport() to RemoteAddress.parse("smb://$host:1445/Private"),
            NfsTransport() to RemoteAddress.parse("nfs://$host:11111/fixtures"))) {
            val auth = if (root.kind == MediaSourceKind.SMB) NetworkCredentials(false, "player-test", "player-test-password") else NetworkCredentials()
            val profile = profile(root.address, auth)
            assertTrue(transport.list(root, profile).any { it.name == "中文 #02.mp4" })
            transport.open(RemoteAddress.parse(root.child("read-pattern.bin")), profile).use { file ->
                val bytes = ByteArray(4096); assertEquals(4096, file.read(1048707L, bytes, 0, bytes.size))
                assertEquals((1048707 % 256).toByte(), bytes.first())
            }
            val closed = AtomicInteger()
            val resolver = RemotePlaybackSourceResolver(context, { address ->
                val actual = transport.open(RemoteAddress.parse(address), profile)
                object : RemoteReadHandle by actual { override fun close() { actual.close(); closed.incrementAndGet() } }
            })
            val scenario = ActivityScenario.launch(MainActivity::class.java); var engine: MpvPlaybackEngine? = null
            try {
                instrumentation.runOnMainSync { engine = MpvPlaybackEngine(context, resolver); engine!!.load(MediaItem(root.child("episode-01.mp4"), "test server video", "video/mp4", null), 3000) }
                await("${root.kind} native streaming") { engine!!.state.value.status == PlaybackStatus.PLAYING && engine!!.state.value.positionMs >= 2900 }
                instrumentation.runOnMainSync { engine!!.pause(); engine!!.seekTo(1000) }
                await("${root.kind} native seek") { engine!!.state.value.status == PlaybackStatus.PAUSED && engine!!.state.value.positionMs in 800..1300 }
            } finally { instrumentation.runOnMainSync { engine?.release() }; scenario.close() }
            await("server media closed") { closed.get() == 1 }
            val folder = SavedBookmark(name = "server-folder-test-${System.nanoTime()}", kind = BookmarkKind.FOLDER,
                address = root.child("Season"))
            ownedBookmarks += folder.id
            runBlocking { container.network.credentials.save(profile.copy(id = folder.id)); container.bookmarks.save(folder)
                container.bookmarks.remove(profile.id); container.network.credentials.remove(profile.id) }
            val entries = runBlocking { container.mediaRepository.list(folder.address) }
            assertEquals("episode-02.mp4", entries.single().name)
            // NFS must still MOUNT /fixtures after opening a /fixtures/Season bookmark.
            assertEquals(root.address, container.network.profileFor(folder.address).address)
            container.network.open(entries.single().address).use { file ->
                assertTrue(file.size > 0); assertTrue(file.read(0, ByteArray(4096), 0, 4096) > 0)
            }
        }
    }
    @Test fun realRemoteAudioMetadataReadsCoverAndTags() = runBlocking {
        val host = InstrumentationRegistry.getArguments().getString("networkTestHost")
        assumeTrue("temporary network test server not configured", host != null)
        for (root in listOf(RemoteAddress.parse("smb://$host:1445/Private"), RemoteAddress.parse("nfs://$host:11111/fixtures"))) {
            profile(root.address, if (root.kind == MediaSourceKind.SMB) NetworkCredentials(false, "player-test", "player-test-password") else NetworkCredentials())
            val tags = container.audioMetadata.read(MediaItem(root.child("music-tags-test.mp3"), "remote song.mp3", "audio/mpeg", null))
            assertEquals("${root.kind} tags", "月下航行（测试）", tags.title)
            assertEquals("测试歌手", tags.artist); assertEquals("星河录", tags.album); assertNotNull("${root.kind} cover", tags.cover)
            assertTrue(maxOf(tags.cover!!.width, tags.cover.height) <= 768)
        }
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 15000
        while (android.os.SystemClock.elapsedRealtime() < until) { if (predicate()) return; Thread.sleep(80) }
        fail("Timed out: $label")
    }
}
