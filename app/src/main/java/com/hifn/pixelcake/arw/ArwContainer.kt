package com.hifn.pixelcake.arw

/**
 * 纯 Kotlin 的 ARW(TIFF) 容器解析，零 NDK。
 *
 * 只服务于「打开/预览」(M0b)：定位内嵌 JPEG 预览的字节区间。
 * Sony A7C II 的 ARW 把预览放在 IFD0 的：
 *   - 0x0201 JPEGInterchangeFormat       = 预览 JPEG 在文件中的偏移
 *   - 0x0202 JPEGInterchangeFormatLength = 预览 JPEG 的字节长度
 * 少数机型/旧固件会放在 SubIFD 里，这里做回退查找。
 *
 * 解析只读标签所需的少量字节，不加载整张 RAW（整文件可达 65MB+）。
 */
object ArwContainer {

    /** 内嵌预览 JPEG 的 [startOffset, endOffset] 闭区间；找不到返回 null。 */
    fun previewJpegRange(source: ArwByteSource): LongRange? {
        if (source.size < 8) return null
        val header = source.read(0, 8)
        val littleEndian = when {
            header[0] == 'I'.toByte() && header[1] == 'I'.toByte() -> true
            header[0] == 'M'.toByte() && header[1] == 'M'.toByte() -> false
            else -> return null
        }
        val magic = readU16(header, 2, littleEndian)
        if (magic != 42) return null
        val ifd0Offset = readU32(header, 4, littleEndian).toLong()

        findPreviewInIfd(source, ifd0Offset, littleEndian)?.let { return it }

        for (sub in readSubIfdOffsets(source, ifd0Offset, littleEndian)) {
            findPreviewInIfd(source, sub, littleEndian)?.let { return it }
        }
        return null
    }

    private fun findPreviewInIfd(source: ArwByteSource, ifdOffset: Long, le: Boolean): LongRange? {
        if (ifdOffset < 0 || ifdOffset + 2 > source.size) return null
        val head = source.read(ifdOffset, 2)
        val count = readU16(head, 0, le)
        val entriesStart = ifdOffset + 2
        val entriesEnd = entriesStart + count * 12L
        if (entriesEnd > source.size) return null

        var jpegOffset: Long? = null
        var jpegLength: Long? = null
        for (i in 0 until count) {
            val entry = source.read(entriesStart + i * 12L, 12)
            val tag = readU16(entry, 0, le)
            val type = readU16(entry, 2, le)
            val value = readU32(entry, 8, le).toLong()
            if (type == 4) {
                when (tag) {
                    0x0201 -> jpegOffset = value
                    0x0202 -> jpegLength = value
                }
            }
        }
        if (jpegOffset != null && jpegLength != null && jpegLength > 0) {
            return jpegOffset..(jpegOffset + jpegLength - 1)
        }
        return null
    }

    private fun readSubIfdOffsets(source: ArwByteSource, ifdOffset: Long, le: Boolean): List<Long> {
        if (ifdOffset < 0 || ifdOffset + 2 > source.size) return emptyList()
        val head = source.read(ifdOffset, 2)
        val count = readU16(head, 0, le)
        val entriesStart = ifdOffset + 2
        val result = mutableListOf<Long>()
        for (i in 0 until count) {
            val entry = source.read(entriesStart + i * 12L, 12)
            val tag = readU16(entry, 0, le)
            val type = readU16(entry, 2, le)
            val cnt = readU32(entry, 4, le)
            val value = readU32(entry, 8, le).toLong()
            if (tag == 0x014a && type == 4) {
                if (cnt == 1L) {
                    result.add(value)
                } else if (value + cnt * 4 <= source.size) {
                    val buf = source.read(value, (cnt * 4).toInt())
                    for (j in 0 until cnt) {
                        result.add(readU32(buf, (j * 4).toInt(), le).toLong())
                    }
                }
            }
        }
        return result
    }

    private fun readU16(buf: ByteArray, off: Int, le: Boolean): Int =
        if (le) {
            (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
        } else {
            ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
        }

    private fun readU32(buf: ByteArray, off: Int, le: Boolean): Long {
        val b0 = buf[off].toInt() and 0xFF
        val b1 = buf[off + 1].toInt() and 0xFF
        val b2 = buf[off + 2].toInt() and 0xFF
        val b3 = buf[off + 3].toInt() and 0xFF
        val v = if (le) {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        } else {
            b3 or (b2 shl 8) or (b1 shl 16) or (b0 shl 24)
        }
        return v.toLong() and 0xFFFFFFFFL
    }
}

/** 只读片段数据源，便于在设备(ContentResolver)与 JVM 单测(ByteArray)间复用。 */
interface ArwByteSource {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}
