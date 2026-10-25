package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Histogram] 纯函数的回归钉子（2026-10-25 全量复审）。
 *
 * 重点钉 [Histogram.clippedMedian] 的**中位数位置**：修复前的越过阈值写作
 * `(clip + total) / 2`，而「裁掉两端各 clip 后取中位数」的正确位置是
 * `clip + total / 2` —— 两者相差 clip/2 个样本（读数系统性偏暗约 2.5% 分位）。
 * 断言刻意选在「旧阈值与新阈值给出**不同桶**」的分布上（见 clippedMedian_…50Samples），
 * 只断言对称图（旧新同桶）区分不出修复前后。
 */
class HistogramTest {

    @Test
    fun clippedMedian_crossesAtCorrectRank_50Samples() {
        // 50 样本、clip=2：保留 46 个，中位数 = 第 23 个保留样本 = 整体累计第 25 个
        // ⇒ 落在 bin 11（F(10)=24 < 25 ≤ F(11)=25）。
        // 旧阈值 (2+50)/2=26 会在 F=24（bin 10）就提前返回 —— 本断言修复前失败、修复后通过。
        val bins = IntArray(Histogram.BINS)
        bins[10] = 24
        bins[11] = 1
        bins[200] = 25 // 剩余样本垫在高光端，不影响低位穿越
        assertEquals(11, Histogram.clippedMedian(bins))
    }

    @Test
    fun clippedMedian_singleBinDistribution() {
        val bins = IntArray(Histogram.BINS)
        bins[128] = 100
        assertEquals(128, Histogram.clippedMedian(bins))
    }

    @Test
    fun clippedMedian_emptyFallsBackToMidGray() {
        assertEquals(128, Histogram.clippedMedian(IntArray(Histogram.BINS)))
    }

    @Test
    fun clippedMedian_uniformFullRange() {
        val bins = IntArray(Histogram.BINS) { 1 }
        // 均匀分布 0..255 的中位数在 127/128 之间；两端各裁 12 个后落在 bin 127。
        assertEquals(127, Histogram.clippedMedian(bins))
    }

    @Test
    fun normalize_peakBinMapsToOne() {
        val out = Histogram.normalize(intArrayOf(0, 10, 5, 0))
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(1f, out[1], 1e-6f)
        assertEquals(0.5f, out[2], 1e-6f)
    }

    @Test
    fun normalize_allZeroStaysZero() {
        val out = Histogram.normalize(IntArray(4))
        assertEquals(0f, out.max(), 1e-6f)
    }

    @Test
    fun meanShift_brighterAfterMeansPositive() {
        // ⚠️ 数组必须开满 [Histogram.BINS]：索引 10 / 200 分别是「暗端」与「亮端」的落点。
        // 写成 `IntArray(4)` 会在 `before[10] = 100` 处抛 IndexOutOfBoundsException ——
        // 表现出来是「测试 FAILED 但没有 expected/but was」，很容易被误读成断言不通过。
        val before = IntArray(Histogram.BINS)
        before[10] = 100
        val after = IntArray(Histogram.BINS)
        after[200] = 100
        // 平均灰阶从 10 抬到 200：(200-10)/128 ≈ +1.48
        assertEquals((200 - 10) / 128f, Histogram.meanShift(before, after), 1e-3f)
    }

    @Test
    fun meanShift_identicalIsZero() {
        val b = IntArray(Histogram.BINS).also { it[3] = 7; it[9] = 3 }
        assertEquals(0f, Histogram.meanShift(b, b.copyOf()), 1e-6f)
    }
}
