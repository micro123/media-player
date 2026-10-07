package io.github.micro123.mediaplayer.data

import io.github.micro123.mediaplayer.core.MediaItem
import java.util.Locale

/** Keep unavailable fields absent; filename/MIME and known size still work for remote video. */
fun queueMediaInfo(media: MediaItem, preview: QueuePreview): String {
    val mime = media.mimeType.orEmpty().lowercase()
    val format = when (mime) {
        "audio/mpeg" -> "MP3"
        "audio/flac", "audio/x-flac" -> "FLAC"
        "audio/mp4", "audio/x-m4a" -> "M4A"
        "audio/aac" -> "AAC"
        "audio/ogg", "application/ogg" -> "OGG"
        "audio/opus" -> "OPUS"
        "audio/wav", "audio/x-wav" -> "WAV"
        "video/mp4" -> "MP4"
        "video/x-matroska" -> "MKV"
        "video/webm" -> "WEBM"
        "video/mp2t" -> "TS"
        else -> media.displayName.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }?.uppercase(Locale.ROOT)
            ?: if (media.isVideo) "视频" else "音频"
    }
    val fields = mutableListOf<String>()
    if (preview.durationMs > 0) {
        val seconds = preview.durationMs / 1000
        fields += if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }
    fields += format
    if (media.isVideo && preview.width > 0 && preview.height > 0) fields += "${preview.width}×${preview.height}"
    if (!media.isVideo && preview.bitrate > 0) fields += "${preview.bitrate / 1000} kbps"
    (preview.sizeBytes ?: media.sizeBytes)?.takeIf { it >= 0 }?.let { size ->
        fields += when {
            size >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f GB", size / (1024.0 * 1024 * 1024))
            size >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024))
            size >= 1024 -> String.format(Locale.ROOT, "%.0f KB", size / 1024.0)
            else -> "$size B"
        }
    }
    return fields.joinToString(" · ")
}
