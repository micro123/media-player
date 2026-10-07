package io.github.micro123.mediaplayer.data.network

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import io.github.micro123.mediaplayer.core.*
import java.util.concurrent.atomic.AtomicBoolean

/** Seekable FUSE descriptor keeps SMB/NFS details outside mpv and avoids full-file downloads. */
class RemotePlaybackSourceResolver(context: Context, private val remoteOpen: (String) -> RemoteReadHandle,
    private val local: PlaybackSourceResolver = AndroidPlaybackSourceResolver(context)) : PlaybackSourceResolver {
    private val storage = context.applicationContext.getSystemService(StorageManager::class.java)
    override fun open(media: MediaItem): OpenedMediaSource {
        if (media.sourceKind !in setOf(MediaSourceKind.SMB, MediaSourceKind.NFS)) return local.open(media)
        val file = remoteOpen(media.uri)
        val worker = HandlerThread("remote-media-read").apply { start() }
        val released = AtomicBoolean()
        fun release() { if (released.compareAndSet(false, true)) { runCatching { file.close() }; worker.quitSafely() } }
        try {
            val fd = storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
                override fun onGetSize() = file.size
                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    try {
                        if (released.get()) throw ErrnoException("remote-read", OsConstants.EBADF)
                        var total = 0
                        // mpv expects regular-file semantics, including short remote READ replies.
                        while (total < size && offset + total < file.size) {
                            val count = file.read(offset + total, data, total, size - total)
                            if (count <= 0) throw RemoteAccessException("远程文件读取中断")
                            require(count <= size - total); total += count
                        }
                        return total
                    } catch (error: Exception) { throw ErrnoException("remote-read", OsConstants.EIO, error) }
                }
                override fun onRelease() { release() }
            }, Handler(worker.looper))
            return OpenedMediaSource("fd://${fd.fd}") { try { fd.close() } finally { release() } }
        } catch (error: Throwable) { release(); throw error }
    }
}
