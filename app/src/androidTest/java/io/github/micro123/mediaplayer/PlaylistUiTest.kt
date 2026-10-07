package io.github.micro123.mediaplayer

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.micro123.mediaplayer.core.*
import io.github.micro123.mediaplayer.data.QueuePreview
import io.github.micro123.mediaplayer.ui.components.PlaylistContent
import io.github.micro123.mediaplayer.ui.theme.LocalPlayerTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Generated artwork/metadata only: drag handles must not trigger playback or row removal. */
@RunWith(AndroidJUnit4::class)
class PlaylistUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val queue = mutableStateOf(PlaybackQueue())
    private val selected = AtomicInteger()
    private val moved = AtomicInteger()
    private val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(32, 146, 172)) }

    private fun launch(): ActivityScenario<MainActivity> {
        val repeated = MediaItem("file:///fixture/repeated.mp3", "重复曲目", "audio/mpeg", 1572864)
        queue.value = PlaybackQueue(listOf(MediaItem("file:///fixture/video.mp4", "测试视频", "video/mp4", 12L * 1024 * 1024),
            repeated, MediaItem("file:///fixture/song2.mp3", "第二首", "audio/mpeg", 1572864), repeated) +
            List(12) { MediaItem("file:///fixture/extra-$it.mp3", "测试歌曲 ${it + 5}", "audio/mpeg", 1572864) }, 3)
        return ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent { LocalPlayerTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    PlaylistContent(queue.value, true, {}, { selected.incrementAndGet() }, { index ->
                        queue.value = queue.value.copy(items = queue.value.items.toMutableList().apply { removeAt(index) })
                    }, { from, delta -> queue.value = queue.value.move(from, from + delta); moved.incrementAndGet() }, {},
                        Modifier.fillMaxSize().padding(16.dp), onPreview = { media ->
                            if (media.isVideo) QueuePreview(bitmap, durationMs = 40000, width = 1920, height = 1080)
                            else QueuePreview(bitmap, "测试歌手 · 测试专辑", durationMs = 65000, bitrate = 320000)
                        })
                } } }
            }
        }
    }

    @Test fun queueShowsArtworkFactsDedicatedHandlesAndAccessibleMoves() {
        val scenario = launch()
        try {
            await("preview and media facts") { node("视频缩略图：测试视频") != null && node("音乐封面：重复曲目") != null && node("00:40 · MP4 · 1920×1080 · 12.0 MB") != null }
            assertNotNull(node("01:05 · MP3 · 320 kbps · 1.5 MB"))
            assertNotNull(node("重排第 1 项")); assertNull(node("上移")); assertNull(node("下移"))
            val handle = requireNotNull(node("重排第 1 项"))
            val down = handle.actionList.first { it.label?.toString() == "下移" }
            assertTrue(handle.performAction(down.id))
            await("accessible handle reorder") { queue.value.items[1].displayName == "测试视频" }
            assertEquals(3, queue.value.index); assertEquals(0, selected.get())
            await("reordered first row remains visible") { node("移除第 1 项") != null }
            instrumentation.waitForIdleSync()
            screenshot("playlist-preview-0.14.png")
            val remove = requireNotNull(node("移除第 1 项"))
            assertTrue(remove.isEnabled)
            val bounds = Rect().also(remove::getBoundsInScreen)
            instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").close()
            await("row remove") { queue.value.items.size == 15 }
        } finally { scenario.close() }
    }

    @Test fun handleDragReordersAcrossRowsAndAutoScrollsWithoutPlaying() {
        val scenario = launch()
        try {
            await("queue handles") { node("重排第 1 项") != null && node("重排第 3 项") != null }
            val first = Rect().also { requireNotNull(node("重排第 1 项")).getBoundsInScreen(it) }
            val third = Rect().also { requireNotNull(node("重排第 3 项")).getBoundsInScreen(it) }
            drag(first.centerX().toFloat(), first.centerY().toFloat(), third.centerY().toFloat(), 0)
            await("drag reorder") { queue.value.items.indexOfFirst { it.displayName == "测试视频" } >= 1 }
            assertTrue(moved.get() > 0); assertEquals(0, selected.get())
            screenshot("playlist-drag-0.14.png")
            await("first row handle remains visible after drag") { node("重排第 1 项") != null }
            val currentVideoIndex = queue.value.items.indexOfFirst { it.displayName == "测试视频" }
            val next = Rect().also { requireNotNull(node("重排第 ${currentVideoIndex + 1} 项")).getBoundsInScreen(it) }
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val screen = Rect().also { root.getBoundsInScreen(it) }
            val target = screen.bottom - 110f
            val moving = queue.value.items[currentVideoIndex]
            drag(next.centerX().toFloat(), next.centerY().toFloat(), target, 1800)
            screenshot("playlist-edge-drag-0.14.png")
            await("edge scroll move beyond initial viewport") { queue.value.items.indexOfFirst { it === moving } >= 9 }
            assertEquals(0, selected.get())
        } finally { scenario.close() }
    }

    private fun drag(x: Float, fromY: Float, toY: Float, holdMs: Long) {
        val start = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, fromY)
        for (step in 1..20) { Thread.sleep(30); event(MotionEvent.ACTION_MOVE, fromY + (toY - fromY) * step / 20) }
        if (holdMs > 0) {
            val end = SystemClock.uptimeMillis() + holdMs
            while (SystemClock.uptimeMillis() < end) { Thread.sleep(40); event(MotionEvent.ACTION_MOVE, toY) }
        }
        event(MotionEvent.ACTION_UP, toY)
        instrumentation.waitForIdleSync()
    }
    private fun node(text: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        return walk(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text || it.contentDescription?.toString() == text }
    }
    private fun walk(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (match(node)) return node
        for (index in 0 until node.childCount) walk(node.getChild(index), match)?.let { return it }
        return null
    }
    private fun screenshot(name: String) {
        assertEquals(instrumentation.targetContext.packageName, instrumentation.uiAutomation.rootInActiveWindow.packageName.toString())
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        assertTrue(label, condition())
    }
}
