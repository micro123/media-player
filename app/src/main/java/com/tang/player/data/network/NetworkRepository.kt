package com.tang.player.data.network

import android.content.Context
import com.tang.player.core.MediaItem
import com.tang.player.core.MediaSourceKind
import com.tang.player.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NetworkRepository(context: Context, private val bookmarks: BookmarkRepository,
    private val transports: Map<MediaSourceKind, RemoteTransport> = mapOf(MediaSourceKind.SMB to SmbTransport(), MediaSourceKind.NFS to NfsTransport())) {
    val credentials = NetworkCredentialStore(context)
    val providers = listOf(MediaSourceKind.SMB, MediaSourceKind.NFS).map { kind -> object : MediaSourceProvider {
        override val kind = kind
        override val capabilities = SourceCapabilities(playable = true, browsable = true)
        override suspend fun resolve(address: String): MediaItem = withContext(Dispatchers.IO) {
            access(address) { parsed, profile, transport ->
                val info = transport.stat(parsed, profile)
                require(!info.directory) { "请选择目录中的媒体文件" }
                item(parsed.address, info)
            }
        }
        override suspend fun list(address: String): List<SourceEntry> = withContext(Dispatchers.IO) {
            access(address) { parsed, profile, transport ->
                transport.list(parsed, profile).mapNotNull { info ->
                    val child = parsed.child(info.name)
                    if (info.directory) SourceEntry(child, info.name, true)
                    else if (mimeFromName(info.name)?.let { it.startsWith("video/") || it.startsWith("audio/") } == true ||
                        info.name.endsWith(".m3u", true) || info.name.endsWith(".m3u8", true))
                        SourceEntry(child, info.name, false, item(child, info)) else null
                }.sortedWith { left, right ->
                    compareValues(right.directory, left.directory).takeIf { it != 0 }
                        ?: MediaSeriesGrouper.naturalCompare(left.name, right.name)
                }
            }
        }
    } }
    private fun item(address: String, info: RemoteFileInfo) = MediaItem(address, info.name, mimeFromName(info.name) ?: "video/*", info.size)

    private fun profile(address: RemoteAddress): NetworkProfile {
        val locations = bookmarks.items.value.filter { it.kind in setOf(BookmarkKind.LOCATION, BookmarkKind.FOLDER) }
            .mapNotNull { bookmark -> runCatching { RemoteAddress.parse(bookmark.address) }.getOrNull()?.let { bookmark to it } }
            .filter { (_, root) -> root.contains(address) || (root.kind == MediaSourceKind.SMB &&
                root.host.equals(address.host, true) && root.effectivePort() == address.effectivePort() && root.parts.first() == address.parts.first()) }
            .sortedByDescending { (_, root) -> if (root.contains(address)) root.parts.size else 0 }
        for ((bookmark, _) in locations) {
            val saved = credentials.read(bookmark.id)
            if (saved != null) {
                val root = RemoteAddress.parse(saved.address)
                if (root.contains(address) || (root.kind == MediaSourceKind.SMB && root.host.equals(address.host, true) &&
                        root.effectivePort() == address.effectivePort() && root.parts.first() == address.parts.first())) return saved
            }
            // Old bookmarks become functional with guest SMB or default NFS AUTH_SYS.
            if (saved == null) return NetworkProfile(bookmark.id, bookmark.address, NetworkCredentials())
        }
        if (address.kind == MediaSourceKind.NFS) throw RemoteAccessException("请先保存 NFS 导出目录书签，再打开其中的媒体")
        return NetworkProfile("", java.net.URI(address.uri.scheme, null, address.uri.host, address.uri.port,
            "/" + address.parts.first(), null, null).toASCIIString(), NetworkCredentials())
    }
    /** Folder bookmarks inherit the original export/share profile, especially the NFS mount root. */
    fun profileFor(address: String): NetworkProfile = profile(RemoteAddress.parse(address))
    private fun <T> access(address: String, operation: (RemoteAddress, NetworkProfile, RemoteTransport) -> T): T {
        val parsed = RemoteAddress.parse(address)
        try { return operation(parsed, profile(parsed), requireNotNull(transports[parsed.kind])) }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: RemoteAccessException) { throw error }
        catch (error: Exception) { throw explainNetworkFailure(parsed.kind.name, NetworkStage.LIST, error) }
    }
    fun open(address: String): RemoteReadHandle = access(address) { parsed, profile, transport -> transport.open(parsed, profile) }
    fun readPlaylist(address: String): String = open(address).use { file ->
        require(file.size in 0..M3uCodec.MAX_BYTES.toLong()) { "M3U 文件超过 2 MiB" }
        val bytes = ByteArray(file.size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = file.read(offset.toLong(), bytes, offset, bytes.size - offset)
            check(count > 0) { "网络播放列表被截断" }; offset += count
        }
        String(bytes, Charsets.UTF_8)
    }
}
