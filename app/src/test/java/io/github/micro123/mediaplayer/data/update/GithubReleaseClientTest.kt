package io.github.micro123.mediaplayer.data.update

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class GithubReleaseClientTest {
    private class Reply(private val code: Int = 200, private val body: ByteArray = "{\"name\":\"媒体播放器\"}".toByteArray(),
        private val headers: Map<String, String> = emptyMap(), private val timeout: Boolean = false) : HttpURLConnection(URL(GithubReleaseClient.API_URL)) {
        var closed = false
        override fun getResponseCode(): Int { if (timeout) throw SocketTimeoutException(); return code }
        override fun getHeaderField(name: String?) = headers[name]
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
    }
    @Test fun sendsOnlyPublicMetadataRequestAndReleasesTheConnection() {
        val reply = Reply()
        assertTrue(GithubReleaseClient { reply }.readResponse().contains("媒体播放器"))
        assertEquals("application/vnd.github+json", reply.getRequestProperty("Accept"))
        assertEquals("2026-03-10", reply.getRequestProperty("X-GitHub-Api-Version"))
        assertNull(reply.getRequestProperty("Authorization"))
        assertFalse(reply.instanceFollowRedirects)
        assertEquals(10000, reply.connectTimeout); assertEquals(10000, reply.readTimeout)
        assertTrue(reply.closed)
    }
    @Test fun rateLimitsMissingReleasesAndServerErrorsOfferUsefulFeedback() {
        for ((code, message) in listOf(403 to "次数已达上限", 429 to "次数已达上限", 404 to "尚未找到", 500 to "HTTP 500")) {
            val reply = Reply(code, headers = mapOf("X-RateLimit-Remaining" to "0"))
            val error = assertThrows(UpdateCheckException::class.java) { GithubReleaseClient { reply }.readResponse() }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(message))
            assertTrue(reply.closed)
        }
    }
    @Test fun timeoutsAreReadableAndOverlargeResponsesAreBounded() {
        val timeout = Reply(timeout = true)
        assertTrue(assertThrows(UpdateCheckException::class.java) { GithubReleaseClient { timeout }.readResponse() }.message!!.contains("超时"))
        assertTrue(timeout.closed)
        val oversized = Reply(body = ByteArray(GithubReleaseClient.MAX_BYTES + 1))
        assertTrue(assertThrows(UpdateCheckException::class.java) { GithubReleaseClient { oversized }.readResponse() }.message!!.contains("过大"))
        assertTrue(oversized.closed)
    }
}
