package com.hifn.pixelcake.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * 浅色普通主题（首页 / 设置）。
 *
 * `background` 用比卡片暗一档的灰（[NeutralSurface]），卡片用 [ContainerLight]，
 * 两者相差约 11 级明度 —— 卡片不再靠玻璃半透明分层，而是靠**实心底色差 + 极淡描边**
 * （见 [containerSurface]）。
 */
private val LightColors = lightColorScheme(
    primary = Seed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E3FF),
    onPrimaryContainer = Color(0xFF1B0F4B),
    secondary = Color(0xFF5F5A66),
    onSecondary = Color.White,
    background = NeutralSurface,
    onBackground = Ink,
    surface = ContainerLight,
    onSurface = Ink,
    surfaceVariant = ContainerLightLow,
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFFCAC4D0),
    outlineVariant = Color(0xFFE5E0E9)
)

/**
 * 深色普通主题。
 *
 * ⚠️ `primary` 用 [SeedOnDark]（而非 [Seed]）：[Seed] 在深底上的对比度只有 4.11 : 1，
 * 达不到 WCAG AA 的正文标准；[SeedOnDark] 是它降饱和 ~13% + 提亮后的派生色，对比度 6.04 : 1。
 */
private val DarkColors = darkColorScheme(
    primary = SeedOnDark,
    onPrimary = Color(0xFF2B1A63),
    primaryContainer = Color(0xFF41307F),
    onPrimaryContainer = Color(0xFFE9E3FF),
    secondary = Color(0xFFCBC2D4),
    onSecondary = Color(0xFF322D3A),
    background = NeutralSurfaceDark,
    onBackground = OnDarkSurface,
    surface = ContainerDark,
    onSurface = OnDarkSurface,
    surfaceVariant = ContainerDarkHigh,
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F)
)

@Composable
fun PixelCakeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 同时下发 LocalDarkTheme：容器色/玻璃色必须跟随**生效**主题，不能各自按系统 uiMode 猜
    // （编辑页强制深色时，系统浅色会让它们取错色板 —— 详见 LocalDarkTheme 的文档）。
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography,
            content = content
        )
    }
}

/**
 * 工作台配色（编辑页专用，**恒为深色，不跟随系统**）。
 *
 * 为什么不跟随系统：调色界面若在浅色下，界面自身的亮度会干扰用户对照片明暗与色彩的判断
 * （浅底会让照片显得偏暗，用户会不自觉地调过曝）。Snapseed / Lightroom / VSCO 的编辑页
 * 都是深色 —— 这是行业共识而非个人偏好。见 `docs/UI_DESIGN.md` §8 决策点 1。
 *
 * `primary` 同样用 [SeedOnDark]：编辑页是深色场景，强调色必须走降饱和版本，
 * 否则选中态会既刺眼又对比度不足。
 */
private val WorkspaceColors = darkColorScheme(
    primary = SeedOnDark,
    onPrimary = Color(0xFF2B1A63),
    primaryContainer = Color(0xFF41307F),
    onPrimaryContainer = Color(0xFFE9E3FF),
    secondary = Color(0xFFCBC2D4),
    onSecondary = Color(0xFF322D3A),
    background = WorkspaceBg,
    onBackground = OnDarkSurface,
    surface = ContainerDark,
    onSurface = OnDarkSurface,
    surfaceVariant = ContainerDarkHigh,
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF605C66),
    outlineVariant = Color(0xFF33313A)
)

/**
 * 把内容强制套进工作台深色配色。
 *
 * 用法：编辑页整体包一层 —— `PixelCakeWorkspaceTheme { EditorScreen(...) }`。
 * 嵌套 `MaterialTheme` 是官方支持的用法，外层主题与其它页面不受影响。
 *
 * 这里**必须**同时把 `LocalDarkTheme` 置为 `true`：换 `colorScheme` 并不会改变系统的 `uiMode`，
 * 而容器色 / 玻璃色是按「生效主题」解析的 —— 漏了这一句，系统处于浅色模式时编辑页会拿到
 * 浅色色板（一块近白的参数面板压在深色工作台上）。见 [LocalDarkTheme]。
 */
@Composable
fun PixelCakeWorkspaceTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDarkTheme provides true) {
        MaterialTheme(
            colorScheme = WorkspaceColors,
            typography = Typography,
            content = content
        )
    }
}
