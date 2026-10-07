package com.tang.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PlayerColors = darkColorScheme(
    primary = Color(0xFFB4E38C),
    onPrimary = Color(0xFF203510),
    primaryContainer = Color(0xFF304822),
    onPrimaryContainer = Color(0xFFD0F5AF),
    secondary = Color(0xFFBCCBB0),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE1E3E5),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE1E3E5),
    surfaceVariant = Color(0xFF26302A),
    onSurfaceVariant = Color(0xFFBEC8BE),
)

@Composable
fun LocalPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PlayerColors, content = content)
}

private val VideoColors = darkColorScheme(
    primary = Color(0xFFCEC3FF),
    onPrimary = Color(0xFF241C42),
    primaryContainer = Color(0xFF37304D),
    onPrimaryContainer = Color(0xFFE9E1FF),
    secondary = Color(0xFFC9C3D5),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF17151C),
    onSurface = Color(0xFFF4F0F7),
    surfaceContainer = Color(0xFF211E27),
    surfaceVariant = Color(0xFF34303E),
    onSurfaceVariant = Color(0xFFC9C3D1),
)

@Composable
fun VideoPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VideoColors, content = content)
}
