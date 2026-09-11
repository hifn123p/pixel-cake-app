package com.hifn.pixelcake.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * P2 PoC-1：枚举当前挂载的 USB 设备。
 *
 * 只读 [UsbManager.getDeviceList]——**不需要任何权限**（USB 权限仅在 `openDevice()` 时才需要）。
 * 因此本步零副作用、零授权弹窗，可安全先在真机上验证「A7C2 是否以 USB 设备出现、处于何种模式」。
 */
object UsbCameraScanner {

    /** 返回当前挂载的 USB 设备摘要（按 VID/PID 排序，便于对照）。 */
    fun scan(context: Context): List<UsbDeviceSummary> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return manager.deviceList.values
            .map { it.toSummary() }
            .sortedWith(compareBy({ it.vendorId }, { it.productId }))
    }

    /**
     * 按 `deviceName` 找回真正的 [UsbDevice]。
     *
     * 枚举给 UI 的是与 Android 解耦的 [UsbDeviceSummary]（便于单测），
     * 但 PTP 连接需要能交给 `openDevice()` 的设备对象，故按名字回查一次。
     */
    fun find(context: Context, deviceName: String): UsbDevice? {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return manager.deviceList[deviceName]
    }

    private fun UsbDevice.toSummary(): UsbDeviceSummary {
        val classes = (0 until interfaceCount).map { getInterface(it).interfaceClass }
        return UsbDeviceSummary(
            vendorId = vendorId,
            productId = productId,
            deviceName = deviceName ?: "",
            manufacturer = manufacturerName,
            product = productName,
            interfaceClasses = classes
        )
    }
}
