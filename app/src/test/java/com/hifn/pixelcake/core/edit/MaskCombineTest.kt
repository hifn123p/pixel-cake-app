package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蒙版合并口径单测（P1p-1，`docs/P1p_DESIGN.md` §7）：`max` 语义 + `null` 分支。
 */
class MaskCombineTest {

    private class ConstMask(private val v: Float) : RetouchMask {
        override fun sample(px: Int, py: Int) = v
        override fun resampleTo(w: Int, h: Int): RetouchMask = this
    }

    @Test
    fun maxTakesLargerValueAtEachPoint() {
        assertEquals(0.7f, MaxMask(ConstMask(0.2f), ConstMask(0.7f)).sample(3, 4), 0f)
        assertEquals(0.7f, MaxMask(ConstMask(0.7f), ConstMask(0.2f)).sample(3, 4), 0f)
    }

    @Test
    fun maxWithFullMaskKeepsFullStrength() {
        assertEquals(1f, MaxMask(FullMask, ConstMask(0.4f)).sample(0, 0), 0f)
    }

    @Test
    fun maxResampleKeepsSemantics() {
        val r = MaxMask(ConstMask(0.1f), ConstMask(0.9f)).resampleTo(100, 50)
        assertEquals(0.9f, r.sample(10, 10), 0f)
    }

    @Test
    fun brushStrokeCanOnlyRaiseStrengthNeverLowerIt() {
        // 用户画笔是「显式补正」⇒ 涂了之后强度只能 ≥ 原值（这正是不取 min 的原因）
        val raster = RasterMask.fromStrokes(8, 8, listOf(BrushStroke(4, 4, 2)))
        val ml = ConstMask(0.6f)
        val merged = MaxMask(ml, raster)
        assertTrue(merged.sample(4, 4) >= ml.sample(4, 4))
    }

    @Test
    fun mergeReturnsOtherSideWhenOneIsNull() {
        val a = ConstMask(0.5f)
        assertSame(a, RetouchScale.mergeMasks(a, null))
        assertSame(a, RetouchScale.mergeMasks(null, a))
        assertNull(RetouchScale.mergeMasks(null, null))
    }

    @Test
    fun mergeCombinesBothSidesByMax() {
        val out = RetouchScale.mergeMasks(ConstMask(0.3f), ConstMask(0.9f))
        assertEquals(0.9f, out!!.sample(0, 0), 0f)
    }
}
