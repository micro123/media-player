package io.github.micro123.mediaplayer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.micro123.mediaplayer.data.PlayerPreferences
import io.github.micro123.mediaplayer.data.VideoAspect
import io.github.micro123.mediaplayer.data.VideoOrientation
import io.github.micro123.mediaplayer.ui.PlaylistState
import java.util.Locale

fun speedLabel(value: Double): String = String.format(Locale.ROOT, "%.1f×", value)
fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

@Composable
fun SpeedControls(speed: Double, rememberSpeed: Boolean, onSpeed: (Double) -> Unit, onRemember: (Boolean) -> Unit) {
    var dragged by remember { mutableFloatStateOf(-1f) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("播放倍速 ${speedLabel(if (dragged >= 0) dragged.toDouble() else speed)}", style = MaterialTheme.typography.titleMedium)
        Slider(value = if (dragged >= 0) dragged else speed.toFloat(), valueRange = 0.1f..5f, steps = 48,
            onValueChange = { dragged = it }, onValueChangeFinished = { if (dragged >= 0) onSpeed(dragged.toDouble()); dragged = -1f },
            modifier = Modifier.semantics { contentDescription = "播放倍速滑块" })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.5, 1.0, 1.5, 2.0, 3.0, 5.0).forEach { value ->
                FilterChip(selected = kotlin.math.abs(speed - value) < 0.01, onClick = { onSpeed(value) }, label = { Text(speedLabel(value)) })
            }
        }
        OutlinedButton(onClick = { onSpeed(1.0) }, modifier = Modifier.fillMaxWidth()) { Text("恢复 1.0 倍") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("记住上次倍速（全局）", Modifier.weight(1f))
            Switch(checked = rememberSpeed, onCheckedChange = onRemember, modifier = Modifier.semantics { contentDescription = "记住上次倍速" })
        }
        Text("长按视频临时使用当前倍速的 2 倍，最高 5.0 倍；松手恢复。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SpeedDialog(speed: Double, preferences: PlayerPreferences, onSpeed: (Double) -> Unit, onRemember: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("倍速播放") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { SpeedControls(speed, preferences.rememberSpeed, onSpeed, onRemember) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

@Composable
fun AspectChoices(selected: VideoAspect, onSelect: (VideoAspect) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VideoAspect.entries.forEach { aspect -> FilterChip(selected = selected == aspect, onClick = { onSelect(aspect) }, label = { Text(aspect.label) }) }
    }
}

@Composable
fun OrientationChoices(selected: VideoOrientation, onSelect: (VideoOrientation) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VideoOrientation.entries.forEach { value ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) },
                label = { Text(if (value == VideoOrientation.LANDSCAPE) "横屏（默认）" else value.label) })
        }
    }
}

@Composable
fun AspectDialog(selected: VideoAspect, onSelect: (VideoAspect) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("视频长宽比") }, text = { AspectChoices(selected, onSelect) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

@Composable
fun SkipDurationControls(seconds: Int, onSave: (Int) -> Unit) {
    var input by rememberSaveable(seconds) { mutableStateOf(seconds.toString()) }
    val valid = input.toIntOrNull()?.takeIf { it > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = input, onValueChange = { input = it.filter(Char::isDigit) },
            label = { Text("跳过 OP / ED（秒）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = valid == null, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { valid?.let(onSave) }, enabled = valid != null) { Text("保存时长") }
            TextButton(onClick = { onSave(85); input = "85" }) { Text("恢复 85 秒") }
        }
        Text("当前 $seconds 秒 · 全局保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun VideoSettingsDialog(preferences: PlayerPreferences, onAspect: (VideoAspect) -> Unit,
    onSkipSeconds: (Int) -> Unit, onAutoNext: (Boolean) -> Unit, onOrientation: (VideoOrientation) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("播放设置") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("画面比例", style = MaterialTheme.typography.titleSmall)
                AspectChoices(preferences.aspect, onAspect)
                HorizontalDivider()
                SkipDurationControls(preferences.skipSeconds, onSkipSeconds)
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("自动播放下一项", Modifier.weight(1f))
                    Switch(checked = preferences.autoNext, onCheckedChange = onAutoNext)
                }
                HorizontalDivider()
                Text("全屏播放方向", style = MaterialTheme.typography.titleSmall)
                OrientationChoices(preferences.orientation, onOrientation)
                Text("保持：不切换方向，保持当前横屏或竖屏。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSheet(queue: PlaylistState, autoNext: Boolean, onAutoNext: (Boolean) -> Unit, onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit, onMove: (Int, Int) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PlaylistContent(queue, autoNext, onAutoNext, onSelect, onRemove, onMove, onAdd,
            Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 20.dp))
    }
}

@Composable
fun PlaylistContent(queue: PlaylistState, autoNext: Boolean, onAutoNext: (Boolean) -> Unit,
    onSelect: (Int) -> Unit, onRemove: (Int) -> Unit, onMove: (Int, Int) -> Unit, onAdd: () -> Unit,
    modifier: Modifier = Modifier, onImport: (() -> Unit)? = null, onExport: (() -> Unit)? = null,
    onSaveBookmark: (() -> Unit)? = null) {
        Column(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("播放列表 · ${queue.items.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onAdd) { Text("添加文件") }
            }
            if (onImport != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onImport) { Text("导入 M3U") }
                TextButton(onClick = { onExport?.invoke() }, enabled = queue.items.isNotEmpty()) { Text("导出 M3U") }
                TextButton(onClick = { onSaveBookmark?.invoke() }, enabled = queue.items.isNotEmpty()) { Text("保存列表书签") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("播放结束后自动下一项", Modifier.weight(1f))
                Switch(checked = autoNext, onCheckedChange = onAutoNext)
            }
            if (queue.items.isEmpty()) Text("列表为空，从媒体库或文件夹选择文件。", Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(queue.items, key = { _, media -> media.uri }) { index, media ->
                    Surface(color = if (index == queue.index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            TextButton(onClick = { onSelect(index) }, modifier = Modifier.fillMaxWidth()) {
                                Text("${if (index == queue.index) "▶ " else ""}${index + 1}. ${media.displayName}", Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onMove(index, -1) }, enabled = index > 0) { Text("上移") }
                                TextButton(onClick = { onMove(index, 1) }, enabled = index < queue.items.lastIndex) { Text("下移") }
                                TextButton(onClick = { onRemove(index) }) { Text("移除") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
}
