package com.voicechat.client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AppColors {
    // Backgrounds — deep blue-black palette
    val Background  = Color(0xFF0D0D14)
    val Surface1    = Color(0xFF13131F)
    val Surface2    = Color(0xFF1A1A28)
    val Surface3    = Color(0xFF222236)
    val Border      = Color(0xFF2A2A42)
    val BorderLight = Color(0xFF363655)

    // Accent — indigo/violet
    val Accent      = Color(0xFF6366F1)
    val AccentLight = Color(0xFF818CF8)
    val AccentDim   = Color(0x336366F1)

    // States
    val Success     = Color(0xFF10B981)
    val SuccessDim  = Color(0x2210B981)
    val Danger      = Color(0xFFF43F5E)
    val DangerDim   = Color(0x22F43F5E)

    // Text
    val TextPrimary   = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted     = Color(0xFF475569)
}

private val DarkColorScheme = darkColorScheme(
    primary      = AppColors.Accent,
    secondary    = AppColors.AccentLight,
    background   = AppColors.Background,
    surface      = AppColors.Surface1,
    onPrimary    = Color.White,
    onSecondary  = AppColors.TextPrimary,
    onBackground = AppColors.TextPrimary,
    onSurface    = AppColors.TextPrimary,
    error        = AppColors.Danger,
    outline      = AppColors.Border,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, content = content)
}
