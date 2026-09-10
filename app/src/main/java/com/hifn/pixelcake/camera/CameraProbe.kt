package com.hifn.pixelcake.camera

/**
 * USB 设备摘要（与 `android.hardware.usb.UsbDevice` 解耦，便于 JVM 单测与日志序列化）。
 * 仅承载 P2 直连 PoC 需要的字段：VID/PID、路径、厂商/产品名、接口类列表。
 */
data class UsbDeviceSummary(
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val manufacturer: String?,
    val product: String?,
    val interfaceClasses: List<Int>
)

/**
 * A7C2 直连（P2）USB 设备识别（纯逻辑、零 Android 依赖，可 JVM 单测）。
 *
 * PoC-1 只做「识别」：把 VID/PID 与接口类翻译成人类可读的相机 USB 模式提示，
 * 判定是否为 Sony 机身。真正打开设备 / PTP 会话见后续 PoC 阶段（`docs/P2_DESIGN.md`）。
 */
object CameraProbe {

    /** Sony 的 USB Vendor ID（ILCE / Alpha 系列共用）。 */
    const val SONY_VENDOR_ID = 0x054C

    const val USB_CLASS_STILL_IMAGE = 6
    const val USB_CLASS_MASS_STORAGE = 8
    const val USB_CLASS_VENDOR_SPEC = 0xFF

    fun isSonyCamera(d: UsbDeviceSummary): Boolean = d.vendorId == SONY_VENDOR_ID

    /** 依据接口类推断相机当前 USB 模式（决定后续能否直接枚举/拉图）。 */
    fun modeHint(d: UsbDeviceSummary): String = when {
        d.interfaceClasses.contains(USB_CLASS_MASS_STORAGE) -> "Mass Storage（可当 U 盘直接读文件）"
        d.interfaceClasses.contains(USB_CLASS_STILL_IMAGE) -> "PTP/MTP（静像设备，可枚举/拉图）"
        d.interfaceClasses.contains(USB_CLASS_VENDOR_SPEC) -> "厂商特定（可能是 PC Remote）"
        else -> "未知接口"
    }

    fun hex(id: Int): String = "0x%04X".format(id)

    fun describe(d: UsbDeviceSummary): String =
        "${d.manufacturer ?: "?"} ${d.product ?: "?"} " +
            "[${hex(d.vendorId)}:${hex(d.productId)}] · ${modeHint(d)}"
}
