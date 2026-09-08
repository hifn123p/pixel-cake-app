package com.hifn.pixelcake.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Seed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E3FF),
    onPrimaryContainer = Color(0xFF1B0F4B),
    secondary = Color(0xFF5F5A66),
    onSecondary = Color.White,
    background = NeutralSurface,
    onBackground = Ink,
    surface = Color(0xFFFCFCFD),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7E2EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFFCAC4D0),
    outlineVariant = Color(0xFFE5E0E9)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCFC0FF),
    onPrimary = Color(0xFF2B1A63),
    primaryContainer = Color(0xFF41307F),
    onPrimaryContainer = Color(0xFFE9E3FF),
    secondary = Color(0xFFCBC2D4),
    onSecondary = Color(0xFF322D3A),
    background = NeutralSurfaceDark,
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF191920),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F)
)

@Composable
fun PixelCakeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
