package com.hifn.pixelcake.core.camera

import com.hifn.pixelcake.camera.PtpObjectInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系留轮询状态机的回归测试（批 5 可测化；hy4 阶段 2 判据 11 ——
 * K2 基线播种 / K3 坏对象记账 / K1 路由契约，各自一条**会红**的断言）。
 */
class TetherProcessorTest {

    private val logs = mutableListOf<String>()

    private fun processor(maxPerRound: Int = 3): TetherProcessor =
        TetherProcessor(log = { logs.add(it) }, maxPerRound = maxPerRound)

    private fun info(name: String): PtpObjectInfo = PtpObjectInfo(
        storageId = 1, format = 0x3000, protectionStatus = 0,
        compressedSizeBytes = 40_000_000L, imageWidth = 7008, imageHeight = 4672,
        imageBitDepth = 24, parentObject = 0, sequenceNumber = 0,
        filename = name, captureDate = "2026:11:07 12:00:00", modificationDate = "2026:11:07 12:00:00"
    )

    private fun fakeInfoOf(vararg map: Pair<Int, PtpObjectInfo?>): suspend (Int) -> PtpObjectInfo? {
        val m = map.toMap()
        return { h -> m[h] }
    }

    @Test
    fun k2_baselineSeed_keepsOldPhotosUnprocessed() = runTest {
        val p = processor()
        p.seedBaseline(listOf(1, 2, 3))
        // 卡上还是那三个对象 ⇒ 没有任何「新片」
        val out = p.collectNewPhotos(listOf(1, 2, 3), fakeInfoOf())
        assertTrue("基线播种后旧片必须全部跳过（K2）", out.isEmpty())
        assertEquals(3, p.knownCount)
    }

    @Test
    fun k3_badObjectIsBookedAndNeverBlocksNewOnes() = runTest {
        val p = processor()
        // 句柄 1 读不到 Info（坏对象）；2/3/4 是照片。
        // 名额上限（maxPerRound=3）在**原始 fresh 列表**上截断（与旧口径一致：take(3) 先行），
        // 所以本轮 = 1(坏，记账跳过) / 2 / 3；关键性质在第二轮：
        val info = fakeInfoOf(
            2 to info("DSC00002.JPG"), 3 to info("DSC00003.JPG"), 4 to info("DSC00004.ARW")
        )
        val round1 = p.collectNewPhotos(listOf(1, 2, 3, 4), info)
        assertEquals(listOf(2, 3), round1.map { it.handle })
        assertTrue("坏对象必须记账并打日志（K3）", logs.any { it.contains("#1") })
        // 第二轮：1 已记账不再进 fresh，4/5 正常进入 —— 坏对象**永久占位**被证伪。
        val round2 = p.collectNewPhotos(listOf(1, 2, 3, 4, 5), info)
        assertEquals("坏对象记账后不再占名额（K3 的本质）", listOf(4, 5), round2.map { it.handle })
    }

    @Test
    fun nonPhotoObjectIsBookedSilently() = runTest {
        val p = processor()
        val dir = info("100MSDCF")            // 目录：无照片后缀
        val info = fakeInfoOf(9 to dir, 10 to info("DSC00010.JPG"))
        val out = p.collectNewPhotos(listOf(9, 10), info)
        assertEquals(listOf(10), out.map { it.handle })
        // 目录是正常对象：不打日志（只有「读不到信息」才打）
        assertTrue(logs.none { it.contains("#9") })
    }

    @Test
    fun maxPerRoundCapsEachRound() = runTest {
        val p = processor(maxPerRound = 2)
        val info = fakeInfoOf(
            1 to info("A.JPG"), 2 to info("B.JPG"), 3 to info("C.JPG"), 4 to info("D.JPG")
        )
        val out = p.collectNewPhotos(listOf(1, 2, 3, 4), info)
        assertEquals(2, out.size)
        // 未处理的 3/4 下轮仍在 fresh 集合里（记账只在交付时发生）
        val out2 = p.collectNewPhotos(listOf(1, 2, 3, 4), info)
        assertEquals(listOf(3, 4), out2.map { it.handle })
    }

    @Test
    fun k1_arwObjectIsDeliveredWithIsRawTrue() = runTest {
        val p = processor()
        val info = fakeInfoOf(7 to info("DSC00007.ARW"))
        val out = p.collectNewPhotos(listOf(7), info)
        assertEquals(1, out.size)
        // K1 路由契约：处理回调拿到的 photo.isRaw == true ⇒ 上层据此走 LibRaw 线性管线
        //（decodeFullRes 对 .arw 设计上返回 null —— 路由错就是 P0-1 复发）。
        assertTrue("RAW 对象必须以 isRaw=true 交付（K1）", out[0].isRaw)
    }
}
