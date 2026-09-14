package com.hifn.pixelcake.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 动效 token（`docs/UI_DESIGN.md` §5）。
 *
 * ## 一、弹簧按属性分族（Material 3 Expressive 口径）
 *
 * 曲线**不能按「快 / 慢」挑，要按「动的是什么属性」挑**。这是 M3E 最容易被忽略、
 * 也最能一眼看出专业度的一条：
 *
 * | 动的属性 | 用哪个 | 阻尼比 | 理由 |
 * |---|---|---|---|
 * | 位移 / 尺寸 / 形状 | [springSpatialFast] / [springSpatial] | 0.6 | 物体有质量，到位时轻微回弹才像实体 |
 * | 颜色 | [springEffects] | 1.0（临界阻尼） | 回弹会让颜色越过目标色再弹回，看着发脏 |
 * | 透明度 | `tween` + [durationFor] | —— | 淡入淡出要**时长确定**，且必须能被无障碍开关坍缩 |
 *
 * ⚠️ **绝不要把回弹弹簧用在透明度上**：alpha 越过 1 之后被裁掉，观感是「闪一下」，
 * 比完全不做动画更糟。这就是「分族」存在的全部理由 —— 不是风格问题，是对错问题。
 *
 * 空间族按**受影响的范围**分 2 档：控件内部的小位移（按下缩放、数值放大）用
 * [springSpatialFast]，把更多控制权交给手指；跨格位/容器级的位移（指示块滑格、
 * 整页轻微缩放）用 [springSpatial]。
 *
 * 刻意**没有**第 3 档「大幅整屏位移」：本 App 的编辑器是硬切进入的（不套动画，
 * 因为退出时会立刻回收源位图，套转场会有「画到已回收 Bitmap」的风险），
 * 没有任何调用点需要它 —— 一个没人用的档位只会让下一个人选错。
 *
 * ## 二、系统「移除动画」开关（无障碍 → 移除动画 / `ANIMATOR_DURATION_SCALE == 0`）
 *
 * - **弹簧天然支持**：Compose 会把 `MotionDurationScale` 一起作用到弹簧的播放时间上
 *   （scale 为 0 时直接落到终值），所以弹簧调用点**不需要**任何额外处理；
 * - **`tween` 必须自己坍缩**：一律写成 `tween(Motion.durationFor(Motion.base), easing = ...)`。
 *   漏掉 [durationFor] 就会出现「系统关了动画、App 还在动」。
 *
 * 硬约定：本文件历史上出现过 7 处漏写 [durationFor] 的 `tween`，随本次改造一并修掉。
 */
object Motion {
    /** 150ms：按下反馈、拖动时隐藏 chrome 等短 `tween` 档 */
    const val fast = 150

    /** 240ms：常规 `tween` 转场档（分类 Crossfade、Tab 换页） */
    const val base = 240

    /** 320ms：胶囊提示这类「停留后自行退场」的慢 `tween` 档 */
    const val slow = 320

    /** 标准出场曲线（先快后慢，收尾干净） */
    val easingOut: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** 标准入场曲线 */
    val easingIn: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** 按钮 / 卡片按下时的缩放比例 */
    const val pressedScale = 0.97f

    /** 空间族阻尼比：0.6f ＝「看得出回弹、但不会来回振荡」的经验值 */
    private const val SpatialDamping = 0.6f

    /** 效果族阻尼比：1f ＝ 临界阻尼，绝不越过目标值 */
    private const val EffectsDamping = 1f

    // —————————————— 空间族：位移 / 尺寸 / 形状 ——————————————

    /** 小位移（≤ 1 个控件尺寸）：按下缩放、数值放大。最硬、最跟手。 */
    fun <T> springSpatialFast(): FiniteAnimationSpec<T> =
        spring(dampingRatio = SpatialDamping, stiffness = Spring.StiffnessMediumLow)

    /** 常规位移：指示块滑格、整页轻微缩放。 */
    fun <T> springSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = SpatialDamping, stiffness = Spring.StiffnessMedium)

    // —————————————— 效果族：颜色 ——————————————

    /**
     * 颜色专用。**临界阻尼，不回弹** —— 颜色回弹会越过目标色再弹回，看着发脏。
     */
    fun <T> springEffects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = EffectsDamping, stiffness = Spring.StiffnessMediumLow)

    /**
     * 系统是否关闭了动画（设置 → 开发者选项 / 无障碍 → 移除动画）。
     *
     * Android 上对应的开关是 `ANIMATOR_DURATION_SCALE == 0`，
     * `ValueAnimator.areAnimatorsEnabled()` 是官方推荐的读取方式（API 26+，本项目 minSdk 36）。
     *
     * ⚠️ **刻意不加 `@Composable`**：`areAnimatorsEnabled()` 是一次静态读取，不需要合成作用域；
     * 而 `tween(Motion.durationFor(...))` 必须能在**非合成**上下文（如 `AnimatedContent` 的
     * `transitionSpec` lambda）里调用 —— 标成 `@Composable` 会让那些调用点直接编译失败。
     */
    fun reduceMotion(): Boolean = !ValueAnimator.areAnimatorsEnabled()

    /**
     * 依据系统动画开关，把时长档「坍缩」成 0（即不做 `tween`，直接落到终值）。
     *
     * 用法：`tween(Motion.durationFor(Motion.base), easing = Motion.easingOut)`。
     * ⚠️ 弹簧调用点**不需要**它 —— 弹簧自己就知道系统关了动画。
     *
     * 同样**不加 `@Composable`**：合成内与 `transitionSpec` 等非合成上下文都要能用。
     */
    fun durationFor(ms: Int): Int = if (reduceMotion()) 0 else ms
}

/**
 * 按下时轻微缩到 [Motion.pressedScale]（0.97）。
 *
 * 为什么不用 `Modifier.clickable` 的默认涟漪：涟漪是「覆盖在内容上的一层颜色」，
 * 而缩放在视觉上是「这个东西被按了进去」—— 后者更接近实体按键的心理模型，
 * 也是 iOS 味的关键一笔。两者叠加会显得脏，所以调用点请传 `indication = null`。
 *
 * 只作用于**大块可点区域**（列表项、动作卡）。给 chip / 图标按钮加缩放会显得抖。
 *
 * 缩放是**空间属性** → 用空间族最硬的一档（[Motion.springSpatialFast]），
 * 保证「按下去」的反馈跟手。
 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.pressedScale else 1f,
        animationSpec = Motion.springSpatialFast(),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
