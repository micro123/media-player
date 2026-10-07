package com.tang.player.ui.screens

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tang.player.core.MediaItem
import com.tang.player.data.FolderEntry
import com.tang.player.data.LibraryMediaEntry
import com.tang.player.data.BrowseSort
import com.tang.player.data.sortBrowseItems
import com.tang.player.ui.components.SearchButton
import com.tang.player.ui.components.BrowserSearchField
import com.tang.player.ui.components.BrowseSortButton
import com.tang.player.ui.LibrarySource
import com.tang.player.ui.LibraryState
import com.tang.player.ui.components.PlayerIcon
import com.tang.player.ui.components.PlayerSymbol

@Composable
fun LibraryScreen(state: LibraryState, onOpenFile: () -> Unit, onSelect: (List<MediaItem>, Int) -> Unit,
    onSource: (LibrarySource) -> Unit, onAccess: () -> Unit, onFolder: () -> Unit,
    onEnterFolder: (FolderEntry) -> Unit, onUp: () -> Unit, onRefresh: () -> Unit,
    onAddFiles: () -> Unit, onQueue: () -> Unit, groupMedia: Boolean, onGroupMedia: (Boolean) -> Unit,
    modifier: Modifier = Modifier, onNetwork: () -> Unit = {}, onImportPlaylist: () -> Unit = {}, onBookmarks: () -> Unit = {},
    onFileRoot: () -> Unit = {}, onSaveFolder: () -> Unit = {}, fileSort: BrowseSort = BrowseSort.NAME,
    fileSortDescending: Boolean = false, onFileSort: (BrowseSort, Boolean) -> Unit = { _, _ -> }) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable(state.source) { mutableStateOf("") }
    var searchOpen by rememberSaveable(state.source) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var selectedSeriesKey by rememberSaveable { mutableStateOf<String?>(null) }
    val rootScroll = rememberLazyListState()
    val folderMode = state.source == LibrarySource.FOLDER
    val grouping = state.source == LibrarySource.MEDIA_STORE && groupMedia
    val selectedSeries = if (grouping) state.mediaEntries.filterIsInstance<LibraryMediaEntry.Series>()
        .firstOrNull { it.key == selectedSeriesKey } else null
    LaunchedEffect(state.source, groupMedia) {
        if (state.source != LibrarySource.MEDIA_STORE || !groupMedia) selectedSeriesKey = null
    }
    LaunchedEffect(state.mediaEntries, state.isLoading) {
        if (!state.isLoading && selectedSeriesKey != null && selectedSeries == null) selectedSeriesKey = null
    }
    BackHandler(enabled = selectedSeries != null) { selectedSeriesKey = null }
    BackHandler(enabled = searchOpen && selectedSeries == null) { searchOpen = false; search = "" }
    if (selectedSeries != null) {
        SeriesDetail(selectedSeries, state.isLoading, { selectedSeriesKey = null }, onSelect, modifier)
        return
    }
    val sourceItemsUnsorted = when (state.source) {
        LibrarySource.MEDIA_STORE -> state.media
        LibrarySource.FOLDER -> state.entries.mapNotNull { it.media }
        LibrarySource.RECENT -> state.recent
    }
    val sourceItems = if (folderMode) sortBrowseItems(sourceItemsUnsorted, fileSort, fileSortDescending,
        { it.displayName }, { false }, { it.sizeBytes }) else sourceItemsUnsorted
    val visible = sourceItems.filter { (filter == 0 || (filter == 1 && it.isVideo) || (filter == 2 && !it.isVideo)) &&
        (search.isBlank() || it.displayName.contains(search.trim(), ignoreCase = true)) }
    val groupedVisible = if (grouping) state.mediaEntries.filter { entry ->
        (filter == 0 || (filter == 1 && entry.items.first().isVideo) || (filter == 2 && !entry.items.first().isVideo)) &&
            (search.isBlank() || entry.title.contains(search.trim(), ignoreCase = true) || entry.items.any { it.displayName.contains(search.trim(), ignoreCase = true) })
    } else emptyList()
    val playable = if (grouping) groupedVisible.flatMap { entry ->
        entry.items.filter { search.isBlank() || entry.title.contains(search.trim(), ignoreCase = true) || it.displayName.contains(search.trim(), ignoreCase = true) }
    } else visible
    LazyColumn(modifier.fillMaxSize(), state = rootScroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (folderMode) "文件夹" else "媒体库", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    SearchButton(searchOpen) { searchOpen = !searchOpen; if (!searchOpen) search = "" }
                    if (folderMode) BrowseSortButton(fileSort, fileSortDescending, onFileSort)
                    TextButton(onClick = onOpenFile) { Text("打开文件") }
                    Box {
                        IconButton(onClick = { more = true }) { PlayerSymbol(PlayerIcon.MORE, description = "浏览选项") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text("打开网络地址") }, onClick = { more = false; onNetwork() })
                            DropdownMenuItem(text = { Text("导入 M3U 播放列表") }, onClick = { more = false; onImportPlaylist() })
                            DropdownMenuItem(text = { Text("位置与播放书签") }, onClick = { more = false; onBookmarks() })
                            DropdownMenuItem(text = { Text("刷新媒体库") }, onClick = { more = false; onRefresh() })
                            DropdownMenuItem(text = { Text("添加多个文件到播放列表") }, onClick = { more = false; onAddFiles() })
                            DropdownMenuItem(text = { Text("查看播放列表") }, onClick = { more = false; onQueue() })
                        }
                    }
                }
                if (!folderMode) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(LibrarySource.MEDIA_STORE to "全部媒体", LibrarySource.RECENT to "最近播放").forEach { (source, label) ->
                        FilterChip(selected = state.source == source, onClick = { onSource(source) }, label = { Text(label) })
                    }
                }
                if (state.source == LibrarySource.MEDIA_STORE) {
                    if (!state.access.full) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (state.access.limitedVideo) "当前只能访问选中的视频，可重新选择或授予全部视频权限。"
                                    else "授权后可直接浏览安卓媒体库；也可用文件夹或系统选择器打开文件。")
                                Button(onClick = onAccess) { Text(if (state.access.any) "调整媒体库授权" else "读取安卓媒体库") }
                            }
                        }
                    }
                    TextButton(onClick = onRefresh) { Text("刷新媒体库") }
                }
                if (state.source == LibrarySource.FOLDER) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onFileRoot) { Text("返回文件") }
                        TextButton(onClick = onSaveFolder, enabled = state.folderPath.isNotEmpty() && state.browserError == null && !state.isLoading) { Text("收藏当前目录") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.storageBrowser) "共享存储" else "已授权文件夹", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = onFolder) { Text(if (state.folderPath.isEmpty()) "选择文件夹" else "存储根目录") }
                    }
                    Text(state.folderPath.joinToString(" / ") { it.name }.ifBlank { "选择文件夹后浏览本地音视频" },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.folderPath.size > 1) TextButton(onClick = onUp) {
                        PlayerSymbol(PlayerIcon.BACK, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("返回上级文件夹")
                    }
                }
                if (searchOpen) BrowserSearchField(search, { search = it }, if (folderMode) "搜索当前文件夹中的音视频" else "搜索文件名")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部", "视频", "音频").forEachIndexed { index, label ->
                        FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(label) })
                    }
                    if (state.source == LibrarySource.MEDIA_STORE) FilterChip(selected = groupMedia,
                        onClick = { onGroupMedia(!groupMedia) }, label = { Text("剧集归类") },
                        leadingIcon = { PlayerSymbol(PlayerIcon.PLAYLIST, Modifier.size(18.dp)) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (grouping) "${groupedVisible.count { it is LibraryMediaEntry.Series }} 个剧集 · ${playable.size} 个文件" else "${visible.size} 个文件",
                        style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { onSelect(playable, 0) }, enabled = playable.isNotEmpty() && !state.isLoading) { Text("播放全部") }
                }
                state.browserError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (state.source == LibrarySource.FOLDER) {
            items(sortBrowseItems(state.entries.filter { it.isDirectory && (search.isBlank() || it.name.contains(search.trim(), true)) },
                fileSort, fileSortDescending, { it.name }, { true }, { null }), key = { it.uri }) { folder ->
                Card(onClick = { onEnterFolder(folder) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PlayerSymbol(PlayerIcon.FOLDER)
                        Text(folder.name, Modifier.weight(1f))
                        Text("›")
                    }
                }
            }
        }
        if (grouping) items(groupedVisible, key = { it.key }) { entry ->
            when (entry) {
                is LibraryMediaEntry.Series -> SeriesRow(entry, !state.isLoading) { selectedSeriesKey = entry.key }
                is LibraryMediaEntry.File -> MediaRow(entry.media, !state.isLoading) { onSelect(playable, playable.indexOf(entry.media)) }
            }
        } else items(visible, key = { it.uri }) { media ->
            MediaRow(media, !state.isLoading) { onSelect(visible, visible.indexOf(media)) }
        }
        if (playable.isEmpty() && !state.isLoading && (!folderMode || state.entries.none { it.isDirectory && (search.isBlank() || it.name.contains(search.trim(), true)) })) item {
            Text(when {
                search.isNotBlank() -> "没有匹配的音视频文件。"
                state.source == LibrarySource.FOLDER && state.folderPath.isEmpty() -> "选择一个文件夹后，可逐级浏览音视频文件。"
                else -> "这里还没有可访问的音视频文件。"
            }, Modifier.padding(vertical = 28.dp))
        }
    }
}

@Composable
private fun SeriesRow(series: LibraryMediaEntry.Series, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "打开剧集 ${series.title}" }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { PlayerSymbol(PlayerIcon.PLAYLIST, Modifier.size(28.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(series.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${if (series.items.first().isVideo) "剧集" else "音频合集"} · ${series.items.size} 个文件 · 自动归类", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun SeriesDetail(series: LibraryMediaEntry.Series, loading: Boolean, onBack: () -> Unit,
    onSelect: (List<MediaItem>, Int) -> Unit, modifier: Modifier) {
    var search by rememberSaveable(series.key) { mutableStateOf("") }
    var searchOpen by rememberSaveable(series.key) { mutableStateOf(false) }
    BackHandler(enabled = searchOpen) { searchOpen = false; search = "" }
    val visible = series.items.filter { search.isBlank() || it.displayName.contains(search.trim(), ignoreCase = true) }
    LazyColumn(modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { PlayerSymbol(PlayerIcon.BACK, description = "返回媒体库") }
                    Text(series.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    SearchButton(searchOpen) { searchOpen = !searchOpen; if (!searchOpen) search = "" }
                }
                Text("${series.items.size} 个文件 · 按集数顺序排列", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { onSelect(series.items, 0) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    PlayerSymbol(PlayerIcon.PLAY, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("播放全部")
                }
                if (searchOpen) BrowserSearchField(search, { search = it }, "搜索本剧集中的文件")
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        items(visible, key = { it.uri }) { media ->
            MediaRow(media, !loading) { onSelect(series.items, series.items.indexOf(media)) }
        }
        if (visible.isEmpty()) item { Text("没有匹配的文件。", Modifier.padding(vertical = 24.dp)) }
    }
}

@Composable
private fun MediaRow(media: MediaItem, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val size = media.sizeBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "大小未知"
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PlayerSymbol(if (media.isVideo) PlayerIcon.VIDEO else PlayerIcon.MUSIC, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(media.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${if (media.isVideo) "视频" else "音频"} · $size", style = MaterialTheme.typography.bodySmall)
            }
            PlayerSymbol(PlayerIcon.PLAY, modifier = Modifier.size(20.dp))
        }
    }
}
