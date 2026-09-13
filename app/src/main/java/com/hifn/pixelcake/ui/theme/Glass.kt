package com.hifn.pixelcake.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
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
 */
object Glass {
    /** 高光描边宽度。1px 在两个主题下都成立 */
    val borderWidth: Dp = 1.dp

    /** 浮层玻璃（半透明）：顶栏、悬浮工具条、底部 Sheet */
    private val PanelLight = GlassTint(GlassTintLight, GlassBorderLight, Ink)
    private val PanelDark = GlassTint(GlassTintDark, GlassBorderDark, OnDarkSurface)

    /** 实心底（降级用）：高对比度模式、低端设备、内容密集的长列表 */
    private val OpaqueLight = GlassTint(Gray1, GlassBorderLightOpaque, Ink)
    private val OpaqueDark = GlassTint(WorkspaceSurfaceHigh, GlassBorderDark, OnDarkSurface)

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

/** 按当前系统主题取玻璃色值。应用内开关打开时一律降级为实心底。 */
@Composable
fun rememberGlassTint(opaque: Boolean = false): GlassTint =
    Glass.of(isSystemInDarkTheme(), opaque || LocalLowTransparency.current)

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
