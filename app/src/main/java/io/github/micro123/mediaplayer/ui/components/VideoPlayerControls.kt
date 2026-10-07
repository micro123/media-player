package io.github.micro123.mediaplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.micro123.mediaplayer.core.PlaybackState
import io.github.micro123.mediaplayer.core.PlaybackStatus
import io.github.micro123.mediaplayer.data.VideoAspect
import io.github.micro123.mediaplayer.ui.PlaylistState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SecondaryVideoText = Color.White.copy(alpha = 0.65f)

@Composable
fun VideoIconButton(icon: PlayerIcon, description: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, size: Int = 26) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.size(48.dp).semantics { contentDescription = description },
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.27f))) {
        PlayerSymbol(icon, Modifier.size(size.dp))
    }
}

@Composable
private fun CircularSeekButton(seconds: Int, onClick: () -> Unit, description: String,
    enabled: Boolean, forward: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
        val color = if (enabled) Color.White else Color.White.copy(alpha = 0.27f)
        Box(Modifier.size(30.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                scale(size.width / 32, size.height / 32, pivot = Offset.Zero) {
                    scale(if (forward) 1f else -1f, 1f, pivot = Offset(16f, 16f)) {
                        drawArc(color, -72f, 300f, false, Offset(3f, 5f), Size(26f, 26f), style = Stroke(2.2f, cap = StrokeCap.Round))
                        drawPath(Path().apply { moveTo(21f, 0f); lineTo(28f, 6f); lineTo(20f, 9f); close() }, color)
                    }
                }
            }
            Text(if (seconds < 1000) seconds.toString() else ">>", color = color, fontWeight = FontWeight.Bold,
                fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun VideoTopBar(title: String, queue: PlaylistState, compact: Boolean, skipSeconds: Int,
    canSeek: Boolean, onBack: () -> Unit, onSkip: () -> Unit, onSettings: () -> Unit,
    onPrevious: () -> Unit, onSeek: (Long) -> Unit, modifier: Modifier = Modifier, onMenuVisibility: (Boolean) -> Unit = {},
    onClip: () -> Unit = {}, recording: Boolean = false) {
    var showMore by remember { mutableStateOf(false) }
    val closeMenu = { showMore = false; onMenuVisibility(false) }
    var time by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) { time = formatter.format(Date()); delay(15_000) }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        VideoIconButton(PlayerIcon.BACK, "返回", onBack)
        Column(Modifier.weight(1f).padding(start = 6.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color.White, fontSize = if (compact) 16.sp else 18.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val source = if (queue.current?.sourceKind == io.github.micro123.mediaplayer.core.MediaSourceKind.HTTP) "网络视频" else "本地视频"
            Text(if (queue.index >= 0) "$source  ·  ${queue.index + 1} / ${queue.items.size}" else source,
                color = SecondaryVideoText, fontSize = 12.sp, maxLines = 1)
        }
        if (!compact) Text(time, color = SecondaryVideoText, fontSize = 14.sp, modifier = Modifier.padding(end = 14.dp))
        CircularSeekButton(skipSeconds, onSkip, "跳过 $skipSeconds 秒", canSeek)
        key(recording) {
            VideoIconButton(if (recording) PlayerIcon.STOP else PlayerIcon.RECORD, if (recording) "结束录制" else "开始录制", onClip, enabled = canSeek)
        }
        VideoIconButton(PlayerIcon.SETTINGS, "播放设置", onSettings)
        Box {
            VideoIconButton(PlayerIcon.MORE, "更多播放操作", { showMore = true; onMenuVisibility(true) })
            DropdownMenu(expanded = showMore, onDismissRequest = closeMenu) {
                DropdownMenuItem(text = { Text("从头播放") }, enabled = canSeek,
                    onClick = { closeMenu(); onSeek(0) })
                DropdownMenuItem(text = { Text("上一项") }, enabled = queue.hasPrevious,
                    onClick = { closeMenu(); onPrevious() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoBottomBar(state: PlaybackState, queue: PlaylistState, aspect: VideoAspect, baseSpeed: Double,
    portrait: Boolean, wide: Boolean, resumePosition: Long, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit, onAspect: () -> Unit,
    onSpeed: () -> Unit, onRotate: () -> Unit, onResume: () -> Unit, onInteract: () -> Unit,
    modifier: Modifier = Modifier, seekPreview: io.github.micro123.mediaplayer.ui.VideoSeekPreview? = null,
    thumbnail: android.graphics.Bitmap? = null, onSeekBegin: () -> Unit = {},
    onSeekUpdate: (Long, Boolean) -> Unit = { _, _ -> }, onSeekFinish: (Boolean) -> Unit = {}, cancelBoundaryY: Float = 0f) {
    val duration = state.durationMs.coerceAtLeast(0)
    val fraction = if (duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val position = seekPreview?.targetPositionMs ?: state.positionMs
    val remaining = ((duration - position).coerceAtLeast(0) / state.speed.coerceAtLeast(0.1)).toLong()
    val canSeek = state.canControl && state.seekable && duration > 0
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${formatTime(position)} / ${formatTime(duration)}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("  (−${formatTime(remaining)})", color = SecondaryVideoText, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (resumePosition > 0 && wide) TextButton(onClick = { onResume(); onInteract() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                Text("上次 ${formatTime(resumePosition)}", fontSize = 12.sp)
            }
        }
        Box(Modifier.fillMaxWidth()) {
            VideoSeekBar(state.media?.uri, if (seekPreview != null && duration > 0) seekPreview.targetPositionMs.toFloat() / duration else fraction,
                duration, canSeek, accent, cancelBoundaryY, onSeekBegin, onSeekUpdate, onSeekFinish, onSeek, onInteract)
            if (seekPreview != null) Box(Modifier.matchParentSize().wrapContentHeight(Alignment.Bottom, unbounded = true).offset(y = (-32).dp)) {
                SeekPreviewCard(seekPreview, thumbnail, duration)
            }
        }
        val transport: @Composable RowScope.() -> Unit = {
            VideoIconButton(if (state.status == PlaybackStatus.PLAYING) PlayerIcon.PAUSE else if (state.status == PlaybackStatus.ENDED) PlayerIcon.REPLAY else PlayerIcon.PLAY,
                if (state.status == PlaybackStatus.PLAYING) "暂停" else if (state.status == PlaybackStatus.ENDED) "重播" else "播放",
                { onToggle(); onInteract() }, enabled = state.canControl, size = 32)
            if (wide) VideoIconButton(PlayerIcon.PREVIOUS, "上一项", { onPrevious(); onInteract() }, enabled = queue.hasPrevious)
            VideoIconButton(PlayerIcon.NEXT, "下一项", { onNext(); onInteract() }, enabled = queue.hasNext)
            CircularSeekButton(10, { onSeek(state.positionMs - 10_000); onInteract() }, "后退 10 秒", canSeek, forward = false)
            CircularSeekButton(10, { onSeek(state.positionMs + 10_000); onInteract() }, "快进 10 秒", canSeek)
        }
        val options: @Composable RowScope.() -> Unit = {
            TextButton(onClick = { onQueue(); onInteract() }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                PlayerSymbol(PlayerIcon.PLAYLIST, Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text("列表", fontSize = 14.sp)
            }
            VideoTextButton(if (aspect == VideoAspect.ORIGINAL) "原始" else aspect.label, "视频长宽比", { onAspect(); onInteract() })
            VideoTextButton(String.format(Locale.ROOT, "%.2fx", baseSpeed), "播放倍速", { onSpeed(); onInteract() })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            transport()
            Spacer(Modifier.weight(1f))
            if (wide) options()
            VideoIconButton(PlayerIcon.ROTATE, if (portrait) "切换为横屏" else "切换为竖屏", { onRotate(); onInteract() })
        }
        if (!wide) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            options()
        }
    }
}

@Composable
private fun VideoTextButton(label: String, description: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun VideoSideActions(locked: Boolean, canPip: Boolean, onLock: () -> Unit, onPip: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!locked) Surface(color = Color.Black.copy(alpha = 0.2f), shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.26f))) {
            VideoIconButton(PlayerIcon.PIP, "小窗播放", onPip, enabled = canPip)
        }
        Surface(color = Color.Black.copy(alpha = 0.2f), shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = if (locked) 0.6f else 0.26f))) {
            VideoIconButton(if (locked) PlayerIcon.LOCK else PlayerIcon.UNLOCK, if (locked) "解锁控制" else "锁定控制", onLock)
        }
    }
}
