package com.hifn.pixelcake.core.edit.retouch

import com.hifn.pixelcake.core.edit.NeutralGrayParams
import com.hifn.pixelcake.core.edit.RetouchMask
import kotlin.math.abs

/**
 * 中性灰磨皮（P1b-4 / Phase 2 试点算子）。
 *
 * CPU 表面模糊近似：分离式 box blur 得低频图，按「原图与模糊图差异」做边缘保护混合，
 * 再用 skin [RetouchMask] 调制强度。纯函数、零 Android 依赖，便于 JVM 单测。
 *
 * 算法要点（参考 Rust 引擎 `crates/engine/src/retouch/neutral_gray.rs`，实施时精读）：
 * - 平坦皮肤区（原图≈模糊图）全强度混合 → 压中频纹理；
 * - 强边缘/强纹理区（差异>阈值）按 `阈值/差异` 比例衰减 → 保边缘、不糊五官。
 */
object NeutralGray {

    fun apply(pixels: IntArray, w: Int, h: Int, params: NeutralGrayParams, mask: RetouchMask?) {
        val radius = params.radiusPx.coerceAtLeast(1)
        val strength = params.strength.coerceIn(0f, 1f)
        if (strength <= 0f) return
        val threshold = params.threshold.coerceAtLeast(1)

        val blurred = boxBlur(pixels, w, h, radius)
        for (i in pixels.indices) {
            val m = if (mask == null) 1f else mask.sample(i % w, i / w)
            if (m <= 0f) continue
            val a = pixels[i] and 0xff000000.toInt()
            val or = (pixels[i] shr 16) and 0xff
            val og = (pixels[i] shr 8) and 0xff
            val ob = pixels[i] and 0xff
            val br = (blurred[i] shr 16) and 0xff
            val bg = (blurred[i] shr 8) and 0xff
            val bb = blurred[i] and 0xff
            val maxd = maxOf(abs(or - br), abs(og - bg), abs(ob - bb))
            // 表面模糊：差异小时全强度；差异大（边缘/强纹理）时按比例衰减，保边缘。
            val edge = if (maxd <= threshold) strength else strength * (threshold.toFloat() / maxd)
            val f = edge * m
            val nr = (or + (br - or) * f).toInt().coerceIn(0, 255)
            val ng = (og + (bg - og) * f).toInt().coerceIn(0, 255)
            val nb = (ob + (bb - ob) * f).toInt().coerceIn(0, 255)
            pixels[i] = a or (nr shl 16) or (ng shl 8) or nb
        }
    }

    /** 分离式 box blur（横向 + 纵向各一趟），O(n·radius)。 */
    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(src.size)
        boxPass(src, tmp, w, h, r, horizontal = true)
        val out = IntArray(src.size)
        boxPass(tmp, out, w, h, r, horizontal = false)
        return out
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val win = r * 2 + 1
        for (major in 0 until (if (horizontal) h else w)) {
            var accR = 0L
            var accG = 0L
            var accB = 0L
            for (k in -r..r) {
                val idx = if (horizontal) major * w + k.coerceIn(0, w - 1)
                else k.coerceIn(0, h - 1) * w + major
                accR += (src[idx] shr 16) and 0xff
                accG += (src[idx] shr 8) and 0xff
                accB += src[idx] and 0xff
            }
            for (minor in 0 until len) {
                val outIdx = if (horizontal) major * w + minor else minor * w + major
                val nr = (accR / win).toInt().coerceIn(0, 255)
                val ng = (accG / win).toInt().coerceIn(0, 255)
                val nb = (accB / win).toInt().coerceIn(0, 255)
                dst[outIdx] = (0xff shl 24) or (nr shl 16) or (ng shl 8) or nb
                val left = minor - r
                val right = minor + r + 1
                val li = if (horizontal) major * w + left.coerceIn(0, w - 1)
                else left.coerceIn(0, h - 1) * w + major
                val ri = if (horizontal) major * w + right.coerceIn(0, w - 1)
                else right.coerceIn(0, h - 1) * w + major
                accR += ((src[ri] shr 16) and 0xff) - ((src[li] shr 16) and 0xff)
                accG += ((src[ri] shr 8) and 0xff) - ((src[li] shr 8) and 0xff)
                accB += (src[ri] and 0xff) - (src[li] and 0xff)
            }
        }
    }
}
