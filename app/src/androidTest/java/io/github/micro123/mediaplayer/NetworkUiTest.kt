package io.github.micro123.mediaplayer

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.micro123.mediaplayer.core.MediaItem
import io.github.micro123.mediaplayer.data.*
import io.github.micro123.mediaplayer.data.network.NetworkCredentials
import io.github.micro123.mediaplayer.ui.RemoteBrowserState
import io.github.micro123.mediaplayer.ui.LibrarySource
import io.github.micro123.mediaplayer.ui.LibraryState
import io.github.micro123.mediaplayer.ui.screens.*
import io.github.micro123.mediaplayer.ui.theme.LocalPlayerTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Screens contain only generated fixture entries, never the user's bookmarks or media. */
@RunWith(AndroidJUnit4::class)
class NetworkUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun localFilesHideSearchAndSortDisplayAndPlaybackTogether() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val first = MediaItem("file:///test/episode2.mp4", "episode2.mp4", "video/mp4", 200)
        val second = MediaItem("file:///test/episode10.mp4", "episode10.mp4", "video/mp4", 50)
        val state = LibraryState(source = LibrarySource.FOLDER, isLoading = false,
            entries = listOf(FolderEntry(first.uri, first.displayName, "first", first), FolderEntry(second.uri, second.displayName, "second", second),
                FolderEntry("file:///test/Folder", "Folder", "folder")), folderPath = listOf(FolderLocation("test", "测试文件夹")))
        val sort = mutableStateOf(BrowseSort.NAME)
        val reverse = mutableStateOf(false)
        var selected = emptyList<MediaItem>()
        try {
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent { LocalPlayerTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    LibraryScreen(state, {}, { items, _ -> selected = items }, {}, {}, {}, {}, {}, {}, {}, {}, false, {},
                        fileSort = sort.value, fileSortDescending = reverse.value, onFileSort = { value, descending -> sort.value = value; reverse.value = descending })
                } } }
            }
            await("file list has search icon") { contains("搜索") && contains("文件排序") }
            assertNull(walk(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable })
            click("文件排序"); click("按大小排序")
            await("size order selected") { sort.value == BrowseSort.SIZE }
            click("播放全部")
            await("playback follows ascending displayed order") { selected.map { it.uri } == listOf(second.uri, first.uri) }
            click("文件排序"); click("降序")
            await("descending selected") { reverse.value }
            click("播放全部")
            await("playback follows descending displayed order") { selected.map { it.uri } == listOf(first.uri, second.uri) }
            click("搜索")
            await("search expands") { walk(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable } != null }
            val editor = requireNotNull(walk(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable })
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Folder")
            }))
            await("search includes directories and filters media") { contains("Folder") && !contains("episode2.mp4") && !contains("episode10.mp4") }
            click("关闭搜索")
            await("closing clears filter and editor") { walk(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable } == null }
            click("播放全部")
            await("unfiltered files restored") { selected.size == 2 }
            screenshot("file-sort-search-0.11.png")
        } finally { scenario.close() }
    }

    @Test fun networkFilesHideSearchAndOfferSharedSortingControls() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val sort = mutableStateOf(BrowseSort.NAME)
        val reverse = mutableStateOf(false)
        val root = "smb://fixture.example.invalid/Movies"
        val state = RemoteBrowserState(root, root, listOf(SourceEntry("$root/Season", "Season", true),
            SourceEntry("$root/episode2.mp4", "episode2.mp4", false, MediaItem("$root/episode2.mp4", "episode2.mp4", "video/mp4", 42))), loading = false)
        try {
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent { LocalPlayerTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    NetworkBrowserScreen(state, {}, {}, {}, fileSort = sort.value, fileSortDescending = reverse.value,
                        onFileSort = { value, descending -> sort.value = value; reverse.value = descending })
                } } }
            }
            await("remote browse tools") { contains("搜索") && contains("文件排序") }
            assertNull(walk(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable })
            click("文件排序"); click("按类型排序")
            await("network type sort selected") { sort.value == BrowseSort.TYPE }
            click("文件排序"); click("降序")
            await("network reverse selected") { reverse.value }
            click("搜索")
            await("remote search expanded") { walk(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable } != null }
            click("关闭搜索")
            await("remote search collapsed") { walk(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable } == null }
        } finally { scenario.close() }
    }
    @Test fun videoDirectionChoicesExposeLandscapePortraitAndKeep() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val selection = mutableStateOf(PlayerPreferences().orientation)
        try {
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent { LocalPlayerTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(24.dp)) {
                        androidx.compose.material3.Text("全屏播放方向")
                        io.github.micro123.mediaplayer.ui.components.OrientationChoices(selection.value, { selection.value = it })
                        androidx.compose.material3.Text("保持：不切换方向，保持当前横屏或竖屏。")
                    }
                } } }
            }
            await("three direction options") { contains("横屏（默认）") && contains("竖屏") && contains("保持") }
            assertEquals(VideoOrientation.LANDSCAPE, selection.value)
            assertFalse(contains("自动"))
            screenshot("video-direction-options-0.8.1.png")
            click("竖屏"); await("portrait selected") { selection.value == VideoOrientation.PORTRAIT }
            click("保持"); await("keep selected") { selection.value == VideoOrientation.KEEP }
            click("横屏（默认）"); await("landscape selected") { selection.value == VideoOrientation.LANDSCAPE }
        } finally { scenario.close() }
    }
    @Test fun filesDestinationShowsLocationsAndOffersBookmarkActions() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val page = mutableStateOf(0)
        val actions = mutableListOf<String>()
        val location = SavedBookmark(id = "files-server", name = "测试 NAS", kind = BookmarkKind.LOCATION, address = "smb://nas.example.invalid/Movies")
        val folder = SavedBookmark(id = "files-folder", name = "测试剧集", kind = BookmarkKind.FOLDER, address = "smb://nas.example.invalid/Movies/Season")
        val position = SavedBookmark(id = "files-position", name = "不应出现在文件页的时间点", kind = BookmarkKind.POSITION)
        try {
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent { LocalPlayerTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    if (page.value == 0) FilesScreen(listOf(location, folder, position), { actions += "storage" }, { actions += "folder-picker" },
                        { actions += "network" }, { actions += "open-${it.id}" }, { actions += "edit-${it.id}" }, { actions += "remove-$it" })
                    else NetworkBrowserScreen(RemoteBrowserState(folder.address, folder.address, loading = false,
                        error = "系统拒绝创建网络连接", networkRestricted = true), {}, {}, {}, onNetworkSettings = { actions += "settings" })
                } } }
            }
            await("files locations") { contains("目录与位置书签") && contains("测试剧集") }
            assertFalse(contains(position.name))
            screenshot("files-locations-0.8.png")
            click("内部共享存储"); click("选择本地文件夹"); click("添加网络位置")
            click("测试 NAS"); click("重命名")
            await("files actions") { actions.containsAll(listOf("storage", "folder-picker", "network", "open-files-server", "edit-files-folder")) }
            instrumentation.runOnMainSync { page.value = 1 }
            await("settings advice") { contains("打开应用网络设置") }
            screenshot("network-restricted-0.8.png")
            click("打开应用网络设置"); await("settings action") { "settings" in actions }
        } finally { scenario.close() }
    }
    @Test fun newGuestLocationCanBeEnteredAndSaved() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var saved: String? = null
        try {
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent { LocalPlayerTheme {
                    NetworkLocationDialog(null, null, { name, address, id, auth, open ->
                        assertEquals("测试 NAS", name); assertNull(id); assertTrue(auth.guest); assertFalse(open); saved = address
                    }, {})
                } }
            }
            await("new address form") { walk(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable } != null }
            fun input(index: Int, value: String) {
                val editors = mutableListOf<AccessibilityNodeInfo>()
                walk(instrumentation.uiAutomation.rootInActiveWindow) { if (it.isEditable) editors += it; false }
                assertTrue(editors[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
                instrumentation.waitForIdleSync()
            }
            input(0, "smb://test-nas/share"); input(1, "测试 NAS")
            await("save validated") {
                val node = walk(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == "保存书签" }
                node?.isEnabled == true
            }
            click("保存书签")
            await("guest submitted") { saved == "smb://test-nas/share" }
        } finally { scenario.close() }
    }
    @Test fun navidromeFormAndMusicBrowserExposeAccountSearchAndOrderedPlayback() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val page = mutableStateOf(0)
        var saved: SavedBookmark? = null
        var search = ""
        val plays = AtomicInteger()
        val root = io.github.micro123.mediaplayer.data.navidrome.NavidromeAddress.fromServer("http://music.example.invalid:4533")
        val state = RemoteBrowserState(root.address, root.at("album", "fixture-album"), listOf(
            SourceEntry(root.at("song", "b"), "曲目 B", false, MediaItem(root.at("song", "b"), "曲目 B", "audio/mpeg", null), "测试歌手 · 测试专辑"),
            SourceEntry(root.at("song", "a"), "曲目 A", false, MediaItem(root.at("song", "a"), "曲目 A", "audio/mpeg", null))), loading = false)
        try {
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent { LocalPlayerTheme {
                    if (page.value == 0) NetworkLocationDialog(null, null, { name, address, id, auth, open ->
                        assertEquals("测试音乐库", name); assertNotNull(id); assertFalse(auth.guest); assertTrue(open)
                        assertTrue(auth.username == "fixture-user" && auth.password == "fixture-password")
                        assertFalse(address.contains(auth.password))
                        saved = SavedBookmark(id!!, name, BookmarkKind.LOCATION, address)
                        page.value = 1
                    }, {}) else Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                        NavidromeBrowserScreen(state, {}, {}, {}, { search = it }, { plays.incrementAndGet() }, {}, {})
                    }
                } }
            }
            await("Navidrome source choice") { contains("Navidrome") }
            click("Navidrome")
            await("account fields") { contains("用户名") && contains("密码") }
            fun input(index: Int, value: String) {
                val editors = mutableListOf<AccessibilityNodeInfo>()
                walk(instrumentation.uiAutomation.rootInActiveWindow) { if (it.isEditable) editors += it; false }
                assertTrue(editors[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
                instrumentation.waitForIdleSync()
            }
            input(0, "http://music.example.invalid:4533"); input(1, "测试音乐库")
            input(2, "fixture-user"); input(3, "fixture-password")
            click("保存并连接")
            await("music source submitted") { saved != null && contains("播放全部（2 首）") }
            assertNull(walk(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable })
            assertFalse(contains("文件排序"))
            click("播放全部（2 首）"); await("server ordered playback action") { plays.get() == 1 }
            click("搜索")
            await("music search expands") { walk(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable } != null }
            input(0, "服务器曲目")
            // The submit text and search icon share a label; the open icon is now labelled 关闭搜索.
            click("搜索"); await("server query submitted") { search == "服务器曲目" }
            click("关闭搜索")
            await("music search hidden") { walk(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable } == null }
        } finally { scenario.close() }
    }

    @Test fun connectionFormsDirectoryAndRetryControls() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val page = mutableStateOf(0); val opened = AtomicInteger(); val refreshed = AtomicInteger(); val up = AtomicInteger()
        val bookmark = SavedBookmark(id = "ui-fixture", name = "测试 NAS", kind = BookmarkKind.LOCATION, address = "smb://nas.example.invalid/Movies")
        val state = RemoteBrowserState(bookmark.address, bookmark.address, listOf(SourceEntry("${bookmark.address}/Season", "Season", true),
            SourceEntry("${bookmark.address}/episode-02.mp4", "episode-02.mp4", false, MediaItem("${bookmark.address}/episode-02.mp4", "episode-02.mp4", "video/mp4", 42)),
            SourceEntry("${bookmark.address}/list.m3u", "list.m3u", false)), loading = false)
        try {
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent { LocalPlayerTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    when (page.value) {
                        0 -> NetworkLocationDialog(bookmark, NetworkCredentials(false, "player-test", "fixture-password", "TEST"), { _, _, _, _, _ -> page.value = 2 }, { page.value = 2 })
                        1 -> NetworkLocationDialog(bookmark.copy(address = "nfs://nas.example.invalid/volume1/video"), NetworkCredentials(uid = 1000, gid = 1000), { _, _, _, _, _ -> page.value = 2 }, { page.value = 2 })
                        2 -> NetworkBrowserScreen(state, { opened.incrementAndGet() }, { up.incrementAndGet() }, { refreshed.incrementAndGet() }, onBookmark = { opened.addAndGet(10) })
                        else -> NetworkBrowserScreen(state.copy(entries = emptyList(), error = "无法连接 SMB，请检查服务器、共享名、账号和访问权限"), {}, { up.incrementAndGet() }, { refreshed.incrementAndGet() })
                    }
                } } }
            }
            await("SMB form") { contains("访客访问") && contains("保存并连接") }
            assertTrue(contains("用户名")); assertTrue(contains("域（选填）"))
            // The password must not appear as plaintext in accessibility.
            assertFalse(contains("fixture-password"))
            screenshot("network-smb-0.8.png")
            instrumentation.runOnMainSync { page.value = 1 }
            await("NFS form") { contains("UID") && contains("GID") }
            screenshot("network-nfs-0.8.png")
            click("保存并连接")
            await("directory") { contains("episode-02.mp4") && contains("搜索") }
            assertNull(walk(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable })
            screenshot("network-browser-0.8.png")
            click("episode-02.mp4"); await("file action") { opened.get() == 1 }
            click("收藏当前目录"); await("folder bookmark action") { opened.get() == 11 }
            click("刷新"); await("refresh action") { refreshed.get() == 1 }
            click("返回文件"); await("up action") { up.get() == 1 }
            instrumentation.runOnMainSync { page.value = 3 }
            await("error visible") { contains("无法连接 SMB，请检查服务器、共享名、账号和访问权限") }
            screenshot("network-error-0.8.png")
            click("刷新"); await("error retry") { refreshed.get() == 2 }
        } finally { scenario.close() }
    }
    private fun walk(node: AccessibilityNodeInfo?, action: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (action(node)) return node
        for (index in 0 until node.childCount) walk(node.getChild(index), action)?.let { return it }
        return null
    }
    private fun contains(text: String) = walk(instrumentation.uiAutomation.rootInActiveWindow) {
        it.text?.toString() == text || it.contentDescription?.toString() == text
    } != null
    private fun click(text: String) {
        await("clickable $text") {
            var node = walk(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text || it.contentDescription?.toString() == text }
            while (node != null && !node.isClickable) node = node.parent
            node?.isClickable == true && node.isEnabled
        }
        var node = walk(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text || it.contentDescription?.toString() == text }
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("click target $text", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun screenshot(name: String) {
        Thread.sleep(600); instrumentation.waitForIdleSync()
        assertEquals(context.packageName, instrumentation.uiAutomation.rootInActiveWindow.packageName.toString())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 10000
        while (android.os.SystemClock.elapsedRealtime() < end) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out: $label")
    }
}
