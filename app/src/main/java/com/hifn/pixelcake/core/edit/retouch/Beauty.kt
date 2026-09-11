package com.hifn.pixelcake.core.edit.retouch

import com.hifn.pixelcake.core.edit.BeautyParams
import com.hifn.pixelcake.core.edit.RetouchMask

/**
 * 美型液化（P1b-4 / Phase 3）。
 *
 * P1 不依赖人脸检测，按画笔蒙版作用域做几何形变（landmark-free 启发式）：
 *  - slimFace：水平方向把蒙版内像素拉向蒙版质心 x（瘦脸 / 收颊）；
 *  - slimJaw：仅蒙版下半部，垂直方向拉向质心 y（收下颌）；
 *  - eyeEnlarge：以质心为锚做局部放大（大眼近似）。
 *
 * 参考 Rust `crates/engine/src/retouch/beauty.rs`（实施时精读）。P1+ 接入人脸关键点后改为
 * 脸部感知液化（见 `docs/P1b_DESIGN.md` §5.2）。纯函数、零 Android 依赖，便于 JVM 单测。
 */
object Beauty {

    fun apply(pixels: IntArray, w: Int, h: Int, params: BeautyParams, mask: RetouchMask?) {
        if (params.slimFace <= 0f && params.slimJaw <= 0f && params.eyeEnlarge <= 0f) return
        // 液化必须有权重作用域：mask 为 null 时全局形变会糊整图，直接跳过。
        val m = mask ?: return
        // 蒙版质心（作用域锚点）
        var sx = 0L; var sy = 0L; var sw = 0L
        for (y in 0 until h) for (x in 0 until w) {
            val mv = m.sample(x, y)
            if (mv > 0f) { sx += x; sy += y; sw += 1 }
        }
        if (sw == 0L) return
        val cx = (sx / sw).toFloat()
        val cy = (sy / sw).toFloat()

        val out = IntArray(pixels.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val mv = m.sample(x, y).coerceIn(0f, 1f)
                if (mv <= 0f) {
                    out[y * w + x] = pixels[y * w + x]
                    continue
                }
                var dx = x.toFloat()
                var dy = y.toFloat()
                // 这里是**后向映射**（对每个输出点求其源坐标）。要让脸「变窄」，输出点必须去采
                // 更靠外（远离质心）的源点，外侧内容才会被拉进来、整体向质心收拢。
                // 此前误写成 `-=`（采更靠内的源点）→ 实际是「放大」，与 KDoc 的「拉向质心（瘦脸）」相反。
                if (params.slimFace > 0f) dx += mv * params.slimFace * 0.3f * (x - cx)
                if (params.slimJaw > 0f && y > cy) dy += mv * params.slimJaw * 0.3f * (y - cy)
                if (params.eyeEnlarge > 0f) {
                    dx = cx + (dx - cx) * (1f - mv * params.eyeEnlarge * 0.3f)
                    dy = cy + (dy - cy) * (1f - mv * params.eyeEnlarge * 0.3f)
                }
                out[y * w + x] = sampleBilinear(pixels, w, h, dx, dy)
            }
        }
        out.copyInto(pixels)
    }

    private fun sampleBilinear(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x0 = fx.toInt().coerceIn(0, w - 1)
        val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceIn(0, w - 1)
        val y1 = (y0 + 1).coerceIn(0, h - 1)
        val tx = (fx - x0).coerceIn(0f, 1f)
        val ty = (fy - y0).coerceIn(0f, 1f)
        val a = px[y0 * w + x0]; val b = px[y0 * w + x1]
        val c = px[y1 * w + x0]; val d = px[y1 * w + x1]
        val ar = (a shr 16) and 0xff; val ag = (a shr 8) and 0xff; val ab = a and 0xff
        val br = (b shr 16) and 0xff; val bg = (b shr 8) and 0xff; val bb = b and 0xff
        val cr = (c shr 16) and 0xff; val cg = (c shr 8) and 0xff; val cb = c and 0xff
        val dr = (d shr 16) and 0xff; val dg = (d shr 8) and 0xff; val db = d and 0xff
        val lerp = { p: Int, q: Int -> ((p * (1 - tx) + q * tx)).toInt().coerceIn(0, 255) }
        val tr = lerp(ar, br); val tg = lerp(ag, bg); val tb = lerp(ab, bb)
        val br2 = lerp(cr, dr); val bg2 = lerp(cg, dg); val bb2 = lerp(cb, db)
        val rr = ((tr * (1 - ty) + br2 * ty)).toInt().coerceIn(0, 255)
        val gg = ((tg * (1 - ty) + bg2 * ty)).toInt().coerceIn(0, 255)
        val bb3 = ((tb * (1 - ty) + bb2 * ty)).toInt().coerceIn(0, 255)
        return 0xff000000.toInt() or (rr shl 16) or (gg shl 8) or bb3
    }
}
