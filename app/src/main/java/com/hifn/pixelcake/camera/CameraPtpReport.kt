package com.hifn.pixelcake.camera

/**
 * P2 PoC-2 / PoC-3 的握手报告（纯数据 + 纯格式化，便于 JVM 单测与日志回传）。
 *
 * 一次「连接并握手」把下面这些信息一次性带回来，等价于插上相机做了一轮完整的协议体检：
 * 是否拿到授权、能否打开设备、PTP 会话是否建立、相机自报了什么型号、
 * 卡里有多少对象、最后几张叫什么名字——**一旦某步失败，报告里也能看出卡在哪一步**。
 */
data class CameraPtpReport(
    val deviceLabel: String,
    val steps: List<String>,
    val openSessionCode: Int?,
    val deviceInfo: PtpDeviceInfo?,
    val storages: List<PtpStorageInfo>,
    val handleCounts: Map<Int, Int>,
    val handleQueryMode: Map<Int, String>,
    val samples: List<PtpObjectInfo>,
    val elapsedMs: Long,
    val notices: List<String>,
    val failure: String?
) {
    val ok: Boolean
        get() = failure == null && openSessionCode == PtpProtocol.RC_OK

    val rawSampleCount: Int get() = samples.count { it.looksLikeRaw }

    /** 供 UI 逐行展示、也便于用户直接复制到聊天里回传。 */
    fun summaryLines(): List<String> {
        val out = ArrayList<String>()
        out.add(if (ok) "结果：握手成功（PTP 会话已建立）" else "结果：失败 · ${failure ?: "未知原因"}")
        out.add("设备：$deviceLabel")
        out.add("耗时：$elapsedMs ms")
        out.add("步骤：")
        for (step in steps) out.add("  $step")

        openSessionCode?.let { out.add("OpenSession 响应：${PtpProtocol.responseName(it)}") }

        deviceInfo?.let { info ->
            out.add("机型：${info.headline()}")
            out.add(
                "支持操作 ${info.operationsSupported.size} 项 · " +
                    "GetObjectInfo=${yesNo(info.hasOperation(PtpProtocol.OP_GET_OBJECT_INFO))} · " +
                    "GetObjectHandles=${yesNo(info.hasOperation(PtpProtocol.OP_GET_OBJECT_HANDLES))} · " +
                    "GetObject=${yesNo(info.hasOperation(PtpProtocol.OP_GET_OBJECT))}"
            )
            if (info.imageFormats.isNotEmpty()) {
                out.add(
                    "支持图片格式：" + info.imageFormats.take(8)
                        .joinToString(" / ") { format -> PtpProtocol.formatName(format) }
                )
            }
            if (info.vendorExtensionDesc.isNotBlank()) {
                out.add("厂商扩展：${info.vendorExtensionDesc}（id ${PtpProtocol.hex8(info.vendorExtensionId)}）")
            }
        }

        for (storage in storages) {
            out.add("存储 ${PtpProtocol.hex8(storage.storageId)}：${storage.label()}")
        }
        if (handleCounts.isNotEmpty()) {
            out.add(
                "对象总数：" + handleCounts.entries
                    .joinToString(" / ") { entry -> "${PtpProtocol.hex8(entry.key)}=${entry.value}" }
            )
        }
        for (mode in handleQueryMode.entries) {
            out.add("句柄查询 ${PtpProtocol.hex8(mode.key)}：${mode.value}")
        }

        if (samples.isNotEmpty()) {
            val raw = samples.count { it.looksLikeRaw }
            val jpeg = samples.count { it.looksLikeJpeg }
            out.add("采样 ${samples.size} 个（RAW 候选 $raw / JPEG $jpeg）：")
            for (sample in samples) out.add("  · ${sample.label()}")
        }

        for (notice in notices) out.add("提示：$notice")
        return out
    }

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"
}
