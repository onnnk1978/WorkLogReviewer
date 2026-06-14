package com.example.worklogreviewer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    background = Color.Black,
    surface = Color(0xFF1A1A1A),
    onBackground = Color.White,
    onSurface = Color.White,
    primary = Color(0xFF9B8AFB),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3D3560),
    onPrimaryContainer = Color.White,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFCCCCCC),
    outline = Color(0xFF666666)
)

@Composable
fun WorkLogReviewerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}