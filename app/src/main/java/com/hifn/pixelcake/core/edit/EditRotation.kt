package com.hifn.pixelcake.core.edit

import com.hifn.pixelcake.core.decode.LinearImage
import kotlin.math.max

/**
 * Q5 手动旋转的**纯几何层**（JVM 可测；Bitmap 操作在 UI 层经 [rotateQuarterBitmap] 同一口径）。
 *
 * ## 坐标口径（与本仓归一化约定同源）
 *
 * 归一化坐标 `u = (x + 0.5) / L`、`v = (y + 0.5) / L`，`L = max(w, h)`。
 * 旋转 q×90°（顺时针）把未旋转帧映射到预览/导出空间：
 * - q1：(x, y) → (H-1-y, x)，目标尺寸 H×W
 * - q2：(x, y) → (W-1-x, H-1-y)
 * - q3：(x, y) → (y, W-1-x)
 *
 * 归一化映射（用未旋转 w/h 推导，L 旋转不变）：
 * - q1：(u, v) → (h/L − v, u)
 * - q2：(u, v) → (w/L − u, h/L − v)
 * - q3：(u, v) → (v, w/L − u)
 */
object EditRotation {

    /** 归一化点：未旋转空间 → 旋转后（预览）空间。 */
    fun mapNormPoint(u: Float, v: Float, quarterTurns: Int, w: Int, h: Int): Pair<Float, Float> {
        val l = max(w, h).toFloat()
        val wl = w / l
        val hl = h / l
        return when (norm(q = quarterTurns)) {
            1 -> (hl - v) to u
            2 -> (wl - u) to (hl - v)
            3 -> v to (wl - u)
            else -> u to v
        }
    }

    /** 归一化点：旋转后（预览）空间 → 未旋转空间（导出烘焙时，把预览空间采集的量映射回去）。 */
    fun unmapNormPoint(u: Float, v: Float, quarterTurns: Int, w: Int, h: Int): Pair<Float, Float> {
        val l = max(w, h).toFloat()
        val wl = w / l
        val hl = h / l
        return when (norm(q = quarterTurns)) {
            1 -> v to (hl - u)
            2 -> (wl - u) to (hl - v)
            3 -> (wl - v) to u
            else -> u to v
        }
    }

    /**
     * 蒙版采样坐标映射：渲染空间（未旋转）像素 → 旋转后蒙版空间的像素。
     * [RotatedMask] 用它把「预览空间采集的蒙版」无损套到未旋转的渲染帧上。
     */
    fun mapMaskPixel(px: Int, py: Int, quarterTurns: Int, w: Int, h: Int): Pair<Int, Int> {
        return when (norm(q = quarterTurns)) {
            1 -> (h - 1 - py) to px
            2 -> (w - 1 - px) to (h - 1 - py)
            3 -> py to (w - 1 - px)
            else -> px to py
        }
    }

    /**
     * 液化锚点映射：旋转后（预览）空间的锚点 → 未旋转渲染空间的锚点。
     * [a] 的坐标按**旋转后渲染尺寸**（q 奇数时 = h×w）给出。
     */
    fun mapAnchorToRender(a: RetouchLayer.FaceAnchor, quarterTurns: Int, w: Int, h: Int): RetouchLayer.FaceAnchor {
        val q = norm(q = quarterTurns)
        if (q == 0) return a
        return when (q) {
            1 -> RetouchLayer.FaceAnchor(
                faceX = a.faceY, faceY = (h - 1 - a.faceX),
                eyeX = a.eyeY, eyeY = (h - 1 - a.eyeX)
            )
            2 -> RetouchLayer.FaceAnchor(
                faceX = (w - 1 - a.faceX), faceY = (h - 1 - a.faceY),
                eyeX = (w - 1 - a.eyeX), eyeY = (h - 1 - a.eyeY)
            )
            else -> RetouchLayer.FaceAnchor(
                faceX = (w - 1 - a.faceY), faceY = a.faceX,
                eyeX = (w - 1 - a.eyeY), eyeY = a.eyeX
            )
        }
    }

    /** 线性母版的 q×90° 旋转（16-bit 精度无损重排，宽高对 q 奇数互换）。 */
    fun rotateLinear(image: LinearImage, quarterTurns: Int): LinearImage {
        val q = norm(q = quarterTurns)
        if (q == 0) return image
        val w = image.width
        val h = image.height
        val out = ShortArray(image.data.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val s = (y * w + x) * 3
                val dx: Int
                val dy: Int
                when (q) {
                    1 -> { dx = h - 1 - y; dy = x }
                    2 -> { dx = w - 1 - x; dy = h - 1 - y }
                    else -> { dx = y; dy = w - 1 - x }
                }
                val nw = if (q == 2) w else h
                val d = (dy * nw + dx) * 3
                out[d] = image.data[s]
                out[d + 1] = image.data[s + 1]
                out[d + 2] = image.data[s + 2]
            }
        }
        return if (q == 2) LinearImage(w, h, out) else LinearImage(h, w, out)
    }

    private fun norm(q: Int): Int = ((q % 4) + 4) % 4
}

/**
 * 蒙版装饰器（Q5）：把「预览（旋转后）空间」的蒙版套到「未旋转渲染空间」上。
 *
 * RAW 导出时，渲染在未旋转帧上分带进行，而蒙版（ML 网格 / 画笔栅格）是用户在
 * **旋转后的预览**里采集的 —— 本装饰器在采样时把渲染坐标映射回蒙版空间，
 * 使 RAW + 旋转 + 蒙版/液化全部一致（ML 网格是 256×256 正方形 ⇒ 纯下标重排，零重采样）。
 */
class RotatedMask(
    private val inner: RetouchMask,
    private val quarterTurns: Int,
    /** 未旋转渲染帧尺寸。 */
    private val w: Int,
    private val h: Int
) : RetouchMask {
    override fun sample(px: Int, py: Int): Float {
        val (mx, my) = EditRotation.mapMaskPixel(px, py, quarterTurns, w, h)
        return inner.sample(mx, my)
    }

    override fun resampleTo(nw: Int, nh: Int): RetouchMask =
        RotatedMask(inner, quarterTurns, nw, nh)
}
