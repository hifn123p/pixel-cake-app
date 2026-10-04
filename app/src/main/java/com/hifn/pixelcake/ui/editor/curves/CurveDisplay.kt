package com.hifn.pixelcake.ui.editor.curves

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.core.edit.Histogram
import com.hifn.pixelcake.ui.theme.Spacing

/**
 * 直方图显示组件。
 *
 * 叠加绘制 R/G/B 三条通道（同 [HistogramDisplay] 的口径），
 * 由 [rememberHistogram] 提供的桶数组驱动。
 */
@Composable
fun HistogramDisplay(
    r: IntArray,
    g: IntArray,
    b: IntArray,
    modifier: Modifier = Modifier,
    showRgb: Boolean = true,
    height: Int = 64
) {
    var peak = 0
    for (i in 0 until Histogram.BINS) {
        // ⚠️ Kotlin stdlib 的 maxOf 只有 2 参与 3 参重载，**没有 4 参** —— 这里必须嵌套。
        peak = if (showRgb) maxOf(maxOf(peak, r[i]), maxOf(g[i], b[i])) else maxOf(peak, r[i])
    }
    val invPeak = if (peak == 0) 0f else 1f / peak

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .padding(horizontal = Spacing.xs)
    ) {
        val w = size.width
        val h = size.height
        val binW = w / Histogram.BINS
        if (showRgb) {
            for (i in 0 until Histogram.BINS) {
                val rc = r[i].toFloat() * invPeak * h
                if (rc > 0f) drawRect(Color(0x55FF5555), Offset(i * binW, h - rc), Size(binW + 1f, rc))
                val gc = g[i].toFloat() * invPeak * h
                if (gc > 0f) drawRect(Color(0x4455FF55), Offset(i * binW, h - gc), Size(binW + 1f, gc))
                val bc = b[i].toFloat() * invPeak * h
                if (bc > 0f) drawRect(Color(0x445555FF), Offset(i * binW, h - bc), Size(binW + 1f, bc))
            }
        } else {
            for (i in 0 until Histogram.BINS) {
                val c = r[i].toFloat() * invPeak * h
                if (c > 0f) drawRect(Color(0x55FFFFFF), Offset(i * binW, h - c), Size(binW + 1f, c))
            }
        }
    }
}

/**
 * 从 Bitmap 计算 R/G/B 三通道直方图。
 *
 * [step] 是采样步长（1=全量）。预览用 8 即可，直方图形状不受影响。
 */
@Composable
fun rememberHistogram(bitmap: android.graphics.Bitmap?, step: Int = 8): Triple<IntArray, IntArray, IntArray> {
    return remember(bitmap, step) {
        bitmap?.let { bmp ->
            val w = bmp.width
            val h = bmp.height
            val count = w * h
            val pixels = IntArray(count)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val rBuf = ByteArray(count)
            val gBuf = ByteArray(count)
            val bBuf = ByteArray(count)
            for (i in pixels.indices) {
                val p = pixels[i]
                rBuf[i] = ((p shr 16) and 0xFF).toByte()
                gBuf[i] = ((p shr 8) and 0xFF).toByte()
                bBuf[i] = (p and 0xFF).toByte()
            }
            Histogram.computeRgb(rBuf, gBuf, bBuf, count, step)
        } ?: Triple(IntArray(Histogram.BINS), IntArray(Histogram.BINS), IntArray(Histogram.BINS))
    }
}
