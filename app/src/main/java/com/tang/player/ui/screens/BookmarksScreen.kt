package com.tang.player.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tang.player.data.BookmarkKind
import com.tang.player.data.SavedBookmark
import com.tang.player.ui.components.formatTime

@Composable
fun BookmarksScreen(items: List<SavedBookmark>, onOpen: (SavedBookmark) -> Unit, onRemove: (String) -> Unit,
    onLocation: () -> Unit, onEdit: (SavedBookmark) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("书签", style = MaterialTheme.typography.headlineMedium)
                Button(onClick = onLocation) { Text("添加网络位置") }
                Text("保存目录、播放时间点和播放列表。网络位置与目录也可从文件页打开。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (items.isEmpty()) item { Text("还没有书签。视频播放页可保存时间点，播放列表页可收藏当前列表。", Modifier.padding(vertical = 24.dp)) }
        items(items, key = { it.id }) { bookmark ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(bookmark.name, style = MaterialTheme.typography.titleMedium)
                    Text(when (bookmark.kind) {
                        BookmarkKind.LOCATION -> "网络位置 · ${bookmark.address}"
                        BookmarkKind.FOLDER -> "目录书签 · ${bookmark.name}"
                        BookmarkKind.POSITION -> "播放位置 · ${formatTime(bookmark.positionMs)} · ${bookmark.items.firstOrNull()?.displayName.orEmpty()}"
                        BookmarkKind.PLAYLIST -> "播放列表 · ${bookmark.items.size} 项"
                    }, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onOpen(bookmark) }) { Text(if (bookmark.kind == BookmarkKind.PLAYLIST) "添加到播放列表" else "打开") }
                        if (bookmark.kind in setOf(BookmarkKind.LOCATION, BookmarkKind.FOLDER)) TextButton(onClick = { onEdit(bookmark) }) { Text(if (bookmark.kind == BookmarkKind.FOLDER) "重命名" else "编辑") }
                        TextButton(onClick = { onRemove(bookmark.id) }) { Text("删除") }
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkLocationDialog(initial: SavedBookmark?, initialCredentials: com.tang.player.data.network.NetworkCredentials?,
    onSubmit: (String, String, String?, com.tang.player.data.network.NetworkCredentials, Boolean) -> Unit, onDismiss: () -> Unit) {
    var address by rememberSaveable(initial?.id) { mutableStateOf(initial?.address.orEmpty()) }
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    // Passwords are deliberately not written into saved instance state.
    var password by remember(initial?.id, initialCredentials) { mutableStateOf(initialCredentials?.password.orEmpty()) }
    var username by remember(initial?.id, initialCredentials) { mutableStateOf(initialCredentials?.username.orEmpty()) }
    var domain by remember(initial?.id, initialCredentials) { mutableStateOf(initialCredentials?.domain.orEmpty()) }
    var guest by remember(initial?.id, initialCredentials) { mutableStateOf(initialCredentials?.guest ?: true) }
    var uid by remember(initial?.id, initialCredentials) { mutableStateOf((initialCredentials?.uid ?: 65534).toString()) }
    var gid by remember(initial?.id, initialCredentials) { mutableStateOf((initialCredentials?.gid ?: 65534).toString()) }
    val kind = com.tang.player.core.mediaSourceKind(address.trim())
    val remote = kind in setOf(com.tang.player.core.MediaSourceKind.SMB, com.tang.player.core.MediaSourceKind.NFS)
    val error = runCatching {
        if (remote) com.tang.player.data.network.RemoteAddress.parse(address) else com.tang.player.data.validateNetworkAddress(address)
        if (kind == com.tang.player.core.MediaSourceKind.SMB && !guest) require(username.isNotBlank()) { "请输入用户名" }
        if (kind == com.tang.player.core.MediaSourceKind.NFS) require(uid.toLongOrNull() in 0L..0xffffffffL && gid.toLongOrNull() in 0L..0xffffffffL) { "UID / GID 须为 0～4294967295" }
    }.exceptionOrNull()?.message
    val credentialsLoading = initial != null && remote && initialCredentials == null
    fun submit(open: Boolean) {
        val auth = com.tang.player.data.network.NetworkCredentials(guest, username, password, domain,
            uid.toLongOrNull() ?: 65534, gid.toLongOrNull() ?: 65534)
        onSubmit(name.trim().ifBlank { java.net.URI(address.trim()).host }, address, initial?.id, auth, open)
        onDismiss()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "网络地址" else "编辑网络位置") }, text = {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(address, { address = it }, label = { Text("地址") }, placeholder = { Text("smb://服务器/Movies") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, isError = address.isNotBlank() && error != null)
            OutlinedTextField(name, { name = it.take(200) }, label = { Text("书签名称（选填）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (kind == com.tang.player.core.MediaSourceKind.SMB) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("访客访问", Modifier.align(androidx.compose.ui.Alignment.CenterVertically)); Switch(guest, { guest = it })
                }
                if (!guest) {
                    OutlinedTextField(username, { username = it.take(256) }, label = { Text("用户名") }, singleLine = true)
                    OutlinedTextField(password, { password = it.take(4096) }, label = { Text("密码") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    OutlinedTextField(domain, { domain = it.take(256) }, label = { Text("域（选填）") }, singleLine = true)
                }
            }
            if (kind == com.tang.player.core.MediaSourceKind.NFS) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(uid, { uid = it.take(10) }, label = { Text("UID") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    OutlinedTextField(gid, { gid = it.take(10) }, label = { Text("GID") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                }
            }
            Text(if (address.isNotBlank() && error != null) error else when (kind) {
                com.tang.player.core.MediaSourceKind.SMB -> "SMB 2 / 3：填写共享目录，可指定端口。账号密码加密保存。"
                com.tang.player.core.MediaSourceKind.NFS -> "NFS v3 / TCP：填写实际导出的根目录，例如 nfs://服务器/volume1/video。服务端需允许非特权源端口（insecure）。"
                else -> "HTTP / HTTPS 支持媒体与 M3U 地址；SMB / NFS 可浏览共享目录。"
            }, style = MaterialTheme.typography.bodySmall)
            if (credentialsLoading) Text("正在读取认证配置…", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClick = { submit(false) }, enabled = error == null && !credentialsLoading) { Text("保存书签") }
    }, dismissButton = {
        Row {
            TextButton(onClick = onDismiss) { Text("取消") }
            TextButton(onClick = { submit(true) }, enabled = error == null && !credentialsLoading) { Text(if (remote) "保存并连接" else "播放") }
        }
    })
}

@Composable
fun BookmarkNameDialog(title: String, initialName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName.take(200)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { name = it.take(200) }, label = { Text("书签名称") }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { onSave(name.trim()); onDismiss() }, enabled = name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
