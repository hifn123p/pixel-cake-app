package com.hifn.pixelcake.core.ml

import kotlin.math.roundToInt

/**
 * 等比缩放 + **居中**填充（letterbox）的几何换算（P1p-2，纯 Kotlin，可 JVM 单测）。
 *
 * 对应 MediaPipe 的 `ImageToTensorCalculator` 配置
 * （`face_detection.pbtxt`：`keep_aspect_ratio: true`、`border_mode: BORDER_ZERO`、
 * `output_tensor_float_range { min: -1.0 max: 1.0 }`）：
 *
 * - **必须等比缩放，不能直接拉伸**（`createScaledBitmap` 到方图会改变人脸尺度/比例，
 *   与训练分布不符，召回明显下降）；
 * - 缩放后**居中**放置，四周补 0（黑）。
 *
 * ⚠️ **补齐色假设**：`BORDER_ZERO` 在 MediaPipe 里作用于 8-bit 源域 ⇒ 补 **0（黑）**，
 * 归一化到 `[-1,1]` 即 `-1.0`。若真机上发现检测框系统性偏移/漏检，这里是第一个要复核的点。
 *
 * 换算口径（**正向**：源像素 → 张量归一化坐标）：
 * ```
 * scale    = min(dstW / srcW, dstH / srcH)
 * scaledW  = round(srcW * scale)，scaledH = round(srcH * scale)
 * offsetX  = (dstW - scaledW) / 2，offsetY = (dstH - scaledH) / 2
 * nx       = (sx * scale + offsetX) / dstW
 * ny       = (sy * scale + offsetY) / dstH
 * ```
 * 反向（**张量归一化坐标 → 源像素**）为其逆：
 * ```
 * sx = (nx * dstW - offsetX) / scale
 * sy = (ny * dstH - offsetY) / scale
 * ```
 */
class LetterboxTransform(
    val srcW: Int,
    val srcH: Int,
    val dstW: Int,
    val dstH: Int,
) {
    /** 等比缩放系数。 */
    val scale: Float

    /** 缩放后的实际像素尺寸（四舍五入后居中，可能比理论值差不到 1px）。 */
    val scaledW: Int
    val scaledH: Int

    /** 居中偏移（张量内）。 */
    val offsetX: Int
    val offsetY: Int

    init {
        require(srcW > 0 && srcH > 0) { "source size must be positive: ${srcW}x$srcH" }
        require(dstW > 0 && dstH > 0) { "tensor size must be positive: ${dstW}x$dstH" }
        scale = minOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        scaledW = (srcW * scale).roundToInt().coerceIn(1, dstW)
        scaledH = (srcH * scale).roundToInt().coerceIn(1, dstH)
        offsetX = (dstW - scaledW) / 2
        offsetY = (dstH - scaledH) / 2
    }

    /** 源图像素坐标 → 张量归一化坐标（裁剪到 [0,1]）。 */
    fun srcToTensorNorm(sx: Float, sy: Float): Pair<Float, Float> {
        val nx = ((sx * scale + offsetX) / dstW).coerceIn(0f, 1f)
        val ny = ((sy * scale + offsetY) / dstH).coerceIn(0f, 1f)
        return nx to ny
    }

    /** 张量归一化坐标 → 源图像素坐标（**不**裁剪，允许落在源图外，由调用方决定是否丢弃）。 */
    fun tensorNormToSrc(nx: Float, ny: Float): Pair<Float, Float> {
        val sx = (nx * dstW - offsetX) / scale
        val sy = (ny * dstH - offsetY) / scale
        return sx to sy
    }

    /** 把归一化检测框（张量坐标）投回**源图像素**坐标。 */
    fun toSource(norm: NormFace): FaceDetection {
        val (x0, y0) = tensorNormToSrc(norm.xmin, norm.ymin)
        val (x1, y1) = tensorNormToSrc(norm.xmax, norm.ymax)
        val kps = norm.keypoints.map { (kx, ky) ->
            val (sx, sy) = tensorNormToSrc(kx, ky)
            sx to sy
        }
        return FaceDetection(
            xmin = minOf(x0, x1),
            ymin = minOf(y0, y1),
            xmax = maxOf(x0, x1),
            ymax = maxOf(y0, y1),
            score = norm.score,
            keypoints = kps,
        )
    }

    /**
     * 生成 letterbox 后的 ARGB 图（长度 `dstW * dstH`），边框补黑 [BORDER_ARGB]。
     *
     * 采样为**最近邻**，仅供单测/兜底；生产路径由 `MlFaceProvider` 走
     * `Bitmap.createScaledBitmap`（双线性，缩小时抗锯齿更好）再拷进同位置的缓冲，
     * **几何（[scale]/[offsetX]/[offsetY]）完全一致**。
     *
     * @param srcArgb 长度须 ≥ `srcW * srcH`
     */
    fun renderNearest(srcArgb: IntArray): IntArray {
        require(srcArgb.size >= srcW * srcH) { "srcArgb.size=${srcArgb.size} < ${srcW * srcH}" }
        val dst = IntArray(dstW * dstH) { BORDER_ARGB }
        for (ty in 0 until dstH) {
            val sy = ((ty - offsetY) / scale).toInt()
            if (sy < 0 || sy >= srcH) continue
            for (tx in 0 until dstW) {
                val sx = ((tx - offsetX) / scale).toInt()
                if (sx < 0 || sx >= srcW) continue
                dst[ty * dstW + tx] = srcArgb[sy * srcW + sx]
            }
        }
        return dst
    }

    companion object {
        /** 边框填充色：0 = 纯黑（= 归一化后的 -1.0），对齐 `BORDER_ZERO`。 */
        const val BORDER_ARGB = 0
    }
}
