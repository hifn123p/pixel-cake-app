package com.hifn.pixelcake.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 后处理纯函数单测（P1p-1）。这些函数不依赖 Android / LiteRT，是 ML 蒙版可被 JVM 验证的部分。
 */
class SkinMaskPostProcessTest {

    /** 构造 `side × side` 的 channel-last 概率数组，每像素六类全部取同一个值。 */
    private fun uniformProbs(side: Int, v: Float): FloatArray =
        FloatArray(side * side * SkinMaskPostProcess.CLASSES) { v }

    @Test
    fun skinProbabilitySumsBodyAndFaceSkin() {
        val side = 2
        val probs = FloatArray(side * side * SkinMaskPostProcess.CLASSES)
        // 像素 0：body=0.4, face=0.5 → 0.9
        probs[0 * 6 + SkinMaskPostProcess.CLASS_BODY_SKIN] = 0.4f
        probs[0 * 6 + SkinMaskPostProcess.CLASS_FACE_SKIN] = 0.5f
        // 像素 1：body=0, face=0 （纯背景）
        // 像素 2：body=0.1, face=0.2 → 0.3
        probs[2 * 6 + SkinMaskPostProcess.CLASS_BODY_SKIN] = 0.1f
        probs[2 * 6 + SkinMaskPostProcess.CLASS_FACE_SKIN] = 0.2f
        // 像素 3：body=0.7, face=0.7 → 截断到 1.0
        probs[3 * 6 + SkinMaskPostProcess.CLASS_BODY_SKIN] = 0.7f
        probs[3 * 6 + SkinMaskPostProcess.CLASS_FACE_SKIN] = 0.7f

        val skin = SkinMaskPostProcess.skinProbability(probs, side)
        assertEquals(4, skin.size)
        assertEquals(0.9f, skin[0], 1e-6f)
        assertEquals(0f, skin[1], 0f)
        assertEquals(0.3f, skin[2], 1e-6f)
        assertEquals(1f, skin[3], 0f)
    }

    @Test
    fun skinProbabilityClampsNegativeToZero() {
        val probs = uniformProbs(1, -0.5f)
        val skin = SkinMaskPostProcess.skinProbability(probs, 1)
        assertEquals(0f, skin[0], 0f)
    }

    @Test
    fun skinProbabilityRejectsTooShortInput() {
        assertThrows(IllegalArgumentException::class.java) {
            SkinMaskPostProcess.skinProbability(FloatArray(5), 1)
        }
    }

    @Test
    fun thresholdAndFeatherMapsLinearly() {
        val v = floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f)
        val out = SkinMaskPostProcess.thresholdAndFeather(v, 0.35f, 0.65f)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals(0.5f, out[2], 1e-6f)
        assertEquals(1f, out[3], 1e-6f)
        assertEquals(1f, out[4], 1e-6f)
    }

    @Test
    fun thresholdAndFeatherIsInPlaceSafe() {
        val v = floatArrayOf(0.2f, 0.8f)
        SkinMaskPostProcess.thresholdAndFeather(v, 0.3f, 0.7f, v) // out === v
        assertEquals(0f, v[0], 1e-6f)
        assertEquals(1f, v[1], 1e-6f)
    }

    @Test
    fun thresholdAndFeatherDegeneratesToHardThresholdWhenSpanNonPositive() {
        val v = floatArrayOf(0.49f, 0.5f, 0.9f)
        val out = SkinMaskPostProcess.thresholdAndFeather(v, 0.5f, 0.5f)
        assertEquals(0f, out[0], 0f)
        assertEquals(1f, out[1], 0f)
        assertEquals(1f, out[2], 0f)
    }

    @Test
    fun smooth3x3AveragesOverPresentNeighbours() {
        val side = 3
        val v = FloatArray(side * side)
        v[1 * side + 1] = 1f // 仅中心为 1
        SkinMaskPostProcess.smooth3x3(v, side)
        // 按「窗口内实际存在的邻居」取均值：中心满 3×3 窗口 → 1/9；角落仅 4 格 → 1/4
        assertEquals(1f / 9f, v[1 * side + 1], 1e-6f)
        assertEquals(1f / 4f, v[0], 1e-6f)
        assertEquals(1f / 4f, v[2 * side + 2], 1e-6f)
    }

    @Test
    fun smooth3x3UsesExistingNeighbourCountAtCorners() {
        val side = 3
        val v = FloatArray(side * side) { 1f }
        SkinMaskPostProcess.smooth3x3(v, side)
        // 全 1 平滑后仍全 1（角落邻居数少但均值不变）
        for (x in v) assertEquals(1f, x, 1e-6f)
    }
}
