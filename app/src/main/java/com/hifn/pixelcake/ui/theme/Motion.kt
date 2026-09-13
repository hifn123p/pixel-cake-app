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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 动效 token（`docs/UI_DESIGN.md` §5）。
 *
 * 规则：**统一缓动 + 统一时长档**，不逐处手写数字。
 * - 位移 / 缩放 / 高度类走 [springSoft]（iOS 观感）；
 * - 淡入淡出类走 [easingOut] + 时长档。
 *
 * ⚠️ 所有转场都必须先查 [reduceMotion]：系统开启「移除动画」时，
 * 位移类动画会变成一帧跳变，观感比不做动画更差。
 */
object Motion {
    /** 150ms：按下反馈、胶囊提示淡出 */
    const val fast = 150

    /** 240ms：常规转场、Tab 切换 */
    const val base = 240

    /** 320ms：面板开合、TabBar 收缩 */
    const val slow = 320

    /** 标准出场曲线（先快后慢，收尾干净） */
    val easingOut: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** 标准入场曲线 */
    val easingIn: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** 按钮 / 卡片按下时的缩放比例 */
    const val pressedScale = 0.97f

    /**
     * iOS 观感弹簧：位移、缩放、面板高度统一用它。
     * `dampingRatio = 0.8f` —— 轻微回弹但不振荡，`StiffnessMedium` 保证响应跟手。
     */
    fun <T> springSoft(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)

    /** 需要更强跟手感时用（跟手拖动、指示块滑动） */
    fun <T> springSnappy(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    /**
     * 系统是否关闭了动画（设置 → 开发者选项 / 无障碍 → 移除动画）。
     *
     * Android 上对应的开关是 `ANIMATOR_DURATION_SCALE == 0`，
     * `ValueAnimator.areAnimatorsEnabled()` 是官方推荐的读取方式（API 26+，本项目 minSdk 36）。
     */
    @Composable
    fun reduceMotion(): Boolean = remember { !ValueAnimator.areAnimatorsEnabled() }

    /**
     * 依据系统动画开关，把时长档「坍缩」成 0（即不做位移/缩放动画，只靠内容变化）。
     * 用法：`tween(Motion.durationFor(Motion.base), easing = Motion.easingOut)`。
     */
    @Composable
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
 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.pressedScale else 1f,
        animationSpec = Motion.springSnappy(),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
