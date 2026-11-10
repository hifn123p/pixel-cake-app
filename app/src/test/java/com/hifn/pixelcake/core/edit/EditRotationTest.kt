package com.hifn.pixelcake.core.edit

import com.hifn.pixelcake.core.decode.LinearImage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Q5 旋转几何：归一化点映射 / 蒙版装饰器 / 液化锚点映射。
 * 用已知探针值逐方向验证（与 EditRotation KDoc 的公式表一一对应）。
 */
class EditRotationTest {

    /** 未旋转 100×50 的归一化口径：L = 100，u ∈ (0,1]，v ∈ (0,0.5]。 */
    private val w = 100
    private val h = 50

    @Test
    fun mapPoint_q1_swapsIntoRotatedFrame() {
        // 中心点：u=0.5, v=0.25 → (h/L − v, u) = (0.5−0.25, 0.5) = (0.25, 0.5)
        val (u, v) = EditRotation.mapNormPoint(0.5f, 0.25f, 1, w, h)
        assertEquals(0.25f, u, 1e-4f)
        assertEquals(0.5f, v, 1e-4f)
    }

    @Test
    fun mapPoint_q2_centralSymmetry() {
        val (u, v) = EditRotation.mapNormPoint(0.2f, 0.1f, 2, w, h)
        assertEquals(w.toFloat() / w - 0.2f, u, 1e-4f)
        assertEquals(h.toFloat() / w - 0.1f, v, 1e-4f)
    }

    @Test
    fun unmap_invertsMap_forAllQuadrants() {
        for (q in 0..3) {
            for (pair in listOf(0.1f to 0.05f, 0.7f to 0.4f, 0.95f to 0.45f)) {
                val (u, v) = pair
                val mapped = EditRotation.mapNormPoint(u, v, q, w, h)
                val back = EditRotation.unmapNormPoint(mapped.first, mapped.second, q, w, h)
                assertEquals("q=$q u 往返", u, back.first, 1e-4f)
                assertEquals("q=$q v 往返", v, back.second, 1e-4f)
            }
        }
    }

    @Test
    fun mapMaskPixel_routesToRotatedGridPosition() {
        // 未旋转 100×50，渲染空间 (0, 0)（左上）在 q1（顺时针 90°）下对应
        // 旋转蒙版空间的 (h-1-0, 0) = (49, 0)。
        val (mx, my) = EditRotation.mapMaskPixel(0, 0, 1, w, h)
        assertEquals(49, mx)
        assertEquals(0, my)
    }

    @Test
    fun rotatedMask_mapsSampleCoords() {
        // 内层蒙版：只有 (49, 0) = 1.0，其余 0 —— q1 下渲染空间 (0, 0) 应采到它
        // （构造 100×50 的栅格蒙版：inner 坐标 (49, 0) 对应 mask 里的值）。
        val inner = RasterMaskForTest()
        val decorated = RotatedMask(inner, 1, w, h)
        assertEquals(1f, decorated.sample(0, 0), 1e-4f)
        assertEquals(0f, decorated.sample(1, 0), 1e-4f)
    }

    /** 固定值蒙版（测试桩）：sample(px, py) 记录调用并返回查表值。 */
    private class RasterMaskForTest : RetouchMask {
        val calls = mutableListOf<Pair<Int, Int>>()
        override fun sample(px: Int, py: Int): Float {
            calls.add(px to py)
            return if (px == 49 && py == 0) 1f else 0f
        }

        override fun resampleTo(nw: Int, nh: Int): RetouchMask = this
    }

    @Test
    fun mapAnchorToRender_routesIntoUnrotatedFrame() {
        // 锚点在旋转后空间 (49, 0)（q1）→ 未旋转渲染空间 (0, 49-1-0) = (0, 49)
        val a = RetouchLayer.FaceAnchor(faceX = 49f, faceY = 0f, eyeX = 49f, eyeY = 0f)
        val mapped = EditRotation.mapAnchorToRender(a, 1, w, h)
        assertEquals(0f, mapped.faceX, 1e-4f)
        assertEquals(49f, mapped.faceY, 1e-4f)
    }

    @Test
    fun linearImage_rotateQuarter_journey() {
        // 与 EditRotation 同源口径的 16-bit 重排：16→9→4→1 编码探针走四分之一圈
        val w = 4
        val h = 2
        val data = ShortArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 3
                data[i] = (x * 10).toShort()
                data[i + 1] = (y * 10).toShort()
                data[i + 2] = 3
            }
        }
        val img = LinearImage(w, h, data)
        val r1 = img.rotateQuarter(1)
        assertEquals(2, r1.width)
        assertEquals(4, r1.height)
        // (x=3, y=0) → 90CW → (1, 3)
        val i = (3 * r1.width + 1) * 3
        assertEquals(30, r1.data[i].toInt() and 0xffff)
    }

    @Test
    fun linearImage_rotate_inEditorRotation_matchesDirectCall() {
        // 线性旋转与 EditRotation 共用同一套方向约定（q=1 都映射 (H-1-y, x)）
        val img = LinearImage(2, 2, shortArrayOf(
            1, 1, 1,  2, 2, 2,
            3, 3, 3,  4, 4, 4
        ))
        val r1 = img.rotateQuarter(1)
        // (0, 0) → (H-1-0, 0) = (1, 0)：值 (1,1,1) 应出现在旋转后 (1, 0)
        val i = (0 * r1.width + 1) * 3
        assertEquals(1, r1.data[i].toInt() and 0xffff)
    }
}
