package com.tang.player.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tang.player.core.PlaybackState
import com.tang.player.core.PlaybackStatus

@Composable
fun AudioMiniPlayer(state: PlaybackState, onOpen: () -> Unit, onToggle: () -> Unit, onStop: () -> Unit,
    metadata: com.tang.player.data.AudioMetadata = com.tang.player.data.AudioMetadata()) {
    val tags = metadata.takeIf { it.uri == state.media?.uri } ?: com.tang.player.data.AudioMetadata(title = state.media?.displayName.orEmpty())
    Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "打开音频播放器" }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            com.tang.player.ui.screens.MusicArtwork(tags, Modifier.size(44.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(tags.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggle, enabled = state.canControl) {
                PlayerSymbol(if (state.status == PlaybackStatus.PLAYING) PlayerIcon.PAUSE else PlayerIcon.PLAY,
                    description = if (state.status == PlaybackStatus.PLAYING) "暂停音频" else "播放音频")
            }
            IconButton(onClick = onStop) { Text("×", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { contentDescription = "停止音频" }) }
        }
    }
}
