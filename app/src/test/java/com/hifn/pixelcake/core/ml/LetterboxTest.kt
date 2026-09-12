package com.hifn.pixelcake.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letterbox 几何换算单测（P1p-2）。核心诉求：**正反换算必须互为逆运算**，
 * 否则检测框投回源图时会整体偏移（且「预览所见 ≠ 导出所得」）。
 */
class LetterboxTest {

    @Test
    fun landscapeSourcePadsVertically() {
        val t = LetterboxTransform(srcW = 400, srcH = 300, dstW = 192, dstH = 192)
        assertEquals(0.48f, t.scale, 1e-6f)
        assertEquals(192, t.scaledW)
        assertEquals(144, t.scaledH)
        assertEquals(0, t.offsetX)
        assertEquals(24, t.offsetY)
    }

    @Test
    fun portraitSourcePadsHorizontally() {
        val t = LetterboxTransform(srcW = 300, srcH = 400, dstW = 192, dstH = 192)
        assertEquals(0.48f, t.scale, 1e-6f)
        assertEquals(144, t.scaledW)
        assertEquals(192, t.scaledH)
        assertEquals(24, t.offsetX)
        assertEquals(0, t.offsetY)
    }

    @Test
    fun squareSourceFillsTensorWithoutPadding() {
        val t = LetterboxTransform(srcW = 500, srcH = 500, dstW = 192, dstH = 192)
        assertEquals(192, t.scaledW)
        assertEquals(192, t.scaledH)
        assertEquals(0, t.offsetX)
        assertEquals(0, t.offsetY)
    }

    @Test
    fun sourceCornersMapToExpectedTensorCoords() {
        val t = LetterboxTransform(srcW = 400, srcH = 300, dstW = 192, dstH = 192)
        val (nx0, ny0) = t.srcToTensorNorm(0f, 0f)
        assertEquals(0f, nx0, 1e-6f)
        assertEquals(0.125f, ny0, 1e-6f) // 24/192
        val (nx1, ny1) = t.srcToTensorNorm(400f, 300f)
        assertEquals(1f, nx1, 1e-6f)
        assertEquals(0.875f, ny1, 1e-6f) // 168/192
    }

    @Test
    fun normToSourceInvertsSourceToNorm() {
        val t = LetterboxTransform(srcW = 400, srcH = 300, dstW = 192, dstH = 192)
        for ((sx, sy) in listOf(0f to 0f, 50f to 90f, 200f to 150f, 400f to 300f)) {
            val (nx, ny) = t.srcToTensorNorm(sx, sy)
            val (rx, ry) = t.tensorNormToSrc(nx, ny)
            assertEquals(sx, rx, 1e-3f)
            assertEquals(sy, ry, 1e-3f)
        }
    }

    @Test
    fun toSourceProjectsBoxAndKeypoints() {
        val t = LetterboxTransform(srcW = 400, srcH = 300, dstW = 192, dstH = 192)
        val norm = NormFace(
            xmin = 0f, ymin = 0.125f, xmax = 0.5f, ymax = 0.625f,
            score = 0.9f,
            keypoints = listOf(0.25f to 0.375f),
        )
        val d = t.toSource(norm)
        assertEquals(0f, d.xmin, 1e-3f)
        assertEquals(0f, d.ymin, 1e-3f)
        assertEquals(200f, d.xmax, 1e-3f) // 96/0.48
        assertEquals(200f, d.ymax, 1e-3f) // 48*... = (0.625*192-24)/0.48
        assertEquals(100f, d.keypoints[0].first, 1e-3f)
        assertEquals(100f, d.keypoints[0].second, 1e-3f)
        assertEquals(0.9f, d.score, 1e-6f)
    }

    @Test
    fun renderNearestFillsBordersBlackAndPlacesImage() {
        val t = LetterboxTransform(srcW = 400, srcH = 300, dstW = 192, dstH = 192)
        val src = IntArray(400 * 300)
        src[0] = 0xFF123456.toInt()
        val dst = t.renderNearest(src)
        assertEquals(192 * 192, dst.size)
        // 顶部 padding 行（ty < offsetY=24）保持在源图外 ⇒ 边框黑
        assertEquals(LetterboxTransform.BORDER_ARGB, dst[0])
        // ty = 24 恰好落在缩放图第一行、tx = 0 对应源 (0,0)
        assertEquals(0xFF123456.toInt(), dst[24 * 192 + 0])
        // 底部 padding 行
        assertTrue(dst[191 * 192 + 100] == LetterboxTransform.BORDER_ARGB)
    }
}
