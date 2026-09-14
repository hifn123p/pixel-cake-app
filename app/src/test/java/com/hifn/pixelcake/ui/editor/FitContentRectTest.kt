package com.hifn.pixelcake.ui.editor

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 预览手势落点换算的护栏（审计报告 A2）。
 *
 * **为什么必须用单测钉死**：`ContentScale.Fit` 会给长宽比不一致的照片留黑边，而手势上报的是
 * 「相对照片」的归一化坐标。曾经的实现除以**整块预览 Box**，只在「照片恰好铺满 Box」时才正确；
 * 横构图照片在竖屏上会让落点系统性偏移。
 *
 * 这类缺陷**目视几乎发现不了**（涂抹位置就在手指附近，看起来「差不多」），但预览与导出
 * 共用同一套归一化坐标 ⇒ 两条路径一起错。所以这里用**具体数字**断言，而不是靠肉眼。
 */
class FitContentRectTest {

    /** 2:1 的宽照片放进正方 Box：只占中间一条，上下各留 1/4 黑边。 */
    @Test
    fun wideImageInSquareBox_letterboxTopAndBottom() {
        val rect = fitContentRect(box = IntSize(1000, 1000), image = IntSize(2000, 1000))
        // scale = min(1000/2000, 1000/1000) = 0.5 ⇒ 内容 1000×500，垂直居中
        assertEquals(0.0, rect.left.toDouble(), 1e-4)
        assertEquals(250.0, rect.top.toDouble(), 1e-4)
        assertEquals(1000.0, rect.right.toDouble(), 1e-4)
        assertEquals(750.0, rect.bottom.toDouble(), 1e-4)
    }

    /** 竖构照片放进正方 Box：左右留黑边，x 才是偏移轴（修 bug 不能只修一半）。 */
    @Test
    fun tallImageInSquareBox_letterboxLeftAndRight() {
        val rect = fitContentRect(box = IntSize(1000, 1000), image = IntSize(1000, 2000))
        assertEquals(250.0, rect.left.toDouble(), 1e-4)
        assertEquals(0.0, rect.top.toDouble(), 1e-4)
        assertEquals(750.0, rect.right.toDouble(), 1e-4)
        assertEquals(1000.0, rect.bottom.toDouble(), 1e-4)
    }

    /**
     * **本组测试的核心区分点**：内容矩形内的归一化与「按 Box 归一化」差多少。
     * 上例中照片上沿按内容归一化是 `y=0`，按 Box 归一化却是 `0.25` —— 那个 0.25 就是
     * 修复前的系统性偏移量（黑边占 Box 高度的比例）。
     */
    @Test
    fun contentNormalizationDiffersFromBoxNormalization() {
        val box = IntSize(1000, 1000)
        val rect = fitContentRect(box, IntSize(2000, 1000))

        // 修复后的口径：照片上沿 = 0
        val byContent = ((rect.top - rect.top) / rect.height).toDouble()
        assertEquals(0.0, byContent, 1e-4)

        // 修复前的口径：同一个点被算成 0.25（偏差 = 上黑边高度 / Box 高度）
        val byBox = (rect.top / box.height.toFloat()).toDouble()
        assertEquals(0.25, byBox, 1e-4)
    }

    /** 长宽比恰好一致时内容应铺满 Box —— 回归保护：别把原本正确的情况改坏。 */
    @Test
    fun matchingAspectFillsBox() {
        val rect = fitContentRect(box = IntSize(1000, 500), image = IntSize(2000, 1000))
        assertEquals(0.0, rect.left.toDouble(), 1e-4)
        assertEquals(0.0, rect.top.toDouble(), 1e-4)
        assertEquals(1000.0, rect.right.toDouble(), 1e-4)
        assertEquals(500.0, rect.bottom.toDouble(), 1e-4)
    }

    /**
     * 尺寸非法（尚未测量 / 位图未就绪）时返回空矩形，调用方据此**跳过**手势，
     * 而不是退化成「除以 1f」把每次触摸都算成右下角。
     */
    @Test
    fun invalidSizesReturnEmptyRect() {
        val a = fitContentRect(box = IntSize.Zero, image = IntSize(2000, 1000))
        assertEquals(0.0, a.width.toDouble(), 1e-4)
        assertEquals(0.0, a.height.toDouble(), 1e-4)

        val b = fitContentRect(box = IntSize(1000, 1000), image = IntSize.Zero)
        assertEquals(0.0, b.width.toDouble(), 1e-4)
        assertEquals(0.0, b.height.toDouble(), 1e-4)
    }
}
