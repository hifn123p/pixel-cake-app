package com.hifn.pixelcake.arw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ARW 预览解析的 JVM 单测（不依赖 Android，可在 CI 的 unitTest 阶段跑）。
 * 由于 65MB 真实样本已 gitignore，这里用合成的最小 TIFF 覆盖三条路径：
 *   1) 预览在 IFD0；2) 预览在 SubIFD（回退查找）；3) 无预览标签。
 */
class ArwPreviewExtractorTest {

    // 一个最小“JPEG”：SOI(FF D8) ... EOI(FF D9)，中间随意填充，长度 7
    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03, 0xFF.toByte(), 0xD9.toByte()
    )

    private fun u16le(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    private fun u32le(v: Long): ByteArray {
        val x = v and 0xFFFFFFFFL
        return byteArrayOf(
            (x and 0xFF).toByte(),
            ((x ushr 8) and 0xFF).toByte(),
            ((x ushr 16) and 0xFF).toByte(),
            ((x ushr 24) and 0xFF).toByte()
        )
    }

    private fun entry(tag: Int, type: Int, count: Long, value: Long): ByteArray =
        u16le(tag) + u16le(type) + u32le(count) + u32le(value)

    private class ByteArrayArwSource(val data: ByteArray) : ArwByteSource {
        override val size: Long get() = data.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray {
            val start = offset.toInt().coerceAtLeast(0)
            val end = (start + length).coerceAtMost(data.size)
            return data.copyOfRange(start, end)
        }
    }

    @Test
    fun previewInIfd0_isFound() {
        // IFD0: 2 条 (0x0201 -> jpeg@38, 0x0202 -> len 7)，next IFD = 0
        val ifd0 = u16le(2) + entry(0x0201, 4, 1, 38) + entry(0x0202, 4, 1, 7) + u32le(0)
        // header(8) + ifd0(2 + 24 + 4 = 30) -> jpeg 落在索引 38
        val bytes = byteArrayOf(0x49, 0x49) + u16le(42) + u32le(8) + ifd0 + jpeg
        val src = ByteArrayArwSource(bytes)
        val range = ArwContainer.previewJpegRange(src)!!
        assertEquals(38L, range.first)
        assertEquals(44L, range.last)
        val out = ArwPreviewExtractor.extract(src)
        assertNotNull(out)
        assertEquals(7, out!!.size)
    }

    @Test
    fun previewInSubIfd_isFoundViaFallback() {
        // SubIFD 位于 26，内部预览 @56
        val sub = u16le(2) + entry(0x0201, 4, 1, 56) + entry(0x0202, 4, 1, 7) + u32le(0)
        // IFD0: 1 条 0x014a -> 26，next IFD = 0
        val ifd0 = u16le(1) + entry(0x014a, 4, 1, 26) + u32le(0)
        // header(8) + ifd0(18) -> sub@26；sub(30) -> jpeg@56
        val bytes = byteArrayOf(0x49, 0x49) + u16le(42) + u32le(8) + ifd0 + sub + jpeg
        val src = ByteArrayArwSource(bytes)
        val range = ArwContainer.previewJpegRange(src)!!
        assertEquals(56L, range.first)
        assertEquals(62L, range.last)
        val out = ArwPreviewExtractor.extract(src)
        assertNotNull(out)
        assertEquals(7, out!!.size)
    }

    @Test
    fun noPreviewTags_returnsNull() {
        // IFD0: 1 条无关条目 (0x0100 ImageWidth)，无 0x0201/0x0202 也无 0x014a
        val ifd0 = u16le(1) + entry(0x0100, 4, 1, 1) + u32le(0)
        val bytes = byteArrayOf(0x49, 0x49) + u16le(42) + u32le(8) + ifd0
        val src = ByteArrayArwSource(bytes)
        assertNull(ArwContainer.previewJpegRange(src))
    }
}
