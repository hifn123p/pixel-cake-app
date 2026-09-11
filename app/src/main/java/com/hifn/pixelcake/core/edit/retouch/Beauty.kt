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
 *
 * **内存纪律（FIX_LIST F05 / 第二轮复审 R10）**：原先无条件分配整幅 `IntArray(w·h)` 作输出缓冲
 * （33MP 下 ≈131MB）。现改为**只在蒙版支撑的包围盒**上开缓冲：蒙版外 `mv == 0` 即恒等映射，
 * 本就无需写入，故包围盒外一律**原地保留**。数学上与整幅输出**逐位一致** ——
 * 由 `BeautyTest.bboxBufferMatchesFullFrame` 用朴素参照实现钉死。
 *
 * **为何不用「分带」**：液化的后向映射位移随「到质心的距离」线性增长，`slimJaw` 会把源点拉到
 * 带**上方**、`eyeEnlarge` 会把源点拉到质心**下方**（跨越多带）。任一带状原地处理都会读到
 * 已被前一带写过的像素而失真；要精确就得保留无界的原始 halo，等于没省。包围盒是此算子
 * 唯一「既精确又有界」的口径。
 *
 * **[rowOffset] 按条带处理**：`RetouchLayer` 以「行条带」为单位喂像素（缓冲只覆盖整图的一段行）。
 * 此时位移与蒙版采样**一律用绝对坐标**，只有取样缓冲时减去 [rowOffset]；质心须由调用方在
 * 条带外算好传入，否则会只在条带内统计而偏。
 */
object Beauty {

    /**
     * 蒙版质心（绝对坐标）；蒙版为空（无 `mv > 0` 像素）返回 `null`。
     * 整幅单次扫描，与 [apply] 内部口径一致。按条带调用时用它算一次即可全条带复用。
     */
    fun centroid(mask: RetouchMask, w: Int, h: Int): Pair<Float, Float>? {
        var sx = 0L; var sy = 0L; var sw = 0L
        for (y in 0 until h) for (x in 0 until w) {
            if (mask.sample(x, y) > 0f) { sx += x; sy += y; sw += 1 }
        }
        if (sw == 0L) return null
        return (sx / sw).toFloat() to (sy / sw).toFloat()
    }

    /**
     * @param rowOffset 本缓冲首行在整图中的**绝对行号**；整幅调用传 0（默认）。
     * @param centroid 预先算好的蒙版质心（绝对坐标）。传 `null`（默认）则本函数自行按 [fullHeight]
     *   全量扫描；**按条带调用时必须传入**。
     * @param fullHeight 蒙版扫描的高度（=整图高度）；仅 [centroid] 为 `null` 时用到。
     */
    fun apply(
        pixels: IntArray, w: Int, h: Int,
        params: BeautyParams, mask: RetouchMask?,
        rowOffset: Int = 0, centroid: Pair<Float, Float>? = null, fullHeight: Int = h
    ) {
        if (params.slimFace <= 0f && params.slimJaw <= 0f && params.eyeEnlarge <= 0f) return
        // 液化必须有权重作用域：mask 为 null 时全局形变会糊整图，直接跳过。
        val m = mask ?: return
        val c = centroid ?: centroid(m, w, fullHeight) ?: return
        val cx = c.first
        val cy = c.second

        // 蒙版支撑的包围盒 —— 只扫**本缓冲覆盖的那些行**（条带契约：调用方保证条带覆盖蒙版的整段行）。
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val ay = y + rowOffset
            for (x in 0 until w) {
                if (m.sample(x, ay) > 0f) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return

        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val out = IntArray(bw * bh)
        for (y in minY..maxY) {
            val ay = y + rowOffset
            val rowOff = (y - minY) * bw
            for (x in minX..maxX) {
                val mv = m.sample(x, ay).coerceIn(0f, 1f)
                val o = rowOff + (x - minX)
                if (mv <= 0f) {
                    out[o] = pixels[y * w + x]
                    continue
                }
                var dx = x.toFloat()
                var dy = ay.toFloat()
                // 这里是**后向映射**（对每个输出点求其源坐标）。要让脸「变窄」，输出点必须去采
                // 更靠外（远离质心）的源点，外侧内容才会被拉进来、整体向质心收拢。
                // 此前误写成 `-=`（采更靠内的源点）→ 实际是「放大」，与 KDoc 的「拉向质心（瘦脸）」相反。
                if (params.slimFace > 0f) dx += mv * params.slimFace * 0.3f * (x - cx)
                if (params.slimJaw > 0f && ay > cy) dy += mv * params.slimJaw * 0.3f * (ay - cy)
                if (params.eyeEnlarge > 0f) {
                    dx = cx + (dx - cx) * (1f - mv * params.eyeEnlarge * 0.3f)
                    dy = cy + (dy - cy) * (1f - mv * params.eyeEnlarge * 0.3f)
                }
                // 源坐标是绝对行号，取样缓冲前换算回缓冲内的局部行
                out[o] = sampleBilinear(pixels, w, h, dx, dy - rowOffset)
            }
        }
        // 全部输出算完再统一写回：缓冲期间 `pixels` 保持原值，保证后向映射读到的都是原始像素。
        for (y in minY..maxY) {
            System.arraycopy(out, (y - minY) * bw, pixels, y * w + minX, bw)
        }
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
