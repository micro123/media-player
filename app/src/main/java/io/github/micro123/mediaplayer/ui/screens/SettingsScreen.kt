package io.github.micro123.mediaplayer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.micro123.mediaplayer.data.PlayerPreferences
import io.github.micro123.mediaplayer.data.VideoAspect
import io.github.micro123.mediaplayer.data.VideoOrientation
import io.github.micro123.mediaplayer.ui.components.AspectChoices
import io.github.micro123.mediaplayer.ui.components.SpeedControls
import io.github.micro123.mediaplayer.ui.components.SkipDurationControls
import io.github.micro123.mediaplayer.ui.components.OrientationChoices
import io.github.micro123.mediaplayer.ui.components.AboutSection

@Composable
fun SettingsScreen(preferences: PlayerPreferences, speed: Double, onSpeed: (Double) -> Unit, onRememberSpeed: (Boolean) -> Unit,
    onAspect: (VideoAspect) -> Unit, onAutoNext: (Boolean) -> Unit, canClear: Boolean, onClearRecent: () -> Unit,
    allFilesAccess: Boolean, onAllFilesAccess: () -> Unit,
    onSkipSeconds: (Int) -> Unit, onOrientation: (VideoOrientation) -> Unit,
    modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineLarge)
        Text("文件访问", style = MaterialTheme.typography.titleMedium)
        Text(if (allFilesAccess) "访问所有文件：已开启" else "访问所有文件：未开启")
        OutlinedButton(onClick = onAllFilesAccess) { Text(if (allFilesAccess) "管理文件访问权限" else "授权访问所有文件") }
        SpeedControls(speed, preferences.rememberSpeed, onSpeed, onRememberSpeed)
        HorizontalDivider()
        Text("视频长宽比", style = MaterialTheme.typography.titleMedium)
        AspectChoices(preferences.aspect, onAspect)
        Text("全屏播放方向", style = MaterialTheme.typography.titleMedium)
        OrientationChoices(preferences.orientation, onOrientation)
        Text("默认横屏。选择保持时，进入视频播放页保留当前屏幕方向；选择会记住。", style = MaterialTheme.typography.bodySmall)
        Text("视频始终全屏，可竖屏或横屏。返回会保存进度并停止播放；主动点小窗可继续播放。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("播放列表自动下一项", Modifier.weight(1f))
            Switch(checked = preferences.autoNext, onCheckedChange = onAutoNext)
        }
        HorizontalDivider()
        Text("跳过 OP / ED", style = MaterialTheme.typography.titleMedium)
        SkipDurationControls(preferences.skipSeconds, onSkipSeconds)
        Text("当前 ${preferences.skipSeconds} 秒，应用于所有视频；超过剩余时长会跳到结尾。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("视频手势", style = MaterialTheme.typography.titleMedium)
        Text("单击显示控制 · 双击中间播放/暂停\n横屏双击左侧 / 右侧：快退 / 快进 10 秒\n左右滑动预览进度，滑入顶部取消区域可取消\n左侧上下滑调亮度 · 右侧上下滑调音量\n长按临时 2 倍当前速度（最高 5.0 倍）")
        Text("播放进度自动保存，重新打开时继续上次位置，也可一键从头播放。")
        HorizontalDivider()
        OutlinedButton(onClick = onClearRecent, enabled = canClear, modifier = Modifier.fillMaxWidth()) { Text("清空最近打开记录") }
        Text("只移除最近列表，不删除设备文件、播放列表或观看进度。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        AboutSection()
    }
}
