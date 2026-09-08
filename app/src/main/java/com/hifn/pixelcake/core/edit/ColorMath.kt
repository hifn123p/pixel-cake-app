package com.hifn.pixelcake.core.edit

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 纯函数像素运算（无 Android 依赖，可 JVM 单测）。
 *
 * 编辑只发生在「参数栈」[EditParams] 上，真正落像素时由 [EditEngine] 逐像素调用这里。
 * 管线顺序（与 DEV_PLAN 操作栈一致）：
 *   白平衡(线性) -> 曝光(线性) -> sRGB -> 阴影/高光 -> 对比度 -> 饱和度 -> 亮度曲线 -> 内置 LUT
 */
object ColorMath {

    fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    fun linearToSrgb(c: Float): Float {
        val x = if (c <= 0f) 0f else if (c >= 1f) 1f else c
        return if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1 / 2.4f) - 0.055f
    }

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

    fun applyWhiteBalance(
        r: Float, g: Float, b: Float,
        temperature: Float, tint: Float
    ): Triple<Float, Float, Float> {
        // 色温>0 偏暖(增 R 减 B)；色调>0 偏品红(增 R/B 减 G)
        val wr = 1f + temperature * 0.18f + tint * 0.10f
        val wg = 1f - tint * 0.12f
        val wb = 1f - temperature * 0.18f + tint * 0.06f
        return Triple(r * wr, g * wg, b * wb)
    }

    fun applyContrast(c: Float, contrast: Float): Float =
        (c - 0.5f) * (1f + contrast) + 0.5f

    fun applySaturation(
        r: Float, g: Float, b: Float, sat: Float
    ): Triple<Float, Float, Float> {
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val f = 1f + sat
        return Triple(luma + (r - luma) * f, luma + (g - luma) * f, luma + (b - luma) * f)
    }

    fun applyLumaCurve(
        r: Float, g: Float, b: Float, lut: IntArray
    ): Triple<Float, Float, Float> {
        val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
        val idx = (luma * 255f).toInt().coerceIn(0, 255)
        val newLuma = lut[idx] / 255f
        val ratio = if (luma <= 1e-4f) 1f else newLuma / luma
        return Triple(
            (r * ratio).coerceIn(0f, 1f),
            (g * ratio).coerceIn(0f, 1f),
            (b * ratio).coerceIn(0f, 1f)
        )
    }

    fun applyBuiltinLut(
        r: Float, g: Float, b: Float, lutId: String, intensity: Float
    ): Triple<Float, Float, Float> {
        val i = intensity.coerceIn(0f, 1f)
        if (i <= 0f || lutId == "none") return Triple(r, g, b)
        return when (lutId) {
            "bw" -> {
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                Triple(l, l, l)
            }
            "warm" -> Triple(
                (r * (1f + 0.10f * i)).coerceIn(0f, 1f),
                g,
                (b * (1f - 0.08f * i)).coerceIn(0f, 1f)
            )
            "cool" -> Triple(
                (r * (1f - 0.08f * i)).coerceIn(0f, 1f),
                g,
                (b * (1f + 0.10f * i)).coerceIn(0f, 1f)
            )
            "film" -> {
                val c = applyContrast((r * (1f + 0.06f * i)).coerceIn(0f, 1f), 0.12f * i)
                val cg = applyContrast(g, 0.12f * i)
                val cb = applyContrast((b * (1f - 0.04f * i)).coerceIn(0f, 1f), 0.12f * i)
                val sat = applySaturation(c, cg, cb, 0.08f * i)
                Triple(sat.first, sat.second, sat.third)
            }
            else -> Triple(r, g, b)
        }
    }

    /** 单像素全管线。lut 由调用方预构建，避免逐像素重建。 */
    fun processPixel(r8: Int, g8: Int, b8: Int, p: EditParams, lut: IntArray): Triple<Int, Int, Int> {
        var r = r8 / 255f
        var g = g8 / 255f
        var b = b8 / 255f
        val lin = applyWhiteBalance(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b), p.temperature, p.tint)
        val ev = 2f.pow(p.exposureEv)
        val lr = lin.first * ev
        val lg = lin.second * ev
        val lb = lin.third * ev
        r = linearToSrgb(lr)
        g = linearToSrgb(lg)
        b = linearToSrgb(lb)
        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        r += p.shadows * (1f - lum) * 0.5f
        g += p.shadows * (1f - lum) * 0.5f
        b += p.shadows * (1f - lum) * 0.5f
        r -= p.highlights * lum * 0.5f
        g -= p.highlights * lum * 0.5f
        b -= p.highlights * lum * 0.5f
        r = applyContrast(r, p.contrast)
        g = applyContrast(g, p.contrast)
        b = applyContrast(b, p.contrast)
        val sat = applySaturation(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), p.saturation)
        r = sat.first; g = sat.second; b = sat.third
        val cur = applyLumaCurve(r, g, b, lut)
        r = cur.first; g = cur.second; b = cur.third
        val fin = applyBuiltinLut(r, g, b, p.lutId, p.lutIntensity)
        r = fin.first; g = fin.second; b = fin.third
        val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return Triple(ri, gi, bi)
    }
}
