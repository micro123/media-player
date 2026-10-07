package com.tang.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tang.player.data.SourceEntry
import com.tang.player.ui.RemoteBrowserState

@Composable
fun NetworkBrowserScreen(state: RemoteBrowserState, onOpen: (SourceEntry) -> Unit, onUp: () -> Unit,
    onRefresh: () -> Unit, modifier: Modifier = Modifier, onBookmark: () -> Unit = {}, onNetworkSettings: () -> Unit = {}) {
    var search by rememberSaveable(state.current) { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onUp) { Text(if (state.current == state.root) "返回文件" else "上一级") }
            TextButton(onClick = onBookmark, enabled = !state.loading && state.error == null) { Text("收藏当前目录") }
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text("刷新") }
        }
        Text("网络目录", style = MaterialTheme.typography.headlineSmall)
        Text(java.net.URI(state.current).let { it.host + it.path }, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        OutlinedTextField(search, { search = it }, label = { Text("搜索当前目录") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp)); Text("正在读取目录…") }
        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 16.dp))
            if (state.networkRestricted) TextButton(onClick = onNetworkSettings) { Text("打开应用网络设置") }
            else Text("可返回文件页编辑地址和认证信息，或检查服务器与网络后刷新。", style = MaterialTheme.typography.bodySmall)
        }
        val entries = state.entries.filter { it.name.contains(search, true) }
        if (!state.loading && state.error == null && entries.isEmpty()) Text("没有匹配的目录、音视频或 M3U 文件", Modifier.padding(vertical = 24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(entries, key = { it.address }) { entry ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(entry) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (entry.directory) "文件夹" else if (entry.name.endsWith(".m3u", true) || entry.name.endsWith(".m3u8", true)) "导入播放列表"
                            else if (entry.media?.isVideo == true) "视频" else "音频", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
