package com.hifn.pixelcake.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hifn.pixelcake.ui.theme.Motion
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * 强度环（UI 3.0 §F / §03 `.dial`）：96dp 圆环 + 7dp 琥珀弧 + 中心等宽百分比读数。
 *
 * ## 为什么是环而不是滑块
 *
 * 强度是「把整套调法向中性回退多少」的**比例**语义（`EditLerp` 的 amount），不是某个
 * 有量程的物理参数：滑块的两端（0/100%）对它是天然语义（空 / 原样），而环的
 * 「扫过多少弧」与「回退多少」是同一种「占比」直觉 —— 规范把它列为琥珀的第二处合法出现。
 * lerp 引擎（v0.6.x）落地后这里仍是普通滑块，审计（A-P3-7）把它归为「规范主张未落地」；
 * 本控件补齐 UI 侧。
 *
 * ## 手势口径与 [ParamSlider] 一致
 *
 * - 拖动中回调 `onDraggingChange(true)`（上层据此隐掉非参数 UI），松手/取消复位；
 * - 松手才 `onValueChangeFinished`（撤销栈一次拖动只入一条）；
 * - 拖动读数走 [LocalHudReporter]（中央 HUD 46sp 大读数）；
 * - 组合销毁兜底复位 dragging（M3 系手势回调在节点销毁时不发 finished —— 见 ParamSlider）。
 *
 * ## 数值步进与回填动画
 *
 * 值按 5% 量化（与原滑块 step 同口径）；非拖动来源的变化（套预设时从 1.0 落到 0.65）
 * 走 420ms ease-out「弧线生长」动画（规范 §05），拖动中用 snap 保证跟手。
 *
 * @param value 0..1（1 = 预设原样）
 */
@Composable
fun StrengthDial(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var dragging by remember { mutableStateOf(false) }
    val reportHud = LocalHudReporter.current

    DisposableEffect(Unit) {
        onDispose {
            if (dragging) {
                dragging = false
                onDraggingChange(false)
            }
        }
    }

    val display by animateFloatAsState(
        targetValue = value,
        animationSpec = if (dragging) snap() else tween(420, easing = Motion.easingOut),
        label = "strengthDial"
    )

    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
    val arcColor = MaterialTheme.colorScheme.primary
    val readoutColor = MaterialTheme.colorScheme.onSurface

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .size(96.dp)
                    .semantics {
                        contentDescription = "预设强度 ${"%.0f".format(value * 100)}%，拖动圆环调整"
                    }
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        detectDragGestures(
                            onDragStart = {
                                dragging = true
                                onDraggingChange(true)
                            },
                            onDragEnd = {
                                dragging = false
                                onDraggingChange(false)
                                reportHud(null)
                                onValueChangeFinished()
                            },
                            onDragCancel = {
                                dragging = false
                                onDraggingChange(false)
                                reportHud(null)
                                onValueChangeFinished()
                            }
                        ) { change, _ ->
                            change.consume()
                            // ⚠️ `center` 是 DrawScope 的属性，PointerInputScope 没有 ——
                            // 必须从 `size` 现算（与绘制侧 drawArc 的圆心同一口径）。
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val dx = change.position.x - c.x
                            val dy = change.position.y - c.y
                            if (dx == 0f && dy == 0f) return@detectDragGestures
                            // 屏幕坐标 y 向下：0° 在正右方、90° 在正下方。从 12 点钟方向顺时针
                            // 扫过的角度 = atan2 + 90°，负值补一圈 —— 与圆弧绘制的起始角同口径。
                            var fromTop = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0
                            if (fromTop < 0) fromTop += 360.0
                            var nv = (fromTop / 360.0).toFloat().coerceIn(0f, 1f)
                            nv = ((nv / 0.05f).roundToInt() * 0.05f).coerceIn(0f, 1f)
                            onValueChange(nv)
                            reportHud(Pair("强度", "%.0f%%".format(nv * 100)))
                        }
                    }
            ) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2 + 1.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                if (display > 0.001f) {
                    drawArc(
                        color = arcColor,
                        startAngle = -90f,
                        sweepAngle = 360f * display.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Text(
                text = "%.0f%%".format(display * 100),
                // 规范 `.dial .v`：19% 数值、等宽（审计 A-P3-7 的规范值）
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Light,
                    fontFeatureSettings = "tnum"
                ),
                color = readoutColor
            )
        }
    }
}
