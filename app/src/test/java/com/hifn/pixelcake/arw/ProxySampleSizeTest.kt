package com.hifn.pixelcake.arw

import com.hifn.pixelcake.core.decode.Decoder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 代理解码采样档位的回归钉子（2026-10-25 全量复审）。
 *
 * 修复前两个采样函数只按 AOSP 惯例对折（「对折后就小于目标」即停）：
 * 33MP 源 + 2048 代理会停在 **3504×2336 ≈ 31MB**，是「长边 ≤2048 ≈ 11MB」
 * 预算的 3 倍，而这张位图整个编辑会话常驻（占位图 / proxyFallback / ML 推理源）。
 * 修复 = 补一条「结果超目标 1.5 倍再对折」；这些断言钉住采样档位
 * （修复前 7008/2048 会返回 2，修复后必须返回 4）。
 */
class ProxySampleSizeTest {

    @Test
    fun arw33mp_DownsampledWithinBudget() {
        // 修复前：s=2 → 3504（1.71 倍超预算）；修复后：s=4 → 1752（0.86 倍）。
        val s = ArwPreviewDecoder.computeSampleSize(7008, 4672, 2048)
        assertEquals(4, s)
        assertEquals(1752, 7008 / s)
    }

    @Test
    fun jpeg4000px_DownsampledToTarget() {
        // 修复前：s=1 → 4000（1.95 倍超预算）；修复后：s=2 → 2000。
        assertEquals(2, Decoder.computeSample(4000, 3000, 2048))
    }

    @Test
    fun mildOversampling_Within150Percent_IsKept() {
        // 3000/2048 = 1.46 倍：在 1.5 倍容忍带内，保留原档（再折就只剩 1500，预览过软）。
        assertEquals(1, Decoder.computeSample(3000, 2000, 2048))
        assertEquals(1, ArwPreviewDecoder.computeSampleSize(3000, 2000, 2048))
    }

    @Test
    fun atOrUnderTarget_NeverDownsamples() {
        assertEquals(1, Decoder.computeSample(2048, 1366, 2048))
        assertEquals(1, ArwPreviewDecoder.computeSampleSize(1024, 768, 2048))
        assertEquals(1, Decoder.computeSample(7008, 4672, 7008))
    }

    @Test
    fun lowResProxyTier_AlsoBounded() {
        // 低配档 proxy=1024：修复前 7008 → 1752（1.71 倍）；修复后 → 876。
        assertEquals(8, ArwPreviewDecoder.computeSampleSize(7008, 4672, 1024))
    }
}
