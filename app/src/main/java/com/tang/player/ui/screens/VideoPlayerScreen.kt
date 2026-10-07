package com.tang.player.ui.screens

import android.content.pm.ActivityInfo
import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tang.player.MainActivity
import com.tang.player.core.PlaybackState
import com.tang.player.core.PlaybackStatus
import com.tang.player.data.VideoAspect
import com.tang.player.data.VideoOrientation
import com.tang.player.data.boostedSpeed
import com.tang.player.ui.PlaylistState
import com.tang.player.ui.components.*
import kotlinx.coroutines.delay

@Composable
fun VideoPlayerScreen(state: PlaybackState, queue: PlaylistState, aspect: VideoAspect, baseSpeed: Double,
    boosting: Boolean, orientation: VideoOrientation, inPip: Boolean, resumePosition: Long,
    onToggle: () -> Unit, onSeek: (Long) -> Unit, onSurface: (android.view.Surface?) -> Unit, onSize: (Int, Int) -> Unit,
    onBoost: (Boolean) -> Unit, onRotate: () -> Unit, onPip: () -> Unit, onSpeed: () -> Unit,
    onAspect: () -> Unit, onQueue: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onResume: () -> Unit, onRetry: () -> Unit, skipSeconds: Int, onSkip: () -> Unit,
    onBack: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier,
    onClip: () -> Unit = {}, clipStartMs: Long? = null, clipEndMs: Long? = null,
    seekPreview: com.tang.player.ui.VideoSeekPreview? = null, seekThumbnail: android.graphics.Bitmap? = null,
    onSeekBegin: () -> Unit = {}, onSeekUpdate: (Long, Boolean) -> Unit = { _, _ -> }, onSeekFinish: (Boolean) -> Unit = {}) {
    val activity = LocalActivity.current as? MainActivity
    val configuration = LocalConfiguration.current
    val keptOrientation = remember(activity, orientation) {
        if (configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }
    val displayAspect = (if (aspect == VideoAspect.ORIGINAL) state.videoAspectRatio else aspect.ratio)
        .takeIf { it.isFinite() && it > 0 } ?: (16.0 / 9.0)
    var controlsVisible by remember(state.media?.uri) { mutableStateOf(true) }
    var locked by rememberSaveable(state.media?.uri) { mutableStateOf(false) }
    var lockNotice by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var menuOpen by remember(state.media?.uri) { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val currentFinish by rememberUpdatedState(onSeekFinish)
    DisposableEffect(state.media?.uri) { onDispose { currentFinish(false) } }
    LaunchedEffect(hint) { if (hint != null) { delay(1200); hint = null } }
    val interact: () -> Unit = { controlsVisible = true; interaction++ }
    val unlock = { locked = false; lockNotice = false; interact() }
    BackHandler(enabled = locked && !inPip) { unlock() }
    BackHandler(enabled = seekPreview != null && !inPip) { onSeekFinish(false) }
    LaunchedEffect(inPip) { if (inPip) { onSeekFinish(false); locked = false; lockNotice = false } }
    LaunchedEffect(lockNotice) { if (lockNotice) { delay(1800); lockNotice = false } }
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            previousOrientation?.let { activity?.requestedOrientation = it }
            activity?.updateVideoBounds(null)
            onBoost(false)
        }
    }
    DisposableEffect(activity, orientation, inPip) {
        if (!inPip && activity != null) {
            val requested = when (orientation) {
                VideoOrientation.KEEP -> keptOrientation
                VideoOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                VideoOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            // Avoid re-locking to the PiP window's orientation when returning to fullscreen.
            if (activity.requestedOrientation != requested) activity.requestedOrientation = requested
        }
        onDispose { }
    }
    LaunchedEffect(controlsVisible, interaction, state.status, boosting, locked, menuOpen) {
        if (controlsVisible && state.status == PlaybackStatus.PLAYING && !boosting && !locked && !menuOpen) {
            delay(3500)
            controlsVisible = false
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val wide = maxWidth >= 600.dp
        val portrait = maxHeight > maxWidth
        val cancelBoundary = with(LocalDensity.current) { maxHeight.toPx() * .18f }
        val surfaceWidth = if (maxWidth / maxHeight > displayAspect.toFloat()) maxHeight * displayAspect.toFloat() else maxWidth
        val surfaceHeight = surfaceWidth / displayAspect.toFloat()
        VideoSurface(onSurface, onSize, Modifier.width(surfaceWidth).height(surfaceHeight).align(Alignment.Center)
            .onGloballyPositioned { coords ->
                val rect = coords.boundsInWindow()
                activity?.updateVideoBounds(Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()))
            })
        if (!inPip) {
            VideoGestures(state, onToggle, {
                if (locked) lockNotice = true else { controlsVisible = !controlsVisible; interaction++ }
            }, onSeek, onBoost, { hint = it }, Modifier.matchParentSize(), enabled = !locked,
                landscape = !portrait, onSeekBegin = { onSeekBegin(); interact() }, onSeekUpdate = onSeekUpdate, onSeekFinish = onSeekFinish)

            val showControls = !locked && (controlsVisible || seekPreview != null || state.status == PlaybackStatus.ERROR)
            AnimatedVisibility(showControls, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxWidth().heightIn(min = if (wide) 124.dp else 144.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.84f), Color.Transparent)))) {
                    VideoTopBar(state.media?.displayName.orEmpty(), queue, !wide, skipSeconds, state.canControl && state.seekable,
                        { onBack(); interact() }, { onSkip(); interact() }, { onSettings(); interact() },
                        onPrevious, onSeek,
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .padding(horizontal = if (wide) 16.dp else 4.dp, vertical = 12.dp),
                        onMenuVisibility = { menuOpen = it; interact() }, onClip = { onClip(); interact() }, recording = clipStartMs != null && clipEndMs == null)
                }
            }
            AnimatedVisibility(showControls, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))) {
                    VideoBottomBar(state, queue, aspect, baseSpeed, portrait, wide, resumePosition,
                        onToggle, onSeek, onPrevious, onNext, onQueue, onAspect, onSpeed, onRotate,
                        onResume, interact,
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                            .padding(start = if (wide) 22.dp else 16.dp, end = if (wide) 22.dp else 16.dp, top = 26.dp, bottom = 8.dp),
                        seekPreview, seekThumbnail, onSeekBegin, onSeekUpdate, onSeekFinish, cancelBoundary)
                }
            }
            AnimatedVisibility(showControls || locked, Modifier.align(Alignment.CenterEnd), enter = fadeIn(), exit = fadeOut()) {
                VideoSideActions(locked, state.canControl, {
                    onBoost(false)
                    onSeekFinish(false)
                    hint = null
                    if (locked) unlock() else { locked = true; controlsVisible = false; lockNotice = true }
                }, { onSeekFinish(false); onPip(); interact() },
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(end = if (wide) 24.dp else 16.dp))
            }
            if (clipStartMs != null && clipEndMs == null) TextButton(onClick = { onClip(); interact() },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = if (wide) 94.dp else 116.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF8A80))) {
                Text("● 录制中 ${formatTime((state.positionMs - clipStartMs).coerceAtLeast(0))} · 点击结束")
            }
            if (seekPreview != null) Box(Modifier.fillMaxWidth().fillMaxHeight(.18f)
                .background(if (seekPreview.cancelled) Color(0xCCAA3333) else Color(0x88443355))) {
                Text(if (seekPreview.cancelled) "松手取消跳转" else "滑到此区域取消跳转", color = Color.White,
                    modifier = Modifier.align(Alignment.Center))
            }
            if (boosting || hint != null || lockNotice) Box(Modifier.align(Alignment.Center)
                .background(Color(0xE6222029), RoundedCornerShape(16.dp))) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when { boosting -> "${speedLabel(boostedSpeed(baseSpeed))} 加速播放"; lockNotice -> "控制已锁定"; else -> hint.orEmpty() },
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (boosting || lockNotice) Text(if (boosting) "松手恢复 ${speedLabel(baseSpeed)}" else "点击右侧锁按钮解锁", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                }
            }
            if (state.status == PlaybackStatus.BUFFERING) CircularProgressIndicator(Modifier.size(40.dp).align(Alignment.Center), strokeWidth = 3.dp)
            if (state.status == PlaybackStatus.ERROR) Surface(Modifier.align(Alignment.Center).padding(32.dp),
                color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(20.dp)) {
                    Text(state.errorMessage ?: "无法播放此文件")
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
        }
    }
}
