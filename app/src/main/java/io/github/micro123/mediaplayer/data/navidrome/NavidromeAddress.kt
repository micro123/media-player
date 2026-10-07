package io.github.micro123.mediaplayer.data.navidrome

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** Stable queue/bookmark identity. Authentication is resolved at playback time, never persisted in a URL. */
class NavidromeAddress private constructor(val server: String, val profileId: String, val route: List<String>) {
    val address: String get() {
        val base = URI(server)
        return "navidrome+${base.scheme}://${base.rawAuthority}${base.rawPath}?profile=$profileId" +
            if (route.isEmpty()) "" else "#" + route.joinToString("/") { encode(it) }
    }
    fun at(vararg parts: String): String = NavidromeAddress(server, profileId, parts.toList()).address
    fun parent(): String = when (route.firstOrNull()) {
        "album" -> at("albums", "0")
        "artist" -> at("artists")
        "playlist" -> at("playlists")
        else -> at()
    }
    fun matches(other: NavidromeAddress) = server == other.server && profileId == other.profileId

    companion object {
        fun fromServer(value: String, profileId: String = UUID.randomUUID().toString()): NavidromeAddress {
            require(UUID.fromString(profileId).toString() == profileId) { "服务器书签标识无效" }
            val uri = runCatching { URI(value.trim()) }.getOrElse { error("请输入完整的 Navidrome 服务器地址") }
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.port in -1..65535) {
                "请输入 http:// 或 https:// 开头的服务器地址"
            }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "服务器地址不能包含账号、查询参数或片段" }
            require(value.length <= 4096 && uri.path.orEmpty().split('/').none { it == "." || it == ".." }) { "服务器路径无效" }
            val scheme = uri.scheme.lowercase()
            val port = uri.port.takeUnless { it == if (scheme == "https") 443 else 80 } ?: -1
            val normalized = URI(scheme, null, uri.host.lowercase(), port, uri.path.orEmpty().trimEnd('/'), null, null).toASCIIString()
            return NavidromeAddress(normalized, profileId, emptyList())
        }

        fun parse(value: String): NavidromeAddress {
            val uri = runCatching { URI(value) }.getOrElse { error("Navidrome 地址无效") }
            require(uri.scheme in setOf("navidrome+http", "navidrome+https")) { "不是 Navidrome 地址" }
            require(uri.rawUserInfo == null && uri.rawQuery?.matches(Regex("profile=[0-9a-f-]{36}")) == true) { "服务器书签标识无效" }
            val root = fromServer(uri.toString().substringBefore('?').removePrefix("navidrome+"), uri.rawQuery!!.substringAfter('='))
            val parts = uri.rawFragment?.split('/')?.map { URLDecoder.decode(it, "UTF-8") }.orEmpty()
            require(parts.size <= 3 && parts.all { it.isNotBlank() && it.length <= 1024 && '\u0000' !in it }) { "音乐库路径无效" }
            return NavidromeAddress(root.server, root.profileId, parts)
        }

        internal fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
