package com.tang.player.data

import com.tang.player.core.MediaItem
import com.tang.player.core.MediaSourceKind
import com.tang.player.core.mediaSourceKind
import java.net.URI

data class SourceCapabilities(val playable: Boolean, val browsable: Boolean = false)

/** SMB/NFS plugins implement this boundary instead of changing queues, history or the player UI. */
interface MediaSourceProvider {
    val kind: MediaSourceKind
    val capabilities: SourceCapabilities
    suspend fun resolve(address: String): MediaItem
    suspend fun list(address: String): List<SourceEntry> = error("此来源暂不支持浏览目录")
}

data class SourceEntry(val address: String, val name: String, val directory: Boolean, val media: MediaItem? = null)

class MediaSourceRegistry(providers: List<MediaSourceProvider>) {
    private val providers = providers.associateBy { it.kind }
    suspend fun resolve(address: String): MediaItem {
        val kind = mediaSourceKind(address)
        val provider = providers[kind] ?: error("尚未接入 ${kind.name} 来源适配器，可先保存网络位置书签。")
        check(provider.capabilities.playable) { "此来源暂不支持播放" }
        return provider.resolve(address)
    }
    suspend fun list(address: String): List<SourceEntry> {
        val provider = providers[mediaSourceKind(address)] ?: error("此来源尚未接入目录适配器")
        check(provider.capabilities.browsable) { "此来源暂不支持目录浏览" }
        return provider.list(address)
    }
}

/** No implicit protocol guessing, command protocols, or embedded passwords in saved addresses. */
fun validateNetworkAddress(value: String, allowFutureSources: Boolean = false): String {
    val address = value.trim()
    val uri = runCatching { URI(address) }.getOrElse { error("地址格式无效，请填写完整 URL") }
    val kind = mediaSourceKind(address)
    require(kind == MediaSourceKind.HTTP || (allowFutureSources && kind in setOf(MediaSourceKind.SMB, MediaSourceKind.NFS))) {
        "请输入 http://、https://${if (allowFutureSources) "、smb:// 或 nfs://" else " 地址"}"
    }
    require(!uri.host.isNullOrBlank()) { "地址缺少服务器名称或 IP" }
    require(uri.rawUserInfo == null) { "地址中不能包含账号密码；账号认证将在网络来源接入时独立配置" }
    require(uri.port in -1..65535) { "端口无效" }
    require(address.length <= 8192) { "地址过长" }
    return address
}

class HttpMediaSourceProvider : MediaSourceProvider {
    override val kind = MediaSourceKind.HTTP
    override val capabilities = SourceCapabilities(playable = true)
    override suspend fun resolve(address: String): MediaItem {
        val validated = validateNetworkAddress(address)
        val path = URI(validated).path.orEmpty()
        val name = path.substringAfterLast('/').ifBlank { URI(validated).host }
        // Unknown network media defaults to video; audio extensions are still classified as audio.
        val mime = if (name.endsWith(".m3u8", true)) "video/mp2t" else mimeFromName(name) ?: "video/*"
        return MediaItem(validated, name, mime, null)
    }
}
