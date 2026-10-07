package com.tang.player.data

import java.net.URI

data class M3uEntry(val reference: String, val title: String? = null)
data class M3uDocument(val entries: List<M3uEntry>, val hls: Boolean)

/** Pure text format boundary. HLS manifests are streams, not queues of transport segments. */
object M3uCodec {
    const val MAX_ENTRIES = 5000
    const val MAX_BYTES = 2 * 1024 * 1024

    fun parse(text: String): M3uDocument {
        require(text.length <= MAX_BYTES) { "播放列表超过 2 MiB 限制" }
        val entries = mutableListOf<M3uEntry>()
        var title: String? = null
        var hls = false
        for (line in text.removePrefix("\uFEFF").lineSequence()) {
            val value = line.trim()
            when {
                value.isBlank() -> Unit
                value.startsWith("#EXT-X-", true) -> hls = true
                value.startsWith("#EXTINF:", true) -> title = value.substringAfter(',', "").trim().takeIf { it.isNotEmpty() }
                value.startsWith('#') -> Unit
                else -> {
                    require(entries.size < MAX_ENTRIES) { "播放列表最多支持 $MAX_ENTRIES 项" }
                    require(value.length <= 8192) { "列表地址过长" }
                    entries += M3uEntry(value, title)
                    title = null
                }
            }
        }
        return M3uDocument(entries, hls)
    }

    fun resolve(reference: String, base: String? = null): String {
        val normalized = reference.replace('\\', '/')
        val origin = base?.let(::URI)
        val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(normalized)
        if (origin?.scheme.equals("file", true) && !hasScheme) {
            // Local names can contain brackets/#/? literally; these are not URL query fragments.
            return requireNotNull(origin).resolve(URI(null, null, normalized, null)).toASCIIString()
        }
        if (origin?.scheme?.lowercase() in listOf("smb", "nfs") && !hasScheme) {
            val path = normalized.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D")
                .replace("#", "%23").replace("?", "%3F")
            return requireNotNull(origin).resolve(URI(path)).normalize().toASCIIString()
        }
        // Spaces in filenames are valid in M3U; preserve already escaped URL components.
        val uri = URI(normalized.replace(" ", "%20"))
        if (uri.isAbsolute) return uri.toASCIIString()
        val root = origin ?: error("相对路径需要选择播放列表所在文件夹")
        require(root.scheme.lowercase() in listOf("http", "https", "file", "smb", "nfs")) { "相对路径需要选择播放列表所在文件夹" }
        return root.resolve(uri).toASCIIString()
    }

    fun write(entries: List<M3uEntry>): String = buildString {
        append("#EXTM3U\n")
        for (entry in entries) {
            require(!entry.reference.contains('\n') && !entry.reference.contains('\r')) { "地址不能包含换行" }
            entry.title?.let { append("#EXTINF:-1,").append(it.replace('\n', ' ').replace('\r', ' ')).append('\n') }
            append(entry.reference).append('\n')
        }
    }
}
