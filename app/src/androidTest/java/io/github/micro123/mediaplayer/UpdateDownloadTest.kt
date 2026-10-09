package io.github.micro123.mediaplayer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.micro123.mediaplayer.data.update.*
import io.github.micro123.mediaplayer.ui.UpdateState
import io.github.micro123.mediaplayer.ui.components.UpdateSection
import io.github.micro123.mediaplayer.ui.theme.LocalPlayerTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateDownloadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun githubApkDownloadsWithoutBrowserRestoresTaskAndChecksSignature() = runBlocking {
        val release = GithubReleaseClient().latest()
        val apk = requireNotNull(release.preferredApk(Build.SUPPORTED_ABIS.toList()))
        val name = "update-download-fixture-${System.nanoTime()}"
        val installPermission = context.packageManager.canRequestPackageInstalls()
        var downloads = UpdateDownloads(context, name)
        try {
            downloads.start(apk, release.tag)
            await(15000) { downloads.state.value != UpdateDownloadState.Empty }
            assertTrue(downloads.state.value.toString(), downloads.state.value is UpdateDownloadState.Transferring || downloads.state.value is UpdateDownloadState.Ready || downloads.state.value is UpdateDownloadState.Verifying)
            downloads.close() // The transfer belongs to the system, not the activity/repository.
            downloads = UpdateDownloads(context, name)
            await(180000) { downloads.state.value is UpdateDownloadState.Ready || downloads.state.value is UpdateDownloadState.Failed }
            val result = downloads.state.value
            assertTrue("GitHub download result: $result", result is UpdateDownloadState.Ready)
            val ready = result as UpdateDownloadState.Ready
            assertEquals(apk.name, ready.task.apk.name)
            assertEquals("content", ready.uri.scheme)
            context.contentResolver.openInputStream(ready.uri)!!.use { assertTrue(it.read() >= 0) }
            if (BuildConfig.DEBUG) {
                assertFalse("Official release cannot overwrite a debug signature", ready.canInstall)
                assertTrue(ready.message.contains("签名不同"))
                try { downloads.installationIntent(); fail("Incompatible package was installable") }
                catch (error: IllegalStateException) { assertTrue(error.message!!.contains("签名不同")) }
            }
            val intent = UpdateDownloads.installIntent(ready.uri)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(UpdateDownloads.APK_MIME, intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(ready.uri, intent.clipData!!.getItemAt(0).uri)
            assertNotNull("System APK installer is available without a browser", context.packageManager.resolveActivity(intent, 0))
            assertEquals("Do not change device install permissions during tests", installPermission, context.packageManager.canRequestPackageInstalls())
        } finally {
            downloads.cancel()
            await(10000) { downloads.state.value == UpdateDownloadState.Empty }
            downloads.close()
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun downloadUiShowsProgressRetryAndInstallationWithoutOpeningUrls() {
        val apk = ReleaseApk("media-player-0.15.0-arm64-v8a.apk", "${GithubReleaseClient.REPOSITORY_URL}/releases/download/v0.15.0/media-player-0.15.0-arm64-v8a.apk", 10000)
        val release = GithubRelease("v0.15.0", "测试", "更新说明", "", "${GithubReleaseClient.REPOSITORY_URL}/releases/tag/v0.15.0", listOf(apk))
        val task = UpdateDownloadTask(123, apk, release.tag, "update-fixture.apk")
        val state = mutableStateOf<UpdateDownloadState>(UpdateDownloadState.Empty)
        var browserOpens = 0; var retries = 0
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity -> activity.setContent { LocalPlayerTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                UpdateSection(UpdateState.Checked(release), {}, { browserOpens++ }, currentVersion = "0.14.0", supportedAbis = listOf("arm64-v8a"),
                    debugBuild = false, downloadState = state.value,
                    onDownload = { chosen, tag -> assertEquals(apk, chosen); assertEquals(release.tag, tag); state.value = UpdateDownloadState.Transferring(task, 5000, 10000, "正在下载…") },
                    onCancelDownload = { state.value = UpdateDownloadState.Empty }, onRetryDownload = { retries++ },
                    installationIntent = { UpdateDownloads.installIntent(Uri.parse("content://downloads/fixture")) })
            } } } }
            click("检查更新"); click("下载 APK")
            await(10000) { contains("取消下载") && contains("正在下载…") }
            assertEquals(0, browserOpens)
            click("关闭")
            await(10000) { contains("取消下载") }
            instrumentation.runOnMainSync { state.value = UpdateDownloadState.Failed(task, "测试网络中断") }
            click("重试下载"); assertEquals(1, retries)
            instrumentation.runOnMainSync { state.value = UpdateDownloadState.Ready(task, Uri.parse("content://downloads/fixture"), true, "安装包已检查") }
            await(10000) { contains("安装更新") }
            assertEquals(0, browserOpens)
            click("删除下载")
            await(10000) { state.value == UpdateDownloadState.Empty }
        } finally { scenario.close() }
    }

    @Test fun pageFallbackFindsActualApkLinksAndDigestsInsteadOfGuessingNames() {
        val tag = "v0.15.0"
        val href = "/micro123/media-player/releases/download/$tag/media-player-0.15.0-arm64-v8a.apk"
        val digest = "a".repeat(64)
        val html = "<li><a href=\"$href\">APK</a><span>sha256:$digest</span></li>" +
            "<li><a href=\"https://evil.invalid/media-player-x86.apk\">bad</a></li><li><a href=\"/elsewhere/file.apk\">bad</a></li>"
        val apks = GithubReleaseClient.pageApks(html, tag)
        assertEquals(1, apks.size)
        assertEquals(digest, apks.single().sha256)
        assertTrue(apks.single().url.startsWith(GithubReleaseClient.REPOSITORY_URL))
        assertTrue(GithubReleaseClient.pageApks(html, "v0.16.0").isEmpty())
    }

    private fun walk(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) walk(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun contains(text: String): Boolean {
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        return walk(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text } != null
    }
    private fun click(text: String) {
        repeat(12) {
            if (contains(text)) return@repeat
            walk(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(150)
        }
        await(10000) { contains(text) }
        var node = walk(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text }
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull(text, node); assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun await(timeout: Long, predicate: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(100) }
        assertTrue("Timed out", predicate())
    }
}
