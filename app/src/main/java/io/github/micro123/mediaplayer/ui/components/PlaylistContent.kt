package io.github.micro123.mediaplayer.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.micro123.mediaplayer.core.MediaItem
import io.github.micro123.mediaplayer.data.QueuePreview
import io.github.micro123.mediaplayer.data.queueMediaInfo
import io.github.micro123.mediaplayer.ui.PlaylistState
import kotlinx.coroutines.isActive
import java.util.IdentityHashMap
import java.util.UUID

private data class QueueRow(val key: String, val media: MediaItem)

/** A row identity belongs to an occurrence, not its URI. Server playlists can repeat songs. */
private class QueueRowKeys {
    private var previous = emptyList<QueueRow>()
    fun move(from: Int, to: Int) {
        if (from in previous.indices && to in previous.indices) previous = previous.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun rows(items: List<MediaItem>): List<QueueRow> {
        val existing = IdentityHashMap<MediaItem, ArrayDeque<String>>()
        previous.forEach { existing.getOrPut(it.media) { ArrayDeque() }.add(it.key) }
        return items.map { media -> QueueRow(existing[media]?.removeFirstOrNull() ?: UUID.randomUUID().toString(), media) }
            .also { previous = it }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSheet(queue: PlaylistState, autoNext: Boolean, onAutoNext: (Boolean) -> Unit, onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit, onMove: (Int, Int) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit,
    onPreview: (suspend (MediaItem) -> QueuePreview)? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PlaylistContent(queue, autoNext, onAutoNext, onSelect, onRemove, onMove, onAdd,
            Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 20.dp), onPreview = onPreview)
    }
}

@Composable
fun PlaylistContent(queue: PlaylistState, autoNext: Boolean, onAutoNext: (Boolean) -> Unit,
    onSelect: (Int) -> Unit, onRemove: (Int) -> Unit, onMove: (Int, Int) -> Unit, onAdd: () -> Unit,
    modifier: Modifier = Modifier, onImport: (() -> Unit)? = null, onExport: (() -> Unit)? = null,
    onSaveBookmark: (() -> Unit)? = null, onPreview: (suspend (MediaItem) -> QueuePreview)? = null) {
    val keys = remember { QueueRowKeys() }
    var orderRevision by remember { mutableIntStateOf(0) }
    val rows = remember(queue.items, orderRevision) { keys.rows(queue.items) }
    val currentRows by rememberUpdatedState(rows)
    val move by rememberUpdatedState(onMove)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableIntStateOf(0) }
    var dragScrollAnchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pendingScroll by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    fun performMove(from: Int, delta: Int) {
        pendingScroll = dragScrollAnchor ?: (listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
        keys.move(from, from + delta)
        orderRevision++ // Swapping equal, repeated MediaItems still changes their row occurrences.
        move(from, delta)
    }
    fun moveUnderPointer() {
        if (pendingScroll != null) return
        val key = draggedKey ?: return
        val layout = listState.layoutInfo
        val dragged = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        if (dragged.index != draggedIndex) return // Wait for the previous move to be laid out.
        val center = dragTop + dragHeight / 2f
        val visible = layout.visibleItemsInfo
        val target = visible.firstOrNull { center >= it.offset && center < it.offset + it.size }
            ?: when {
                center < visible.first().offset -> visible.first()
                center >= visible.last().offset + visible.last().size -> visible.last()
                else -> return
            }
        if (target.key == key || target.index !in currentRows.indices) return
        performMove(draggedIndex, target.index - draggedIndex)
        draggedIndex = target.index
    }
    fun stopDragging() { draggedKey = null; draggedIndex = -1; dragScrollAnchor = null }
    LaunchedEffect(draggedKey) {
        if (draggedKey == null) return@LaunchedEffect
        val edge = with(density) { 48.dp.toPx() }
        val maxStep = with(density) { 12.dp.toPx() }
        while (isActive) {
            withFrameNanos { }
            if (pendingScroll != null) continue
            val layout = listState.layoutInfo
            val center = dragTop + dragHeight / 2f
            val scroll = when {
                center < layout.viewportStartOffset + edge -> -maxStep * ((layout.viewportStartOffset + edge - center) / edge).coerceIn(0f, 1f)
                center > layout.viewportEndOffset - edge -> maxStep * ((center - layout.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (scroll != 0f) {
                listState.scrollBy(scroll)
                dragScrollAnchor = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }
            moveUnderPointer()
        }
    }
    LaunchedEffect(rows, pendingScroll) {
        // Apply after the lazy item provider has the new order; an earlier request can be
        // consumed by the old layout and then its first-item key anchor scrolls the list.
        pendingScroll?.let { (index, offset) ->
            listState.requestScrollToItem(index, offset)
            // Let the reordered provider and its layout settle before another pointer move.
            repeat(2) { withFrameNanos { } }
            pendingScroll = null
        }
        if (draggedKey != null && rows.none { it.key == draggedKey }) stopDragging()
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("播放列表 · ${queue.items.size}", style = MaterialTheme.typography.titleLarge)
                Text("拖动右侧把手调整顺序", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onAdd) { PlayerSymbol(PlayerIcon.FILES, description = "添加文件") }
        }
        if (onImport != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onImport) { Text("导入 M3U") }
            TextButton(onClick = { onExport?.invoke() }, enabled = queue.items.isNotEmpty()) { Text("导出 M3U") }
            TextButton(onClick = { onSaveBookmark?.invoke() }, enabled = queue.items.isNotEmpty()) { Text("保存列表书签") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自动播放下一项", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = autoNext, onCheckedChange = onAutoNext)
        }
        if (queue.items.isEmpty()) Text("列表为空，从媒体库或文件夹选择文件。", Modifier.padding(vertical = 24.dp))
        LazyColumn(Modifier.weight(1f).pointerInput(Unit) {
            // Keep the gesture on the list, not a disposable lazy row. Only its handle
            // strip starts a drag; scrolling/tapping the rest of a row works normally.
            val handleWidth = 48.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.position.x < size.width - handleWidth) return@awaitEachGesture
                val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                    down.position.y >= it.offset && down.position.y < it.offset + it.size
                } ?: return@awaitEachGesture
                val row = currentRows.getOrNull(visible.index) ?: return@awaitEachGesture
                down.consume()
                draggedKey = row.key; draggedIndex = visible.index; dragTop = visible.offset.toFloat(); dragHeight = visible.size
                dragScrollAnchor = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { event.changes.forEach { it.consume() }; break }
                        dragTop += change.positionChange().y
                        event.changes.forEach { it.consume() }
                        moveUnderPointer()
                    }
                } finally { stopDragging() }
            }
        }, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                val active = row.key == draggedKey
                val selected = index == queue.index
                val preview by produceState(QueuePreview(), row.media.uri, onPreview) {
                    value = onPreview?.invoke(row.media) ?: QueuePreview()
                }
                Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.zIndex(if (active) 1f else 0f)
                        .then(if (active) Modifier else Modifier.animateItem())
                        .graphicsLayer {
                            translationY = if (active) dragTop - (listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == row.key }?.offset ?: dragTop.toInt()) else 0f
                            shadowElevation = if (active) 8.dp.toPx() else 0f
                        }) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable(enabled = draggedKey == null, onClick = { onSelect(index) })
                            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            QueueArtwork(row.media, preview.artwork)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(row.media.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (selected) "正在播放 · 第 ${index + 1} 项" else "第 ${index + 1} 项 · ${if (row.media.isVideo) "视频" else "音频"}",
                                    style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (preview.subtitle.isNotBlank()) Text(preview.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(queueMediaInfo(row.media, preview), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { onRemove(index) }, enabled = draggedKey == null) {
                            PlayerSymbol(PlayerIcon.CLOSE, Modifier.size(18.dp), "移除第 ${index + 1} 项")
                        }
                        Box(Modifier.size(48.dp, 64.dp).semantics {
                            contentDescription = "重排第 ${index + 1} 项"
                            customActions = listOf(CustomAccessibilityAction("上移") {
                                if (index > 0) { performMove(index, -1); true } else false
                            }, CustomAccessibilityAction("下移") {
                                if (index < queue.items.lastIndex) { performMove(index, 1); true } else false
                            })
                        }, contentAlignment = Alignment.Center) {
                            PlayerSymbol(PlayerIcon.DRAG_HANDLE, Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun QueueArtwork(media: MediaItem, bitmap: Bitmap?) {
    Box(Modifier.size(58.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer)
        .semantics { contentDescription = if (bitmap != null) "${if (media.isVideo) "视频缩略图" else "音乐封面"}：${media.displayName}" else "${if (media.isVideo) "视频" else "音频"}预览：${media.displayName}" },
        contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(remember(bitmap) { bitmap.asImageBitmap() }, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else PlayerSymbol(if (media.isVideo) PlayerIcon.VIDEO else PlayerIcon.MUSIC, Modifier.size(28.dp))
    }
}
