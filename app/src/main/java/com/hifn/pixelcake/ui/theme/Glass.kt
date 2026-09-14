package com.hifn.pixelcake.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 一套玻璃材质的三个色值。
 *
 * @param surface 半透明底（叠在下层内容之上）
 * @param border  高光描边（模拟玻璃边缘受光）
 * @param content 该材质上推荐的正文色
 */
@Immutable
data class GlassTint(
    val surface: Color,
    val border: Color,
    val content: Color
)

/**
 * 玻璃材质 token（`docs/UI_DESIGN.md` §2.1 / §6）。
 *
 * ## ⚠️ 关键护栏：本项目**不做实时 backdrop 模糊**
 *
 * 真模糊需要把下层内容渲染进一层再采样，在大图上极贵；而修图 App 的玻璃永远浮在
 * 一张**静止的预览图**之上 —— 所以正确做法是：
 *
 * 1. 玻璃层只做「半透明 + 1px 高光描边」（O(1)，滚动拖动零成本）；
 * 2. 需要模糊质感时，模糊交给**导入时一次性生成的静态模糊底图**（UI-5 性能阶段落地）。
 *
 * 这样滚动、拖动滑块、切页签时都不会触发 blur 重算 —— 这是整个改版最重要的性能约定。
 *
 * ## 用途已被收敛（§1.6）：玻璃 = **浮层专用**，不是整套设计语言
 *
 * 只允许用在：底部 TabBar、一级工具条、悬浮图标按钮、胶囊提示、首屏展示位、底部 Sheet。
 * 页面内的**承载型容器**（设置页分组卡、参数面板）一律改用 [ContainerLevel] 的实心色
 * —— 见 [containerSurface]。判据是「它是否浮在别的内容之上」：不浮，就不要玻璃。
 */
object Glass {
    /** 高光描边宽度。1px 在两个主题下都成立 */
    val borderWidth: Dp = 1.dp

    /** 浮层玻璃（半透明）：顶栏、悬浮工具条、底部 Sheet */
    private val PanelLight = GlassTint(GlassTintLight, GlassBorderLight, Ink)
    private val PanelDark = GlassTint(GlassTintDark, GlassBorderDark, OnDarkSurface)

    /** 实心底（降级用）：高对比度模式、低端设备、内容密集的长列表 */
    private val OpaqueLight = GlassTint(Gray1, GlassBorderLightOpaque, Ink)
    private val OpaqueDark = GlassTint(ContainerDarkHigh, GlassBorderDark, OnDarkSurface)

    /**
     * 取一组玻璃色值。
     *
     * @param dark   是否暗色主题
     * @param opaque 是否降级为实心（高对比度 / 长列表 / 性能兜底）
     */
    fun of(dark: Boolean, opaque: Boolean = false): GlassTint = when {
        opaque && dark -> OpaqueDark
        opaque -> OpaqueLight
        dark -> PanelDark
        else -> PanelLight
    }
}

/**
 * 应用内「降低透明度」开关（设置 → 外观）。
 *
 * Android **没有** iOS 那种系统级「降低透明度」开关（详见 `docs/UI_DESIGN.md` §6 的更正），
 * 所以只能由应用自己提供。用 CompositionLocal 而不是逐层传参：玻璃色值在十几个组件里被解析，
 * 逐层透传会把参数列表污染得很难看，而这是一个「全局观感」开关，语义上就是环境值。
 *
 * 默认 `false`：即使调用方忘了 provide 也是正常观感，不会退化。
 */
val LocalLowTransparency = staticCompositionLocalOf { false }

/**
 * 当前**生效**主题是否为深色（由 `PixelCakeTheme` / `PixelCakeWorkspaceTheme` 提供）。
 *
 * ## ⚠️ 为什么不能用 `isSystemInDarkTheme()`
 *
 * 编辑页用 [PixelCakeWorkspaceTheme] **强制深色**，而强制换 `colorScheme` **不会**改变系统的
 * `uiMode` —— `isSystemInDarkTheme()` 读的是 `LocalConfiguration`。于是系统处于浅色模式时：
 * 编辑页明明是深色工作台，这里却拿到 `false`，容器色/玻璃色会取**浅色**那一套
 * ⇒ 一块近白的参数面板压在深色工作台上（同类 bug 的上一代形态是「照片周围一圈浅色」，
 * 见 `EditorScreen` 自己铺底那段注释）。
 *
 * 所以「深色与否」必须由**主题层显式下发**，而不是各处按系统猜。默认 `false`
 * （浅色）保持原 `isSystemInDarkTheme()` 的默认语义，且两个主题包装都必然 provide。
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** 按当前**生效**主题取玻璃色值。应用内开关打开时一律降级为实心底。 */
@Composable
fun rememberGlassTint(opaque: Boolean = false): GlassTint =
    Glass.of(LocalDarkTheme.current, opaque || LocalLowTransparency.current)

/**
 * 把玻璃材质应用到任意组件：裁剪 + 半透明底 + 高光描边。
 *
 * 这是「无边框分层」的载体 —— 层次靠**底色差 + 描边受光**表达，而不是靠粗边框分割线。
 */
fun Modifier.glassSurface(
    tint: GlassTint,
    shape: Shape = Radius.card,
    borderWidth: Dp = Glass.borderWidth
): Modifier = this
    .clip(shape)
    .background(tint.surface, shape)
    .border(borderWidth, tint.border, shape)

/** 便捷重载：自行按系统主题解析色值。 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = Radius.card,
    opaque: Boolean = false,
    borderWidth: Dp = Glass.borderWidth
): Modifier = this.glassSurface(rememberGlassTint(opaque), shape, borderWidth)

// ———————————————————————————————————————————————————————————————
// 实心容器（玻璃的「另一半」）
// ———————————————————————————————————————————————————————————————

/**
 * 实心容器层级（`Color.kt` 容器色阶的语义入口）。
 *
 * **档位越高 = 越亮 = 越「浮」**，明暗主题方向一致。
 * 页面内绝大多数卡片用默认的 [Container]；凹陷区（输入槽、进度槽）用 [Lowest] / [Low]，
 * 浮起控件（选中 chip、浮动条）用 [High] / [Highest]。
 */
enum class ContainerLevel { Lowest, Low, Container, High, Highest }

/** 按当前**生效**主题解析容器层级对应的实心色（不能用系统 `uiMode`，理由见 [LocalDarkTheme]）。 */
@Composable
fun ContainerLevel.containerColor(): Color {
    val dark = LocalDarkTheme.current
    return when (this) {
        ContainerLevel.Lowest -> if (dark) ContainerDarkLowest else ContainerLightLowest
        ContainerLevel.Low -> if (dark) ContainerDarkLow else ContainerLightLow
        ContainerLevel.Container -> if (dark) ContainerDark else ContainerLight
        ContainerLevel.High -> if (dark) ContainerDarkHigh else ContainerLightHigh
        ContainerLevel.Highest -> if (dark) ContainerDarkHighest else ContainerLightHighest
    }
}

/**
 * 把实心容器底应用到任意组件：裁剪 + 实心底 + 极淡描边。
 *
 * 与 [glassSurface] **同形**（参数顺序与默认值对齐），调用方切换材质只要换函数名 ——
 * 这是刻意收的口子，让「玻璃 → 实心」的收敛成本保持在「改一个 token」的量级。
 *
 * 描边刻意比玻璃的**高光**更弱：实心容器没有「边缘受光」的物理依据，
 * 这 1px 线只在明度接近时**兜底分割**，不是装饰 —— 加粗或加深就会退化成 §2.2 批评的
 * 「到处描边框」。
 */
@Composable
fun Modifier.containerSurface(
    shape: Shape = Radius.card,
    level: ContainerLevel = ContainerLevel.Container,
    borderWidth: Dp = 1.dp
): Modifier {
    val fill = level.containerColor()
    val border = if (LocalDarkTheme.current) ContainerBorderDark else ContainerBorderLight
    return this
        .clip(shape)
        .background(fill, shape)
        .border(borderWidth, border, shape)
}
