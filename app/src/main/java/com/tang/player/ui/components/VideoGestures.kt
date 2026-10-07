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
    onBoost: (Boolean) -> Unit, onHint: (String?) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val activity = LocalActivity.current ?: return
    val audio = remember(activity) { activity.getSystemService(AudioManager::class.java) }
    val currentState by rememberUpdatedState(state)
    val originalBrightness = remember(activity) { activity.window.attributes.screenBrightness }
    DisposableEffect(activity) {
        onDispose {
            onBoost(false)
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = originalBrightness }
        }
    }
    Box(modifier
        .pointerInput(state.media?.uri, enabled) {
            detectTapGestures(
                onPress = {
                    try { tryAwaitRelease() } finally { onBoost(false) }
                },
                onTap = { onControls() },
                onDoubleTap = { if (enabled) onToggle() else onControls() },
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
            val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            detectDragGestures(
                onDragStart = { start ->
                    onBoost(false)
                    axis = 0; dx = 0f; dy = 0f; startX = start.x
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
                            val span = currentState.durationMs.coerceAtMost(180_000L)
                            targetPosition = (startPosition + dx / size.width.coerceAtLeast(1) * span)
                                .toLong().coerceIn(0, currentState.durationMs)
                            onHint("跳转 ${formatTime(targetPosition)} / ${formatTime(currentState.durationMs)}")
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
                onDragEnd = { if (axis == 1) onSeek(targetPosition); onHint(null); onBoost(false) },
                onDragCancel = { onHint(null); onBoost(false) },
            )
        })
}
