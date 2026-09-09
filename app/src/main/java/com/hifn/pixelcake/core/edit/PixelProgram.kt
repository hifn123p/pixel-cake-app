package com.hifn.pixelcake.core.edit

import kotlin.math.pow

/**
 * 由 [EditParams] 预编译出的逐像素运算程序。
 *
 * 存在的意义是性能：此前 `processPixel` 每像素返回 `Triple`，内部四个子函数再各返回
 * 一个 `Triple`，33MP 导出约 1.6 亿次对象分配 + 装箱，与「亚秒级」的注释自相矛盾
 * （FIX_LIST F06）。这里把参数在 **渲染开始前** 塌缩成标量字段，逐像素路径上
 * 只有浮点乘加与查表，零分配、零装箱。
 *
 * 白平衡与曝光都折叠进「线性 16-bit -> sRGB」那一步的查表索引里：
 * 每通道只需一次乘法后取整即可查表，不需要逐像素 pow。
 */
class PixelProgram(
    params: EditParams,
    lumaLut: IntArray = ColorMath.buildLumaLut(params.lumaPoints)
) {
    // 白平衡 × 曝光，折叠进查表前的缩放系数
    private val gainR: Float
    private val gainG: Float
    private val gainB: Float

    private val contrast: Float = params.contrast
    private val saturation: Float = params.saturation
    private val shadows: Float = params.shadows
    private val highlights: Float = params.highlights
    private val lutId: String = params.lutId
    private val lutIntensity: Float = params.lutIntensity.coerceIn(0f, 1f)
    private val lumaLut: IntArray = lumaLut

    init {
        val ev = 2f.pow(params.exposureEv)
        // 与旧 applyWhiteBalance 语义一致：色温>0 偏暖(增 R 减 B)；色调>0 偏品红(增 R/B 减 G)
        val wr = 1f + params.temperature * 0.28f + params.tint * 0.14f
        val wg = 1f - params.tint * 0.16f
        val wb = 1f - params.temperature * 0.28f + params.tint * 0.08f
        gainR = wr * ev
        gainG = wg * ev
        gainB = wb * ev
    }

    /** 输入 16-bit 线性 RGB（0..65535），返回打包好的 0xAARRGGBB。 */
    fun applyLinear(r16: Int, g16: Int, b16: Int): Int =
        finish(
            ColorMath.linear16ToSrgb(r16 * gainR),
            ColorMath.linear16ToSrgb(g16 * gainG),
            ColorMath.linear16ToSrgb(b16 * gainB)
        )

    /** 输入 8-bit sRGB（0..255），先转线性再走同一条管线，保证与 RAW 同源。 */
    fun applySrgb8(r8: Int, g8: Int, b8: Int): Int =
        finish(
            ColorMath.linear16ToSrgb(ColorMath.SRGB8_TO_LINEAR16[r8] * gainR),
            ColorMath.linear16ToSrgb(ColorMath.SRGB8_TO_LINEAR16[g8] * gainG),
            ColorMath.linear16ToSrgb(ColorMath.SRGB8_TO_LINEAR16[b8] * gainB)
        )

    private fun finish(r0: Float, g0: Float, b0: Float): Int {
        var r = r0
        var g = g0
        var b = b0

        // 阴影 / 高光：以 sRGB 亮度为权重整体提压
        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        r += shadows * (1f - lum) * 0.5f
        g += shadows * (1f - lum) * 0.5f
        b += shadows * (1f - lum) * 0.5f
        r -= highlights * lum * 0.5f
        g -= highlights * lum * 0.5f
        b -= highlights * lum * 0.5f

        // 对比度
        r = ColorMath.applyContrast(r, contrast)
        g = ColorMath.applyContrast(g, contrast)
        b = ColorMath.applyContrast(b, contrast)

        // 饱和度
        r = r.coerceIn(0f, 1f)
        g = g.coerceIn(0f, 1f)
        b = b.coerceIn(0f, 1f)
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val f = 1f + saturation
        r = luma + (r - luma) * f
        g = luma + (g - luma) * f
        b = luma + (b - luma) * f

        // 亮度曲线：按曲线前后亮度之比整体缩放，保持色相不变
        val idx = (lum * 255f).toInt().coerceIn(0, 255)
        val newLuma = lumaLut[idx] / 255f
        val ratio = if (lum <= 1e-4f) 1f else newLuma / lum
        r = (r * ratio).coerceIn(0f, 1f)
        g = (g * ratio).coerceIn(0f, 1f)
        b = (b * ratio).coerceIn(0f, 1f)

        // 内置 LUT
        if (lutIntensity > 0f && lutId != "none") {
            when (lutId) {
                "bw" -> {
                    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    r = l; g = l; b = l
                }
                "warm" -> {
                    r = (r * (1f + 0.16f * lutIntensity)).coerceIn(0f, 1f)
                    b = (b * (1f - 0.12f * lutIntensity)).coerceIn(0f, 1f)
                }
                "cool" -> {
                    r = (r * (1f - 0.12f * lutIntensity)).coerceIn(0f, 1f)
                    b = (b * (1f + 0.16f * lutIntensity)).coerceIn(0f, 1f)
                }
                "film" -> {
                    val c = 0.16f * lutIntensity
                    r = ColorMath.applyContrast((r * (1f + 0.08f * lutIntensity)).coerceIn(0f, 1f), c)
                    g = ColorMath.applyContrast(g, c)
                    b = ColorMath.applyContrast((b * (1f - 0.06f * lutIntensity)).coerceIn(0f, 1f), c)
                    val l2 = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val f2 = 1f + 0.12f * lutIntensity
                    r = l2 + (r - l2) * f2
                    g = l2 + (g - l2) * f2
                    b = l2 + (b - l2) * f2
                }
            }
        }

        val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
