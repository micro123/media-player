package io.github.micro123.mediaplayer.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Owns one native session per file, keeping its descriptor alive until mpv is destroyed. */
class MpvPlaybackEngine(context: Context,
    private val sourceResolver: PlaybackSourceResolver = AndroidPlaybackSourceResolver(context),
) : PlaybackEngine {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "localplayer-mpv").apply { isDaemon = true }
    }
    private val generation = AtomicLong()
    private val released = AtomicBoolean()
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state = mutableState.asStateFlow()

    // These fields belong exclusively to worker. A generation rejects callbacks from old files.
    private var session: Session? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    @Volatile private var desiredSpeed = 1.0
    @Volatile private var desiredAspect = -1.0

    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build())
        .setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) pause()
        }, main)
        .build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    init {
        ContextCompat.registerReceiver(appContext, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun load(media: MediaItem, startPositionMs: Long) {
        if (released.get()) return
        val id = generation.incrementAndGet()
        val startPaused = !requestFocus()
        mutableState.value = PlaybackState(media = media, status = PlaybackStatus.BUFFERING, speed = desiredSpeed)
        enqueue {
            if (id != generation.get()) return@enqueue
            closeSession()
            var opened: OpenedMediaSource? = null
            var mpv: MPVLib? = null
            try {
                opened = sourceResolver.open(media)
                if (id != generation.get() || released.get()) {
                    opened.close()
                    return@enqueue
                }
                mpv = MPVLib.create(appContext) ?: error("无法创建播放内核。")
                val next = Session(id, media, opened, mpv)
                session = next
                next.paused = startPaused
                configure(next, startPositionMs)
                mpv.addObserver(next)
                mpv.addLogObserver(next)
                mpv.init()
                observeProperties(mpv)
                bindSurface(next)
                // fd:// borrows the descriptor. Do not close it at EOF or while replacing a file.
                if (!media.isVideo || next.attachedSurface != null) startFile(next)
                else main.postDelayed({ enqueue {
                    // Headless consumers can still probe video files; the UI normally attaches first.
                    if (session === next && next.id == generation.get()) startFile(next)
                } }, 500)
                Log.i(TAG, "Loaded ${media.sourceKind} media using ${mpv.getPropertyString("mpv-version")}")
            } catch (error: Exception) {
                failLoad(id, media, opened, mpv, error)
            } catch (error: LinkageError) {
                failLoad(id, media, opened, mpv, error)
            }
        }
    }

    override fun setSpeed(speed: Double) {
        if (released.get() || !speed.isFinite()) return
        val previous = desiredSpeed
        val target = speed.coerceIn(0.1, 5.0)
        desiredSpeed = target
        mutableState.value = mutableState.value.copy(speed = desiredSpeed)
        command { current ->
            current.mpv.setPropertyDouble("speed", target)
            // Flush queued audio with the old tempo when crossing into/out of fast playback.
            if (current.loaded && current.seekable && !current.ended && previous != target && (previous >= 3 || target >= 3)) {
                current.mpv.command(arrayOf("seek", "0", "relative+exact"))
            }
        }
    }

    override fun setVideoAspectRatio(ratio: Double) {
        if (released.get() || !ratio.isFinite() || (ratio <= 0 && ratio != -1.0)) return
        desiredAspect = ratio
        command { it.mpv.setPropertyDouble("video-aspect-override", desiredAspect) }
    }

    override fun attachSurface(surface: Surface?) {
        if (released.get()) return
        enqueue {
            this.surface = surface
            session?.let(::bindSurface)
        }
    }

    override fun updateSurfaceSize(width: Int, height: Int) {
        if (released.get() || width <= 0 || height <= 0) return
        enqueue {
            val changed = surfaceWidth != width || surfaceHeight != height
            surfaceWidth = width
            surfaceHeight = height
            session?.takeIf { it.attachedSurface != null }?.let { current ->
                current.mpv.setPropertyString("android-surface-size", "${width}x$height")
                // Refresh the paused frame after rotation instead of leaving an old-sized buffer visible.
                if (changed && current.loaded && current.paused && current.seekable && !current.ended) {
                    current.mpv.command(arrayOf("seek", "0", "relative+exact"))
                }
            }
        }
    }

    override fun play() {
        if (released.get() || !state.value.canControl || !requestFocus()) return
        command { current ->
            if (current.ended) {
                current.ended = false
                current.mpv.command(arrayOf("seek", "0", "absolute+exact"))
            }
            current.mpv.setPropertyBoolean("pause", false)
        }
    }

    override fun pause() {
        if (released.get()) return
        abandonFocus()
        command { it.mpv.setPropertyBoolean("pause", true) }
    }

    override fun seekTo(positionMs: Long) {
        if (released.get() || !state.value.seekable || !state.value.canControl) return
        command { current ->
            val target = positionMs.coerceIn(0, current.durationMs)
            current.mpv.command(arrayOf("seek", (target / 1000.0).toString(), "absolute+exact"))
        }
    }

    override fun stop() {
        if (released.get()) return
        val id = generation.incrementAndGet()
        abandonFocus()
        mutableState.value = PlaybackState()
        enqueue {
            if (id == generation.get()) closeSession()
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        generation.incrementAndGet()
        abandonFocus()
        appContext.unregisterReceiver(noisyReceiver)
        mutableState.value = PlaybackState()
        // Queued commands/callbacks see released and are discarded; cleanup is last in this queue.
        worker.execute {
            closeSession()
            surface = null
        }
        worker.shutdown()
    }

    private fun configure(current: Session, startPositionMs: Long) {
        val options = mapOf(
            "config" to "no",
            "terminal" to "no",
            "input-default-bindings" to "no",
            "input-vo-keyboard" to "no",
            "osd-level" to "0",
            "idle" to "yes",
            "keep-open" to "yes",
            "audio-display" to "no",
            "sub-auto" to "no",
            "gpu-context" to "android",
            "opengl-es" to "yes",
            "hwdec" to "mediacodec,mediacodec-copy,auto",
            "video-sync" to "audio",
            "framedrop" to "vo",
            "audio-pitch-correction" to "yes",
            "audio-buffer" to "0.1",
            "ao" to "audiotrack",
            "vo" to "null",
            "pause" to if (current.paused) "yes" else "no",
            "speed" to desiredSpeed.toString(),
            "video-aspect-override" to desiredAspect.toString(),
            "start" to (startPositionMs.coerceAtLeast(0) / 1000.0).toString(),
        )
        options.forEach { (name, value) ->
            check(current.mpv.setOptionString(name, value) >= 0) { "播放内核不支持配置：$name" }
        }
    }

    private fun observeProperties(mpv: MPVLib) {
        for (name in listOf("time-pos", "duration", "speed", "video-params/aspect", "avsync")) {
            mpv.observeProperty(name, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        }
        mpv.observeProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        for (name in listOf("pause", "paused-for-cache", "eof-reached", "seekable")) {
            mpv.observeProperty(name, MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        }
    }

    private fun bindSurface(current: Session) {
        val target = surface?.takeIf { it.isValid && current.media.isVideo }
        if (target === current.attachedSurface) return
        if (current.attachedSurface != null) {
            current.mpv.setPropertyString("vo", "null")
            current.mpv.setPropertyString("force-window", "no")
            current.mpv.detachSurface()
            current.attachedSurface = null
        }
        if (target != null) {
            current.mpv.attachSurface(target)
            current.attachedSurface = target
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                current.mpv.setPropertyString("android-surface-size", "${surfaceWidth}x$surfaceHeight")
            }
            current.mpv.setPropertyString("force-window", "yes")
            current.mpv.setPropertyString("vo", "gpu")
            startFile(current)
        }
    }

    private fun startFile(current: Session) {
        if (current.started) return
        current.started = true
        current.mpv.command(arrayOf("loadfile", current.opened.input, "replace"))
    }

    private fun command(action: (Session) -> Unit) {
        val id = generation.get()
        enqueue {
            session?.takeIf { it.id == id }?.let { current ->
                try {
                    action(current)
                } catch (error: Exception) {
                    current.error = error.message ?: "播放操作失败。"
                    current.publish()
                }
            }
        }
    }

    private fun enqueue(action: () -> Unit) {
        if (released.get()) return
        try {
            worker.execute { if (!released.get()) action() }
        } catch (_: RejectedExecutionException) {
            // A callback can race with release() shutting down the executor.
        }
    }

    private fun closeSession() {
        val old = session ?: return
        session = null
        try {
            old.mpv.removeObserver(old)
            old.mpv.removeLogObserver(old)
            try {
                if (old.attachedSurface != null) {
                    old.mpv.setPropertyString("vo", "null")
                    old.mpv.detachSurface()
                    old.attachedSurface = null
                }
            } finally {
                try {
                    old.mpv.destroy()
                } finally {
                    old.opened.close()
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Cannot close playback session", error)
        }
    }

    private fun failLoad(id: Long, media: MediaItem, opened: OpenedMediaSource?, mpv: MPVLib?, error: Throwable) {
        Log.e(TAG, "Cannot load ${media.sourceKind} media: ${error.javaClass.simpleName}")
        if (session?.id == id) {
            closeSession()
        } else {
            try { mpv?.destroy() } catch (cleanupError: Exception) {
                Log.w(TAG, "Cannot close failed native instance", cleanupError)
            } finally { opened?.close() }
        }
        publish(id, PlaybackState(media = media, status = PlaybackStatus.ERROR,
            errorMessage = error.message ?: "无法初始化播放内核。"))
    }

    private fun publish(id: Long, value: PlaybackState) {
        main.post {
            if (!released.get() && id == generation.get()) {
                mutableState.value = value
                if (value.status == PlaybackStatus.ERROR || value.status == PlaybackStatus.ENDED) abandonFocus()
            }
        }
    }

    private fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private inner class Session(
        val id: Long,
        val media: MediaItem,
        val opened: OpenedMediaSource,
        val mpv: MPVLib,
    ) : MPVLib.EventObserver, MPVLib.LogObserver {
        var attachedSurface: Surface? = null
        var loaded = false
        var started = false
        var paused = false
        var buffering = false
        var ended = false
        var seekable = false
        var positionMs = 0L
        var durationMs = 0L
        var error: String? = null
        var lastNativeError: String? = null
        var speed = desiredSpeed
        var videoAspect = 16.0 / 9.0
        var avSyncMs: Double? = null
        var hardwareDecoder: String? = null

        private fun eventAction(action: () -> Unit) {
            enqueue {
                if (session === this && id == generation.get()) {
                    action()
                    publish()
                }
            }
        }

        fun publish() {
            val status = when {
                error != null -> PlaybackStatus.ERROR
                !loaded -> PlaybackStatus.BUFFERING
                ended -> PlaybackStatus.ENDED
                paused -> PlaybackStatus.PAUSED
                buffering -> PlaybackStatus.BUFFERING
                else -> PlaybackStatus.PLAYING
            }
            publish(id, PlaybackState(media, status, positionMs, durationMs, error, seekable, speed, videoAspect, avSyncMs, hardwareDecoder))
        }

        override fun event(eventId: Int) = eventAction {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    loaded = true
                    error = null
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    if (mpv.getPropertyBoolean("eof-reached") == true && loaded) ended = true
                    else error = if (media.sourceKind in setOf(MediaSourceKind.HTTP, MediaSourceKind.SMB, MediaSourceKind.NFS, MediaSourceKind.NAVIDROME))
                        "网络播放中断，请检查网络访问权限、地址、服务器状态及媒体格式。"
                        else lastNativeError ?: "无法播放此文件，格式可能不受支持。"
                }
            }
        }

        override fun eventProperty(property: String, value: Double) = eventAction {
            val milliseconds = if (value.isFinite()) (value.coerceAtLeast(0.0) * 1000).toLong() else 0L
            when (property) {
                "time-pos" -> positionMs = milliseconds
                "duration" -> durationMs = milliseconds
                "speed" -> if (value.isFinite()) speed = value.coerceIn(0.1, 5.0)
                "video-params/aspect" -> if (value.isFinite() && value > 0) videoAspect = value
                "avsync" -> avSyncMs = value.takeIf { it.isFinite() }?.times(1000)
            }
        }

        override fun eventProperty(property: String, value: Boolean) = eventAction {
            when (property) {
                "pause" -> paused = value
                "paused-for-cache" -> buffering = value
                "eof-reached" -> ended = value
                "seekable" -> seekable = value
            }
        }

        override fun eventProperty(property: String) = Unit
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: String) = eventAction {
            if (property == "hwdec-current") hardwareDecoder = value
        }

        override fun logMessage(prefix: String, level: Int, text: String) {
            if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) {
                // Native HTTP errors can contain authenticated stream URLs.
                if (media.sourceKind == MediaSourceKind.NAVIDROME) {
                    enqueue { if (session === this && id == generation.get()) lastNativeError = "Navidrome 音频读取失败" }
                    Log.w(TAG, "Navidrome native playback error")
                    return
                }
                enqueue {
                    if (session === this && id == generation.get()) lastNativeError = text.trim().take(300)
                }
                Log.w(TAG, "[$prefix] ${text.trim()}")
            }
        }
    }

    private companion object {
        const val TAG = "LocalPlayerMpv"
    }
}
