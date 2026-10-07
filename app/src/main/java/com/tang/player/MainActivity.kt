package com.tang.player

import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import com.tang.player.ui.PlayerViewModel
import com.tang.player.ui.PlayerApp
import com.tang.player.ui.theme.LocalPlayerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {
    private val mutablePip = MutableStateFlow(false)
    val inPip = mutablePip.asStateFlow()
    private val mutableEnteringPip = MutableStateFlow(false)
    val enteringPip = mutableEnteringPip.asStateFlow()
    var player: PlayerViewModel? = null
        private set
    private var videoActive = false
    private var playing = false
    private var aspect = 16.0 / 9.0
    private var videoBounds: Rect? = null
    private var pendingExternalMedia: ExternalMediaRequest? = null
    var openedFromExternal = false
        private set
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_PLAY) player?.togglePlayback()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedFromExternal = savedInstanceState?.getBoolean("opened_from_external") ?: false
        if (savedInstanceState == null) receiveExternalMedia(intent)
        else savedInstanceState.getString("pending_external_uri")?.let { uri ->
            receiveExternalMedia(Intent(Intent.ACTION_VIEW).setDataAndType(uri.toUri(), savedInstanceState.getString("pending_external_mime")))
        }
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_PLAY), ContextCompat.RECEIVER_NOT_EXPORTED)
        val container = (application as PlayerApplication).container
        setContent { LocalPlayerTheme { PlayerApp(container) } }
    }

    fun bindPlayer(value: PlayerViewModel?) {
        player = value
        dispatchExternalMedia()
    }

    private fun receiveExternalMedia(intent: Intent) {
        try {
            val request = ExternalMediaRequest.fromIntent(intent) ?: return
            openedFromExternal = true
            pendingExternalMedia = request
            dispatchExternalMedia()
        } catch (error: IllegalArgumentException) {
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun dispatchExternalMedia() {
        val target = player ?: return
        val request = pendingExternalMedia ?: return
        pendingExternalMedia = null
        target.openExternal(request)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("opened_from_external", openedFromExternal)
        pendingExternalMedia?.let {
            outState.putString("pending_external_uri", it.uri.toString())
            outState.putString("pending_external_mime", it.mimeType)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveExternalMedia(intent)
        // MAIN only brings the existing playback forward; it never reloads a file.
    }

    fun updatePlaybackPresentation(active: Boolean, isPlaying: Boolean, videoAspect: Double) {
        videoActive = active
        playing = isPlaying
        aspect = videoAspect.takeIf { it.isFinite() && it > 0 } ?: (16.0 / 9.0)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) setPictureInPictureParams(pipParams())
    }

    fun updateVideoBounds(bounds: Rect?) {
        if (bounds == videoBounds) return
        videoBounds = bounds
        if (videoActive && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) setPictureInPictureParams(pipParams())
    }

    private fun pipParams(): PictureInPictureParams {
        val ratio = aspect.coerceIn(0.42, 2.38)
        val action = RemoteAction(
            Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (playing) "暂停" else "播放", if (playing) "暂停" else "播放",
            PendingIntent.getBroadcast(this, 0, Intent(ACTION_PIP_PLAY).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(if (videoActive) listOf(action) else emptyList())
            .apply {
                videoBounds?.takeIf { !it.isEmpty }?.let { setSourceRectHint(it) }
                if (Build.VERSION.SDK_INT >= 31) {
                    // Leaving the player stops video; PiP is entered only by its explicit action.
                    setAutoEnterEnabled(false)
                    setSeamlessResizeEnabled(true)
                }
            }.build()
    }

    fun requestPip(): Boolean {
        if (!videoActive || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "当前设备或播放状态不支持画中画", Toast.LENGTH_SHORT).show()
            return false
        }
        player?.setSpeedBoost(false)
        mutableEnteringPip.value = true
        return try {
            enterPictureInPictureMode(pipParams()).also { if (!it) mutableEnteringPip.value = false }
        } catch (_: IllegalStateException) {
            mutableEnteringPip.value = false
            Toast.makeText(this, "无法进入画中画，请检查系统的小窗权限", Toast.LENGTH_SHORT).show()
            false
        }
    }

    override fun onPictureInPictureUiStateChanged(pipState: android.app.PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= 35 && pipState.isTransitioningToPip) {
            player?.setSpeedBoost(false)
            mutableEnteringPip.value = true
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        mutablePip.value = isInPictureInPictureMode
        mutableEnteringPip.value = false
        player?.setSpeedBoost(false)
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player?.exitVideo()
    }

    override fun onStop() {
        if (!isChangingConfigurations && !isInPictureInPictureMode && !mutableEnteringPip.value) {
            player?.onBackground()
        }
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(pipReceiver)
        super.onDestroy()
    }

    private companion object { const val ACTION_PIP_PLAY = "com.tang.player.PIP_PLAY" }
}
