package io.github.micro123.mediaplayer.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class HttpPlaylistClientTest {
    private class Reply(url: String, private val code: Int = 200, val body: ByteArray = ByteArray(0), val location: String? = null) : HttpURLConnection(URL(url)) {
        var closed = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
    }
    @Test fun unicodeBodyUsesBoundedDownloadAndClosesConnection() {
        val response = Reply("https://nas.test/list.m3u", body = "#EXTM3U\n#EXTINF:-1,第一集\n01.mp4".toByteArray())
        val result = HttpPlaylistClient { response }.read(response.url.toString())
        assertEquals("第一集", M3uCodec.parse(result.text).entries.single().title)
        assertEquals(10_000, response.connectTimeout); assertEquals(10_000, response.readTimeout)
        assertFalse(response.instanceFollowRedirects); assertTrue(response.closed)
    }
    @Test fun redirectsUseFinalLocationForRelativeEntries() {
        val first = Reply("https://nas.test/start", 302, location = "/shows/list.m3u")
        val second = Reply("https://nas.test/shows/list.m3u", body = "01.mp4".toByteArray())
        val result = HttpPlaylistClient { if (it.endsWith("/start")) first else second }.read(first.url.toString())
        assertEquals("https://nas.test/shows/01.mp4", M3uCodec.resolve(M3uCodec.parse(result.text).entries.single().reference, result.address))
        assertTrue(first.closed); assertTrue(second.closed)
    }
    @Test fun redirectCannotSwitchToFileOrEmbedCredentials() {
        for (target in listOf("file:///data/private", "http://user:password@nas.test/list.m3u")) {
            val reply = Reply("https://nas.test/start", 302, location = target)
            assertThrows(IllegalArgumentException::class.java) { HttpPlaylistClient { reply }.read(reply.url.toString()) }
            assertTrue(reply.closed)
        }
    }
    @Test fun redirectLoopsHaveFiniteLimitAndReleaseEveryConnection() {
        val responses = mutableListOf<Reply>()
        assertThrows(IllegalStateException::class.java) { HttpPlaylistClient { address -> Reply(address, 302, location = "/again").also { responses += it } }.read("https://nas.test/start") }
        assertEquals(6, responses.size); assertTrue(responses.all { it.closed })
    }
    @Test fun missingRedirectAndHttpErrorsAreReported() {
        for (code in listOf(302, 403, 404, 500)) {
            val reply = Reply("https://nas.test/start", code)
            assertThrows(IllegalStateException::class.java) { HttpPlaylistClient { reply }.read(reply.url.toString()) }
            assertTrue(reply.closed)
        }
    }
    @Test fun excessiveResponseIsRejectedBeforeParsing() {
        val reply = Reply("https://nas.test/list.m3u", body = ByteArray(M3uCodec.MAX_BYTES + 1))
        assertThrows(IllegalStateException::class.java) { HttpPlaylistClient { reply }.read(reply.url.toString()) }
        assertTrue(reply.closed)
    }
}
