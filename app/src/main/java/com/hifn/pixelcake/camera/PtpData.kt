package com.hifn.pixelcake.camera

/**
 * PTP 数据集读取器（little-endian 游标，越界读取返回 0 而不抛异常）。
 *
 * 为什么「不抛异常」：这些字节来自相机的真实响应，任何字节都可能出现在任何位置。
 * PoC 阶段宁可得出一份残缺但可读的报告（并附上原始长度），也不要因为一个越界崩溃丢掉整轮真机验证。
 */
class PtpReader(private val buf: ByteArray, private var pos: Int = 0) {

    val remaining: Int get() = buf.size - pos

    fun u8(): Int {
        if (remaining < 1) return 0
        val v = buf[pos].toInt() and 0xFF
        pos += 1
        return v
    }

    fun u16(): Int {
        if (remaining < 2) return 0
        val v = PtpProtocol.readU16(buf, pos)
        pos += 2
        return v
    }

    fun u32(): Int {
        if (remaining < 4) return 0
        val v = PtpProtocol.readU32(buf, pos)
        pos += 4
        return v
    }

    fun u64(): Long {
        if (remaining < 8) return 0L
        val lo = PtpProtocol.readU32(buf, pos).toLong() and 0xFFFFFFFFL
        val hi = PtpProtocol.readU32(buf, pos + 4).toLong() and 0xFFFFFFFFL
        pos += 8
        return (hi shl 32) or lo
    }

    /**
     * PTP 字符串：1 字节长度 n（**含结尾 NUL**）+ n 个 UTF-16LE 码元。
     * 长度 0 表示空串；结尾 NUL 与尾部填充一并裁掉。
     */
    fun string(): String {
        val n = u8()
        if (n <= 0) return ""
        val chars = CharArray(n)
        var read = 0
        while (read < n) {
            if (remaining < 2) break
            chars[read] = u16().toChar()
            read += 1
        }
        return chars.concatToString().trimEnd('\u0000')
    }

    /** 长度前缀的 u16 数组（如 OperationsSupported / ImageFormats）。 */
    fun u16List(): List<Int> {
        val count = u32()
        if (count <= 0 || count > MAX_ARRAY) return emptyList()
        if (remaining < count * 2) return emptyList()
        val out = ArrayList<Int>(count)
        for (i in 0 until count) {
            out.add(u16())
        }
        return out
    }

    /** 长度前缀的 u32 数组（如 StorageIDs / ObjectHandles）。 */
    fun u32List(): List<Int> {
        val count = u32()
        if (count <= 0 || count > MAX_ARRAY) return emptyList()
        if (remaining < count * 4) return emptyList()
        val out = ArrayList<Int>(count)
        for (i in 0 until count) {
            out.add(u32())
        }
        return out
    }

    private companion object {
        /** 数组长度上限：既是防御（畸形数据），也足够覆盖真实相机的任何列表。 */
        const val MAX_ARRAY = 1_000_000
    }
}

/** DeviceInfo 数据集（PTP §5.1）。 */
data class PtpDeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Int,
    val vendorExtensionVersion: Int,
    val vendorExtensionDesc: String,
    val functionalMode: Int,
    val operationsSupported: List<Int>,
    val eventsSupported: List<Int>,
    val devicePropertiesSupported: List<Int>,
    val captureFormats: List<Int>,
    val imageFormats: List<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String
) {
    fun hasOperation(code: Int): Boolean = operationsSupported.contains(code)

    /** 一行摘要（用于报告与日志）。 */
    fun headline(): String =
        "$manufacturer $model".trim() + " · 固件 $deviceVersion · SN $serialNumber"
}

/** StorageInfo 数据集（PTP §5.4）。 */
data class PtpStorageInfo(
    val storageId: Int,
    val storageType: Int,
    val filesystemType: Int,
    val accessCapability: Int,
    val maxCapacityBytes: Long,
    val freeSpaceBytes: Long,
    val freeSpaceImages: Int,
    val description: String,
    val volumeLabel: String
) {
    fun storageTypeName(): String = when (storageType) {
        0 -> "未定义"
        1 -> "固定 ROM"
        2 -> "可移动 ROM"
        3 -> "固定 RAM（机身存储）"
        4 -> "可移动 RAM（存储卡）"
        else -> PtpProtocol.hex4(storageType)
    }

    fun accessName(): String = when (accessCapability) {
        0 -> "读写"
        1 -> "只读（不可删）"
        2 -> "只读（光盘类）"
        else -> PtpProtocol.hex4(accessCapability)
    }

    fun freeSpaceMiB(): Long = freeSpaceBytes / (1024L * 1024L)

    fun capacityGiB(): Double = maxCapacityBytes / (1024.0 * 1024.0 * 1024.0)

    fun label(): String {
        val title = volumeLabel.ifBlank { description }.ifBlank { "存储 ${PtpProtocol.hex8(storageId)}" }
        return "$title（${storageTypeName()} · ${accessName()} · " +
            "%.1fGiB 可用 %dMiB / 可存 %d 张）".format(capacityGiB(), freeSpaceMiB(), freeSpaceImages)
    }
}

/** ObjectInfo 数据集（PTP §5.8）。 */
data class PtpObjectInfo(
    val storageId: Int,
    val format: Int,
    val protectionStatus: Int,
    val compressedSizeBytes: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageBitDepth: Int,
    val parentObject: Int,
    val sequenceNumber: Int,
    val filename: String,
    val captureDate: String,
    val modificationDate: String
) {
    val extension: String
        get() = filename.substringAfterLast('.', "").lowercase()

    /** ARW/RAW 候选：优先看后缀（格式码不可靠，见 [PtpProtocol.FORMAT_ARW] 注释）。 */
    val looksLikeRaw: Boolean
        get() = extension == "arw" || extension == "dng" || format == PtpProtocol.FORMAT_ARW

    val looksLikeJpeg: Boolean
        get() = extension == "jpg" || extension == "jpeg" || format == PtpProtocol.FORMAT_EXIF_JPEG

    fun sizeMiB(): Double = compressedSizeBytes / (1024.0 * 1024.0)

    fun label(): String = "%s · %s · %.1fMB · %d×%d · %s".format(
        filename.ifBlank { "（无文件名）" },
        PtpProtocol.formatName(format),
        sizeMiB(),
        imageWidth,
        imageHeight,
        captureDate.ifBlank { modificationDate }
    )
}

/** 解析 DeviceInfo 数据集；结构明显不完整时返回 null。 */
fun parseDeviceInfo(payload: ByteArray): PtpDeviceInfo? {
    // 最小长度 = 2+4+2+1+2+4*5+1*4(strings 长度字节) = 35；再宽松一点也不接受 < 24 的输入。
    if (payload.size < 24) return null
    return try {
        val r = PtpReader(payload)
        PtpDeviceInfo(
            standardVersion = r.u16(),
            vendorExtensionId = r.u32(),
            vendorExtensionVersion = r.u16(),
            vendorExtensionDesc = r.string(),
            functionalMode = r.u16(),
            operationsSupported = r.u16List(),
            eventsSupported = r.u16List(),
            devicePropertiesSupported = r.u16List(),
            captureFormats = r.u16List(),
            imageFormats = r.u16List(),
            manufacturer = r.string(),
            model = r.string(),
            deviceVersion = r.string(),
            serialNumber = r.string()
        )
    } catch (_: Exception) {
        null
    }
}

/** 解析 StorageIDs 数据集。 */
fun parseStorageIds(payload: ByteArray): List<Int> =
    try {
        PtpReader(payload).u32List()
    } catch (_: Exception) {
        emptyList()
    }

/** 解析 ObjectHandles 数据集。 */
fun parseObjectHandles(payload: ByteArray): List<Int> =
    try {
        PtpReader(payload).u32List()
    } catch (_: Exception) {
        emptyList()
    }

/** 解析 StorageInfo 数据集。 */
fun parseStorageInfo(payload: ByteArray): PtpStorageInfo? {
    if (payload.size < 26) return null
    return try {
        val r = PtpReader(payload)
        PtpStorageInfo(
            storageId = r.u32(),
            storageType = r.u16(),
            filesystemType = r.u16(),
            accessCapability = r.u16(),
            maxCapacityBytes = r.u64(),
            freeSpaceBytes = r.u64(),
            freeSpaceImages = r.u32(),
            description = r.string(),
            volumeLabel = r.string()
        )
    } catch (_: Exception) {
        null
    }
}

/** 解析 ObjectInfo 数据集。 */
fun parseObjectInfo(payload: ByteArray): PtpObjectInfo? {
    if (payload.size < 52) return null
    return try {
        val r = PtpReader(payload)
        val storageId = r.u32()
        val format = r.u16()
        val protection = r.u16()
        val size = r.u32().toLong() and 0xFFFFFFFFL
        r.u16() // ThumbFormat
        r.u32() // ThumbCompressedSize
        r.u32() // ThumbPixWidth
        r.u32() // ThumbPixHeight
        val width = r.u32()
        val height = r.u32()
        val depth = r.u32()
        val parent = r.u32()
        r.u16() // AssociationType
        r.u32() // AssociationDesc
        val sequence = r.u32()
        val filename = r.string()
        val captureDate = r.string()
        val modificationDate = r.string()
        PtpObjectInfo(
            storageId = storageId,
            format = format,
            protectionStatus = protection,
            compressedSizeBytes = size,
            imageWidth = width,
            imageHeight = height,
            imageBitDepth = depth,
            parentObject = parent,
            sequenceNumber = sequence,
            filename = filename,
            captureDate = captureDate,
            modificationDate = modificationDate
        )
    } catch (_: Exception) {
        null
    }
}
