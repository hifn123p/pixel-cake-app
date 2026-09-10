package com.hifn.pixelcake.core.edit.retouch

import com.hifn.pixelcake.core.edit.ColorTransferParams
import org.junit.Assert.assertEquals
import org.junit.Test

/** 追色 / 色彩迁移：全局统计量匹配，纯函数、零 Android 依赖测试。 */
class ColorTransferTest {

    private fun flat(value: Int, n: Int = 64): IntArray {
        val px = IntArray(n)
        for (i in px.indices) px[i] = 0xff000000.toInt() or (value shl 16) or (value shl 8) or value
        return px
    }

    private fun channel(p: Int, shift: Int) = (p shr shift) and 0xff

    @Test
    fun noneRefIsNoOp() {
        val px = flat(128)
        val copy = px.copyOf()
        ColorTransfer.apply(px, 8, 8, ColorTransferParams(refId = "none", intensity = 1f))
        assertEquals("refId=none 应完全不动", copy.contentHashCode(), px.contentHashCode())
    }

    @Test
    fun flatImageMatchesPortraMeanAtFullIntensity() {
        // 平整图（std≈0）z-score 为 0，out = refMean；intensity=1 时整图等于参考均值。
        val px = flat(128)
        ColorTransfer.apply(px, 8, 8, ColorTransferParams(refId = "portra", intensity = 1f))
        for (p in px) {
            assertEquals("R 应等于 portra 均值 140", 140, channel(p, 16))
            assertEquals("G 应等于 portra 均值 128", 128, channel(p, 8))
            assertEquals("B 应等于 portra 均值 118", 118, channel(p, 0))
        }
    }

    @Test
    fun blendsByIntensity() {
        // intensity=0.5：R = 140*0.5 + 128*0.5 = 134；G = 128*0.5+128*0.5=128；B=118*0.5+128*0.5=123。
        val px = flat(128)
        ColorTransfer.apply(px, 8, 8, ColorTransferParams(refId = "portra", intensity = 0.5f))
        for (p in px) {
            assertEquals(134, channel(p, 16))
            assertEquals(128, channel(p, 8))
            assertEquals(123, channel(p, 0))
        }
    }

    @Test
    fun zeroIntensityIsNoOp() {
        val px = flat(128)
        val copy = px.copyOf()
        ColorTransfer.apply(px, 8, 8, ColorTransferParams(refId = "portra", intensity = 0f))
        assertEquals("intensity=0 应完全不动", copy.contentHashCode(), px.contentHashCode())
    }
}
