package com.hifn.pixelcake.ui.editor.curves

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.core.edit.ToneCurve
import kotlin.math.abs

/**
 * 绘制亮度曲线的可交互画布。
 *
 * - 绘制 256×256 网格
 * - 绘制贝塞尔曲线
 * - 支持拖拽三个锚点（黑场/中间调/白场）
 */
@Composable
fun InteractiveCurveCanvas(
    blackY: Float,
    midY: Float,
    whiteY: Float,
    isIdentity: Boolean,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onAnchorDrag: (anchor: AnchorType, newY: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val curveW = 256f
    val curveH = 256f
    var dragging by remember { mutableStateOf<AnchorType?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val hit = hitTest(pos.x, pos.y, blackY, midY, whiteY)
                        if (hit != null) {
                            dragging = hit
                            onDragStart()
                        }
                    },
                    onDragEnd = {
                        dragging = null
                        onDragEnd()
                    },
                    onDragCancel = {
                        dragging = null
                        onDragEnd()
                    },
                    onDrag = { change, dragAmount ->
                        val d = dragging ?: return@detectDragGestures
                        val scaleY = curveH / curveH // 1:1
                        val currentY = when (d) {
                            AnchorType.Black -> blackY
                            AnchorType.Mid -> midY
                            AnchorType.White -> whiteY
                        }
                        val newY = (currentY + dragAmount.y).coerceIn(0f, curveH)
                        // 防止锚点交叉
                        val clamped = when (d) {
                            AnchorType.Black -> newY.coerceAtMost(midY - 8f)
                            AnchorType.Mid -> newY.coerceIn(blackY + 8f, whiteY - 8f)
                            AnchorType.White -> newY.coerceAtLeast(midY + 8f)
                        }
                        onAnchorDrag(d, clamped)
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val scaleX = w / curveW
        val scaleY = h / curveH

        // 背景
        drawRect(Color(0xFF1A1A2E))

        // 网格线
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(Color(0x20FFFFFF), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        for (i in 1..3) {
            val x = w * i / 4f
            drawLine(Color(0x20FFFFFF), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }

        // 曲线
        //
        // ⚠️ Compose 的 `Path` **没有** `cubicBezier` —— 三次贝塞尔是 `cubicTo(x1, y1, x2, y2, x3, y3)`
        // （3 个点 / 6 个浮点，终点是第 3 个点）。写成 5 参会报 `No value passed for parameter 'third'`
        // 并连带一串 `Cannot infer type`。两段曲线分别落在 [黑场→中间调] 与 [中间调→白场]。
        val path = Path()
        path.moveTo(0f, h - blackY * scaleY)
        path.cubicTo(
            w * 0.33f, h - blackY * scaleY,
            w * 0.33f, h - midY * scaleY,
            w * 0.5f, h - midY * scaleY
        )
        path.cubicTo(
            w * 0.66f, h - midY * scaleY,
            w * 0.66f, h - whiteY * scaleY,
            w, h - whiteY * scaleY
        )
        drawPath(path, Color.White, style = Stroke(width = 2f))

        // 锚点手柄
        listOf(
            Triple(AnchorType.Black, blackY),
            Triple(AnchorType.Mid, midY),
            Triple(AnchorType.White, whiteY),
        ).forEach { (anchor, y) ->
            val x = ToneCurve.anchorX(anchor.toCoreAnchor()).toFloat() * scaleX
            val cy = h - y * scaleY
            drawCircle(Color(0xFF8B6FFF), radius = 6f, center = Offset(x, cy))
            drawCircle(Color.White, radius = 3f, center = Offset(x, cy))
        }
    }
}

private fun hitTest(
    x: Float, y: Float,
    blackY: Float, midY: Float, whiteY: Float
): AnchorType? {
    val tol = 30f
    val scale = 160f / 256f // Canvas 高度与内部坐标的比例
    val bx = ToneCurve.BLACK_X.toFloat() * scale
    val mx = ToneCurve.MID_X.toFloat() * scale
    val wx = ToneCurve.WHITE_X.toFloat() * scale
    if (abs(x - bx) < tol && abs(y - (160f - blackY * scale)) < tol) return AnchorType.Black
    if (abs(x - mx) < tol && abs(y - (160f - midY * scale)) < tol) return AnchorType.Mid
    if (abs(x - wx) < tol && abs(y - (160f - whiteY * scale)) < tol) return AnchorType.White
    return null
}

/** UI 的 [AnchorType] → 核心层 [ToneCurve.Anchor]（核心层不依赖 UI 包）。 */
private fun AnchorType.toCoreAnchor(): ToneCurve.Anchor = when (this) {
    AnchorType.Black -> ToneCurve.Anchor.Black
    AnchorType.Mid -> ToneCurve.Anchor.Mid
    AnchorType.White -> ToneCurve.Anchor.White
}