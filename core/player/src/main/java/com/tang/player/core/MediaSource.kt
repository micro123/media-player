package com.tang.player.core

import android.content.Context
import androidx.core.net.toUri

/** A playback address is stable identity; native input and its lifetime are resolved separately. */
enum class MediaSourceKind { LOCAL, HTTP, SMB, NFS, UNSUPPORTED }

fun mediaSourceKind(address: String): MediaSourceKind = when (address.substringBefore(':', "").lowercase()) {
    "content", "file" -> MediaSourceKind.LOCAL
    "http", "https" -> MediaSourceKind.HTTP
    "smb" -> MediaSourceKind.SMB
    "nfs" -> MediaSourceKind.NFS
    else -> MediaSourceKind.UNSUPPORTED
}

/** A provider can return fd://, a URL, or a future stream bridge. The engine owns the handle. */
class OpenedMediaSource(val input: String, private val closeHandle: () -> Unit = {}) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) closeHandle() }
}

fun interface PlaybackSourceResolver {
    /** Runs on the engine worker, never the UI thread. */
    fun open(media: MediaItem): OpenedMediaSource
}

class AndroidPlaybackSourceResolver(context: Context) : PlaybackSourceResolver {
    private val resolver = context.applicationContext.contentResolver
    override fun open(media: MediaItem): OpenedMediaSource = when (mediaSourceKind(media.uri)) {
        MediaSourceKind.LOCAL -> {
            val fd = resolver.openFileDescriptor(media.uri.toUri(), "r") ?: error("无法打开文件，请重新选择。")
            try { OpenedMediaSource("fd://${fd.fd}") { fd.close() } }
            catch (error: Throwable) { fd.close(); throw error }
        }
        MediaSourceKind.HTTP -> OpenedMediaSource(media.uri)
        MediaSourceKind.SMB, MediaSourceKind.NFS -> error("此网络位置已保留，尚未安装 ${media.uri.substringBefore(':').uppercase()} 来源适配器。")
        MediaSourceKind.UNSUPPORTED -> error("不支持的媒体地址。")
    }
}
