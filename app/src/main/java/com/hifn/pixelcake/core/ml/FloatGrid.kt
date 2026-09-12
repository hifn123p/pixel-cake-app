package com.hifn.pixelcake.core.ml

import kotlin.math.floor

/**
 * 低分辨率浮点网格（值域 [0,1]）+ 双线性采样。
 *
 * **存在意义（内存纪律，见 `docs/P1p_DESIGN.md` §8）**：ML 蒙版本身只需要 256×256 分辨率，
 * 只要把网格**按需采样**到任意目标尺寸，就无需物化 `FloatArray(w*h)` ——
 * 33MP（7008×4672）下那是一次 **131MB** 分配，正是 R10 之后全仓要避免的东西。
 *
 * 因此 [com.hifn.pixelcake.core.edit.RetouchMask.resampleTo] 对 ML 蒙版来说是「换目标尺寸」
 * 而不是「重采样像素」：共享同一份 `data`，零大分配。
 *
 * 纯 Kotlin、零 Android 依赖，便于 JVM 单测。
 */
class FloatGrid(val data: FloatArray, val w: Int, val h: Int) {

    init {
        require(w > 0 && h > 0) { "FloatGrid size must be positive: ${w}x$h" }
        require(data.size >= w * h) { "FloatGrid data too small: ${data.size} < ${w * h}" }
    }

    /**
     * 把「目标图上的像素中心」映射回网格坐标做双线性插值。
     *
     * 用**像素中心对齐**（`(px + 0.5f) / outW`）而不是简单比例，避免半像素偏移让蒙版整体错位；
     * 当 `outW == w && outH == h` 时退化为恒等采样（`gx == px`、`fx == 0`），保证「同尺寸零失真」。
     */
    fun sample(px: Int, py: Int, outW: Int, outH: Int): Float {
        if (outW <= 0 || outH <= 0) return 0f
        val gx = ((px + 0.5f) / outW) * w - 0.5f
        val gy = ((py + 0.5f) / outH) * h - 0.5f
        val x0 = floor(gx).toInt()
        val y0 = floor(gy).toInt()
        val fx = gx - x0
        val fy = gy - y0
        val cx0 = x0.coerceIn(0, w - 1)
        val cy0 = y0.coerceIn(0, h - 1)
        val cx1 = (x0 + 1).coerceIn(0, w - 1)
        val cy1 = (y0 + 1).coerceIn(0, h - 1)
        val v00 = data[cy0 * w + cx0]
        val v10 = data[cy0 * w + cx1]
        val v01 = data[cy1 * w + cx0]
        val v11 = data[cy1 * w + cx1]
        val top = v00 + (v10 - v00) * fx
        val bot = v01 + (v11 - v01) * fx
        return (top + (bot - top) * fy).coerceIn(0f, 1f)
    }

}
