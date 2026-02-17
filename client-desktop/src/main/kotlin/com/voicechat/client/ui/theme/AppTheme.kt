package com.voicechat.client.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Discord-style dark theme colors
object AppColors {
    val Background = Color(0xFF36393F)
    val Sidebar = Color(0xFF2F3136)
    val DarkBackground = Color(0xFF202225)
    val TextPrimary = Color(0xFFDCDDDE)
    val TextSecondary = Color(0xFF72767D)
    val Accent = Color(0xFF5865F2) // Blurple
    val Success = Color(0xFF3BA55C)
    val Danger = Color(0xFFED4245)
}

private val DarkColorScheme = darkColorScheme(
    primary = AppColors.Accent,
    secondary = AppColors.TextSecondary,
    background = AppColors.Background,
    surface = AppColors.Sidebar,
    onPrimary = Color.White,
    onSecondary = AppColors.TextPrimary,
    onBackground = AppColors.TextPrimary,
    onSurface = AppColors.TextPrimary,
    error = AppColors.Danger
)

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
