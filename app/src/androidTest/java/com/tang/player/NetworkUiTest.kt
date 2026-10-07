package com.tang.player

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
import com.tang.player.core.MediaItem
import com.tang.player.data.*
import com.tang.player.data.network.NetworkCredentials
import com.tang.player.ui.RemoteBrowserState
import com.tang.player.ui.screens.*
import com.tang.player.ui.theme.LocalPlayerTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Screens contain only generated fixture entries, never the user's bookmarks or media. */
@RunWith(AndroidJUnit4::class)
class NetworkUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun videoDirectionChoicesExposeLandscapePortraitAndKeep() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val selection = mutableStateOf(PlayerPreferences().orientation)
        try {
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent { LocalPlayerTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(24.dp)) {
                        androidx.compose.material3.Text("全屏播放方向")
                        com.tang.player.ui.components.OrientationChoices(selection.value, { selection.value = it })
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
            await("directory") { contains("episode-02.mp4") && contains("搜索当前目录") }
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
            var node = walk(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text }
            while (node != null && !node.isClickable) node = node.parent
            node?.isClickable == true && node.isEnabled
        }
        var node = walk(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text }
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
