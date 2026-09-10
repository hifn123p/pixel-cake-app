package com.hifn.pixelcake.core.edit.retouch

import com.hifn.pixelcake.core.edit.NeutralGrayParams
import org.junit.Assert.assertTrue
import org.junit.Test

class NeutralGrayTest {

    @Test
    fun smoothsFlatNoisyRegion() {
        val w = 32
        val h = 32
        val px = IntArray(w * h)
        // 平坦灰底 + 确定性噪声
        var seed = 12345L
        for (i in px.indices) {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            val n = ((seed % 80) - 40)
            val v = (128 + n).coerceIn(0, 255).toInt()
            px[i] = 0xff000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        val before = variance(px)
        NeutralGray.apply(px, w, h, NeutralGrayParams(strength = 1f, radiusPx = 4, threshold = 60), null)
        val after = variance(px)
        assertTrue("磨皮应降低平坦区方差 (before=$before, after=$after)", after < before)
    }

    @Test
    fun preservesHardEdge() {
        val w = 64
        val h = 8
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = if (x < w / 2) 20 else 220
                px[y * w + x] = 0xff000000.toInt() or (v shl 16) or (v shl 8) or v
            }
        }
        NeutralGray.apply(px, w, h, NeutralGrayParams(strength = 1f, radiusPx = 4, threshold = 24), null)
        val left = (px[3] shr 16) and 0xff
        val right = (px[w - 4] shr 16) and 0xff
        assertTrue("硬边缘低侧应仍偏暗 (left=$left)", left < 80)
        assertTrue("硬边缘高侧应仍偏亮 (right=$right)", right > 170)
    }

    private fun variance(px: IntArray): Double {
        val lum = px.map { (((it shr 16) and 0xff) + ((it shr 8) and 0xff) + (it and 0xff)) / 3.0 }
        val mean = lum.sum() / lum.size
        return lum.sumOf { (it - mean) * (it - mean) } / lum.size
    }
}
