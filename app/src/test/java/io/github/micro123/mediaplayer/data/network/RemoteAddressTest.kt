package io.github.micro123.mediaplayer.data.network

import org.junit.Assert.*
import org.junit.Test

class RemoteAddressTest {
    @Test fun normalizedUnicodeAddressesRoundTrip() {
        val root = RemoteAddress.parse(" SMB://NAS/Movies/ ")
        val child = RemoteAddress.parse(root.child("中文 #02.mp4"))
        assertEquals("smb://nas/Movies", root.address)
        assertEquals(listOf("Movies", "中文 #02.mp4"), child.parts)
        assertTrue(child.address.contains("%23")); assertTrue(root.contains(child))
        assertEquals(root.address, child.parent())
    }
    @Test fun shareBoundaryAndPortsAreExact() {
        val root = RemoteAddress.parse("smb://nas/Movies")
        assertTrue(root.contains(RemoteAddress.parse("smb://NAS:445/Movies/file.mp4")))
        for (address in listOf("smb://nas/Movies2/a.mp4", "smb://nas:1445/Movies/a.mp4", "smb://other/Movies/a.mp4", "nfs://nas/Movies/a.mp4"))
            assertFalse(root.contains(RemoteAddress.parse(address)))
    }
    @Test fun rejectsCredentialsTraversalAndEncodedSeparators() {
        for (value in listOf("smb://user:secret@nas/Movies", "smb://nas/Movies/../private", "smb://nas/Movies/%2e%2e",
            "smb://nas/Movies%2fprivate", "smb://nas/Movies%5cprivate", "smb://nas/Movies/%00", "smb://nas", "smb://nas:0/Movies",
            "smb://nas/Movies?a=1", "smb://nas/Movies#fragment"))
            assertThrows(value, IllegalArgumentException::class.java) { RemoteAddress.parse(value) }
    }
    @Test fun childrenCannotEscapeDirectory() {
        val root = RemoteAddress.parse("nfs://nas/volume1/video")
        for (name in listOf("..", ".", "../secret", "a/b", "a\\b", "a\u0000", ""))
            assertThrows(IllegalArgumentException::class.java) { root.child(name) }
        assertEquals("nfs://nas/volume1/video/episode%201.mp4", root.child("episode 1.mp4"))
    }
    @Test fun ipv6AndUnsignedCredentials() {
        val address = RemoteAddress.parse("nfs://[::1]:11111/export")
        assertEquals("::1", address.host); assertEquals(11111, address.effectivePort())
        assertEquals("nfs://[::1]:11111/export/a.mp4", address.child("a.mp4"))
        val credentials = NetworkCredentials(false, "user", "do-not-log", uid = 0xffffffffL)
        assertFalse(credentials.toString().contains("do-not-log"))
        assertThrows(IllegalArgumentException::class.java) { NetworkCredentials(uid = -1) }
        assertThrows(IllegalArgumentException::class.java) { NetworkCredentials(gid = 0x100000000L) }
    }
    @Test fun remoteM3uResolvesParentAndEscapedNames() {
        assertEquals("smb://nas/Movies/Season/episode%201.mp4", io.github.micro123.mediaplayer.data.M3uCodec.resolve("Season/episode 1.mp4", "smb://nas/Movies/list.m3u"))
        assertEquals("nfs://nas/export/episode%23%5B2%5D.mp4", io.github.micro123.mediaplayer.data.M3uCodec.resolve("../episode#[2].mp4", "nfs://nas/export/Season/list.m3u"))
        assertEquals("smb://nas/Movies/episode%20one.mp4", io.github.micro123.mediaplayer.data.M3uCodec.resolve("episode%20one.mp4", "smb://nas/Movies/list.m3u"))
    }
}
