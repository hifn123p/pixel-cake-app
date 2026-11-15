package com.hifn.pixelcake.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * marching squares 等值线提取的纯 JVM 回归（拾取可视化「琥珀虚线轮廓」的几何层）。
 */
class MaskContourTest {

    private fun grid(w: Int, h: Int, fill: (Int, Int) -> Float): FloatGrid {
        val data = FloatArray(w * h) { i -> fill(i % w, i / w) }
        return FloatGrid(data, w, h)
    }

    @Test
    fun emptyGrid_yieldsNoContours() {
        val g = grid(8, 8) { _, _ -> 0f }
        assertTrue(MaskContour.contours(g).isEmpty())
    }

    @Test
    fun fullGrid_yieldsSingleBoundaryLoop() {
        // 全 1 网格：唯一的等值线 = 贴着网格外沿的一整圈
        val g = grid(8, 8) { _, _ -> 1f }
        val loops = MaskContour.contours(g)
        assertEquals(1, loops.size)
        // 闭环：首尾顶点相同
        assertEquals(loops[0].first(), loops[0].last())
        assertTrue("外沿环的顶点数应覆盖四条边（≥8）", loops[0].size >= 8)
        // 顶点都在归一化边界上（u 或 v ∈ {0,1}）
        assertTrue(loops[0].all { (u, v) -> u == 0f || u == 1f || v == 0f || v == 1f })
    }

    @Test
    fun squareBlock_yieldsOneLoopWithCorners() {
        // 中央 4×4 方块（0.25..0.74）：等值线应是一个近似矩形的环
        val g = grid(16, 16) { x, y ->
            if (x in 4..11 && y in 4..11) 1f else 0f
        }
        val loops = MaskContour.contours(g)
        assertEquals(1, loops.size)
        val loop = loops[0]
        assertEquals(loop.first(), loop.last())
        // 四个角都落在方块边界 ±1 格内（线性插值让交点在格边上）
        val xs = loop.map { it.first }
        val ys = loop.map { it.second }
        assertTrue(xs.min() > 0.1f && xs.max() < 0.9f)
        assertTrue(ys.min() > 0.1f && ys.max() < 0.9f)
    }

    @Test
    fun ring_yieldsTwoLoops_outerAndHole() {
        // 环形（外框 2 格厚 + 中央 4×4 孔）：外轮廓与孔洞各一条闭环
        val g = grid(16, 16) { x, y ->
            val border = x < 2 || x > 13 || y < 2 || y > 13
            val hole = x in 6..9 && y in 6..9
            if (border || !hole) 1f else 0f
        }
        val loops = MaskContour.contours(g)
        assertEquals(2, loops.size)
    }
}
