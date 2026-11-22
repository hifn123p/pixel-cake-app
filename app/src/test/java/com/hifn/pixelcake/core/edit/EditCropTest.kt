package com.hifn.pixelcake.core.edit

import com.hifn.pixelcake.core.decode.LinearImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.8.0 自由裁剪的纯几何回归：
 * ① pristine ⇄ 旋转空间矩形映射（q0..3 往返）；
 * ② 像素窗口与 16-bit 线性子矩形拷贝（逐值）；
 * ③ 拖拽数学（移动 / 角拖锚定 / 比例约束）；
 * ④ 复合矩形（二次裁剪）与命中测试。
 */
class EditCropTest {

    private val full = CropRect(0f, 0f, 1f, 1f)

    // —— 矩形映射 ——

    @Test
    fun `rectToRotated identity at q0`() {
        val r = CropRect(0.1f, 0.2f, 0.8f, 0.9f)
        assertEquals(r, EditCrop.rectToRotated(r, 0, 100, 200))
        assertEquals(r, EditCrop.rectToUnrotated(r, 0, 100, 200))
    }

    @Test
    fun `rectToRotated and back round trips for all quarters`() {
        val r = CropRect(0.1f, 0.15f, 0.85f, 0.7f)
        for (q in 1..3) {
            val rot = EditCrop.rectToRotated(r, q, 100, 200)
            val back = EditCrop.rectToUnrotated(rot, q, 100, 200)
            assertEquals("q=$q l", r.l, back.l, 1e-4f)
            assertEquals("q=$q t", r.t, back.t, 1e-4f)
            assertEquals("q=$q r", r.r, back.r, 1e-4f)
            assertEquals("q=$q b", r.b, back.b, 1e-4f)
        }
    }

    @Test
    fun `quarter rotation maps full frame to full frame`() {
        for (q in 0..3) {
            assertEquals(full, EditCrop.rectToRotated(full, q, 300, 500))
        }
    }

    // —— 像素窗口 ——

    @Test
    fun `renderWindow rounding is stable`() {
        // 0.1..0.9 × 200 → [20, 180)；0.25..0.75 × 100 → [25, 75)
        val win = EditCrop.renderWindow(CropRect(0.1f, 0.25f, 0.9f, 0.75f), 200, 100)
        assertEquals(20, win[0]); assertEquals(25, win[1]); assertEquals(160, win[2]); assertEquals(50, win[3])
    }

    @Test
    fun `croppedSize matches window`() {
        val (cw, ch) = EditCrop.croppedSize(CropRect(0.25f, 0.25f, 0.75f, 0.75f), 400, 200)
        assertEquals(200, cw)
        assertEquals(100, ch)
    }

    // —— 16-bit 线性裁剪（逐值）——

    @Test
    fun `cropLinear keeps subrect values row-major`() {
        // 3×2 图：像素 p 的三个分量 = p*10 + 0..2（data[p*3 + c] = p*10 + c）
        val w = 3; val h = 2
        val data = ShortArray(w * h * 3) { i ->
            val p = i / 3; val c = i % 3
            (p * 10 + c).toShort()
        }
        val lin = LinearImage(w, h, data)
        // 裁右下 2×1（列 1..2，行 1）→ 源像素 p = 1*3+1 = 4
        val cropped = EditCrop.cropLinear(lin, CropRect(1f / 3f, 0.5f, 1f, 1f))
        assertEquals(2, cropped.width)
        assertEquals(1, cropped.height)
        assertEquals((40).toShort(), cropped.data[0])
        assertEquals((41).toShort(), cropped.data[1])
        assertEquals((42).toShort(), cropped.data[2])
    }

    @Test
    fun `cropLinear full frame returns same instance`() {
        val lin = LinearImage(4, 4, ShortArray(4 * 4 * 3))
        assertTrue(lin === EditCrop.cropLinear(lin, full))
    }

    // —— 拖拽数学 ——

    @Test
    fun `move drag shifts within bounds`() {
        val r = CropRect(0.4f, 0.4f, 0.8f, 0.8f)
        val moved = EditCrop.dragRect(EditCrop.CropDrag.MOVE, r, 0.3f, 0f, null, 1f)
        // 右缘 0.8+0.3=1.1 → 钳到 1：整框贴右
        assertEquals(0.6f, moved.l, 1e-4f)
        assertEquals(1f, moved.r, 1e-4f)
    }

    @Test
    fun `corner drag anchors opposite corner`() {
        val r = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        // 拖 BR 角向内 0.1：TL 锚定，右下收进
        val dragged = EditCrop.dragRect(EditCrop.CropDrag.BR, r, -0.1f, -0.1f, null, 1f)
        assertEquals(0.2f, dragged.l, 1e-4f)
        assertEquals(0.2f, dragged.t, 1e-4f)
        assertEquals(0.7f, dragged.r, 1e-4f)
        assertEquals(0.7f, dragged.b, 1e-4f)
    }

    @Test
    fun `corner drag with aspect keeps pixel ratio`() {
        // 帧方形（frameAspect=1），比例 2:1：wN/hN = 2
        val r = CropRect(0.1f, 0.1f, 0.9f, 0.9f)
        val dragged = EditCrop.dragRect(EditCrop.CropDrag.BR, r, -0.4f, 0f, 2f, 1f)
        val wN = dragged.r - dragged.l
        val hN = dragged.b - dragged.t
        assertEquals(wN / hN, 2f, 0.01f)
        // 锚点 TL 不动
        assertEquals(0.1f, dragged.l, 1e-4f)
        assertEquals(0.1f, dragged.t, 1e-4f)
    }

    @Test
    fun `aspect fit centers largest rect of ratio`() {
        // 方形帧取 16:9：宽满幅、高 9/16（断言的是框高本身，不是上下边距）
        val fit = EditCrop.aspectFit(full, 16f / 9f, 1000f, 1000f)
        assertEquals(0f, fit.l, 1e-4f)
        assertEquals(1f, fit.r, 1e-4f)
        assertEquals(9f / 16f, fit.b - fit.t, 1e-3f)
        assertEquals(0.5f, (fit.t + fit.b) / 2f, 1e-3f)
    }

    // —— 复合与命中 ——

    @Test
    fun `composeRects maps inner into outer`() {
        val outer = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        // inner = outer 的一半（右下象限）
        val inner = CropRect(0.5f, 0.5f, 1f, 1f)
        val composed = EditCrop.composeRects(inner, outer)
        assertEquals(0.5f, composed.l, 1e-5f)
        assertEquals(0.8f, composed.r, 1e-5f)
    }

    @Test
    fun `hitKind corners and outside`() {
        val r = CropRect(0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(EditCrop.CropDrag.TL, EditCrop.hitKind(0.25f, 0.25f, r, 0.05f))
        assertEquals(EditCrop.CropDrag.BR, EditCrop.hitKind(0.75f, 0.75f, r, 0.05f))
        assertEquals(EditCrop.CropDrag.MOVE, EditCrop.hitKind(0.5f, 0.5f, r, 0.05f))
        assertNull(EditCrop.hitKind(0.1f, 0.9f, r, 0.05f))
    }

    @Test
    fun `isFullFrame guards default state`() {
        assertTrue(full.isFullFrame)
        assertFalse(CropRect(0.01f, 0.01f, 0.99f, 0.99f).isFullFrame)
    }
}
