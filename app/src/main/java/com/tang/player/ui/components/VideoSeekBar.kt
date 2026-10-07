package com.tang.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/** Keep ownership outside the bar so a finger can reach the player's cancellation strip. */
@Composable
fun VideoSeekBar(mediaUri: String?, fraction: Float, duration: Long, enabled: Boolean, accent: Color,
    cancelBoundaryY: Float, onBegin: () -> Unit, onUpdate: (Long, Boolean) -> Unit,
    onFinish: (Boolean) -> Unit, onSeek: (Long) -> Unit, onInteract: () -> Unit) {
    var top by remember { mutableFloatStateOf(0f) }
    val begin by rememberUpdatedState(onBegin)
    val update by rememberUpdatedState(onUpdate)
    val finish by rememberUpdatedState(onFinish)
    val interact by rememberUpdatedState(onInteract)
    val boundary by rememberUpdatedState(cancelBoundaryY)
    Canvas(Modifier.fillMaxWidth().height(32.dp).onGloballyPositioned { top = it.boundsInWindow().top }
        .semantics {
            contentDescription = "播放进度"
            progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
            setProgress { value ->
                if (enabled) { onSeek((value.coerceIn(0f, 1f) * duration).toLong()); onInteract() }
                enabled
            }
        }
        .pointerInput(mediaUri, duration, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                var active = true
                var cancelled = false
                begin(); interact()
                update((down.position.x / size.width.coerceAtLeast(1) * duration).toLong(), false)
                try {
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) { cancelled = true; break }
                        cancelled = top + change.position.y < boundary
                        update((change.position.x / size.width.coerceAtLeast(1) * duration).toLong(), cancelled)
                        change.consume()
                        if (!change.pressed) {
                            finish(!cancelled); active = false; interact(); break
                        }
                    } while (true)
                } finally { if (active) finish(false) }
            }
        }) {
        val inset = 7.dp.toPx()
        val end = inset + (size.width - 2 * inset) * fraction.coerceIn(0f, 1f)
        drawLine(Color.White.copy(alpha = .28f), Offset(inset, center.y), Offset(size.width - inset, center.y), 3.dp.toPx(), StrokeCap.Round)
        drawLine(if (enabled) accent else Color.Gray, Offset(inset, center.y), Offset(end, center.y), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(if (enabled) accent else Color.Gray, 7.dp.toPx(), Offset(end, center.y))
    }
}
