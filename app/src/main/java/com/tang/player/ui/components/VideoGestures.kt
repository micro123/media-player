package com.tang.player.ui.components

import android.media.AudioManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.tang.player.core.PlaybackState
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun VideoGestures(state: PlaybackState, onToggle: () -> Unit, onControls: () -> Unit, onSeek: (Long) -> Unit,
    onBoost: (Boolean) -> Unit, onHint: (String?) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    landscape: Boolean = false, onSeekBegin: () -> Unit = {}, onSeekUpdate: (Long, Boolean) -> Unit = { _, _ -> },
    onSeekFinish: (Boolean) -> Unit = {}) {
    val activity = LocalActivity.current ?: return
    val audio = remember(activity) { activity.getSystemService(AudioManager::class.java) }
    val currentState by rememberUpdatedState(state)
    val begin by rememberUpdatedState(onSeekBegin)
    val update by rememberUpdatedState(onSeekUpdate)
    val finish by rememberUpdatedState(onSeekFinish)
    val originalBrightness = remember(activity) { activity.window.attributes.screenBrightness }
    DisposableEffect(activity) {
        onDispose {
            onBoost(false)
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = originalBrightness }
        }
    }
    Box(modifier
        .pointerInput(state.media?.uri, enabled, landscape) {
            detectTapGestures(
                onPress = {
                    try { tryAwaitRelease() } finally { onBoost(false) }
                },
                onTap = { onControls() },
                onDoubleTap = { position ->
                    if (!enabled) onControls()
                    else if (landscape && (position.x < size.width / 3f || position.x > size.width * 2f / 3f)) {
                        if (currentState.canControl && currentState.seekable) {
                            val forward = position.x > size.width / 2f
                            onSeek(currentState.positionMs + if (forward) 10_000 else -10_000)
                            onHint(if (forward) "快进 10 秒" else "后退 10 秒")
                        }
                    } else onToggle()
                },
                onLongPress = { if (enabled) onBoost(true) },
            )
        }
        .pointerInput(state.media?.uri, enabled) {
            if (!enabled) return@pointerInput
            var axis = 0 // 1 seek, 2 brightness, 3 volume
            var startX = 0f
            var dx = 0f
            var dy = 0f
            var startPosition = 0L
            var targetPosition = 0L
            var startBrightness = 0.5f
            var startVolume = 0
            var seeking = false
            var cancelled = false
            val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            detectDragGestures(
                onDragStart = { start ->
                    onBoost(false)
                    axis = 0; dx = 0f; dy = 0f; startX = start.x; seeking = false; cancelled = false
                    startPosition = currentState.positionMs
                    targetPosition = startPosition
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    startBrightness = activity.window.attributes.screenBrightness.takeIf { it >= 0 }
                        ?: (Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                },
                onDrag = { change, drag ->
                    change.consume()
                    dx += drag.x; dy += drag.y
                    if (axis == 0) axis = if (abs(dx) >= abs(dy)) 1 else if (startX < size.width / 2f) 2 else 3
                    when (axis) {
                        1 -> if (currentState.seekable && currentState.canControl) {
                            if (!seeking) { begin(); seeking = true }
                            val span = currentState.durationMs.coerceAtMost(180_000L)
                            targetPosition = (startPosition + dx / size.width.coerceAtLeast(1) * span)
                                .toLong().coerceIn(0, currentState.durationMs)
                            cancelled = change.position.y < size.height * 0.18f
                            update(targetPosition, cancelled)
                        }
                        2 -> {
                            val brightness = (startBrightness - dy / (size.height.coerceAtLeast(1) * 0.65f)).coerceIn(0.02f, 1f)
                            activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
                            onHint("亮度 ${(brightness * 100).roundToInt()}%")
                        }
                        3 -> {
                            val volume = (startVolume - dy / (size.height.coerceAtLeast(1) * 0.65f) * maxVolume).roundToInt().coerceIn(0, maxVolume)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                            onHint("音量 ${(volume * 100f / maxVolume).roundToInt()}%")
                        }
                    }
                },
                onDragEnd = { if (seeking) finish(!cancelled); onHint(null); onBoost(false) },
                onDragCancel = { if (seeking) finish(false); onHint(null); onBoost(false) },
            )
        })
}
