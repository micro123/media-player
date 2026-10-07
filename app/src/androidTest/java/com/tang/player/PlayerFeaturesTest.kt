package com.tang.player

import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.MotionEvent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.media.AudioManager
import java.io.File
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.tang.player.core.MediaItem
import com.tang.player.core.PlaybackStatus
import com.tang.player.data.*
import com.tang.player.ui.PlayerViewModel
import com.tang.player.ui.LibrarySource
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class PlayerFeaturesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private val player: PlayerViewModel get() = requireNotNull(activity.player)
    private lateinit var first: MediaItem
    private lateinit var second: MediaItem
    private lateinit var originalPreferences: PlayerPreferences
    private var originalQueue: List<MediaItem> = emptyList()
    private var originalRecent: List<MediaItem> = emptyList()
    private lateinit var store: PlaybackStore
    private val created = mutableListOf<Uri>()
    private var originalBookmarkIds: Set<String> = emptySet()
    private var bookmarkSnapshotReady = false
    private val bookmarksStore get() = (context.applicationContext as PlayerApplication).container.bookmarks

    @Before
    fun prepare() = runBlocking {
        store = PlaybackStore(context)
        originalPreferences = store.readPreferences()
        originalQueue = store.readQueue()
        originalRecent = LocalMediaRepository(context).readRecent()
        originalBookmarkIds = bookmarksStore.items.value.map { it.id }.toSet()
        bookmarkSnapshotReady = true
        first = createVideo("feature-first")
        second = createVideo("feature-second")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
        await("view model ready") { activity.player != null && !player.library.value.isLoading }
        onMain { player.setAutoNext(false); player.setVideoOrientation(VideoOrientation.LANDSCAPE) }
    }

    @After
    fun cleanup() = runBlocking {
        try {
            if (::scenario.isInitialized) {
                onMain { activity.player?.pause() }
                await("paused before test teardown") { activity.player?.playback?.value?.status != PlaybackStatus.PLAYING }
                onMain {
                    // Disable auto-PiP synchronously before finishing a playing task.
                    activity.updatePlaybackPresentation(false, false, 16.0 / 9.0)
                    activity.finishAndRemoveTask()
                }
                scenario.close()
            }
        } finally {
            if (::store.isInitialized) {
                // Let lifecycle writes finish, then restore pre-test application data even on a failure.
                Thread.sleep(350)
                if (::originalPreferences.isInitialized) store.writePreferences(originalPreferences)
                store.writeQueue(originalQueue)
                LocalMediaRepository(context).writeRecent(originalRecent)
                created.forEach { store.writeBookmark(it.toString(), 0, 0) }
            }
            created.forEach { context.contentResolver.delete(it, null, null) }
            if (bookmarkSnapshotReady) bookmarksStore.items.value.filter { it.id !in originalBookmarkIds }.forEach {
                bookmarksStore.remove(it.id)
                (context.applicationContext as PlayerApplication).container.network.credentials.remove(it.id)
            }
        }
    }

    @Test
    fun mediaLibraryPlaylistOrderAndAutomaticNext() {
        val library = runBlocking { MediaBrowserRepository(context).queryLibrary() }
        assertTrue("First self-owned video missing from MediaStore", library.any { it.uri == first.uri })
        assertTrue("Second self-owned video missing from MediaStore", library.any { it.uri == second.uri })
        onMain { player.playList(listOf(first, second), 0) }
        await("first video") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.seekable && player.queue.value.index == 0 }
        assertTrue("Video did not default to fullscreen", player.fullScreen.value)
        onMain { player.moveQueueItem(0, 1) }
        assertEquals(first.uri, player.queue.value.current?.uri)
        assertEquals(1, player.queue.value.index)
        onMain { player.moveQueueItem(1, -1); player.setAutoNext(true); player.seekTo(39_800) }
        await("automatic next") { player.queue.value.index == 1 && player.playback.value.media?.uri == second.uri && player.playback.value.status == PlaybackStatus.PLAYING }
        onMain { player.previous() }
        await("previous") { player.queue.value.index == 0 && player.playback.value.media?.uri == first.uri && player.playback.value.status == PlaybackStatus.PLAYING }
        onMain { player.removeFromQueue(1) }
        assertEquals(1, player.queue.value.items.size)
        onMain { player.pause() }
        await("queue persisted") { runBlocking { store.readQueue() }.size == 1 }
    }

    @Test
    fun nativeResumeAndGlobalSpeedPersistence() {
        onMain { player.setRememberSpeed(true); player.setSpeed(1.7); player.setAspect(VideoAspect.FOUR_THREE); player.playList(listOf(first, second), 0) }
        await("first playing") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.seekable }
        onMain { player.pause(); player.seekTo(12_000) }
        await("saved seek position") { player.playback.value.status == PlaybackStatus.PAUSED && player.playback.value.positionMs in 11_800..12_200 }
        await("bookmark persisted") { runBlocking { store.readBookmark(first.uri) }?.positionMs?.let { it in 11_800..12_200 } == true }
        onMain { player.next() }
        await("second inherits global speed") { player.playback.value.media?.uri == second.uri && player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.speed == 1.7 }
        onMain { player.previous() }
        await("first resumes") { player.playback.value.media?.uri == first.uri && player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.positionMs in 11_800..15_000 }
        assertTrue(player.resumePosition.value >= 11_800)
        await("global preferences persisted") { val saved = PlaybackStore(context).readPreferences(); saved.rememberSpeed && kotlin.math.abs(saved.lastSpeed - 1.7) < 0.01 && saved.aspect == VideoAspect.FOUR_THREE }
        onMain { player.setSpeed(1.0); player.pause() }
        await("one-tap normal rate") { player.playback.value.speed == 1.0 }
        onMain { player.setRememberSpeed(false) }
        await("disable remembers") { !PlaybackStore(context).readPreferences().rememberSpeed }
    }

    @Test
    fun actualLongPressDoublesClampsAndRestoresOnReleaseAndCancel() {
        onMain { player.setRememberSpeed(true); player.setSpeed(3.0); player.playList(listOf(first), 0) }
        await("playing fullscreen") { player.fullScreen.value && player.playback.value.status == PlaybackStatus.PLAYING }
        Thread.sleep(1200) // Let orientation and Surface sizing settle.
        var down = sendDown()
        try {
            await("real long press activates") { player.speedBoost.value && player.playback.value.speed == 5.0 }
            assertEquals(3.0, player.baseSpeed.value, 0.001)
        } finally { sendEnd(down, MotionEvent.ACTION_UP) }
        await("release restores base") { !player.speedBoost.value && player.playback.value.speed == 3.0 }
        onMain { player.setSpeed(1.5) }
        Thread.sleep(300)
        down = sendDown()
        try { await("1.5x long press becomes 3x") { player.speedBoost.value && player.playback.value.speed == 3.0 } }
        finally { sendEnd(down, MotionEvent.ACTION_CANCEL) }
        await("cancel restores base") { !player.speedBoost.value && player.playback.value.speed == 1.5 }
        await("boost not persisted") { kotlin.math.abs(PlaybackStore(context).readPreferences().lastSpeed - 1.5) < 0.01 }
    }

    @Test
    fun videoGesturesSeekBrightnessVolumeAndDoubleTap() {
        onMain { player.setSpeed(1.0); player.playList(listOf(first), 0) }
        await("video gestures ready") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.seekable }
        Thread.sleep(1200)
        onMain { player.pause(); player.seekTo(5000); activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.5f } }
        await("paused at gesture origin") { player.playback.value.status == PlaybackStatus.PAUSED && player.playback.value.positionMs in 4800..5200 }
        var width = 0; var height = 0
        onMain { width = activity.window.decorView.width; height = activity.window.decorView.height }
        val y = height / 2
        shell("input swipe ${width / 3} $y ${width * 2 / 3} $y 450")
        await("horizontal seek gesture") { player.playback.value.positionMs > 13_000 }
        shell("input swipe ${width / 5} ${height * 2 / 3} ${width / 5} ${height / 3} 450")
        assertTrue("Left swipe did not increase brightness", activity.window.attributes.screenBrightness > 0.7f)
        val audio = context.getSystemService(AudioManager::class.java)
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try {
            onMain { audio.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume / 3, 0) }
            shell("input swipe ${width * 4 / 5} ${height * 2 / 3} ${width * 4 / 5} ${height / 3} 450")
            assertTrue("Right swipe did not increase volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC) > maxVolume / 3)
        } finally { onMain { audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0) } }
        doubleTap()
        await("double-tap resumes") { player.playback.value.status == PlaybackStatus.PLAYING }
        Thread.sleep(400)
        doubleTap()
        await("double-tap pauses") { player.playback.value.status == PlaybackStatus.PAUSED }
    }

    @Test
    fun configurableSkipButtonPersistsAndClampsAtEnd() {
        onMain { player.setSkipSeconds(10); player.playList(listOf(first), 0) }
        await("skip video ready") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.seekable }
        onMain { player.pause(); player.seekTo(5000) }
        await("skip origin") { player.playback.value.status == PlaybackStatus.PAUSED && player.playback.value.positionMs in 4800..5200 }
        Thread.sleep(400)
        clickAccessibleText("跳过 10 秒")
        await("skip button advances configured ten seconds") { player.playback.value.positionMs in 14_800..15_200 }
        await("skip setting persisted") { PlaybackStore(context).readPreferences().skipSeconds == 10 }
        onMain { player.setSkipSeconds(85) }
        Thread.sleep(400)
        clickAccessibleText("跳过 85 秒")
        await("skip clamps to end") { player.playback.value.positionMs >= 39_800 }
        assertTrue(player.playback.value.positionMs <= player.playback.value.durationMs)
        await("default skip can be restored") { PlaybackStore(context).readPreferences().skipSeconds == 85 }
    }

    @Test
    fun redesignedControlsLockGesturesAndUnlock() {
        onMain { player.setSpeed(1.5); player.playList(listOf(first), 0) }
        await("lock video ready") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.seekable }
        Thread.sleep(1200)
        onMain { player.pause(); player.seekTo(5000) }
        await("lock origin") { player.playback.value.status == PlaybackStatus.PAUSED && player.playback.value.positionMs in 4800..5200 }
        clickAccessibleText("锁定控制")
        await("unlock remains accessible") { findAccessibleText("解锁控制") != null }
        Thread.sleep(400)
        assertNull("Locked transport still exposed", findAccessibleText("播放倍速"))
        doubleTap()
        Thread.sleep(350)
        assertEquals(PlaybackStatus.PAUSED, player.playback.value.status)
        var width = 0; var height = 0
        onMain { width = activity.window.decorView.width; height = activity.window.decorView.height }
        shell("input swipe ${width / 3} ${height / 2} ${width * 2 / 3} ${height / 2} 450")
        assertTrue("Locked swipe changed progress", player.playback.value.positionMs in 4800..5200)
        val down = sendDown()
        try { Thread.sleep(800); assertFalse("Locked long press changed speed", player.speedBoost.value) }
        finally { sendEnd(down, MotionEvent.ACTION_UP) }
        clickAccessibleText("解锁控制")
        await("transport restored") { findAccessibleText("播放倍速") != null }
        clickAccessibleText("播放")
        await("unlocked play button works") { player.playback.value.status == PlaybackStatus.PLAYING }
    }

    @Test
    fun videoStartOrientationLandscapePortraitKeepAndRestore() {
        fun browse(request: Int, portrait: Boolean) {
            onMain { player.stopPlayback(); activity.requestedOrientation = request }
            await("browsing orientation $request") {
                player.playback.value.status == PlaybackStatus.IDLE && activity.requestedOrientation == request &&
                    (activity.window.decorView.width < activity.window.decorView.height) == portrait
            }
        }
        fun start(mode: VideoOrientation, portrait: Boolean, request: Int) {
            onMain { player.setVideoOrientation(mode); player.playList(listOf(first), 0) }
            await("playing in $mode") {
                player.playback.value.status == PlaybackStatus.PLAYING && activity.requestedOrientation == request &&
                    (activity.window.decorView.width < activity.window.decorView.height) == portrait
            }
            assertTrue(player.fullScreen.value)
            onMain { player.pause() }
            await("$mode persisted") { PlaybackStore(context).readPreferences().orientation == mode }
        }
        val portrait = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val landscape = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        browse(portrait, true)
        start(VideoOrientation.LANDSCAPE, false, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
        onMain { player.exitVideo() }
        await("landscape exit restores portrait") { player.playback.value.status == PlaybackStatus.IDLE && activity.requestedOrientation == portrait &&
            activity.window.decorView.width < activity.window.decorView.height }
        browse(landscape, false)
        start(VideoOrientation.PORTRAIT, true, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
        onMain { player.exitVideo() }
        await("portrait exit restores landscape") { player.playback.value.status == PlaybackStatus.IDLE && activity.requestedOrientation == landscape &&
            activity.window.decorView.width > activity.window.decorView.height }
        browse(portrait, true)
        start(VideoOrientation.KEEP, true, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
        onMain { player.exitVideo() }
        await("keep portrait restores request") { player.playback.value.status == PlaybackStatus.IDLE && activity.requestedOrientation == portrait }
        browse(landscape, false)
        start(VideoOrientation.KEEP, false, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
        assertTrue(activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))
        onMain { assertTrue(activity.requestPip()) }
        await("keep landscape enters PiP") { activity.isInPictureInPictureMode }
        Thread.sleep(1200) // PiP reports its mode before the system transition finishes; don't relaunch mid-animation.
        shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n com.tang.player/.MainActivity")
        await("keep landscape survives PiP return") { !activity.isInPictureInPictureMode &&
            activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED && activity.window.decorView.width > activity.window.decorView.height }
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        onMain { player.exitVideo() }
        await("keep landscape exit restores request") { player.playback.value.status == PlaybackStatus.IDLE && activity.requestedOrientation == landscape }
    }

    @Test
    fun redesignedLayoutSettingsAndPortraitAdaptation() {
        onMain { player.setSpeed(1.5); player.setAspect(VideoAspect.ORIGINAL); player.setSkipSeconds(85); player.playList(listOf(first, second), 0) }
        await("redesigned video ready") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.seekable }
        Thread.sleep(1200)
        onMain { player.pause(); player.seekTo(8000) }
        await("review frame") { player.playback.value.status == PlaybackStatus.PAUSED && player.playback.value.positionMs in 7800..8200 }
        assertControlBounds("跳过 85 秒", "播放进度", "播放倍速", "视频长宽比", "小窗播放", "锁定控制")
        capturePlayer("video-ui-landscape.png")
        clickAccessibleText("播放设置")
        await("editable skip duration") { findAccessibleNode { it.isEditable } != null }
        val editor = requireNotNull(findAccessibleNode { it.isEditable })
        assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "90")
        }))
        clickAccessibleText("保存时长")
        await("in-player skip setting saved") { PlaybackStore(context).readPreferences().skipSeconds == 90 }
        clickAccessibleText("4:3")
        await("in-player aspect saved") { player.preferences.value.aspect == VideoAspect.FOUR_THREE }
        clickAccessibleText("完成")
        await("configured skip icon updated") { findAccessibleText("跳过 90 秒") != null }
        clickAccessibleText("播放倍速")
        clickAccessibleText("恢复 1.0 倍")
        await("speed shortcut updates native clock") { player.playback.value.speed == 1.0 }
        clickAccessibleText("完成")
        onMain { player.setAspect(VideoAspect.ORIGINAL) }
        Thread.sleep(400)
        clickAccessibleText("切换为竖屏")
        await("portrait layout") { activity.window.decorView.width < activity.window.decorView.height }
        assertTrue("Portrait left the full-screen player", player.fullScreen.value)
        assertEquals(VideoOrientation.PORTRAIT, player.preferences.value.orientation)
        Thread.sleep(900)
        assertControlBounds("跳过 90 秒", "播放进度", "播放倍速", "视频长宽比", "小窗播放", "锁定控制")
        capturePlayer("video-ui-portrait.png")
        clickAccessibleText("播放倍速")
        clickAccessibleText("完成")
    }

    @Test
    fun explicitPipAndNativePlaybackContinues() {
        onMain { player.playList(listOf(first), 0) }
        await("video ready for pip") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.positionMs > 200 }
        Thread.sleep(500)
        onMain { assertTrue(activity.requestPip()) }
        await("explicit picture-in-picture") { activity.isInPictureInPictureMode }
        val position = player.playback.value.positionMs
        Thread.sleep(1300)
        assertEquals("PiP paused the video", PlaybackStatus.PLAYING, player.playback.value.status)
        assertTrue("Native clock stopped in PiP", player.playback.value.positionMs > position + 600)
        shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n com.tang.player/.MainActivity")
        await("return from pip") { !activity.isInPictureInPictureMode }
        assertEquals(first.uri, player.playback.value.media?.uri)
    }

    @Test
    fun leavingVideoStopsAndReturnsToItsBrowsingOriginWithResume() {
        onMain { player.selectTab(2); player.setSpeed(1.0); player.playList(listOf(first, second), 0) }
        await("video opened from playlist") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.seekable }
        Thread.sleep(1000)
        onMain { player.pause(); player.seekTo(12_000) }
        await("exit bookmark position") { player.playback.value.status == PlaybackStatus.PAUSED && player.playback.value.positionMs in 11_800..12_200 }
        clickAccessibleText("返回")
        await("video fully stopped") { player.playback.value.status == PlaybackStatus.IDLE && player.playback.value.media == null }
        assertFalse(player.fullScreen.value)
        assertEquals("Did not return to playlist origin", 2, player.selectedTab.value)
        await("exit progress saved") { runBlocking { store.readBookmark(first.uri) }?.positionMs?.let { it in 11_800..12_200 } == true }
        await("browsing navigation restored") { findAccessibleText("媒体库") != null && findAccessibleText("播放列表 · 2") != null }
        assertTrue(player.queue.value.items.all { it.uri == first.uri || it.uri == second.uri })
        saveScreenshot("app-queue-ui.png")
        clickAccessibleText("▶ 1. ${first.displayName}")
        await("reopen resumes saved video") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.positionMs in 11_800..16_000 }
        assertTrue(player.fullScreen.value)
        assertNull("Browsing navigation exposed during video", findAccessibleText("媒体库"))
        shell("input keyevent KEYCODE_BACK")
        await("system back also stops") { player.playback.value.status == PlaybackStatus.IDLE && !player.fullScreen.value }
        assertEquals(2, player.selectedTab.value)
    }

    @Test
    fun homeStopsVideoUnlessExplicitPipWasRequested() {
        onMain { player.setSpeed(1.0); player.playList(listOf(first), 0) }
        await("video ready to leave") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.seekable }
        onMain { player.seekTo(12_000) }
        await("home bookmark position") { player.playback.value.positionMs in 11_800..14_000 }
        shell("input keyevent KEYCODE_HOME")
        await("home stopped native video") { player.playback.value.status == PlaybackStatus.IDLE && player.playback.value.media == null }
        assertFalse("Home entered PiP automatically", activity.isInPictureInPictureMode)
        await("home saved progress") { runBlocking { store.readBookmark(first.uri) }?.positionMs?.let { it >= 11_800 } == true }
        shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n com.tang.player/.MainActivity")
        awaitAppWindow()
        assertEquals(PlaybackStatus.IDLE, player.playback.value.status)
    }

    @Test
    fun musicTagsCoverTwoLayoutsAndAudioControls() {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "music-metadata-${System.nanoTime()}.mp3")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/LocalPlayerTests")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        created += uri
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            instrumentation.context.assets.open("music-tags-test.mp3").use { it.copyTo(out) }
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val audio = runBlocking { LocalMediaRepository(context).resolve(uri) }
        val portrait = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val landscape = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onMain { activity.requestedOrientation = portrait }
        await("portrait before music") { activity.window.decorView.width < activity.window.decorView.height }
        onMain { player.playList(listOf(audio), 0) }
        await("music playing with cover") { player.playback.value.status == PlaybackStatus.PLAYING && player.audioMetadata.value.cover != null }
        onMain { player.pause() }
        val tags = player.audioMetadata.value
        assertEquals("月下航行（测试）", tags.title); assertEquals("测试歌手", tags.artist); assertEquals("星河录", tags.album)
        assertEquals("测试乐队", tags.albumArtist); assertEquals("2026", tags.year); assertEquals("2/12", tags.track)
        assertTrue(maxOf(tags.cover!!.width, tags.cover.height) <= 768)
        assertEquals(portrait, activity.requestedOrientation)
        await("portrait music controls") { findAccessibleText("专辑封面") != null && findAccessibleText("音乐播放进度") != null }
        assertNull(findAccessibleText("小窗播放")); assertNull(findAccessibleText("切换为横屏")); assertNull(findAccessibleText("视频长宽比"))
        saveScreenshot("music-portrait-0.9.png")
        clickUiAction("歌曲信息")
        await("ID3 details") { findAccessibleText("专辑歌手") != null && findAccessibleText("测试乐队") != null }
        saveScreenshot("music-info-0.9.png")
        clickUiAction("关闭")
        onMain { activity.requestedOrientation = landscape }
        await("landscape music layout") { activity.window.decorView.width > activity.window.decorView.height && findAccessibleText("音乐播放进度") != null }
        assertEquals(landscape, activity.requestedOrientation)
        saveScreenshot("music-landscape-0.9.png")
        clickUiAction("播放")
        await("music resumes") { player.playback.value.status == PlaybackStatus.PLAYING }
        onMain { player.seekTo(15_000) }
        await("music seek") { player.playback.value.positionMs >= 14_800 }
        clickUiAction("返回浏览")
        await("music mini player with tags") { !player.audioPlayerOpen.value && findAccessibleText("月下航行（测试）") != null }
        assertEquals(PlaybackStatus.PLAYING, player.playback.value.status)
        clickUiAction("打开音频播放器")
        await("music page reopened") { player.audioPlayerOpen.value }
        clickUiAction("停止")
        await("music stops") { player.playback.value.status == PlaybackStatus.IDLE }
        assertTrue(player.audioMetadata.value.uri.isEmpty())
    }

    @Test
    fun audioReturnsToBrowsingWithMiniPlayerAndCanBeStopped() {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "feature-audio-${System.nanoTime()}.wav")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/LocalPlayerTests")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        created += uri
        val pcmSize = 8000 * 40 * 2
        val wave = java.nio.ByteBuffer.allocate(44 + pcmSize).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcmSize); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcmSize)
        }.array()
        context.contentResolver.openOutputStream(uri)!!.use { it.write(wave) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val audio = LocalMediaRepository(context).let { runBlocking { it.resolve(uri) } }
        onMain { player.selectTab(0); player.playList(listOf(audio), 0) }
        await("audio screen opened") { player.audioPlayerOpen.value && player.playback.value.status == PlaybackStatus.PLAYING }
        clickAccessibleText("返回浏览")
        await("audio browsing mini player") { !player.audioPlayerOpen.value && findAccessibleText("打开音频播放器") != null }
        assertEquals(PlaybackStatus.PLAYING, player.playback.value.status)
        clickAccessibleText("打开音频播放器")
        await("audio screen expanded") { player.audioPlayerOpen.value }
        clickAccessibleText("停止")
        await("audio stopped") { player.playback.value.status == PlaybackStatus.IDLE && !player.audioPlayerOpen.value }
    }

    @Test
    fun mediaLibraryGroupsSeriesOrdersEpisodesAndReturnsToDetails() {
        val title = "巡海日记 测试${System.nanoTime()}"
        val firstName = "$title 第10集 远航.mp4"
        val secondName = "$title 第2集 出发.mp4"
        context.contentResolver.update(Uri.parse(first.uri), ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, firstName) }, null, null)
        context.contentResolver.update(Uri.parse(second.uri), ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, secondName) }, null, null)
        first = first.copy(displayName = firstName); second = second.copy(displayName = secondName)
        onMain {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            player.setGroupMedia(true); player.selectSource(LibrarySource.MEDIA_STORE)
        }
        await("test series inferred") {
            player.library.value.mediaEntries.filterIsInstance<LibraryMediaEntry.Series>()
                .any { it.title == title && it.items.map { media -> media.uri } == listOf(second.uri, first.uri) } && !player.library.value.isLoading
        }
        await("portrait library") { activity.window.decorView.width < activity.window.decorView.height }
        await("library search ready") { findAccessibleNode { it.isEditable } != null }
        assertTrue(requireNotNull(findAccessibleNode { it.isEditable }).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title)
        }))
        await("only owned test series shown") { findAccessibleText("1 个剧集 · 2 个文件") != null }
        saveScreenshot("library-series.png")
        clickAccessibleText("打开剧集 $title")
        await("series details open") { findAccessibleText("2 个文件 · 按集数顺序排列") != null }
        assertNotNull(findAccessibleText(firstName)); assertNotNull(findAccessibleText(secondName))
        saveScreenshot("library-series-detail.png")
        clickAccessibleText(secondName)
        await("selected episode plays") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.media?.uri == second.uri }
        assertEquals(listOf(second.uri, first.uri), player.queue.value.items.map { it.uri })
        assertEquals(0, player.queue.value.index)
        onMain { player.pause() }
        await("series episode paused") { player.playback.value.status == PlaybackStatus.PAUSED }
        clickAccessibleText("返回")
        await("exit returns to same series") { player.playback.value.status == PlaybackStatus.IDLE && findAccessibleText("2 个文件 · 按集数顺序排列") != null }
        onMain { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await("portrait details restored") { activity.window.decorView.width < activity.window.decorView.height }
        shell("input keyevent KEYCODE_BACK")
        await("back returns to grouped media library") { findAccessibleText("1 个剧集 · 2 个文件") != null }
        clickAccessibleText("剧集归类")
        await("grouping preference persisted") { !PlaybackStore(context).readPreferences().groupMedia }
        await("plain media list restored") { findAccessibleText(firstName) != null && findAccessibleText(secondName) != null }
        assertNull(findAccessibleText("打开剧集 $title"))
    }

    @Test
    fun fiveTimesFullHdPlaybackMeasuresAudioVideoSync() {
        val video = createVideo("feature-sync", "sync-test-video.mp4")
        onMain { player.setSpeed(1.0); player.playList(listOf(video), 0) }
        await("full HD audio/video playback") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.avSyncMs != null }
        onMain { player.setSpeed(5.0) }
        await("five times speed") { player.playback.value.speed == 5.0 && player.playback.value.positionMs > 2_000 }
        Thread.sleep(1300)
        val samples = mutableListOf<Double>()
        repeat(18) {
            player.playback.value.avSyncMs?.let { samples += kotlin.math.abs(it) }
            Thread.sleep(150)
        }
        val max = samples.maxOrNull() ?: error("No A/V sync samples")
        android.util.Log.i("LocalPlayerSyncTest", "5x 1080p30 hwdec=${player.playback.value.hardwareDecoder}, maxAbsAvSyncMs=$max, samples=$samples")
        assertTrue("5x A/V drift: $max ms (${player.playback.value.hardwareDecoder})", max < 300)
        onMain { player.setSpeed(1.0) }
        await("speed reset sync") { player.playback.value.speed == 1.0 && (player.playback.value.avSyncMs?.let { kotlin.math.abs(it) < 100 } == true) }
    }

    @Test
    fun httpMediaAndRelativeNetworkM3uUseTheSamePlaybackFlow() = runBlocking {
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(android.Manifest.permission.INTERNET))
        try {
            val fd = android.system.Os.socket(android.system.OsConstants.AF_INET, android.system.OsConstants.SOCK_STREAM, 0)
            android.system.Os.close(fd)
        } catch (error: android.system.ErrnoException) {
            if (error.errno != android.system.OsConstants.ECONNREFUSED) throw error
            // An environment failure before our HTTP code is reached is reported as skipped, not passed.
            android.util.Log.w("LocalPlayerNetworkTest", "SKIPPED: device rejects socket() with ECONNREFUSED despite granted INTERNET")
            Assume.assumeNoException("Device network stack rejects socket() before HTTP test starts", error)
        }
        val bytes = instrumentation.context.assets.open("feature-test-video.mp4").use { it.readBytes() }
        val list = "#EXTM3U\n#EXTINF:-1,网络测试集\nepisode.mp4\nlavf://invalid\n"
        val hls = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST\n"
        LocalMediaHttpServer(mapOf("/show/episode.mp4" to ("video/mp4" to bytes),
            "/show/list.m3u" to ("audio/x-mpegurl" to list.toByteArray()),
            "/show/live.m3u8" to ("application/vnd.apple.mpegurl" to hls.toByteArray()))).use { server ->
            val playlists = PlaylistRepository(context, MediaRepository(context))
            val imported = playlists.read("${server.base}/show/list.m3u")
            assertEquals(1, imported.items.size); assertEquals(1, imported.rejected)
            assertEquals("${server.base}/show/episode.mp4", imported.items.single().uri)
            val stream = playlists.read("${server.base}/show/live.m3u8")
            assertEquals(1, stream.items.size); assertTrue(stream.items.single().isVideo)
            assertEquals("${server.base}/show/live.m3u8", stream.items.single().uri)
            val media = imported.items.single()
            try {
                onMain { player.setSpeed(1.0); player.playList(imported.items) }
                await("HTTP native stream loaded") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.seekable && player.playback.value.durationMs > 39_000 }
                assertTrue(player.fullScreen.value)
                assertEquals("网络测试集", player.playback.value.media?.displayName)
                onMain { player.pause(); player.seekTo(12_000) }
                await("HTTP range seek") { player.playback.value.status == PlaybackStatus.PAUSED && player.playback.value.positionMs in 11_800..12_200 }
                onMain { player.saveLocation("测试 HTTP", media.uri); player.stopPlayback() }
                await("HTTP location stored") { bookmarksStore.items.value.any { it.name == "测试 HTTP" } }
                assertEquals(PlaybackStatus.IDLE, player.playback.value.status)
            } finally { onMain { player.stopPlayback() }; store.writeBookmark(media.uri, 0, 0) }
        }
    }

    @Test
    fun namedBookmarksPersistLocationsPositionsAndPlaylists() {
        onMain { player.saveLocation("测试 NAS", "smb://test-nas/share"); player.saveLocation("测试 NFS", "nfs://test-nas/media") }
        await("network bookmarks saved") { bookmarksStore.items.value.count { it.id !in originalBookmarkIds } == 2 }
        assertEquals(2, BookmarkRepository(context).items.value.count { it.id !in originalBookmarkIds })
        onMain { player.setSpeed(1.0); player.playList(listOf(first, second), 0) }
        await("video loaded for bookmark") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.seekable }
        onMain { player.pause(); player.seekTo(12_000) }
        await("bookmark source position") { player.playback.value.status == PlaybackStatus.PAUSED && player.playback.value.positionMs in 11_800..12_200 }
        onMain { player.savePositionBookmark("测试第12秒"); player.savePlaylistBookmark("测试两集列表") }
        await("position and playlist saved") { bookmarksStore.items.value.any { it.name == "测试第12秒" } && bookmarksStore.items.value.any { it.name == "测试两集列表" } }
        onMain { player.seekTo(25_000) }
        await("progress moved independently") { player.playback.value.positionMs in 24_800..25_200 }
        val position = bookmarksStore.items.value.first { it.name == "测试第12秒" }
        onMain { player.openBookmark(position) }
        await("explicit bookmark overrides automatic resume") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.positionMs in 11_800..15_000 }
        onMain { player.stopPlayback(); player.removeFromQueue(1) }
        val list = bookmarksStore.items.value.first { it.name == "测试两集列表" }
        onMain { player.openBookmark(list) }
        assertEquals(listOf(first.uri, second.uri), player.queue.value.items.map { it.uri })
        assertEquals(PlaybackStatus.IDLE, player.playback.value.status)
        val location = bookmarksStore.items.value.first { it.name == "测试 NAS" }
        onMain { player.saveLocation("测试 NAS 修改", "smb://test-nas/new-share", location.id) }
        await("location edited in place") { BookmarkRepository(context).items.value.any { it.id == location.id && it.name == "测试 NAS 修改" } }
        onMain { player.removeBookmark(location.id) }
        await("location removed") { BookmarkRepository(context).items.value.none { it.id == location.id } }
    }

    @Test
    fun m3uImportsExportsAndPreservesTitles() = runBlocking {
        val input = File(context.cacheDir, "feature-list-${System.nanoTime()}.m3u")
        val output = File(context.cacheDir, "feature-export-${System.nanoTime()}.m3u")
        try {
            input.writeText(M3uCodec.write(listOf(M3uEntry(second.uri, "测试第二集"), M3uEntry(first.uri, "测试第一集"))))
            val playlists = PlaylistRepository(context, MediaRepository(context))
            val imported = playlists.read(Uri.fromFile(input).toString())
            assertEquals(listOf(second.uri, first.uri), imported.items.map { it.uri })
            assertEquals(listOf("测试第二集", "测试第一集"), imported.items.map { it.displayName })
            playlists.write(Uri.fromFile(output), imported.items)
            assertEquals(imported.items.map { it.uri }, playlists.read(Uri.fromFile(output).toString()).items.map { it.uri })
            onMain { player.importPlaylist(Uri.fromFile(input).toString()) }
            await("M3U imported without autoplay") { player.queue.value.items.any { it.uri == second.uri && it.displayName == "测试第二集" } }
            assertEquals(PlaybackStatus.IDLE, player.playback.value.status)
            onMain { player.playIndex(player.queue.value.items.indexOfFirst { it.uri == second.uri }) }
            await("M3U episode title survives resolution") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.media?.displayName == "测试第二集" }
        } finally { input.delete(); output.delete() }
    }

    @Test
    fun clipExportsOriginalSpeedAudioVideoAndReturnsToPlayback() = runBlocking {
        val output = File(context.cacheDir, "feature-clip-${System.nanoTime()}.mp4")
        try {
            onMain { player.setSpeed(5.0); player.playList(listOf(first), 0) }
            await("clip source playing") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.seekable }
            onMain { player.pause(); player.seekTo(10_000) }
            await("clip start position") { player.playback.value.status == PlaybackStatus.PAUSED && player.playback.value.positionMs in 9900..10_100 }
            onMain { player.markClipStart(); player.seekTo(15_000) }
            await("clip end position") { player.playback.value.positionMs in 14_900..15_100 }
            onMain { player.markClipEnd() }
            await("clip keyframe preview") { player.clip.value.actualStartMs != null && !player.clip.value.preparing }
            assertEquals("Long GOP should honestly preview the previous keyframe", 0L, player.clip.value.actualStartMs)
            onMain { player.stopPlayback() }
            assertNotNull("SAF background stop lost completed interval", player.clip.value.endMs)
            val range = ClipRange(first, requireNotNull(player.clip.value.startMs), requireNotNull(player.clip.value.endMs))
            val preview = AndroidClipExporter(context).prepare(range)
            onMain { player.exportClip(Uri.fromFile(output)) }
            await("clip exported through ViewModel") { !player.clip.value.exporting && player.clip.value.media == null && output.exists() && output.length() > 10_000 }
            assertTrue(preview.actualStartMs <= 10_100)
            assertTrue(output.length() > 10_000)
            val metadata = android.media.MediaMetadataRetriever()
            try {
                metadata.setDataSource(output.path)
                val duration = requireNotNull(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                val expected = preview.endMs - preview.actualStartMs
                assertTrue("Clip was sped up or wrong interval: $duration expected $expected", kotlin.math.abs(duration - expected) < 150)
                assertEquals("yes", metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertEquals("yes", metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            } finally { metadata.release() }
            onMain { player.setSpeed(1.0); player.open(Uri.fromFile(output)) }
            await("exported clip plays in libmpv") { player.playback.value.status == PlaybackStatus.PLAYING && kotlin.math.abs(player.playback.value.durationMs - (preview.endMs - preview.actualStartMs)) < 150 }
        } finally { onMain { player.stopPlayback() }; output.delete(); store.writeBookmark(Uri.fromFile(output).toString(), 0, 0) }
    }

    @Test
    fun bookmarkNetworkPlaylistAndClipActionsAreReachableInTheUi() {
        onMain { player.setSpeed(1.0); player.playList(listOf(first, second)); activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await("own test queue ready") { player.playback.value.status == PlaybackStatus.PLAYING }
        onMain { player.stopPlayback(); player.selectTab(4) }
        await("portrait browsing") { activity.window.decorView.width < activity.window.decorView.height }
        Thread.sleep(500)
        await("bookmarks screen") { findAccessibleText("添加网络位置") != null }
        clickUiAction("添加网络位置")
        await("network form") { findAccessibleNode { it.isEditable } != null }
        setEditableText(0, "smb://test-nas/share")
        setEditableText(1, "测试界面 NAS")
        if (originalBookmarkIds.isEmpty()) saveScreenshot("network-location-ui.png")
        clickUiAction("保存书签")
        await("location saved from form") { bookmarksStore.items.value.any { it.name == "测试界面 NAS" } }
        clickUiAction("播放列表")
        await("playlist import/export actions") { findAccessibleText("导入 M3U") != null && findAccessibleText("导出 M3U") != null }
        saveScreenshot("playlist-m3u-ui.png")
        clickUiAction("保存列表书签")
        await("playlist name form") { findAccessibleNode { it.isEditable } != null }
        setEditableText(0, "测试界面列表")
        clickUiAction("保存")
        await("playlist saved from form") { bookmarksStore.items.value.any { it.name == "测试界面列表" } }
        onMain { player.playIndex(0) }
        await("video for UI actions") { player.playback.value.status == PlaybackStatus.PLAYING && player.playback.value.seekable }
        onMain { player.pause(); player.seekTo(10_000) }
        await("UI clip paused start") { player.playback.value.status == PlaybackStatus.PAUSED && player.playback.value.positionMs in 9900..10_100 }
        clickUiAction("更多播放操作")
        clickUiAction("保存播放位置书签")
        await("position name form") { findAccessibleNode { it.isEditable } != null }
        setEditableText(0, "测试界面位置")
        clickUiAction("保存")
        await("position saved from UI") { bookmarksStore.items.value.any { it.name == "测试界面位置" } }
        clickUiAction("更多播放操作")
        clickUiAction("区间录制")
        clickUiAction("标记起点")
        clickUiAction("返回观看")
        onMain { player.seekTo(15_000) }
        await("UI clip paused end") { player.playback.value.positionMs in 14_900..15_100 }
        clickUiAction("● 区间起点 00:10 · 标记终点")
        clickUiAction("标记终点")
        await("actual clip range displayed") { findAccessibleText("实际导出 00:00～00:15") != null }
        saveScreenshot("clip-range-ui.png")
        assertTrue(requireNotNull(findAccessibleText("导出片段")).isEnabled)
        clickUiAction("返回观看")
        onMain { player.stopPlayback(); player.selectTab(4) }
        await("bookmark UI has owned items") { findAccessibleText("测试界面 NAS") != null }
        if (originalBookmarkIds.isEmpty()) saveScreenshot("bookmarks-ui.png")
    }

    private fun clickUiAction(text: String) {
        // Text-field ACTION_SET_TEXT and Compose validation publish asynchronously.
        await("enabled UI action $text") {
            var node = findAccessibleText(text)
            while (node != null && !node.isClickable && node.parent != null) node = node.parent
            node?.isClickable == true && node.isEnabled
        }
        var node = requireNotNull(findAccessibleText(text))
        while (!node.isClickable && node.parent != null) node = requireNotNull(node.parent)
        assertTrue("$text is not an enabled clickable action", node.isClickable && node.isEnabled)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun setEditableText(index: Int, value: String) {
        val editors = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isEditable) editors += node
            for (child in 0 until node.childCount) visit(node.getChild(child))
        }
        visit(awaitAppWindow())
        val editor = editors.getOrNull(index) ?: error("Editable field $index not found")
        assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        instrumentation.waitForIdleSync()
    }

    private fun createVideo(prefix: String, asset: String = "feature-test-video.mp4"): MediaItem {
        val name = "$prefix-${System.nanoTime()}.mp4"
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/LocalPlayerTests"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("Cannot create test video")
        created += uri
        instrumentation.context.assets.open(asset).use { input ->
            context.contentResolver.openOutputStream(uri)!!.use { output -> input.copyTo(output) }
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return MediaItem(uri.toString(), name, "video/mp4", null)
    }

    private data class PointerDown(val time: Long, val x: Float, val y: Float)
    private fun sendDown(): PointerDown {
        var x = 0f; var y = 0f
        onMain { x = activity.window.decorView.width / 2f; y = activity.window.decorView.height / 2f }
        val down = PointerDown(SystemClock.uptimeMillis(), x, y)
        MotionEvent.obtain(down.time, down.time, MotionEvent.ACTION_DOWN, x, y, 0).also {
            instrumentation.sendPointerSync(it); it.recycle()
        }
        return down
    }
    private fun sendEnd(down: PointerDown, action: Int) {
        MotionEvent.obtain(down.time, SystemClock.uptimeMillis(), action, down.x, down.y, 0).also {
            instrumentation.sendPointerSync(it); it.recycle()
        }
    }
    private fun doubleTap() {
        val firstDown = sendDown()
        Thread.sleep(55)
        sendEnd(firstDown, MotionEvent.ACTION_UP)
        Thread.sleep(90)
        val secondDown = sendDown()
        Thread.sleep(55)
        sendEnd(secondDown, MotionEvent.ACTION_UP)
    }
    private fun clickAccessibleText(text: String) {
        var target: AccessibilityNodeInfo? = null
        val display = Rect()
        awaitAppWindow().getBoundsInScreen(display)
        await("visible $text button") {
            target = findAccessibleText(text)
            target != null
        }
        val bounds = Rect()
        requireNotNull(target).getBoundsInScreen(bounds)
        if (bounds.isEmpty || !display.contains(bounds)) {
            requireNotNull(target).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            await("$text scrolled into view") {
                target = findAccessibleText(text)
                target?.getBoundsInScreen(bounds)
                target != null && !bounds.isEmpty && display.contains(bounds)
            }
        }
        instrumentation.waitForIdleSync()
        assertTrue("$text outside app: $bounds / $display", display.contains(bounds))
        val down = PointerDown(SystemClock.uptimeMillis(), bounds.exactCenterX(), bounds.exactCenterY())
        MotionEvent.obtain(down.time, down.time, MotionEvent.ACTION_DOWN, down.x, down.y, 0).also {
            instrumentation.sendPointerSync(it); it.recycle()
        }
        Thread.sleep(55)
        sendEnd(down, MotionEvent.ACTION_UP)
    }
    private fun findAccessibleNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun findAccessibleText(text: String) = findAccessibleNode { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun assertControlBounds(vararg descriptions: String) {
        val display = Rect()
        awaitAppWindow().getBoundsInScreen(display)
        descriptions.forEach { description ->
            await("visible $description") { findAccessibleText(description) != null }
            val bounds = Rect()
            requireNotNull(findAccessibleText(description)).getBoundsInScreen(bounds)
            assertFalse("$description has empty bounds", bounds.isEmpty)
            assertTrue("$description clipped outside display: $bounds / $display", display.contains(bounds))
        }
    }
    private fun capturePlayer(name: String) {
        // Only capture this test's own media, never a user's video or another application.
        assertEquals(first.uri, player.playback.value.media?.uri)
        saveScreenshot(name)
    }
    private fun saveScreenshot(name: String) {
        // Accessibility updates before the display's rotation animation finishes.
        Thread.sleep(850)
        instrumentation.waitForIdleSync()
        assertEquals(context.packageName, awaitAppWindow().packageName.toString())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun awaitAppWindow(): AccessibilityNodeInfo {
        var root: AccessibilityNodeInfo? = null
        await("application accessibility window ready") {
            root = instrumentation.uiAutomation.rootInActiveWindow
            root?.packageName?.toString() == context.packageName
        }
        return requireNotNull(root)
    }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Timed out: $label; playback=${activity.player?.playback?.value}; " +
                "orientation=${activity.requestedOrientation}, config=${activity.resources.configuration.orientation}, " +
                "window=${activity.window.decorView.width}x${activity.window.decorView.height}, " +
                "pip=${activity.isInPictureInPictureMode}, lifecycle=${activity.lifecycle.currentState}")
            Thread.sleep(50)
        }
    }
}
