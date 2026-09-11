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

    /**
     * 方向断言（此前 `shiftsPixelsWithMask` 只断言 `diff > 0`，正是它放过了 S1「方向反了」）。
     * 在 x=18（质心 x=10 的右侧）放一条亮线，瘦脸应把它**向质心方向（左）**移动。
     */
    @Test
    fun slimFaceMovesContentTowardCentroid() {
        val w = 21; val h = 21
        val px = IntArray(w * h) { 0xff000000.toInt() }
        for (y in 0 until h) px[y * w + 18] = 0xffffffff.toInt()
        Beauty.apply(px, w, h, BeautyParams(slimFace = 0.5f), fullMask)

        var brightestX = -1
        var best = -1
        for (x in 0 until w) {
            val v = (px[10 * w + x] shr 16) and 0xff
            if (v > best) { best = v; brightestX = x }
        }
        assertTrue("亮线应向质心（左）移动，实际在 x=$brightestX", brightestX < 18)
    }

    /** 同理：质心 y=10 下方 (y=18) 的亮线应被向上（质心方向）拉 = 收下颌。 */
    @Test
    fun slimJawMovesContentTowardCentroidVertically() {
        val w = 21; val h = 21
        val px = IntArray(w * h) { 0xff000000.toInt() }
        for (x in 0 until w) px[18 * w + x] = 0xffffffff.toInt()
        Beauty.apply(px, w, h, BeautyParams(slimJaw = 0.5f), fullMask)

        var brightestY = -1
        var best = -1
        for (y in 0 until h) {
            val v = (px[y * w + 10] shr 16) and 0xff
            if (v > best) { best = v; brightestY = y }
        }
        assertTrue("亮线应向上（质心方向）移动，实际在 y=$brightestY", brightestY < 18)
    }
}
