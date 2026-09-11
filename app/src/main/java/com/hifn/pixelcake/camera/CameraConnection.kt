package com.hifn.pixelcake.camera

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hifn.pixelcake.diag.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * P2 PoC-2 + PoC-3：USB 授权 → 打开设备 → PTP 会话握手 → 枚举存储与对象。
 *
 * 全过程**只读**：只发 OpenSession / GetDeviceInfo / GetStorageIDs / GetStorageInfo /
 * GetObjectHandles / GetObjectInfo / CloseSession，不写相机、不删文件、不占相机 UI。
 *
 * 设计取舍：
 * - 授权用「广播 + 轮询」双保险。授权广播在不同 ROM 上的投递行为并不完全一致，
 *   只靠广播，一次没送到就会让整个 PoC 假失败；只靠轮询，用户点了允许还要多等一拍。
 *   两条路并行，谁先到谁算数。
 * - 所有 USB I/O 都在 [Dispatchers.IO] 上跑；主线程只负责弹授权与刷新 UI。
 * - 失败即返回带原因的报告（含步骤轨迹），不做重试、不吞异常。
 */
object CameraConnection {

    /** 我们自己定义的授权广播 action（PendingIntent 的基准 action）。 */
    const val ACTION_USB_PERMISSION = "com.hifn.pixelcake.action.USB_PERMISSION"

    /**
     * 系统 UsbService 发广播时可能用的 action。
     * 该字符串没有在 `UsbManager` 上公开为常量，故此处以字面量声明，和自定义 action 一起注册，
     * 覆盖两种实现差异。
     */
    private const val SYSTEM_PERMISSION_ACTION = "android.hardware.usb.action.USB_PERMISSION"

    private const val PERMISSION_TIMEOUT_MS = 25_000L
    private const val PERMISSION_POLL_MS = 300L

    /** 每个存储采样多少个对象（取句柄列表尾部——MTP 句柄通常按时间递增，尾部即最新）。 */
    private const val SAMPLE_OBJECTS = 8

    suspend fun connect(context: Context, device: UsbDevice): CameraPtpReport =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val label = labelOf(device)
            val steps = ArrayList<String>()
            val notices = ArrayList<String>()

            fun log(message: String) {
                steps.add("${steps.size + 1}) $message")
                DebugLog.i(DebugLog.TAG_CAMERA, message)
            }

            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (manager == null) {
                return@withContext failureReport(label, steps, notices, startedAt, "UsbManager 不可用")
            }

            if (manager.hasPermission(device)) {
                log("USB 授权：已具备（无需弹窗）")
            } else {
                log("USB 授权：已请求，请在系统弹窗点「允许」")
                manager.requestPermission(device, permissionIntent(context))
                if (!awaitPermission(context, manager, device)) {
                    return@withContext failureReport(
                        label, steps, notices, startedAt,
                        "USB 授权失败：${PERMISSION_TIMEOUT_MS / 1000}s 内未获授权（用户拒绝，或弹窗未出现）"
                    )
                }
                log("USB 授权：已获取")
            }

            val transport = PtpTransport.open(manager, device)
                ?: return@withContext failureReport(
                    label, steps, notices, startedAt,
                    "打开设备或声明 PTP 接口失败（见 CAMERA 日志的 openDevice / claimInterface 两条）"
                )
            log("已打开设备并声明 PTP 接口")

            try {
                val open = transport.execute(PtpProtocol.OP_OPEN_SESSION, PtpProtocol.SESSION_ID_DEFAULT)
                if (!open.ok) {
                    notices.add(open.describe())
                    return@withContext failureReport(
                        label, steps, notices, startedAt,
                        "OpenSession 未成功：${open.describe()}"
                    )
                }
                log("OpenSession → ${open.responseName()}")

                val infoTxn = transport.execute(PtpProtocol.OP_GET_DEVICE_INFO)
                val deviceInfo = infoTxn.data?.let { parseDeviceInfo(it) }
                if (deviceInfo == null) {
                    notices.add(infoTxn.describe())
                    notices.add("GetDeviceInfo 数据集解析失败（${infoTxn.data?.size ?: 0} 字节）")
                } else {
                    log("GetDeviceInfo → ${deviceInfo.headline()}")
                }

                val idsTxn = transport.execute(PtpProtocol.OP_GET_STORAGE_IDS)
                val storageIds = idsTxn.data?.let { parseStorageIds(it) }.orEmpty()
                log(
                    "GetStorageIDs → ${storageIds.size} 个存储" +
                        storageIds.joinToString(prefix = "（", postfix = "）", separator = " ") { PtpProtocol.hex8(it) }
                )

                val storages = ArrayList<PtpStorageInfo>()
                val handleCounts = LinkedHashMap<Int, Int>()
                val handleModes = LinkedHashMap<Int, String>()
                val samples = ArrayList<PtpObjectInfo>()

                for (storageId in storageIds) {
                    transport.execute(PtpProtocol.OP_GET_STORAGE_INFO, storageId).data
                        ?.let { parseStorageInfo(it) }
                        ?.let { storages.add(it) }

                    val (handles, mode) = fetchHandles(transport, storageId)
                    handleCounts[storageId] = handles.size
                    handleModes[storageId] = mode
                    log("存储 ${PtpProtocol.hex8(storageId)}：对象 ${handles.size} 个（$mode）")

                    if (handles.isEmpty()) continue
                    for (handle in handles.takeLast(SAMPLE_OBJECTS)) {
                        transport.execute(PtpProtocol.OP_GET_OBJECT_INFO, handle).data
                            ?.let { parseObjectInfo(it) }
                            ?.let { samples.add(it) }
                    }
                }
                log("对象采样完成：${samples.size} 个（RAW 候选 ${samples.count { it.looksLikeRaw }}）")

                val close = transport.execute(PtpProtocol.OP_CLOSE_SESSION)
                log("CloseSession → ${close.responseName()}")

                CameraPtpReport(
                    deviceLabel = label,
                    steps = steps.toList(),
                    openSessionCode = open.responseCode,
                    deviceInfo = deviceInfo,
                    storages = storages.toList(),
                    handleCounts = handleCounts.toMap(),
                    handleQueryMode = handleModes.toMap(),
                    samples = samples.toList(),
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    notices = notices.toList(),
                    failure = null
                )
            } finally {
                transport.close()
            }
        }

    /**
     * 取某个存储的对象句柄。
     *
     * 先用 `associationHandle = 0xFFFFFFFF`（全部对象）；若拿不到，再退到 `0`（仅根层）+ 目录对象。
     * 两条路都记进报告的「句柄查询」里——回退本身也是重要的真机情报。
     */
    private fun fetchHandles(transport: PtpTransport, storageId: Int): Pair<List<Int>, String> {
        val all = transport.execute(
            PtpProtocol.OP_GET_OBJECT_HANDLES,
            storageId,
            0,
            PtpProtocol.HANDLE_ALL
        )
        val allHandles = if (all.ok) all.data?.let { parseObjectHandles(it) }.orEmpty() else emptyList()
        if (allHandles.isNotEmpty()) {
            return allHandles to "associationHandle=0xFFFFFFFF（全部对象）"
        }

        // 空结果有两种可能：卡里确实没东西，或相机不认 0xFFFFFFFF。用根层查询区分开。
        val root = transport.execute(
            PtpProtocol.OP_GET_OBJECT_HANDLES,
            storageId,
            0,
            PtpProtocol.ASSOCIATION_ROOT
        )
        val rootHandles = if (root.ok) root.data?.let { parseObjectHandles(it) }.orEmpty() else emptyList()
        val reason = if (all.ok) "0xFFFFFFFF 返回空，已回退" else "0xFFFFFFFF → ${all.responseName()}"
        return rootHandles to "associationHandle=0（仅根层 / $reason）"
    }

    /**
     * 等待 USB 授权：广播 + 轮询双保险，谁先到算谁。
     * @return 超时或拒绝时返回 false。
     */
    private suspend fun awaitPermission(
        context: Context,
        manager: UsbManager,
        device: UsbDevice
    ): Boolean {
        val granted = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action != ACTION_USB_PERMISSION && action != SYSTEM_PERMISSION_ACTION) return
                val fromIntent: UsbDevice? =
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (fromIntent == null || fromIntent.deviceName != device.deviceName) return
                granted.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(SYSTEM_PERMISSION_ACTION)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        return try {
            withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
                coroutineScope {
                    val poll = launch {
                        while (isActive) {
                            if (manager.hasPermission(device)) {
                                granted.complete(true)
                                return@launch
                            }
                            delay(PERMISSION_POLL_MS)
                        }
                    }
                    val result = granted.await()
                    poll.cancel()
                    result
                }
            } ?: false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * 授权用的 PendingIntent。
     *
     * 必须 `FLAG_MUTABLE`：系统要往这个 Intent 里填 `EXTRA_DEVICE` 与 `EXTRA_PERMISSION_GRANTED`，
     * 不可变 PendingIntent 会导致接收方拿不到「是否授权成功」。
     */
    @SuppressLint("MutableImplicitPendingIntent")
    private fun permissionIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
    }

    private fun failureReport(
        label: String,
        steps: List<String>,
        notices: List<String>,
        startedAt: Long,
        reason: String
    ): CameraPtpReport = CameraPtpReport(
        deviceLabel = label,
        steps = steps.toList(),
        openSessionCode = null,
        deviceInfo = null,
        storages = emptyList(),
        handleCounts = emptyMap(),
        handleQueryMode = emptyMap(),
        samples = emptyList(),
        elapsedMs = System.currentTimeMillis() - startedAt,
        notices = notices.toList(),
        failure = reason
    )

    private fun labelOf(device: UsbDevice): String =
        "${device.manufacturerName ?: "?"} ${device.productName ?: "?"} " +
            "[${CameraProbe.hex(device.vendorId)}:${CameraProbe.hex(device.productId)}] " +
            device.deviceName.orEmpty()
}
