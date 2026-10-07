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
 * 走完，把所有候选收齐后**取最大的那张**，才能拿到全分辨率预览（PHASE_DESIGN_HISTORY.md（审查台账） F04）。
 *
 * 解析只读标签所需的少量字节，不加载整张 RAW（整文件可达 65MB+）。
 */
object ArwContainer {

    /** IFD 链最大遍历深度，防御畸形文件里的自环 / 超长链。 */
    private const val MAX_IFD_CHAIN = 16

    /**
     * SubIFD 数量上限。真实 ARW 的 SubIFD 只有个位数；`cnt` 是 U32，只被文件大小约束，
     * 不封顶就能让一个畸形 tag 把解析线程拖死（见 [readSubIfdOffsets]）。
     */
    private const val MAX_SUBIFD_COUNT = 64L

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
        if (magic != 42) {
            // 43 = BigTIFF。换机型 / 厂商升级到 BigTIFF 时这里会 return null，
            // 而日志与「文件里没有内嵌预览」完全一样 ⇒ 排查时被误导一轮。
            // 所以这里明确区分一句。
            DebugLog.w(
                DebugLog.TAG_DECODE, "arw: not a classic TIFF",
                mapOf("magic" to magic, "bigTiff" to (magic == 43))
            )
            return null
        }

        val candidates = mutableListOf<LongRange>()
        val visited = mutableSetOf<Long>()
        var ifdOffset = readU32(header, 4, littleEndian).toLong()
        var hops = 0

        // 主 IFD 链：IFD0 -> next -> next ...，逐个收集预览候选并展开各自的 SubIFD。
        // 关键：主链是否继续只由 next 指针决定（受 MAX_IFD_CHAIN 防环），
        // visited 只用于 SubIFD 去重，绝不因「某 IFD 此前被当 SubIFD 访问过」而提前退出主链。
        // F04 回归修复：原实现把 visited.add(ifdOffset) 写进 while 条件，导致 SubIFD 与 next
        // 指向同一偏移时主链直接退出，漏掉挂着全分辨率预览的后续 IFD。
        while (ifdOffset > 0 && hops < MAX_IFD_CHAIN) {
            hops++
            val firstTouch = visited.add(ifdOffset)
            // ⚠️ entry count 必须**只读一次**：以前下面三个函数各自 source.read(ifdOffset, 2)，
            // 而 ContentArwSource 的每次 read 都要 openInputStream + 从 0 skip 到偏移 ⇒
            // 「同一个 IFD 的 2 字节读 3 次」在 ContentResolver 上就是 3 次开流 + 3 次长距离跳。
            val count = readIfdCount(source, ifdOffset, littleEndian)
            if (count >= 0) {
                findPreviewInIfd(source, ifdOffset, count, littleEndian)?.let { candidates.add(it) }
                if (firstTouch) {
                    for (sub in readSubIfdOffsets(source, ifdOffset, count, littleEndian)) {
                        if (visited.add(sub)) {
                            findPreviewInIfd(source, sub, readIfdCount(source, sub, littleEndian), littleEndian)
                                ?.let { candidates.add(it) }
                        }
                    }
                }
                ifdOffset = readNextIfdOffset(source, ifdOffset, count, littleEndian)
            } else {
                ifdOffset = 0L
            }
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

    /** 读 IFD 头部的 entry 数量；偏移非法或越界返回 −1（调用方据此终止该分支）。 */
    private fun readIfdCount(source: ArwByteSource, ifdOffset: Long, le: Boolean): Int {
        if (ifdOffset < 0 || ifdOffset + 2 > source.size) return -1
        val head = source.read(ifdOffset, 2)
        if (head.size < 2) return -1
        return readU16(head, 0, le)
    }

    /**
     * @param count 已读出的 entry 数量（见 [readIfdCount]），避免重复开流读同一个 2 字节。
     */
    private fun findPreviewInIfd(
        source: ArwByteSource,
        ifdOffset: Long,
        count: Int,
        le: Boolean
    ): LongRange? {
        if (count < 0) return null
        val entriesStart = ifdOffset + 2
        if (entriesStart + count * 12L > source.size) return null
        // ⚠️ 整个 entry 区**一次性读入**再切片。以前每个 entry 单独 source.read()，
        // 而 ContentArwSource 每次 read 都要 openInputStream + 从 0 skip ⇒ 16 IFD × 30 entry
        // 就是约 500 次开流 + 500 次长距离跳，O(n²) 级 IO —— 这是「秒开」变慢数秒的真凶。
        val table = if (count > 0) source.read(entriesStart, count * 12) else ByteArray(0)

        var jpegOffset: Long? = null
        var jpegLength: Long? = null
        for (i in 0 until count) {
            val off = i * 12
            // provider 可能短读；table 不足时必须停在这里而不是让 readU16/32 越界。
            if (off + 12 > table.size) break
            val tag = readU16(table, off, le)
            val type = readU16(table, off + 2, le)
            val value = readU32(table, off + 8, le).toLong()
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
    private fun readNextIfdOffset(
        source: ArwByteSource,
        ifdOffset: Long,
        count: Int,
        le: Boolean
    ): Long {
        if (count < 0) return 0L
        val nextPos = ifdOffset + 2 + count * 12L
        if (nextPos + 4 > source.size) return 0L
        val buf = source.read(nextPos, 4)
        if (buf.size < 4) return 0L
        val next = readU32(buf, 0, le).toLong()
        return if (next in 1 until source.size) next else 0L
    }

    private fun readSubIfdOffsets(
        source: ArwByteSource,
        ifdOffset: Long,
        count: Int,
        le: Boolean
    ): List<Long> {
        if (count < 0) return emptyList()
        val entriesStart = ifdOffset + 2
        if (entriesStart + count * 12L > source.size) return emptyList()
        val table = if (count > 0) source.read(entriesStart, count * 12) else ByteArray(0)
        val result = mutableListOf<Long>()
        for (i in 0 until count) {
            val off = i * 12
            if (off + 12 > table.size) break
            val tag = readU16(table, off, le)
            val type = readU16(table, off + 2, le)
            val cnt = readU32(table, off + 4, le)
            val value = readU32(table, off + 8, le).toLong()
            if (tag == 0x014a && type == 4) {
                if (cnt == 1L) {
                    result.add(value)
                } else if (cnt >= 1L && cnt <= MAX_SUBIFD_COUNT && value + cnt * 4 <= source.size) {
                    // ⚠️ 必须给 SubIFD 数量封顶：cnt 是 U32，上界只来自文件大小 ⇒ 65MB 文件里
                    // 一个畸形 tag 就能让 cnt 达到 1600 万，然后每个都会被 findPreviewInIfd
                    // 解析一次（合「每次 open+skip」彻底卡死 IO 线程）。真实 ARW 的 SubIFD 只有个位数。
                    val buf = source.read(value, (cnt * 4).toInt())
                    for (j in 0 until cnt) {
                        result.add(readU32(buf, (j * 4).toInt(), le).toLong())
                    }
                } else if (cnt > MAX_SUBIFD_COUNT) {
                    DebugLog.w(
                        DebugLog.TAG_DECODE, "arw: subifd count implausible, ignored",
                        mapOf("count" to cnt, "cap" to MAX_SUBIFD_COUNT)
                    )
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
