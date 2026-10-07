package io.github.micro123.mediaplayer.data.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.share.DiskShare
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** SMB 2/3 only. Every open explicitly uses FILE_OPEN and read-only access. */
class SmbTransport : RemoteTransport {
    private fun connect(address: RemoteAddress, credentials: NetworkCredentials): Connection {
        val config = SmbConfig.builder().withSecurityProvider(BCSecurityProvider())
            .withAuthenticators(NtlmAuthenticator.Factory())
            .withTimeout(10, TimeUnit.SECONDS).withSoTimeout(15, TimeUnit.SECONDS)
            .withReadBufferSize(256 * 1024).withDfsEnabled(false)
            .withSocketFactory(object : SocketFactory() {
                override fun createSocket() = Socket()
                override fun createSocket(host: String, port: Int) = Socket().apply { connect(InetSocketAddress(host, port), 10000) }
                override fun createSocket(host: String, port: Int, local: java.net.InetAddress, localPort: Int) =
                    Socket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port), 10000) }
                override fun createSocket(host: java.net.InetAddress, port: Int) = createSocket(host.hostAddress!!, port)
                override fun createSocket(host: java.net.InetAddress, port: Int, local: java.net.InetAddress, localPort: Int) = createSocket(host.hostAddress!!, port, local, localPort)
            }).build()
        val client = SMBClient(config)
        var stage = NetworkStage.CONNECT
        try {
            val connection = client.connect(address.host, address.effectivePort())
            try {
                val auth = if (credentials.guest) AuthenticationContext.guest()
                    else AuthenticationContext(credentials.username, credentials.password.toCharArray(), credentials.domain)
                stage = NetworkStage.AUTHENTICATE
                val session = connection.authenticate(auth)
                stage = NetworkStage.SHARE
                val share = session.connectShare(address.parts.first())
                require(share is DiskShare) { "此 SMB 共享不是文件目录" }
                return Connection(client, connection, session, share)
            } catch (error: Exception) { runCatching { connection.close() }; throw error }
        } catch (error: Exception) { runCatching { client.close() }; throw explainNetworkFailure("SMB", stage, error) }
    }
    override fun list(address: RemoteAddress, profile: NetworkProfile): List<RemoteFileInfo> = connect(address, profile.credentials).use { connection ->
        connection.share.openDirectory(address.parts.drop(1).joinToString("\\"), setOf(AccessMask.FILE_LIST_DIRECTORY),
            setOf(FileAttributes.FILE_ATTRIBUTE_DIRECTORY), SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, setOf(SMB2CreateOptions.FILE_DIRECTORY_FILE)).use { directory ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            val result = ArrayList<RemoteFileInfo>()
            for (entry in directory) {
                check(System.nanoTime() < deadline) { "SMB 目录读取超时，请选择更小的目录或重试" }
                if (entry.fileName in listOf(".", "..")) continue
                check(result.size < MAX_ENTRIES) { "目录超过 $MAX_ENTRIES 项，请使用更具体的子目录" }
                result += RemoteFileInfo(entry.fileName, entry.fileAttributes and 0x10L != 0L, entry.endOfFile)
            }
            result
        }
    }
    override fun stat(address: RemoteAddress, profile: NetworkProfile): RemoteFileInfo = connect(address, profile.credentials).use {
        val info = it.share.getFileInformation(address.parts.drop(1).joinToString("\\"))
        RemoteFileInfo(address.parts.last(), info.standardInformation.isDirectory, info.standardInformation.endOfFile)
    }
    override fun open(address: RemoteAddress, profile: NetworkProfile): RemoteReadHandle {
        val connection = connect(address, profile.credentials)
        try {
            val file = connection.share.openFile(address.parts.drop(1).joinToString("\\"), setOf(AccessMask.GENERIC_READ),
                setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
                setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            try {
                val fileSize = file.fileInformation.standardInformation.endOfFile
                require(fileSize >= 0) { "SMB 文件大小超出范围" }
                return object : RemoteReadHandle {
                    override val size = fileSize
                    private var closed = false
                    @Synchronized override fun read(offset: Long, bytes: ByteArray, bufferOffset: Int, length: Int): Int {
                        checkRead(offset, bytes, bufferOffset, length); check(!closed) { "连接已关闭" }
                        if (offset >= size || length == 0) return 0
                        return file.read(bytes, offset, bufferOffset, minOf(length.toLong(), size - offset, 256L * 1024).toInt()).coerceAtLeast(0)
                    }
                    @Synchronized override fun close() { if (!closed) { closed = true; runCatching { file.close() }; connection.close() } }
                }
            } catch (error: Throwable) { runCatching { file.close() }; throw error }
        } catch (error: Throwable) { connection.close(); throw error }
    }
    private class Connection(val client: SMBClient, val connection: com.hierynomus.smbj.connection.Connection,
        val session: com.hierynomus.smbj.session.Session, val share: DiskShare) : AutoCloseable {
        override fun close() { runCatching { share.close() }; runCatching { session.close() }; runCatching { connection.close() }; runCatching { client.close() } }
    }
    companion object { const val MAX_ENTRIES = 10000 }
}
