package io.github.micro123.mediaplayer.data.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Bounded XDR reader: untrusted lengths are checked before allocation. */
internal class XdrReader(bytes: ByteArray) {
    private val input = DataInputStream(ByteArrayInputStream(bytes))
    fun int(): Int = input.readInt()
    fun uint(): Long = int().toLong() and 0xffffffffL
    fun long(): Long = input.readLong()
    fun bool(): Boolean = when (int()) { 0 -> false; 1 -> true; else -> throw RemoteAccessException("无效的 NFS 布尔字段") }
    fun fixed(length: Int): ByteArray {
        require(length >= 0 && length <= input.available()) { "NFS 响应截断" }
        return ByteArray(length).also { input.readFully(it) }
    }
    fun opaque(max: Int = 1024 * 1024): ByteArray {
        val length = uint(); require(length <= max && length <= input.available()) { "NFS 响应字段过大或截断" }
        val data = fixed(length.toInt()); fixed((4 - length.toInt() % 4) % 4); return data
    }
    fun string(max: Int = 1024) = String(opaque(max), Charsets.UTF_8)
    fun remaining() = input.available()
}

internal class XdrWriter {
    private val bytes = ByteArrayOutputStream()
    private val output = DataOutputStream(bytes)
    fun int(value: Int) { output.writeInt(value) }
    fun uint(value: Long) { require(value in 0..0xffffffffL); int(value.toInt()) }
    fun long(value: Long) { output.writeLong(value) }
    fun bool(value: Boolean) { int(if (value) 1 else 0) }
    fun fixed(value: ByteArray) { output.write(value) }
    fun opaque(value: ByteArray) { int(value.size); fixed(value); repeat((4 - value.size % 4) % 4) { output.writeByte(0) } }
    fun string(value: String) = opaque(value.toByteArray(Charsets.UTF_8))
    fun bytes(): ByteArray = bytes.toByteArray()
}

/** ONC RPC v2 over TCP record marking. No remote mutation RPC is exposed. */
internal class NfsRpc(host: String, port: Int, private val credentials: NetworkCredentials?, timeoutMs: Int = 10000) : AutoCloseable {
    private val socket = Socket().apply {
        try { connect(InetSocketAddress(host, port), timeoutMs); soTimeout = timeoutMs; tcpNoDelay = true }
        catch (error: Exception) { close(); throw error }
    }
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    @Synchronized fun call(program: Int, version: Int, procedure: Int, arguments: XdrWriter.() -> Unit = {}): XdrReader {
        val id = nextId.incrementAndGet()
        val request = XdrWriter().apply {
            int(id); int(0); int(2); int(program); int(version); int(procedure)
            if (credentials == null) { int(0); opaque(byteArrayOf()) } else {
                int(1); opaque(XdrWriter().apply {
                    int(id); string("android-player"); uint(credentials.uid); uint(credentials.gid); int(0)
                }.bytes())
            }
            int(0); opaque(byteArrayOf()); arguments()
        }.bytes()
        try {
            output.writeInt(request.size or Int.MIN_VALUE); output.write(request); output.flush()
            val result = ByteArrayOutputStream()
            var fragments = 0
            do {
                val marker = input.readInt()
                val count = marker and Int.MAX_VALUE
                require(++fragments <= 64 && count <= MAX_RECORD - result.size()) { "NFS RPC 响应过大" }
                val data = ByteArray(count); input.readFully(data); result.write(data)
            } while (marker >= 0)
            val response = XdrReader(result.toByteArray())
            require(response.int() == id && response.int() == 1) { "NFS RPC 响应不匹配" }
            if (response.int() != 0) throw RemoteAccessException("NFS RPC 认证被拒绝，请检查 UID / GID 与导出权限")
            response.int(); response.opaque(400)
            val accepted = response.int()
            if (accepted != 0) throw RemoteAccessException("NFS RPC 服务不可用（状态 $accepted），需要 NFS v3 / TCP")
            return response
        } catch (error: Exception) {
            // Do not reuse a socket after a malformed reply, truncation or timeout.
            runCatching { socket.close() }
            throw error
        }
    }
    override fun close() { socket.close() }
    companion object {
        private val nextId = AtomicInteger(java.security.SecureRandom().nextInt())
        private const val MAX_RECORD = 2 * 1024 * 1024
        fun port(host: String, rpcPort: Int, program: Int, version: Int): Int = NfsRpc(host, rpcPort, null).use {
            val response = it.call(100000, 2, 3) { int(program); int(version); int(6); int(0) }
            val value = response.uint()
            require(value in 1..65535) { "服务器未提供 NFS v3 / TCP 的 ${if (program == 100005) "mountd" else "nfsd"} 服务" }
            value.toInt()
        }
    }
}
