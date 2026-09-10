package com.hifn.pixelcake.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2 PoC-1：USB 设备识别逻辑（纯 JVM，无 Android 依赖）。 */
class CameraProbeTest {

    private fun dev(vid: Int, classes: List<Int>) = UsbDeviceSummary(
        vendorId = vid,
        productId = 0x1234,
        deviceName = "/dev/bus/usb/001/002",
        manufacturer = "Sony",
        product = "ILCE-7CM2",
        interfaceClasses = classes
    )

    @Test
    fun detectsSonyVendor() {
        assertTrue(CameraProbe.isSonyCamera(dev(CameraProbe.SONY_VENDOR_ID, listOf(6))))
        assertFalse(CameraProbe.isSonyCamera(dev(0x1234, listOf(6))))
    }

    @Test
    fun modeHintByInterfaceClass() {
        assertEquals(
            "PTP/MTP（静像设备，可枚举/拉图）",
            CameraProbe.modeHint(dev(CameraProbe.SONY_VENDOR_ID, listOf(CameraProbe.USB_CLASS_STILL_IMAGE)))
        )
        assertEquals(
            "Mass Storage（可当 U 盘直接读文件）",
            CameraProbe.modeHint(dev(CameraProbe.SONY_VENDOR_ID, listOf(CameraProbe.USB_CLASS_MASS_STORAGE)))
        )
        assertEquals(
            "厂商特定（可能是 PC Remote）",
            CameraProbe.modeHint(dev(CameraProbe.SONY_VENDOR_ID, listOf(CameraProbe.USB_CLASS_VENDOR_SPEC)))
        )
        assertEquals(
            "未知接口",
            CameraProbe.modeHint(dev(CameraProbe.SONY_VENDOR_ID, listOf(3)))
        )
    }

    @Test
    fun hexFormatsToFourDigits() {
        assertEquals("0x054C", CameraProbe.hex(0x054C))
        assertEquals("0x0000", CameraProbe.hex(0))
        assertEquals("0xFFFF", CameraProbe.hex(0xFFFF))
    }

    @Test
    fun describeIncludesVidPidAndMode() {
        val s = CameraProbe.describe(dev(CameraProbe.SONY_VENDOR_ID, listOf(CameraProbe.USB_CLASS_STILL_IMAGE)))
        assertTrue(s.contains("0x054C"))
        assertTrue(s.contains("ILCE-7CM2"))
        assertTrue(s.contains("PTP/MTP"))
    }
}
