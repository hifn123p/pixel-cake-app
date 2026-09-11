package com.hifn.pixelcake.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hifn.pixelcake.diag.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/** 会话打开结果：成功带会话，失败带**可读原因**（真机排查就靠这一句话）。 */
sealed class CameraSessionResult {
    class Ok(val session: CameraSession) : CameraSessionResult()
    class Failed(val reason: String) : CameraSessionResult()
}

/**
 * 一次**长生命周期**的 PTP 会话：`OpenSession` →（枚举/下载若干次）→ `CloseSession`。
 *
 * 为什么要长会话：每次操作都重开一次会话意味着「授权 + 打开设备 + claimInterface + OpenSession」
 * 全部重来一遍，既慢又容易在相机端留下半开状态。相机直连面板会持有一个会话，
 * 用户浏览列表、拉单张、批量拉取都在同一会话里完成，直到点「断开」或页面销毁。
 *
 * 线程约定：所有 USB I/O 都串行化在内部 [Mutex] 上——`PtpTransport` 的 `transactionId`
 * 是可变状态，并发发命令会错位；UI 层即使误并发调用也只会排队，不会坏协议。
 * 所有阻塞操作都在 [Dispatchers.IO] 上执行，主线程只负责刷新 UI。
 */
class CameraSession private constructor(
    private val transport: PtpTransport,
    val deviceLabel: String,
    val deviceInfo: PtpDeviceInfo?,
    val openSessionCode: Int,
    val storageIds: List<Int>,
    val storages: List<PtpStorageInfo>
) : AutoCloseable {

    private val io = Mutex()
    private val handleModes = LinkedHashMap<Int, String>()

    @Volatile
    private var closed = false

    /** 每个存储实际使用的句柄查询方式（回退信息对真机排查很重要）。 */
    val handleQueryMode: Map<Int, String> get() = handleModes.toMap()

    /** 句柄查询结果 + 走的是哪条路。 */
    data class HandleQuery(val handles: List<Int>, val mode: String)

    /** 单张下载结果。 */
    data class DownloadOutcome(val ok: Boolean, val bytes: Long, val message: String)

    /** 存储摘要（报告/UI 共用）。 */
    fun storagesSummary(): String =
        storages.joinToString(" / ") { "${it.label()} · ${PtpProtocol.hex8(it.storageId)}" }

    suspend fun objectHandles(storageId: Int): HandleQuery = io.withLock { queryHandles(storageId) }

    suspend fun objectInfo(handle: Int): PtpObjectInfo? = io.withLock { readObjectInfo(handle) }

    /**
     * 列出相机里的照片（新 → 旧）。
     *
     * 只查**最新的** [maxItems] 个句柄：每次 `GetObjectInfo` 是一条 USB 事务，
     * 上万条对象全量遍历会慢到不可接受，而用户真正要的是「刚拍的那几张」。
     * 被截断这件事会如实写进 [CameraPhotoList.truncated]。
     */
    suspend fun listPhotos(maxItems: Int = DEFAULT_LIST_ITEMS): CameraPhotoList =
        io.withLock { listPhotosUnlocked(maxItems) }

    /** 流式下载一个对象到 [target]（不整段进堆）。失败时删除半成品文件。 */
    suspend fun download(
        handle: Int,
        target: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): DownloadOutcome = io.withLock { downloadUnlocked(handle, target, onProgress) }

    /**
     * 关闭会话：先礼貌地 `CloseSession`，再释放 USB 接口。
     * 需在协程里调用（会做一次 USB 往返）。
     */
    suspend fun closeGracefully() {
        if (closed) return
        io.withLock {
            withContext(Dispatchers.IO) {
                runCatching { transport.execute(PtpProtocol.OP_CLOSE_SESSION) }
            }
        }
        close()
    }

    /**
     * 直接释放底层 USB 连接（不发 `CloseSession`）。
     * 供 `DisposableEffect` 等非协程场景兜底：管道一断，相机侧会话自然结束。
     */
    override fun close() {
        if (closed) return
        closed = true
        transport.close()
        DebugLog.i(DebugLog.TAG_CAMERA, "camera session closed", mapOf("device" to deviceLabel))
    }

    // ---------------- 内部实现（调用方必须已持有 io 锁） ----------------

    private suspend fun queryHandles(storageId: Int): HandleQuery = withContext(Dispatchers.IO) {
        val all = transport.execute(
            PtpProtocol.OP_GET_OBJECT_HANDLES,
            storageId,
            0,
            PtpProtocol.HANDLE_ALL
        )
        val allHandles = if (all.ok) all.data?.let { parseObjectHandles(it) }.orEmpty() else emptyList()

        val query = if (allHandles.isNotEmpty()) {
            HandleQuery(allHandles, "associationHandle=0xFFFFFFFF（全部对象）")
        } else {
            // 空结果有两种可能：卡里确实没东西，或相机不认 0xFFFFFFFF。用根层查询区分开。
            val root = transport.execute(
                PtpProtocol.OP_GET_OBJECT_HANDLES,
                storageId,
                0,
                PtpProtocol.ASSOCIATION_ROOT
            )
            val rootHandles = if (root.ok) root.data?.let { parseObjectHandles(it) }.orEmpty() else emptyList()
            val reason = if (all.ok) "0xFFFFFFFF 返回空，已回退" else "0xFFFFFFFF → ${all.responseName()}"
            HandleQuery(rootHandles, "associationHandle=0（仅根层 / $reason）")
        }
        handleModes[storageId] = query.mode
        DebugLog.i(
            DebugLog.TAG_CAMERA, "object handles",
            mapOf(
                "storage" to PtpProtocol.hex8(storageId),
                "count" to query.handles.size,
                "mode" to query.mode
            )
        )
        query
    }

    private suspend fun readObjectInfo(handle: Int): PtpObjectInfo? = withContext(Dispatchers.IO) {
        transport.execute(PtpProtocol.OP_GET_OBJECT_INFO, handle).data?.let { parseObjectInfo(it) }
    }

    private suspend fun listPhotosUnlocked(maxItems: Int): CameraPhotoList =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val notices = ArrayList<String>()
            val photos = ArrayList<CameraPhoto>()
            var total = 0
            var inspected = 0
            val perStorage = maxOf(MIN_PER_STORAGE_ITEMS, maxItems / storageIds.size.coerceAtLeast(1))

            for (storageId in storageIds) {
                val query = queryHandles(storageId)
                total += query.handles.size
                if (query.handles.isEmpty()) continue

                var failed = 0
                // 句柄通常按时间递增 → 取尾部（最新）后倒序，UI 第 0 个即最新一张
                for (handle in query.handles.takeLast(perStorage).asReversed()) {
                    inspected += 1
                    val info = readObjectInfo(handle)
                    if (info == null) {
                        failed += 1
                        continue
                    }
                    if (CameraPhotoFilter.isPhoto(info)) photos.add(CameraPhoto(handle, info))
                }
                if (failed > 0) {
                    notices.add("存储 ${PtpProtocol.hex8(storageId)} 有 $failed 条对象信息读取失败（已跳过）")
                }
            }

            val elapsed = System.currentTimeMillis() - started
            DebugLog.i(
                DebugLog.TAG_CAMERA, "photo list",
                mapOf(
                    "total" to total, "inspected" to inspected,
                    "photos" to photos.size, "ms" to elapsed
                )
            )
            CameraPhotoList(
                totalHandles = total,
                inspected = inspected,
                photos = photos,
                elapsedMs = elapsed,
                notices = notices
            )
        }

    private suspend fun downloadUnlocked(
        handle: Int,
        target: File,
        onProgress: (Long, Long) -> Unit
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val dir = target.parentFile
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return@withContext DownloadOutcome(false, 0, "无法创建缓存目录：${dir.absolutePath}")
        }

        val started = System.currentTimeMillis()
        val download = try {
            FileOutputStream(target).use { fos ->
                BufferedOutputStream(fos, 64 * 1024).use { out ->
                    transport.downloadObject(handle, out, onProgress)
                }
            }
        } catch (e: Exception) {
            return@withContext DownloadOutcome(
                false, 0,
                "写入缓存失败：${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
            )
        }

        DebugLog.i(
            DebugLog.TAG_CAMERA, "get object",
            mapOf(
                "handle" to handle, "ok" to download.ok, "bytes" to download.bytes,
                "of" to download.totalBytes, "ms" to (System.currentTimeMillis() - started),
                "file" to target.name, "failure" to (download.failure ?: "-")
            )
        )

        if (!download.ok) {
            runCatching { target.delete() }
            DownloadOutcome(
                false, download.bytes,
                download.failure ?: "GetObject 响应 ${download.responseName()}"
            )
        } else {
            DownloadOutcome(true, download.bytes, "已下载 ${download.bytes} 字节")
        }
    }

    companion object {

        /** 默认检查多少个最新句柄（每个一次 `GetObjectInfo`）。 */
        const val DEFAULT_LIST_ITEMS = 40

        private const val MIN_PER_STORAGE_ITEMS = 8

        /**
         * 打开会话：USB 授权 → 打开设备并声明 PTP 接口 → `OpenSession` →
         * `GetDeviceInfo` → `GetStorageIDs` / `GetStorageInfo`。
         *
         * [onStep] 会带上每一步的中文描述（含失败点），既用于 UI，也用于调试报告。
         * 采样/上报由 [CameraConnection] 负责，本函数只保证「会话可用」。
         */
        suspend fun open(
            context: Context,
            device: UsbDevice,
            onStep: (String) -> Unit = {}
        ): CameraSessionResult = withContext(Dispatchers.IO) {
            fun step(message: String) {
                onStep(message)
                DebugLog.i(DebugLog.TAG_CAMERA, message)
            }

            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return@withContext CameraSessionResult.Failed("UsbManager 不可用")

            if (manager.hasPermission(device)) {
                step("USB 授权：已具备（无需弹窗）")
            } else {
                step("USB 授权：已请求，请在系统弹窗点「允许」")
                if (!UsbPermission.request(context, manager, device)) {
                    return@withContext CameraSessionResult.Failed(
                        "USB 授权失败：25s 内未获授权（用户拒绝，或弹窗未出现）"
                    )
                }
                step("USB 授权：已获取")
            }

            val transport = PtpTransport.open(manager, device)
                ?: return@withContext CameraSessionResult.Failed(
                    "打开设备或声明 PTP 接口失败（见 CAMERA 日志的 openDevice / claimInterface 两条）"
                )
            step("已打开设备并声明 PTP 接口")

            val open = transport.execute(PtpProtocol.OP_OPEN_SESSION, PtpProtocol.SESSION_ID_DEFAULT)
            if (!open.ok) {
                transport.close()
                return@withContext CameraSessionResult.Failed("OpenSession 未成功：${open.describe()}")
            }
            step("OpenSession → ${open.responseName()}")

            val deviceInfo = transport.execute(PtpProtocol.OP_GET_DEVICE_INFO).data
                ?.let { parseDeviceInfo(it) }
            if (deviceInfo != null) {
                step("GetDeviceInfo → ${deviceInfo.headline()}")
            } else {
                step("GetDeviceInfo 数据集解析失败（继续，不影响拉图）")
            }

            val storageIds = transport.execute(PtpProtocol.OP_GET_STORAGE_IDS).data
                ?.let { parseStorageIds(it) }
                .orEmpty()
            step("GetStorageIDs → ${storageIds.size} 个存储")

            val storages = ArrayList<PtpStorageInfo>()
            for (storageId in storageIds) {
                // 存储信息拿不到不致命：跳过即可，别让整轮连接失败
                transport.execute(PtpProtocol.OP_GET_STORAGE_INFO, storageId).data
                    ?.let { parseStorageInfo(it) }
                    ?.let { storages.add(it) }
            }
            if (storages.isNotEmpty()) {
                step("GetStorageInfo → ${storages.joinToString(" / ") { it.label() }}")
            }

            CameraSessionResult.Ok(
                CameraSession(
                    transport = transport,
                    deviceLabel = describeDevice(device),
                    deviceInfo = deviceInfo,
                    openSessionCode = open.responseCode,
                    storageIds = storageIds,
                    storages = storages
                )
            )
        }

        /** 设备描述：「厂商 型号 [VID:PID] 节点路径」，用于报告与 `CAMERA` 日志。 */
        fun describeDevice(device: UsbDevice): String =
            "${device.manufacturerName ?: "?"} ${device.productName ?: "?"} " +
                "[${CameraProbe.hex(device.vendorId)}:${CameraProbe.hex(device.productId)}] " +
                device.deviceName.orEmpty()
    }
}
