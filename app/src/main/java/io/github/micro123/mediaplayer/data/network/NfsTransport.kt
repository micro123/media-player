package io.github.micro123.mediaplayer.data.network

/** NFS v3 / TCP, AUTH_SYS, read-only. The saved location is the actual exported root. */
class NfsTransport : RemoteTransport {
    private fun connect(address: RemoteAddress, profile: NetworkProfile): Session {
        val root = RemoteAddress.parse(profile.address)
        require(root.contains(address)) { "文件不在已保存的 NFS 导出目录内" }
        val mountPort = NfsRpc.port(address.host, address.effectivePort(), 100005, 3)
        val nfsPort = NfsRpc.port(address.host, address.effectivePort(), 100003, 3)
        val handle = NfsRpc(address.host, mountPort, profile.credentials).use { mount ->
            val reply = mount.call(100005, 3, 1) { string("/" + root.parts.joinToString("/")) }
            status(reply.int(), "挂载导出目录")
            val fileHandle = reply.opaque(64)
            require(fileHandle.isNotEmpty()) { "NFS 导出文件句柄为空" }
            val count = reply.uint(); require(count <= 64) { "NFS 认证类型过多" }
            val flavors = List(count.toInt()) { reply.int() }
            require(flavors.isEmpty() || 1 in flavors) { "此 NFS 导出需要 AUTH_SYS 以外的认证，暂不支持" }
            fileHandle
        }
        val session = Session(NfsRpc(address.host, nfsPort, profile.credentials))
        try {
            var current = handle
            for (part in address.parts.drop(root.parts.size)) current = session.lookup(current, part).first
            session.handle = current
            return session
        } catch (error: Throwable) { session.close(); throw error }
    }
    override fun stat(address: RemoteAddress, profile: NetworkProfile): RemoteFileInfo = connect(address, profile).use {
        val attr = it.getattr(it.handle)
        RemoteFileInfo(address.parts.last(), attr.type == 2, attr.size)
    }
    override fun list(address: RemoteAddress, profile: NetworkProfile): List<RemoteFileInfo> = connect(address, profile).use { it.list() }
    override fun open(address: RemoteAddress, profile: NetworkProfile): RemoteReadHandle {
        val session = connect(address, profile)
        try {
            val attr = session.getattr(session.handle)
            require(attr.type == 1) { "此 NFS 位置不是普通文件（符号链接暂不支持）" }
            return object : RemoteReadHandle {
                override val size = attr.size
                private var closed = false
                @Synchronized override fun read(offset: Long, bytes: ByteArray, bufferOffset: Int, length: Int): Int {
                    checkRead(offset, bytes, bufferOffset, length); check(!closed) { "NFS 连接已关闭" }
                    if (offset >= size || length == 0) return 0
                    val count = minOf(length.toLong(), size - offset, READ_SIZE.toLong()).toInt()
                    val reply = session.call(6) { opaque(session.handle); long(offset); int(count) }
                    status(reply.int(), "读取文件"); session.postAttr(reply)
                    val received = reply.uint(); val eof = reply.bool(); val data = reply.opaque(READ_SIZE)
                    require(received == data.size.toLong() && received <= count) { "NFS 读取长度不匹配" }
                    require(data.isNotEmpty() || eof) { "NFS 读取没有进展" }
                    data.copyInto(bytes, bufferOffset); return data.size
                }
                @Synchronized override fun close() { if (!closed) { closed = true; session.close() } }
            }
        } catch (error: Throwable) { session.close(); throw error }
    }

    private data class Attr(val type: Int, val size: Long)
    private class Session(private val rpc: NfsRpc) : AutoCloseable {
        var handle = byteArrayOf()
        fun call(procedure: Int, arguments: XdrWriter.() -> Unit) = rpc.call(100003, 3, procedure, arguments)
        private fun attr(reply: XdrReader): Attr {
            val type = reply.int(); repeat(4) { reply.int() }
            val size = reply.long(); require(size >= 0) { "NFS 文件大小超出范围" }
            reply.fixed(56)
            return Attr(type, size)
        }
        fun postAttr(reply: XdrReader): Attr? = if (reply.bool()) attr(reply) else null
        fun getattr(fh: ByteArray): Attr {
            val reply = call(1) { opaque(fh) }; status(reply.int(), "读取属性"); return attr(reply)
        }
        fun lookup(fh: ByteArray, name: String): Pair<ByteArray, Attr?> {
            val reply = call(3) { opaque(fh); string(name) }; status(reply.int(), "查找文件")
            val next = reply.opaque(64); require(next.isNotEmpty()) { "NFS 文件句柄为空" }
            val attr = postAttr(reply); postAttr(reply); return next to attr
        }
        fun list(): List<RemoteFileInfo> {
            require(getattr(handle).type == 2) { "此 NFS 位置不是目录" }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
            val entries = linkedMapOf<String, RemoteFileInfo>()
            var cookie = 0L; var verifier = ByteArray(8); var plus = true
            repeat(1024) {
                check(System.nanoTime() < deadline) { "NFS 目录读取超时，请选择更小的目录或重试" }
                val reply = call(if (plus) 17 else 16) {
                    opaque(handle); long(cookie); fixed(verifier)
                    int(32 * 1024); if (plus) int(128 * 1024)
                }
                val code = reply.int()
                if (code == 10004 && plus && cookie == 0L) { plus = false; return@repeat }
                status(code, "列出目录"); postAttr(reply); verifier = reply.fixed(8)
                val previous = cookie
                while (reply.bool()) {
                    check(System.nanoTime() < deadline) { "NFS 目录读取超时，请选择更小的目录或重试" }
                    reply.long(); val name = reply.string(255); cookie = reply.long()
                    val provided = if (plus) postAttr(reply) else null
                    val fh = if (plus && reply.bool()) reply.opaque(64) else null
                    if (name in listOf(".", "..")) continue
                    require(name.isNotBlank() && '/' !in name && '\u0000' !in name) { "NFS 文件名无效" }
                    val info = provided ?: (fh?.let { getattr(it) } ?: lookup(handle, name).let { it.second ?: getattr(it.first) })
                    // Never traverse symbolic links or devices outside the chosen export root.
                    if (info.type in listOf(1, 2)) entries[name] = RemoteFileInfo(name, info.type == 2, info.size)
                    require(entries.size <= SmbTransport.MAX_ENTRIES) { "目录超过 ${SmbTransport.MAX_ENTRIES} 项，请使用更具体的子目录" }
                }
                if (reply.bool()) return entries.values.toList()
                require(cookie != previous) { "NFS 目录分页没有进展" }
            }
            error("NFS 目录分页过多")
        }
        override fun close() { rpc.close() }
    }
    companion object {
        private const val READ_SIZE = 128 * 1024
        private fun status(code: Int, operation: String) {
            if (code == 0) return
            val detail = when (code) {
                1, 13 -> "访问被拒绝，请检查导出权限、UID / GID；安卓使用非特权源端口，服务端需允许 insecure"
                2 -> "文件或导出目录不存在"
                20 -> "路径不是目录"
                70 -> "文件句柄失效，请刷新目录"
                10003 -> "目录已改变，请刷新后重试"
                10004 -> "服务器不支持该操作"
                else -> "服务器状态 $code"
            }
            throw RemoteAccessException("NFS $operation 失败：$detail")
        }
    }
}
