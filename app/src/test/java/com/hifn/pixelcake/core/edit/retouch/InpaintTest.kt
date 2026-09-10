package com.hifn.pixelcake.core.edit.retouch

import com.hifn.pixelcake.core.edit.InpaintStroke
import org.junit.Assert.assertEquals
import org.junit.Test

/** 祛瑕：外环均值回填，纯函数、零 Android 依赖测试。 */
class InpaintTest {

    private fun buildGray(w: Int, h: Int, dot: Pair<Int, Int>? = null, dotR: Int = 2): IntArray {
        val px = IntArray(w * h)
        for (i in px.indices) px[i] = 0xff000000.toInt() or (100 shl 16) or (100 shl 8) or 100
        if (dot != null) {
            val (cx, cy) = dot
            for (dy in -dotR..dotR) for (dx in -dotR..dotR) {
                if (dx * dx + dy * dy > dotR * dotR) continue
                val x = cx + dx; val y = cy + dy
                if (x in 0 until w && y in 0 until h) {
                    px[y * w + x] = 0xff000000.toInt() or (255 shl 16) // 红点
                }
            }
        }
        return px
    }

    @Test
    fun fillsBlemishWithNeighborhoodMean() {
        val w = 20; val h = 20
        val px = buildGray(w, h, dot = 10 to 10)
        Inpaint.apply(px, w, h, listOf(InpaintStroke(10, 10, 2)))
        // 红点外环全是灰(100)，回填后中心点应等于灰(100,100,100)。
        assertEquals("中心点应被回填为灰", 0xff000000.toInt() or (100 shl 16) or (100 shl 8) or 100, px[10 * w + 10])
    }

    @Test
    fun noOpWhenEmpty() {
        val px = buildGray(20, 20, dot = 10 to 10)
        val copy = px.copyOf()
        Inpaint.apply(px, 20, 20, emptyList())
        assertEquals("无描迹应完全不动", copy.contentHashCode(), px.contentHashCode())
    }

    @Test
    fun removesMultipleBlemishes() {
        val w = 30; val h = 30
        val px = buildGray(w, h, dot = 8 to 8)
        // 在 (22,22) 再补一个红点
        for (dy in -2..2) for (dx in -2..2) {
            if (dx * dx + dy * dy <= 4) px[(22 + dy) * w + (22 + dx)] = 0xff000000.toInt() or (255 shl 16)
        }
        Inpaint.apply(px, w, h, listOf(InpaintStroke(8, 8, 2), InpaintStroke(22, 22, 2)))
        assertEquals(0xff000000.toInt() or (100 shl 16) or (100 shl 8) or 100, px[8 * w + 8])
        assertEquals(0xff000000.toInt() or (100 shl 16) or (100 shl 8) or 100, px[22 * w + 22])
    }
}
