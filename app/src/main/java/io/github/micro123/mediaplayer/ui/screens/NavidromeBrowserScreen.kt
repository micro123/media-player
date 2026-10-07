package io.github.micro123.mediaplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.micro123.mediaplayer.data.SourceEntry
import io.github.micro123.mediaplayer.data.navidrome.NavidromeAddress
import io.github.micro123.mediaplayer.ui.RemoteBrowserState
import io.github.micro123.mediaplayer.ui.components.SearchButton

@Composable
fun NavidromeBrowserScreen(state: RemoteBrowserState, onOpen: (SourceEntry) -> Unit, onUp: () -> Unit,
    onRefresh: () -> Unit, onSearch: (String) -> Unit, onPlay: () -> Unit, onBookmark: () -> Unit,
    onNetworkSettings: () -> Unit, modifier: Modifier = Modifier, onCancelQueue: () -> Unit = {}) {
    val address = remember(state.current) { NavidromeAddress.parse(state.current) }
    var searchOpen by rememberSaveable(state.root) { mutableStateOf(false) }
    var query by rememberSaveable(state.root) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun search() { if (query.isNotBlank()) { focus.clearFocus(); onSearch(query) } }
    BackHandler(enabled = searchOpen) { searchOpen = false; focus.clearFocus() }
    val category = address.route.firstOrNull()
    val title = when (category) {
        "albums" -> "专辑"; "artists" -> "歌手"; "artist" -> "歌手专辑"
        "album" -> "专辑曲目"; "songs" -> "全部歌曲"; "playlists" -> "服务器播放列表"
        "playlist" -> "播放列表曲目"; "search" -> "搜索结果"; else -> "Navidrome"
    }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onUp) { Text(if (state.current == state.root) "返回文件" else "上一级") }
            TextButton(onClick = onBookmark, enabled = !state.loading && state.error == null) { Text("收藏当前位置") }
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text("刷新") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            SearchButton(searchOpen) { searchOpen = !searchOpen }
        }
        Text(address.server, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp))
        if (searchOpen) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it.take(500) }, Modifier.weight(1f), singleLine = true,
                label = { Text("搜索服务器歌曲、专辑、歌手") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }))
            TextButton(onClick = { search() }, enabled = query.isNotBlank() && !state.loading) { Text("搜索") }
        }
        if (category == "search") Text("“${address.route.getOrNull(1).orEmpty()}”", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp))
        if (category in setOf("albums", "songs", "search")) Text("第 ${(address.route.lastOrNull()?.toIntOrNull() ?: 0) / 100 + 1} 页",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 16.dp))
            if (state.networkRestricted) TextButton(onClick = onNetworkSettings) { Text("打开应用网络设置") }
            else Text("可返回文件页编辑账号，或检查服务器后刷新。", style = MaterialTheme.typography.bodySmall)
        }
        val songs = state.entries.count { it.media != null }
        if (state.queueLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("正在读取完整歌曲列表，已读取 ${state.queueLoadedCount} 首…", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancelQueue) { Text("取消加载") }
            }
        } else if (!state.loading && state.error == null && (songs > 0 || category in setOf("songs", "search"))) {
            TextButton(onClick = onPlay) {
                Text(if (category in setOf("album", "playlist")) "播放全部（$songs 首）" else "播放全部歌曲")
            }
        }
        if (!state.loading && state.error == null && state.entries.isEmpty()) Text("没有找到音乐或播放列表", Modifier.padding(vertical = 24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            // Server playlists may repeat the same song; position remains its queue ordering.
            items(state.entries) { entry ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(entry) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        if (entry.subtitle.isNotBlank()) Text(entry.subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
