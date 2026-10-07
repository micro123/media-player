package io.github.micro123.mediaplayer

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.micro123.mediaplayer.core.*
import io.github.micro123.mediaplayer.data.*
import io.github.micro123.mediaplayer.data.navidrome.*
import io.github.micro123.mediaplayer.data.network.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Isolated music fixtures, plus an optional live configuration supplied only on the test device. */
@RunWith(AndroidJUnit4::class)
class NavidromeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as PlayerApplication).container

    private fun profile(server: String, username: String = "fixture-user", password: String = "fixture-secret"): NetworkProfile = runBlocking {
        val root = NavidromeAddress.fromServer(server)
        val profile = NetworkProfile(root.profileId, root.address, NetworkCredentials(false, username, password))
        container.network.credentials.save(profile)
        container.bookmarks.save(SavedBookmark(profile.id, "navidrome-fixture", BookmarkKind.LOCATION, root.address))
        profile
    }
    private fun remove(profile: NetworkProfile) = runBlocking { container.bookmarks.remove(profile.id); container.network.credentials.remove(profile.id) }

    @Test fun libraryPaginationServerSearchOrderAndEncryptedBookmarks() = runBlocking {
        FixtureServer().use { server ->
            val profile = profile(server.address)
            var folder: NetworkProfile? = null
            try {
                val root = NavidromeAddress.parse(profile.address)
                val nav = container.navidrome
                assertEquals(listOf("专辑", "歌手", "全部歌曲", "服务器播放列表"), nav.list(root.address).map { it.name })
                val albums = nav.list(root.at("albums", "0"))
                assertEquals(101, albums.size); assertEquals("下一页", albums.last().name)
                val page2 = nav.list(albums.last().address)
                assertEquals(listOf("上一页", "专辑 100"), page2.map { it.name })
                assertEquals(2, nav.list(root.at("artists")).size)
                assertEquals(1, nav.list(root.at("artist", "artist-1")).size)
                val album = nav.list(root.at("album", "album-0"))
                assertEquals(listOf("曲目 B", "曲目 A"), album.map { it.name })
                val playlists = nav.list(root.at("playlists"))
                val playlist = nav.list(playlists.single().address)
                assertEquals(listOf("曲目 A", "曲目 B", "曲目 A"), playlist.map { it.name })
                val search = nav.list(root.at("search", "曲目 & + /", "0"))
                assertTrue(search.any { it.media != null }); assertTrue(server.correctSearch)
                val songs = nav.list(root.at("songs", "0"))
                assertEquals(100, songs.count { it.media != null }); assertEquals("下一页", songs.last().name)
                val media = nav.resolve(album.first().address)
                assertEquals(MediaSourceKind.NAVIDROME, media.sourceKind); assertFalse(media.isVideo)
                val m3u = M3uCodec.write(listOf(M3uEntry(media.uri, media.displayName)))
                assertFalse(m3u.contains(profile.credentials.password)); assertFalse(m3u.contains("&t=")); assertFalse(m3u.contains("/rest/"))
                val encrypted = context.getSharedPreferences("network_credentials", 0).getString(profile.id, "")!!
                assertFalse(encrypted.contains(profile.credentials.password)); assertFalse(encrypted.contains(profile.credentials.username))
                val pin = SavedBookmark(name = "navidrome-folder-fixture", kind = BookmarkKind.FOLDER, address = root.at("album", "album-0"))
                folder = profile.copy(id = pin.id)
                container.network.credentials.save(folder!!); container.bookmarks.save(pin)
                remove(profile)
                assertEquals(2, nav.list(pin.address).size) // Folder bookmark retains its encrypted account after removing the location.
            } finally { remove(profile); folder?.let { remove(it) } }
        }
    }

    @Test fun authenticationErrorsRedirectsAndBoundsDoNotLeakCredentials() {
        FixtureServer().use { server ->
            val profile = profile(server.address)
            try {
                val client = NavidromeClient(profile)
                assertEquals("ok", client.request("ping").getString("status"))
                val first = client.url("stream", mapOf("id" to "song &/?+"))
                val second = client.url("stream", mapOf("id" to "song &/?+"))
                assertNotEquals(first, second)
                assertFalse(first.contains(profile.credentials.password))
                val invalid = NavidromeClient(profile.copy(credentials = profile.credentials.copy(password = "wrong")))
                val auth = runCatching { invalid.request("ping") }.exceptionOrNull()
                assertTrue(auth is RemoteAccessException); assertEquals(NetworkFailureKind.AUTHENTICATION, (auth as RemoteAccessException).kind)
                assertFalse(auth.message.orEmpty().contains("fixture-secret")); assertFalse(auth.message.orEmpty().contains("/rest/"))
                for (endpoint in listOf("redirect", "oversized", "malformed")) {
                    val error = runCatching { client.request(endpoint) }.exceptionOrNull()
                    assertTrue(endpoint, error is RemoteAccessException)
                    assertFalse(error!!.message.orEmpty().contains("fixture-secret"))
                }
                assertTrue(server.authenticatedRequests.get() > 0)
            } finally { remove(profile) }
        }
    }

    @Test fun musicCoverTagsNativeStreamingAndSeek() = runBlocking {
        FixtureServer().use { server ->
            val profile = profile(server.address)
            try {
                val root = NavidromeAddress.parse(profile.address)
                val media = container.navidrome.resolve(root.at("song", "song-a"))
                val tags = container.audioMetadata.read(media)
                assertEquals("曲目 A", tags.title); assertEquals("测试歌手", tags.artist); assertEquals("测试专辑", tags.album)
                assertEquals("2026", tags.year); assertEquals("1", tags.track); assertEquals(128000, tags.bitrate)
                assertNotNull(tags.cover)
                playAndSeek(media)
                assertTrue(server.streamRequests.get() > 0)
            } finally { remove(profile) }
        }
    }

    @Test fun viewModelSavesServerNavigatesPagesRestoresBookmarksAndPreservesPlaylistRepeats() = runBlocking {
        FixtureServer().use { server ->
            val store = PlaybackStore(context)
            val oldQueue = store.readQueue()
            val oldRecent = container.mediaRepository.readRecent()
            val root = NavidromeAddress.fromServer(server.address)
            val models = androidx.lifecycle.ViewModelStore()
            var vm: io.github.micro123.mediaplayer.ui.PlayerViewModel? = null
            val states = kotlinx.coroutines.flow.MutableStateFlow(PlaybackState())
            val engine = object : PlaybackEngine {
                override val state = states
                override fun load(media: MediaItem, startPositionMs: Long) { states.value = PlaybackState(media = media,
                    status = PlaybackStatus.PLAYING, positionMs = startPositionMs, durationMs = 45000, seekable = true) }
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
            val folderName = "navidrome-folder-${System.nanoTime()}"
            try {
                instrumentation.runOnMainSync {
                    vm = io.github.micro123.mediaplayer.ui.PlayerViewModel(container.mediaRepository, engine, androidx.lifecycle.SavedStateHandle(),
                        container.mediaBrowser, store, container.bookmarks, container.playlists, container.clips, container.network,
                        container.audioMetadata, navidrome = container.navidrome)
                    models.put("navidrome-fixture", requireNotNull(vm))
                }
                val player = requireNotNull(vm)
                instrumentation.runOnMainSync { player.saveNetworkLocation("Navidrome fixture", root.address, root.profileId,
                    NetworkCredentials(false, "fixture-user", "fixture-secret"), true) }
                await("server saved and root browsed") { player.remoteBrowser.value?.entries?.size == 4 && player.remoteBrowser.value?.loading == false }
                instrumentation.runOnMainSync { player.openRemoteEntry(player.remoteBrowser.value!!.entries.first { it.name == "全部歌曲" }) }
                await("first songs page") { player.remoteBrowser.value?.entries?.lastOrNull()?.name == "下一页" }
                instrumentation.runOnMainSync { player.openRemoteEntry(player.remoteBrowser.value!!.entries.last()) }
                await("second songs page") { player.remoteBrowser.value?.entries?.size == 2 && player.remoteBrowser.value?.entries?.firstOrNull()?.name == "上一页" }
                instrumentation.runOnMainSync { player.upRemote() }
                await("page navigation returns to category root") { player.remoteBrowser.value?.current == root.address && player.remoteBrowser.value?.loading == false }
                instrumentation.runOnMainSync { player.searchNavidrome("曲目 & + /") }
                await("global server search") { player.remoteBrowser.value?.entries?.any { it.media != null } == true && player.remoteBrowser.value?.loading == false }
                instrumentation.runOnMainSync { player.upRemote() }
                await("search back to root") { player.remoteBrowser.value?.current == root.address && player.remoteBrowser.value?.loading == false }
                instrumentation.runOnMainSync { player.openRemoteEntry(player.remoteBrowser.value!!.entries.first { it.name == "服务器播放列表" }) }
                await("server playlist list") { player.remoteBrowser.value?.entries?.singleOrNull()?.name == "测试列表" }
                instrumentation.runOnMainSync { player.openRemoteEntry(player.remoteBrowser.value!!.entries.single()) }
                await("server playlist songs") { player.remoteBrowser.value?.entries?.size == 3 }
                instrumentation.runOnMainSync { player.openRemoteEntry(player.remoteBrowser.value!!.entries.last()) }
                await("repeated song preserves occurrence in queue") { player.queue.value.items.size == 3 && player.queue.value.index == 2 && player.playback.value.media != null }
                assertFalse(player.playback.value.media!!.isVideo)
                await("server tags in music player") { player.audioMetadata.value.title == "曲目 A" && player.audioMetadata.value.cover != null }
                instrumentation.runOnMainSync { player.saveCurrentFolderBookmark(folderName) }
                await("server view bookmark saved") { container.bookmarks.items.value.any { it.name == folderName } }
                val bookmark = container.bookmarks.items.value.first { it.name == folderName }
                assertNotNull(container.network.credentials.read(bookmark.id))
                instrumentation.runOnMainSync { player.showFileLocations(); player.openBookmark(bookmark) }
                await("view bookmark reopens server playlist") { player.remoteBrowser.value?.current == bookmark.address && player.remoteBrowser.value?.entries?.size == 3 }
                instrumentation.runOnMainSync { player.playRemotePage() }
                await("play whole server playlist") { player.queue.value.items.size == 3 && player.queue.value.index == 0 }
                instrumentation.runOnMainSync { engine.seekTo(12000); player.stopPlayback() }
                await("progress saved by stable identity") { runBlocking { store.readBookmark(root.at("song", "song-a")) }?.positionMs == 12000L }
            } finally {
                instrumentation.runOnMainSync { vm?.stopPlayback(); models.clear() }
                store.writeQueue(oldQueue); container.mediaRepository.writeRecent(oldRecent)
                store.writeBookmark(root.at("song", "song-a"), 0, 0)
                store.writeBookmark(root.at("song", "song-b"), 0, 0)
                for (bookmark in container.bookmarks.items.value.filter { it.id == root.profileId || it.name == folderName }) {
                    container.bookmarks.remove(bookmark.id); container.network.credentials.remove(bookmark.id)
                }
            }
        }
    }

    @Test fun liveServerBrowseCoverAndPlaybackWhenConfigured() = runBlocking {
        val configName = InstrumentationRegistry.getArguments().getString("navidromeLiveConfig")
        assumeTrue("Optional live Navidrome configuration not supplied", configName != null)
        require(configName == "navidrome-live-test.json")
        val file = java.io.File(context.filesDir, configName!!)
        try {
            val config = JSONObject(file.readText())
            val profile = profile(config.getString("server"), config.getString("username"), config.getString("password"))
            try {
                val root = NavidromeAddress.parse(profile.address)
                val nav = container.navidrome
                assertEquals(4, nav.list(root.address).size)
                assertTrue(nav.list(root.at("artists")).isNotEmpty())
                nav.list(root.at("playlists"))
                val album = nav.list(root.at("albums", "0")).first { it.directory && it.name !in setOf("下一页", "上一页") }
                val song = nav.list(album.address).first { it.media != null }
                val media = nav.resolve(song.address)
                val tags = container.audioMetadata.read(media)
                assertTrue(tags.title.isNotBlank()); assertTrue(tags.artist.isNotBlank()); assertNotNull(tags.cover)
                assertTrue(nav.list(root.at("search", tags.title, "0")).any { it.media != null })
                playAndSeek(media)
            } finally { remove(profile) }
        } finally { file.delete() }
    }

    private fun playAndSeek(media: MediaItem) {
        var engine: MpvPlaybackEngine? = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            instrumentation.runOnMainSync { engine = container.createPlaybackEngine() as MpvPlaybackEngine; engine!!.load(media, 2000) }
            val player = requireNotNull(engine)
            await("Navidrome audio playback") { player.state.value.status == PlaybackStatus.PLAYING && player.state.value.seekable && player.state.value.positionMs >= 1800 }
            instrumentation.runOnMainSync { player.pause(); player.seekTo(8000) }
            await("Navidrome forward seek") { player.state.value.status == PlaybackStatus.PAUSED && player.state.value.positionMs in 7500..8500 }
            instrumentation.runOnMainSync { player.seekTo(1000) }
            await("Navidrome reverse seek") { player.state.value.positionMs in 500..1500 }
            instrumentation.runOnMainSync { player.stop() }
            await("Navidrome stopped") { player.state.value.media == null }
        } finally { instrumentation.runOnMainSync { engine?.release() }; scenario.close() }
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        assertTrue(label, condition())
    }

    private inner class FixtureServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newFixedThreadPool(4)
        private val listener = Thread {
            while (!socket.isClosed) try { val client = socket.accept(); workers.execute { serve(client) } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val address = "http://127.0.0.1:${socket.localPort}/music"
        val authenticatedRequests = AtomicInteger()
        val streamRequests = AtomicInteger()
        @Volatile var correctSearch = false
        private val audio by lazy { instrumentation.context.assets.open("music-tags-test.mp3").use { it.readBytes() } }
        private val cover by lazy { ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }.toByteArray() }
        private fun song(id: String) = JSONObject().apply {
            put("id", id); put("title", if (id == "song-b") "曲目 B" else "曲目 A"); put("artist", "测试歌手"); put("album", "测试专辑")
            put("albumArtist", "测试歌手"); put("year", 2026); put("track", if (id == "song-b") 2 else 1); put("genre", "测试音乐")
            put("bitRate", 128); put("contentType", "audio/mpeg"); put("duration", 45); put("coverArt", "cover-1"); put("size", audio.size)
        }
        private fun album(index: Int) = JSONObject().apply { put("id", "album-$index"); put("name", "专辑 $index"); put("artist", "测试歌手"); put("songCount", 2) }
        private fun serve(client: Socket) = client.use {
            try {
                it.soTimeout = 10_000
                val reader = it.getInputStream().bufferedReader()
                val first = reader.readLine() ?: return@use
                val uri = URI(first.split(' ')[1])
                val headers = mutableMapOf<String, String>()
                while (headers.size < 100) {
                    val line = reader.readLine().orEmpty(); if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                // BufferedReader cannot be used to consume a request body; fixture requests are GET only.
                val params = uri.rawQuery.orEmpty().split('&').associate { param ->
                    URLDecoder.decode(param.substringBefore('='), "UTF-8") to URLDecoder.decode(param.substringAfter('=', ""), "UTF-8")
                }
                val authorized = params["u"] == "fixture-user" && params["t"] == NavidromeClient.token("fixture-secret", params["s"].orEmpty()) && "p" !in params
                val endpoint = uri.path.substringAfterLast('/').substringBefore('.')
                fun reply(bytes: ByteArray, type: String = "application/json", status: String = "200 OK", extra: String = "", advertised: Int = bytes.size) {
                    val output = it.getOutputStream()
                    output.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: $advertised\r\nConnection: close\r\n$extra\r\n".toByteArray())
                    output.write(bytes); output.flush()
                }
                if (!authorized) {
                    reply("{\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":40,\"message\":\"fixture-secret private server details\"}}}".toByteArray())
                    return@use
                }
                authenticatedRequests.incrementAndGet()
                when (endpoint) {
                    "stream" -> {
                        streamRequests.incrementAndGet()
                        val start = headers["range"]?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        val bytes = audio.copyOfRange(start.coerceIn(0, audio.size), audio.size)
                        reply(bytes, "audio/mpeg", if (start > 0) "206 Partial Content" else "200 OK", "Accept-Ranges: bytes\r\n" +
                            if (start > 0) "Content-Range: bytes $start-${audio.lastIndex}/${audio.size}\r\n" else "")
                    }
                    "getCoverArt" -> reply(cover, "image/png")
                    "redirect" -> reply(ByteArray(0), status = "302 Found", extra = "Location: http://other.invalid/music/rest/ping.view\r\n")
                    "oversized" -> reply(ByteArray(0), advertised = 10 * 1024 * 1024)
                    "malformed" -> reply("not JSON".toByteArray())
                    else -> {
                        val response = JSONObject().put("status", "ok").put("version", "1.16.1").put("type", "navidrome")
                        when (endpoint) {
                            "getAlbumList2" -> response.put("albumList2", JSONObject().put("album", JSONArray().apply {
                                val offset = params["offset"]?.toInt() ?: 0
                                for (index in offset until minOf(101, offset + (params["size"]?.toInt() ?: 101))) put(album(index))
                            }))
                            "getArtists" -> response.put("artists", JSONObject().put("index", JSONArray().put(JSONObject().put("artist", JSONArray()
                                .put(JSONObject().put("id", "artist-1").put("name", "测试歌手"))
                                .put(JSONObject().put("id", "artist-2").put("name", "歌手 2"))))))
                            "getArtist" -> response.put("artist", JSONObject().put("album", JSONArray().put(album(0))))
                            "getAlbum" -> response.put("album", JSONObject().put("song", JSONArray().put(song("song-b")).put(song("song-a"))))
                            "getPlaylists" -> response.put("playlists", JSONObject().put("playlist", JSONArray().put(JSONObject().put("id", "playlist-1").put("name", "测试列表").put("songCount", 3))))
                            "getPlaylist" -> response.put("playlist", JSONObject().put("entry", JSONArray().put(song("song-a")).put(song("song-b")).put(song("song-a"))))
                            "getSong" -> response.put("song", song(params["id"]!!))
                            "search3" -> {
                                correctSearch = params["query"] == "曲目 & + /"
                                val offset = params["songOffset"]?.toInt() ?: 0
                                response.put("searchResult3", JSONObject().put("song", JSONArray().apply {
                                    if (params["query"].isNullOrEmpty()) for (index in offset until minOf(101, offset + 101)) put(song("song-$index"))
                                    else put(song("song-a"))
                                }))
                            }
                        }
                        reply(JSONObject().put("subsonic-response", response).toString().toByteArray())
                    }
                }
            } catch (_: Exception) { }
        }
        override fun close() { socket.close(); workers.shutdownNow(); listener.join(1000) }
    }
}
