package com.tang.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tang.player.data.BookmarkKind
import com.tang.player.data.SavedBookmark

/** All browsable sources live here; time points and playlists remain in the bookmark destination. */
@Composable
fun FilesScreen(bookmarks: List<SavedBookmark>, onStorage: () -> Unit, onFolder: () -> Unit,
    onNetwork: () -> Unit, onOpen: (SavedBookmark) -> Unit, onEdit: (SavedBookmark) -> Unit,
    onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    val locations = bookmarks.filter { it.kind == BookmarkKind.LOCATION || it.kind == BookmarkKind.FOLDER }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("文件", style = MaterialTheme.typography.headlineMedium)
                Text("浏览本地存储与网络位置，收藏常用目录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Card(Modifier.fillMaxWidth().clickable(onClick = onStorage)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("内部共享存储", style = MaterialTheme.typography.titleMedium)
                        Text("浏览手机上的音视频文件", style = MaterialTheme.typography.bodySmall)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onFolder) { Text("选择本地文件夹") }
                    Button(onClick = onNetwork) { Text("添加网络位置") }
                }
                Text("目录与位置书签", style = MaterialTheme.typography.titleLarge)
            }
        }
        if (locations.isEmpty()) item { Text("还没有位置书签。添加网络位置，或进入本地 / 网络目录后点击「收藏当前目录」。",
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
        items(locations, key = { it.id }) { bookmark ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.fillMaxWidth().clickable { onOpen(bookmark) }.padding(vertical = 4.dp)) {
                        Text(bookmark.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (bookmark.address.startsWith("file:") || bookmark.address.startsWith("content:")) "本地文件夹"
                            else "${if (bookmark.kind == BookmarkKind.FOLDER) "网络文件夹" else "网络位置"} · ${bookmark.address}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onOpen(bookmark) }) { Text("打开") }
                        TextButton(onClick = { onEdit(bookmark) }) { Text(if (bookmark.kind == BookmarkKind.FOLDER) "重命名" else "编辑") }
                        TextButton(onClick = { onRemove(bookmark.id) }) { Text("删除") }
                    }
                }
            }
        }
    }
}
