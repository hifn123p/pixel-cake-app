package com.hifn.pixelcake.core.ml

import kotlin.math.exp

/**
 * 人脸检测的**输出解码 + 加权 NMS**（纯函数，零 Android / 零 LiteRT 依赖，可 JVM 单测）。
 *
 * 这是移植 MediaPipe 的两个 C++ 计算器（P1p-2）：
 * - 解码：`mediapipe/calculators/tflite/tflite_tensors_to_detections_calculator.cc`（`DecodeBoxes` / `ConvertToDetections`）；
 * - 抑制：`mediapipe/calculators/util/non_max_suppression_calculator.cc`（`WeightedNonMaxSuppression`）。
 *
 * 参数全部来自 `mediapipe/modules/face_detection/face_detection.pbtxt`（与模型配套的权威配置，
 * **不在 `.tflite` 里**）：`num_classes=1 / num_coords=16 / box_coord_offset=0 / keypoint_coord_offset=4 /
 * num_keypoints=6 / num_values_per_keypoint=2 / sigmoid_score=true / score_clipping_thresh=100 /
 * reverse_output_order=true`；NMS `overlap_type=INTERSECTION_OVER_UNION / min_suppression_threshold=0.3 /
 * algorithm=WEIGHTED`。
 *
 * ⚠️ **坐标序**：`reverse_output_order=true` ⇒ 每个 anchor 的 16 个值前 4 个是
 * `[x_center, y_center, w, h]`（**不是** `[y_center, x_center, h, w]`），其后是 6 个关键点的 `(x, y)`。
 * 写反会让框整体转 90°。
 */
object FaceDetectionPostProcess {

    /** 每个 anchor 的输出维度（16 = 4 框 + 6 关键点 × 2）。 */
    const val NUM_COORDS = 16

    /** 关键点个数（左眼 / 右眼 / 鼻尖 / 嘴中心 / 左耳屏 / 右耳屏）。 */
    const val NUM_KEYPOINTS = 6

    /** 每个关键点的维度（2D）。 */
    const val NUM_VALUES_PER_KEYPOINT = 2

    /** 框坐标在 16 维里的起始下标。 */
    const val BOX_COORD_OFFSET = 0

    /** 关键点坐标在 16 维里的起始下标（= 4）。 */
    const val KEYPOINT_COORD_OFFSET = 4

    /** 分数 sigmoid 前的截断阈值（pbtxt `score_clipping_thresh: 100.0`）。 */
    const val SCORE_CLIPPING = 100f

    /**
     * 解码参数（pbtxt 里 `FaceDetectionOptions` 的覆盖项 + NMS 阈值）。
     *
     * - `*_scale` 对 full-range 均为 192（= 张量边长），short-range 均为 128；
     * - [minScore] full-range 0.6 / short-range 0.5；
     * - [iouThreshold] 0.3。
     */
    data class DecodeOptions(
        val numBoxes: Int,
        val xScale: Float,
        val yScale: Float,
        val wScale: Float,
        val hScale: Float,
        val minScore: Float,
        val iouThreshold: Float = 0.3f,
    ) {
        companion object {
            /** `face_detection_full_range(_sparse).tflite`（192×192，2304 anchors）。 */
            val FULL_RANGE = DecodeOptions(
                numBoxes = 2304,
                xScale = 192f, yScale = 192f, wScale = 192f, hScale = 192f,
                minScore = 0.6f,
            )

            /** `face_detection_short_range.tflite`（128×128，896 anchors）。 */
            val SHORT_RANGE = DecodeOptions(
                numBoxes = 896,
                xScale = 128f, yScale = 128f, wScale = 128f, hScale = 128f,
                minScore = 0.5f,
            )
        }
    }

    /**
     * 解码：模型 raw 输出 → 归一化检测框（**已过分数阈值**，已丢弃负宽高框）。
     *
     * @param rawBoxes  长度须 ≥ `numBoxes * NUM_COORDS`（regressors 张量展平）
     * @param rawScores 长度须 ≥ `numBoxes`（classificators 张量展平，**logit**）
     * @param anchors   长度须 = `numBoxes`
     */
    fun decode(
        rawBoxes: FloatArray,
        rawScores: FloatArray,
        anchors: List<FaceAnchor>,
        opt: DecodeOptions,
    ): List<NormFace> {
        require(rawBoxes.size >= opt.numBoxes * NUM_COORDS) {
            "rawBoxes.size=${rawBoxes.size} < ${opt.numBoxes * NUM_COORDS}"
        }
        require(rawScores.size >= opt.numBoxes) {
            "rawScores.size=${rawScores.size} < ${opt.numBoxes}"
        }
        require(anchors.size == opt.numBoxes) {
            "anchors.size=${anchors.size} != numBoxes=${opt.numBoxes}"
        }

        val out = ArrayList<NormFace>()
        for (i in 0 until opt.numBoxes) {
            val score = sigmoid(rawScores[i].coerceIn(-SCORE_CLIPPING, SCORE_CLIPPING))
            if (score < opt.minScore) continue

            val o = i * NUM_COORDS + BOX_COORD_OFFSET
            // reverse_output_order = true: [x_center, y_center, w, h]
            val a = anchors[i]
            val xCenter = rawBoxes[o] / opt.xScale * a.w + a.xCenter
            val yCenter = rawBoxes[o + 1] / opt.yScale * a.h + a.yCenter
            // apply_exponential_on_box_size 默认 false ⇒ 直接相除（非 exp）
            val w = rawBoxes[o + 2] / opt.wScale * a.w
            val h = rawBoxes[o + 3] / opt.hScale * a.h

            val xmin = xCenter - w * 0.5f
            val ymin = yCenter - h * 0.5f
            val xmax = xCenter + w * 0.5f
            val ymax = yCenter + h * 0.5f
            // C++：宽或高为负的框直接丢弃（下游可能假设非负）
            if (xmax - xmin < 0f || ymax - ymin < 0f) continue

            val keypoints = ArrayList<Pair<Float, Float>>(NUM_KEYPOINTS)
            for (k in 0 until NUM_KEYPOINTS) {
                val ko = i * NUM_COORDS + KEYPOINT_COORD_OFFSET + k * NUM_VALUES_PER_KEYPOINT
                val kx = rawBoxes[ko] / opt.xScale * a.w + a.xCenter
                val ky = rawBoxes[ko + 1] / opt.yScale * a.h + a.yCenter
                keypoints.add(kx to ky)
            }

            out.add(NormFace(xmin, ymin, xmax, ymax, score, keypoints))
        }
        return out
    }

    /**
     * **加权** NMS（MediaPipe `NonMaxSuppressionCalculator`，`algorithm: WEIGHTED`）。
     *
     * 与朴素 NMS 的差别：被抑制的框不是丢弃，而是**按分数加权平均**并入胜者（框与关键点都平均），
     * 因此关键点更稳。相异性用 `INTERSECTION_OVER_UNION`，阈值 `> iouThreshold` 才抑制。
     *
     * ⚠️ 照抄 C++ 的一处细节：合并后**分数保持胜者原值**（不累加），只有位置被加权平均。
     */
    fun weightedNms(detections: List<NormFace>, iouThreshold: Float = 0.3f): List<NormFace> {
        val output = ArrayList<NormFace>()
        var remained = detections.withIndex().sortedByDescending { it.value.score }

        while (remained.isNotEmpty()) {
            val sizeBefore = remained.size
            val first = remained[0].value

            val candidates = ArrayList<IndexedValue<NormFace>>()
            val next = ArrayList<IndexedValue<NormFace>>()
            for (iv in remained) {
                // 与自身相似度为 1.0 > 阈值 ⇒ 胜者必然落在 candidates 里
                val sim = iou(first, iv.value)
                if (sim > iouThreshold) candidates.add(iv) else next.add(iv)
            }

            var weighted = first
            if (candidates.isNotEmpty()) {
                var totalScore = 0f
                var wXmin = 0f; var wYmin = 0f; var wXmax = 0f; var wYmax = 0f
                val kpX = FloatArray(NUM_KEYPOINTS)
                val kpY = FloatArray(NUM_KEYPOINTS)
                for (c in candidates) {
                    val d = c.value
                    val sc = d.score
                    totalScore += sc
                    wXmin += d.xmin * sc
                    wYmin += d.ymin * sc
                    wXmax += d.xmax * sc
                    wYmax += d.ymax * sc
                    for (k in 0 until NUM_KEYPOINTS) {
                        val kp = d.keypoints.getOrNull(k) ?: continue
                        kpX[k] += kp.first * sc
                        kpY[k] += kp.second * sc
                    }
                }
                if (totalScore > 0f) {
                    val keypoints = ArrayList<Pair<Float, Float>>(NUM_KEYPOINTS)
                    for (k in 0 until NUM_KEYPOINTS) {
                        keypoints.add(kpX[k] / totalScore to kpY[k] / totalScore)
                    }
                    weighted = first.copy(
                        xmin = wXmin / totalScore,
                        ymin = wYmin / totalScore,
                        xmax = wXmax / totalScore,
                        ymax = wYmax / totalScore,
                        keypoints = keypoints,
                    )
                }
            }

            output.add(weighted)
            // 照抄 C++：一轮没消化任何框就退出（正常阈值 0.3 下不会发生）
            if (sizeBefore == next.size) break
            remained = next
        }
        return output
    }

    /** 解码 + 抑制一步到位。 */
    fun decodeAndNms(
        rawBoxes: FloatArray,
        rawScores: FloatArray,
        anchors: List<FaceAnchor>,
        opt: DecodeOptions,
    ): List<NormFace> = weightedNms(decode(rawBoxes, rawScores, anchors, opt), opt.iouThreshold)

    /** 按面积取最大的一张脸（合照取主体）；无脸返回 `null`。 */
    fun largest(detections: List<NormFace>): NormFace? =
        detections.maxByOrNull { it.area }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    /** `INTERSECTION_OVER_UNION`。 */
    private fun iou(a: NormFace, b: NormFace): Float {
        val ix = minOf(a.xmax, b.xmax) - maxOf(a.xmin, b.xmin)
        val iy = minOf(a.ymax, b.ymax) - maxOf(a.ymin, b.ymin)
        if (ix <= 0f || iy <= 0f) return 0f
        val inter = ix * iy
        val union = a.area + b.area - inter
        return if (union > 0f) inter / union else 0f
    }
}
