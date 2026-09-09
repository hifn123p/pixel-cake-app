package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 像素管线纯函数单测（JVM，无需设备）。
 *
 * 管线重构（FIX_LIST F06）后，逐像素逻辑从 `ColorMath.processPixel` 迁移到
 * [PixelProgram]：`processPixel` 每像素返回 `Triple`、内部四子函数再各返回 `Triple`，
 * 已被塌缩成「预编译标量增益 + 查表」、逐像素零分配零装箱。这里改测 [PixelProgram]，
 * 语义与原用例一致——输入为 8-bit sRGB，输出为打包的 0xAARRGGBB。
 */
class ColorMathTest {

    /** 解包 0xAARRGGBB 为 (r, g, b)。 */
    private fun rgb(packed: Int) = Triple(
        (packed shr 16) and 0xff,
        (packed shr 8) and 0xff,
        packed and 0xff
    )

    @Test
    fun identityWhenDefault() {
        val out = rgb(PixelProgram(EditParams()).applySrgb8(128, 128, 128))
        assertTrue(
            "default params must be identity, got r=${out.first} g=${out.second} b=${out.third}",
            abs(out.first - 128) <= 1 && abs(out.second - 128) <= 1 && abs(out.third - 128) <= 1
        )
    }

    @Test
    fun exposureBrightensMidtone() {
        val r = rgb(PixelProgram(EditParams(exposureEv = 1f)).applySrgb8(60, 60, 60)).first
        assertTrue("exposure +1EV should brighten 60 -> >60, got $r", r > 60)
    }

    @Test
    fun bwForcesGray() {
        val out = rgb(
            PixelProgram(EditParams(lutId = "bw", lutIntensity = 1f)).applySrgb8(200, 100, 50)
        )
        assertTrue("bw LUT must produce gray, got r=${out.first} g=${out.second} b=${out.third}",
            out.first == out.second && out.second == out.third)
    }

    @Test
    fun contrastIncreasesSeparation() {
        val prog = PixelProgram(EditParams(contrast = 0.5f))
        val hi = rgb(prog.applySrgb8(220, 220, 220)).first
        val lo = rgb(prog.applySrgb8(30, 30, 30)).first
        assertTrue("high key should push >220, got $hi", hi > 220)
        assertTrue("low key should push <30, got $lo", lo < 30)
    }

    @Test
    fun desaturateKeepsGrayNeutral() {
        val out = rgb(PixelProgram(EditParams(saturation = -1f)).applySrgb8(120, 120, 120))
        assertTrue(
            "desaturated gray stays gray, got r=${out.first} g=${out.second} b=${out.third}",
            abs(out.first - out.second) <= 1 && abs(out.second - out.third) <= 1
        )
    }
}
