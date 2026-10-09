package io.github.micro123.mediaplayer.ui.components

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.micro123.mediaplayer.BuildConfig
import io.github.micro123.mediaplayer.R
import io.github.micro123.mediaplayer.ui.UpdateState
import io.github.micro123.mediaplayer.data.update.GithubReleaseClient
import io.github.micro123.mediaplayer.data.update.UpdateDownloadState
import io.github.micro123.mediaplayer.data.update.UpdateDownloads

@Composable
fun AboutSection(modifier: Modifier = Modifier, updateState: UpdateState = UpdateState.Idle, onCheckUpdate: () -> Unit = {},
    downloadState: UpdateDownloadState = UpdateDownloadState.Empty, downloads: UpdateDownloads? = null) {
    val context = LocalContext.current
    val handler = LocalUriHandler.current
    fun open(url: String) {
        try { handler.openUri(url) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, "未找到可打开链接的应用", Toast.LENGTH_SHORT).show() }
        catch (_: SecurityException) { Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show() }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("关于", style = MaterialTheme.typography.titleMedium)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null, Modifier.size(64.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${if (BuildConfig.DEBUG) "调试版" else "正式版"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("基于 libmpv 的开源音视频播放器，支持本地媒体与网络音乐。", style = MaterialTheme.typography.bodyMedium)
                Text("播放内核：libmpv · mpv 0.41.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            UpdateSection(updateState, onCheckUpdate, ::open, downloadState = downloadState,
                onDownload = { apk, tag -> downloads?.start(apk, tag) }, onCancelDownload = { downloads?.cancel() },
                onRetryDownload = { downloads?.retry() }, installationIntent = downloads?.let { it::installationIntent })
            HorizontalDivider()
            ListItem(headlineContent = { Text("代码仓库") }, supportingContent = { Text("micro123/media-player") },
                trailingContent = { PlayerSymbol(PlayerIcon.OPEN_EXTERNAL, Modifier.size(20.dp)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { open(GithubReleaseClient.REPOSITORY_URL) })
            ListItem(headlineContent = { Text("开源许可") }, supportingContent = { Text("GPL-3.0") },
                trailingContent = { PlayerSymbol(PlayerIcon.OPEN_EXTERNAL, Modifier.size(20.dp)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { open("https://github.com/micro123/media-player/blob/main/LICENSE") })
            Text("源码下载与构建说明见仓库 README。", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
