package com.hifn.pixelcake.core.edit.retouch

import com.hifn.pixelcake.core.edit.retouch.RetouchLayer.FaceAnchor
import com.hifn.pixelcake.core.ml.FaceDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `FaceAnchor.fromDetection` 的**归一化换算**（P1p-2c）。
 *
 * 这是「预览 / 导出分辨率不同、锚点必须不跑偏」的唯一护栏：
 * - 人脸检测跑在**源图**上（ARW 时是内嵌预览，如 3504×2336）；
 * - retouch 跑在**渲染分辨率**上（RAW 预览 = 16-bit 线性代理，如 2048×1366；导出 = 全分辨率）。
 *
 * 两者尺寸不同，直接把检测像素当渲染像素用，锚点就会整体偏移 —— 而且偏移量随分辨率变化，
 * 于是「预览看着还行、导出就跑偏」。所以断言的是**归一化位置**，不是像素值。
 */
class FaceAnchorTest {

    private fun face(cx: Float, cy: Float, keypoints: List<Pair<Float, Float>> = emptyList()) =
        FaceDetection(
            xmin = cx - 40f, ymin = cy - 50f,
            xmax = cx + 40f, ymax = cy + 50f,
            score = 0.9f, keypoints = keypoints
        )

    /** 源图正中心（1752, 1168）/ 3504×2336 ⇒ 任意渲染尺寸下都该落在正中心。 */
    @Test
    fun mapsNormalizedPositionIntoRenderSpace() {
        val f = face(1752f, 1168f, listOf(1700f to 1100f, 1800f to 1100f))
        val a = FaceAnchor.fromDetection(f, 3504, 2336, 2048, 1366)!!
        assertEquals("脸心 x 应落在渲染宽的正中", 2047f / 2f, a.faceX, 0.01f)
        assertEquals("脸心 y 应落在渲染高的正中", 1365f / 2f, a.faceY, 0.01f)
        // 眼心 = 两眼均值 = (1750, 1100) ⇒ 归一化 1750/3504
        assertEquals(2047f * (1750f / 3504f), a.eyeX, 0.01f)
        assertEquals(1365f * (1100f / 2336f), a.eyeY, 0.01f)
    }

    /**
     * 核心不变式：同一张脸在**不同渲染分辨率**下，锚点的归一化位置必须一致。
     * 这正是「预览（2048）与导出（7008）所见即所得」的前提。
     */
    @Test
    fun sameFaceLandsAtSameNormalizedSpotAcrossResolutions() {
        val f = face(1000f, 800f)
        val proxy = FaceAnchor.fromDetection(f, 3504, 2336, 2048, 1366)!!
        val full = FaceAnchor.fromDetection(f, 3504, 2336, 7008, 4672)!!
        assertEquals("归一化脸心 x 必须一致", proxy.faceX / 2047f, full.faceX / 7007f, 1e-4f)
        assertEquals("归一化脸心 y 必须一致", proxy.faceY / 1365f, full.faceY / 4671f, 1e-4f)
    }

    /** 关键点缺失（<2 个）时眼心退回脸框中心 —— 与 P1 的 `eyeEnlarge` 锚点口径一致，不会突然跑偏。 */
    @Test
    fun eyeCenterFallsBackToFaceCenterWhenKeypointsMissing() {
        val f = face(1000f, 800f, emptyList())
        val a = FaceAnchor.fromDetection(f, 3504, 2336, 2048, 1366)!!
        assertEquals(a.faceX, a.eyeX, 0f)
        assertEquals(a.faceY, a.eyeY, 0f)
    }

    /** 越界坐标（检测不该产生，但兜底）被 clamp 到渲染画面内，不会把锚点甩到采样范围外。 */
    @Test
    fun clampsOutOfRangeCoordinates() {
        val right = face(4000f, 3000f)
        val a = FaceAnchor.fromDetection(right, 3504, 2336, 100, 100)!!
        assertEquals("右越界应 clamp 到 w-1", 99f, a.faceX, 0f)
        assertEquals("下越界应 clamp 到 h-1", 99f, a.faceY, 0f)

        val left = face(-500f, -500f)
        val b = FaceAnchor.fromDetection(left, 3504, 2336, 100, 100)!!
        assertEquals("左越界应 clamp 到 0", 0f, b.faceX, 0f)
        assertEquals("上越界应 clamp 到 0", 0f, b.faceY, 0f)
    }

    /** 任一尺寸非法 ⇒ `null`（调用方据此退回「蒙版质心猜」），绝不抛异常。 */
    @Test
    fun returnsNullForDegenerateSizes() {
        val f = face(1000f, 800f)
        assertNull(FaceAnchor.fromDetection(f, 0, 2336, 100, 100))
        assertNull(FaceAnchor.fromDetection(f, 3504, 0, 100, 100))
        assertNull(FaceAnchor.fromDetection(f, 3504, 2336, 0, 100))
        assertNull(FaceAnchor.fromDetection(f, 3504, 2336, 100, 0))
    }
}
