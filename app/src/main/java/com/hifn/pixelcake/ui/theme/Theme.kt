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

/**
 * 工作台配色（编辑页专用，**恒为深色，不跟随系统**）。
 *
 * 为什么不跟随系统：调色界面若在浅色下，界面自身的亮度会干扰用户对照片明暗与色彩的判断
 * （浅底会让照片显得偏暗，用户会不自觉地调过曝）。Snapseed / Lightroom / VSCO 的编辑页
 * 都是深色 —— 这是行业共识而非个人偏好。见 `docs/UI_DESIGN.md` §8 决策点 1。
 */
private val WorkspaceColors = darkColorScheme(
    primary = Color(0xFFCFC0FF),
    onPrimary = Color(0xFF2B1A63),
    primaryContainer = Color(0xFF41307F),
    onPrimaryContainer = Color(0xFFE9E3FF),
    secondary = Color(0xFFCBC2D4),
    onSecondary = Color(0xFF322D3A),
    background = WorkspaceBg,
    onBackground = OnDarkSurface,
    surface = WorkspaceSurface,
    onSurface = OnDarkSurface,
    surfaceVariant = WorkspaceSurfaceHigh,
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF605C66),
    outlineVariant = Color(0xFF33313A)
)

/**
 * 把内容强制套进工作台深色配色。
 *
 * 用法：编辑页整体包一层 —— `PixelCakeWorkspaceTheme { EditorScreen(...) }`。
 * 嵌套 `MaterialTheme` 是官方支持的用法，外层主题与其它页面不受影响。
 */
@Composable
fun PixelCakeWorkspaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WorkspaceColors,
        typography = Typography,
        content = content
    )
}
