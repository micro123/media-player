package com.tang.player.data.network

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors

class NfsRpcTest {
    @Test fun xdrPaddingUnsignedValuesAnd64BitOffsets() {
        val writer = XdrWriter().apply { uint(0xffffffffL); string("中文"); opaque(byteArrayOf(1, 2, 3)); long(5L * 1024 * 1024 * 1024); bool(true) }
        val reader = XdrReader(writer.bytes())
        assertEquals(0xffffffffL, reader.uint()); assertEquals("中文", reader.string())
        assertArrayEquals(byteArrayOf(1, 2, 3), reader.opaque()); assertEquals(5L * 1024 * 1024 * 1024, reader.long())
        assertTrue(reader.bool()); assertEquals(0, reader.remaining())
    }
    @Test fun xdrRejectsMaliciousLengthsAndTruncation() {
        for (bytes in listOf(XdrWriter().apply { int(-1) }.bytes(), XdrWriter().apply { int(4096); int(0) }.bytes(),
            XdrWriter().apply { int(3); fixed(byteArrayOf(1, 2, 3)) }.bytes()))
            assertThrows(IllegalArgumentException::class.java) { XdrReader(bytes).opaque(64) }
        assertThrows(RemoteAccessException::class.java) { XdrReader(XdrWriter().apply { int(2) }.bytes()).bool() }
    }
    private fun server(reply: (ByteArray) -> ByteArray, action: (Int) -> Unit) {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val job = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = DataInputStream(socket.getInputStream()); val size = input.readInt() and Int.MAX_VALUE
                    val request = ByteArray(size); input.readFully(request)
                    val response = reply(request); val output = DataOutputStream(socket.getOutputStream())
                    // Two TCP RPC fragments, including a header split across them.
                    output.writeInt(7); output.write(response, 0, 7)
                    output.writeInt((response.size - 7) or Int.MIN_VALUE); output.write(response, 7, response.size - 7); output.flush()
                }
            }
            try { action(server.localPort); job.get() } finally { executor.shutdownNow() }
        }
    }
    @Test fun wireUsesAuthSysAndReassemblesFragmentedReply() = server({ bytes ->
        val request = XdrReader(bytes); val id = request.int()
        assertEquals(0, request.int()); assertEquals(2, request.int()); assertEquals(100003, request.int())
        assertEquals(3, request.int()); assertEquals(6, request.int()); assertEquals(1, request.int())
        val auth = XdrReader(request.opaque(400)); auth.int(); assertEquals("android-player", auth.string())
        assertEquals(1001L, auth.uint()); assertEquals(2002L, auth.uint()); assertEquals(0, auth.int())
        assertEquals(0, request.int()); assertArrayEquals(byteArrayOf(), request.opaque())
        assertEquals(5L * 1024 * 1024 * 1024, request.long())
        XdrWriter().apply { int(id); int(1); int(0); int(0); opaque(byteArrayOf()); int(0); int(42) }.bytes()
    }) { port ->
        NfsRpc("127.0.0.1", port, NetworkCredentials(uid = 1001, gid = 2002)).use {
            assertEquals(42, it.call(100003, 3, 6) { long(5L * 1024 * 1024 * 1024) }.int())
        }
    }
    @Test fun deniedReplyIsAnExplicitAuthenticationError() = server({ bytes ->
        XdrWriter().apply { int(XdrReader(bytes).int()); int(1); int(1); int(1); int(1) }.bytes()
    }) { port -> NfsRpc("127.0.0.1", port, NetworkCredentials()).use { rpc ->
        val error = assertThrows(RemoteAccessException::class.java) { rpc.call(100003, 3, 1) }
        assertTrue(error.message!!.contains("认证"))
    } }
    @Test fun mismatchedReplyCannotBeReused() = server({ bytes ->
        XdrWriter().apply { int(XdrReader(bytes).int() + 1); int(1); int(0); int(0); opaque(byteArrayOf()); int(0) }.bytes()
    }) { port -> NfsRpc("127.0.0.1", port, null).use { rpc ->
        assertThrows(IllegalArgumentException::class.java) { rpc.call(100003, 3, 1) }
        assertThrows(java.net.SocketException::class.java) { rpc.call(100003, 3, 1) }
    } }
}
