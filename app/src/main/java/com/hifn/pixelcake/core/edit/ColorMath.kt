package com.hifn.pixelcake.core.edit

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 纯函数像素运算（无 Android 依赖，可 JVM 单测）。
 *
 * 编辑只发生在「参数栈」[EditParams] 上，真正落像素时由 [EditEngine] 调用 [PixelProgram]。
 * 管线的输入统一是 **16-bit 线性 sRGB**（0..65535，白点 65535）：
 *  - ARW：LibRaw 直接输出线性数据（见 `raw_bridge.cpp`，`output_bps=16` + 线性 gamma）；
 *  - JPEG/HEIF：先经 [SRGB8_TO_LINEAR16] 转线性，再走同一条管线。
 * 两端同源，保证「预览所见即导出所得」。
 *
 * 管线顺序（与 DEV_PLAN 操作栈一致）：
 *   白平衡(线性) -> 曝光(线性) -> sRGB 编码 -> 阴影/高光 -> 对比度 -> 饱和度 -> 亮度曲线 -> 内置 LUT
 */
object ColorMath {

    /** 8-bit sRGB -> 16-bit 线性。输入只有 256 种取值，精确表即可，不存在精度损失。 */
    val SRGB8_TO_LINEAR16: IntArray = IntArray(256) { i ->
        (srgbToLinear(i / 255f) * 65535f + 0.5f).toInt().coerceIn(0, 65535)
    }

    /**
     * 16-bit 线性 -> sRGB 编码表，索引 = `round(线性值 * 65535)`，共 65536 项（约 256KB）。
     *
     * 此前只有 256 项且建在线性域均匀网格上：线性 0~1/255 已对应 sRGB 0~0.19，
     * 暗部整段塌进第一格，必然出色带。加密到 65536 项后每格宽度 = 1/65535，
     * 暗部台阶宽度降到原来的 1/256，肉眼不可辨（FIX_LIST F07）。
     */
    private val LINEAR16_TO_SRGB: FloatArray = FloatArray(65536) { i ->
        linearToSrgb(i / 65535f)
    }

    /**
     * 线性域查表编码为 sRGB，返回 0..1。
     * 只在 v >= 白点时钳到 1（这是真实的高光截断，不是提前 clamp 掉可恢复的高光）。
     */
    fun linear16ToSrgb(v: Float): Float = when {
        v <= 0f -> 0f
        v >= 65535f -> 1f
        else -> LINEAR16_TO_SRGB[(v + 0.5f).toInt()]
    }

    fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    fun linearToSrgb(c: Float): Float {
        val x = if (c <= 0f) 0f else if (c >= 1f) 1f else c
        return if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1 / 2.4f) - 0.055f
    }

    fun applyContrast(c: Float, contrast: Float): Float =
        (c - 0.5f) * (1f + contrast) + 0.5f

    /** 由控制点（x,y ∈ 0..255，按 x 升序）构建 256 项亮度 LUT。 */
    fun buildLumaLut(points: List<Pair<Int, Int>>): IntArray {
        val pts = if (points.isEmpty()) listOf(0 to 0, 255 to 255) else points.sortedBy { it.first }
        val lut = IntArray(256)
        var seg = 0
        for (i in 0..255) {
            while (seg < pts.lastIndex && i > pts[seg + 1].first) seg++
            val (x0, y0) = pts[seg]
            val (x1, y1) = if (seg < pts.lastIndex) pts[seg + 1] else pts[seg]
            val t = if (x1 == x0) 0f else (i - x0).toFloat() / (x1 - x0)
            lut[i] = (y0 + (y1 - y0) * t.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        }
        return lut
    }
}
