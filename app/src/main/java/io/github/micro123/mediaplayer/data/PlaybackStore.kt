package io.github.micro123.mediaplayer.data

import android.annotation.SuppressLint
import android.content.Context
import io.github.micro123.mediaplayer.core.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

enum class VideoAspect(val label: String, val ratio: Double) {
    ORIGINAL("原始比例", -1.0), FOUR_THREE("4:3", 4.0 / 3.0), SIXTEEN_NINE("16:9", 16.0 / 9.0),
}

enum class VideoOrientation(val label: String) {
    LANDSCAPE("横屏"), PORTRAIT("竖屏"), KEEP("保持");
    companion object {
        // The old AUTO mode and absent/invalid values use the new landscape default.
        fun fromStored(value: String?): VideoOrientation = entries.firstOrNull { it.name == value } ?: LANDSCAPE
    }
}

data class PlayerPreferences(
    val rememberSpeed: Boolean = false,
    val lastSpeed: Double = 1.0,
    val aspect: VideoAspect = VideoAspect.ORIGINAL,
    val autoNext: Boolean = true,
    val skipSeconds: Int = 85,
    val orientation: VideoOrientation = VideoOrientation.LANDSCAPE,
    val groupMedia: Boolean = true,
    val fileSort: BrowseSort = BrowseSort.NAME,
    val fileSortDescending: Boolean = false,
    val autoPip: Boolean = false,
    val backgroundVideo: Boolean = false,
)

data class PlaybackBookmark(val positionMs: Long, val durationMs: Long)

/** One durable queue snapshot. Availability is deliberately checked only when played. */
data class LastPlayback(val name: String, val items: List<MediaItem>, val index: Int) {
    val current: MediaItem? get() = items.getOrNull(index)
}

fun normalizeSpeed(speed: Double): Double = if (speed.isFinite()) round(speed.coerceIn(0.1, 5.0) * 10) / 10 else 1.0
fun boostedSpeed(speed: Double): Double = (speed * 2).coerceIn(0.1, 5.0)

/** Settings are global; bookmarks are keyed by the original content URI. */
// Synchronous commit is checked on the IO dispatcher so a failed durable write is not silently accepted.
@SuppressLint("UseKtx")
class PlaybackStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    fun readPreferences(): PlayerPreferences = PlayerPreferences(
        rememberSpeed = preferences.getBoolean("remember_speed", false),
        lastSpeed = normalizeSpeed(preferences.getFloat("speed", 1f).toDouble()),
        aspect = runCatching { VideoAspect.valueOf(preferences.getString("aspect", "ORIGINAL")!!) }.getOrDefault(VideoAspect.ORIGINAL),
        autoNext = preferences.getBoolean("auto_next", true),
        skipSeconds = preferences.getInt("skip_seconds", 85).coerceAtLeast(1),
        orientation = VideoOrientation.fromStored(preferences.getString("orientation", null)),
        groupMedia = preferences.getBoolean("group_media", true),
        fileSort = runCatching { BrowseSort.valueOf(preferences.getString("file_sort", "NAME")!!) }.getOrDefault(BrowseSort.NAME),
        fileSortDescending = preferences.getBoolean("file_sort_descending", false),
        autoPip = preferences.getBoolean("auto_pip", false),
        backgroundVideo = preferences.getBoolean("background_video", false),
    )

    suspend fun writePreferences(value: PlayerPreferences) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(preferences.edit().putBoolean("remember_speed", value.rememberSpeed)
                .putFloat("speed", value.lastSpeed.toFloat()).putString("aspect", value.aspect.name)
                .putBoolean("auto_next", value.autoNext).putInt("skip_seconds", value.skipSeconds.coerceAtLeast(1))
                .putString("orientation", value.orientation.name).putBoolean("group_media", value.groupMedia)
                .putString("file_sort", value.fileSort.name).putBoolean("file_sort_descending", value.fileSortDescending)
                .putBoolean("auto_pip", value.autoPip).putBoolean("background_video", value.backgroundVideo).commit())
        }
    }

    suspend fun readBookmark(uri: String): PlaybackBookmark? = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val item = JSONObject(preferences.getString("bookmarks", "{}")!!).optJSONObject(uri)
                item?.let { PlaybackBookmark(it.getLong("position"), it.getLong("duration")) }
                    ?.takeIf { it.positionMs > 0 }
            }.getOrNull()
        }
    }

    suspend fun writeBookmark(uri: String, position: Long, duration: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val items = runCatching { JSONObject(preferences.getString("bookmarks", "{}")!!) }.getOrDefault(JSONObject())
            if (position <= 0) items.remove(uri) else items.put(uri, JSONObject().apply {
                put("position", position); put("duration", duration); put("updated", System.currentTimeMillis())
            })
            // Keep the most recently watched 500 files, without growing preferences indefinitely.
            if (items.length() > 500) {
                items.keys().asSequence().sortedByDescending { items.optJSONObject(it)?.optLong("updated") ?: 0 }
                    .drop(500).toList().forEach(items::remove)
            }
            check(preferences.edit().putString("bookmarks", items.toString()).commit())
        }
    }

    suspend fun readQueue(): List<MediaItem> = withContext(Dispatchers.IO) {
        runCatching {
            val items = JSONArray(preferences.getString("queue", "[]"))
            List(items.length()) { index ->
                val value = items.getJSONObject(index)
                MediaItem(value.getString("uri"), value.getString("name"), value.optString("mime").takeIf { it.isNotBlank() },
                    value.optLong("size", -1).takeIf { it >= 0 })
            }
        }.getOrDefault(emptyList())
    }

    suspend fun writeQueue(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        val values = JSONArray()
        items.forEach { media -> values.put(JSONObject().apply {
            put("uri", media.uri); put("name", media.displayName); put("mime", media.mimeType ?: ""); put("size", media.sizeBytes ?: -1)
        }) }
        mutex.withLock { check(preferences.edit().putString("queue", values.toString()).commit()) }
    }

    suspend fun readLastPlayback(): LastPlayback? = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val encoded = preferences.getString("last_playback", null) ?: return@withLock null
                val value = JSONObject(encoded)
                val values = value.getJSONArray("items")
                val items = List(values.length()) { index ->
                    val item = values.getJSONObject(index)
                    MediaItem(item.getString("uri"), item.getString("name"), item.optString("mime").takeIf { it.isNotBlank() },
                        item.optLong("size", -1).takeIf { it >= 0 })
                }
                val index = value.getInt("index")
                if (index !in items.indices) null else LastPlayback(value.getString("name"), items, index)
            }.getOrNull()
        }
    }

    suspend fun writeLastPlayback(value: LastPlayback?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val editor = preferences.edit()
            if (value == null) editor.remove("last_playback") else {
                require(value.index in value.items.indices)
                val items = JSONArray()
                value.items.forEach { media -> items.put(JSONObject().apply {
                    put("uri", media.uri); put("name", media.displayName); put("mime", media.mimeType ?: ""); put("size", media.sizeBytes ?: -1)
                }) }
                editor.putString("last_playback", JSONObject().apply {
                    put("name", value.name); put("index", value.index); put("items", items)
                }.toString())
            }
            check(editor.commit())
        }
    }
}
