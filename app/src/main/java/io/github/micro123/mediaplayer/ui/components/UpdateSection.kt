package io.github.micro123.mediaplayer.ui.components

import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.micro123.mediaplayer.BuildConfig
import io.github.micro123.mediaplayer.data.update.GithubReleaseClient
import io.github.micro123.mediaplayer.data.update.ReleaseVersion
import io.github.micro123.mediaplayer.data.update.ReleaseApk
import io.github.micro123.mediaplayer.data.update.UpdateDownloadState
import android.content.Intent
import io.github.micro123.mediaplayer.ui.UpdateState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun UpdateSection(state: UpdateState, onCheck: () -> Unit, onOpen: (String) -> Unit,
    currentVersion: String = BuildConfig.VERSION_NAME, supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(), debugBuild: Boolean = BuildConfig.DEBUG,
    downloadState: UpdateDownloadState = UpdateDownloadState.Empty, onDownload: (ReleaseApk, String) -> Unit = { _, _ -> },
    onCancelDownload: () -> Unit = {}, onRetryDownload: () -> Unit = {}, installationIntent: (suspend () -> Intent)? = null) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val release = (state as? UpdateState.Checked)?.release
    val comparison = release?.version?.let { latest -> ReleaseVersion.parse(currentVersion)?.let { latest.compareTo(it) } }
    val status = when (state) {
        UpdateState.Idle -> "从 GitHub 检查最新正式版本"
        UpdateState.Checking -> "正在检查更新…"
        is UpdateState.Failed -> state.message
        is UpdateState.Checked -> when {
            comparison == null -> "最新发布 ${state.release.tag}，点击查看"
            comparison > 0 -> "发现新版本 ${state.release.tag}"
            comparison == 0 -> "当前已是最新版本 · ${state.release.tag}"
            else -> "当前版本高于已发布版本 · ${state.release.tag}"
        }
    }
    ListItem(headlineContent = { Text("检查更新") }, supportingContent = { Text(status) },
        trailingContent = {
            if (state == UpdateState.Checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else PlayerSymbol(PlayerIcon.REPLAY, Modifier.size(20.dp))
        }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { showDialog = true; onCheck() })
    if (!showDialog) UpdateDownloadCard(downloadState, onCancelDownload, onRetryDownload, installationIntent)

    if (showDialog) {
        val context = LocalContext.current
        val apk = release?.preferredApk(supportedAbis)
        AlertDialog(onDismissRequest = { showDialog = false }, title = {
            Text(when {
                state == UpdateState.Checking -> "检查更新"
                state is UpdateState.Failed -> "检查更新失败"
                comparison == null -> "发布版本"
                comparison > 0 -> "发现新版本"
                comparison == 0 -> "已是最新版本"
                else -> "当前版本高于已发布版本"
            })
        }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UpdateDownloadCard(downloadState, onCancelDownload, onRetryDownload, installationIntent)
                Text("当前版本：$currentVersion")
                when (state) {
                    UpdateState.Checking -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在连接 GitHub，请稍候…") }
                    is UpdateState.Failed -> Text(state.message)
                    is UpdateState.Checked -> {
                        Text("最新发布：${state.release.tag}", fontWeight = FontWeight.Medium)
                        val date = runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
                            .format(Instant.parse(state.release.publishedAt)) }.getOrNull()
                        if (date != null) Text("发布时间：$date", style = MaterialTheme.typography.bodySmall)
                        if (comparison != null && comparison > 0) {
                            if (apk != null) {
                                Text("适合此设备：${apk.name}", style = MaterialTheme.typography.bodySmall)
                                if (apk.sizeBytes > 0) Text("下载大小：${Formatter.formatFileSize(context, apk.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                            } else Text(if (state.release.metadataComplete) "暂未提供适合此设备的 APK，可打开发布页面查看。"
                                else "已确认新版本，请在发布页面查看更新说明并下载安装包。", style = MaterialTheme.typography.bodySmall)
                            if (debugBuild) Text("当前是调试版，GitHub 正式包签名不同，无法直接覆盖安装。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            Text("APK 在应用内下载，不需要浏览器。下载完成后可打开系统安装器，由你确认安装。", style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                        Text("更新说明", style = MaterialTheme.typography.titleSmall)
                        Text(state.release.notes.ifBlank { "此版本未提供更新说明，可前往发布页面查看。" }, style = MaterialTheme.typography.bodySmall)
                    }
                    UpdateState.Idle -> Text("点击重试检查 GitHub 最新正式版本。")
                }
                if (state != UpdateState.Checking) TextButton(onClick = { onOpen(release?.pageUrl ?: GithubReleaseClient.RELEASES_URL) }) { Text("查看发布页面") }
            }
        }, confirmButton = {
            when {
                state is UpdateState.Failed || state == UpdateState.Idle -> TextButton(onClick = onCheck) { Text("重试") }
                comparison != null && comparison > 0 && apk != null && downloadState == UpdateDownloadState.Empty ->
                    TextButton(onClick = { onDownload(apk, release.tag) }) { Text("下载 APK") }
            }
        }, dismissButton = { TextButton(onClick = { showDialog = false }) { Text("关闭") } })
    }
}
