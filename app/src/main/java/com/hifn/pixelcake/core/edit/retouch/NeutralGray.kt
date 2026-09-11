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
 *
 * **内存纪律（FIX_LIST F05 / 第二轮复审 R10）**：原先整幅实现会在堆上同时持有 3 份全幅
 * `IntArray`（源 + 横向中间 + 纵向结果，33MP 下各 ≈131MB，合计 ≈393MB）。现改为**分带**处理：
 * 每条带只在堆上持有 `(带高 + 2×radius)` 行的 3 份缓冲（带缓冲 + boxBlur 的 tmp/out），
 * 33MP（`radiusNorm=0.01` ⇒ `radiusPx≈46`）下 ≈30MB，峰值从 `O(w·h)` 降到 `O((带高+2r)·w)`。
 * 数学上与整幅实现**逐位一致** —— 由 `NeutralGrayTest.bandedMatchesFullFrame` 用朴素参照实现钉死。
 *
 * `mask == null` 语义见 [RetouchMask]：未圈定作用域 → **不执行**（不是全局生效）。
 */
object NeutralGray {

    /**
     * 分带行数。取 256：33MP（7008×4672，短边 4672 ⇒ `radiusPx≈46`）下每条带缓冲
     * `(256+2×46)×7008×4 ≈ 9.8MB`，含 boxBlur 的 tmp/out 峰值 ≈30MB；全图约 19 带。
     * 分带引入的重复 halo 计算占比 = `2r/带高 ≈ 36%`，换来约 13× 的内存下降，划算。
     */
    private const val BAND_ROWS = 256

    fun apply(pixels: IntArray, w: Int, h: Int, params: NeutralGrayParams, mask: RetouchMask?) {
        val radius = params.radiusPx.coerceAtLeast(1)
        val strength = params.strength.coerceIn(0f, 1f)
        if (strength <= 0f) return
        // mask == null 表示「未圈定作用域」→ 不执行（**不是**全局生效）。
        // 与 [Beauty] 保持同一条约定，见 [RetouchMask] KDoc 与 `docs/P1b_DESIGN.md` §4：
        // 皮肤类算子按设计必须由 skin 蒙版调制；没有蒙版就不该凭一个默认值把整张图（含背景）都磨了。
        // 相机批量链路正是靠这条约定（`CameraBatch` 传 `mask = null`）避免误磨背景。
        val skin = mask ?: return
        val threshold = params.threshold.coerceAtLeast(1)

        // 带高必须 ≥ radius，否则一条带装不下自己的 halo（也保证下一带的 carry 一定够）。
        val band = maxOf(BAND_ROWS, radius)
        if (h <= band + radius) {
            applyFull(pixels, w, h, radius, strength, threshold, skin)
        } else {
            applyBanded(pixels, w, h, radius, strength, threshold, skin, band)
        }
    }

    // ---------------- 整幅实现（小图走这条；语义是基准） ----------------

    private fun applyFull(
        pixels: IntArray, w: Int, h: Int,
        radius: Int, strength: Float, threshold: Int, skin: RetouchMask
    ) {
        val blurred = boxBlur(pixels, w, h, radius)
        for (i in pixels.indices) {
            val m = skin.sample(i % w, i / w)
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

    // ---------------- 分带实现（大图走这条） ----------------

    /**
     * 逐带处理。每带的源缓冲 = `上一带保留下来的 radius 行原始像素` + `本带核心行及其下方 radius 行`。
     *
     * 为什么要「保留上一带的原始行」：核心行写回是**原位**的，上一带写过之后，
     * 本带顶部 halo（`[y-radius, y)`）在 `pixels` 里已经是磨过的值，不能再当源用。
     * 而这些行在本带之前是原始值，故上一带在建缓冲时顺手留存即可（无需额外一次全图拷贝）。
     *
     * 等价性：`boxBlur` 直接作用在子图上，其边界 clamp 落在 `[top, bot]`；
     * 对核心行来说窗口始终完全落在 `[top, bot]` 内，故与整幅版的 `[0, h-1]` clamp 结果一致。
     */
    private fun applyBanded(
        pixels: IntArray, w: Int, h: Int,
        radius: Int, strength: Float, threshold: Int, skin: RetouchMask, band: Int
    ) {
        var y = 0
        var carry = IntArray(0)   // 位于 [y-radius, y) 的**原始**像素
        var carryRows = 0
        while (y < h) {
            val rows = minOf(band, h - y)
            val top = (y - radius).coerceAtLeast(0)
            val bot = (y + rows - 1 + radius).coerceAtMost(h - 1)
            val headRows = y - top
            val bufRows = bot - top + 1
            val buf = IntArray(bufRows * w)
            if (headRows > 0 && carryRows >= headRows) {
                System.arraycopy(carry, 0, buf, 0, headRows * w)
            } else if (headRows > 0) {
                for (yy in top until y) System.arraycopy(pixels, yy * w, buf, (yy - top) * w, w)
            }
            for (yy in y..bot) System.arraycopy(pixels, yy * w, buf, (yy - top) * w, w)

            val blurred = boxBlur(buf, w, bufRows, radius)

            // 下一带的顶部 halo = 本带核心末尾的 radius 行；此刻 buf 里还是原始值，先留存。
            if (y + rows < h && rows >= radius) {
                carryRows = radius
                carry = IntArray(radius * w)
                System.arraycopy(buf, (y + rows - radius - top) * w, carry, 0, radius * w)
            } else {
                carryRows = 0
                carry = IntArray(0)
            }

            for (yy in y until y + rows) {
                val base = yy * w
                val bl = (yy - top) * w
                for (x in 0 until w) {
                    val mv = skin.sample(x, yy)
                    if (mv <= 0f) continue
                    val i = base + x
                    val a = pixels[i] and 0xff000000.toInt()
                    val or = (pixels[i] shr 16) and 0xff
                    val og = (pixels[i] shr 8) and 0xff
                    val ob = pixels[i] and 0xff
                    val br = (blurred[bl + x] shr 16) and 0xff
                    val bg = (blurred[bl + x] shr 8) and 0xff
                    val bb = blurred[bl + x] and 0xff
                    val maxd = maxOf(abs(or - br), abs(og - bg), abs(ob - bb))
                    val edge = if (maxd <= threshold) strength else strength * (threshold.toFloat() / maxd)
                    val f = edge * mv
                    val nr = (or + (br - or) * f).toInt().coerceIn(0, 255)
                    val ng = (og + (bg - og) * f).toInt().coerceIn(0, 255)
                    val nb = (ob + (bb - ob) * f).toInt().coerceIn(0, 255)
                    pixels[i] = a or (nr shl 16) or (ng shl 8) or nb
                }
            }
            y += rows
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
