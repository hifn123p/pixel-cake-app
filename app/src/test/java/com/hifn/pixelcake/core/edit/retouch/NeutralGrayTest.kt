package com.hifn.pixelcake.core.edit.retouch

import com.hifn.pixelcake.core.edit.NeutralGrayParams
import com.hifn.pixelcake.core.edit.RetouchMask
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NeutralGrayTest {

    /** 全幅恒强蒙版（测试用，不走 RasterMask 羽化）。 */
    private val fullMask = object : RetouchMask {
        override fun sample(px: Int, py: Int) = 1f
        override fun resampleTo(w: Int, h: Int) = this
    }

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
        NeutralGray.apply(px, w, h, NeutralGrayParams(strength = 1f, radiusPx = 4, threshold = 60), fullMask)
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
        NeutralGray.apply(px, w, h, NeutralGrayParams(strength = 1f, radiusPx = 4, threshold = 24), fullMask)
        val left = (px[3] shr 16) and 0xff
        val right = (px[w - 4] shr 16) and 0xff
        assertTrue("硬边缘低侧应仍偏暗 (left=$left)", left < 80)
        assertTrue("硬边缘高侧应仍偏亮 (right=$right)", right > 170)
    }

    /**
     * null 语义断言（第二轮复审 N1）：`mask == null` = 「未圈定作用域」→ **完全不执行**，
     * 而不是「全局生效」。此前这里是 `if (mask == null) 1f`，会让相机批量链路（传 null）
     * 把整张照片连背景一起磨；且与 [Beauty] 的 `mask ?: return` 语义**相反**。
     */
    @Test
    fun noOpWhenMaskNull() {
        val w = 32
        val h = 32
        val px = IntArray(w * h)
        var seed = 999L
        for (i in px.indices) {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            val v = (128 + ((seed % 80) - 40)).coerceIn(0, 255).toInt()
            px[i] = 0xff000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        val copy = px.copyOf()
        NeutralGray.apply(px, w, h, NeutralGrayParams(strength = 1f, radiusPx = 4, threshold = 60), null)
        assertTrue("无蒙版应完全不动", px.contentEquals(copy))
    }

    /**
     * 分带等价性（FIX_LIST R10 方案 A）：`NeutralGray.apply` 在**大图**上走分带路径
     * （`h > BAND_ROWS + radius`），必须与「朴素整幅实现」逐位一致 —— 否则分带就是把画质悄悄改了。
     *
     * 参照实现对 box blur 与混合公式**逐像素直算**（不复用被测代码），是最强的口径。
     */
    @Test
    fun bandedMatchesFullFrame() {
        val w = 40
        val h = 600 // > 256 + radius(4) → 强制走分带路径
        val radius = 4
        val strength = 0.8f
        val threshold = 40
        // 一半全幅、一半只在左半区生效：同时覆盖 mv>0 与 mv==0 两条分支。
        val mask = object : RetouchMask {
            override fun sample(px: Int, py: Int): Float = if (px < w / 2) 1f else 0f
            override fun resampleTo(w: Int, h: Int) = this
        }
        val src = IntArray(w * h)
        var seed = 20260911L
        for (i in src.indices) {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            val v = (128 + ((seed % 120) - 60)).coerceIn(0, 255).toInt()
            src[i] = 0xff000000.toInt() or (v shl 16) or (v shl 8) or v
        }

        val expected = naiveFullFrame(src, w, h, radius, strength, threshold, mask)
        val actual = src.copyOf()
        NeutralGray.apply(
            actual, w, h,
            NeutralGrayParams(strength = strength, radiusPx = radius, threshold = threshold), mask
        )

        assertTrue("分带结果应与整幅实现逐位一致", expected.contentEquals(actual))
    }

    // ---- 朴素整幅参照实现（独立于被测代码，仅用于钉住分带等价性） ----

    private fun naiveFullFrame(
        src: IntArray, w: Int, h: Int, radius: Int, strength: Float, threshold: Int, mask: RetouchMask
    ): IntArray {
        val blurred = naiveBoxBlur(src, w, h, radius)
        val px = src.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val m = mask.sample(x, y)
            if (m <= 0f) continue
            val or = (px[i] shr 16) and 0xff
            val og = (px[i] shr 8) and 0xff
            val ob = px[i] and 0xff
            val br = (blurred[i] shr 16) and 0xff
            val bg = (blurred[i] shr 8) and 0xff
            val bb = blurred[i] and 0xff
            val maxd = maxOf(abs(or - br), abs(og - bg), abs(ob - bb))
            val edge = if (maxd <= threshold) strength else strength * (threshold.toFloat() / maxd)
            val f = edge * m
            val nr = (or + (br - or) * f).toInt().coerceIn(0, 255)
            val ng = (og + (bg - og) * f).toInt().coerceIn(0, 255)
            val nb = (ob + (bb - ob) * f).toInt().coerceIn(0, 255)
            px[i] = (px[i] and 0xff000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
        return px
    }

    private fun naiveBoxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val win = r * 2 + 1
        val tmp = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val p = src[y * w + (x + k).coerceIn(0, w - 1)]
                sr += (p shr 16) and 0xff; sg += (p shr 8) and 0xff; sb += p and 0xff
            }
            tmp[y * w + x] = (0xff shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
        }
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val p = tmp[(y + k).coerceIn(0, h - 1) * w + x]
                sr += (p shr 16) and 0xff; sg += (p shr 8) and 0xff; sb += p and 0xff
            }
            out[y * w + x] = (0xff shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
        }
        return out
    }

    private fun variance(px: IntArray): Double {
        val lum = px.map { (((it shr 16) and 0xff) + ((it shr 8) and 0xff) + (it and 0xff)) / 3.0 }
        val mean = lum.sum() / lum.size
        return lum.sumOf { (it - mean) * (it - mean) } / lum.size
    }
}
