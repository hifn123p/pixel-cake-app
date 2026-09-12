package com.hifn.pixelcake.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * anchor 生成单测（P1p-2）。锚点数量与顺序**必须**与模型输出逐行对应，
 * 否则整条解码链会整体错位——这里用「已知数字」把顺序钉死。
 */
class FaceAnchorsTest {

    @Test
    fun fullRangeYields2304Anchors() {
        val anchors = FaceAnchors.generate(FaceAnchors.FULL_RANGE)
        // ceil(192/4) = 48 ⇒ 48×48×1 = 2304，须与 regressors [1,2304,16] 对齐
        assertEquals(2304, anchors.size)
    }

    @Test
    fun shortRangeYields896Anchors() {
        val anchors = FaceAnchors.generate(FaceAnchors.SHORT_RANGE)
        // 16×16×2 + 8×8×(2+2+2) = 512 + 384 = 896
        assertEquals(896, anchors.size)
    }

    @Test
    fun fullRangeCentresAreCellCentresAndSizeIsOne() {
        val anchors = FaceAnchors.generate(FaceAnchors.FULL_RANGE)
        val first = anchors.first()
        assertEquals(0.5f / 48f, first.xCenter, 1e-6f)
        assertEquals(0.5f / 48f, first.yCenter, 1e-6f)
        // fixed_anchor_size = true ⇒ w = h = 1
        assertEquals(1f, first.w, 0f)
        assertEquals(1f, first.h, 0f)

        val last = anchors.last()
        assertEquals(47.5f / 48f, last.xCenter, 1e-6f)
        assertEquals(47.5f / 48f, last.yCenter, 1e-6f)
    }

    @Test
    fun shortRangeEmitsTwoAnchorsPerCellOnLowestStride() {
        val anchors = FaceAnchors.generate(FaceAnchors.SHORT_RANGE)
        // 第 0、1 个 anchor 同属 (y=0,x=0) 格（interpolated ratio 各贡献一个），中心 = 0.5/16
        assertEquals(0.5f / 16f, anchors[0].xCenter, 1e-6f)
        assertEquals(0.5f / 16f, anchors[1].xCenter, 1e-6f)
        assertEquals(anchors[0].yCenter, anchors[1].yCenter, 0f)
        // 第 2 个进入下一格 x=1 ⇒ 1.5/16
        assertEquals(1.5f / 16f, anchors[2].xCenter, 1e-6f)
    }

    @Test
    fun shortRangeMergesSameStrideLayersIntoOneFeatureMap() {
        val anchors = FaceAnchors.generate(FaceAnchors.SHORT_RANGE)
        // 前 512 个来自 stride=8（16×16×2），其后 384 个来自 stride=16（8×8×6）
        assertEquals(512, 16 * 16 * 2)
        // stride-16 组的第一格中心 = 0.5/8
        assertEquals(0.5f / 8f, anchors[512].xCenter, 1e-6f)
        assertEquals(0.5f / 8f, anchors[512].yCenter, 1e-6f)
        // 合并后同格 6 个 anchor 中心相同
        for (i in 513..517) {
            assertEquals(anchors[512].xCenter, anchors[i].xCenter, 0f)
            assertEquals(anchors[512].yCenter, anchors[i].yCenter, 0f)
        }
        // 第 518 个进入下一格 x=1 ⇒ 1.5/8
        assertEquals(1.5f / 8f, anchors[518].xCenter, 1e-6f)
    }

    @Test
    fun rejectsStridesCountMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            FaceAnchors.generate(
                FaceAnchors.FULL_RANGE.copy(numLayers = 2, strides = listOf(4)),
            )
        }
    }
}
