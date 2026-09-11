package com.hifn.pixelcake.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hifn.pixelcake.core.edit.RetouchScale

/**
 * P2 PoC-5：批量流水线中**纯逻辑**部分的回归（纯 JVM，不需要真机/相机）。
 *
 * 真正要上真机才能验的是「USB 会话 + GetObject」；但下面这些不依赖 Android 的判定与文案
 * 一旦写错，会在真机上以「文件名错乱 / 被当成 JPEG / 列表误判」等形式暴露，
 * 排查成本极高，所以在这里钉住。
 */
class CameraBatchTest {

    // ---------------- 文件名清洗 / 补后缀 ----------------

    @Test
    fun safeNameKeepsExtensionAndSanitizesIllegalChars() {
        // 相机正常给名：原样保留（后缀必须保留，ARW/JPEG 路由全靠它）
        assertEquals("DSC01234.ARW", CameraBatch.safeName("DSC01234.ARW", 7))
        // 非法字符（空格 / 斜杠 / 冒号）替换为下划线
        assertEquals("a_b_c_d.jpg", CameraBatch.safeName("a b/c:d.jpg", 7))
        // 空名 / 全是分隔符 → 用句柄兜底，不能产出空文件名
        assertEquals("cam_7", CameraBatch.safeName("", 7))
        assertEquals("cam_7", CameraBatch.safeName("...", 7))
    }

    @Test
    fun targetNameAppendsExtensionWhenCameraGivesNone() {
        // 相机没给后缀时，按相机自报类型补一个 —— 否则 ARW 会被当成 JPEG 走 BitmapFactory 而解码失败
        assertEquals("cam_42.ARW", CameraBatch.targetName(photo("", PtpProtocol.FORMAT_ARW)))
        assertEquals("cam_42.JPG", CameraBatch.targetName(photo("", PtpProtocol.FORMAT_UNDEFINED)))
        // 已有后缀则一字不改
        assertEquals("DSC01234.ARW", CameraBatch.targetName(photo("DSC01234.ARW", PtpProtocol.FORMAT_ARW)))
        assertEquals("DSC01234.JPG", CameraBatch.targetName(photo("DSC01234.JPG", PtpProtocol.FORMAT_UNDEFINED)))
    }

    // ---------------- 照片过滤 ----------------

    @Test
    fun photoFilterPrefersExtensionThenFormat() {
        assertTrue(CameraPhotoFilter.isPhoto(photo("DSC01234.ARW", PtpProtocol.FORMAT_UNDEFINED)))
        assertTrue(CameraPhotoFilter.isPhoto(photo("DSC01234.JPG", PtpProtocol.FORMAT_UNDEFINED)))
        assertTrue(CameraPhotoFilter.isPhoto(photo("DSC01234.HIF", PtpProtocol.FORMAT_UNDEFINED)))
        // 非照片后缀（相机目录对象等）应被排除
        assertFalse(CameraPhotoFilter.isPhoto(photo("MISC.TXT", PtpProtocol.FORMAT_UNDEFINED)))
        // 无名对象：退回格式码
        assertTrue(CameraPhotoFilter.isPhoto(photo("", PtpProtocol.FORMAT_EXIF_JPEG)))
        assertFalse(CameraPhotoFilter.isPhoto(photo("", PtpProtocol.FORMAT_UNDEFINED)))
    }

    @Test
    fun photoTypeAndLabelAreUiFriendly() {
        val raw = photo("DSC01234.ARW", PtpProtocol.FORMAT_UNDEFINED, width = 7008, height = 4672)
        assertTrue(raw.isRaw)
        assertEquals("RAW", raw.typeName())
        val label = raw.label()
        assertTrue(label.contains("DSC01234.ARW"))
        assertTrue(label.contains("RAW"))
    }

    // ---------------- 进度文案 ----------------

    @Test
    fun progressPercentAndText() {
        val half = CameraBatch.Progress(1, 3, "a.ARW", "拉取中", 5L * 1024 * 1024, 10L * 1024 * 1024)
        assertEquals(50, half.percent)
        val text = half.text()
        assertTrue(text.contains("1/3"))
        assertTrue(text.contains("50%"))
        assertTrue(text.contains("5MB / 10MB"))

        // 未知总长：不显示百分比，只显示阶段（相机不报长度时走这条）
        val unknown = CameraBatch.Progress(2, 3, "b.ARW", "套预设 + 导出")
        assertEquals(0, unknown.percent)
        assertTrue(unknown.text().contains("套预设 + 导出"))
    }

    // ---------------- 批量摘要 ----------------

    @Test
    fun summaryLinesReflectSuccessesAndFailures() {
        val summary = CameraBatch.Summary(
            items = listOf(
                CameraBatch.ItemResult("a.ARW", true, "content://out/1", "已导出", 120L),
                CameraBatch.ItemResult("b.JPG", false, null, "解码失败", 30L)
            ),
            cancelled = false,
            elapsedMs = 500L
        )
        assertEquals(1, summary.okCount)
        val lines = summary.summaryLines()
        assertTrue(lines.first().contains("成功 1 / 共 2"))
        assertTrue(lines.any { it.contains("a.ARW") && it.contains("已导出") })
        assertTrue(lines.any { it.contains("b.JPG") && it.contains("解码失败") })
        assertFalse(lines.first().contains("已取消"))
    }

    @Test
    fun cancelledSummarySaysSo() {
        val summary = CameraBatch.Summary(
            items = listOf(CameraBatch.ItemResult("a.ARW", true, "u", "已导出", 10L)),
            cancelled = true,
            elapsedMs = 10L
        )
        assertTrue(summary.summaryLines().first().contains("已取消"))
    }

    // ---------------- 尺寸换算（批量渲染半径的依据） ----------------

    @Test
    fun fitLongEdgeKeepsLongSideAndPreservesAspect() {
        assertEquals(4096 to 2730, RetouchScale.fitLongEdge(7008, 4672, 4096))
        // 竖幅
        assertEquals(2730 to 4096, RetouchScale.fitLongEdge(4672, 7008, 4096))
        // 相机没报尺寸 → 退化为方形，只会让归一化半径偏小，不会崩
        assertEquals(2048 to 2048, RetouchScale.fitLongEdge(0, 0, 2048))
    }
}

/** 构造一个相机对象（句柄固定 42），供纯 JVM 断言使用。 */
private fun photo(
    filename: String,
    format: Int,
    width: Int = 7008,
    height: Int = 4672
): CameraPhoto = CameraPhoto(
    handle = 42,
    info = PtpObjectInfo(
        storageId = 0x00010001,
        format = format,
        protectionStatus = 0,
        compressedSizeBytes = 26L * 1024 * 1024,
        imageWidth = width,
        imageHeight = height,
        imageBitDepth = 14,
        parentObject = PtpProtocol.HANDLE_ALL,
        sequenceNumber = 42,
        filename = filename,
        captureDate = "20260910T181500",
        modificationDate = "20260910T181500"
    )
)
