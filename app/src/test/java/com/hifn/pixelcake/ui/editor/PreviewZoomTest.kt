package com.hifn.pixelcake.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 预览缩放（§4.0.10）的三个纯函数钉子。
 *
 * 这套换算「看着对、其实系统性偏了」的概率和 [fitContentRect] 一样高 —— 放大后涂在脸上的
 * 笔迹如果偏了半张脸，用户只会以为「蒙版不准」。必须用数字钉死。
 */
class PreviewZoomTest {

    // 预览框 1000×800，照片 2000×1000（2:1 横构图）⇒ fit = 全宽 1000×500，上下各留 150。
    private val box = IntSize(1000, 800)
    private val content = IntSize(2000, 1000)

    @Test
    fun clampPan_notZoomed_isAlwaysZero() {
        // 未放大（显示尺寸 ≤ 框）时平移恒为 0 —— 平移自然无效，不会露出图外的底。
        val p = clampPreviewPan(Offset(50f, -30f), 1000f, 800f, 1000f, 500f)
        assertEquals(0f, p.x, 1e-4f)
        assertEquals(0f, p.y, 1e-4f)
    }

    @Test
    fun clampPan_zoomed_clampsToOverflowOnly() {
        // 2x：显示 2000×1000，框 1000×800 ⇒ 每轴最多露出 (2000-1000)/2=500 / (1000-800)/2=100。
        val p = clampPreviewPan(Offset(900f, 500f), 1000f, 800f, 2000f, 1000f)
        assertEquals(500f, p.x, 1e-4f)
        assertEquals(100f, p.y, 1e-4f)
        // 负向同理（对称）
        val n = clampPreviewPan(Offset(-900f, -500f), 1000f, 800f, 2000f, 1000f)
        assertEquals(-500f, n.x, 1e-4f)
        assertEquals(-100f, n.y, 1e-4f)
    }

    @Test
    fun pointToNorm_unzoomed_matchesFitRect() {
        // 未放大时：显示矩形 == fit 矩形（左上 (0,150)，1000×500）。
        // 点照片左上角 ⇒ (0,0)；正中 ⇒ (0.5,0.5)。
        val tl = pointToContentNorm(Offset(0f, 150f), box, content, 1f, Offset.Zero)
        assertEquals(0f, tl!!.first, 1e-4f)
        assertEquals(0f, tl.second, 1e-4f)
        val center = pointToContentNorm(Offset(500f, 400f), box, content, 1f, Offset.Zero)
        assertEquals(0.5f, center!!.first, 1e-4f)
        assertEquals(0.5f, center.second, 1e-4f)
    }

    @Test
    fun pointToNorm_zoomedAndPanned_landsOnSameContent() {
        // 放大 2x、向右下平移 (100,50)：点「照片显示矩形的中心」必须仍是内容 (0.5, 0.5)。
        // 显示矩形 = 中心(500,400) + pan(100,50) - 半尺寸(1000,500) ⇒ 左上 (-400,-50)。
        val zoom = 2f
        val pan = Offset(100f, 50f)
        val dispCenter = Offset(500f + 100f, 400f + 50f)
        val hit = pointToContentNorm(dispCenter, box, content, zoom, pan)
        assertEquals(0.5f, hit!!.first, 1e-4f)
        assertEquals(0.5f, hit.second, 1e-4f)
    }

    @Test
    fun pointToNorm_outsideImage_returnsNull_unlessClamp() {
        // 未放大时点黑边（y=100 在照片上方）：祛瑕口径 = 不落点
        assertNull(pointToContentNorm(Offset(500f, 100f), box, content, 1f, Offset.Zero))
        // 画笔口径 = clamp 不断笔（贴到照片上边缘）
        val clamped = pointToContentNorm(
            Offset(500f, 100f), box, content, 1f, Offset.Zero, clampToContent = true
        )
        assertEquals(0f, clamped!!.second, 1e-4f)
    }

    @Test
    fun panAnchor_zoomIn_keepsTappedContentStationary() {
        // 双击照片中心 (500,400) 从 1x 放到 2.5x：新 pan 应让「中心点的内容」仍在 (500,400)。
        val newPan = panAnchorAtPoint(
            tap = Offset(500f, 400f), boxW = 1000f, boxH = 800f,
            fitW = 1000f, fitH = 500f, oldZoom = 1f, oldPan = Offset.Zero, newZoom = 2.5f
        )
        // 显示矩形 2500×1250 中心 = (500,400) + pan ⇒ pan 必须为 0（本来就点在中心）。
        assertEquals(0f, newPan.x, 1e-3f)
        assertEquals(0f, newPan.y, 1e-3f)

        // 双击左上角内容 (0,0)（fit 矩形左上 = (0,150)）：放大后该内容应仍在这根手指下。
        val newPan2 = panAnchorAtPoint(
            tap = Offset(0f, 150f), boxW = 1000f, boxH = 800f,
            fitW = 1000f, fitH = 500f, oldZoom = 1f, oldPan = Offset.Zero, newZoom = 2.5f
        )
        // 显示左上 = (0,150) - (0,0)*disp ⇒ left'=0 ⇒ pan.x = 0 - (500 - 1250) = 750
        assertEquals(750f, newPan2.x, 1e-3f)
        assertEquals(150f - (400f - 625f), newPan2.y, 1e-3f)
    }
}
