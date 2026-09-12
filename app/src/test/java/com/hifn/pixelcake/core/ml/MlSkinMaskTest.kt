package com.hifn.pixelcake.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MlSkinMask] 单测（P1p-1）。
 *
 * 重点是那条**内存硬约束**的等价性：`resampleTo` 只换目标尺寸、共享同一低分辨率网格，
 * 绝不物化 `FloatArray(w*h)`（33MP = 131MB）。
 */
class MlSkinMaskTest {

    /** 构造 `side × side` 概率：给定矩形区域内 face-skin 概率为 1，其余为 0。 */
    private fun blockProbs(side: Int, x0: Int, y0: Int, x1: Int, y1: Int): FloatArray {
        val p = FloatArray(side * side * SkinMaskPostProcess.CLASSES)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                p[(y * side + x) * SkinMaskPostProcess.CLASSES + SkinMaskPostProcess.CLASS_FACE_SKIN] = 1f
            }
        }
        return p
    }

    @Test
    fun samplesOneInsideSkinBlockAndZeroOutside() {
        val side = 8
        val mask = MlSkinMask.fromProbs(blockProbs(side, 2, 2, 6, 6), side, side, side, smooth = false)
        assertEquals(1f, mask.sample(4, 4), 1e-6f)
        assertEquals(0f, mask.sample(0, 0), 1e-6f)
        assertEquals(0f, mask.sample(7, 7), 1e-6f)
    }

    @Test
    fun outOfBoundsSampleIsZero() {
        val side = 8
        val mask = MlSkinMask.fromProbs(blockProbs(side, 0, 0, side, side), side, side, side, smooth = false)
        assertEquals(0f, mask.sample(-1, 0), 0f)
        assertEquals(0f, mask.sample(0, -1), 0f)
        assertEquals(0f, mask.sample(side, 0), 0f)
        assertEquals(0f, mask.sample(0, side), 0f)
    }

    @Test
    fun resampleToSameSizeReturnsSameInstance() {
        val side = 8
        val mask = MlSkinMask.fromProbs(blockProbs(side, 0, 0, side, side), side, 32, 16)
        assertSame(mask, mask.resampleTo(32, 16))
    }

    @Test
    fun resampleToFullResolutionSharesGridBehaviour() {
        val side = 8
        val probs = blockProbs(side, 2, 2, 6, 6)
        val base = MlSkinMask.fromProbs(probs, side, side, side, smooth = false)
        val up = base.resampleTo(7008, 4672)

        // 与「直接用同一概率、同一目标尺寸构造」的蒙版逐点一致
        // ⇒ 证明 resampleTo 只换了目标尺寸，没有改数据、也没有物化整幅蒙版
        val direct = MlSkinMask.fromProbs(probs, side, 7008, 4672, smooth = false)
        for (y in 0 until 4672 step 97) {
            for (x in 0 until 7008 step 151) {
                assertEquals("px=$x py=$y", direct.sample(x, y), up.sample(x, y), 0f)
            }
        }
        assertNotSame(base, up)
    }

    @Test
    fun smoothSoftensHardEdgeWithoutTouchingCore() {
        val side = 8
        val probs = blockProbs(side, 2, 2, 6, 6)
        val hard = MlSkinMask.fromProbs(probs, side, side, side, smooth = false)
        val soft = MlSkinMask.fromProbs(probs, side, side, side, smooth = true)
        assertTrue("平滑后块外邻接像素应被软化", soft.sample(1, 4) > hard.sample(1, 4))
        assertEquals(1f, soft.sample(4, 4), 1e-6f) // 块内部不受影响
    }

    @Test
    fun weakSkinProbabilityIsThresholdedAway() {
        val side = 2
        val probs = FloatArray(side * side * SkinMaskPostProcess.CLASSES)
        for (i in 0 until side * side) {
            probs[i * SkinMaskPostProcess.CLASSES + SkinMaskPostProcess.CLASS_BODY_SKIN] = 0.1f
        }
        // 0.1 < lo(0.35) ⇒ 完全不作用
        val mask = MlSkinMask.fromProbs(probs, side, side, side, smooth = false)
        assertEquals(0f, mask.sample(0, 0), 0f)
    }

    @Test
    fun gridSideReportsModelResolution() {
        val side = 8
        val mask = MlSkinMask.fromProbs(blockProbs(side, 0, 0, side, side), side, 4096, 2048)
        assertEquals(side, mask.gridSide)
    }
}
