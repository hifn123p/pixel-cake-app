package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RetouchScale.skinMask` 的作用域口径（FIX_LIST §9 R08 的用户决策落点）：
 *
 * **无描迹 ⇒ [FullMask]（作用域 = 整幅）**，而不是 `null`。`null` 只表示「不执行」，
 * 专留给相机批量链路（`CameraBatch` 显式传 `null`）。编辑器由此保留「滑杆一拖就有可见效果」。
 */
class RetouchScaleTest {

    @Test
    fun noStrokesYieldsFullMask() {
        val m = RetouchScale.skinMask(100, 80, emptyList(), 0.01f)
        assertSame("无描迹应返回 FullMask（O(1) 内存的整幅作用域）", FullMask, m)
        assertEquals(1f, m!!.sample(0, 0), 0f)
        assertEquals(1f, m.sample(99, 79), 0f)
    }

    @Test
    fun strokeProducesLimitedRasterMask() {
        val m = RetouchScale.skinMask(100, 80, listOf(0.5f to 0.5f), 0.1f)
        assertTrue("有描迹应得到栅格蒙版", m is RasterMask)
        assertTrue("描迹中心应为正强度", m!!.sample(50, 40) > 0f)
        assertEquals("远端应为 0", 0f, m.sample(0, 0), 0f)
    }

    @Test
    fun degenerateSizeYieldsNull() {
        assertEquals("尺寸非法时返回 null（无处可施加）", null, RetouchScale.skinMask(0, 0, emptyList(), 0.01f))
    }

    // ---- P1p-1b：编辑器「自动蒙版 ∪ 画笔」合成口径（docs/P1p_DESIGN.md §7）----

    @Test
    fun brushMaskIsNullWhenNoStrokesOrBadSize() {
        assertEquals("无描迹 = 未圈定作用域（区别于 skinMask 的 FullMask）", null,
            RetouchScale.brushMask(100, 80, emptyList(), 0.01f))
        assertEquals("尺寸非法也为 null", null,
            RetouchScale.brushMask(0, 0, listOf(0.5f to 0.5f), 0.01f))
    }

    @Test
    fun editorSkinMaskWithoutAutoMaskKeepsLegacyBehaviour() {
        // 自动蒙版关闭（autoMask = null）：无描迹 ⇒ 整幅
        assertSame(FullMask, RetouchScale.editorSkinMask(100, 80, emptyList(), 0.01f, null))
        // 有描迹 ⇒ 画笔栅格
        assertTrue(RetouchScale.editorSkinMask(100, 80, listOf(0.5f to 0.5f), 0.1f, null) is RasterMask)
    }

    @Test
    fun editorSkinMaskDoesNotDegradeToFullFrameWhenAutoMaskPresent() {
        // 关键回归：自动蒙版开启且未涂画笔时 **不得** 退化成整幅，否则自动蒙版形同虚设
        val m = RetouchScale.editorSkinMask(100, 80, emptyList(), 0.01f, ConstMask(0.7f))
        assertEquals("应沿用 ML 强度，而非整幅 1.0", 0.7f, m!!.sample(10, 10), 0f)
        assertEquals(0.7f, m.sample(99, 79), 0f)
    }

    @Test
    fun editorSkinMaskMergesMlAndBrushByMax() {
        val m = RetouchScale.editorSkinMask(100, 80, listOf(0.5f to 0.5f), 0.1f, ConstMask(0.2f))!!
        assertTrue("描迹中心应被画笔抬高到 ML 强度之上（max 语义）", m.sample(50, 40) > 0.2f)
        assertEquals("远端保留 ML 强度", 0.2f, m.sample(0, 0), 0f)
    }

    @Test
    fun editorSkinMaskBadSizeYieldsNull() {
        assertEquals(null, RetouchScale.editorSkinMask(0, 0, emptyList(), 0.01f, ConstMask(0.5f)))
    }

    /** 常量蒙版：全图同一强度，便于验证「不退化」「取 max」等口径。 */
    private class ConstMask(private val v: Float) : RetouchMask {
        override fun sample(px: Int, py: Int) = v
        override fun resampleTo(w: Int, h: Int): RetouchMask = this
    }
}
