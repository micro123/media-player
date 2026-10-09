package io.github.micro123.mediaplayer

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.micro123.mediaplayer.core.MediaItem
import io.github.micro123.mediaplayer.core.PlaybackStatus
import io.github.micro123.mediaplayer.data.LocalMediaRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackgroundMusicTest {
    @Test fun musicSurvivesHomeAndTaskRemovalWithSessionControlsAndNotificationReentry() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as PlayerApplication).container
        val preferences = container.playbackStore.readPreferences()
        val oldQueue = container.playbackStore.readQueue()
        val oldLastPlayback = container.playbackStore.readLastPlayback()
        val oldRecent = LocalMediaRepository(context).readRecent()
        val root = File(context.cacheDir, "background-music-${System.nanoTime()}").apply { mkdirs() }
        var scenario: ActivityScenario<MainActivity>? = null
        val failureMessages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val messages = launch(kotlinx.coroutines.Dispatchers.Main) { container.player.messages.collect { failureMessages += it } }
        try {
            fun fixture(name: String): MediaItem {
                val file = File(root, name).canonicalFile
                instrumentation.context.assets.open("music-tags-test.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
                return MediaItem(android.net.Uri.fromFile(file).toString(), name, "audio/mpeg", file.length())
            }
            val tracks = listOf(fixture("first.mp3"), fixture("second.mp3"))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            await("player ready") { activity.player != null && !container.player.library.value.isLoading }
            instrumentation.runOnMainSync { container.player.setAutoNext(false); container.player.setSpeed(1.0); container.player.playList(tracks) }
            val notifications = context.getSystemService(NotificationManager::class.java)
            fun notification() = notifications.activeNotifications.firstOrNull { it.id == MusicPlaybackService.NOTIFICATION_ID }?.notification
            await("music notification with cover") {
                container.player.playback.value.status == PlaybackStatus.PLAYING && notification()?.getLargeIcon() != null
            }
            assertEquals("月下航行（测试）", notification()!!.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(notification()!!.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("测试歌手"))
            @Suppress("DEPRECATION")
            val token = requireNotNull(notification()!!.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION))
            val controller = MediaController(context, token)
            await("session metadata and duration") {
                controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == "月下航行（测试）" &&
                    (controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0) >= 44000
            }
            assertNotNull(controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
            instrumentation.uiAutomation.executeShellCommand("input keyevent 3").close()
            await("activity background") { !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
            val position = container.player.playback.value.positionMs
            await("music advances in background") { container.player.playback.value.positionMs > position + 600 }
            assertEquals(PlaybackStatus.PLAYING, container.player.playback.value.status)
            instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
            scenario.close(); scenario = null
            await("music survives task removal") { container.player.playback.value.status == PlaybackStatus.PLAYING && notification() != null }
            controller.transportControls.pause()
            await("notification pause") { container.player.playback.value.status == PlaybackStatus.PAUSED && controller.playbackState?.state == PlaybackState.STATE_PAUSED }
            controller.transportControls.seekTo(10000)
            await("notification seek") { container.player.playback.value.positionMs in 9800..10500 }
            controller.transportControls.play()
            await("notification resume") { container.player.playback.value.status == PlaybackStatus.PLAYING }
            controller.transportControls.skipToNext()
            try {
                await("next while background") { container.player.queue.value.index == 1 && container.player.playback.value.media?.uri == tracks[1].uri && container.player.playback.value.status == PlaybackStatus.PLAYING }
            } catch (error: AssertionError) {
                throw AssertionError("next background: index=${container.player.queue.value.index}, status=${container.player.playback.value.status}, messages=$failureMessages", error)
            }
            controller.transportControls.skipToPrevious()
            await("previous while background") { container.player.queue.value.index == 0 && container.player.playback.value.media?.uri == tracks[0].uri && container.player.playback.value.status == PlaybackStatus.PLAYING }
            // Launch exactly the same target/action used by the notification content PendingIntent.
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(MusicPlaybackService.ACTION_OPEN))
            scenario.onActivity { activity = it }
            await("notification reopens same playback") { activity.player === container.player && container.player.audioPlayerOpen.value }
            assertEquals(tracks[0].uri, container.player.playback.value.media!!.uri)
            controller.transportControls.stop()
            await("stop removes notification and music") { container.player.playback.value.status == PlaybackStatus.IDLE && notification() == null }

            val videoFile = File(root, "video.mp4")
            instrumentation.context.assets.open("feature-test-video.mp4").use { input -> videoFile.outputStream().use { input.copyTo(it) } }
            val video = MediaItem(android.net.Uri.fromFile(videoFile).toString(), "video.mp4", "video/mp4", videoFile.length())
            instrumentation.runOnMainSync { container.player.setAutoPip(false); container.player.setBackgroundVideo(false); container.player.playList(listOf(video)) }
            await("video starts") { container.player.playback.value.status == PlaybackStatus.PLAYING }
            instrumentation.uiAutomation.executeShellCommand("input keyevent 3").close()
            await("video pauses on background") { container.player.playback.value.status == PlaybackStatus.PAUSED && container.player.playback.value.media != null && notification() == null }
        } finally {
            messages.cancel()
            instrumentation.runOnMainSync { container.player.stopPlayback() }
            scenario?.close()
            SystemClock.sleep(400)
            container.playbackStore.writePreferences(preferences)
            container.playbackStore.writeQueue(oldQueue)
            container.playbackStore.writeLastPlayback(oldLastPlayback)
            LocalMediaRepository(context).writeRecent(oldRecent)
            root.listFiles().orEmpty().forEach { container.playbackStore.writeBookmark(android.net.Uri.fromFile(it).toString(), 0, 0) }
            root.deleteRecursively()
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; SystemClock.sleep(100) }
        assertTrue(label, condition())
    }
}
