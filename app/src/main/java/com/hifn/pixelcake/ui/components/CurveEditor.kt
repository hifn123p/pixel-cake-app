package com.hifn.pixelcake.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.core.edit.ToneCurve
import com.hifn.pixelcake.ui.theme.Radius
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 曲线编辑器：可拖的三锚点曲线本体（`UI3.0.html` §E）。
 *
 * 规范原文：「**拖动曲线本体调锚点**；下方三个读数是同一数据的数字通道。直方图已叠在曲线底。」
 * 此前本项目的曲线页只有那三个数字滑块（黑场 / 中间调 / 白场），**没有曲线本体** ——
 * 用户看到的是一组抽象数字，而不是「这条线把输入映射成了什么」。
 *
 * ## ⚠️ 画折线，不画样条 —— 这是正确性，不是风格
 *
 * 引擎的曲线由 `ColorMath.buildLumaLut` 生成，而它是**分段线性插值**
 * （相邻两锚点之间 `y0 + (y1 - y0) * t`）。若这里画一条 Catmull-Rom / 贝塞尔平滑曲线，
 * 屏幕上看到的就不是真正生效的那条映射 —— 用户会以为中间调被平滑过渡，实际是硬折角。
 * **UI 图的形状必须等于引擎的算法**，否则它比没有图更坏。
 *
 * ## 为什么锚点的 x 不可拖
 *
 * `ToneCurve` 的数据模型是三个**固定 x**（0 / 128 / 255）、只有 y 可变的锚点
 * （见 `ToneCurve.BLACK_X / MID_X / WHITE_X`）。允许横向拖动会造出引擎表达不了的状态。
 * 每档 y 还有各自的合法区间（`BLACK_RANGE` / `MID_RANGE` / `WHITE_RANGE`），
 * 拖出区间就 clamp —— 这一条防止把「白场压到黑场以下」这类必然损坏画面的操作做出来。
 *
 * ## 手势为什么用 `rememberUpdatedState`
 *
 * `pointerInput(Unit)` 只在首次组合时建闭包。若闭包里直接读 `points` 参数，
 * 拖第二下用的还是第一次的旧值（**拖动看起来能用、但每次都是从原值起算**）。
 * `pointerInput(points)` 又会每帧重启手势、把正在进行的拖动打断。
 * 正确解法是 `rememberUpdatedState` —— 闭包稳定、读到的永远是最新值。
 * **回调（onPointsChange / onCommit）与数据是同一个坑的两面**：闭包捕获的旧回调
 * 会在切页签后写错通道、用旧 params 做 copy 回滚其它调整 —— 两者都必须走
 * `rememberUpdatedState`，只修数据不修回调等于没修完。
 *
 * @param points 三个锚点（`ToneCurve.points()` 的产物），x 固定、只有 y 有意义
 * @param histogram 当前渲染图的 RGB 直方图；传 null 时不画底（首帧 / 读取失败）
 */
@Composable
fun CurveEditor(
    points: List<Pair<Int, Int>>,
    onPointsChange: (List<Pair<Int, Int>>) -> Unit,
    onCommit: () -> Unit,
    histogram: Triple<FloatArray, FloatArray, FloatArray>?,
    modifier: Modifier = Modifier
) {
    // 闭包里必须读「最新」的 points：见类 KDoc 最后一段。
    val latestPoints = rememberUpdatedState(points)
    // ⚠️ 回调与数据是同一个坑的两面：pointerInput(Unit) 的闭包若直接捕获
    // onPointsChange / onCommit，切换页签（Luma→Red）或父级重组后拖动仍会调
    // 首次组合的旧 lambda —— 写错通道、用旧 params 做 copy 把其它调整回滚。
    // 与 latestPoints 同一道 rememberUpdatedState 解法（数据修过、回调漏了的那半）。
    val latestOnPointsChange = rememberUpdatedState(onPointsChange)
    val latestOnCommit = rememberUpdatedState(onCommit)
    // 当前按住的是哪个锚点。null = 没在拖。
    var active by remember { mutableStateOf<ToneCurve.Anchor?>(null) }

    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
    val refColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val histColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val edgeColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            // 组件自身尺寸（不是间距），不进 Spacing 标尺：与 64dp 直方图条差一档层次。
            .height(132.dp)
            .clip(Radius.chip)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        // 按 x 找最近的锚点。三个 x 相距 128，取最小值一定不会错选。
                        val xNorm = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        active = ToneCurve.Anchor.entries.minByOrNull { a ->
                            abs(xNorm * 255f - ToneCurve.anchorX(a))
                        }
                    },
                    onDragEnd = {
                        active = null
                        latestOnCommit.value()
                    },
                    onDragCancel = { active = null }
                ) { change, _ ->
                    val anchor = active ?: return@detectDragGestures
                    val h = size.height.toFloat()
                    if (h <= 0f) return@detectDragGestures
                    // 屏幕上 y 向下增大，而曲线值 0 在下、255 在上 ⇒ 需要翻转。
                    val raw = (255f - (change.position.y / h) * 255f).roundToInt()
                    val r = when (anchor) {
                        ToneCurve.Anchor.Black -> ToneCurve.BLACK_RANGE
                        ToneCurve.Anchor.Mid -> ToneCurve.MID_RANGE
                        ToneCurve.Anchor.White -> ToneCurve.WHITE_RANGE
                    }
                    val v = raw.coerceIn(r.start.roundToInt(), r.endInclusive.roundToInt())
                    val p = latestPoints.value
                    latestOnPointsChange.value(
                        ToneCurve.points(
                            black = if (anchor == ToneCurve.Anchor.Black) v else ToneCurve.black(p),
                            mid = if (anchor == ToneCurve.Anchor.Mid) v else ToneCurve.mid(p),
                            white = if (anchor == ToneCurve.Anchor.White) v else ToneCurve.white(p)
                        )
                    )
                }
            }
    ) {
        val w = size.width
        val h = size.height
        // 归一化：曲线值 v（0..255）→ 画布 y
        fun yOf(v: Int): Float = h - (v / 255f) * h
        fun xOf(v: Int): Float = (v / 255f) * w

        // —— 直方图垫底（规范「直方图已叠在曲线底」）——
        //
        // 用三通道平均当亮度分布：这一页既有亮度曲线也有分通道曲线，
        // 画三条彩色通道会让「我正在调红通道」时的视觉焦点被底色抢走。
        histogram?.let { (r, g, b) ->
            val bins = minOf(r.size, g.size, b.size)
            if (bins >= 2) {
                val bw = w / bins
                for (i in 0 until bins) {
                    val v = ((r[i] + g[i] + b[i]) / 3f).coerceIn(0f, 1f)
                    if (v <= 0.002f) continue
                    drawLine(
                        color = histColor,
                        start = Offset(i * bw, h),
                        end = Offset(i * bw, h - v * h),
                        strokeWidth = bw.coerceAtLeast(1f)
                    )
                }
            }
        }

        // —— 网格：4×4 ——
        for (i in 1..3) {
            val t = i / 4f
            drawLine(gridColor, Offset(w * t, 0f), Offset(w * t, h), 1f)
            drawLine(gridColor, Offset(0f, h * t), Offset(w, h * t), 1f)
        }
        // —— 恒等参考线：判断「我到底改了哪一段」的基线 ——
        drawLine(refColor, Offset(0f, h), Offset(w, 0f), 1f)

        // —— 曲线本体：**分段线性**，与 `buildLumaLut` 完全同构 ——
        val black = ToneCurve.black(points)
        val mid = ToneCurve.mid(points)
        val white = ToneCurve.white(points)
        val path = Path().apply {
            moveTo(xOf(ToneCurve.BLACK_X), yOf(black))
            lineTo(xOf(ToneCurve.MID_X), yOf(mid))
            lineTo(xOf(ToneCurve.WHITE_X), yOf(white))
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))

        // —— 三个锚点 ——
        listOf(
            ToneCurve.Anchor.Black to black,
            ToneCurve.Anchor.Mid to mid,
            ToneCurve.Anchor.White to white
        ).forEach { (anchor, v) ->
            val c = Offset(xOf(ToneCurve.anchorX(anchor)), yOf(v))
            val held = active == anchor
            // 按住的那个画大一圈 + 加描边：拖到一半松手前必须能确认「我在拖谁」。
            drawCircle(if (held) lineColor else lineColor.copy(alpha = 0.85f), 7.dp.toPx(), c)
            drawCircle(Color.White, 3.dp.toPx(), c)
        }

        // 外框最后画，压住直方图在边缘的溢出
        drawRect(color = edgeColor, style = Stroke(width = 1.dp.toPx()))
    }
}
