package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动增强的启发式回归钉子（§9.4-1：先算再断言）。
 * 构造三种典型直方图（欠曝 / 过曝 / 正常），断言建议值的**方向与量级**。
 */
class AutoEnhanceTest {

    /** 造一条峰值在 [peakBin]、宽度 [width] 的归一化单峰曲线。 */
    private fun peakAt(peakBin: Int, width: Int = 40): FloatArray {
        val bins = 256
        val arr = FloatArray(bins)
        for (i in 0 until bins) {
            val d = (i - peakBin).toFloat() / width
            arr[i] = kotlin.math.exp(-0.5f * d * d)
        }
        return arr
    }

    private fun hist(r: FloatArray, g: FloatArray, b: FloatArray) =
        Triple(r, g, b)

    @Test
    fun darkImage_suggestsPositiveExposure() {
        // 峰在 bin 40（严重欠曝）→ 曝光应正推
        val dark = hist(peakAt(40), peakAt(40), peakAt(40))
        val s = AutoEnhance.suggest(dark)
        assertTrue("expected positive EV, got ${s.exposureEv}", s.exposureEv > 0.1f)
        assertTrue("EV capped at 0.7", s.exposureEv <= 0.7f)
    }

    @Test
    fun brightImage_suggestsNegativeExposure() {
        // 峰在 bin 220（过曝）→ 曝光应负推
        val bright = hist(peakAt(220), peakAt(220), peakAt(220))
        val s = AutoEnhance.suggest(bright)
        assertTrue("expected negative EV, got ${s.exposureEv}", s.exposureEv < -0.1f)
    }

    @Test
    fun normalImage_suggestsNearZero() {
        // 峰在 bin 128、宽 60 → 分布正常 → 曝光 ≈ 0
        val normal = hist(peakAt(128, 60), peakAt(128, 60), peakAt(128, 60))
        val s = AutoEnhance.suggest(normal)
        assertTrue("expected near-zero EV, got ${s.exposureEv}", abs(s.exposureEv) < 0.15f)
    }

    @Test
    fun narrowRange_suggestsContrast() {
        // 窄峰（width=8）⇒ p95-p05 小 ⇒ 提对比
        val flat = hist(peakAt(128, 8), peakAt(128, 8), peakAt(128, 8))
        val s = AutoEnhance.suggest(flat)
        assertTrue("expected positive contrast, got ${s.contrast}", s.contrast > 0.05f)
    }

    @Test
    fun wideRange_noContrast() {
        // 全宽均匀分布 ⇒ 不提对比
        val bins = 256
        val uniform = FloatArray(bins) { 1f }
        val s = AutoEnhance.suggest(hist(uniform, uniform, uniform))
        assertEquals(0f, s.contrast, 1e-6f)
    }

    @Test
    fun warmCast_suggestsCooling() {
        // R 峰在高位、B 峰在低位 ⇒ 偏暖 → 建议降温（负 temperature）
        val warm = hist(peakAt(160), peakAt(128), peakAt(80))
        val s = AutoEnhance.suggest(warm)
        assertTrue("expected cooling (negative T), got ${s.temperature}", s.temperature < 0f)
    }

    @Test
    fun coolCast_suggestsWarming() {
        val cool = hist(peakAt(80), peakAt(128), peakAt(160))
        val s = AutoEnhance.suggest(cool)
        assertTrue("expected warming (positive T), got ${s.temperature}", s.temperature > 0f)
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v
}
