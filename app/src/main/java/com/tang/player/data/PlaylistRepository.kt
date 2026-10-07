package com.tang.player.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.provider.DocumentsContract
import com.tang.player.core.MediaItem
import com.tang.player.core.MediaSourceKind
import com.tang.player.core.mediaSourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.coroutines.coroutineContext

data class PlaylistImport(val items: List<MediaItem> = emptyList(), val rejected: Int = 0, val needsFolder: Boolean = false)

class PlaylistRepository(context: Context, private val media: MediaRepository,
    private val network: com.tang.player.data.network.NetworkRepository? = null) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun read(address: String, folder: Uri? = null): PlaylistImport = withContext(Dispatchers.IO) {
        val (text, base) = readText(address)
        val document = M3uCodec.parse(text)
        if (document.hls) {
            require(mediaSourceKind(address) == MediaSourceKind.HTTP) { "本地 HLS 分片列表暂不支持，请使用 HTTP / HTTPS HLS 地址" }
            return@withContext PlaylistImport(listOf(media.resolve(base.toUri()).copy(mimeType = "video/mp2t")))
        }
        val localDocument = mediaSourceKind(address) == MediaSourceKind.LOCAL && !address.startsWith("file:")
        val relative = document.entries.any { !hasScheme(it.reference) && !it.reference.startsWith('/') }
        if (localDocument && relative && folder == null) return@withContext PlaylistImport(needsFolder = true)
        folder?.let { resolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        var rejected = 0
        val items = document.entries.mapNotNull { entry ->
            coroutineContext.ensureActive()
            try {
                val reference = entry.reference.replace('\\', '/')
                val resolved = if (localDocument && !hasScheme(reference)) {
                    if (reference.startsWith('/')) Uri.fromFile(java.io.File(reference)).toString()
                    else resolveDocument(requireNotNull(folder), reference)
                }
                    else M3uCodec.resolve(reference, base)
                val kind = mediaSourceKind(resolved)
                require(kind != MediaSourceKind.UNSUPPORTED)
                val item = when (kind) {
                    MediaSourceKind.SMB, MediaSourceKind.NFS -> {
                        val parsed = com.tang.player.data.network.RemoteAddress.parse(resolved)
                        MediaItem(parsed.address, parsed.parts.last(), mimeFromName(parsed.parts.last()) ?: "video/*", null)
                    }
                    else -> media.resolve(resolved.toUri())
                }
                item.copy(displayName = entry.title ?: item.displayName)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { rejected++; null }
        }.distinctBy { it.uri }
        check(items.isNotEmpty()) { "播放列表中没有可读取的媒体${if (rejected > 0) "（$rejected 项无效或未获授权）" else ""}" }
        PlaylistImport(items, rejected)
    }

    suspend fun write(uri: Uri, items: List<MediaItem>) = withContext(Dispatchers.IO) {
        check(items.isNotEmpty()) { "播放列表为空" }
        val text = M3uCodec.write(items.map { M3uEntry(it.uri, it.displayName) })
        resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
            ?: error("无法写入播放列表")
    }

    private fun readText(address: String): Pair<String, String> {
        if (mediaSourceKind(address) in setOf(MediaSourceKind.SMB, MediaSourceKind.NFS))
            return requireNotNull(network) { "网络来源未配置" }.readPlaylist(address) to address
        if (mediaSourceKind(address) == MediaSourceKind.HTTP) {
            val value = HttpPlaylistClient().read(address)
            return value.text to value.address
        }
        val uri = address.toUri()
        try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
        return (resolver.openInputStream(uri)?.use(::boundedPlaylistText) ?: error("无法读取播放列表")) to address
    }

    private fun hasScheme(reference: String) = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(reference)

    private fun resolveDocument(tree: Uri, reference: String): String {
        val segments = mutableListOf<String>()
        for (part in reference.split('/')) when (part) {
            "", "." -> Unit
            ".." -> { check(segments.isNotEmpty()) { "相对路径超出授权文件夹" }; segments.removeAt(segments.lastIndex) }
            else -> segments += part
        }
        check(segments.isNotEmpty()) { "媒体路径为空" }
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (part in segments) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            id = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                var match: String? = null
                while (cursor.moveToNext()) if (cursor.getString(1) == part) { match = cursor.getString(0); break }
                match
            } ?: error("找不到相对路径中的文件")
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
    }
}
