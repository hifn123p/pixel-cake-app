package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** ColorMath 是纯函数，可在 JVM 单测中验证管线语义，无需设备。 */
class ColorMathTest {

    @Test
    fun identityWhenDefault() {
        val p = EditParams()
        val lut = ColorMath.buildLumaLut(p.lumaPoints)
        val (r, g, b) = ColorMath.processPixel(128, 128, 128, p, lut)
        assertTrue("default params must be identity, got r=$r g=$g b=$b",
            abs(r - 128) <= 1 && abs(g - 128) <= 1 && abs(b - 128) <= 1)
    }

    @Test
    fun exposureBrightensMidtone() {
        val p = EditParams(exposureEv = 1f)
        val lut = ColorMath.buildLumaLut(p.lumaPoints)
        val (r, _, _) = ColorMath.processPixel(60, 60, 60, p, lut)
        assertTrue("exposure +1EV should brighten 60 -> >60, got $r", r > 60)
    }

    @Test
    fun bwForcesGray() {
        val p = EditParams(lutId = "bw", lutIntensity = 1f)
        val lut = ColorMath.buildLumaLut(p.lumaPoints)
        val (r, g, b) = ColorMath.processPixel(200, 100, 50, p, lut)
        assertTrue("bw LUT must produce gray, got r=$r g=$g b=$b", r == g && g == b)
    }

    @Test
    fun contrastIncreasesSeparation() {
        val p = EditParams(contrast = 0.5f)
        val lut = ColorMath.buildLumaLut(p.lumaPoints)
        val hi = ColorMath.processPixel(220, 220, 220, p, lut)
        val lo = ColorMath.processPixel(30, 30, 30, p, lut)
        assertTrue("high key should push >220, got ${hi.first}", hi.first > 220)
        assertTrue("low key should push <30, got ${lo.first}", lo.first < 30)
    }

    @Test
    fun desaturateKeepsGrayNeutral() {
        val p = EditParams(saturation = -1f)
        val lut = ColorMath.buildLumaLut(p.lumaPoints)
        val (r, g, b) = ColorMath.processPixel(120, 120, 120, p, lut)
        assertTrue("desaturated gray stays gray, got r=$r g=$g b=$b",
            abs(r - g) <= 1 && abs(g - b) <= 1)
    }
}
