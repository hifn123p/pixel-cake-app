package com.hifn.pixelcake.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码 + 加权 NMS 单测（P1p-2）。纯函数，跑在 JVM 上。
 *
 * 期望值全部**按 MediaPipe C++ 的公式手推**（不照抄被测实现的中间量），
 * 否则「测试通过」只证明代码自洽、不证明移植正确。
 */
class FaceDetectionPostProcessTest {

    private fun opts(numBoxes: Int, minScore: Float = 0f) = FaceDetectionPostProcess.DecodeOptions(
        numBoxes = numBoxes,
        xScale = 192f, yScale = 192f, wScale = 192f, hScale = 192f,
        minScore = minScore,
    )

    private fun faceOf(
        xmin: Float, ymin: Float, xmax: Float, ymax: Float, score: Float, kpBase: Float,
    ): NormFace = NormFace(
        xmin, ymin, xmax, ymax, score,
        List(FaceDetectionPostProcess.NUM_KEYPOINTS) { k ->
            (kpBase + k * 0.01f) to (kpBase + k * 0.01f)
        },
    )

    @Test
    fun decodeReadsReverseOutputOrderAndScalesByAnchor() {
        val anchors = listOf(FaceAnchor(0.5f, 0.5f, 1f, 1f))
        val boxes = FloatArray(FaceDetectionPostProcess.NUM_COORDS)
        boxes[0] = 0f      // x_center 原始偏移
        boxes[1] = 0f      // y_center
        boxes[2] = 10f     // w
        boxes[3] = 20f     // h
        boxes[6] = 19.2f   // kp1.x → 19.2/192 + 0.5 = 0.6
        // 其余关键点原始值 = 0 ⇒ 落在 anchor 中心 0.5

        val dets = FaceDetectionPostProcess.decode(boxes, floatArrayOf(0f), anchors, opts(1))
        assertEquals(1, dets.size)
        val d = dets[0]
        // xc=0.5, w=10/192 ⇒ xmin=0.5-0.0260416667
        assertEquals(0.5f - 10f / 192f / 2f, d.xmin, 1e-6f)
        assertEquals(0.5f + 10f / 192f / 2f, d.xmax, 1e-6f)
        assertEquals(0.5f - 20f / 192f / 2f, d.ymin, 1e-6f)
        assertEquals(0.5f + 20f / 192f / 2f, d.ymax, 1e-6f)
        assertEquals(0.5f, d.score, 1e-6f) // sigmoid(0) = 0.5
        assertEquals(0.5f, d.keypoints[0].first, 1e-6f)
        assertEquals(0.5f, d.keypoints[0].second, 1e-6f)
        assertEquals(0.6f, d.keypoints[1].first, 1e-6f)
        assertEquals(0.5f, d.keypoints[1].second, 1e-6f)
        assertEquals(FaceDetectionPostProcess.NUM_KEYPOINTS, d.keypoints.size)
    }

    @Test
    fun anchorScaleMovesBoxWithAnchor() {
        // 第二大 anchor 在 (0.25, 0.75)，同样给 0 偏移 ⇒ 框心应落在 anchor 中心
        val anchors = listOf(FaceAnchor(0.25f, 0.75f, 1f, 1f))
        val boxes = FloatArray(FaceDetectionPostProcess.NUM_COORDS)
        boxes[2] = 0f
        boxes[3] = 0f
        val d = FaceDetectionPostProcess.decode(boxes, floatArrayOf(0f), anchors, opts(1)).single()
        assertEquals(0.25f, (d.xmin + d.xmax) / 2f, 1e-6f)
        assertEquals(0.75f, (d.ymin + d.ymax) / 2f, 1e-6f)
    }

    @Test
    fun decodeDropsBoxesBelowScoreThreshold() {
        val anchors = listOf(FaceAnchor(0.5f, 0.5f, 1f, 1f))
        val boxes = FloatArray(FaceDetectionPostProcess.NUM_COORDS)
        // sigmoid(0) = 0.5 < 0.6 ⇒ 丢弃
        val dets = FaceDetectionPostProcess.decode(boxes, floatArrayOf(0f), anchors, opts(1, minScore = 0.6f))
        assertTrue(dets.isEmpty())
    }

    @Test
    fun decodeDropsNegativeSizeBoxes() {
        val anchors = listOf(FaceAnchor(0.5f, 0.5f, 1f, 1f))
        val boxes = FloatArray(FaceDetectionPostProcess.NUM_COORDS)
        boxes[2] = -10f // 负宽 ⇒ 丢弃
        val dets = FaceDetectionPostProcess.decode(boxes, floatArrayOf(0f), anchors, opts(1))
        assertTrue(dets.isEmpty())
    }

    @Test
    fun weightedNmsMergesOverlappingByScoreWeightAndKeepsWinnerScore() {
        val a = faceOf(0f, 0f, 0.5f, 0.5f, 0.9f, kpBase = 0.2f)
        val b = faceOf(0.1f, 0.1f, 0.6f, 0.6f, 0.8f, kpBase = 0.3f)
        // IoU = 0.16 / (0.25 + 0.25 - 0.16) = 0.4706 > 0.3 ⇒ 合并
        val out = FaceDetectionPostProcess.weightedNms(listOf(a, b), 0.3f)
        assertEquals(1, out.size)
        val m = out[0]
        val total = 1.7f
        assertEquals(0.1f * 0.8f / total, m.xmin, 1e-5f)
        assertEquals(0.1f * 0.8f / total, m.ymin, 1e-5f)
        assertEquals((0.5f * 0.9f + 0.6f * 0.8f) / total, m.xmax, 1e-5f)
        assertEquals((0.5f * 0.9f + 0.6f * 0.8f) / total, m.ymax, 1e-5f)
        // 关键点同样按分数加权平均
        assertEquals((0.2f * 0.9f + 0.3f * 0.8f) / total, m.keypoints[0].first, 1e-5f)
        // 分数保持胜者原值（MediaPipe 的 WeightedNonMaxSuppression 不累加分数）
        assertEquals(0.9f, m.score, 1e-6f)
    }

    @Test
    fun weightedNmsKeepsDisjointBoxesSeparate() {
        val a = faceOf(0f, 0f, 0.2f, 0.2f, 0.9f, kpBase = 0.1f)
        val b = faceOf(0.7f, 0.7f, 0.9f, 0.9f, 0.8f, kpBase = 0.8f)
        val out = FaceDetectionPostProcess.weightedNms(listOf(a, b), 0.3f)
        assertEquals(2, out.size)
        // 输出按分数降序
        assertEquals(0.9f, out[0].score, 1e-6f)
        assertEquals(0.8f, out[1].score, 1e-6f)
    }

    @Test
    fun largestPicksBiggestFace() {
        val small = faceOf(0f, 0f, 0.2f, 0.2f, 0.9f, kpBase = 0.1f)
        val big = faceOf(0.1f, 0.1f, 0.9f, 0.9f, 0.7f, kpBase = 0.5f)
        val picked = FaceDetectionPostProcess.largest(listOf(small, big))
        assertEquals(big, picked)
        assertNull(FaceDetectionPostProcess.largest(emptyList()))
    }

    @Test
    fun decodeAndNmsEndToEnd() {
        // 两个 anchor 都预测到同一张「脸」（偏移被设计成指向同一处）⇒ 合并为一
        val anchors = listOf(FaceAnchor(0.5f, 0.5f, 1f, 1f), FaceAnchor(0.5f, 0.5f, 1f, 1f))
        val boxes = FloatArray(2 * FaceDetectionPostProcess.NUM_COORDS)
        for (i in 0 until 2) {
            boxes[i * 16 + 2] = 40f // w = 40/192
            boxes[i * 16 + 3] = 40f // h = 40/192
        }
        val scores = floatArrayOf(2f, 1f) // sigmoid(2)≈0.881, sigmoid(1)≈0.731
        val out = FaceDetectionPostProcess.decodeAndNms(boxes, scores, anchors, opts(2))
        assertEquals(1, out.size)
        assertEquals(sigmoid(2f), out[0].score, 1e-6f)
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + kotlin.math.exp(-x))
}
