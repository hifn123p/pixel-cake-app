package com.hifn.pixelcake.core.ml

/**
 * 一张人脸（**归一化张量坐标**，x/y ∈ [0,1]，相对模型输入张量 192×192）。
 *
 * 这是解码/抑制阶段的中间表示；投回源图后由 [FaceDetection] 承载（单位=源图像素）。
 */
data class NormFace(
    val xmin: Float,
    val ymin: Float,
    val xmax: Float,
    val ymax: Float,
    val score: Float,
    /** 6 个关键点，`(x, y)` 归一化坐标，顺序：左眼 / 右眼 / 鼻尖 / 嘴中心 / 左耳屏 / 右耳屏。 */
    val keypoints: List<Pair<Float, Float>>,
) {
    val width: Float get() = xmax - xmin
    val height: Float get() = ymax - ymin
    val area: Float get() = width * height
}

/**
 * 一张人脸（**源图像素坐标**），P1p-2 的对外产物。
 *
 * 消费方（编辑器）主要用它两件事：
 * 1. [centerX] / [centerY] —— 直接喂液化的 `centroid`，不再靠「蒙版质心猜」；
 * 2. [eyeCenterX] / [eyeCenterY] —— 大眼中心。
 *
 * 坐标一律以**源图**（调用方传入的那张 Bitmap）像素为单位，语义与
 * `Beauty.centroid(mask, w, h)` 的返回值完全一致，可直接互换。
 */
data class FaceDetection(
    val xmin: Float,
    val ymin: Float,
    val xmax: Float,
    val ymax: Float,
    val score: Float,
    /** 6 个关键点，`(x, y)` 源图像素坐标，顺序：左眼 / 右眼 / 鼻尖 / 嘴中心 / 左耳屏 / 右耳屏。 */
    val keypoints: List<Pair<Float, Float>>,
) {
    val centerX: Float get() = (xmin + xmax) * 0.5f
    val centerY: Float get() = (ymin + ymax) * 0.5f
    val width: Float get() = xmax - xmin
    val height: Float get() = ymax - ymin
    val area: Float get() = width * height

    /** 左右眼中心（关键点 0=左眼、1=右眼）；关键点缺失时返回 `null`。 */
    val eyeCenter: Pair<Float, Float>?
        get() = if (keypoints.size >= 2) {
            (keypoints[0].first + keypoints[1].first) * 0.5f to
                (keypoints[0].second + keypoints[1].second) * 0.5f
        } else {
            null
        }

    /**
     * 两眼连线在图像坐标系中的**旋转角**（弧度）——供后续「人脸摆正」使用。
     * 关键点不足时返回 `null`。
     */
    val eyeRoll: Float?
        get() = if (keypoints.size >= 2) {
            kotlin.math.atan2(
                (keypoints[1].second - keypoints[0].second).toDouble(),
                (keypoints[1].first - keypoints[0].first).toDouble(),
            ).toFloat()
        } else {
            null
        }
}
