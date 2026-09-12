package com.hifn.pixelcake.core.ml

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 单个 SSD anchor（中心 + 宽高，均为**归一化张量坐标**）。
 *
 * 人脸检测模型输出的不是坐标本身，而是「相对 anchor 的偏移」。要还原成框，
 * 必须先按模型规格造出 anchor 网格 —— 这就是本文件的全部职责。
 */
class FaceAnchor(
    val xCenter: Float,
    val yCenter: Float,
    val w: Float,
    val h: Float,
)

/**
 * SSD anchor 生成（MediaPipe `SsdAnchorsCalculator` 的逐行 Kotlin 移植，P1p-2）。
 *
 * **为什么必须自己实现**：`.tflite` 里**不含** anchor 与阈值，它们在同目录的
 * `mediapipe/modules/face_detection/face_detection_*.pbtxt` 里，由 C++ 计算器生成后
 * 作为 side packet 喂给解码器。端侧自持底层（与 LibRaw/PTP/retouch 一致）就得自己造。
 *
 * 对照源码：`mediapipe/calculators/tflite/ssd_anchors_calculator.cc`
 * （`CalculateScale` / `GenerateAnchors` / `GetFeatureMapDimensions`）。
 *
 * 纯 Kotlin、零 Android 依赖，可 JVM 单测。
 */
object FaceAnchors {

    /**
     * anchor 规格（对应 pbtxt 里的 `SsdAnchorsCalculatorOptions` + `FaceDetectionOptions` 覆盖项）。
     *
     * 默认值取自 `mediapipe/modules/face_detection/face_detection.pbtxt` 的
     * `min_scale: 0.1484375`、`max_scale: 0.75`、`anchor_offset_x/y: 0.5`、
     * `aspect_ratios: 1.0`、`fixed_anchor_size: true`。
     */
    data class Spec(
        val inputWidth: Int,
        val inputHeight: Int,
        val numLayers: Int,
        val strides: List<Int>,
        val minScale: Float = 0.1484375f,
        val maxScale: Float = 0.75f,
        val aspectRatios: List<Float> = listOf(1f),
        val interpolatedScaleAspectRatio: Float = 1f,
        val fixedAnchorSize: Boolean = true,
        val reduceBoxesInLowestLayer: Boolean = false,
        val anchorOffsetX: Float = 0.5f,
        val anchorOffsetY: Float = 0.5f,
    )

    /**
     * `face_detection_full_range(_sparse).tflite`：192×192、1 层、stride 4。
     *
     * `interpolated_scale_aspect_ratio: 0.0`（pbtxt 显式覆盖）⇒ 每格 1 个 anchor，
     * 特征图 `ceil(192/4)=48` ⇒ **48×48 = 2304**，与模型输出 `[1,2304,16]` 对齐。
     */
    val FULL_RANGE = Spec(
        inputWidth = 192,
        inputHeight = 192,
        numLayers = 1,
        strides = listOf(4),
        interpolatedScaleAspectRatio = 0f,
    )

    /**
     * `face_detection_short_range.tflite`：128×128、4 层、strides 8/16/16/16、
     * `interpolated_scale_aspect_ratio: 1.0`（pbtxt 显式覆盖）。
     *
     * 生成数为 `16×16×2 + 8×8×(2+2+2) = 512 + 384 = 896`，与模型输出 `[1,896,16]` 对齐。
     */
    val SHORT_RANGE = Spec(
        inputWidth = 128,
        inputHeight = 128,
        numLayers = 4,
        strides = listOf(8, 16, 16, 16),
        interpolatedScaleAspectRatio = 1f,
    )

    /** MediaPipe `CalculateScale`：层数 >1 时在 min→max 间线性插值；层数 =1 时取中点。 */
    private fun calculateScale(minScale: Float, maxScale: Float, strideIndex: Int, numStrides: Int): Float =
        if (numStrides == 1) {
            (minScale + maxScale) * 0.5f
        } else {
            minScale + (maxScale - minScale) * strideIndex / (numStrides - 1)
        }

    /**
     * 生成 anchor 序列（顺序**必须**与模型输出逐行对应）。
     *
     * 关键细节（照抄 C++，错一处整个解码就整体错位）：
     * 1. **相同 stride 的连续层会被合并**成同一张特征图的一格多锚（内层 while）；
     * 2. `fixed_anchor_size = true` 时 `w = h = 1`，尺度信息改由解码时的 `*_scale` 承担；
     * 3. 中心点用 `(x + offset) / featureMapDim`（offset 默认 0.5，即格中心）；
     * 4. 特征图边长 = `ceil(input / stride)`。
     */
    fun generate(spec: Spec): List<FaceAnchor> {
        require(spec.numLayers > 0) { "numLayers must be > 0, got ${spec.numLayers}" }
        require(spec.strides.size == spec.numLayers) {
            "strides.size=${spec.strides.size} != numLayers=${spec.numLayers}"
        }
        require(spec.strides.all { it > 0 }) { "strides must be positive: ${spec.strides}" }

        val anchors = ArrayList<FaceAnchor>()
        var layerId = 0
        while (layerId < spec.numLayers) {
            val anchorHeight = ArrayList<Float>()
            val anchorWidth = ArrayList<Float>()
            val aspectRatios = ArrayList<Float>()
            val scales = ArrayList<Float>()

            // 合并连续同 stride 的层（C++ 注释：For same strides, we merge the anchors in the same order.）
            var lastSameStrideLayer = layerId
            while (lastSameStrideLayer < spec.strides.size &&
                spec.strides[lastSameStrideLayer] == spec.strides[layerId]
            ) {
                val scale = calculateScale(spec.minScale, spec.maxScale, lastSameStrideLayer, spec.strides.size)
                if (lastSameStrideLayer == 0 && spec.reduceBoxesInLowestLayer) {
                    // 底层预置 anchor（本项目两个模型均为 false，保留以对齐上游行为）
                    aspectRatios.add(1f); aspectRatios.add(2f); aspectRatios.add(0.5f)
                    scales.add(0.1f); scales.add(scale); scales.add(scale)
                } else {
                    for (ar in spec.aspectRatios) {
                        aspectRatios.add(ar)
                        scales.add(scale)
                    }
                    if (spec.interpolatedScaleAspectRatio > 0f) {
                        val scaleNext = if (lastSameStrideLayer == spec.strides.size - 1) {
                            1f
                        } else {
                            calculateScale(spec.minScale, spec.maxScale, lastSameStrideLayer + 1, spec.strides.size)
                        }
                        scales.add(sqrt(scale * scaleNext))
                        aspectRatios.add(spec.interpolatedScaleAspectRatio)
                    }
                }
                lastSameStrideLayer++
            }

            for (i in aspectRatios.indices) {
                val ratioSqrt = sqrt(aspectRatios[i])
                anchorHeight.add(scales[i] / ratioSqrt)
                anchorWidth.add(scales[i] * ratioSqrt)
            }

            val stride = spec.strides[layerId]
            val fmHeight = ceil(spec.inputHeight.toFloat() / stride).toInt()
            val fmWidth = ceil(spec.inputWidth.toFloat() / stride).toInt()

            for (y in 0 until fmHeight) {
                for (x in 0 until fmWidth) {
                    for (anchorId in anchorHeight.indices) {
                        val xCenter = (x + spec.anchorOffsetX) * 1.0f / fmWidth
                        val yCenter = (y + spec.anchorOffsetY) * 1.0f / fmHeight
                        if (spec.fixedAnchorSize) {
                            anchors.add(FaceAnchor(xCenter, yCenter, 1f, 1f))
                        } else {
                            anchors.add(FaceAnchor(xCenter, yCenter, anchorWidth[anchorId], anchorHeight[anchorId]))
                        }
                    }
                }
            }
            layerId = lastSameStrideLayer
        }
        return anchors
    }
}
