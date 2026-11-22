package com.hifn.pixelcake.core.edit

import com.hifn.pixelcake.core.decode.LinearImage
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v0.8.0：自由裁剪的**纯几何层**（JVM 可测；Bitmap 操作留在 UI 层）。
 *
 * ## 坐标口径（与 Q5 旋转同一套归一化约定）
 *
 * **裁剪矩形存在 pristine（未旋转）空间** —— 这是本设计的核心解耦：
 *
 * - 旋转 q 变化时裁剪矩形**不动**（pristine 空间与旋转无关），`applySessionRotation`
 *   重推导时把矩形映射进旋转空间再裁；
 * - RAW 导出渲染在未旋转空间 ⇒ 矩形**直接可用**（零转换），分带窗口按它收窄；
 * - sRGB 导出按「解码 → 旋转 → 裁剪位图」烘焙；
 * - 预览重推导 = pristine → 旋转 → 裁剪（bitmap + linear 各一次）。
 *
 * 矩形各分量是**归一化** [0,1]（相对所在空间的全幅），`l < r`、`t < b`。
 * 90° 步进旋转下轴对齐矩形映射后仍轴对齐（角点映射 + min/max 精确无采样误差）。
 */
@Serializable
data class CropRect(
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float
) {
    init {
        require(l < r && t < b) { "empty crop rect $this" }
        require(l >= 0f && t >= 0f && r <= 1f && b <= 1f) { "crop rect out of range $this" }
    }

    /** 是否近似全幅（默认档不落库，避免无意义状态）。容差覆盖整数取整的半像素抖动。 */
    val isFullFrame: Boolean
        get() = l <= 0.0005f && t <= 0.0005f && r >= 0.9995f && b >= 0.9995f

    /** 宽高比（像素口径下才有意义；归一化分量 × 全幅尺寸后才是真比例）。 */
    fun aspect(fullW: Int, fullH: Int): Float {
        val w = (r - l) * fullW
        val h = (b - t) * fullH
        return if (h <= 0f) 1f else w / h
    }
}

object EditCrop {

    /**
     * pristine 空间矩形 → 旋转后（预览）空间矩形。
     *
     * ⚠️ 刻意用**像素连续角点映射**（[mapCorner]）而不是 ：
     * 仓库的归一化约定是 u = x/L（L = 长边，非方形帧 u 不到 1），而 [CropRect] 是
     * **逐轴 [0,1]** —— 两套口径不能混。像素坐标与约定无关，且边缘（x = w）映射后
     * 精确落在旋转帧边缘（q2 的 x = w → w − x = 0），全幅帧不丢行列。
     * 90° 步进下矩形保持轴对齐（角点映射 + min/max）。
     */
    fun rectToRotated(rect: CropRect, quarterTurns: Int, w: Int, h: Int): CropRect {
        val q = ((quarterTurns % 4) + 4) % 4
        if (q == 0) return rect
        val rw = if (q % 2 == 1) h else w
        val rh = if (q % 2 == 1) w else h
        val corners = listOf(
            mapCorner(rect.l * w, rect.t * h, q, w, h),
            mapCorner(rect.r * w, rect.t * h, q, w, h),
            mapCorner(rect.l * w, rect.b * h, q, w, h),
            mapCorner(rect.r * w, rect.b * h, q, w, h)
        )
        val ls = corners.map { it.first / rw }
        val ts = corners.map { it.second / rh }
        return CropRect(min(ls.min(), ls.max()), min(ts.min(), ts.max()), max(ls.min(), ls.max()), max(ts.min(), ts.max()))
    }

    /**
     * 旋转后（预览）空间矩形 → pristine 空间矩形（[rectToRotated] 的逆，反向转 4−q）。
     */
    fun rectToUnrotated(rect: CropRect, quarterTurns: Int, w: Int, h: Int): CropRect {
        val q = ((quarterTurns % 4) + 4) % 4
        if (q == 0) return rect
        val rw = if (q % 2 == 1) h else w
        val rh = if (q % 2 == 1) w else h
        val corners = listOf(
            mapCorner(rect.l * rw, rect.t * rh, (4 - q) % 4, rw, rh),
            mapCorner(rect.r * rw, rect.t * rh, (4 - q) % 4, rw, rh),
            mapCorner(rect.l * rw, rect.b * rh, (4 - q) % 4, rw, rh),
            mapCorner(rect.r * rw, rect.b * rh, (4 - q) % 4, rw, rh)
        )
        val ls = corners.map { it.first / w }
        val ts = corners.map { it.second / h }
        return CropRect(min(ls.min(), ls.max()), min(ts.min(), ts.max()), max(ls.min(), ls.max()), max(ts.min(), ts.max()))
    }

    /** 连续坐标的 90° 步进旋转（边缘映射，非像素中心）：q1: (x,y)→(h−y, x)；q2: (w−x, h−y)；q3: (y, w−x)。 */
    private fun mapCorner(x: Float, y: Float, q: Int, w: Int, h: Int): Pair<Float, Float> = when (q) {
        1 -> (h - y) to x
        2 -> (w - x) to (h - y)
        3 -> y to (w - x)
        else -> x to y
    }

    /**
     * 复合矩形：[inner] 表达在 [outer] 内部（两者都是各自空间的归一化矩形）。
     * 二次裁剪场景：预览已是上一次裁剪的结果，新 UI 矩形在**裁剪后预览**空间，
     * 存档前先合回全幅。`EditCrop.composeRects(inner, EditCrop.rectToRotated(crop, q, w, h))`。
     */
    fun composeRects(inner: CropRect, outer: CropRect): CropRect {
        val ow = outer.r - outer.l
        val oh = outer.b - outer.t
        return CropRect(
            outer.l + inner.l * ow,
            outer.t + inner.t * oh,
            outer.l + inner.r * ow,
            outer.t + inner.b * oh
        )
    }

    /** 裁剪后尺寸（像素；宽高各至少 1px）。 */
    fun croppedSize(rect: CropRect, w: Int, h: Int): Pair<Int, Int> {
        val win = renderWindow(rect, w, h)
        return win[2] to win[3]
    }

    /**
     * 渲染窗口：全幅 (w×h) 上裁剪窗口的 `[x0, y0, cw, ch]`（像素，含半像素取整）。
     * 分带渲染/位图裁剪共用这一个口径，保证逐位一致。
     */
    fun renderWindow(rect: CropRect, w: Int, h: Int): IntArray {
        val x0 = (rect.l * w).roundToInt().coerceIn(0, w - 1)
        val y0 = (rect.t * h).roundToInt().coerceIn(0, h - 1)
        val x1 = (rect.r * w).roundToInt().coerceIn(x0 + 1, w)
        val y1 = (rect.b * h).roundToInt().coerceIn(y0 + 1, h)
        return intArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * 16-bit 线性母版裁剪：子矩形拷贝（纯函数，零精度损失，`EditCropTest` 把守）。
     * 归一化矩形按 [LinearImage] 自身尺寸换算 —— 与位图裁剪共用 [renderWindow] 口径。
     */
    fun cropLinear(lin: LinearImage, rect: CropRect): LinearImage {
        val win = renderWindow(rect, lin.width, lin.height)
        val (x0, y0, cw, ch) = win
        if (cw == lin.width && ch == lin.height) return lin
        val out = ShortArray(cw * ch * 3)
        for (row in 0 until ch) {
            val srcBase = ((y0 + row) * lin.width + x0) * 3
            val dstBase = row * cw * 3
            val rowShorts = cw * 3
            System.arraycopy(lin.data, srcBase, out, dstBase, rowShorts)
        }
        return LinearImage(cw, ch, out)
    }

    /** UI 拖动后的钳制：分量排序 + 收进 [0,1]（保留最小 1% 宽高，防退化成零面积）。 */
    fun clamp(l: Float, t: Float, r: Float, b: Float): CropRect {
        val minSpan = 0.01f
        val ll = l.coerceIn(0f, 1f - minSpan)
        val tt = t.coerceIn(0f, 1f - minSpan)
        val rr = r.coerceIn(ll + minSpan, 1f)
        val bb = b.coerceIn(tt + minSpan, 1f)
        return CropRect(ll, tt, rr, bb)
    }

    // ---------------- 拖拽数学（UI 覆盖层用；纯函数，`EditCropTest` 把守） ----------------

    /** 拖动目标：移动整框 / 四角之一（对角锚定）。 */
    enum class CropDrag { MOVE, TL, TR, BL, BR }

    /**
     * 拖动一步后的矩形。
     *
     * @param dxN/dyN 本步位移（归一化，相对裁剪框所在帧）
     * @param aspectPx 目标宽高比（像素口径 w/h）；null = 自由
     * @param frameAspect 帧宽高比 W/H（归一化 → 像素比例换算用）
     */
    fun dragRect(
        kind: CropDrag,
        rect: CropRect,
        dxN: Float,
        dyN: Float,
        aspectPx: Float?,
        frameAspect: Float
    ): CropRect {
        return when (kind) {
            CropDrag.MOVE -> {
                val w = rect.r - rect.l
                val h = rect.b - rect.t
                val nl = (rect.l + dxN).coerceIn(0f, 1f - w)
                val nt = (rect.t + dyN).coerceIn(0f, 1f - h)
                CropRect(nl, nt, nl + w, nt + h)
            }
            CropDrag.TL -> cornerDrag(rect, dxN, dyN, aspectPx, frameAspect, anchorRight = true, anchorBottom = true)
            CropDrag.TR -> cornerDrag(rect, dxN, dyN, aspectPx, frameAspect, anchorRight = false, anchorBottom = true)
            CropDrag.BL -> cornerDrag(rect, dxN, dyN, aspectPx, frameAspect, anchorRight = true, anchorBottom = false)
            CropDrag.BR -> cornerDrag(rect, dxN, dyN, aspectPx, frameAspect, anchorRight = false, anchorBottom = false)
        }
    }

    /** 角拖：对角锚定，被拖角随手指；有比例约束时按宽度驱动、越界回退高度驱动。 */
    private fun cornerDrag(
        rect: CropRect, dxN: Float, dyN: Float,
        aspectPx: Float?, frameAspect: Float,
        anchorRight: Boolean, anchorBottom: Boolean
    ): CropRect {
        // 锚定角（对角）
        val ax = if (anchorRight) rect.r else rect.l
        val ay = if (anchorBottom) rect.b else rect.t
        // 被拖角当前位置（位移加在**被拖角**上，不是锚点上）
        val cx = if (anchorRight) rect.l else rect.r
        val cy = if (anchorBottom) rect.t else rect.b
        // 归一化下的目标比例：wN/hN = aspectPx / frameAspect
        val aspectNorm = aspectPx?.let { it / frameAspect.coerceAtLeast(0.0001f) }
        var mx = (cx + dxN).coerceIn(0f, 1f)
        var my = (cy + dyN).coerceIn(0f, 1f)
        if (aspectNorm != null) {
            val wN = kotlin.math.abs(mx - ax)
            var hN = wN / aspectNorm
            val maxH = if (anchorBottom) ay else 1f - ay
            if (hN > maxH) {
                // 高度到顶 → 改按高度驱动宽度
                hN = maxH
                val wN2 = hN * aspectNorm
                mx = if (anchorRight) ax - wN2 else ax + wN2
            } else {
                my = if (anchorBottom) ay - hN else ay + hN
            }
            val minSpanN = 0.01f
            if (hN < minSpanN || kotlin.math.abs(mx - ax) < minSpanN) {
                // 退化保护：比例导致过小 → 放弃比例，走自由拖
                val fx = (cx + dxN).coerceIn(0f, 1f)
                val fy = (cy + dyN).coerceIn(0f, 1f)
                return clamp(min(ax, fx), min(ay, fy), max(ax, fx), max(ay, fy))
            }
        }
        val l = min(ax, mx); val r = max(ax, mx)
        val t = min(ay, my); val b = max(ay, my)
        return clamp(l, t, r, b)
    }

    /** 比例约束：以当前框中心为中心取该比例的最大矩形（[aspectPx] = 像素口径 w/h）。 */
    fun aspectFit(current: CropRect, aspectPx: Float, frameW: Float, frameH: Float): CropRect {
        val cx = (current.l + current.r) / 2f
        val cy = (current.t + current.b) / 2f
        // 归一化下的目标比例：wN/hN = aspectPx / (W/H)
        val aspectNorm = aspectPx / (frameW / frameH).coerceAtLeast(0.0001f)
        val wN = if (aspectNorm >= 1f) 1f else aspectNorm
        val hN = if (aspectNorm >= 1f) 1f / aspectNorm else 1f
        val l = (cx - wN / 2f).coerceIn(0f, 1f - wN)
        val t = (cy - hN / 2f).coerceIn(0f, 1f - hN)
        return clamp(l, t, l + wN, t + hN)
    }

    /** 命中测试：归一化触点 → 拖动目标；框外返回 null（无操作，不吞掉也不误移）。 */
    fun hitKind(posXn: Float, posYn: Float, rect: CropRect, cornerR: Float): CropDrag? {
        val nearL = kotlin.math.abs(posXn - rect.l) <= cornerR
        val nearR = kotlin.math.abs(posXn - rect.r) <= cornerR
        val nearT = kotlin.math.abs(posYn - rect.t) <= cornerR
        val nearB = kotlin.math.abs(posYn - rect.b) <= cornerR
        if (nearL && nearT) return CropDrag.TL
        if (nearR && nearT) return CropDrag.TR
        if (nearL && nearB) return CropDrag.BL
        if (nearR && nearB) return CropDrag.BR
        val inside = posXn > rect.l && posXn < rect.r && posYn > rect.t && posYn < rect.b
        return if (inside) CropDrag.MOVE else null
    }
}
