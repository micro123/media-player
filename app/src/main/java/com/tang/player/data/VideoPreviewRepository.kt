package com.tang.player.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.core.graphics.scale
import com.tang.player.core.MediaItem
import com.tang.player.core.MediaSourceKind
import com.tang.player.core.PlaybackSourceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One bounded local-frame reader; never creates extra network connections. */
class VideoPreviewRepository(private val sources: PlaybackSourceResolver) {
    private val cache = LruCache<String, Bitmap>(12)
    suspend fun read(media: MediaItem, positionMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        if (!media.isVideo || media.sourceKind != MediaSourceKind.LOCAL) return@withContext null
        val position = positionMs.coerceAtLeast(0) / 1000 * 1000
        val key = "${media.uri}:$position"
        cache.get(key)?.let { return@withContext it }
        try {
            sources.open(media).use { source ->
                require(source.input.startsWith("fd://"))
                ParcelFileDescriptor.fromFd(source.input.substringAfter("fd://").toInt()).use { fd ->
                    val reader = MediaMetadataRetriever()
                    try {
                        reader.setDataSource(fd.fileDescriptor)
                        val bitmap = if (Build.VERSION.SDK_INT >= 27)
                            reader.getScaledFrameAtTime(position * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 240, 135)
                        else {
                            val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            // On API 26 only a full-resolution decode exists; avoid huge allocations.
                            if (width.toLong() * height > 8_300_000L) return@withContext null
                            reader.getFrameAtTime(position * 1000, MediaMetadataRetriever.OPTION_CLOSEST)?.let { frame ->
                                val scale = minOf(240f / frame.width, 135f / frame.height, 1f)
                                frame.scale((frame.width * scale).toInt().coerceAtLeast(1),
                                    (frame.height * scale).toInt().coerceAtLeast(1)).also { if (it !== frame) frame.recycle() }
                            }
                        }
                        bitmap?.also { cache.put(key, it) }
                    } finally { reader.release() }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { null } // Time preview and seeking still work if frame extraction is unsupported.
    }
}
