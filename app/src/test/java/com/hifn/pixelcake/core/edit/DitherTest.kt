package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-3：16-bit → 8-bit 量化的 Bayer 有序抖动。
 * 三条不变量：确定性（同坐标同结果）/ 有界性（±0.5 LSB ⇒ 相邻坐标差 ≤ 1）/ 关闭时零影响。
 */
class DitherTest {

    private fun program(dither: Boolean) = PixelProgram(EditParams(), frameW = 256, frameH = 160, dither = dither)

    /** 16-bit 中灰输入（0x8000），经中性管线应落在 sRGB 中灰附近。 */
    private val r16 = 0x8000
    private val g16 = 0x8000
    private val b16 = 0x8000

    @Test
    fun dither_isDeterministic() {
        val p = program(dither = true)
        val a = p.applyLinear(r16, g16, b16, u = 0.25f, v = 0.5f)
        val b = p.applyLinear(r16, g16, b16, u = 0.25f, v = 0.5f)
        assertEquals("同一像素坐标必须逐位一致（预览=导出的前提）", a, b)
    }

    @Test
    fun dither_spreadIsAtMostOneLsb() {
        val p = program(dither = true)
        val w = 256
        val h = 160
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (x in 0 until 64) {
            val px = p.applyLinear(r16, g16, b16, u = (x + 0.5f) / w, v = 0.5f)
            val r = (px shr 16) and 0xff
            if (r < min) min = r
            if (r > max) max = r
        }
        assertTrue("Bayer 抖动的量化阈值差最多 1 LSB（实测 $max-$min）", max - min <= 1)
    }

    @Test
    fun ditherOff_isFlatAcrossPixels() {
        val p = program(dither = false)
        val first = p.applyLinear(r16, g16, b16, u = 0.5f / 256, v = 0.5f)
        var allSame = true
        for (x in 0 until 64) {
            val px = p.applyLinear(r16, g16, b16, u = (x + 0.5f) / 256, v = 0.5f)
            if (px != first) allSame = false
        }
        assertTrue("关闭抖动时逐像素输出必须完全一致（旧口径回归）", allSame)
    }
}
