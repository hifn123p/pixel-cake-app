package com.hifn.pixelcake.core.edit.retouch

import com.hifn.pixelcake.core.edit.BeautyParams
import com.hifn.pixelcake.core.edit.RetouchMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 美型液化：纯函数、零 Android 依赖，逐像素确定性测试。 */
class BeautyTest {

    /** 整图恒强蒙版（测试用，不走 RasterMask 羽化）。 */
    private val fullMask = object : RetouchMask {
        override fun sample(px: Int, py: Int) = 1f
        override fun resampleTo(w: Int, h: Int) = this
    }

    private fun gradient(w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = (x * 10).coerceIn(0, 255)
            px[y * w + x] = 0xff000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        return px
    }

    @Test
    fun noOpWhenAllParamsZero() {
        val px = gradient(20, 20)
        val copy = px.copyOf()
        Beauty.apply(px, 20, 20, BeautyParams(), fullMask)
        assertTrue("零参数应完全不动", px.contentEquals(copy))
    }

    @Test
    fun noOpWhenMaskNull() {
        val px = gradient(20, 20)
        val copy = px.copyOf()
        // 液化必须有权重作用域：蒙版为 null 时即便给了参数也应跳过，否则会糊整图。
        Beauty.apply(px, 20, 20, BeautyParams(slimFace = 0.5f), null)
        assertTrue("无蒙版应完全不动", px.contentEquals(copy))
    }

    @Test
    fun shiftsPixelsWithMask() {
        val px = gradient(20, 20)
        val copy = px.copyOf()
        Beauty.apply(px, 20, 20, BeautyParams(slimFace = 0.5f), fullMask)
        var diff = 0
        for (i in px.indices) if (px[i] != copy[i]) diff++
        assertTrue("有蒙版+非零参数应至少改变部分像素", diff > 0)
    }

    @Test
    fun centroidPixelUnchanged() {
        // 质心处的像素 (x==cx) 在 slimFace 下位移为 0，应保持不变。
        val w = 21; val h = 21
        val px = gradient(w, h)
        val before = px[10 * w + 10]
        Beauty.apply(px, w, h, BeautyParams(slimFace = 0.5f), fullMask)
        assertEquals("质心像素不应移动", before, px[10 * w + 10])
    }
}
