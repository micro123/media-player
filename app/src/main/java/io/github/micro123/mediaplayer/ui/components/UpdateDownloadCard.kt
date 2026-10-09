package io.github.micro123.mediaplayer.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.micro123.mediaplayer.data.update.UpdateDownloadState
import kotlinx.coroutines.launch

@Composable
fun UpdateDownloadCard(state: UpdateDownloadState, onCancel: () -> Unit, onRetry: () -> Unit,
    installationIntent: (suspend () -> Intent)?) {
    if (state == UpdateDownloadState.Empty) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }
    var pendingPermission by rememberSaveable { mutableStateOf(false) }
    fun install() {
        if (installationIntent == null || installing) return
        installing = true
        scope.launch {
            try { context.startActivity(installationIntent()) }
            catch (_: ActivityNotFoundException) { Toast.makeText(context, "未找到系统安装器，下载文件已保留", Toast.LENGTH_LONG).show() }
            catch (error: Exception) { Toast.makeText(context, error.message ?: "无法打开安装器", Toast.LENGTH_LONG).show() }
            finally { installing = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingPermission) {
            pendingPermission = false
            if (context.packageManager.canRequestPackageInstalls()) install()
            else Toast.makeText(context, "未允许安装此来源的应用，下载文件已保留", Toast.LENGTH_LONG).show()
        }
    }
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("更新下载", style = MaterialTheme.typography.titleSmall)
            when (state) {
                is UpdateDownloadState.Transferring -> {
                    Text(state.task.apk.name, style = MaterialTheme.typography.bodySmall)
                    if (state.total > 0) {
                        val fraction = (state.received.toDouble() / state.total).coerceIn(0.0, 1.0).toFloat()
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text("${(fraction * 100).toInt()}% · ${Formatter.formatFileSize(context, state.received)} / ${Formatter.formatFileSize(context, state.total)}",
                            style = MaterialTheme.typography.bodySmall)
                    } else { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(Formatter.formatFileSize(context, state.received)) }
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancel) { Text("取消下载") }
                }
                is UpdateDownloadState.Verifying -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在检查安装包…") }
                is UpdateDownloadState.Ready -> {
                    Text("下载完成 · ${state.task.tag}")
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = state.canInstall && !installing && installationIntent != null, onClick = {
                            if (context.packageManager.canRequestPackageInstalls()) install()
                            else {
                                pendingPermission = true
                                try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())) }
                                catch (_: ActivityNotFoundException) {
                                    pendingPermission = false
                                    Toast.makeText(context, "无法打开安装来源设置，下载文件已保留", Toast.LENGTH_LONG).show()
                                }
                            }
                        }) { Text(if (installing) "正在打开安装器…" else "安装更新") }
                        TextButton(onClick = onCancel) { Text("删除下载") }
                    }
                }
                is UpdateDownloadState.Failed -> {
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row { TextButton(onClick = onRetry) { Text("重试下载") }; TextButton(onClick = onCancel) { Text("取消下载") } }
                }
                UpdateDownloadState.Empty -> Unit
            }
        }
    }
}
