package io.github.micro123.mediaplayer

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SystemPlaybackState
import android.os.*
import androidx.core.content.ContextCompat
import io.github.micro123.mediaplayer.core.PlaybackState
import io.github.micro123.mediaplayer.core.PlaybackStatus
import io.github.micro123.mediaplayer.data.AudioMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** Application-owned session for music and optional video background playback. */
class MusicPlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player get() = (application as PlayerApplication).container.player
    private lateinit var session: MediaSession
    private lateinit var notifications: NotificationManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private var lastNotification: NotificationKey? = null
    private var hasMedia = false
    private var shuttingDown = false
    private val handler = Handler(Looper.getMainLooper())
    private val abandonPendingStart = Runnable { if (!hasMedia) shutdown(false) }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player.pause() }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "媒体播放", NotificationManager.IMPORTANCE_LOW).apply {
            description = "后台音视频、锁屏信息与播放控制"; setShowBadge(false)
        })
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:music")
            .apply { setReferenceCounted(false) }
        session = MediaSession(this, "MediaPlayerMusic").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setSessionActivity(openPlayer())
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { player.play() }
                override fun onPause() { player.pause() }
                override fun onSkipToNext() { player.next() }
                override fun onSkipToPrevious() { player.previous() }
                override fun onSeekTo(pos: Long) { player.seekTo(pos) }
                override fun onStop() { shutdown(true) }
            })
            isActive = true
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        val initial = buildNotification(null, AudioMetadata())
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, initial)
        handler.postDelayed(abandonPendingStart, 15_000)
        scope.launch {
            combine(player.playback, player.audioMetadata, player.queue, player.preferences) { state, tags, _, preferences ->
                Triple(state, tags, preferences.backgroundVideo)
            }.collect { (state, tags, backgroundVideo) ->
                if (shuttingDown) return@collect
                val media = state.media
                if (media != null && (!media.isVideo || backgroundVideo)) {
                    hasMedia = true
                    handler.removeCallbacks(abandonPendingStart)
                    update(state, tags.takeIf { it.uri == media.uri } ?: AudioMetadata(uri = media.uri, title = media.displayName))
                } else if (hasMedia || media?.isVideo == true && player.queue.value.current?.isVideo != false) shutdown(false)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> player.play()
            ACTION_PAUSE -> player.pause()
            ACTION_PREVIOUS -> player.previous()
            ACTION_NEXT -> player.next()
            ACTION_STOP -> shutdown(true)
        }
        return START_NOT_STICKY
    }

    // MediaStyle notifications with a MediaSession are exempt from POST_NOTIFICATIONS.
    @android.annotation.SuppressLint("NotificationPermission", "MissingPermission")
    private fun update(state: PlaybackState, tags: AudioMetadata) {
        val media = requireNotNull(state.media)
        val active = state.status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.BUFFERING)
        if (active && !wakeLock.isHeld) wakeLock.acquire(6 * 60 * 60 * 1000L)
        else if (!active && wakeLock.isHeld) wakeLock.release()
        val queue = player.queue.value
        val actions = SystemPlaybackState.ACTION_PLAY or SystemPlaybackState.ACTION_PAUSE or SystemPlaybackState.ACTION_PLAY_PAUSE or SystemPlaybackState.ACTION_STOP or
            (if (state.seekable) SystemPlaybackState.ACTION_SEEK_TO else 0L) or
            (if (queue.hasPrevious) SystemPlaybackState.ACTION_SKIP_TO_PREVIOUS else 0L) or (if (queue.hasNext) SystemPlaybackState.ACTION_SKIP_TO_NEXT else 0L)
        val status = when (state.status) {
            PlaybackStatus.PLAYING -> SystemPlaybackState.STATE_PLAYING
            PlaybackStatus.BUFFERING -> SystemPlaybackState.STATE_BUFFERING
            PlaybackStatus.PAUSED -> SystemPlaybackState.STATE_PAUSED
            PlaybackStatus.ENDED -> SystemPlaybackState.STATE_STOPPED
            PlaybackStatus.ERROR -> SystemPlaybackState.STATE_ERROR
            else -> SystemPlaybackState.STATE_NONE
        }
        session.setPlaybackState(SystemPlaybackState.Builder().setActions(actions)
            .setState(status, state.positionMs, if (active) state.speed.toFloat() else 0f, SystemClock.elapsedRealtime()).build())
        val key = NotificationKey(media.uri, tags.title, tags.artist, tags.album, tags.cover, state.status, queue.hasPrevious, queue.hasNext, state.durationMs)
        if (key == lastNotification) return
        lastNotification = key
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, media.uri)
            .putString(MediaMetadata.METADATA_KEY_TITLE, tags.title.ifBlank { media.displayName })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, tags.artist).putString(MediaMetadata.METADATA_KEY_ALBUM, tags.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs).apply {
                tags.cover?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it); putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it) }
            }.build())
        notifications.notify(NOTIFICATION_ID, buildNotification(state, tags))
    }

    private fun buildNotification(state: PlaybackState?, tags: AudioMetadata): Notification {
        val playing = state?.status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.BUFFERING)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_music_notification)
            .setContentTitle(tags.title.ifBlank { state?.media?.displayName ?: player.queue.value.current?.displayName ?: "正在载入媒体" })
            .setContentText(listOf(tags.artist, tags.album).filter { it.isNotBlank() }.joinToString(" · "))
            .setContentIntent(openPlayer()).setDeleteIntent(command(ACTION_STOP)).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT).setOngoing(playing)
            .setLargeIcon(tags.cover)
        val compact = mutableListOf<Int>()
        if (player.queue.value.hasPrevious) {
            compact += compact.size
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "上一首", command(ACTION_PREVIOUS)).build())
        }
        compact += compact.size
        builder.addAction(Notification.Action.Builder(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) "暂停" else "播放", command(if (playing) ACTION_PAUSE else ACTION_PLAY)).build())
        if (player.queue.value.hasNext) {
            compact += compact.size
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "下一首", command(ACTION_NEXT)).build())
        }
        builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止", command(ACTION_STOP)).build())
        return builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(*compact.toIntArray())).build()
    }

    private fun openPlayer(): PendingIntent = PendingIntent.getActivity(this, 1,
        Intent(this, MainActivity::class.java).setAction(ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun command(action: String): PendingIntent = PendingIntent.getService(this, action.hashCode(), Intent(this, MusicPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun shutdown(stopMusic: Boolean) {
        if (shuttingDown) return
        shuttingDown = true
        if (stopMusic) player.stopPlayback()
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTaskRemoved(rootIntent: Intent?) { /* Background playback outlives the activity task. */ }
    override fun onDestroy() {
        handler.removeCallbacks(abandonPendingStart)
        scope.cancel()
        unregisterReceiver(noisy)
        if (wakeLock.isHeld) wakeLock.release()
        session.release()
        notifications.cancel(NOTIFICATION_ID)
        if (!shuttingDown && player.playback.value.media != null) player.stopPlayback()
        super.onDestroy()
    }

    private data class NotificationKey(val uri: String, val title: String, val artist: String, val album: String, val cover: android.graphics.Bitmap?,
        val status: PlaybackStatus, val previous: Boolean, val next: Boolean, val durationMs: Long)

    companion object {
        const val NOTIFICATION_ID = 1401
        const val CHANNEL = "music-playback"
        const val ACTION_OPEN = "io.github.micro123.mediaplayer.OPEN_MUSIC"
        private const val ACTION_START = "io.github.micro123.mediaplayer.MUSIC_START"
        const val ACTION_PLAY = "io.github.micro123.mediaplayer.MUSIC_PLAY"
        const val ACTION_PAUSE = "io.github.micro123.mediaplayer.MUSIC_PAUSE"
        const val ACTION_NEXT = "io.github.micro123.mediaplayer.MUSIC_NEXT"
        const val ACTION_PREVIOUS = "io.github.micro123.mediaplayer.MUSIC_PREVIOUS"
        const val ACTION_STOP = "io.github.micro123.mediaplayer.MUSIC_STOP"
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, MusicPlaybackService::class.java).setAction(ACTION_START)) }
    }
}
