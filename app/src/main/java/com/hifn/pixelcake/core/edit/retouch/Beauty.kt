package com.hifn.pixelcake.core.edit.retouch

import com.hifn.pixelcake.core.edit.BeautyParams
import com.hifn.pixelcake.core.edit.RetouchMask

/**
 * 美型液化（P1b-4 / Phase 3）。
 *
 * P1 不依赖人脸检测，按画笔蒙版作用域做几何形变（landmark-free 启发式）：
 *  - slimFace：水平方向把蒙版内像素拉向质心 x（瘦脸 / 收颊）；
 *  - slimJaw：仅蒙版下半部，垂直方向拉向质心 y（收下颌）；
 *  - eyeEnlarge：以质心为锚做局部放大（大眼近似）。
 *
 * **P1p-2c 起锚点可被「人脸检测」覆盖**：`slimFace` / `slimJaw` 用脸框中心，`eyeEnlarge` 用双眼连线
 * 中点（[apply] 的 `centroid` / `eyeCentroid`）；两者都不给时退回「蒙版质心猜」= P1 行为，
 * 且与 P1 结果**逐位相同**（由 `BeautyTest` 钉死）。参考 Rust `crates/engine/src/retouch/beauty.rs`。
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
 * 此时位移与蒙版采样**一律用绝对坐标**，只有取样缓冲时减去 [rowOffset]；锚点须由调用方在
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
     * @param centroid `slimFace` / `slimJaw` 的锚点（绝对坐标）。传 `null`（默认）则本函数自行按
     *   [fullHeight] 全量扫描蒙版求质心；**按条带调用时必须传入**。人脸检测可用时由调用方喂脸框中心。
     * @param fullHeight 蒙版扫描的高度（=整图高度）；仅 [centroid] 为 `null` 时用到。
     * @param eyeCentroid `eyeEnlarge` 的锚点（绝对坐标）。传 `null`（默认）时退回 [centroid]，
     *   与 P1 口径**逐位相同**；人脸检测可用时由调用方喂双眼连线中点。
     */
    fun apply(
        pixels: IntArray, w: Int, h: Int,
        params: BeautyParams, mask: RetouchMask?,
        rowOffset: Int = 0, centroid: Pair<Float, Float>? = null, fullHeight: Int = h,
        eyeCentroid: Pair<Float, Float>? = null
    ) {
        if (params.slimFace <= 0f && params.slimJaw <= 0f && params.eyeEnlarge <= 0f) return
        // 液化必须有权重作用域：mask 为 null 时全局形变会糊整图，直接跳过。
        val m = mask ?: return
        val c = centroid ?: centroid(m, w, fullHeight) ?: return
        val cx = c.first
        val cy = c.second
        // 大眼锚点：给了眼心就用眼心，否则退回质心（P1 行为）
        val ex = eyeCentroid?.first ?: cx
        val ey = eyeCentroid?.second ?: cy

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
                    dx = ex + (dx - ex) * (1f - mv * params.eyeEnlarge * 0.3f)
                    dy = ey + (dy - ey) * (1f - mv * params.eyeEnlarge * 0.3f)
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
        // lerp 抽成私有 inline 函数，而不是局部 lambda：lambda 会捕获 tx ⇒ **每个像素一次对象分配**
        // （33MP 液化 ≈ 3300 万次），与 F06「逐像素零分配」的纪律冲突。
        // 逐位等价：先 Float 运算、再 toInt() 截断、最后 clamp 到 0..255（与旧 lambda 完全一致）。
        val tr = lerp1(ar, br, tx); val tg = lerp1(ag, bg, tx); val tb = lerp1(ab, bb, tx)
        val br2 = lerp1(cr, dr, tx); val bg2 = lerp1(cg, dg, tx); val bb2 = lerp1(cb, db, tx)
        val rr = lerp1(tr, br2, ty)
        val gg = lerp1(tg, bg2, ty)
        val bb3 = lerp1(tb, bb2, ty)
        return 0xff000000.toInt() or (rr shl 16) or (gg shl 8) or bb3
    }

    /** 单通道线性插值；[t] ∈ [0,1]。inline + 无捕获 ⇒ 调用点不产生任何分配。 */
    private inline fun lerp1(p: Int, q: Int, t: Float): Int =
        (p * (1f - t) + q * t).toInt().coerceIn(0, 255)
}
