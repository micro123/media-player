package io.github.micro123.mediaplayer.data.network

import io.github.micro123.mediaplayer.core.MediaSourceKind
import io.github.micro123.mediaplayer.core.mediaSourceKind
import io.github.micro123.mediaplayer.data.validateNetworkAddress
import java.io.IOException
import java.net.URI

data class NetworkCredentials(val guest: Boolean = true, val username: String = "", val password: String = "",
    val domain: String = "", val uid: Long = 65534, val gid: Long = 65534) {
    init {
        require(uid in 0..0xffffffffL && gid in 0..0xffffffffL) { "UID / GID 须为 0～4294967295" }
        require(username.length <= 256 && domain.length <= 256 && password.length <= 4096) { "认证信息过长" }
    }
    override fun toString() = "NetworkCredentials(redacted)"
}

data class NetworkProfile(val id: String, val address: String, val credentials: NetworkCredentials)

/** Remote URIs are identities, never credentials or native player commands. */
class RemoteAddress private constructor(val uri: URI, val parts: List<String>) {
    val kind get() = mediaSourceKind(uri.toASCIIString())
    val host get() = uri.host.removePrefix("[").removeSuffix("]")
    val address get() = uri.toASCIIString()
    fun child(name: String): String {
        require(name.isNotBlank() && name !in listOf(".", "..") && name.none { it == '/' || it == '\\' || it == '\u0000' }) { "服务器返回了无效文件名" }
        return URI(uri.scheme, null, uri.host, uri.port, "/" + (parts + name).joinToString("/"), null, null).toASCIIString()
    }
    fun contains(other: RemoteAddress): Boolean = kind == other.kind && host.equals(other.host, true) &&
        effectivePort() == other.effectivePort() && parts.size <= other.parts.size && parts == other.parts.take(parts.size)
    fun effectivePort() = if (uri.port >= 0) uri.port else if (kind == MediaSourceKind.SMB) 445 else 111
    fun parent(): String = URI(uri.scheme, null, uri.host, uri.port, "/" + parts.dropLast(1).joinToString("/"), null, null).toASCIIString()
    companion object {
        fun parse(value: String): RemoteAddress {
            val uri = URI(validateNetworkAddress(value, true))
            require(mediaSourceKind(value.trim()) in setOf(MediaSourceKind.SMB, MediaSourceKind.NFS)) { "请输入 SMB 或 NFS 位置" }
            require(uri.rawQuery == null && uri.rawFragment == null) { "SMB / NFS 地址不能包含查询参数或片段，文件名中的 # 请写为 %23" }
            require(uri.port != 0) { "端口须为 1～65535" }
            val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
            require(parts.isNotEmpty()) { "请填写共享目录 / 导出目录，例如 smb://服务器/Movies 或 nfs://服务器/volume1/video" }
            require(parts.none { it in listOf(".", "..") || it.contains('\\') || it.contains('\u0000') }) { "目录路径无效" }
            // Encoded separators must not silently change share or export boundaries.
            require(!Regex("%2f|%5c", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawPath.orEmpty())) { "路径不能包含编码的分隔符" }
            val normalized = URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port,
                "/" + parts.joinToString("/"), null, null)
            return RemoteAddress(normalized, parts)
        }
    }
}

data class RemoteFileInfo(val name: String, val directory: Boolean, val size: Long = 0)

interface RemoteReadHandle : AutoCloseable {
    val size: Long
    /** Returns 0 only at EOF. Supports 64-bit offsets; no sequential-only streams. */
    fun read(offset: Long, bytes: ByteArray, bufferOffset: Int, length: Int): Int
}

interface RemoteTransport {
    fun list(address: RemoteAddress, profile: NetworkProfile): List<RemoteFileInfo>
    fun stat(address: RemoteAddress, profile: NetworkProfile): RemoteFileInfo
    fun open(address: RemoteAddress, profile: NetworkProfile): RemoteReadHandle
}

internal fun checkRead(offset: Long, bytes: ByteArray, bufferOffset: Int, length: Int) {
    require(offset >= 0 && bufferOffset >= 0 && length >= 0 && bufferOffset <= bytes.size - length)
}

class RemoteAccessException(message: String, cause: Throwable? = null,
    val kind: NetworkFailureKind = NetworkFailureKind.OTHER) : IOException(message, cause)
