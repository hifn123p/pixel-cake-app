package com.hifn.pixelcake.core.edit

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 细节（批次 4）：降噪 / 清晰度 / 纹理 / 锐化。
 *
 * ## 这是全项目唯一的**邻域**阶段，也是唯一**不能**逐像素算的阶段
 *
 * 批次 1 / 2 的算子只看当前像素，批次 3 的暗角 / 颗粒看「当前像素在画面哪儿」（坐标），
 * 但它们都还是 `(r, g, b, u, v) → (r, g, b)` 的纯函数。本阶段的每个算子都要问
 * 「**当前像素周围长什么样**」—— 输出依赖邻域，因此：
 *
 *  - 它**不能**放进 [PixelProgram]（那是个逐像素程序，签名里没有邻域的位置）；
 *  - 它必须由 [EditEngine] 在**分带之外**单独驱动一遍，见下面「为什么作用在 sRGB8 平面上」。
 *
 * ## 为什么作用在「已调色的 sRGB 8-bit 平面」而不是线性域
 *
 * 三条理由，任一条单独成立就足以定这个位置：
 *
 * 1. **不碰 [PixelProgram] 的入口签名。** 插进逐像素管线就要给它加坐标之外的邻域参数，
 *    而批次 3 刚把 `applyLinear` / `applySrgb8` 改成必填 `(u, v)` —— 再动一次，回归面会叠在一起，
 *    CI 一旦变红无法定位是哪一批。
 * 2. **RAW 与 JPEG 两条输入天然同源。** 本阶段位于「两条输入路径已经汇合」之后的下游，
 *    所以「预览所见 = 导出所得」在这条链上是**结构性**成立的，而不是靠逐处对齐口径维持的。
 * 3. **阈值与增益可以按 0..255 的绝对级数给。** 线性域的亮度分布随曝光漂移，
 *    同一个阈值在不同照片上表现完全不同；而在 sRGB 域里「差 25 级」是个稳定的观感量。
 *    这也与既有的 `retouch/NeutralGray` 同口径（它同样在 8-bit 上做）。
 *
 * ## 为什么排在**人像精修之后**
 *
 * 管线顺序：`逐像素调色 → 人像精修（磨皮/液化/祛瑕/追色）→ 细节 → 导出`。
 *
 * 邻域算子只能占**一个**位置，而四个算子里 [EditParams.sharpenAmount] 对顺序最敏感：
 * 锐化必须在磨皮之后，否则磨皮（本身就是一次表面模糊）会把刚锐出来的边缘糊掉 ——
 * 那在真机上表现为「锐化滑块的量程只有一半」。代价是降噪也落在了调色之后（理性位置本应更早），
 * 这是**刻意接受的取舍**：锐化被糊是肉眼可见的，降噪晚一步只是效率问题。
 *
 * ## 归一化口碑（与批次 3 的差别，必须说清）
 *
 * 批次 3 是 `(u, v)` 的纯函数，所以「预览与导出**逐位相同**」可以承诺。
 * **本阶段不能承诺逐位相同** —— 邻域算子的输出依赖核半径与邻域内容，跨分辨率时半径按
 * 「长边的固定比例」等比缩放，得到的是**同一个观感**而不是同一个数字。
 * 所以这里只承诺：**核半径随长边等比 ⇒ 观感一致**。`DetailPassTest` 断言半径的单调性与等比性，
 * **刻意不断言**跨分辨率逐位相等 —— 那对一个邻域算子根本不该成立，写出来就是假护栏。
 *
 * ## 内存与性能（本地无编译器，这笔账必须先算）
 *
 * 分带处理，跨带**复用**临时缓冲（不每带新分配）。33MP（7008×4672）默认参数下
 * `rFine = round(7008/1000) = 7`、`rCoarse = round(7008/120) = 58`，
 * 带高 `max(128, 58) = 128`，窗口 = `128 + 2×58 = 244` 行：
 *  - 单个 `IntArray(244×7008) ≈ 6.8MB`，同时在世 5 个（窗口 + b1 + b2 + tmp + 输出）≈ **34MB**；
 *  - 相对 131MB 的目标位图是**加分项而非主导项**，与 `NeutralGray` 的分带收益同量级。
 *
 * ⚠️ **默认零成本**：10 个参数全中性 ⇒ [isNeutral] 为真 ⇒ [apply] 立刻返回，
 * 一次缓冲都不分配、一行都不读。没开细节的用户与批次 3 的性能完全一致。
 */
object DetailPass {

    /**
     * 分带行数。
     *
     * 与 `retouch/NeutralGray` 的 256 不同，这里取 128 是因为本阶段的核半径更大
     * （`rCoarse` 是长边的 1/120，33MP 下 58 行）：带高越大，每条带的窗口 `带高 + 2·r` 越胖。
     * 128 行时 halo 开销占比 `2×58/128 ≈ 91%`，再往上加只会换来「重复计算变少、
     * 单带内存变大」，而内存在这里才是硬约束。
     */
    private const val BAND_ROWS = 128

    /** 亮度权重（与 `PixelProgram` / `ColorMath` 的 luma 口径同源）。三者和为 1，见 [core] 的推导。 */
    private const val R_Y = 0.2126f
    private const val G_Y = 0.7152f
    private const val B_Y = 0.0722f

    // ——————————————— 阈值（单位：sRGB 0..255 的「级」）———————————————

    /** 亮度降噪的**最大**边缘保护阈值，出现在 `nrLuminanceDetail = 0` 时（约 10% 白点）。 */
    private const val NR_LUMA_T = 0.10f * 255f

    /** 色度降噪的最大保护阈值。取亮度的 3.5 倍：色度噪声本来就是**大块**的，按亮度阈值压不住。 */
    private const val NR_CHROMA_T = 0.35f * 255f

    /** 清晰度的边缘保护阈值（约 20%）。低于它的低频差视为「局部对比」，高于它视为「边缘」。 */
    private const val CLARITY_T = 0.20f * 255f

    /**
     * 纹理的**反**边缘门阈值（约 6%）。
     *
     * 小阈值 ⇒ 只有很平缓的中频结构才被增强 ⇒「提质感、不提边缘」。与清晰度的门槛方向相反，
     * 这也是两者能同时挂在 `mid` 频带上仍不重复的原因。
     */
    private const val TEXTURE_T = 0.06f * 255f

    /** 锐化边缘蒙版的阈值，出现在 `sharpenMasking = 1` 时。 */
    private const val SHARP_MASK_T = 0.10f * 255f

    // ——————————————— 增益（单位：sRGB 0..255 的「级」）———————————————

    /** 清晰度满量程时的偏移系数。`clarity = ±1` 且 `|low| = 128` 时约 ±45 级（不毁图）。 */
    private const val K_CLARITY = 0.35f

    /** 纹理满量程时的偏移系数。取 0.35：中频差的幅度本来就比高频小。 */
    private const val K_TEXTURE = 0.35f

    /** 锐化满量程时的偏移系数。取 0.7 而非 1：USM 的过冲乘 1 在强边缘上会出现明显白边。 */
    private const val K_SHARP = 0.70f

    /**
     * 是否完全不执行。
     *
     * 只看**强度类**参数：`sharpenRadius` / `*Detail` / `sharpenMasking` 是修饰量，
     * 在对应强度为 0 时改它们不产生任何像素变化，因此不该让整段生效。
     * 这条判据必须与 [EditEngine] 的「是否调用本阶段」一致 —— 两处不一致就会白跑一遍邻域。
     */
    fun isNeutral(p: EditParams): Boolean =
        p.sharpenAmount <= 0f && p.nrLuminance <= 0f && p.nrColor <= 0f &&
            p.clarity == 0f && p.texture == 0f

    /**
     * 本阶段在 `w×h` 上需要的核半径：`[rFine, rCoarse]`。
     *
     * - `rFine` 由 [EditParams.sharpenRadius] 调制（0.5..3 ⇒ 长边的 0.5‰..3‰），供**锐化**与**纹理**用；
     * - `rCoarse` 固定为长边的 1/120，供**清晰度**与**色度降噪**用；
     * - 粗层用 `blur(b1, rCoarse)` 复合作出（三角核），因此总共只需两趟可分离模糊。
     *
     * 两者都以**长边**为基准 ⇒ 换分辨率时半径等比缩放 ⇒ 观感一致（这是本阶段唯一的归一化承诺）。
     */
    fun radii(w: Int, h: Int, p: EditParams): IntArray {
        val l = maxOf(w, h)
        val rFine = maxOf(1, (l / 1000f * p.sharpenRadius.coerceIn(0.5f, 3f)).roundToInt())
        val rCoarse = maxOf(rFine + 1, (l / 120f).roundToInt())
        return intArrayOf(rFine, rCoarse)
    }

    /** 本阶段需要上下各留多少**原始**行（= 最大半径）。中性时为 0。 */
    fun haloRows(w: Int, h: Int, p: EditParams): Int =
        if (isNeutral(p)) 0 else radii(w, h, p)[1]

    /**
     * 可随机读写的像素平面。
     *
     * 抽出接口才能让本阶段在 **JVM 单测**里跑等价性回归：`android.graphics.Bitmap` 在单元测试里
     * 是未实现的桩，测试必须用内存数组实现（同 `RetouchLayer.PixelStore` 的做法）。
     * 生产实现见 `EditEngine.BitmapPlane`。
     */
    interface Plane {
        val width: Int
        val height: Int
        fun readRows(y0: Int, rows: Int, dst: IntArray, dstOffset: Int)
        fun writeRows(y0: Int, rows: Int, src: IntArray, srcOffset: Int)
    }

    /** 内存平面：测试，以及 [apply] 的数组入口。 */
    private class MemoryPlane(
        private val px: IntArray,
        override val width: Int,
        override val height: Int
    ) : Plane {
        override fun readRows(y0: Int, rows: Int, dst: IntArray, dstOffset: Int) {
            System.arraycopy(px, y0 * width, dst, dstOffset, rows * width)
        }

        override fun writeRows(y0: Int, rows: Int, src: IntArray, srcOffset: Int) {
            System.arraycopy(src, srcOffset, px, y0 * width, rows * width)
        }
    }

    /** 原地在整幅像素上跑一遍细节（`w×h`）。中性时直接返回，不分配任何缓冲。 */
    fun apply(pixels: IntArray, w: Int, h: Int, p: EditParams) {
        if (isNeutral(p) || w <= 0 || h <= 0 || pixels.size < w * h) return
        apply(MemoryPlane(pixels, w, h), p)
    }

    /**
     * 在任意平面上跑一遍细节。
     *
     * 小图（`h <= 带高 + 2·r`）走整幅、否则走分带；两条路径共用同一个 [core]，
     * 因此它们的差异只在「窗口怎么来」，不在算法 —— 这也是分带等价性可证的原因。
     */
    fun apply(plane: Plane, p: EditParams) {
        val w = plane.width
        val h = plane.height
        if (isNeutral(p) || w <= 0 || h <= 0) return
        val r = radii(w, h, p)
        val rFine = r[0]
        val rMax = r[1]
        // 带高必须 >= 半径：下一带的顶部 halo 要从「本带核心的末尾 r 行」里留，
        // 核心行数不足 r 时那条 halo 根本凑不出来。这是分带实现唯一的硬约束。
        val band = maxOf(BAND_ROWS, rMax)

        if (h <= band + 2 * rMax) {
            applyWhole(plane, w, h, rFine, rMax, p)
        } else {
            applyBanded(plane, w, h, rFine, rMax, band, p)
        }
    }

    // ————————————————————— 整幅路径（小图）—————————————————————

    private fun applyWhole(plane: Plane, w: Int, h: Int, rFine: Int, rCoarse: Int, p: EditParams) {
        val n = w * h
        val win = IntArray(n)
        plane.readRows(0, h, win, 0)
        val s = Scratch(n, n)
        core(win, w, h, 0, h, s, rFine, rCoarse, p, s.out)
        plane.writeRows(0, h, s.out, 0)
    }

    // ————————————————————— 分带路径（大图）—————————————————————

    /**
     * 逐带处理。每带的窗口 = `上一带留存的 r 行` + `本带核心行及其下方 r 行`。
     *
     * 为什么必须留存上一带的原始行：核心行是**写回平面**的，上一带写过之后，
     * 本带顶部 halo（`[y-r, y)`）在平面里已经是**处理过**的值，不能再当源用。
     * 这些行在本带之前还是原始值，所以上一带顺手留下来即可 —— 这也是本实现
     * 把窗口 `[win]` 做成**只读**（结果另写 `out`）的原因：窗口一旦不被就地修改，
     * 「读到已写过的值」这类错误在结构上就不存在了。
     *
     * 等价性：`boxPass` 的边界 clamp 落在 `[top, bot]`；对核心行来说窗口恒完全落在
     * `[top, bot]` 内 ⇒ 与整幅版的 `[0, h-1]` clamp 结果一致 ⇒ 逐位相同。
     * 由 `DetailPassTest.bandedMatchesWholeFrame` 用测试内**独立**写的朴素参照钉死。
     */
    private fun applyBanded(
        plane: Plane, w: Int, h: Int,
        rFine: Int, rMax: Int, band: Int, p: EditParams
    ) {
        var y = 0
        var carry = IntArray(0)
        // 跨带复用：窗口尺寸只在**末带**变小，所以下面这个分支整趟最多触发一次。
        // 不这么做的话，33MP 导出会为每带新分配约 34MB 的临时数组（37 带 ≈ 1.2GB 垃圾）。
        var win = IntArray(0)
        var s = Scratch(0, 0)

        while (y < h) {
            val rows = minOf(band, h - y)
            val top = (y - rMax).coerceAtLeast(0)
            val bot = (y + rows - 1 + rMax).coerceAtMost(h - 1)
            val headRows = y - top
            val winRows = bot - top + 1
            val need = winRows * w

            if (win.size != need) {
                win = IntArray(need)
                s = Scratch(need, rows * w)
            }

            // 不变式：`carry` 恒有 `rMax` 行，而 `headRows = min(rMax, y) ≤ rMax` ⇒ 这里绝不越界。
            // 一旦哪天被破坏，会以数组越界**响亮地**失败，而不是静默降质。
            if (headRows > 0) System.arraycopy(carry, 0, win, 0, headRows * w)
            plane.readRows(y, bot - y + 1, win, headRows * w)

            core(win, w, winRows, headRows, rows, s, rFine, rMax, p, s.out)
            plane.writeRows(y, rows, s.out, 0)

            // 下一带的顶部 halo = 本带核心末尾的 rMax 行；此刻 win 里仍是**原始**值，先留存。
            if (y + rows < h && rows >= rMax) {
                if (carry.size != rMax * w) carry = IntArray(rMax * w)
                System.arraycopy(win, (headRows + rows - rMax) * w, carry, 0, rMax * w)
            }
            y += rows
        }
    }

    /** 跨带复用的临时缓冲。窗口尺寸变化时由调用方整组重建（`b1` / `tmp` / `b2` / `out` 必须同尺寸）。 */
    private class Scratch(n: Int, outN: Int) {
        val b1 = IntArray(n)
        val tmp = IntArray(n)
        val b2 = IntArray(n)
        val out = IntArray(outN)
    }

    // ————————————————————— 算子核心 —————————————————————

    /**
     * 在行窗口 `[win]`（`winRows × w`）上跑完四个算子，把核心区
     * `[coreTop, coreTop + coreRows)` 写进 `out`（`coreRows × w`）。
     *
     * ## 频带怎么切（四个滑块的语义全靠这一刀）
     *
     * `b1 = boxBlur(win, rFine)`、`b2 = boxBlur(b1, rCoarse)`（复合核 ⇒ 三角响应），
     * 三者的亮度记作 `Y / Y1 / Y2`：
     *
     * | 量 | 含义 | 用它的算子 |
     * |---|---|---|
     * | `hi  = Yd − Y1` | **高频**（细于 rFine） | 锐化 |
     * | `mid = Y1 − Y2` | **中频**（rFine..rCoarse） | 纹理；锐化的「细节」扩展项 |
     * | `low = Yd − Y2` | **全带**（细于 rCoarse） | 清晰度 |
     *
     * `Yd` 是**降噪之后**的亮度（不是原始 `Y`）：降噪先做，锐化 / 纹理 / 清晰度都在降噪结果上提，
     * 否则会把刚压掉的噪声重新提回来。
     *
     * ## 为什么清晰度 / 纹理 / 锐化都是「三通道加同一个偏移」
     *
     * 三个亮度权重之和为 1 ⇒ 三通道同加 `off` 使亮度恰好变 `off`，而 `R−Y` / `B−Y`
     * 两个色度差**一点不变** ⇒ 色相与饱和度不动。这与影调四区、暗角、颗粒是同一条约定
     * （见 `PixelProgram.finish` 的注释），也是「清晰度不会让画面变脏」的原因。
     * 降噪则**必须**分通道处理（它的任务恰恰是改亮度与色度），所以它排在最前单独算。
     */
    private fun core(
        win: IntArray, w: Int, winRows: Int, coreTop: Int, coreRows: Int,
        s: Scratch, rFine: Int, rCoarse: Int, p: EditParams, out: IntArray
    ) {
        boxPass(win, s.tmp, w, winRows, rFine, horizontal = true)
        boxPass(s.tmp, s.b1, w, winRows, rFine, horizontal = false)
        boxPass(s.b1, s.tmp, w, winRows, rCoarse, horizontal = true)
        boxPass(s.tmp, s.b2, w, winRows, rCoarse, horizontal = false)

        val nrLum = p.nrLuminance.coerceIn(0f, 1f)
        val nrChr = p.nrColor.coerceIn(0f, 1f)
        val clarity = p.clarity.coerceIn(-1f, 1f)
        val texture = p.texture.coerceIn(-1f, 1f)
        val sharpen = p.sharpenAmount.coerceIn(0f, 1f)

        // 细节越高 ⇒ 阈值越低 ⇒ 越少被当噪声压掉。detail = 1 时阈值为 0 ⇒ 该级完全不改像素。
        val tLum = NR_LUMA_T * (1f - p.nrLuminanceDetail.coerceIn(0f, 1f))
        val tChr = NR_CHROMA_T * (1f - p.nrColorDetail.coerceIn(0f, 1f))
        val tMask = SHARP_MASK_T * p.sharpenMasking.coerceIn(0f, 1f)
        val detail = p.sharpenDetail.coerceIn(0f, 1f)

        for (yy in 0 until coreRows) {
            val wi = (coreTop + yy) * w
            val oi = yy * w
            for (x in 0 until w) {
                val i = wi + x
                val c = win[i]
                var rf = ((c shr 16) and 0xff).toFloat()
                var gf = ((c shr 8) and 0xff).toFloat()
                var bf = (c and 0xff).toFloat()
                val y0 = R_Y * rf + G_Y * gf + B_Y * bf
                val y1 = luma(s.b1[i])
                val y2 = luma(s.b2[i])

                // ── (A) 亮度降噪：把亮度朝 b1 拉，色度不动（三通道按同一比例缩放）──
                var yd = y0
                if (nrLum > 0f) {
                    val dy = y1 - y0
                    yd = y0 + dy * nrLum * guard(dy, tLum)
                    // y0 -> 0 时比例无意义；此时三通道本身都接近 0，原样返回即可。
                    val k = if (y0 > 1e-3f) yd / y0 else 1f
                    rf *= k
                    gf *= k
                    bf *= k
                }

                // ── (B) 色度降噪：把 (R−Y, B−Y) 朝 b2 的对应量拉，亮度保持 yd ──
                // 拉的是**色度差**而不是三通道各自的值：后者会把色度噪声连同亮度结构一起搬过来。
                if (nrChr > 0f) {
                    val cr = rf - yd
                    val cb = bf - yd
                    val b2c = s.b2[i]
                    val cr2 = ((b2c shr 16) and 0xff).toFloat() - y2
                    val cb2 = (b2c and 0xff).toFloat() - y2
                    val f = nrChr * guard(maxOf(abs(cr2 - cr), abs(cb2 - cb)), tChr)
                    val crn = cr + (cr2 - cr) * f
                    val cbn = cb + (cb2 - cb) * f
                    rf = yd + crn
                    bf = yd + cbn
                    // 由 Y = R_Y·r + G_Y·g + B_Y·b 反解 G；三权重之和为 1 ⇒ 亮度恰好仍等于 yd。
                    gf = yd - (R_Y * crn + B_Y * cbn) / G_Y
                }

                // ── (C)(D)(E) 三个「三通道同加」的偏移；守卫量都取自**加之前**的亮度 ⇒ 与相加顺序无关 ──
                var off = 0f
                if (clarity != 0f) {
                    val low = yd - y2
                    off += clarity * K_CLARITY * low * guard(low, CLARITY_T)
                }
                if (texture != 0f) {
                    val mid = y1 - y2
                    off += texture * K_TEXTURE * mid * guard(mid, TEXTURE_T)
                }
                if (sharpen > 0f) {
                    val hi = yd - y1
                    val mid = y1 - y2
                    // masking = 0 ⇒ 全图锐化。注意 guard 在阈值 0 时返回 0，语义正好相反，
                    // 所以这里必须特判，不能把 tMask 直接交给 guard。
                    val m = if (tMask <= 0f) 1f else guard(hi, tMask)
                    off += sharpen * K_SHARP * m * (hi + detail * mid)
                }
                rf += off
                gf += off
                bf += off

                out[oi + x] = 0xff000000.toInt() or
                    (clamp8(rf) shl 16) or (clamp8(gf) shl 8) or clamp8(bf)
            }
        }
    }

    /** 打包像素的亮度（0..255 域，与 [core] 内部的浮点口径一致）。 */
    private fun luma(packed: Int): Float =
        R_Y * ((packed shr 16) and 0xff) + G_Y * ((packed shr 8) and 0xff) + B_Y * (packed and 0xff)

    /**
     * 边缘保护：`|d| ≤ t` ⇒ 1（全量执行）；`|d| > t` ⇒ `t/|d|`（按比例衰减）。
     *
     * 两个边界都是刻意的：
     *  - `t ≤ 0` ⇒ 返回 **0**（完全不动）。这是「细节 = 100% ⇒ 不做降噪」的实现方式，
     *    也是「masking = 0 ⇒ 全图锐化」**不能**走这里的原因（那个语义要返回 1，由调用方特判）。
     *  - `t` 很大时结果趋近 1 ⇒ 退化为无保护，与「不设阈值」一致。
     */
    private fun guard(d: Float, t: Float): Float {
        if (t <= 0f) return 0f
        val a = abs(d)
        return if (a <= t) 1f else t / a
    }

    private fun clamp8(v: Float): Int = v.roundToInt().coerceIn(0, 255)

    /**
     * 分离式盒式模糊的单趟（横向或纵向），滑动累加 ⇒ `O(像素数)` 而非 `O(像素数 × 半径)`。
     *
     * 边界用 **clamp**（复制边缘行/列）而不是补零：补零会让画面四周出现一圈暗边，
     * 而细节算子在四周恰恰最容易被看出来。核宽取 `2r+1`，恒为奇数 ⇒ 核有明确中心，
     * 不会引入半像素位移。
     *
     * 与 `NeutralGray.boxPass` 同构（那边已过真机验收）。**刻意不复用它**：它在 retouch 包内是
     * 私有的，而把两者抽成公共工具会把两条生命周期绑在一起 —— 它们唯一的共同点只是「都用盒式模糊」，
     * 半径来源、作用域、精度口径全不同。
     */
    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val majorCount = if (horizontal) h else w
        val win = r * 2 + 1
        for (major in 0 until majorCount) {
            var accR = 0L
            var accG = 0L
            var accB = 0L
            for (k in -r..r) {
                val idx = if (horizontal) major * w + k.coerceIn(0, w - 1)
                else k.coerceIn(0, h - 1) * w + major
                val v = src[idx]
                accR += (v shr 16) and 0xff
                accG += (v shr 8) and 0xff
                accB += v and 0xff
            }
            for (minor in 0 until len) {
                val outIdx = if (horizontal) major * w + minor else minor * w + major
                dst[outIdx] = 0xff000000.toInt() or
                    (((accR / win).toInt().coerceIn(0, 255)) shl 16) or
                    (((accG / win).toInt().coerceIn(0, 255)) shl 8) or
                    (accB / win).toInt().coerceIn(0, 255)
                // 滑出窗口的是 minor-r，滑入的是 minor+r+1（两端 clamp 到 [0, len-1]，
                // 与初始累加用的 clamp 口径一致 ⇒ 边界处的和与整幅版完全相同）。
                val left = (minor - r).coerceIn(0, len - 1)
                val right = (minor + r + 1).coerceIn(0, len - 1)
                val li = if (horizontal) major * w + left else left * w + major
                val ri = if (horizontal) major * w + right else right * w + major
                accR += (src[ri] shr 16 and 0xff) - (src[li] shr 16 and 0xff)
                accG += (src[ri] shr 8 and 0xff) - (src[li] shr 8 and 0xff)
                accB += (src[ri] and 0xff) - (src[li] and 0xff)
            }
        }
    }
}
