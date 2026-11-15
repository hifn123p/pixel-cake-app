package com.hifn.pixelcake.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.ui.theme.LocalDarkTheme
import com.hifn.pixelcake.ui.theme.Motion
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.SliderThumbShadow
import com.hifn.pixelcake.ui.theme.SliderTrackDark
import com.hifn.pixelcake.ui.theme.SliderTrackLight
import com.hifn.pixelcake.ui.theme.Spacing
import kotlin.math.round

/**
 * 参数滑块（`docs/UI_DESIGN.md` §4.3：带数值气泡）。
 *
 * ## 触控热区 48dp（v0.7.3，所有人对 §9.6-Q4 的最终裁决）
 *
 * 旧版把 `LocalMinimumInteractiveComponentSize` 压掉、滑杆交互面只有 26dp —— 「把视觉规范
 * 当交互规范执行」，命中面积砍 46%。本版改为**单层 48dp 盒**：标签/数值行叠布在盒内上半部，
 * Slider 铺满整盒（视觉轨道下移 ≈3dp，肉眼不可辨），触控面 26→48dp 且调用点零改动
 * （Deck 高度预算净 0：旧「标签行 22 + 滑杆 26」≈48，新单盒 48）。
 *
 * ## 自绘轨道（§03 `.sl-track` + `.sl-zero`）
 *
 * 3dp 线（未选段 α0.13 / 选中段琥珀）+ 两端圆帽；**双极参数（量程跨 0）在 50% 处画
 * 1.5×10dp 零点刻度** —— 这是规范明文而实现一直欠着的 `.sl-zero`（§19.4-C9 清账）。
 * M3 默认轨道右端的「停点圆点」一并消失（mock 没有这个元素）。
 *
 * ## 拖动状态上报（[onDraggingChange]）
 *
 * 拖动中回调 `true`，松手回调 `false`。上层据此临时隐藏非参数 UI。撤销栈只在
 * `onValueChangeFinished` 入栈一次（F08）。
 *
 * @param step 量化步长；传 0 表示不量化（连续）。量化在**回调前**完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 0f,
    format: (Float) -> String = { "%.2f".format(it) },
    onDraggingChange: (Boolean) -> Unit = {},
    enabled: Boolean = true
) {
    var dragging by remember { mutableStateOf(false) }
    // ⚠️ CompositionLocal.current 只能在组合期读取 —— 拖动回调是非组合 lambda，
    // 必须在这里先把上报函数捕获成局部量。
    val reportHud = LocalHudReporter.current
    // 拖动途中本组合被销毁（第二根手指点了未 inert 的 chip ⇒ 面板内容切换）时，
    // M3 Slider **不会**回调 finished ⇒ 上层 `dragging` 永远收不到 false。onDispose 兜底。
    DisposableEffect(Unit) {
        onDispose {
            if (dragging) {
                dragging = false
                onDraggingChange(false)
            }
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val dark = LocalDarkTheme.current

    val valueColor by animateColorAsState(
        targetValue = if (dragging) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = Motion.springEffects(),
        label = "paramValueColor"
    )
    val valueScale by animateFloatAsState(
        targetValue = if (dragging) 1.12f else 1f,
        animationSpec = Motion.springSpatialFast(),
        label = "paramValueScale"
    )

    val thumbColor = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val trackInactive = if (dark) SliderTrackDark else SliderTrackLight
    val interaction = remember { MutableInteractionSource() }

    // —— 单层 48dp 盒：标签叠布上半部，Slider 铺满整盒（触控 48dp，视觉近似旧版）——
    Box(modifier = modifier.fillMaxWidth().height(48.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, start = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // ⚠️ 必须显式写 `color`（容器不是 Surface，不写就继承外层 contentColor ⇒ 文字消失）。
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = format(value),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = valueColor,
                modifier = Modifier
                    .scale(valueScale)
                    .background(
                        if (dragging) accent.copy(alpha = 0.12f) else Color.Transparent,
                        Radius.pill
                    )
                    .padding(horizontal = Spacing.s, vertical = Spacing.xs)
            )
        }
        Slider(
            value = value,
            enabled = enabled,
            modifier = Modifier
                .fillMaxSize()
                // 标签叠布在轨道上方：整体下移让轨道视觉落点与旧版一致（盒中心 24dp → 31dp）
                .graphicsLayer { translationY = 7.dp.toPx() }
                .semantics { contentDescription = label },
            onValueChange = { raw ->
                if (!dragging) {
                    dragging = true
                    onDraggingChange(true)
                }
                val out = if (step > 0f) round(raw / step) * step else raw
                onValueChange(out)
                reportHud(Pair(label, format(out)))
            },
            valueRange = valueRange,
            onValueChangeFinished = {
                dragging = false
                onDraggingChange(false)
                reportHud(null)
                onValueChangeFinished()
            },
            interactionSource = interaction,
            colors = SliderDefaults.colors(
                thumbColor = thumbColor,
                activeTrackColor = accent,
                inactiveTrackColor = trackInactive,
                disabledThumbColor = thumbColor.copy(alpha = 0.38f),
                disabledActiveTrackColor = accent.copy(alpha = 0.38f),
                disabledInactiveTrackColor = trackInactive
            ),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .alpha(if (enabled) 1f else 0.38f)
                        .shadow(
                            elevation = 4.dp,
                            shape = Radius.pill,
                            clip = false,
                            ambientColor = SliderThumbShadow,
                            spotColor = SliderThumbShadow
                        )
                        .background(thumbColor, Radius.pill)
                )
            },
            track = { state ->
                // 自绘轨道：3dp 线 + 双极零点刻度（`.sl-zero`），右端 M3 默认停点圆点消失。
                val fraction = if (state.valueRange.endInclusive - state.valueRange.start > 0f) {
                    ((state.value - state.valueRange.start) /
                        (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
                } else 0f
                val bipolar = state.valueRange.start < 0f && state.valueRange.endInclusive > 0f
                val trackColor = if (enabled) accent else accent.copy(alpha = 0.38f)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val y = size.height / 2f
                    val cap = StrokeCap.Round
                    // 未选中段
                    drawLine(
                        color = trackInactive,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 3.dp.toPx(),
                        cap = cap
                    )
                    // 选中段
                    drawLine(
                        color = trackColor,
                        start = Offset(0f, y),
                        end = Offset(size.width * fraction, y),
                        strokeWidth = 3.dp.toPx(),
                        cap = cap
                    )
                    // 双极参数的零点刻度：1.5×10dp 竖线在 50% 处（`.sl-zero`）
                    if (bipolar) {
                        drawLine(
                            color = thumbColor.copy(alpha = 0.55f),
                            start = Offset(size.width / 2f, y - 5.dp.toPx()),
                            end = Offset(size.width / 2f, y + 5.dp.toPx()),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = cap
                        )
                    }
                }
            }
        )
    }
}

/**
 * 拖动读数上报（UI 3.0「中央 HUD」的数据通道）。见类 KDoc。
 */
val LocalHudReporter = staticCompositionLocalOf<((Pair<String, String>?) -> Unit)> { {} }

