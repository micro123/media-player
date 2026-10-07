package com.tang.player.data

import com.tang.player.core.MediaItem
import com.tang.player.core.MediaSourceKind
import com.tang.player.core.mediaSourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class M3uCodecTest {
    @Test fun bomCommentsCrLfAndTitlesAreParsed() {
        val result = M3uCodec.parse("\uFEFF#EXTM3U\r\n# comment\r\n#EXTINF:12,第一集,起点\r\nshow 01.mp4\r\n\r\n#EXTINF:-1,Second\r\nhttps://example.test/a.mp4?q=1\r\n")
        assertFalse(result.hls)
        assertEquals(listOf(M3uEntry("show 01.mp4", "第一集,起点"), M3uEntry("https://example.test/a.mp4?q=1", "Second")), result.entries)
    }
    @Test fun hlsIsRecognizedRatherThanAListOfEpisodes() {
        assertTrue(M3uCodec.parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts").hls)
        assertTrue(M3uCodec.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=50000\nvariant.m3u8").hls)
    }
    @Test fun relativeHttpPathsPreserveQueriesAndEncoding() {
        assertEquals("https://example.test/show/ep%2002.mp4?token=a%2Fb", M3uCodec.resolve("../ep 02.mp4?token=a%2Fb", "https://example.test/show/lists/all.m3u"))
        assertEquals("https://cdn.test/ep.mp4", M3uCodec.resolve("//cdn.test/ep.mp4", "https://example.test/all.m3u"))
    }
    @Test fun localRelativePathsAndBackslashesResolve() {
        assertEquals("file:/storage/Movies/show/02.mp4", M3uCodec.resolve("show\\02.mp4", "file:///storage/Movies/list.m3u"))
    }
    @Test fun localTitlesMayContainReleaseBracketsAndLiteralHash() {
        assertEquals("file:/storage/Movies/%5BGroup%5D%20ep%2301.mp4", M3uCodec.resolve("[Group] ep#01.mp4", "file:///storage/Movies/list.m3u"))
    }
    @Test fun documentUrisAreNeverGuessedAsRelativeFilesystemPaths() {
        assertThrows(IllegalArgumentException::class.java) { M3uCodec.resolve("02.mp4", "content://documents/document/opaque%3Aid") }
    }
    @Test fun exportedTitlesCannotInjectPlaylistLines() {
        val encoded = M3uCodec.write(listOf(M3uEntry("content://media/1", "Episode\n#EXTINF:0,injected\rtitle")))
        assertEquals(1, M3uCodec.parse(encoded).entries.size)
        assertThrows(IllegalArgumentException::class.java) { M3uCodec.write(listOf(M3uEntry("a\nb"))) }
    }
    @Test fun exportRoundTripsAbsoluteUrisAndUnicodeTitles() {
        val input = listOf(M3uEntry("content://media/external/video/media/1", "第一集"), M3uEntry("https://example.test/v.mp4?x=1&y=2", "第二集"))
        assertEquals(input, M3uCodec.parse(M3uCodec.write(input)).entries)
    }
    @Test fun excessivePlaylistsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { M3uCodec.parse("a.mp4\n".repeat(M3uCodec.MAX_ENTRIES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { M3uCodec.parse("a".repeat(M3uCodec.MAX_BYTES + 1)) }
    }
    @Test fun networkLocationsValidateHostsPortsAndCredentialSeparation() {
        assertEquals("smb://nas/share", validateNetworkAddress(" smb://nas/share ", true))
        assertEquals("nfs://192.168.1.2/media", validateNetworkAddress("nfs://192.168.1.2/media", true))
        assertThrows(IllegalArgumentException::class.java) { validateNetworkAddress("https:///video.mp4") }
        assertThrows(IllegalArgumentException::class.java) { validateNetworkAddress("http://nas:70000/v.mp4") }
        assertThrows(IllegalArgumentException::class.java) { validateNetworkAddress("http://user:secret@nas/v.mp4") }
        assertThrows(IllegalArgumentException::class.java) { validateNetworkAddress("smb://nas/share") }
    }
    @Test fun commandProtocolsCannotBeOpenedAsNetworkMedia() {
        for (url in listOf("fd://3", "lavf://http://nas/", "file:///data/a", "exec://cmd", "javascript:alert(1)")) {
            assertThrows(IllegalArgumentException::class.java) { validateNetworkAddress(url, true) }
        }
        assertEquals(MediaSourceKind.HTTP, mediaSourceKind("HTTPS://nas/a.mp4"))
        assertEquals(MediaSourceKind.LOCAL, mediaSourceKind("content://media/1"))
    }
    @Test fun providersCanBeAddedWithoutChangingQueueOrEngineModels() = runBlocking {
        val item = MediaItem("smb://nas/show/01.mkv", "01.mkv", "video/x-matroska", null)
        val provider = object : MediaSourceProvider {
            override val kind = MediaSourceKind.SMB
            override val capabilities = SourceCapabilities(true, true)
            override suspend fun resolve(address: String) = item
            override suspend fun list(address: String) = listOf(SourceEntry(item.uri, item.displayName, false, item))
        }
        val sources = MediaSourceRegistry(listOf(provider))
        assertEquals(item, sources.resolve(item.uri))
        assertEquals(item, sources.list("smb://nas/show").single().media)
    }
}
