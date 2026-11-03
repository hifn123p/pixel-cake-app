package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 强度插值的三个不变量（§9.4-2：数值是算出来的，不是抄的）：
 * t=1 恒等 / t=0 逐像素中性 / t=0.5 单调折半 —— 加上「形状字段不被回退」的边界。
 */
class EditLerpTest {

    private val base = EditParams(
        exposureEv = 0.6f, contrast = 0.4f, saturation = 0.3f,
        vignetteAmount = 0.5f, vignetteMidpoint = 0.7f,
        grainAmount = 0.4f, grainSize = 0.6f,
        sharpenAmount = 0.8f, sharpenRadius = 2f,
        clarity = 0.3f, texture = -0.2f,
        lutId = "warm", lutIntensity = 0.9f,
        hsl = HslMix().withSat(2, 0.4f),
        temperature = 0.3f, tint = -0.2f,
    )

    @Test
    fun amount1_isIdentity() {
        assertEquals(base, base.lerped(1f))
    }

    @Test
    fun amount0_pixelFieldsAreNeutral() {
        val r = base.lerped(0f)
        assertEquals(0f, r.exposureEv, 1e-6f)
        assertEquals(0f, r.contrast, 1e-6f)
        assertEquals(0f, r.saturation, 1e-6f)
        assertEquals(0f, r.vignetteAmount, 1e-6f)
        assertEquals(0f, r.grainAmount, 1e-6f)
        assertEquals(0f, r.sharpenAmount, 1e-6f)
        assertEquals(0f, r.clarity, 1e-6f)
        assertEquals(0f, r.temperature, 1e-6f)
        // LUT：中性 = 0（不是默认 0.8），确保 PixelProgram 的 lutOp 整段跳过
        assertEquals(0f, r.lutIntensity, 1e-6f)
        // HSL 全零
        assertTrue(r.hsl.sat.all { it == 0f })
    }

    @Test
    fun amount0_shapeFieldsPreserved() {
        val r = base.lerped(0f)
        // 形状 / 修饰字段不回退（Lightroom 口径：拧的是「多重」不是「换个形状」）
        assertEquals(0.7f, r.vignetteMidpoint, 1e-6f)
        assertEquals(0.6f, r.grainSize, 1e-6f)
        assertEquals(2f, r.sharpenRadius, 1e-6f)
    }

    @Test
    fun amountHalf_scalesLinearly() {
        val r = base.lerped(0.5f)
        assertEquals(0.3f, r.exposureEv, 1e-6f)
        assertEquals(0.2f, r.contrast, 1e-6f)
        assertEquals(0.15f, r.saturation, 1e-6f)
        assertEquals(0.25f, r.vignetteAmount, 1e-6f)
        assertEquals(0.4f, r.sharpenAmount, 1e-6f)
    }

    @Test
    fun hsl_lerpsTowardZero() {
        val half = base.lerped(0.5f)
        assertEquals(0.2f, half.hsl.sat[2], 1e-6f)
        val zero = base.lerped(0f)
        assertEquals(0f, zero.hsl.sat[2], 1e-6f)
    }

    @Test
    fun curves_lerpTowardIdentity() {
        val withCurve = base.copy(lumaPoints = ToneCurve.points(20, 160, 240))
        val half = withCurve.lerped(0.5f)
        // 中间调 160 → 默认 128，半程 = 144
        assertEquals(144, ToneCurve.mid(half.lumaPoints))
    }

    @Test
    fun retouch_lerpsWiredFields() {
        val rt = RetouchState(
            neutralGray = NeutralGrayParams(strength = 0.6f),
            beauty = BeautyParams(slimFace = 0.4f, slimJaw = 0.2f, eyeEnlarge = 0.5f),
            colorTransfer = ColorTransferParams(refId = "portra", intensity = 0.7f),
        )
        val half = rt.lerped(0.5f)
        assertEquals(0.3f, half.neutralGray.strength, 1e-6f)
        assertEquals(0.2f, half.beauty.slimFace, 1e-6f)
        assertEquals(0.1f, half.beauty.slimJaw, 1e-6f)
        assertEquals(0.25f, half.beauty.eyeEnlarge, 1e-6f)
        assertEquals(0.35f, half.colorTransfer.intensity, 1e-6f)
    }

    @Test
    fun monotonic_exposure() {
        var prev = base.lerped(1f).exposureEv
        for (step in 9 downTo 0) {
            val v = base.lerped(step / 10f).exposureEv
            assertTrue("t=${step / 10f}: $v should ≤ $prev", v <= prev + 1e-6f)
            prev = v
        }
    }
}
