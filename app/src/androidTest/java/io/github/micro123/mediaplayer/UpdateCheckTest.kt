package io.github.micro123.mediaplayer

import android.content.pm.ActivityInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.micro123.mediaplayer.data.update.*
import io.github.micro123.mediaplayer.ui.UpdateState
import io.github.micro123.mediaplayer.ui.UpdateViewModel
import io.github.micro123.mediaplayer.ui.components.AboutSection
import io.github.micro123.mediaplayer.ui.components.UpdateSection
import io.github.micro123.mediaplayer.ui.theme.LocalPlayerTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class UpdateCheckTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val release get() = GithubReleaseClient.parseRelease(fixture())

    @Test fun releaseParsingUsesOnlyStableVersionsAndTrustedUploadedApks() {
        val parsed = release
        assertEquals("v0.15.0", parsed.tag)
        assertEquals("新增功能\n修复问题", parsed.notes)
        assertEquals(2, parsed.apks.size)
        assertTrue(parsed.preferredApk(listOf("arm64-v8a"))!!.name.contains("arm64-v8a"))
        assertTrue(parsed.preferredApk(listOf("x86"))!!.name.contains("universal"))
        for (field in listOf("draft", "prerelease")) {
            assertThrows(UpdateCheckException::class.java) { GithubReleaseClient.parseRelease(JSONObject(fixture()).put(field, true).toString()) }
        }
        assertThrows(UpdateCheckException::class.java) { GithubReleaseClient.parseRelease("invalid-json") }
        assertThrows(UpdateCheckException::class.java) { GithubReleaseClient.parseRelease(JSONObject(fixture()).put("tag_name", "nightly").toString()) }
        assertThrows(UpdateCheckException::class.java) { GithubReleaseClient.parseRelease(JSONObject(fixture()).put("html_url", "https://evil.invalid/release").toString()) }
        val empty = JSONObject(fixture()).put("assets", JSONArray()).put("body", JSONObject.NULL).toString()
        assertTrue(GithubReleaseClient.parseRelease(empty).notes.isEmpty())
        assertNull(GithubReleaseClient.parseRelease(empty).preferredApk(listOf("arm64-v8a")))
    }

    @Test fun rateLimitedApiFallsBackToTheVersionedPageButNeverAnUntrustedRedirect() = runBlocking {
        class Reply(url: String, private val code: Int, private val location: String? = null) : HttpURLConnection(URL(url)) {
            var closed = false
            override fun getResponseCode() = code
            override fun getHeaderField(name: String?) = when (name) { "Location" -> location; "X-RateLimit-Remaining" -> "0"; else -> null }
            override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
            override fun connect() = Unit
            override fun disconnect() { closed = true }
            override fun usingProxy() = false
        }
        val api = Reply(GithubReleaseClient.API_URL, 403)
        val page = Reply(GithubReleaseClient.RELEASES_URL, 302, "${GithubReleaseClient.REPOSITORY_URL}/releases/tag/v0.15.0")
        val result = GithubReleaseClient { if (it == GithubReleaseClient.API_URL) api else page }.latest()
        assertEquals("v0.15.0", result.tag); assertTrue(result.apks.isEmpty()); assertTrue(result.notes.contains("发布页面"))
        assertTrue(api.closed); assertTrue(page.closed)
        val bad = Reply(GithubReleaseClient.RELEASES_URL, 302, "https://evil.invalid/v9.0.0")
        try {
            GithubReleaseClient { if (it == GithubReleaseClient.API_URL) api else bad }.latest()
            fail("Untrusted redirect was accepted")
        } catch (error: UpdateCheckException) { assertTrue(error.message!!.contains("次数已达上限")) }
        assertTrue(bad.closed)
    }

    @Test fun checksAreManualCoalesceWhileRunningCacheSuccessAndRetryFailures() = runBlocking {
        val holder = ViewModelStore()
        val calls = AtomicInteger()
        val pending = CompletableDeferred<GithubRelease>()
        lateinit var model: UpdateViewModel
        instrumentation.runOnMainSync {
            model = UpdateViewModel { calls.incrementAndGet(); pending.await() }
            holder.put("update", model)
        }
        try {
            assertEquals(0, calls.get()); assertEquals(UpdateState.Idle, model.state.value)
            instrumentation.runOnMainSync { model.checkNow(); model.checkNow() }
            assertEquals(1, calls.get()); assertEquals(UpdateState.Checking, model.state.value)
            pending.complete(release)
            await { model.state.value is UpdateState.Checked }
            instrumentation.runOnMainSync { model.checkNow() }
            assertEquals(1, calls.get())
            var failedOnce = false
            lateinit var retry: UpdateViewModel
            instrumentation.runOnMainSync {
                retry = UpdateViewModel { if (!failedOnce) { failedOnce = true; throw UpdateCheckException("fixture offline") }; release }
                holder.put("retry", retry)
                retry.checkNow()
            }
            await { retry.state.value is UpdateState.Failed }
            instrumentation.runOnMainSync { retry.checkNow() }
            await { retry.state.value is UpdateState.Checked }
        } finally { instrumentation.runOnMainSync { holder.clear() } }
    }

    @Test fun aboutExposesCheckAndNewerVersionOffersCorrectApkAndReleaseNotes() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val state = mutableStateOf<UpdateState>(UpdateState.Idle)
        val requests = AtomicInteger()
        val opened = mutableListOf<String>()
        val downloaded = mutableListOf<Pair<ReleaseApk, String>>()
        try {
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent { LocalPlayerTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    UpdateSection(state.value, { requests.incrementAndGet(); state.value = UpdateState.Checked(release) }, { opened += it },
                        currentVersion = "0.14.0", supportedAbis = listOf("arm64-v8a", "armeabi-v7a"), debugBuild = false,
                        onDownload = { apk, tag -> downloaded += apk to tag })
                } } }
            }
            await { contains("检查更新") }
            assertEquals(0, requests.get())
            click("检查更新")
            await { contains("发现新版本") && contains("下载 APK") }
            assertTrue(contains("最新发布：v0.15.0"))
            scrollTo("新增功能\n修复问题")
            assertTrue(contains("新增功能\n修复问题"))
            click("下载 APK")
            assertEquals(release.preferredApk(listOf("arm64-v8a")), downloaded.single().first)
            assertEquals(release.tag, downloaded.single().second)
            assertTrue("APK download must not open a browser", opened.isEmpty())
            click("查看发布页面")
            assertEquals(release.pageUrl, opened.last())
            click("关闭")
            assertEquals(1, requests.get())
            scenario.onActivity { activity ->
                activity.setContent { LocalPlayerTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    AboutSection(onCheckUpdate = { requests.incrementAndGet() })
                } } }
            }
            await { contains("关于") && contains("检查更新") }
            click("检查更新")
            await { requests.get() == 2 }
        } finally { scenario.close() }
    }

    @Test fun actualSettingsEntryChecksGithubAndShowsResultOrRetry() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val container = (instrumentation.targetContext.applicationContext as PlayerApplication).container
        try {
            scenario.onActivity { container.player.selectTab(3) }
            await { contains("文件访问") }
            scrollTo("检查更新")
            click("检查更新")
            val end = android.os.SystemClock.elapsedRealtime() + 30000
            while (android.os.SystemClock.elapsedRealtime() < end && container.updates.state.value in listOf(UpdateState.Idle, UpdateState.Checking)) Thread.sleep(100)
            when (val result = container.updates.state.value) {
                is UpdateState.Checked -> {
                    assertNotNull(ReleaseVersion.parse(result.release.tag))
                    assertTrue(result.release.pageUrl.startsWith(GithubReleaseClient.REPOSITORY_URL))
                    android.util.Log.i("UpdateCheckTest", "Public GitHub check succeeded: ${result.release.tag}; APKs=${result.release.apks.size}")
                    await { contains("最新发布：${result.release.tag}") }
                }
                is UpdateState.Failed -> {
                    android.util.Log.i("UpdateCheckTest", "Public GitHub check showed recoverable failure: ${result.message}")
                    await { contains("检查更新失败") && contains("重试") }
                }
                else -> fail("GitHub check did not complete: $result")
            }
            scrollTo("查看发布页面")
            assertTrue(contains("查看发布页面"))
            click("关闭")
        } finally { scenario.close() }
    }

    @Test fun errorsCanRetryAndEqualOrOlderReleasesNeverOfferDownloads() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val state = mutableStateOf<UpdateState>(UpdateState.Failed("GitHub 请求次数已达上限，请稍后重试或打开发布页面"))
        val current = mutableStateOf("0.15.0")
        val clicks = AtomicInteger()
        val opened = mutableListOf<String>()
        try {
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent { LocalPlayerTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    UpdateSection(state.value, { clicks.incrementAndGet() }, { opened += it }, currentVersion = current.value, supportedAbis = listOf("arm64-v8a"), debugBuild = false)
                } } }
            }
            await { contains("检查更新") }; click("检查更新")
            await { contains("检查更新失败") && contains("重试") }
            assertFalse(contains("下载 APK"))
            click("重试"); assertEquals(2, clicks.get())
            click("查看发布页面"); assertEquals(GithubReleaseClient.RELEASES_URL, opened.single())
            instrumentation.runOnMainSync { state.value = UpdateState.Checked(release) }
            await { contains("已是最新版本") }
            assertFalse(contains("下载 APK"))
            instrumentation.runOnMainSync { current.value = "0.16.0" }
            await { contains("当前版本高于已发布版本") }
            assertFalse(contains("下载 APK"))
            instrumentation.runOnMainSync { current.value = "0.14.0"; state.value = UpdateState.Checked(release.copy(apks = emptyList())) }
            await { contains("发现新版本") }
            assertFalse(contains("下载 APK")); assertTrue(contains("查看发布页面"))
        } finally { scenario.close() }
    }

    private fun fixture(): String {
        fun asset(name: String, url: String, state: String = "uploaded") = JSONObject().put("name", name).put("browser_download_url", url).put("size", 20000).put("state", state)
        val base = "${GithubReleaseClient.REPOSITORY_URL}/releases/download/v0.15.0"
        return JSONObject().put("tag_name", "v0.15.0").put("name", "媒体播放器 v0.15.0").put("body", "新增功能\n修复问题")
            .put("html_url", "${GithubReleaseClient.REPOSITORY_URL}/releases/tag/v0.15.0").put("published_at", "2026-10-09T10:00:00Z")
            .put("draft", false).put("prerelease", false).put("assets", JSONArray()
                .put(asset("media-player-0.15.0-arm64-v8a.apk", "$base/media-player-0.15.0-arm64-v8a.apk"))
                .put(asset("media-player-0.15.0-universal.apk", "$base/media-player-0.15.0-universal.apk"))
                .put(asset("SHA256SUMS", "$base/SHA256SUMS"))
                .put(asset("media-player-0.15.0-x86.apk", "https://evil.invalid/app.apk"))
                .put(asset("media-player-0.15.0-x86_64.apk", "$base/x86_64.apk", "new"))).toString()
    }
    private fun walk(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) walk(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun contains(text: String) = walk(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text } != null
    private fun click(text: String) {
        scrollTo(text)
        var node = walk(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text }
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull(text, node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun scrollTo(text: String) {
        repeat(16) {
            if (contains(text)) return
            val scroll = walk(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable }
            if (scroll != null) scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(200)
        }
        await { contains(text) }
    }
    private fun await(predicate: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 10000
        while (android.os.SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(100) }
        assertTrue("Timed out", predicate())
    }
}
