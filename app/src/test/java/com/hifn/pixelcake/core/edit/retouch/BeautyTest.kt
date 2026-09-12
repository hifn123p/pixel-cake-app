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

    /**
     * 内存纪律等价性（FIX_LIST R10）：`Beauty` 现在只在**蒙版包围盒**上开输出缓冲
     * （整幅 `out` 已消除）。必须与「朴素整幅实现」逐位一致 —— 否则省内存省出了画质差异。
     *
     * 圆盘蒙版偏心放置：包围盒明显小于整幅，同时覆盖「包围盒内 mv>0 / 包围盒外恒等」两条分支。
     */
    @Test
    fun bboxBufferMatchesFullFrame() {
        val w = 64; val h = 48
        val circle = object : RetouchMask {
            override fun sample(px: Int, py: Int): Float {
                val dx = px - 40; val dy = py - 14
                return if (dx * dx + dy * dy <= 81) 1f else 0f
            }
            override fun resampleTo(w: Int, h: Int) = this
        }
        val px0 = IntArray(w * h)
        var seed = 7L
        for (i in px0.indices) {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            val v = (seed % 256).toInt()
            px0[i] = 0xff000000.toInt() or (v shl 16) or ((v / 2) shl 8) or (v / 3)
        }
        val params = BeautyParams(slimFace = 0.6f, slimJaw = 0.4f, eyeEnlarge = 0.3f)

        val expected = naiveFullFrame(px0, w, h, params, circle)
        val actual = px0.copyOf()
        Beauty.apply(actual, w, h, params, circle)

        assertTrue("包围盒缓冲结果应与整幅实现逐位一致", expected.contentEquals(actual))
    }

    /** `eyeCentroid`（P1p-2c）确实换掉了大眼的锚点：与不给眼心相比，结果必须不同。 */
    @Test
    fun eyeCentroidOverridesEyeEnlargeAnchor() {
        val w = 41; val h = 41
        val params = BeautyParams(eyeEnlarge = 0.6f)
        val faceOnly = gradient(w, h)
        Beauty.apply(faceOnly, w, h, params, fullMask, centroid = 20f to 20f)
        val withEyes = gradient(w, h)
        Beauty.apply(withEyes, w, h, params, fullMask, centroid = 20f to 20f, eyeCentroid = 6f to 6f)
        assertTrue("眼心不同 ⇒ 大眼取样点不同 ⇒ 结果必须不同", !faceOnly.contentEquals(withEyes))
    }

    /** 不给 `eyeCentroid` 与「显式把质心当眼心」逐位相同 —— P1 口径**没有**被悄悄改动。 */
    @Test
    fun omittedEyeCentroidEqualsCentroid() {
        val w = 31; val h = 31
        val params = BeautyParams(slimFace = 0.4f, slimJaw = 0.3f, eyeEnlarge = 0.5f)
        val omitted = gradient(w, h)
        Beauty.apply(omitted, w, h, params, fullMask, centroid = 17f to 11f)
        val explicit = gradient(w, h)
        Beauty.apply(explicit, w, h, params, fullMask, centroid = 17f to 11f, eyeCentroid = 17f to 11f)
        assertTrue("省略 eyeCentroid 必须等价于「眼心 = 质心」", omitted.contentEquals(explicit))
    }

    // ---- 朴素整幅参照实现（独立于被测代码，仅用于钉住缓冲口径等价性） ----

    private fun naiveFullFrame(src: IntArray, w: Int, h: Int, params: BeautyParams, mask: RetouchMask): IntArray {
        var sx = 0L; var sy = 0L; var sw = 0L
        for (y in 0 until h) for (x in 0 until w) {
            if (mask.sample(x, y) > 0f) { sx += x; sy += y; sw += 1 }
        }
        val cx = (sx / sw).toFloat()
        val cy = (sy / sw).toFloat()
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            val mv = mask.sample(x, y).coerceIn(0f, 1f)
            if (mv <= 0f) { out[y * w + x] = src[y * w + x]; continue }
            var dx = x.toFloat(); var dy = y.toFloat()
            if (params.slimFace > 0f) dx += mv * params.slimFace * 0.3f * (x - cx)
            if (params.slimJaw > 0f && y > cy) dy += mv * params.slimJaw * 0.3f * (y - cy)
            if (params.eyeEnlarge > 0f) {
                dx = cx + (dx - cx) * (1f - mv * params.eyeEnlarge * 0.3f)
                dy = cy + (dy - cy) * (1f - mv * params.eyeEnlarge * 0.3f)
            }
            out[y * w + x] = bilinear(src, w, h, dx, dy)
        }
        return out
    }

    private fun bilinear(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x0 = fx.toInt().coerceIn(0, w - 1)
        val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceIn(0, w - 1)
        val y1 = (y0 + 1).coerceIn(0, h - 1)
        val tx = (fx - x0).coerceIn(0f, 1f)
        val ty = (fy - y0).coerceIn(0f, 1f)
        val a = px[y0 * w + x0]; val b = px[y0 * w + x1]
        val c = px[y1 * w + x0]; val d = px[y1 * w + x1]
        val lerp = { p: Int, q: Int -> (p * (1 - tx) + q * tx).toInt().coerceIn(0, 255) }
        val tr = lerp((a shr 16) and 0xff, (b shr 16) and 0xff)
        val tg = lerp((a shr 8) and 0xff, (b shr 8) and 0xff)
        val tb = lerp(a and 0xff, b and 0xff)
        val brc = lerp((c shr 16) and 0xff, (d shr 16) and 0xff)
        val bgc = lerp((c shr 8) and 0xff, (d shr 8) and 0xff)
        val bbc = lerp(c and 0xff, d and 0xff)
        val rr = (tr * (1 - ty) + brc * ty).toInt().coerceIn(0, 255)
        val gg = (tg * (1 - ty) + bgc * ty).toInt().coerceIn(0, 255)
        val bb = (tb * (1 - ty) + bbc * ty).toInt().coerceIn(0, 255)
        return 0xff000000.toInt() or (rr shl 16) or (gg shl 8) or bb
    }
}
