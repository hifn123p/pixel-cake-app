package com.hifn.pixelcake.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.ui.theme.LocalDarkTheme
import com.hifn.pixelcake.ui.theme.Motion
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.SliderThumbShadow
import com.hifn.pixelcake.ui.theme.SliderTrackDark
import com.hifn.pixelcake.ui.theme.SliderTrackLight
import com.hifn.pixelcake.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.round

/**
 * 参数滑块（v0.7.4，用户视角审计 P2-1 重设计）。
 *
 * ## 比例与精度（「修图时不好控制」的三个解）
 *
 * ① **双层 56dp**：上 22dp 标签/数值行（与轨道各归其位，不再挤在一层），下 34dp 纯轨道区；
 *    触控 = 整个 56dp 盒（≥48 达标）。轨道 3→**4dp**、拇指 18→**22dp** + 按下 1.15 放大 ——
 *    「亮照片上 3dp 细线几乎不可见、拇指难抓」的实测反馈。
 * ② **± 步进钮**：轨道两侧 40dp 圆钮，单击 = ±1 步（无 step 时 = ±1% 量程）——
 *    「全宽拖动一步 9.5dp 难停在 +0.30」的精度问题由步进兜底，松手即入撤销栈。
 * ③ **零点磁吸 + 触感**：双极参数（量程跨 0）落进 ±2% 量程自动吸附 0，并给
 *    [HapticFeedbackType.SegmentTick] —— 「对比度拉回正好 0」不再靠手感。
 *
 * ## 拖动状态上报（[onDraggingChange]）
 *
 * 拖动中回调 `true`，松手回调 `false`。上层据此隐藏非参数 UI；撤销栈只在
 * `onValueChangeFinished` 入栈一次（F08）。步进钮的瞬时调整按「一次一格」立即走完成语义。
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
    val reportHud = LocalHudReporter.current
    val haptic = LocalHapticFeedback.current
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
        targetValue = if (dragging) accent else MaterialTheme.colorScheme.onSurface,
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

    // 零点磁吸：双极（量程跨 0）且吸附带 = ±2% 量程
    val bipolar = valueRange.start < 0f && valueRange.endInclusive > 0f
    val magnetSpan = (valueRange.endInclusive - valueRange.start) * 0.02f

    fun quantize(raw: Float): Float {
        var out = if (step > 0f) round(raw / step) * step else raw
        if (bipolar && abs(out) <= magnetSpan && out != 0f) {
            out = 0f
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
        return out.coerceIn(valueRange.start, valueRange.endInclusive)
    }

    fun nudge(delta: Float) {
        if (!enabled) return
        val unit = if (step > 0f) step else (valueRange.endInclusive - valueRange.start) * 0.01f
        val target = quantize(value + delta * unit)
        if (target != value) {
            onValueChange(target)
            reportHud(Pair(label, format(target)))
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            // 步进是瞬时动作：立即走完成语义（撤销栈一次一格）
            onValueChangeFinished()
        }
    }

    val interaction = remember { MutableInteractionSource() }
    var thumbPressed by remember { mutableStateOf(false) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { ev ->
            thumbPressed = ev is PressInteraction.Press
        }
    }

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // —— 减步进钮（对齐轨道：v0.7.5 视觉审计 V4 —— 步进钮中心此前落在
        // 标签行与轨道的中间，与滑轨中线错位 ~7dp）——
        Column(modifier = Modifier.height(56.dp)) {
            Spacer(Modifier.height(22.dp))
            Box(
                modifier = Modifier
                    .size(34.dp)
                .alpha(if (enabled && value > valueRange.start) 1f else 0.3f)
                .clip(Radius.pill)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    enabled = enabled
                ) { nudge(-1f) }
                .semantics { contentDescription = "$label 减一格" },
            contentAlignment = Alignment.Center
        ) {
                Text(
                    "−",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // —— 滑杆本体：双层叠布（标签行在上，轨道区在下），触控 = 整盒 ——
        Box(
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 1.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
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
                    .graphicsLayer { translationY = 8.dp.toPx() }
                    .semantics { contentDescription = label },
                onValueChange = { raw ->
                    if (!dragging) {
                        dragging = true
                        onDraggingChange(true)
                    }
                    val out = quantize(raw)
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
                            .size(22.dp)
                            .scale(if (thumbPressed || dragging) 1.15f else 1f)
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
                    val fraction = if (state.valueRange.endInclusive - state.valueRange.start > 0f) {
                        ((state.value - state.valueRange.start) /
                            (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
                    } else 0f
                    val bipolarTrack = state.valueRange.start < 0f && state.valueRange.endInclusive > 0f
                    val trackColor = if (enabled) accent else accent.copy(alpha = 0.38f)
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // 轨道画在盒高 62% 处（标签行正下方），4dp + 圆帽
                        val y = size.height * 0.62f
                        drawLine(
                            color = trackInactive,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 4.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = trackColor,
                            start = Offset(0f, y),
                            end = Offset(size.width * fraction, y),
                            strokeWidth = 4.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        if (bipolarTrack) {
                            drawLine(
                                color = thumbColor.copy(alpha = 0.55f),
                                start = Offset(size.width / 2f, y - 5.dp.toPx()),
                                end = Offset(size.width / 2f, y + 5.dp.toPx()),
                                strokeWidth = 1.5.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }
            )
        }
        // —— 加步进钮 ——
        Column(modifier = Modifier.height(56.dp)) {
            Spacer(Modifier.height(22.dp))
            Box(
                modifier = Modifier
                    .size(34.dp)
                .alpha(if (enabled && value < valueRange.endInclusive) 1f else 0.3f)
                .clip(Radius.pill)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    enabled = enabled
                ) { nudge(1f) }
                .semantics { contentDescription = "$label 加一格" },
            contentAlignment = Alignment.Center
        ) {
                Text(
                    "+",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 拖动读数上报（UI 3.0「中央 HUD」的数据通道）。无编辑器上层时是空实现。
 */
val LocalHudReporter = staticCompositionLocalOf<((Pair<String, String>?) -> Unit)> { {} }
