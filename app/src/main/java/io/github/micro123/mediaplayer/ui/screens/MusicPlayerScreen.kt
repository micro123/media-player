package io.github.micro123.mediaplayer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.micro123.mediaplayer.core.PlaybackState
import io.github.micro123.mediaplayer.core.PlaybackStatus
import io.github.micro123.mediaplayer.data.AudioMetadata
import io.github.micro123.mediaplayer.ui.PlaylistState
import io.github.micro123.mediaplayer.ui.components.*

/** Audio has its own artwork and transport layout, without video surfaces, gestures or rotation controls. */
@Composable
fun MusicPlayerScreen(state: PlaybackState, metadata: AudioMetadata, queue: PlaylistState,
    onToggle: () -> Unit, onSeek: (Long) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onQueue: () -> Unit, onSpeed: () -> Unit, onOpenFile: () -> Unit, onRetry: () -> Unit,
    resumePosition: Long, onResume: () -> Unit, modifier: Modifier = Modifier) {
    val tags = metadata.takeIf { it.uri == state.media?.uri } ?: AudioMetadata(title = state.media?.displayName.orEmpty())
    var showInfo by rememberSaveable(state.media?.uri) { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val coverSize = (if (landscape) minOf(maxHeight - 48.dp, maxWidth * 0.38f)
            else minOf(maxWidth - 48.dp, maxHeight * 0.42f)).coerceAtLeast(80.dp)
        val spacing = if (landscape) 24.dp else 20.dp
        val details: @Composable (Modifier) -> Unit = { detailModifier ->
            Column(detailModifier, verticalArrangement = Arrangement.spacedBy(if (landscape) 6.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tags.title.ifBlank { state.media?.displayName.orEmpty() }, style = if (landscape) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (landscape) Text("${tags.artist.ifBlank { "未知歌手" }} · ${tags.album.ifBlank { "未知专辑" }}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else {
                    Text(tags.artist.ifBlank { "未知歌手" }, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tags.album.ifBlank { "未知专辑" }, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (tags.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                MusicTransport(state, queue, onToggle, onSeek, onPrevious, onNext, compact = landscape)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    TextButton(onClick = onSpeed) { Text(speedLabel(state.speed)) }
                    TextButton(onClick = onQueue) { Text("播放列表") }
                    TextButton(onClick = { showInfo = true }) { Text("歌曲信息") }
                    TextButton(onClick = onOpenFile) { Text("打开文件") }
                }
                if (resumePosition > 0) TextButton(onClick = onResume) { Text("回到上次位置 ${formatTime(resumePosition)}") }
                if (state.status == PlaybackStatus.BUFFERING) Text("正在缓冲…", style = MaterialTheme.typography.bodySmall)
                if (state.status == PlaybackStatus.ERROR) {
                    Text(state.errorMessage ?: "无法播放这首音乐", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
        }
        if (landscape) Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically) {
            MusicArtwork(tags, Modifier.size(coverSize))
            details(Modifier.weight(1f).verticalScroll(rememberScrollState()))
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(spacing), horizontalAlignment = Alignment.CenterHorizontally) {
            MusicArtwork(tags, Modifier.size(coverSize))
            details(Modifier.fillMaxWidth())
        }
    }
    if (showInfo) MusicInfoDialog(tags, state, { showInfo = false })
}

@Composable
fun MusicArtwork(metadata: AudioMetadata, modifier: Modifier = Modifier) {
    val art = metadata.cover
    val description = if (art == null) "默认音乐封面" else "专辑封面"
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Color(0xFF3B527D), Color(0xFF6F466C))))
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (art != null) Image(remember(art) { art.asImageBitmap() }, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.Black.copy(alpha = 0.28f), radius = size.minDimension * 0.37f)
                drawCircle(Color.White.copy(alpha = 0.1f), radius = size.minDimension * 0.25f)
                drawCircle(Color.Black.copy(alpha = 0.25f), radius = size.minDimension * 0.13f)
            }
            PlayerSymbol(PlayerIcon.MUSIC, Modifier.size(48.dp))
        }
    }
}

@Composable
private fun MusicTransport(state: PlaybackState, queue: PlaylistState, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, compact: Boolean = false) {
    var dragged by remember(state.media?.uri) { mutableFloatStateOf(-1f) }
    val duration = state.durationMs.coerceAtLeast(0)
    val fraction = if (duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Slider(if (dragged >= 0) dragged else fraction, { dragged = it }, onValueChangeFinished = {
            if (dragged >= 0 && duration > 0) onSeek((dragged * duration).toLong())
            dragged = -1f
        }, enabled = state.canControl && state.seekable && duration > 0,
            modifier = Modifier.semantics { contentDescription = "音乐播放进度" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(if (dragged >= 0) (dragged * duration).toLong() else state.positionMs), style = MaterialTheme.typography.labelMedium)
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = queue.hasPrevious) { PlayerSymbol(PlayerIcon.PREVIOUS, description = "上一首") }
            FilledIconButton(onClick = onToggle, enabled = state.canControl, modifier = Modifier.size(if (compact) 56.dp else 72.dp)) {
                val playing = state.status == PlaybackStatus.PLAYING
                PlayerSymbol(if (playing) PlayerIcon.PAUSE else PlayerIcon.PLAY, Modifier.size(32.dp),
                    description = if (playing) "暂停" else if (state.status == PlaybackStatus.ENDED) "重新播放" else "播放")
            }
            IconButton(onClick = onNext, enabled = queue.hasNext) { PlayerSymbol(PlayerIcon.NEXT, description = "下一首") }
        }
    }
}

@Composable
private fun MusicInfoDialog(tags: AudioMetadata, state: PlaybackState, onDismiss: () -> Unit) {
    val rows = listOf("歌曲" to tags.title, "歌手" to tags.artist, "专辑" to tags.album, "专辑歌手" to tags.albumArtist,
        "年份" to tags.year, "流派" to tags.genre, "曲目" to tags.track, "作曲" to tags.composer,
        "码率" to tags.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" }.orEmpty(),
        "时长" to formatTime(state.durationMs), "文件名" to state.media?.displayName.orEmpty())
    AlertDialog(onDismissRequest = onDismiss, title = { Text("歌曲信息") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            rows.filter { it.second.isNotBlank() }.forEach { (label, value) ->
                Column { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(value, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
