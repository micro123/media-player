package com.tang.player.data.network

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in isolated Samba + UNFS3 server, never a user's NAS. See tools/network-test-server. */
class RemoteServerIntegrationTest {
    private fun host(): String { val host = System.getenv("PLAYER_NETWORK_TEST_HOST"); assumeTrue("temporary network test server not configured", host != null); return host!! }
    private fun smb(share: String = "Movies") = RemoteAddress.parse("smb://${host()}:1445/$share")
    private fun nfs() = RemoteAddress.parse("nfs://${host()}:11111/fixtures")
    private fun profile(root: RemoteAddress, credentials: NetworkCredentials = NetworkCredentials()) = NetworkProfile("test", root.address, credentials)
    private fun randomReads(transport: RemoteTransport, root: RemoteAddress, credentials: NetworkCredentials = NetworkCredentials()) {
        val profile = profile(root, credentials)
        transport.open(RemoteAddress.parse(root.child("read-pattern.bin")), profile).use { file ->
            assertEquals(2L * 1024 * 1024, file.size)
            for (offset in listOf(0L, 1048707L, 257L, file.size - 19)) {
                val bytes = ByteArray(1057) { -1 }
                val count = file.read(offset, bytes, 13, 1024)
                assertEquals(minOf(1024L, file.size - offset).toInt(), count)
                for (index in 0 until count) assertEquals(((offset + index) % 256).toByte(), bytes[index + 13])
                assertEquals((-1).toByte(), bytes[12])
            }
            assertEquals(0, file.read(file.size, ByteArray(8), 0, 8))
            assertThrows(IllegalArgumentException::class.java) { file.read(-1, ByteArray(8), 0, 8) }
        }
        transport.open(RemoteAddress.parse(root.child("large-offset.bin")), profile).use { file ->
            val bytes = ByteArray(15); val offset = 5L * 1024 * 1024 * 1024
            assertEquals(offset + 15, file.size); assertEquals(15, file.read(offset, bytes, 0, bytes.size))
            assertEquals("large-offset-ok", String(bytes))
        }
    }
    @Test fun sambaGuestListsNestedUnicodeMediaAndRandomReads() {
        val root = smb(); val transport = SmbTransport()
        val entries = transport.list(root, profile(root))
        assertTrue(entries.any { it.name == "Season" && it.directory }); assertTrue(entries.any { it.name == "中文 #02.mp4" && !it.directory })
        val nested = RemoteAddress.parse(root.child("Season"))
        assertEquals("episode-02.mp4", transport.list(nested, profile(root)).single().name)
        assertTrue(transport.stat(nested, profile(root)).directory)
        randomReads(transport, root)
    }
    @Test fun sambaAuthenticatedShareWorksAndWrongPasswordFails() {
        val root = smb("Private"); val transport = SmbTransport()
        val auth = NetworkCredentials(false, "player-test", "player-test-password")
        assertTrue(transport.list(root, profile(root, auth)).isNotEmpty()); randomReads(transport, root, auth)
        assertThrows(RemoteAccessException::class.java) { transport.list(root, profile(root, auth.copy(password = "wrong"))) }
        assertThrows(RemoteAccessException::class.java) { transport.list(root, profile(root)) }
    }
    @Test fun nfsMountLookupReaddirFallbackAnd64BitReads() {
        val root = nfs(); val transport = NfsTransport()
        assertTrue(transport.list(root, profile(root)).any { it.name == "中文 #02.mp4" })
        val nested = RemoteAddress.parse(root.child("Season"))
        assertEquals("episode-02.mp4", transport.list(nested, profile(root)).single().name)
        assertTrue(transport.stat(nested, profile(root)).directory)
        randomReads(transport, root)
    }
    @Test fun nfsRejectsNonExportRootAndMissingMedia() {
        val root = nfs(); val transport = NfsTransport()
        val invalid = RemoteAddress.parse(root.child("does-not-exist"))
        assertThrows(RemoteAccessException::class.java) { transport.stat(invalid, profile(root)) }
        assertThrows(RemoteAccessException::class.java) { transport.list(invalid, profile(invalid)) }
    }
}
