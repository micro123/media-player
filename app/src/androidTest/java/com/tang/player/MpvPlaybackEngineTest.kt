package com.tang.player

import android.content.ContentValues
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.tang.player.core.MediaItem
import com.tang.player.core.MpvPlaybackEngine
import com.tang.player.core.PlaybackState
import com.tang.player.core.PlaybackStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs the actual packaged native libraries, using a self-owned content URI. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class MpvPlaybackEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var activity: ActivityScenario<MainActivity>
    private lateinit var engine: MpvPlaybackEngine
    private lateinit var uri: Uri
    private lateinit var media: MediaItem
    private val localFiles = mutableListOf<File>()

    @Before
    fun prepare() {
        activity = ActivityScenario.launch(MainActivity::class.java)
        instrumentation.runOnMainSync { engine = MpvPlaybackEngine(context) }
        val name = "localplayer-native-test-${System.nanoTime()}.wav"
        uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/LocalPlayerTests")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Cannot create test media")
        context.contentResolver.openOutputStream(uri)!!.use { it.write(silentWave(8)) }
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        media = MediaItem(uri.toString(), name, "audio/wav", null)
    }

    @After
    fun cleanUp() {
        if (::engine.isInitialized) instrumentation.runOnMainSync { engine.release(); engine.release() }
        if (::uri.isInitialized) context.contentResolver.delete(uri, null, null)
        localFiles.forEach { it.delete() }
        if (::activity.isInitialized) activity.close()
    }

    @Test
    fun contentUriPlayPauseSeekEndAndReplay() {
        onMain { engine.load(media) }
        await("native playback and duration") {
            it.status == PlaybackStatus.PLAYING && it.seekable && it.durationMs in 7900..8100 && it.positionMs > 250
        }
        onMain { engine.pause() }
        val paused = await("pause") { it.status == PlaybackStatus.PAUSED }
        Thread.sleep(650)
        assertTrue("Position advanced while paused", kotlin.math.abs(engine.state.value.positionMs - paused.positionMs) < 200)
        onMain { engine.seekTo(5000) }
        await("seek while paused") { it.status == PlaybackStatus.PAUSED && it.positionMs in 4800..5200 }
        onMain { engine.play() }
        await("resume") { it.status == PlaybackStatus.PLAYING }
        onMain { engine.seekTo(7750) }
        await("end of file") { it.status == PlaybackStatus.ENDED }
        onMain { engine.play() }
        await("replay") { it.status == PlaybackStatus.PLAYING && it.positionMs in 100..2000 }
    }

    @Test
    fun invalidInputReportsErrorAndNextFileRecovers() {
        val broken = File(context.cacheDir, "mpv-invalid-${System.nanoTime()}.mp4").apply {
            writeText("This is deliberately not a media file.")
        }
        localFiles += broken
        onMain { engine.load(MediaItem(Uri.fromFile(broken).toString(), broken.name, "video/mp4", broken.length())) }
        assertFalse(await("unsupported input error") { it.status == PlaybackStatus.ERROR }.errorMessage.isNullOrBlank())
        onMain { engine.load(MediaItem("file:///nonexistent/localplayer-test.wav", "missing.wav", "audio/wav", null)) }
        await("missing file error") { it.status == PlaybackStatus.ERROR && it.media?.displayName == "missing.wav" }
        onMain { engine.load(media) }
        await("recovery") { it.status == PlaybackStatus.PLAYING && it.positionMs > 100 }
    }

    @Test
    fun replacementStopAndReleaseCloseDescriptors() {
        val first = File(context.cacheDir, "mpv-fd-test-${System.nanoTime()}.wav").apply { writeBytes(silentWave(8)) }
        localFiles += first
        val item = MediaItem(Uri.fromFile(first).toString(), first.name, "audio/wav", first.length())
        repeat(3) {
            onMain { engine.load(item) }
            await("file replacement $it") { state -> state.status == PlaybackStatus.PLAYING && state.positionMs > 100 }
            assertEquals("Replacement leaked a descriptor", 1, openDescriptors(first))
        }
        onMain { repeat(10) { engine.load(item) }; engine.load(media) }
        await("last rapid replacement wins") { it.status == PlaybackStatus.PLAYING && it.media?.uri == uri.toString() }
        waitUntil("old descriptor closed") { openDescriptors(first) == 0 }
        onMain { engine.load(item) }
        await("file loaded again") { it.status == PlaybackStatus.PLAYING && it.media?.uri == item.uri }
        onMain { engine.stop() }
        assertEquals(PlaybackStatus.IDLE, engine.state.value.status)
        waitUntil("descriptor closed on stop") { openDescriptors(first) == 0 }
        onMain { engine.load(item) }
        await("loaded before release") { it.status == PlaybackStatus.PLAYING }
        onMain { engine.release(); engine.release(); engine.play(); engine.load(media) }
        waitUntil("descriptor closed on release") { openDescriptors(first) == 0 }
        assertEquals(PlaybackStatus.IDLE, engine.state.value.status)
    }

    @Test
    fun initialPositionAndNativePlaybackSpeed() {
        onMain { engine.load(media, 3000) }
        await("initial start position") { it.status == PlaybackStatus.PLAYING && it.positionMs in 2900..4000 }
        onMain { engine.pause(); engine.seekTo(0); engine.setSpeed(0.5) }
        await("speed and reset") { it.status == PlaybackStatus.PAUSED && it.positionMs < 200 && it.speed == 0.5 }
        onMain { engine.play() }
        await("slow playback") { it.status == PlaybackStatus.PLAYING && it.positionMs > 100 }
        val slowStart = engine.state.value.positionMs
        Thread.sleep(900)
        val slowAdvance = engine.state.value.positionMs - slowStart
        assertTrue("0.5x did not affect native clock: $slowAdvance", slowAdvance in 250..750)
        onMain { engine.pause(); engine.seekTo(0); engine.setSpeed(5.0) }
        await("fast reset") { it.status == PlaybackStatus.PAUSED && it.positionMs < 200 && it.speed == 5.0 }
        onMain { engine.play() }
        await("fast playback") { it.status == PlaybackStatus.PLAYING && it.positionMs > 200 }
        val fastStart = engine.state.value.positionMs
        Thread.sleep(600)
        val fastAdvance = engine.state.value.positionMs - fastStart
        assertTrue("5x did not affect native clock: $fastAdvance", fastAdvance in 1800..4500)
    }

    @Test
    fun audioFocusLossPausesPlayback() {
        onMain { engine.load(media) }
        await("playing before focus loss") { it.status == PlaybackStatus.PLAYING }
        val manager = context.getSystemService(AudioManager::class.java)
        val other = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }.build()
        try {
            onMain { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(other)) }
            await("audio focus loss pause") { it.status == PlaybackStatus.PAUSED }
        } finally {
            onMain { manager.abandonAudioFocusRequest(other) }
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(label: String, condition: (PlaybackState) -> Boolean): PlaybackState {
        waitUntil("$label; last=${engine.state.value}") { condition(engine.state.value) }
        return engine.state.value
    }

    private fun waitUntil(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 12_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Timed out: $label; state=${engine.state.value}")
            Thread.sleep(40)
        }
    }

    private fun openDescriptors(file: File): Int = File("/proc/self/fd").listFiles().orEmpty().count {
        try { Os.readlink(it.path) == file.canonicalPath } catch (_: Exception) { false }
    }

    private fun silentWave(seconds: Int): ByteArray {
        val dataSize = seconds * 16_000 * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(dataSize)
        }.array()
    }
}
