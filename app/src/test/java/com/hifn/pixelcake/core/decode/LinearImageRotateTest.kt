package com.hifn.pixelcake.core.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q5：线性母版旋转（16-bit 纯下标重排）。
 * 用「像素级探针」验证四个方向的映射：中心/四角的颜色落点。
 */
class LinearImageRotateTest {

    /** 3×2 的图：每格一个可辨识的颜色编号（RGB 各分量编码格坐标）。 */
    private fun probeImage(): LinearImage {
        val w = 3
        val h = 2
        val data = ShortArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 3
                data[i] = (x * 100).toShort()
                data[i + 1] = (y * 100).toShort()
                data[i + 2] = 7
            }
        }
        return LinearImage(w, h, data)
    }

    private fun pixelAt(img: LinearImage, x: Int, y: Int): Triple<Int, Int, Int> {
        val i = (y * img.width + x) * 3
        return Triple(
            img.data[i].toInt() and 0xffff,
            img.data[i + 1].toInt() and 0xffff,
            img.data[i + 2].toInt() and 0xffff
        )
    }

    @Test
    fun q0_identity() {
        val img = probeImage()
        assertTrue(img.rotateQuarter(0) === img)
        assertTrue(img.rotateQuarter(4) === img)
    }

    @Test
    fun q1_90cw_swapsDims_andMapsPixels() {
        val out = probeImage().rotateQuarter(1)
        // 90° 顺时针：宽高互换
        assertEquals(2, out.width)
        assertEquals(3, out.height)
        // 原 (2, 0) 的探针色（青绿 x=2,y=0）→ 90CW 后落在 (1, 2)：
        // (x, y) → (H-1-y, x) = (2-1-0, 2) = (1, 2)
        val p = pixelAt(out, 1, 2)
        assertEquals("x 分量随源格坐标", 200, p.first)
        assertEquals("y 分量随源格坐标", 0, p.second)
    }

    @Test
    fun q2_180_mapsCornerToCorner() {
        val out = probeImage().rotateQuarter(2)
        assertEquals(3, out.width)
        assertEquals(2, out.height)
        // 原 (0, 0) → 180° 后 (W-1, H-1) = (2, 1)
        val p = pixelAt(out, 2, 1)
        assertEquals(0, p.first)
        assertEquals(0, p.second)
        // 原 (2, 0) → (0, 1)
        val p2 = pixelAt(out, 0, 1)
        assertEquals(200, p2.first)
        assertEquals(0, p2.second)
    }

    @Test
    fun q3_270ccw_swapsDims() {
        val out = probeImage().rotateQuarter(3)
        assertEquals(2, out.width)
        assertEquals(3, out.height)
        // 原 (0, 1)（蓝绿探针）→ 270CW（=90CCW）：(x, y) → (y, W-1-x) = (1, 3-1-0) = (1, 2)
        val p = pixelAt(out, 1, 2)
        assertEquals(0, p.first)
        assertEquals(100, p.second)
    }

    @Test
    fun fourRotations_returnToIdentity() {
        val img = probeImage()
        var cur = img
        for (i in 0 until 4) cur = cur.rotateQuarter(1)
        assertEquals(img.width, cur.width)
        assertEquals(img.height, cur.height)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                assertEquals(pixelAt(img, x, y), pixelAt(cur, x, y))
            }
        }
    }
}
