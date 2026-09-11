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
}
