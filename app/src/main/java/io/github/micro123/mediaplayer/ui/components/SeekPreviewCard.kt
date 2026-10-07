package io.github.micro123.mediaplayer.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.micro123.mediaplayer.ui.VideoSeekPreview

@Composable
fun SeekPreviewCard(preview: VideoSeekPreview, thumbnail: Bitmap?, duration: Long) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = minOf(180.dp, maxWidth)
        val fraction = if (duration > 0) preview.targetPositionMs.toFloat() / duration else 0f
        val left = ((maxWidth - cardWidth) * fraction.coerceIn(0f, 1f))
        Surface(Modifier.offset(x = left).width(cardWidth).semantics { contentDescription = "进度预览 ${formatTime(preview.targetPositionMs)}" },
            color = Color(0xEE222029), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (thumbnail != null && !preview.cancelled) Image(thumbnail.asImageBitmap(), "本地视频画面预览",
                    Modifier.fillMaxWidth().height(86.dp), contentScale = ContentScale.Fit)
                Text(if (preview.cancelled) "松手取消跳转" else formatTime(preview.targetPositionMs), color = Color.White)
                Text(if (preview.cancelled) "移回画面继续选择" else "滑到顶部取消 · 松手跳转", color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
