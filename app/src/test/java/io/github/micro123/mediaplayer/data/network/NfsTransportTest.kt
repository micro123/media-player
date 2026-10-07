package io.github.micro123.mediaplayer.data.network

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList

class NfsTransportTest {
    /** Deterministic RPC peer exercises PLUS pagination/optional fields beyond UNFS3's READDIR. */
    private class Peer(val noProgress: Boolean = false, val wrongReadCount: Boolean = false, val stale: Boolean = false) : AutoCloseable {
        private val server = ServerSocket(0)
        private val workers = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<Socket>()
        val root = RemoteAddress.parse("nfs://127.0.0.1:${server.localPort}/export")
        val profile = NetworkProfile("fixture", root.address, NetworkCredentials(uid = 1000, gid = 1000))
        private val verifier = ByteArray(8) { (it + 7).toByte() }
        init {
            workers.submit {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    sockets += socket
                    workers.submit {
                        socket.use {
                            val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
                            while (!socket.isClosed) {
                                val size = try { input.readInt() and Int.MAX_VALUE } catch (_: Exception) { break }
                                val bytes = ByteArray(size); input.readFully(bytes)
                                val response = reply(bytes)
                                output.writeInt(response.size or Int.MIN_VALUE); output.write(response); output.flush()
                            }
                        }
                    }
                }
            }
        }
        private fun XdrWriter.attr(type: Int = 1) {
            int(type); repeat(4) { int(0) }; long(500000); fixed(ByteArray(56))
        }
        private fun XdrWriter.post(type: Int = 1) { bool(true); attr(type) }
        private fun reply(bytes: ByteArray): ByteArray {
            val request = XdrReader(bytes); val id = request.int(); request.int(); request.int()
            val program = request.int(); request.int(); val procedure = request.int()
            request.int(); request.opaque(400); request.int(); request.opaque(400)
            return XdrWriter().apply {
                int(id); int(1); int(0); int(0); opaque(byteArrayOf()); int(0)
                when (program) {
                    100000 -> int(server.localPort)
                    100005 -> { int(0); opaque("root".toByteArray()); int(1); int(1) }
                    100003 -> when (procedure) {
                        1 -> {
                            val fh = String(request.opaque(64)); int(0)
                            attr(if (fh in listOf("root", "folder")) 2 else if (fh == "link") 3 else 1)
                        }
                        3 -> {
                            request.opaque(64); val name = request.string()
                            int(0); opaque(if (name == "link.mp4") "link".toByteArray() else name.toByteArray()); bool(false); bool(false)
                        }
                        17 -> {
                            request.opaque(64); val cookie = request.long(); val incoming = request.fixed(8); request.int(); request.int()
                            if (cookie > 0) assertArrayEquals(verifier, incoming)
                            int(0); post(2); fixed(verifier)
                            if (cookie == 0L) {
                                bool(true); long(1); string("episode-2.mp4"); long(10); post(); bool(true); opaque("file".toByteArray())
                                bool(true); long(2); string("folder"); long(20); bool(false); bool(true); opaque("folder".toByteArray())
                                bool(true); long(3); string("link.mp4"); long(30); post(3); bool(false)
                                bool(false); bool(false)
                            } else if (noProgress) { bool(false); bool(false) }
                            else {
                                bool(true); long(4); string("episode-10.mp4"); long(40); bool(false); bool(false)
                                bool(false); bool(true)
                            }
                        }
                        6 -> {
                            request.opaque(64); val offset = request.long(); val count = request.int()
                            if (stale) int(70) else {
                                val received = minOf(count, 997, (500000 - offset).toInt())
                                int(0); bool(false); int(if (wrongReadCount) received + 1 else received); bool(false)
                                opaque(ByteArray(received) { ((offset + it) % 251).toByte() })
                            }
                        }
                        else -> error("unexpected fixture RPC $procedure")
                    }
                    else -> error("unexpected fixture program $program")
                }
            }.bytes()
        }
        override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow() }
    }
    @Test fun plusPaginationHandlesOptionalAttributesAndHandlesAndSkipsLinks() {
        Peer().use { peer ->
            val entries = NfsTransport().list(peer.root, peer.profile)
            assertEquals(listOf("episode-2.mp4", "folder", "episode-10.mp4"), entries.map { it.name })
            assertTrue(entries[1].directory); assertFalse(entries[0].directory)
        }
    }
    @Test fun nonAdvancingDirectoryCookieFailsInsteadOfLooping() {
        Peer(noProgress = true).use { peer ->
            assertThrows(IllegalArgumentException::class.java) { NfsTransport().list(peer.root, peer.profile) }
        }
    }
    @Test fun shortReadPreservesOffsetAndDestinationSlice() {
        Peer().use { peer ->
            NfsTransport().open(RemoteAddress.parse(peer.root.child("video.mp4")), peer.profile).use { file ->
                val bytes = ByteArray(2010) { -1 }
                assertEquals(997, file.read(3001, bytes, 7, 2000))
                assertEquals((3001 % 251).toByte(), bytes[7]); assertEquals((-1).toByte(), bytes[6])
                assertEquals((-1).toByte(), bytes[1004])
            }
        }
    }
    @Test fun wrongReadCountFailsAndStaleHandleIsActionable() {
        Peer(wrongReadCount = true).use { peer -> NfsTransport().open(RemoteAddress.parse(peer.root.child("video.mp4")), peer.profile).use { file ->
            assertThrows(IllegalArgumentException::class.java) { file.read(0, ByteArray(500), 0, 500) }
        } }
        Peer(stale = true).use { peer -> NfsTransport().open(RemoteAddress.parse(peer.root.child("video.mp4")), peer.profile).use { file ->
            val error = assertThrows(RemoteAccessException::class.java) { file.read(0, ByteArray(500), 0, 500) }
            assertTrue(error.message!!.contains("文件句柄失效"))
        } }
    }
    @Test fun symbolicLinkIsRejectedForPlayback() {
        Peer().use { peer -> assertThrows(IllegalArgumentException::class.java) {
            NfsTransport().open(RemoteAddress.parse(peer.root.child("link.mp4")), peer.profile)
        } }
    }
}
