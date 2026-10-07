package io.github.micro123.mediaplayer.data

import android.content.Context
import io.github.micro123.mediaplayer.core.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class BookmarkKind { LOCATION, FOLDER, POSITION, PLAYLIST }
data class SavedBookmark(val id: String = UUID.randomUUID().toString(), val name: String,
    val kind: BookmarkKind, val address: String = "", val positionMs: Long = 0, val items: List<MediaItem> = emptyList(),
    val folderDocumentId: String = "")

/** Explicit named bookmarks, independent from automatic last-watched progress. */
@android.annotation.SuppressLint("UseKtx") // Durable synchronous commit is checked on the IO dispatcher.
class BookmarkRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("saved_bookmarks", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutableItems = MutableStateFlow(read())
    val items = mutableItems.asStateFlow()

    suspend fun save(bookmark: SavedBookmark) = withContext(Dispatchers.IO) {
        require(bookmark.name.isNotBlank() && bookmark.name.length <= 200) { "书签名称须为 1～200 字" }
        if (bookmark.kind == BookmarkKind.LOCATION) validateNetworkAddress(bookmark.address, allowFutureSources = true)
        if (bookmark.kind == BookmarkKind.FOLDER) {
            val uri = java.net.URI(bookmark.address)
            require(uri.scheme in setOf("file", "content", "smb", "nfs", "navidrome+http", "navidrome+https")) { "不支持的目录书签" }
            if (uri.scheme in setOf("smb", "nfs")) io.github.micro123.mediaplayer.data.network.RemoteAddress.parse(bookmark.address)
            if (io.github.micro123.mediaplayer.core.mediaSourceKind(bookmark.address) == io.github.micro123.mediaplayer.core.MediaSourceKind.NAVIDROME)
                io.github.micro123.mediaplayer.data.navidrome.NavidromeAddress.parse(bookmark.address)
            require(bookmark.folderDocumentId.length <= 4096) { "目录标识过长" }
        }
        require(bookmark.items.size <= M3uCodec.MAX_ENTRIES) { "书签列表过大" }
        mutex.withLock {
            val updated = listOf(bookmark) + mutableItems.value.filterNot { it.id == bookmark.id }
            require(updated.size <= 200) { "最多保存 200 个书签，请先删除不需要的书签" }
            write(updated); mutableItems.value = updated
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { val updated = mutableItems.value.filterNot { it.id == id }; write(updated); mutableItems.value = updated }
    }

    private fun read(): List<SavedBookmark> = runCatching {
        val array = JSONArray(preferences.getString("items", "[]"))
        List(array.length()) { index ->
            val value = array.getJSONObject(index)
            val media = value.optJSONArray("media") ?: JSONArray()
            SavedBookmark(value.getString("id"), value.getString("name"), BookmarkKind.valueOf(value.getString("kind")),
                value.optString("address"), value.optLong("position"), List(media.length()) { itemIndex ->
                    val item = media.getJSONObject(itemIndex)
                    MediaItem(item.getString("uri"), item.getString("name"), item.optString("mime").takeIf { it.isNotEmpty() }, null)
                }, value.optString("folderDocumentId"))
        }
    }.getOrDefault(emptyList())

    private fun write(items: List<SavedBookmark>) {
        val array = JSONArray()
        for (bookmark in items) array.put(JSONObject().apply {
            put("id", bookmark.id); put("name", bookmark.name); put("kind", bookmark.kind.name)
            put("address", bookmark.address); put("position", bookmark.positionMs)
            put("folderDocumentId", bookmark.folderDocumentId)
            put("media", JSONArray().apply { bookmark.items.forEach { media -> put(JSONObject().apply {
                put("uri", media.uri); put("name", media.displayName); put("mime", media.mimeType ?: "")
            }) } })
        })
        check(preferences.edit().putString("items", array.toString()).commit()) { "无法保存书签" }
    }
}
