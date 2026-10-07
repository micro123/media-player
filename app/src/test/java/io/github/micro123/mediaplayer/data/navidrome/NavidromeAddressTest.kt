package io.github.micro123.mediaplayer.data.navidrome

import io.github.micro123.mediaplayer.core.MediaSourceKind
import io.github.micro123.mediaplayer.core.mediaSourceKind
import io.github.micro123.mediaplayer.data.M3uCodec
import io.github.micro123.mediaplayer.data.M3uEntry
import org.junit.Assert.*
import org.junit.Test

class NavidromeAddressTest {
    private val id = "d42d062f-743d-4b4f-b149-c8eaa33e23bc"
    @Test fun stableIdentityRoundTripsIdsUnicodeAndProxyPaths() {
        val root = NavidromeAddress.fromServer("HTTPS://MUSIC.example.org:443/music%20library/", id)
        assertEquals("https://music.example.org/music%20library", root.server)
        val song = NavidromeAddress.parse(root.at("song", "歌曲/a+b?x#20%"))
        assertEquals(listOf("song", "歌曲/a+b?x#20%"), song.route)
        assertEquals(root.server, song.server)
        assertEquals(MediaSourceKind.NAVIDROME, mediaSourceKind(song.address))
        assertTrue(song.matches(root))
        assertFalse(song.matches(NavidromeAddress.fromServer("http://other.invalid", id)))
        assertFalse(song.matches(NavidromeAddress.fromServer(root.server)))
    }
    @Test fun rejectsCredentialsTraversalAndAmbiguousProfiles() {
        for (value in listOf("file:///music", "http://u:p@music.invalid", "http://music.invalid?token=secret",
            "http://music.invalid#player", "http://music.invalid:99999", "http://music.invalid/../rest", "http://music.invalid/%2e%2e/rest")) {
            assertTrue(value, runCatching { NavidromeAddress.fromServer(value, id) }.isFailure)
        }
        val base = NavidromeAddress.fromServer("http://music.invalid", id).address
        for (value in listOf(base + "&password=secret", base.replace(id, "invalid"), base + "#song/", base + "#song/%00"))
            assertTrue(runCatching { NavidromeAddress.parse(value) }.isFailure)
    }
    @Test fun m3uStoresOnlyStableSongIdentityAndTokenMatchesSubsonicSpec() {
        val address = NavidromeAddress.fromServer("http://music.invalid:4533", id).at("song", "fixture-id")
        val text = M3uCodec.write(listOf(M3uEntry(address, "测试曲目")))
        assertEquals(address, M3uCodec.parse(text).entries.single().reference)
        assertFalse(text.contains("/rest/")); assertFalse(text.contains("&t=")); assertFalse(text.contains("password"))
        assertEquals("26719a1196d2a940705a59634eb18eab", NavidromeClient.token("sesame", "c19b2d"))
    }
}
