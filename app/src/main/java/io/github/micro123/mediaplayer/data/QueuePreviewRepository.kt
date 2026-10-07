package io.github.micro123.mediaplayer.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.core.graphics.scale
import io.github.micro123.mediaplayer.core.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class QueuePreview(val artwork: Bitmap? = null, val subtitle: String = "", val durationMs: Long = 0,
    val width: Int = 0, val height: Int = 0, val bitrate: Long = 0, val sizeBytes: Long? = null)

/** Visible rows only. Two readers, bounded decoded memory and a disposable on-disk thumbnail cache. */
class QueuePreviewRepository(private val audio: AudioMetadataRepository, private val video: VideoPreviewRepository,
    private val directory: File? = null) {
    private val readers = Semaphore(2)
    private val diskLock = Any()
    private val cache = object : LruCache<String, QueuePreview>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: QueuePreview) = (value.artwork?.allocationByteCount ?: 4096).coerceAtLeast(4096)
    }

    suspend fun read(media: MediaItem): QueuePreview = withContext(Dispatchers.IO) {
        readers.withPermit {
            val key = cacheKey(media)
            cache.get(key)?.let { return@withPermit it }
            diskRead(key)?.let { cache.put(key, it); return@withPermit it }
            val preview = if (media.isVideo) {
                val details = video.details(media)
                QueuePreview(video.read(media, 1000), durationMs = details.durationMs, width = details.width,
                    height = details.height, bitrate = details.bitrate, sizeBytes = details.sizeBytes)
            } else {
                val tags = audio.read(media)
                QueuePreview(tags.cover?.let { art ->
                    val factor = minOf(256f / art.width, 256f / art.height, 1f)
                    if (factor < 1f) art.scale((art.width * factor).toInt().coerceAtLeast(1), (art.height * factor).toInt().coerceAtLeast(1)) else art
                }, listOf(tags.artist, tags.album).filter { it.isNotBlank() }.joinToString(" · "),
                    durationMs = tags.durationMs, bitrate = tags.bitrate, sizeBytes = tags.sizeBytes ?: media.sizeBytes)
            }
            coroutineContext.ensureActive()
            cache.put(key, preview)
            // Missing artwork may be a temporary network error. Do not persist negative results across launches.
            if (preview.artwork != null) diskWrite(key, preview)
            preview
        }
    }

    private fun cacheKey(media: MediaItem): String {
        val modified = if (media.uri.startsWith("file:")) runCatching { File(URI(media.uri)).lastModified() }.getOrDefault(0) else 0
        return MessageDigest.getInstance("SHA-256").digest("${media.uri}|${media.mimeType}|${media.sizeBytes}|$modified".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun diskRead(key: String): QueuePreview? = synchronized(diskLock) {
        val root = directory ?: return@synchronized null
        val meta = File(root, "$key.json")
        val image = File(root, "$key.thumb")
        try {
            if (!meta.isFile || !image.isFile || meta.length() > 8192 || image.length() > 1024 * 1024) return@synchronized null
            val value = JSONObject(meta.readText())
            if (System.currentTimeMillis() - value.optLong("created") > MAX_AGE_MS) {
                meta.delete(); image.delete(); return@synchronized null
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.path, options)
            if (options.outWidth !in 1..512 || options.outHeight !in 1..512) return@synchronized null
            val bitmap = BitmapFactory.decodeFile(image.path) ?: return@synchronized null
            meta.setLastModified(System.currentTimeMillis())
            QueuePreview(bitmap, value.optString("subtitle").take(1000), value.optLong("duration").coerceAtLeast(0),
                value.optInt("width").coerceAtLeast(0), value.optInt("height").coerceAtLeast(0), value.optLong("bitrate").coerceAtLeast(0),
                value.optLong("size", -1).takeIf { it >= 0 })
        } catch (_: Exception) { meta.delete(); image.delete(); null }
    }

    private fun diskWrite(key: String, preview: QueuePreview) = synchronized(diskLock) {
        val root = directory ?: return@synchronized
        val temporary = File(root, "$key-${UUID.randomUUID()}.tmp")
        val image = File(root, "$key.thumb")
        val metadata = File(root, "$key.json")
        try {
            if (!root.isDirectory && !root.mkdirs()) return@synchronized
            val compressed = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
            temporary.outputStream().use { if (preview.artwork?.compress(compressed, 84, it) != true) return@synchronized }
            if (!temporary.renameTo(image)) return@synchronized
            metadata.writeText(JSONObject().apply {
                put("created", System.currentTimeMillis()); put("subtitle", preview.subtitle.take(1000)); put("duration", preview.durationMs)
                put("width", preview.width); put("height", preview.height); put("bitrate", preview.bitrate); put("size", preview.sizeBytes ?: -1)
            }.toString())
            val files = root.listFiles().orEmpty()
            // Recover disposable files left by an interrupted write, so they also stay
            // within the cache budget instead of accumulating outside its entry count.
            files.filter { it.extension == "tmp" || it.extension == "thumb" && !File(root, "${it.nameWithoutExtension}.json").isFile }
                .forEach { it.delete() }
            val entries = files.filter { it.extension == "json" }.sortedByDescending { it.lastModified() }
            var bytes = 0L
            entries.forEachIndexed { index, meta ->
                val art = File(root, "${meta.nameWithoutExtension}.thumb")
                bytes += meta.length() + art.length()
                if (index >= MAX_DISK_ITEMS || bytes > MAX_DISK_BYTES) { meta.delete(); art.delete() }
            }
        } catch (_: Exception) {
            metadata.delete(); image.delete() // A partial entry must not consume an untracked disk budget.
        }
        finally { temporary.delete() }
    }

    companion object {
        private const val MAX_DISK_BYTES = 64L * 1024 * 1024
        private const val MAX_DISK_ITEMS = 400
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
