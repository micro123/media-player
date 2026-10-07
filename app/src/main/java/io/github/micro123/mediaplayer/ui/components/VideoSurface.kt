package io.github.micro123.mediaplayer.ui.components

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun VideoSurface(onSurface: (Surface?) -> Unit, onSize: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context -> PlaybackSurfaceView(context).apply {
            surfaceListener = onSurface
            sizeListener = onSize
        } },
        update = { it.surfaceListener = onSurface; it.sizeListener = onSize },
        onRelease = { it.detach() },
    )
}

private class PlaybackSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    var surfaceListener: (Surface?) -> Unit = {}
    var sizeListener: (Int, Int) -> Unit = { _, _ -> }
    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = surfaceListener(holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = sizeListener(width, height)
    override fun surfaceDestroyed(holder: SurfaceHolder) = surfaceListener(null)

    fun detach() {
        surfaceListener(null)
        holder.removeCallback(this)
    }
}
