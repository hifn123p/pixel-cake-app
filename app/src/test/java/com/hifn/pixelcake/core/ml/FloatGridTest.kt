package com.hifn.pixelcake.core.ml

import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FloatGrid] 的双线性采样必须与「朴素参照实现」一致（独立写法，不复用被测代码）。
 *
 * 这类测试是本类改动的唯一可信护栏：等价性无法靠肉眼 review 判断。
 */
class FloatGridTest {

    /**
     * 朴素参照：用**加权角点求和**的写法（与 [FloatGrid.sample] 的「两次一维插值」写法不同，
     * 数学等价）独立算一遍，避免「照抄实现」导致测试恒真。
     */
    private fun naive(data: FloatArray, w: Int, h: Int, px: Int, py: Int, outW: Int, outH: Int): Float {
        val sxc = (px + 0.5f) * w / outW - 0.5f
        val syc = (py + 0.5f) * h / outH - 0.5f
        val xl = floor(sxc).toInt()
        val yt = floor(syc).toInt()
        val ax = sxc - xl
        val ay = syc - yt
        fun at(x: Int, y: Int): Float = data[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        val w00 = (1f - ax) * (1f - ay)
        val w10 = ax * (1f - ay)
        val w01 = (1f - ax) * ay
        val w11 = ax * ay
        return at(xl, yt) * w00 + at(xl + 1, yt) * w10 + at(xl, yt + 1) * w01 + at(xl + 1, yt + 1) * w11
    }

    private fun grid(w: Int, h: Int): FloatGrid {
        val n = w * h
        return FloatGrid(FloatArray(n) { it.toFloat() / (n - 1).toFloat() }, w, h)
    }

    @Test
    fun identityWhenSameSize() {
        val g = grid(8, 8)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                assertEquals(
                    "px=$x py=$y 应与网格原值一致",
                    g.data[y * 8 + x],
                    g.sample(x, y, 8, 8),
                    1e-6f,
                )
            }
        }
    }

    @Test
    fun upscaleMatchesNaiveBilinear() {
        val g = grid(16, 16)
        val outW = 61
        val outH = 37
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                assertEquals(
                    "px=$x py=$y",
                    naive(g.data, 16, 16, x, y, outW, outH),
                    g.sample(x, y, outW, outH),
                    1e-4f,
                )
            }
        }
    }

    @Test
    fun downscaleMatchesNaiveBilinear() {
        val g = grid(64, 48)
        val outW = 13
        val outH = 9
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                assertEquals(
                    "px=$x py=$y",
                    naive(g.data, 64, 48, x, y, outW, outH),
                    g.sample(x, y, outW, outH),
                    1e-4f,
                )
            }
        }
    }

    @Test
    fun samplesStayWithinUnitRange() {
        val g = grid(32, 32)
        for (y in 0 until 40) {
            for (x in 0 until 40) {
                val v = g.sample(x, y, 40, 40)
                assertTrue("v=$v 越界", v in 0f..1f)
            }
        }
    }

    @Test
    fun outOfRangeTargetCoordsClampToEdge() {
        val g = grid(4, 4) // 单调递增 ⇒ 右下角为 1.0
        val v = g.sample(9999, 9999, 10, 10)
        assertTrue("v=$v 越界", v in 0f..1f)
        assertEquals(1f, v, 1e-6f)
    }

    @Test
    fun maxWithTakesElementWiseMaximum() {
        val a = FloatGrid(floatArrayOf(0f, 0.4f, 0.9f, 0.1f), 2, 2)
        val b = FloatGrid(floatArrayOf(0.3f, 0.2f, 0.5f, 0.8f), 2, 2)
        val m = a.maxWith(b)
        assertEquals(0.3f, m.data[0], 0f)
        assertEquals(0.4f, m.data[1], 0f)
        assertEquals(0.9f, m.data[2], 0f)
        assertEquals(0.8f, m.data[3], 0f)
    }
}
