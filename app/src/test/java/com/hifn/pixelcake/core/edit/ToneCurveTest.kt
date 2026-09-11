package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 亮度曲线：锚点映射 + LUT 构建（纯 JVM）。
 *
 * 曲线是「引擎早就支持、UI 一直缺失」的功能（P1 收尾补齐）。这里钉住两件事：
 *  1. 锚点 ↔ 控制点的映射与钳位（写错会让曲线整体跑偏，真机上很难看出来）；
 *  2. [ColorMath.buildLumaLut] 在给定锚点下的**端点与单调性**——
 *     曲线一旦不单调，画面上会出现「亮部反而变暗」这类无法解释的伪影。
 */
class ToneCurveTest {

    @Test
    fun identityPointsAreTheDocumentedShape() {
        assertEquals(listOf(0 to 0, 128 to 128, 255 to 255), ToneCurve.points(0, 128, 255))
        assertTrue(ToneCurve.isIdentity(ToneCurve.points(0, 128, 255)))
        assertTrue(ToneCurve.isIdentity(ToneCurve.IDENTITY))
    }

    @Test
    fun anchorsRoundTripAndClamp() {
        val lifted = ToneCurve.points(black = 24, mid = 140, white = 250)
        assertEquals(24, ToneCurve.black(lifted))
        assertEquals(140, ToneCurve.mid(lifted))
        assertEquals(250, ToneCurve.white(lifted))
        assertFalse(ToneCurve.isIdentity(lifted))

        // 越界值必须钳住，不能让 LUT 出现负索引/越界
        val clamped = ToneCurve.points(black = -50, mid = 999, white = 300)
        assertEquals(0, ToneCurve.black(clamped))
        assertEquals(255, ToneCurve.mid(clamped))
        assertEquals(255, ToneCurve.white(clamped))
    }

    @Test
    fun missingAnchorsFallBackToIdentity() {
        // 别处塞进来的自定义曲线（没有 128 锚点）不能让 UI 读崩溃
        val custom = listOf(0 to 10, 64 to 90, 255 to 240)
        assertEquals(10, ToneCurve.black(custom))
        assertEquals(128, ToneCurve.mid(custom))
        assertEquals(240, ToneCurve.white(custom))
        assertEquals(128, ToneCurve.mid(emptyList()))
    }

    @Test
    fun lutKeepsEndpointsAndStaysMonotonic() {
        val lut = ColorMath.buildLumaLut(ToneCurve.points(black = 20, mid = 150, white = 250))
        assertEquals(256, lut.size)
        assertEquals(20, lut[0])
        assertEquals(250, lut[255])
        for (i in 1..255) {
            assertTrue("luma LUT must not decrease at $i (${lut[i - 1]} -> ${lut[i]})", lut[i] >= lut[i - 1])
        }
    }

    @Test
    fun identityLutIsIdentity() {
        val lut = ColorMath.buildLumaLut(ToneCurve.IDENTITY)
        for (i in 0..255) assertEquals(i, lut[i])
    }

    @Test
    fun midtoneAnchorMovesOnlyMidtones() {
        val dark = ColorMath.buildLumaLut(ToneCurve.points(0, 96, 255))
        val bright = ColorMath.buildLumaLut(ToneCurve.points(0, 160, 255))
        // 中间调锚点下压/上提：中点亮度应显著变化，端点保持不动
        assertTrue("mid=96 should darken the midpoint", dark[128] < 100)
        assertTrue("mid=160 should brighten the midpoint", bright[128] > 155)
        assertEquals(0, dark[0])
        assertEquals(255, bright[255])
    }
}
