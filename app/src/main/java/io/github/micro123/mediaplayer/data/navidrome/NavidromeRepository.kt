package io.github.micro123.mediaplayer.data.navidrome

import io.github.micro123.mediaplayer.core.*
import io.github.micro123.mediaplayer.data.*
import io.github.micro123.mediaplayer.data.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class NavidromeAudioTags(val title: String, val artist: String, val album: String, val albumArtist: String,
    val year: String, val genre: String, val track: String, val bitrate: Long, val artwork: ByteArray?)

/** Browsing is read-only; playlists stay in their server order, queues carry stable song IDs. */
class NavidromeRepository(private val bookmarks: BookmarkRepository, private val vault: NetworkCredentialStore) : MediaSourceProvider {
    override val kind = MediaSourceKind.NAVIDROME
    override val capabilities = SourceCapabilities(playable = true, browsable = true)
    private val songs = object : LinkedHashMap<String, JSONObject>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?) = size > 256
    }

    fun profileFor(address: String): NetworkProfile {
        val target = NavidromeAddress.parse(address)
        val candidates = bookmarks.items.value.filter { it.id == target.profileId } + bookmarks.items.value.filter {
            it.kind == BookmarkKind.FOLDER && runCatching { NavidromeAddress.parse(it.address).matches(target) }.getOrDefault(false)
        }
        for (bookmark in candidates) {
            val profile = vault.read(bookmark.id) ?: continue
            if (runCatching { NavidromeAddress.parse(profile.address).matches(target) }.getOrDefault(false)) {
                require(profile.credentials.username.isNotBlank()) { "请编辑 Navidrome 位置并输入用户名和密码" }
                return profile
            }
        }
        throw RemoteAccessException("找不到此 Navidrome 服务器的认证配置，请在文件页重新添加或编辑网络位置")
    }

    override suspend fun resolve(address: String): MediaItem = withContext(Dispatchers.IO) {
        val parsed = NavidromeAddress.parse(address)
        require(parsed.route.size == 2 && parsed.route[0] == "song") { "请从 Navidrome 音乐库选择曲目" }
        val client = NavidromeClient(profileFor(address))
        // Validate the current account even when tags are cached; deleted/edited profiles cannot reuse stale credentials.
        val song = client.request("getSong", mapOf("id" to parsed.route[1])).getJSONObject("song")
        require(song.id() == parsed.route[1]) { "服务器返回的曲目标识不匹配" }
        songEntry(parsed, song).media!!
    }

    fun open(media: MediaItem): OpenedMediaSource {
        val parsed = NavidromeAddress.parse(media.uri)
        require(parsed.route.size == 2 && parsed.route[0] == "song") { "音乐地址无效" }
        return OpenedMediaSource(NavidromeClient(profileFor(media.uri)).url("stream", mapOf("id" to parsed.route[1], "format" to "raw")))
    }

    suspend fun tags(media: MediaItem): NavidromeAudioTags = withContext(Dispatchers.IO) {
        val parsed = NavidromeAddress.parse(media.uri)
        val client = NavidromeClient(profileFor(media.uri))
        val song = synchronized(songs) { songs[media.uri] } ?: client.request("getSong", mapOf("id" to parsed.route.last())).getJSONObject("song")
        val art = song.optString("coverArt").takeIf { it.isNotBlank() }?.let { runCatching { client.cover(it) }.getOrNull() }
        NavidromeAudioTags(song.label("title"), song.label("artist"), song.label("album"), song.label("albumArtist"),
            song.optInt("year").takeIf { it > 0 }?.toString().orEmpty(), song.label("genre"),
            song.optInt("track").takeIf { it > 0 }?.toString().orEmpty(), song.optLong("bitRate").coerceAtLeast(0) * 1000, art)
    }

    override suspend fun list(address: String): List<SourceEntry> = withContext(Dispatchers.IO) {
        val parsed = NavidromeAddress.parse(address)
        val client = NavidromeClient(profileFor(address))
        fun directory(name: String, vararg route: String, subtitle: String = "") = SourceEntry(parsed.at(*route), name, true, subtitle = subtitle)
        fun albums(array: JSONArray?) = objects(array).map { album ->
            directory(album.label("name").ifBlank { "未命名专辑" }, "album", album.id(), subtitle =
                listOf(album.label("artist"), album.optInt("songCount").takeIf { it > 0 }?.let { "$it 首" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
        }
        fun artists(array: JSONArray?) = objects(array).map { artist -> directory(artist.label("name"), "artist", artist.id(),
            subtitle = artist.optInt("albumCount").takeIf { it > 0 }?.let { "$it 张专辑" }.orEmpty()) }
        fun page(index: Int, count: Int, entries: List<SourceEntry>): List<SourceEntry> {
            val result = mutableListOf<SourceEntry>()
            if (index > 0) result += directory("上一页", *parsed.route.dropLast(1).toTypedArray(), (index - PAGE_SIZE).coerceAtLeast(0).toString())
            result += entries
            if (count > PAGE_SIZE) result += directory("下一页", *parsed.route.dropLast(1).toTypedArray(), (index + PAGE_SIZE).toString())
            return result
        }
        fun offset(): Int = parsed.route.last().toIntOrNull()?.takeIf { it in 0..10_000_000 && it % PAGE_SIZE == 0 } ?: error("音乐库页码无效")
        when (parsed.route.firstOrNull()) {
            null -> {
                client.request("ping")
                listOf(directory("专辑", "albums", "0", subtitle = "按专辑名称浏览"), directory("歌手", "artists"),
                    directory("全部歌曲", "songs", "0", subtitle = "每页 $PAGE_SIZE 首"), directory("服务器播放列表", "playlists"))
            }
            "albums" -> {
                require(parsed.route.size == 2)
                val index = offset()
                val array = client.request("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "${PAGE_SIZE + 1}", "offset" to "$index"))
                    .optJSONObject("albumList2")?.optJSONArray("album")
                page(index, array?.length() ?: 0, albums(array).take(PAGE_SIZE))
            }
            "artists" -> {
                require(parsed.route.size == 1)
                val indexes = client.request("getArtists").optJSONObject("artists")?.optJSONArray("index")
                objects(indexes).flatMap { artists(it.optJSONArray("artist")) }
            }
            "artist" -> {
                require(parsed.route.size == 2)
                albums(client.request("getArtist", mapOf("id" to parsed.route[1])).getJSONObject("artist").optJSONArray("album"))
            }
            "album", "playlist" -> {
                require(parsed.route.size == 2)
                val album = parsed.route[0] == "album"
                val response = client.request(if (album) "getAlbum" else "getPlaylist", mapOf("id" to parsed.route[1]))
                    .getJSONObject(if (album) "album" else "playlist")
                val entries = objects(response.optJSONArray(if (album) "song" else "entry"))
                require(entries.size <= M3uCodec.MAX_ENTRIES) { "此列表超过 ${M3uCodec.MAX_ENTRIES} 首，请拆分服务器播放列表" }
                entries.map { songEntry(parsed, it) }
            }
            "playlists" -> {
                require(parsed.route.size == 1)
                objects(client.request("getPlaylists").optJSONObject("playlists")?.optJSONArray("playlist")).map { playlist ->
                    directory(playlist.label("name"), "playlist", playlist.id(), subtitle = "${playlist.optInt("songCount")} 首 · ${playlist.label("owner")}")
                }
            }
            "songs", "search" -> {
                val search = parsed.route[0] == "search"
                require(parsed.route.size == if (search) 3 else 2)
                val index = offset()
                val count = if (search) PAGE_SIZE + 1 else 0
                val result = client.request("search3", mapOf("query" to if (search) parsed.route[1] else "", "songCount" to "${PAGE_SIZE + 1}",
                    "songOffset" to "$index", "artistCount" to "$count", "artistOffset" to "$index", "albumCount" to "$count", "albumOffset" to "$index"))
                    .optJSONObject("searchResult3") ?: JSONObject()
                val artistRows = artists(result.optJSONArray("artist"))
                val albumRows = albums(result.optJSONArray("album"))
                val songRows = objects(result.optJSONArray("song")).map { songEntry(parsed, it) }
                page(index, maxOf(artistRows.size, albumRows.size, songRows.size), artistRows.take(PAGE_SIZE) + albumRows.take(PAGE_SIZE) + songRows.take(PAGE_SIZE))
            }
            else -> error("不支持的音乐库路径")
        }
    }

    private fun songEntry(address: NavidromeAddress, song: JSONObject): SourceEntry {
        val uri = address.at("song", song.id())
        synchronized(songs) { songs[uri] = song }
        val media = MediaItem(uri, song.label("title").ifBlank { "未命名曲目" }, song.optString("contentType").takeIf { it.startsWith("audio/") } ?: "audio/*",
            song.optLong("size").takeIf { it > 0 })
        val duration = song.optLong("duration").coerceAtLeast(0)
        val subtitle = listOf(song.label("artist"), song.label("album"), "%d:%02d".format(duration / 60, duration % 60)).filter { it.isNotBlank() }.joinToString(" · ")
        return SourceEntry(uri, media.displayName, false, media, subtitle)
    }

    private fun JSONObject.label(key: String) = optString(key).take(500).takeUnless { it == "null" }.orEmpty()
    private fun JSONObject.id() = getString("id").also { require(it.isNotBlank() && it.length <= 1024 && '\u0000' !in it) { "服务器返回了无效标识" } }
    private fun objects(array: JSONArray?) = if (array == null) emptyList() else List(array.length()) { array.getJSONObject(it) }
    companion object { const val PAGE_SIZE = 100 }
}
