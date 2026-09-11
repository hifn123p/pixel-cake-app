package com.hifn.pixelcake.core.edit.retouch

import com.hifn.pixelcake.core.edit.InpaintStroke

/**
 * 祛瑕（P1b-4 / Phase 4）。对每个 blemish 描迹（圆），用其外环邻域（r..2r）的均值回填，
 * 移除脏点 / 瑕疵。参考 Rust `crates/engine/src/retouch/inpaint.rs`（实施时精读）。
 * 纯函数、零 Android 依赖，便于 JVM 单测。
 */
object Inpaint {

    fun apply(pixels: IntArray, w: Int, h: Int, strokes: List<InpaintStroke>) {
        if (strokes.isEmpty()) return
        for (s in strokes) applyOne(pixels, w, h, s)
    }

    /**
     * 把**单个**描迹施加到 `[w×h]` 缓冲上（描迹坐标是该缓冲的**局部坐标**）。
     *
     * 抽成单描迹入口是为了让调用方（`RetouchLayer`）能按「小块」施加：从 Bitmap 取一小块 → 施加 → 写回，
     * 从而整个 retouch pass 无需整幅缓冲。**按序遍历调用与 [apply] 完全等价** ——
     * 描迹之间共享同一份已被前序描迹更新过的像素，而 [apply] 本身就是这个顺序循环。
     *
     * 取块时务必让块覆盖描迹的整个**读取**半径（`2r`），并把块裁到图像内；此时块边界与图像边界重合，
     * `coerceIn` 的裁剪语义与整幅版一致。
     */
    fun applyOne(pixels: IntArray, w: Int, h: Int, s: InpaintStroke) {
        val cx = s.x.coerceIn(0, w - 1)
        val cy = s.y.coerceIn(0, h - 1)
        val r = s.r.coerceAtLeast(1)
        // 外环（r..2r）均值作为回填色
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        val outer = (2 * r) * (2 * r)
        for (dy in -2 * r..2 * r) for (dx in -2 * r..2 * r) {
            val d2 = dx * dx + dy * dy
            if (d2 > r * r && d2 <= outer) {
                val x = cx + dx; val y = cy + dy
                if (x in 0 until w && y in 0 until h) {
                    val p = pixels[y * w + x]
                    sr += (p shr 16) and 0xff; sg += (p shr 8) and 0xff; sb += p and 0xff; n++
                }
            }
        }
        if (n == 0) return
        val ar = (sr / n).toInt().coerceIn(0, 255)
        val ag = (sg / n).toInt().coerceIn(0, 255)
        val ab = (sb / n).toInt().coerceIn(0, 255)
        for (dy in -r..r) for (dx in -r..r) {
            if (dx * dx + dy * dy > r * r) continue
            val x = cx + dx; val y = cy + dy
            if (x in 0 until w && y in 0 until h) {
                pixels[y * w + x] = 0xff000000.toInt() or (ar shl 16) or (ag shl 8) or ab
            }
        }
    }
}
