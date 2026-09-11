package com.hifn.pixelcake.camera

/**
 * PTP（Picture Transfer Protocol, ISO 15740）容器编解码 + 常量表。
 *
 * 为什么自己写：Android 在 **USB 宿主**模式下只把 MTP 设备交给系统 MediaProvider，
 * 并不给 App 任何公开的 MTP API。要枚举/拉取相机文件，要么引入 libmtp（NDK 依赖），
 * 要么自己跑最小 PTP。P2 选后者：纯 Kotlin、零依赖、**字节级可 JVM 单测**。
 *
 * 字节序：PTP over USB **一律 little-endian**。
 *
 * 容器结构（12 字节头 + 负载）：
 * ```
 * length(u32) | type(u16) | code(u16) | transactionId(u32) | payload[]
 * ```
 * - Command(1) ：`code` = OperationCode，payload = 最多 5 个 u32 参数
 * - Data(2)    ：`code` = 触发它的 OperationCode，payload = 数据集（可能很大）
 * - Response(3)：`code` = ResponseCode，payload = 可选 u32 参数
 * - Event(4)   ：相机主动上报（本 PoC 不处理）
 *
 * 一次事务：**Command →（可选 Data）→ Response**。数据阶段是否存在由操作码决定
 * （见 [hasDataPhase]）——不能靠猜，猜错会把 Response 当成 Data 读然后卡死。
 */
object PtpProtocol {

    /** 容器头固定 12 字节。 */
    const val HEADER_SIZE = 12

    const val CONTAINER_COMMAND = 1
    const val CONTAINER_DATA = 2
    const val CONTAINER_RESPONSE = 3
    const val CONTAINER_EVENT = 4

    /** PTP 会话号；单会话客户端固定用 1 即可。 */
    const val SESSION_ID_DEFAULT = 1

    /**
     * 句柄/存储号的「全部」哨兵值 = 0xFFFFFFFF。
     * Kotlin 里 `0xFFFFFFFF` 字面量是 Long（超出 Int 范围），故此处直接写 -1。
     */
    const val HANDLE_ALL = -1

    /** 关联句柄 0x00000000 = 仅根层级对象（作为 0xFFFFFFFF 的回退）。 */
    const val ASSOCIATION_ROOT = 0

    // ---------------- OperationCode ----------------
    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002
    const val OP_CLOSE_SESSION = 0x1003
    const val OP_GET_STORAGE_IDS = 0x1004
    const val OP_GET_STORAGE_INFO = 0x1005
    const val OP_GET_NUM_OBJECTS = 0x1006
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008
    const val OP_GET_OBJECT = 0x1009

    // ---------------- ResponseCode ----------------
    const val RC_OK = 0x2001
    const val RC_GENERAL_ERROR = 0x2002
    const val RC_SESSION_NOT_OPEN = 0x2003
    const val RC_INVALID_TRANSACTION_ID = 0x2004
    const val RC_OPERATION_NOT_SUPPORTED = 0x2005
    const val RC_PARAMETER_NOT_SUPPORTED = 0x2006
    const val RC_INCOMPLETE_TRANSFER = 0x2007
    const val RC_INVALID_STORAGE_ID = 0x2008
    const val RC_INVALID_OBJECT_HANDLE = 0x2009
    const val RC_ACCESS_DENIED = 0x200F
    const val RC_DEVICE_BUSY = 0x2019
    const val RC_INVALID_PARAMETER = 0x201D
    const val RC_SESSION_ALREADY_OPEN = 0x201E
    const val RC_TRANSACTION_CANCELLED = 0x201F

    // ---------------- ObjectFormatCode ----------------
    const val FORMAT_UNDEFINED = 0x3000
    const val FORMAT_ASSOCIATION = 0x3001
    const val FORMAT_EXIF_JPEG = 0x3801
    const val FORMAT_TIFF = 0x380B
    const val FORMAT_DNG = 0x3811
    const val FORMAT_HEIF = 0x3812

    /**
     * Sony ARW 的 MTP 格式码。
     * ⚠️ 0xB101 是社区（libmtp）常见取值，**尚未真机确认**——PoC 日志会打印相机实报的格式码，
     * 以日志为准；文件名后缀始终是更可靠的判据。
     */
    const val FORMAT_ARW = 0xB101

    /** 有 Data 阶段的操作（ResponseCode 为准的操作不在此列，如 OpenSession/CloseSession）。 */
    private val DATA_PHASE_OPS = setOf(
        OP_GET_DEVICE_INFO,
        OP_GET_STORAGE_IDS,
        OP_GET_STORAGE_INFO,
        OP_GET_NUM_OBJECTS,
        OP_GET_OBJECT_HANDLES,
        OP_GET_OBJECT_INFO,
        OP_GET_OBJECT
    )

    fun hasDataPhase(operationCode: Int): Boolean = operationCode in DATA_PHASE_OPS

    /** 解析出的容器头。 */
    data class Header(
        val length: Int,
        val type: Int,
        val code: Int,
        val transactionId: Int
    ) {
        /** 负载长度；长度非法时返回 -1（调用方据此判定「长度未知」并放弃）。 */
        val payloadLength: Int
            get() = when {
                length < HEADER_SIZE -> -1
                else -> length - HEADER_SIZE
            }
    }

    fun containerTypeName(type: Int): String = when (type) {
        CONTAINER_COMMAND -> "Command"
        CONTAINER_DATA -> "Data"
        CONTAINER_RESPONSE -> "Response"
        CONTAINER_EVENT -> "Event"
        else -> "Container($type)"
    }

    /** 组装一个 Command 容器（头 + 参数，参数最多 5 个，本 PoC 最多用 3 个）。 */
    fun encodeCommand(operationCode: Int, params: List<Int>, transactionId: Int): ByteArray {
        val length = HEADER_SIZE + params.size * 4
        val out = ByteArray(length)
        putU32(out, 0, length)
        putU16(out, 4, CONTAINER_COMMAND)
        putU16(out, 6, operationCode)
        putU32(out, 8, transactionId)
        for (i in params.indices) {
            putU32(out, HEADER_SIZE + i * 4, params[i])
        }
        return out
    }

    /**
     * 解析容器头。
     * @return 头非法（长度不足 12、或 length 字段 < 12）时返回 null。
     */
    fun parseHeader(buf: ByteArray, offset: Int = 0): Header? {
        if (buf.size - offset < HEADER_SIZE) return null
        val header = Header(
            length = readU32(buf, offset),
            type = readU16(buf, offset + 4),
            code = readU16(buf, offset + 6),
            transactionId = readU32(buf, offset + 8)
        )
        // length 为负说明高位置 1（≥0x80000000）：多数设备用它表示「长度未知」，不视为非法，
        // 由调用方通过 payloadLength(-1) 决定放弃。
        return if (header.length in 1 until HEADER_SIZE) null else header
    }

    fun isOk(responseCode: Int): Boolean = responseCode == RC_OK

    fun operationName(code: Int): String = when (code) {
        OP_GET_DEVICE_INFO -> "GetDeviceInfo"
        OP_OPEN_SESSION -> "OpenSession"
        OP_CLOSE_SESSION -> "CloseSession"
        OP_GET_STORAGE_IDS -> "GetStorageIDs"
        OP_GET_STORAGE_INFO -> "GetStorageInfo"
        OP_GET_NUM_OBJECTS -> "GetNumObjects"
        OP_GET_OBJECT_HANDLES -> "GetObjectHandles"
        OP_GET_OBJECT_INFO -> "GetObjectInfo"
        OP_GET_OBJECT -> "GetObject"
        else -> hex4(code)
    }

    fun responseName(code: Int): String = when (code) {
        RC_OK -> "OK"
        RC_GENERAL_ERROR -> "GeneralError"
        RC_SESSION_NOT_OPEN -> "SessionNotOpen"
        RC_INVALID_TRANSACTION_ID -> "InvalidTransactionID"
        RC_OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
        RC_PARAMETER_NOT_SUPPORTED -> "ParameterNotSupported"
        RC_INCOMPLETE_TRANSFER -> "IncompleteTransfer"
        RC_INVALID_STORAGE_ID -> "InvalidStorageID"
        RC_INVALID_OBJECT_HANDLE -> "InvalidObjectHandle"
        RC_ACCESS_DENIED -> "AccessDenied"
        RC_DEVICE_BUSY -> "DeviceBusy"
        RC_INVALID_PARAMETER -> "InvalidParameter"
        RC_SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
        RC_TRANSACTION_CANCELLED -> "TransactionCancelled"
        else -> hex4(code)
    }

    fun formatName(code: Int): String = when (code) {
        FORMAT_UNDEFINED -> "Undefined（相机常把 RAW 报成这个）"
        FORMAT_ASSOCIATION -> "目录"
        FORMAT_EXIF_JPEG -> "JPEG"
        FORMAT_TIFF -> "TIFF"
        FORMAT_DNG -> "DNG"
        FORMAT_HEIF -> "HEIF"
        FORMAT_ARW -> "ARW（0xB101，待真机确认）"
        else -> hex4(code)
    }

    // ---------------- little-endian 读写 ----------------

    fun putU16(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    fun putU32(dst: ByteArray, offset: Int, value: Int) {
        for (i in 0..3) {
            dst[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }

    fun readU16(src: ByteArray, offset: Int): Int =
        (src[offset].toInt() and 0xFF) or ((src[offset + 1].toInt() and 0xFF) shl 8)

    fun readU32(src: ByteArray, offset: Int): Int =
        readU16(src, offset) or (readU16(src, offset + 2) shl 16)

    /** `0x054C` 风格（4 位十六进制）。 */
    fun hex4(value: Int): String = "0x%04X".format(value and 0xFFFF)

    /** `0x00010001` 风格（8 位十六进制，接受负值 = 无符号语义）。 */
    fun hex8(value: Int): String = "0x%08X".format(value)
}
