package io.github.micro123.mediaplayer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.micro123.mediaplayer.core.PlaybackState
import io.github.micro123.mediaplayer.ui.ClipSelection

@Composable
fun ClipDialog(clip: ClipSelection, state: PlaybackState, onStart: () -> Unit, onEnd: () -> Unit,
    onExport: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val sameMedia = clip.media == null || clip.media.uri == state.media?.uri
    val canMark = state.canControl && state.seekable && state.media?.isVideo == true && !clip.exporting && sameMedia
    AlertDialog(onDismissRequest = onDismiss, title = { Text("区间录制") }, text = {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前 ${formatTime(state.positionMs)}\n起点 ${clip.startMs?.let(::formatTime) ?: "未标记"}\n终点 ${clip.endMs?.let(::formatTime) ?: "未标记"}")
            Text("播放页点击录制按钮开始，再次点击同一按钮结束。导出原速、无损 MP4，不包含播放控件；实际起点为前一个关键帧，可能早于开始位置。不支持的音视频编码会提示，字幕暂不导出。", style = MaterialTheme.typography.bodySmall)
            if (clip.actualStartMs != null) Text("实际导出 ${formatTime(clip.actualStartMs)}～${formatTime(requireNotNull(clip.endMs))}", color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = if (clip.startMs != null && clip.endMs == null) onEnd else onStart,
                    enabled = canMark && (clip.startMs == null || clip.endMs != null || state.positionMs > clip.startMs)) {
                    Text(if (clip.startMs != null && clip.endMs == null) "结束录制" else if (clip.endMs != null) "重新开始录制" else "开始录制")
                }
                TextButton(onClick = onCancel, enabled = !clip.exporting && clip.startMs != null) { Text("清除区间") }
            }
            if (clip.exporting) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在导出片段…") }
            if (clip.preparing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在检查轨道及关键帧…") }
        }
    }, confirmButton = { TextButton(onClick = onExport, enabled = clip.actualStartMs != null && !clip.preparing && !clip.exporting) { Text("导出片段") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回观看") } })
}
