package com.tang.player.data

import android.Manifest
import android.content.ContentUris
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.tang.player.core.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class FolderEntry(val uri: String, val name: String, val documentId: String, val media: MediaItem? = null) {
    val isDirectory: Boolean get() = media == null
}

data class FolderLocation(val documentId: String, val name: String)

data class MediaAccess(val audio: Boolean, val video: Boolean, val limitedVideo: Boolean, val allFiles: Boolean = false) {
    val any: Boolean get() = audio || video || limitedVideo
    val full: Boolean get() = allFiles || (audio && video)
}

// Synchronous commit is checked on the IO dispatcher so a failed durable write is not silently accepted.
@SuppressLint("UseKtx")
class MediaBrowserRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences("media_browser", Context.MODE_PRIVATE)

    fun access(): MediaAccess {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
        val allFiles = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        return if (Build.VERSION.SDK_INT >= 33) MediaAccess(allFiles || granted(Manifest.permission.READ_MEDIA_AUDIO),
            allFiles || granted(Manifest.permission.READ_MEDIA_VIDEO), Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED), allFiles)
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE).let { MediaAccess(allFiles || it, allFiles || it, false, allFiles) }
    }

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    suspend fun queryLibrary(): List<MediaItem> = withContext(Dispatchers.IO) {
        buildList {
            // MediaStore can return self-owned media even without a broad permission grant.
            for (collection in listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)) {
                try {
                    resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE), null, null,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                            add(MediaItem(uri.toString(), cursor.getString(1) ?: "未命名", cursor.getString(2),
                                if (cursor.isNull(3)) null else cursor.getLong(3)))
                        }
                    }
                } catch (_: SecurityException) {
                    // Audio and video permissions can be granted independently.
                }
            }
        }.sortedWith(compareBy<MediaItem> { !it.isVideo }.thenBy { it.displayName.lowercase() })
    }

    fun lastTree(): Uri? = preferences.getString("tree", null)?.let(Uri::parse)

    @Suppress("DEPRECATION")
    fun storageRoot(): FolderLocation = Environment.getExternalStorageDirectory().canonicalFile.let {
        FolderLocation(it.path, "内部共享存储")
    }

    suspend fun listStorageFolder(path: String): List<FolderEntry> = withContext(Dispatchers.IO) {
        check(access().allFiles) { "需要访问所有文件权限" }
        val root = File(storageRoot().documentId)
        val folder = File(path).canonicalFile
        check(folder.path == root.path || folder.path.startsWith(root.path + File.separator)) { "不支持访问共享存储以外的目录" }
        val files = folder.listFiles() ?: error("无法读取此目录，安卓可能限制了访问。")
        files.mapNotNull { file ->
            val canonical = file.canonicalFile
            // Do not follow a directory link outside shared storage.
            if (!canonical.path.startsWith(root.path + File.separator)) return@mapNotNull null
            val uri = Uri.fromFile(canonical).toString()
            if (file.isDirectory) FolderEntry(uri, file.name, canonical.path)
            else {
                val mime = mimeFromName(file.name)
                if (mime?.startsWith("audio/") == true || mime?.startsWith("video/") == true)
                    FolderEntry(uri, file.name, canonical.path, MediaItem(uri, file.name, mime, file.length()))
                else null
            }
        }.sortedWith(compareBy<FolderEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun selectTree(uri: Uri): FolderLocation = withContext(Dispatchers.IO) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val id = DocumentsContract.getTreeDocumentId(uri)
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, id)
        val name = resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "所选文件夹"
        check(preferences.edit().putString("tree", uri.toString()).commit())
        FolderLocation(id, name)
    }

    suspend fun listFolder(tree: Uri, documentId: String): List<FolderEntry> = withContext(Dispatchers.IO) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        val entries = buildList {
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: "未命名"
                    val mime = cursor.getString(2)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) add(FolderEntry(uri, name, id))
                    else {
                        val type = mime?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
                            ?: mimeFromName(name)
                        if (type?.startsWith("video/") == true || type?.startsWith("audio/") == true) {
                            add(FolderEntry(uri, name, id, MediaItem(uri, name, type,
                                if (cursor.isNull(3)) null else cursor.getLong(3))))
                        }
                    }
                }
            } ?: error("无法列出文件夹")
        }
        entries.sortedWith(compareBy<FolderEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }
}

fun mimeFromName(name: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
