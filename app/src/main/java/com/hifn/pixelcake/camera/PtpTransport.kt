package com.hifn.pixelcake.camera

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.hifn.pixelcake.diag.DebugLog
import java.io.OutputStream

/**
 * PTP over USB 的最小传输层：把「命令 →（可选数据）→ 响应」三阶段封装成一次 [execute]。
 *
 * 这一层是 P2 里唯一必须真机验证的部分（USB 端点/Bulk 传输无法在 CI 覆盖），
 * 因此设计原则是：**任何失败都返回带原因的结果，绝不抛异常、绝不无限等待**，
 * 并把每一步都写进 `CAMERA` 日志——真机上出了问题，日志就是唯一线索。
 */
class PtpTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) {

    private var transactionId = 0

    /**
     * **传输与关闭必须共用同一把锁**（`onDispose` 不能用协程 Mutex）。
     *
     * ⚠️ 以前 `close()` 直接 `connection.close()`，与在途的 `bulkTransfer` **没有任何同步**：
     * 面板离开组合（Home 滚出就会触发 `onDispose`）时，批量下载协程可能正停在
     * `bulkTransfer(...)` 里 —— 而**取消协程不会中断阻塞中的 bulkTransfer**。
     * 于是内核传输继续用一个已释放的 fd（下一台相机的 openDevice 很可能拿到同一个 fd 号）
     * ⇒ native 崩溃，或更糟：把数据传到别的设备。Java 层的 try/catch 兜不住这个。
     */
    private val ioLock = Any()

    @Volatile
    private var closed = false

    /**
     * 数据流是否已**失步**（stream desync）。
     *
     * PTP 没有事务层恢复机制：在数据阶段中途失败（超时/断线）时，IN 端点上还留着未读的
     * 负载字节。若继续用同一个 transport 发下一条事务，下一次读到的「容器头」其实是上一次的
     * 残留 —— `length` 随机，可能恰好凑成一个合法 Data 头，把垃圾字节当响应，
     * 甚至把上一张的残片写进下一张的文件里并**报告成功**。
     * 唯一正确的处置是「失步即作废会话」。
     */
    @Volatile
    private var desynced = false

    /** 上一次传输是否已失步（true ⇒ 这个会话必须作废并重新连接）。 */
    val isDesynced: Boolean get() = desynced

    private fun bulkOut(buf: ByteArray, timeoutMs: Int): Int = synchronized(ioLock) {
        if (closed) -1 else runCatching {
            connection.bulkTransfer(endpointOut, buf, 0, buf.size, timeoutMs)
        }.getOrDefault(-1)
    }

    private fun bulkIn(buf: ByteArray, offset: Int, len: Int, timeoutMs: Int): Int = synchronized(ioLock) {
        if (closed) -1 else runCatching {
            connection.bulkTransfer(endpointIn, buf, offset, len, timeoutMs)
        }.getOrDefault(-1)
    }

    /** 一次事务的结果。 */
    class Transaction(
        val operationCode: Int,
        val transactionId: Int,
        val responseCode: Int,
        val data: ByteArray?,
        val responseHeader: PtpProtocol.Header?,
        val failure: String?,
        val elapsedMs: Long
    ) {
        val ok: Boolean
            get() = failure == null && responseCode == PtpProtocol.RC_OK

        fun responseName(): String = PtpProtocol.responseName(responseCode)

        /** 人类可读的一行（报告与日志共用）。 */
        fun describe(): String {
            if (failure != null) {
                return "${PtpProtocol.operationName(operationCode)} → 传输失败：$failure"
            }
            val dataNote = data?.let { "，data=${it.size}B" } ?: "，无数据"
            return "${PtpProtocol.operationName(operationCode)} → ${responseName()}$dataNote，${elapsedMs}ms"
        }

        companion object {
            fun failure(
                operationCode: Int,
                transactionId: Int,
                message: String,
                elapsedMs: Long
            ): Transaction = Transaction(
                operationCode = operationCode,
                transactionId = transactionId,
                responseCode = -1,
                data = null,
                responseHeader = null,
                failure = message,
                elapsedMs = elapsedMs
            )
        }
    }

    /**
     * 执行一次 PTP 事务。
     * @param params 命令参数（PTP 最多 5 个；本 PoC 用到 3 个）
     */
    fun execute(operationCode: Int, vararg params: Int): Transaction {
        // 会话已作废：继续发命令只会把垃圾当成响应。
        if (closed) {
            return Transaction.failure(operationCode, 0, "会话已关闭", 0)
        }
        if (desynced) {
            return Transaction.failure(
                operationCode, 0, "会话已失步（上次传输中断），请重新连接相机", 0
            )
        }
        val started = System.currentTimeMillis()
        val txId = ++transactionId
        val command = PtpProtocol.encodeCommand(operationCode, params.toList(), txId)

        val sent = bulkOut(command, BULK_TIMEOUT_MS)
        if (sent != command.size) {
            return Transaction.failure(operationCode, txId, "命令发送失败（$sent/${command.size} 字节）", System.currentTimeMillis() - started)
        }

        var data: ByteArray? = null
        var responseHeader: PtpProtocol.Header? = null
        var responseCode = -1

        if (PtpProtocol.hasDataPhase(operationCode)) {
            val container = readContainer(operationCode, txId)
                ?: return Transaction.failure(operationCode, txId, "数据阶段读取失败（见上一条日志）", System.currentTimeMillis() - started)
            val (header, payload) = container
            if (header.type == PtpProtocol.CONTAINER_RESPONSE) {
                // 设备在有数据阶段的操作上直接回了错误响应（常见：OperationNotSupported）
                DebugLog.d(
                    DebugLog.TAG_CAMERA, "ptp data-phase returned response",
                    mapOf("op" to PtpProtocol.operationName(operationCode), "response" to PtpProtocol.responseName(header.code))
                )
                logTransaction(operationCode, txId, header.code, null, started)
                return Transaction(operationCode, txId, header.code, null, header, null, System.currentTimeMillis() - started)
            }
            if (header.type != PtpProtocol.CONTAINER_DATA) {
                return Transaction.failure(
                    operationCode, txId,
                    "数据阶段容器类型异常：${PtpProtocol.containerTypeName(header.type)}", System.currentTimeMillis() - started
                )
            }
            data = payload
        }

        val response = readContainer(operationCode, txId)
            ?: return Transaction.failure(operationCode, txId, "响应阶段读取失败（见上一条日志）", System.currentTimeMillis() - started)
        if (response.first.type != PtpProtocol.CONTAINER_RESPONSE) {
            return Transaction.failure(
                operationCode, txId,
                "期望 Response，实际 ${PtpProtocol.containerTypeName(response.first.type)}", System.currentTimeMillis() - started
            )
        }
        responseHeader = response.first
        responseCode = response.first.code
        // ⚠️ 必须校验事务号：`Header.transactionId` 被解析出来却从未与 txId 比对过。
        // 上一次被放弃的事务（超时/取消后调用方直接返回）留下的响应，会被下一次事务
        // 当成自己的合法响应接受 ⇒ `ok == true` 但数据是上一条命令的。
        // 这类「静默错位」在真机上极难定位，而代价只是一行判断。
        if (response.first.transactionId != txId) {
            desynced = true
            return Transaction.failure(
                operationCode, txId,
                "响应事务号不匹配（期望 $txId，实际 ${response.first.transactionId}）——会话已失步",
                System.currentTimeMillis() - started
            )
        }
        logTransaction(operationCode, txId, responseCode, data, started)
        return Transaction(operationCode, txId, responseCode, data, responseHeader, null, System.currentTimeMillis() - started)
    }

    /** `GetObject` 的流式下载结果。 */
    class Download(
        val ok: Boolean,
        val bytes: Long,
        val totalBytes: Long,
        val responseCode: Int,
        val failure: String?,
        val elapsedMs: Long
    ) {
        fun responseName(): String = PtpProtocol.responseName(responseCode)
    }

    /**
     * 流式下载一个对象（`GetObject`）：数据分块从 Bulk IN 读出后**直接写入** [sink]。
     *
     * 绝不能整段读进 `ByteArray`——单张 ARW 有 35–57MB，批量场景下会立刻把堆打满。
     *
     * 数据阶段的容器长度必须已知（相机已在 `ObjectInfo` 报过对象大小，USB 上总能给出）；
     * 若长度未知（0xFFFFFFFF）则直接失败——不做不可靠的猜测，让报告说清楚。
     *
     * 中断处理：写了一半就失败时返回 `ok = false`，调用方负责删除半成品文件。
     * 注意**不支持中途取消**：半途停止读取会让数据流与响应错位，该会话必须废弃。
     * 批量任务因此把「取消」放在**文件边界**上判断（见 `CameraBatch`）。
     */
    fun downloadObject(
        handle: Int,
        sink: OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Download {
        val started = System.currentTimeMillis()
        val txId = ++transactionId
        val command = PtpProtocol.encodeCommand(PtpProtocol.OP_GET_OBJECT, listOf(handle), txId)

        val sent = runCatching {
            connection.bulkTransfer(endpointOut, command, 0, command.size, BULK_TIMEOUT_MS)
        }.getOrDefault(-1)
        if (sent != command.size) {
            return Download(
                false, 0L, 0L, -1,
                "命令发送失败（$sent/${command.size} 字节）",
                System.currentTimeMillis() - started
            )
        }

        val headerBuf = ByteArray(PtpProtocol.HEADER_SIZE)
        if (!readFully(headerBuf, 0, headerBuf.size, BULK_TIMEOUT_MS)) {
            return Download(false, 0L, 0L, -1, "读取数据头失败", System.currentTimeMillis() - started)
        }
        val header = PtpProtocol.parseHeader(headerBuf)
            ?: return Download(false, 0L, 0L, -1, "数据头非法", System.currentTimeMillis() - started)

        // 设备可能不发 Data、直接回错误响应（例如对象已被相机端删除）
        if (header.type == PtpProtocol.CONTAINER_RESPONSE) {
            DebugLog.d(
                DebugLog.TAG_CAMERA, "ptp get object returned response",
                mapOf("handle" to handle, "response" to PtpProtocol.responseName(header.code))
            )
            return Download(
                false, 0L, 0L, header.code,
                "GetObject 直接返回响应：${PtpProtocol.responseName(header.code)}",
                System.currentTimeMillis() - started
            )
        }
        if (header.type != PtpProtocol.CONTAINER_DATA) {
            return Download(
                false, 0L, 0L, -1,
                "期望 Data，实际 ${PtpProtocol.containerTypeName(header.type)}",
                System.currentTimeMillis() - started
            )
        }

        val total = header.payloadLength.toLong()
        if (total <= 0L) {
            return Download(
                false, 0L, 0L, -1,
                "对象长度未知或为空（length=${PtpProtocol.hex8(header.length)}）",
                System.currentTimeMillis() - started
            )
        }

        // 256KiB 分块：足够摊薄每条 USB 事务的开销，又不会占用可观内存
        val chunk = ByteArray(256 * 1024)
        var done = 0L
        var emptyReads = 0
        while (done < total) {
            val want = minOf(chunk.size.toLong(), total - done).toInt()
            val read = bulkIn(chunk, 0, want, DATA_TIMEOUT_MS)
            if (read < 0) {
                // ⚠️ 数据阶段中途失败 = **会话失步**：IN 端点上还留着未读的负载字节。
                // 不置位的话，下一次事务会把这些残留当成容器头解析（长度随机，可能恰好
                // 凑成合法头），甚至把上一张的残片写进下一张的文件并报告成功。
                desynced = true
                return Download(
                    false, done, total, -1,
                    "下载中断：$done/$total 字节（Bulk 读超时或设备已断开）——会话已失步，请重新连接",
                    System.currentTimeMillis() - started
                )
            }
            if (read == 0) {
                // ZLP：事务边界上的零长度包**不是**错误，与 `readFully` 同一容错策略（返回 <0 才判中断）。
                // 下载的读次数远多于 `readFully`（35–57MB / 256KiB ≈ 200+ 次），故在成功续读后清零计数，
                // 避免零星空包累积触顶而误判中断；只有**连续**超过 MAX_EMPTY_READS 次才放弃。
                emptyReads += 1
                if (emptyReads > MAX_EMPTY_READS) {
                    desynced = true
                    return Download(
                        false, done, total, -1,
                        "下载中断：$done/$total 字节（连续 $emptyReads 次空包，设备可能已断开）——会话已失步，请重新连接",
                        System.currentTimeMillis() - started
                    )
                }
                continue
            }
            emptyReads = 0
            // ⚠️ 本层的契约是「任何失败都返回带原因的结果，绝不抛异常」：`sink.write` 在
            // 缓存目录满 / /data 满时会抛 IOException，穿出去会让上层在**失步之后**才兜住。
            if (!writeChunk(sink, chunk, read)) {
                desynced = true
                return Download(
                    false, done, total, -1,
                    "写入缓存失败（存储空间不足？）", System.currentTimeMillis() - started
                )
            }
            done += read
            onProgress(done, total)
        }
        if (!runCatching { sink.flush() }.isSuccess) {
            desynced = true
            return Download(false, done, total, -1, "写入缓存失败（flush）", System.currentTimeMillis() - started)
        }

        val response = readContainer(PtpProtocol.OP_GET_OBJECT, txId)
            ?: return Download(false, done, total, -1, "响应阶段读取失败", System.currentTimeMillis() - started)
        val responseCode = response.first.code
        logTransaction(PtpProtocol.OP_GET_OBJECT, txId, responseCode, null, started)
        if (responseCode != PtpProtocol.RC_OK) {
            return Download(
                false, done, total, responseCode,
                "GetObject 响应 ${PtpProtocol.responseName(responseCode)}",
                System.currentTimeMillis() - started
            )
        }
        return Download(true, done, total, responseCode, null, System.currentTimeMillis() - started)
    }

    /** 读一个完整容器（头 + 负载）。 */
    private fun readContainer(operationCode: Int, txId: Int): Pair<PtpProtocol.Header, ByteArray>? {
        val headerBuf = ByteArray(PtpProtocol.HEADER_SIZE)
        if (!readFully(headerBuf, 0, headerBuf.size, BULK_TIMEOUT_MS)) {
            DebugLog.w(
                DebugLog.TAG_CAMERA, "ptp read header failed",
                mapOf("op" to PtpProtocol.operationName(operationCode), "txId" to txId)
            )
            return null
        }
        val header = PtpProtocol.parseHeader(headerBuf)
        if (header == null) {
            DebugLog.w(
                DebugLog.TAG_CAMERA, "ptp header illegal",
                mapOf("op" to PtpProtocol.operationName(operationCode), "raw" to hexOf(headerBuf))
            )
            return null
        }
        val payloadLength = header.payloadLength
        if (payloadLength < 0) {
            DebugLog.w(
                DebugLog.TAG_CAMERA, "ptp payload length unknown",
                mapOf("op" to PtpProtocol.operationName(operationCode), "length" to PtpProtocol.hex8(header.length))
            )
            return null
        }
        if (payloadLength == 0) return header to ByteArray(0)
        if (payloadLength > MAX_INLINE_BYTES) {
            // 本阶段只做握手/枚举，数据都很小；>1MiB 说明走错了操作（真正的对象流式下载留待 PoC-4）
            DebugLog.w(
                DebugLog.TAG_CAMERA, "ptp payload too large",
                mapOf("op" to PtpProtocol.operationName(operationCode), "bytes" to payloadLength)
            )
            return null
        }
        val payload = ByteArray(payloadLength)
        if (!readFully(payload, 0, payloadLength, DATA_TIMEOUT_MS)) {
            DebugLog.w(
                DebugLog.TAG_CAMERA, "ptp read payload failed",
                mapOf("op" to PtpProtocol.operationName(operationCode), "bytes" to payloadLength)
            )
            return null
        }
        return header to payload
    }

    /**
     * 读满 [length] 字节。
     *
     * 注意 ZLP：设备在事务边界可能发零长度包，`bulkTransfer` 会返回 0。
     * 这**不是**错误，只是「这次没有数据」，所以允许重试若干次；返回负值才是超时/出错。
     */
    private fun readFully(dst: ByteArray, offset: Int, length: Int, timeoutMs: Int): Boolean {
        var read = 0
        var emptyReads = 0
        while (read < length) {
            val n = bulkIn(dst, offset + read, length - read, timeoutMs)
            if (n < 0) return false
            if (n == 0) {
                emptyReads += 1
                if (emptyReads > MAX_EMPTY_READS) return false
                continue
            }
            // ⚠️ 必须与 `downloadObject` 同策略清零：`bulkTransfer` 超时时返回**已传输的部分
            // 字节数**，所以读大负载时这个循环会转很多次，每次循环里都可能夹一个 ZLP。
            // 以前只在 downloadObject 里清零，这里不清 ⇒ 一次**健康**的长传输会被
            // 零星空包累积顶到 MAX_EMPTY_READS 而误判中断。
            emptyReads = 0
            read += n
        }
        return true
    }

    /** 写一块数据到 [sink]，失败返回 false（本层契约：绝不抛异常）。 */
    private fun writeChunk(sink: OutputStream, buf: ByteArray, len: Int): Boolean =
        runCatching { sink.write(buf, 0, len) }.isSuccess

    private fun logTransaction(
        operationCode: Int,
        txId: Int,
        responseCode: Int,
        data: ByteArray?,
        startedAt: Long
    ) {
        DebugLog.d(
            DebugLog.TAG_CAMERA, "ptp txn",
            mapOf(
                "op" to PtpProtocol.operationName(operationCode),
                "txId" to txId,
                "resp" to PtpProtocol.responseName(responseCode),
                "data" to (data?.size ?: 0),
                "ms" to (System.currentTimeMillis() - startedAt)
            )
        )
    }

    fun close() = synchronized(ioLock) {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
        DebugLog.i(DebugLog.TAG_CAMERA, "ptp transport closed")
    }

    private fun hexOf(bytes: ByteArray, max: Int = 16): String =
        bytes.take(max).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    companion object {

        private const val BULK_TIMEOUT_MS = 5_000
        private const val DATA_TIMEOUT_MS = 10_000
        private const val MAX_EMPTY_READS = 3
        private const val MAX_INLINE_BYTES = 1 shl 20

        /**
         * 打开设备并声明 PTP 接口。
         * @return 失败（openDevice 返回 null / 找不到批量端点 / 接口被占用）时返回 null，原因写入日志。
         */
        fun open(manager: UsbManager, device: UsbDevice): PtpTransport? {
            // 先把接口全貌写进日志：真机上 A7C2 到底暴露了什么，全靠这条
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                DebugLog.i(
                    DebugLog.TAG_CAMERA, "usb interface",
                    mapOf(
                        "id" to itf.id,
                        "class" to itf.interfaceClass,
                        "sub" to itf.interfaceSubclass,
                        "proto" to itf.interfaceProtocol,
                        "eps" to itf.endpointCount
                    )
                )
            }

            val usbInterface = pickPtpInterface(device)
            if (usbInterface == null) {
                DebugLog.e(
                    DebugLog.TAG_CAMERA, "no ptp interface",
                    mapOf("ifs" to device.interfaceCount)
                )
                return null
            }

            var candidateIn: UsbEndpoint? = null
            var candidateOut: UsbEndpoint? = null
            for (i in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN && candidateIn == null) candidateIn = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && candidateOut == null) candidateOut = ep
            }
            if (candidateIn == null && candidateOut == null) {
                DebugLog.e(
                    DebugLog.TAG_CAMERA, "no bulk endpoints at all",
                    mapOf("ifId" to usbInterface.id, "eps" to usbInterface.endpointCount)
                )
            }
            // 用 elvis 取成非空 val：不依赖对可变局部量的智能转换，语义也更直白
            val endpointIn: UsbEndpoint = candidateIn ?: run {
                DebugLog.e(DebugLog.TAG_CAMERA, "no bulk IN endpoint", mapOf("ifId" to usbInterface.id))
                return null
            }
            val endpointOut: UsbEndpoint = candidateOut ?: run {
                DebugLog.e(DebugLog.TAG_CAMERA, "no bulk OUT endpoint", mapOf("ifId" to usbInterface.id))
                return null
            }

            val connection = manager.openDevice(device)
            if (connection == null) {
                DebugLog.e(DebugLog.TAG_CAMERA, "openDevice returned null")
                return null
            }

            // force=true：允许内核驱动让位（Android 上多为 no-op，但能覆盖部分 ROM）
            val claimed = runCatching { connection.claimInterface(usbInterface, true) }.getOrDefault(false)
            DebugLog.i(
                DebugLog.TAG_CAMERA, "ptp claim interface",
                mapOf(
                    "id" to usbInterface.id,
                    "class" to usbInterface.interfaceClass,
                    "ok" to claimed,
                    "epIn" to endpointIn.address,
                    "epOut" to endpointOut.address,
                    "mtu" to endpointIn.maxPacketSize
                )
            )
            if (!claimed) {
                // 真机上最可能踩到：系统的 MTP 服务（MediaProvider）已占用该接口
                DebugLog.e(
                    DebugLog.TAG_CAMERA, "claim interface failed（可能被系统 MTP 服务占用）",
                    mapOf("id" to usbInterface.id)
                )
                runCatching { connection.close() }
                return null
            }

            return PtpTransport(connection, usbInterface, endpointIn, endpointOut)
        }

        /** 选 PTP/MTP 接口：优先「静像设备」类（6），退而求其次厂商特定类（0xFF）。 */
        private fun pickPtpInterface(device: UsbDevice): UsbInterface? {
            val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
            return interfaces.firstOrNull { it.interfaceClass == CameraProbe.USB_CLASS_STILL_IMAGE }
                ?: interfaces.firstOrNull { it.interfaceClass == CameraProbe.USB_CLASS_VENDOR_SPEC }
        }
    }
}
