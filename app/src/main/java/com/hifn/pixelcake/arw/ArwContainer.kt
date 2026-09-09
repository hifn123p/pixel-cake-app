package com.hifn.pixelcake.arw

import com.hifn.pixelcake.diag.DebugLog

/**
 * 纯 Kotlin 的 ARW(TIFF) 容器解析，零 NDK。
 *
 * 只服务于「打开/秒开占位」：定位内嵌 JPEG 预览的字节区间。
 * Sony A7C II 的 ARW 在多个 IFD 上都挂了 `0x0201/0x0202`（JPEGInterchangeFormat /
 * JPEGInterchangeFormatLength），实测 `example/DSC04926.ARW` 的分布是：
 *   - IFD0                      → 1616×1080，192 KB
 *   - IFD 链第 2 个 IFD          → 160×120 缩略图，8 KB
 *   - **IFD 链第 3 个 IFD**      → **7008×4672 全分辨率预览，仅 1.9 MB**
 *
 * 因此不能「查到第一个就返回」，也不能只看 IFD0 + SubIFD —— 必须沿 IFD 链的 next 指针
 * 走完，把所有候选收齐后**取最大的那张**，才能拿到全分辨率预览（FIX_LIST F04）。
 *
 * 解析只读标签所需的少量字节，不加载整张 RAW（整文件可达 65MB+）。
 */
object ArwContainer {

    /** IFD 链最大遍历深度，防御畸形文件里的自环 / 超长链。 */
    private const val MAX_IFD_CHAIN = 16

    /** 内嵌预览 JPEG 的 [startOffset, endOffset] 闭区间；取候选中体积最大者。找不到返回 null。 */
    fun previewJpegRange(source: ArwByteSource): LongRange? {
        if (source.size < 8) return null
        val header = source.read(0, 8)
        val littleEndian = when {
            header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> true
            header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> false
            else -> return null
        }
        val magic = readU16(header, 2, littleEndian)
        if (magic != 42) return null

        val candidates = mutableListOf<LongRange>()
        val visited = mutableSetOf<Long>()
        var ifdOffset = readU32(header, 4, littleEndian).toLong()
        var hops = 0

        // 主 IFD 链：IFD0 -> next -> next ...，逐个收集预览候选并展开各自的 SubIFD
        while (ifdOffset > 0 && hops < MAX_IFD_CHAIN && visited.add(ifdOffset)) {
            hops++
            findPreviewInIfd(source, ifdOffset, littleEndian)?.let { candidates.add(it) }
            for (sub in readSubIfdOffsets(source, ifdOffset, littleEndian)) {
                if (visited.add(sub)) {
                    findPreviewInIfd(source, sub, littleEndian)?.let { candidates.add(it) }
                }
            }
            ifdOffset = readNextIfdOffset(source, ifdOffset, littleEndian)
        }

        val best = candidates.maxByOrNull { it.last - it.first } ?: return null
        DebugLog.i(
            DebugLog.TAG_DECODE, "arw preview candidates",
            mapOf(
                "count" to candidates.size,
                "pickedOffset" to best.first,
                "pickedLength" to (best.last - best.first + 1)
            )
        )
        return best
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
        // 越界的候选直接丢弃，否则会拿到一段截断的 JPEG
        if (jpegOffset != null && jpegLength != null && jpegLength > 0 &&
            jpegOffset + jpegLength <= source.size
        ) {
            return jpegOffset..(jpegOffset + jpegLength - 1)
        }
        return null
    }

    /** 读取 IFD 尾部的 next-IFD 偏移；无后继或越界时返回 0。 */
    private fun readNextIfdOffset(source: ArwByteSource, ifdOffset: Long, le: Boolean): Long {
        if (ifdOffset < 0 || ifdOffset + 2 > source.size) return 0L
        val head = source.read(ifdOffset, 2)
        val count = readU16(head, 0, le)
        val nextPos = ifdOffset + 2 + count * 12L
        if (nextPos + 4 > source.size) return 0L
        val buf = source.read(nextPos, 4)
        if (buf.size < 4) return 0L
        val next = readU32(buf, 0, le).toLong()
        return if (next in 1 until source.size) next else 0L
    }

    private fun readSubIfdOffsets(source: ArwByteSource, ifdOffset: Long, le: Boolean): List<Long> {
        if (ifdOffset < 0 || ifdOffset + 2 > source.size) return emptyList()
        val head = source.read(ifdOffset, 2)
        val count = readU16(head, 0, le)
        val entriesStart = ifdOffset + 2
        if (entriesStart + count * 12L > source.size) return emptyList()
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
