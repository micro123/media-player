package io.github.micro123.mediaplayer.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import io.github.micro123.mediaplayer.core.MediaItem
import io.github.micro123.mediaplayer.core.MediaSourceKind
import io.github.micro123.mediaplayer.core.PlaybackSourceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AudioMetadata(val uri: String = "", val title: String = "", val artist: String = "", val album: String = "",
    val albumArtist: String = "", val year: String = "", val genre: String = "", val track: String = "",
    val composer: String = "", val bitrate: Long = 0, val cover: Bitmap? = null, val loading: Boolean = false)

/** Reads tags and embedded artwork independently of playback, using the same local/remote FD abstraction. */
class AudioMetadataRepository(private val sources: PlaybackSourceResolver) {
    suspend fun read(media: MediaItem): AudioMetadata = withContext(Dispatchers.IO) {
        val fallback = AudioMetadata(uri = media.uri, title = media.displayName)
        // Live HTTP streams may not be seekable files. Do not open a second unbounded HTTP connection for tags.
        if (media.sourceKind == MediaSourceKind.HTTP) return@withContext fallback
        try {
            sources.open(media).use { source ->
                require(source.input.startsWith("fd://"))
                ParcelFileDescriptor.fromFd(source.input.substringAfter("fd://").toInt()).use { fd ->
                    val reader = MediaMetadataRetriever()
                    try {
                        reader.setDataSource(fd.fileDescriptor)
                        fun text(key: Int) = reader.extractMetadata(key)?.trim()?.take(500).orEmpty()
                        AudioMetadata(media.uri, text(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { media.displayName },
                            text(MediaMetadataRetriever.METADATA_KEY_ARTIST), text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                            text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST), text(MediaMetadataRetriever.METADATA_KEY_YEAR),
                            text(MediaMetadataRetriever.METADATA_KEY_GENRE), text(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                            text(MediaMetadataRetriever.METADATA_KEY_COMPOSER), text(MediaMetadataRetriever.METADATA_KEY_BITRATE).toLongOrNull() ?: 0,
                            reader.embeddedPicture?.let(::decodeCover))
                    } finally { reader.release() }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { fallback } // Missing tags, unreadable artwork or unsupported codecs must never prevent playback.
    }

    private fun decodeCover(bytes: ByteArray): Bitmap? {
        if (bytes.size > 4 * 1024 * 1024) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > 768) sample *= 2
        options.inJustDecodeBounds = false; options.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
