package com.hifn.pixelcake.core.edit.retouch

import android.graphics.Bitmap
import com.hifn.pixelcake.core.edit.BeautyParams
import com.hifn.pixelcake.core.edit.ColorTransferParams
import com.hifn.pixelcake.core.edit.InpaintStroke
import com.hifn.pixelcake.core.edit.NeutralGrayParams
import com.hifn.pixelcake.core.edit.RetouchMask
import com.hifn.pixelcake.core.edit.RetouchState
import com.hifn.pixelcake.core.ml.FaceDetection
import kotlin.math.ceil
import kotlin.math.floor

/**
 * retouch 像素仓储：`getPixels`/`setPixels` 的语义与 `android.graphics.Bitmap` 同名方法一致
 * （`stride` 为行跨距，`x/y/width/height` 为矩形）。
 *
 * 抽这一层的理由：[RetouchLayer] 的**分带编排**（带高、halo、算子顺序、追色统计）是本阶段的
 * 关键逻辑，必须被 JVM 单测钉死；而 `android.graphics.Bitmap` 在 JVM 单测里是 not-mocked 桩。
 * 生产侧接 [BitmapStore]，测试侧接内存实现，两侧共用同一份编排。
 */
internal interface PixelStore {
    val w: Int
    val h: Int
    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int)
    fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int)
}

private class BitmapStore(private val bitmap: Bitmap) : PixelStore {
    override val w: Int get() = bitmap.width
    override val h: Int get() = bitmap.height

    override fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) =
        bitmap.getPixels(pixels, offset, stride, x, y, width, height)

    override fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) =
        bitmap.setPixels(pixels, offset, stride, x, y, width, height)
}

/**
 * retouch 整图 pass 编排（P1b-4 / `docs/P1b_DESIGN.md` §2）。
 *
 * 施加顺序：**磨皮 → 液化 → 祛瑕 → 追色**。预览（代理）与导出（全分辨率）用同一算法 + 同一 Mask
 * （经 `resampleTo` 对齐），保证「预览所见即导出所得」。
 *
 * 液化的锚点自 **P1p-2c** 起可由**人脸检测**覆盖（见 [FaceAnchor]）；不给就仍是 P1 的「蒙版质心猜」。
 *
 * **内存纪律（FIX_LIST F05 / 第二轮复审 R10）**：这里曾是最后一个整幅 `IntArray(w·h)`
 * （33MP 下 ≈131MB，四算子共用）。现改为**全程分带 / 分块**，任何时刻只持有与「带高 + halo」
 * 同量级的缓冲：
 *  1. **磨皮**：逐带读入「[BAND_ROWS] 行 + 上下 `radius` 行 halo」的窗口，在窗口上整体施加，再写回核心行。
 *     窗口自带 halo ⇒ 核心行的 box 窗口恒不触边 ⇒ 与整幅结果逐位一致；窗口顶部 halo 必须用
 *     **上一带留存的原始行**（核心行是原位写回的，上一带写过后那几行已非原始值）。
 *  2. **液化**：只在其**源行跨度**（蒙版支撑行 ± 位移上界，有界）上开条带；位移与蒙版采样用绝对坐标
 *     （`rowOffset`），锚点在条带外算一次传入。条带外一律不受影响（蒙版外是恒等映射）。
 *  3. **祛瑕**：逐描迹取一个覆盖 `2r` 的小块，施加后写回，无需整幅。
 *  4. **追色**：统计量只是 6 个标量 —— 两趟只读累加、再一趟逐带施加；累加器跨带按行序推进，
 *     求和顺序与整幅循环完全一致，故逐位相同。
 *
 * 阶段之间靠仓储传递结果（每阶段写回后再进下一阶段），因此与「整幅数组上依次施加」严格等价 ——
 * 由 `RetouchLayerTest` 用朴素整幅参照实现钉死。
 */
object RetouchLayer {

    /**
     * 液化锚点覆盖（P1p-2c）：由**人脸检测**给出，单位是**渲染分辨率下的绝对像素**。
     *
     * 存在的理由：P1 的液化锚点是「蒙版质心猜」—— 蒙版是皮肤概率图，其质心未必是脸中心（头发、
     * 手臂、露肤的肩颈都会把质心拽偏）；`eyeEnlarge` 更是需要一个「眼睛在哪」的先验。
     * 检测到人脸后直接喂真实几何量，液化才落在该落的地方。
     *
     * **`null` 语义**：调用方拿不到人脸（模型不可用 / 图里没人脸）时**整个 `FaceAnchor` 传 `null`**，
     * 编排退回 [Beauty.centroid]（P1 行为，**逐位相同**）。
     *
     * ⚠️ **必须按归一化坐标换算**：人脸检测跑在**源图**（ARW 时是内嵌预览，如 3504×2336）上，而
     * retouch 跑在**渲染分辨率**（RAW 预览 = 16-bit 线性代理 2048×1366，导出 = 全分辨率）上 ——
     * 两者尺寸不同，直接把检测像素当渲染像素用会让锚点整体偏移。换算统一走
     * [FaceAnchor.fromDetection]（同一个函数同时服务预览与导出两条路径，只是传入的 `w/h` 不同）。
     */
    data class FaceAnchor(
        /** 脸框中心（绝对像素）—— `slimFace` / `slimJaw` 的锚点。 */
        val faceX: Float,
        val faceY: Float,
        /** 双眼连线中点（绝对像素）—— `eyeEnlarge` 的锚点。 */
        val eyeX: Float,
        val eyeY: Float,
    ) {
        companion object {
            /**
             * 由**源图空间**的一张人脸建「渲染空间」锚点。
             *
             * 检测跑在**源图**（`srcW × srcH`，ARW 时是内嵌预览）上，而 retouch 跑在**渲染分辨率**
             * （`w × h`，RAW 预览 = 线性代理 / 导出 = 全分辨率）上 —— 两者尺寸不同，必须经
             * **归一化坐标**中转；直接把检测像素当渲染像素用会让锚点整体偏移，且偏移量随分辨率变化
             * （预览看着还行、导出就跑偏）。
             *
             * [FaceDetection.eyeCenter] 缺失（关键点不足）时眼心退回脸框中心 ⇒ 与 P1 的
             * `eyeEnlarge` 口径一致（锚点仍是同一个点），不会突然跑偏。
             *
             * @return 已 clamp 到 `[0, w-1] × [0, h-1]` 的锚点；任一尺寸非法返回 `null`
             *   （调用方据此退回「蒙版质心猜」）。
             */
            fun fromDetection(
                face: FaceDetection,
                srcW: Int, srcH: Int,
                w: Int, h: Int
            ): FaceAnchor? {
                if (srcW <= 0 || srcH <= 0 || w <= 0 || h <= 0) return null
                val mx = (w - 1).toFloat()
                val my = (h - 1).toFloat()
                val (ex, ey) = face.eyeCenter ?: (face.centerX to face.centerY)
                return FaceAnchor(
                    faceX = (face.centerX / srcW).coerceIn(0f, 1f) * mx,
                    faceY = (face.centerY / srcH).coerceIn(0f, 1f) * my,
                    eyeX = (ex / srcW).coerceIn(0f, 1f) * mx,
                    eyeY = (ey / srcH).coerceIn(0f, 1f) * my,
                )
            }
        }
    }

    /** 分带行数。33MP 下横向缓冲 `(256+2r)×7008×4`（`r = 0.01×4672 ≈ 46` ⇒ ≈9.8MB）。 */
    private const val BAND_ROWS = 256

    /** 液化位移系数（与 [Beauty] 内部一致；这里只用来估源行跨度的**上界**）。 */
    private const val BEAUTY_K = 0.3f

    /**
     * 施加到 `bitmap` 上（原位修改）。
     *
     * @param faceAnchor 人脸检测给出的液化锚点；`null`（默认）= 退回「蒙版质心猜」（P1 行为）。
     */
    fun apply(
        bitmap: Bitmap,
        state: RetouchState,
        mask: RetouchMask?,
        faceAnchor: FaceAnchor? = null
    ) {
        apply(BitmapStore(bitmap), state, mask, faceAnchor)
    }

    /** 施加到任意 [PixelStore]（生产是 Bitmap，单测是内存数组）—— 编排逻辑的唯一入口。 */
    internal fun apply(
        store: PixelStore,
        state: RetouchState,
        mask: RetouchMask?,
        faceAnchor: FaceAnchor? = null
    ) {
        val w = store.w
        val h = store.h
        if (w <= 0 || h <= 0) return
        val m = mask?.resampleTo(w, h)

        val ngOn = m != null && state.neutralGray.strength > 0f
        val beautyOn = m != null &&
            (state.beauty.slimFace > 0f || state.beauty.slimJaw > 0f || state.beauty.eyeEnlarge > 0f)
        val inpaintOn = state.inpaint.isNotEmpty()
        val ctOn = ColorTransfer.isActive(state.colorTransfer)
        if (!ngOn && !beautyOn && !inpaintOn && !ctOn) return

        if (ngOn) neutralGrayPhase(store, w, h, state.neutralGray, m!!)
        if (beautyOn) beautyPhase(store, w, h, state.beauty, m!!, faceAnchor)
        if (inpaintOn) inpaintPhase(store, w, h, state.inpaint)
        if (ctOn) colorTransferPhase(store, w, h, state.colorTransfer)
    }

    // ---------------- 1. 磨皮：分带 ----------------

    private fun neutralGrayPhase(
        store: PixelStore, w: Int, h: Int, params: NeutralGrayParams, mask: RetouchMask
    ) {
        val radius = params.radiusPx.coerceAtLeast(1)
        val band = maxOf(BAND_ROWS, radius)
        var y = 0
        var carry = IntArray(0) // 位于 [y-radius, y) 的**原始**像素（带上沿 halo）
        var carryRows = 0
        while (y < h) {
            val n = minOf(band, h - y)
            val top = (y - radius).coerceAtLeast(0)
            val bot = (y + n - 1 + radius).coerceAtMost(h - 1)
            val headRows = y - top
            val winRows = bot - top + 1
            val buf = IntArray(winRows * w)

            if (headRows > 0) {
                if (carryRows >= headRows) {
                    System.arraycopy(carry, 0, buf, 0, headRows * w)
                } else {
                    store.getPixels(buf, 0, w, 0, top, w, headRows)
                }
            }
            // 核心行及其下方 halo —— 这些行还没被写过，读到的是原始值
            store.getPixels(buf, headRows * w, w, 0, y, w, bot - y + 1)

            // 顺手留存下一带的顶部 halo（此刻 buf 里这些行仍是原始值）
            if (y + n < h && n >= radius) {
                carryRows = radius
                carry = IntArray(radius * w)
                System.arraycopy(buf, (y + n - radius - top) * w, carry, 0, radius * w)
            } else {
                carryRows = 0
                carry = IntArray(0)
            }

            // 整段窗口施加一次：窗口自带 halo，核心行的 box 窗口恒落在窗口内 ⇒ 与整幅逐位一致
            NeutralGray.apply(buf, w, winRows, params, OffsetMask(mask, top))

            store.setPixels(buf, headRows * w, w, 0, y, w, n)
            y += n
        }
    }

    /** 把「窗口局部行」翻译成绝对行的蒙版视图（窗口首行的绝对行号为 [rowOffset]）。 */
    private class OffsetMask(private val base: RetouchMask, private val rowOffset: Int) : RetouchMask {
        override fun sample(px: Int, py: Int) = base.sample(px, py + rowOffset)
        override fun resampleTo(w: Int, h: Int) = this
    }

    // ---------------- 2. 液化：源行跨度 ----------------

    private fun beautyPhase(
        store: PixelStore, w: Int, h: Int, params: BeautyParams, mask: RetouchMask,
        faceAnchor: FaceAnchor?
    ) {
        // 锚点：人脸检测给了就用它（脸框中心 / 眼心），否则退回「蒙版质心猜」（P1 行为）。
        // 注意 `?:` 的短路：`faceAnchor != null` 时**不**再扫全图求蒙版质心（省一次全图扫描）。
        val centroid = faceAnchor?.let { it.faceX to it.faceY }
            ?: Beauty.centroid(mask, w, h)
            ?: return
        val cy = centroid.second
        val eyeY = faceAnchor?.eyeY ?: cy
        val span = maskRowSpan(mask, w, h) ?: return
        val mTop = span[0]
        val mBot = span[1]

        // 源行范围（保守上界）：下界取 min(蒙版顶, 锚点)；上界取 蒙版底 + k·(蒙版底 - cy)（mv ≤ 1）。
        // 再各留 1 行，保证双线性取样（floor(dy) 与 floor(dy)+1）落在条带内。
        //
        // ⚠️ **眼心必须参与上下界**：`eyeEnlarge` 是「把源点拉向眼心」，而眼心通常在脸框中心
        // **上方**（眼睛高于脸框几何中心）⇒ 源行可以一直取到 `eyeY < cy`。若下界仍只取
        // `min(蒙版顶, cy)`，条带就会裁掉眼睛上方那几行，大眼的双线性取样落到条带外（越界裁剪，
        // 画质悄悄变差而不报错）。`faceAnchor == null` 时 `eyeY == cy`，两式退化回原式 ⇒ 逐位不变。
        val anchorTop = minOf(cy, eyeY)
        val anchorBot = maxOf(cy, eyeY)
        val loComputed = minOf(mTop, floor(anchorTop).toInt())
        val up = BEAUTY_K * (mBot - cy).coerceAtLeast(0f)
        val hiComputed = maxOf(mBot, ceil(anchorBot).toInt()) + ceil(up.toDouble()).toInt()
        val lo = (loComputed - 1).coerceIn(0, h - 1)
        val hi = (hiComputed + 1).coerceIn(lo, h - 1)

        val spanRows = hi - lo + 1
        val buf = IntArray(spanRows * w)
        store.getPixels(buf, 0, w, 0, lo, w, spanRows)
        Beauty.apply(
            buf, w, spanRows, params, mask,
            rowOffset = lo,
            centroid = centroid,
            eyeCentroid = faceAnchor?.let { it.eyeX to it.eyeY }
        )
        store.setPixels(buf, 0, w, 0, lo, w, spanRows)
    }

    /** 蒙版支撑（`mv > 0`）的行范围 `[top, bot]`；无支撑返回 `null`。 */
    private fun maskRowSpan(mask: RetouchMask, w: Int, h: Int): IntArray? {
        var top = -1
        var bot = -1
        for (y in 0 until h) {
            var hit = false
            for (x in 0 until w) {
                if (mask.sample(x, y) > 0f) {
                    hit = true
                    break
                }
            }
            if (hit) {
                if (top < 0) top = y
                bot = y
            }
        }
        return if (top < 0) null else intArrayOf(top, bot)
    }

    // ---------------- 3. 祛瑕：逐描迹小块 ----------------

    private fun inpaintPhase(store: PixelStore, w: Int, h: Int, strokes: List<InpaintStroke>) {
        for (s in strokes) {
            val r = s.r.coerceAtLeast(1)
            val half = 2 * r
            val x0 = (s.x - half).coerceAtLeast(0)
            val y0 = (s.y - half).coerceAtLeast(0)
            val x1 = (s.x + half).coerceAtMost(w - 1)
            val y1 = (s.y + half).coerceAtMost(h - 1)
            val pw = x1 - x0 + 1
            val ph = y1 - y0 + 1
            if (pw <= 0 || ph <= 0) continue
            val patch = IntArray(pw * ph)
            store.getPixels(patch, 0, pw, x0, y0, pw, ph)
            Inpaint.applyOne(patch, pw, ph, InpaintStroke(s.x - x0, s.y - y0, r))
            store.setPixels(patch, 0, pw, x0, y0, pw, ph)
        }
    }

    // ---------------- 4. 追色：两趟只读统计 + 一趟逐带施加 ----------------

    private fun colorTransferPhase(store: PixelStore, w: Int, h: Int, params: ColorTransferParams) {
        val buf = IntArray(w * minOf(BAND_ROWS, h))
        // 与整幅实现同口径：n = 像素总数
        val n = w.toDouble() * h

        val sum = DoubleArray(3)
        eachBand(store, w, h, buf) { len -> ColorTransfer.accumulateSum(buf, len, sum) }

        val mean = ColorTransfer.meanOf(sum, n)

        val varSum = DoubleArray(3)
        eachBand(store, w, h, buf) { len -> ColorTransfer.accumulateVariance(buf, len, mean, varSum) }

        val stats = ColorTransfer.statsOf(sum, varSum, n)
        eachBand(store, w, h, buf, writeBack = true) { len ->
            ColorTransfer.applyWithStats(buf, len, params, stats)
        }
    }

    /** 逐带读出到 [buf]，对本带有效长度调 [action]；[writeBack] 为真时回写。 */
    private inline fun eachBand(
        store: PixelStore, w: Int, h: Int, buf: IntArray,
        writeBack: Boolean = false, action: (Int) -> Unit
    ) {
        var y = 0
        while (y < h) {
            val rows = minOf(BAND_ROWS, h - y)
            store.getPixels(buf, 0, w, 0, y, w, rows)
            action(rows * w)
            if (writeBack) store.setPixels(buf, 0, w, 0, y, w, rows)
            y += rows
        }
    }
}
