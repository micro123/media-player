package com.tang.player.data

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class PlaylistText(val text: String, val address: String)

/** Bounded download with validated redirects and a final base URL for relative media paths. */
class HttpPlaylistClient(private val connect: (String) -> HttpURLConnection = {
    URL(it).openConnection() as HttpURLConnection
}) {
    fun read(address: String): PlaylistText {
        var target = validateNetworkAddress(address)
        repeat(6) { redirect ->
            val connection = connect(target)
            try {
                connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                    check(redirect < 5) { "重定向次数过多" }
                    val next = URI(target).resolve(connection.getHeaderField("Location") ?: error("重定向缺少地址")).toString()
                    target = validateNetworkAddress(next)
                } else {
                    check(connection.responseCode in 200..299) { "无法读取网络播放列表（HTTP ${connection.responseCode}）" }
                    return PlaylistText(connection.inputStream.use(::boundedPlaylistText), target)
                }
            } finally { connection.disconnect() }
        }
        error("无法读取网络播放列表")
    }
}

fun boundedPlaylistText(input: java.io.InputStream): String {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (buffer.size() <= M3uCodec.MAX_BYTES) {
        val count = input.read(chunk, 0, minOf(chunk.size, M3uCodec.MAX_BYTES + 1 - buffer.size()))
        if (count < 0) break
        buffer.write(chunk, 0, count)
    }
    val bytes = buffer.toByteArray()
    check(bytes.size <= M3uCodec.MAX_BYTES) { "播放列表超过 2 MiB 限制" }
    return bytes.toString(Charsets.UTF_8)
}
