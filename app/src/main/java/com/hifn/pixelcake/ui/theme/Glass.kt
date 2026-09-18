package com.hifn.pixelcake.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 一套玻璃材质的色值。
 *
 * ## 为什么 `surface` / `border` 是 [Brush] 而不是 [Color]
 *
 * 平色的半透明面板只会被读成「磨砂亚克力」—— 它没有厚度。液态玻璃之所以像液体，
 * 靠的是**明度在同一个面内自上而下变化**（顶亮底暗）+ 边缘那圈受光线。
 * 这两件事都必须用渐变表达，所以底与描边都是 [Brush]。
 *
 * 明暗两套主题的方向一致（顶亮底暗，光从上方来），调用点不需要按主题翻转。
 *
 * @param surface   渐变底（叠在下层内容之上）。降级路径传 [SolidColor] 即得到实心底
 * @param border    渐变描边，同时承担「外描边 + 顶部镜面高光 + 底部反光」
 * @param content   该材质上推荐的正文色
 * @param shadow    外投影色（含 α）。传 [Color.Transparent] 表示不投影
 * @param elevation 外投影高度。0 表示不投影（实心降级路径）
 */
@Immutable
data class GlassTint(
    val surface: Brush,
    val border: Brush,
    val content: Color,
    val shadow: Color,
    val elevation: Dp
)

/**
 * 玻璃材质 token（`docs/UI_DESIGN.md` §2.1 / §6）。
 *
 * ## ⚠️ 关键护栏：本项目**不做实时 backdrop 模糊**
 *
 * 真模糊需要把下层内容渲染进一层再采样，在大图上极贵；而修图 App 的玻璃永远浮在
 * 一张**静止的预览图**之上 —— 所以正确做法是：
 *
 * 1. 玻璃层只做「渐变底 + 边缘受光 + 外投影」（O(1)，滚动拖动零成本）；
 * 2. 需要模糊质感时，模糊交给**导入时一次性生成的静态模糊底图**（[blurredBackdrop]，UI-5 落地）。
 *
 * 这样滚动、拖动滑块、切页签时都不会触发 blur 重算 —— 这是整个改版最重要的性能约定。
 *
 * **补充（UI-6）：为什么液态观感不需要真模糊。**
 * 玻璃的「贵」八成来自高光与描边，不是模糊 —— 模糊只负责「让下层不干扰读数」。
 * 而 Compose 没有 backdrop blur：`Modifier.blur()` / `graphicsLayer { renderEffect = ... }`
 * 模糊的是**自己的内容**，不是背后的画面。把模糊加在这四类浮层上，会把浮层里的文字一起糊掉 ——
 * 那正是「方案 A 全面玻璃化」被判死的理由（读数发虚）。所以这里**刻意不引入任何真模糊**。
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

    /**
     * 浮层玻璃（液态）：渐变底 + 顶亮底暗的渐变描边 + 外投影。
     *
     * ⚠️ [Brush] 必须在 object 初始化时**建一次**就缓存住：`glassSurface` 在十几个组件里
     * 每帧被调用，若在 modifier 里现场 `Brush.verticalGradient(...)` 会每帧新建对象
     * （渐变对象不小），这正是「逐像素零分配」纪律在 UI 层的对应要求。
     */
    private val PanelLight = GlassTint(
        surface = Brush.verticalGradient(
            0f to LiquidTopLight,
            0.48f to LiquidMidLight,
            1f to LiquidBottomLight
        ),
        border = Brush.verticalGradient(
            0f to LiquidEdgeTopLight,
            1f to LiquidEdgeBottomLight
        ),
        content = Ink,
        shadow = LiquidShadowLight,
        elevation = 6.dp
    )

    private val PanelDark = GlassTint(
        surface = Brush.verticalGradient(
            0f to LiquidTopDark,
            0.48f to LiquidMidDark,
            1f to LiquidBottomDark
        ),
        border = Brush.verticalGradient(
            0f to LiquidEdgeTopDark,
            1f to LiquidEdgeBottomDark
        ),
        content = OnDarkSurface,
        shadow = LiquidShadowDark,
        elevation = 8.dp
    )

    /** 实心底（降级用）：高对比度模式、低端设备、内容密集的长列表 */
    private val OpaqueLight = GlassTint(
        surface = SolidColor(Gray1),
        border = SolidColor(GlassBorderLightOpaque),
        content = Ink,
        shadow = Color.Transparent,
        elevation = 0.dp
    )

    private val OpaqueDark = GlassTint(
        surface = SolidColor(ContainerDarkHigh),
        border = SolidColor(GlassBorderDark),
        content = OnDarkSurface,
        shadow = Color.Transparent,
        elevation = 0.dp
    )

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
 * 把玻璃材质应用到任意组件：外投影 + 裁剪 + 渐变底 + 渐变描边。
 *
 * 这是「无边框分层」的载体 —— 层次靠**底色差 + 边缘受光**表达，而不是靠粗边框分割线。
 *
 * 绘制顺序不能调：投影必须在最外层（先画的在最底下），否则会被自己的底盖住。
 */
fun Modifier.glassSurface(
    tint: GlassTint,
    shape: Shape = Radius.card,
    borderWidth: Dp = Glass.borderWidth
): Modifier = this
    .shadow(
        elevation = tint.elevation,
        shape = shape,
        clip = false,
        ambientColor = tint.shadow,
        spotColor = tint.shadow
    )
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

// ———————————————————————————————————————————————————————————————
// 分段控件（TabBar / 一级工具条）
// ———————————————————————————————————————————————————————————————

/**
 * 分段控件的**选中指示块**材质。
 *
 * 从「强调色平色块」改成「白渐变 + 顶部亮线 + 投影」：平色块读起来是「一块高亮」，
 * 白色渐变块读起来是「一个被光打到的实体」—— 这是「像不像 iOS」性价比最高的两笔之一
 * （另一笔是 `ParamSlider` 的白色滑块）。
 *
 * ## 抽成共享 modifier 的直接原因
 *
 * `AppShell.GlassTabBar` 与 `GlassSegmentedBar` 此前各写了一遍同样的指示块
 * （审计 L3 记的就是这两套近似实现），改一处必漏另一处 —— 收敛到这里之后，
 * 两者只差外部修饰符。
 *
 * ⚠️ 浅色主题下**不能用白**（白底白块 = 不可见）⇒ 退回强调色淡染，只保留顶部亮线；
 * 深色判定必须走 [LocalDarkTheme]，不能按系统 `uiMode` 猜。
 */
@Composable
fun Modifier.segmentIndicator(shape: Shape = Radius.pill): Modifier =
    if (LocalDarkTheme.current) {
        this
            .shadow(
                elevation = 3.dp,
                shape = shape,
                clip = false,
                ambientColor = SegmentShadowDark,
                spotColor = SegmentShadowDark
            )
            .background(
                Brush.verticalGradient(
                    0f to SegmentFillTopDark,
                    1f to SegmentFillBottomDark
                ),
                shape
            )
            .border(Glass.borderWidth, SolidColor(SegmentEdgeDark), shape)
    } else {
        val accent = MaterialTheme.colorScheme.primary
        this
            .shadow(
                elevation = 2.dp,
                shape = shape,
                clip = false,
                ambientColor = SegmentShadowLight,
                spotColor = SegmentShadowLight
            )
            .background(accent.copy(alpha = 0.18f), shape)
            .border(Glass.borderWidth, SolidColor(SegmentEdgeLight), shape)
    }
