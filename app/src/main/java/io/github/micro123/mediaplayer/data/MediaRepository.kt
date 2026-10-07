package io.github.micro123.mediaplayer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.provider.OpenableColumns
import androidx.core.content.edit
import io.github.micro123.mediaplayer.core.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MediaRepository(context: Context, additionalSources: List<MediaSourceProvider> = emptyList()) {
    private val resolver = context.applicationContext.contentResolver
    private val preferences = context.applicationContext.getSharedPreferences("recent_media", Context.MODE_PRIVATE)
    private val sources = MediaSourceRegistry(listOf(object : MediaSourceProvider {
        override val kind = io.github.micro123.mediaplayer.core.MediaSourceKind.LOCAL
        override val capabilities = SourceCapabilities(playable = true)
        override suspend fun resolve(address: String) = resolveLocal(address.toUri())
    }, HttpMediaSourceProvider()) + additionalSources)

    suspend fun resolve(uri: Uri): MediaItem = sources.resolve(uri.toString())
    suspend fun list(address: String): List<SourceEntry> = sources.list(address)

    private suspend fun resolveLocal(uri: Uri): MediaItem = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path)).canonicalFile
            check(file.isFile && file.canRead()) { "File is not readable" }
            return@withContext MediaItem(Uri.fromFile(file).toString(), file.name, mimeFromName(file.name), file.length())
        }
        // Some providers grant temporary access only. Selection still works for this session.
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The next attempt to open a stale document will report an access error.
        }

        var displayName: String? = null
        var sizeBytes: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
        // Verify access now, so an obsolete recent entry does not appear to open successfully.
        resolver.openFileDescriptor(uri, "r")?.use { } ?: error("Document is not readable")
        MediaItem(
            uri = uri.toString(),
            displayName = displayName ?: uri.lastPathSegment ?: uri.toString(),
            mimeType = resolver.getType(uri)?.takeUnless { it == "application/octet-stream" }
                ?: mimeFromName(displayName ?: uri.lastPathSegment.orEmpty()),
            sizeBytes = sizeBytes,
        )
    }

    suspend fun readRecent(): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            val array = JSONArray(preferences.getString("items", "[]"))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(MediaItem(
                        uri = item.getString("uri"),
                        displayName = item.getString("name"),
                        mimeType = if (item.isNull("mime")) null else item.getString("mime"),
                        sizeBytes = if (item.isNull("size")) null else item.getLong("size"),
                    ))
                }
            }
        } catch (_: org.json.JSONException) {
            emptyList()
        }
    }

    suspend fun writeRecent(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        items.take(MAX_RECENT).forEach { item ->
            array.put(JSONObject().apply {
                put("uri", item.uri)
                put("name", item.displayName)
                put("mime", item.mimeType ?: JSONObject.NULL)
                put("size", item.sizeBytes ?: JSONObject.NULL)
            })
        }
        preferences.edit { putString("items", array.toString()) }
    }

    companion object {
        const val MAX_RECENT = 30
    }
}

// Source compatibility for existing integration tests; application code uses the general repository.
typealias LocalMediaRepository = MediaRepository
