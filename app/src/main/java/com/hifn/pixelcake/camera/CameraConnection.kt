package com.hifn.pixelcake.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P2 PoC-2/3：一次「连接并握手」的完整体检，产出可回传的 [CameraPtpReport]。
 *
 * 实现上是 [CameraSession] 的一层薄封装：会话负责「授权 → 打开 → OpenSession → 设备/存储信息」，
 * 这里补上「枚举每个存储的对象句柄 + 采样末尾若干条 ObjectInfo」，并把每一步收集成报告。
 * USB 授权本身在 [UsbPermission]（广播 + 轮询双保险）。
 *
 * **全程只读**：只发 OpenSession / GetDeviceInfo / GetStorageIDs / GetStorageInfo /
 * GetObjectHandles / GetObjectInfo / CloseSession，不写相机、不删文件。
 * 拉取文件与批处理见 [CameraSession.download] 与 [CameraBatch]。
 */
object CameraConnection {

    /** 每个存储采样多少个对象（取句柄尾部——句柄通常按时间递增，尾部即最新）。 */
    private const val SAMPLE_OBJECTS = 8

    suspend fun connect(context: Context, device: UsbDevice): CameraPtpReport =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val label = CameraSession.describeDevice(device)
            val steps = ArrayList<String>()
            val notices = ArrayList<String>()

            fun log(message: String) {
                steps.add("${steps.size + 1}) $message")
            }

            val opened = CameraSession.open(context, device) { log(it) }
            val session = when (opened) {
                is CameraSessionResult.Failed ->
                    return@withContext failureReport(label, steps, notices, startedAt, opened.reason)
                is CameraSessionResult.Ok -> opened.session
            }

            try {
                val handleCounts = LinkedHashMap<Int, Int>()
                val handleLists = LinkedHashMap<Int, List<Int>>()
                for (storageId in session.storageIds) {
                    val query = session.objectHandles(storageId)
                    handleCounts[storageId] = query.handles.size
                    handleLists[storageId] = query.handles
                    log("存储 ${PtpProtocol.hex8(storageId)}：对象 ${query.handles.size} 个（${query.mode}）")
                }

                val samples = ArrayList<PtpObjectInfo>()
                for (handles in handleLists.values) {
                    if (handles.isEmpty()) continue
                    for (handle in handles.takeLast(SAMPLE_OBJECTS)) {
                        session.objectInfo(handle)?.let { samples.add(it) }
                    }
                }
                log("对象采样完成：${samples.size} 个（照片候选 ${samples.count { CameraPhotoFilter.isPhoto(it) }}）")

                CameraPtpReport(
                    deviceLabel = session.deviceLabel,
                    steps = steps.toList(),
                    openSessionCode = session.openSessionCode,
                    deviceInfo = session.deviceInfo,
                    storages = session.storages,
                    handleCounts = handleCounts.toMap(),
                    handleQueryMode = session.handleQueryMode,
                    samples = samples.toList(),
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    notices = notices.toList(),
                    failure = null
                )
            } finally {
                // 先礼后兵：closeGracefully 可能被取消打断，兜底的 close() 保证 USB 连接一定释放
                runCatching { session.closeGracefully() }
                session.close()
            }
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
}
