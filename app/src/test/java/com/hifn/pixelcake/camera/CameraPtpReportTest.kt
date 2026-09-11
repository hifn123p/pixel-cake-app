package com.hifn.pixelcake.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 握手报告的判定与摘要格式（纯 JVM）。
 *
 * 报告是用户唯一要回传的东西，所以「成功/失败判定」和「关键信息有没有出现在摘要里」
 * 都值得钉住——报告漏了型号或对象数，真机调试就得多跑一轮。
 */
class CameraPtpReportTest {

    private val deviceInfo = PtpDeviceInfo(
        standardVersion = 100,
        vendorExtensionId = 0,
        vendorExtensionVersion = 100,
        vendorExtensionDesc = "SONY EXT",
        functionalMode = 0,
        operationsSupported = listOf(PtpProtocol.OP_GET_OBJECT, PtpProtocol.OP_GET_OBJECT_INFO),
        eventsSupported = emptyList(),
        devicePropertiesSupported = emptyList(),
        captureFormats = emptyList(),
        imageFormats = listOf(PtpProtocol.FORMAT_ARW),
        manufacturer = "SONY",
        model = "ILCE-7CM2",
        deviceVersion = "3.00",
        serialNumber = "1234567"
    )

    private val arwSample = PtpObjectInfo(
        storageId = 0x00010001,
        format = PtpProtocol.FORMAT_ARW,
        protectionStatus = 0,
        compressedSizeBytes = 26L * 1024 * 1024,
        imageWidth = 7008,
        imageHeight = 4672,
        imageBitDepth = 14,
        parentObject = PtpProtocol.HANDLE_ALL,
        sequenceNumber = 42,
        filename = "DSC01234.ARW",
        captureDate = "20260910T181500",
        modificationDate = "20260910T181500"
    )

    private fun report(
        openCode: Int? = PtpProtocol.RC_OK,
        failure: String? = null,
        samples: List<PtpObjectInfo> = emptyList()
    ) = CameraPtpReport(
        deviceLabel = "Sony ILCE-7CM2 [0x054C:0x0E17] /dev/bus/usb/001/002",
        steps = listOf("1) USB 授权：已具备（无需弹窗）", "2) OpenSession → OK"),
        openSessionCode = openCode,
        deviceInfo = deviceInfo,
        storages = emptyList(),
        handleCounts = mapOf(0x00010001 to 1532),
        handleQueryMode = mapOf(0x00010001 to "associationHandle=0xFFFFFFFF（全部对象）"),
        samples = samples,
        elapsedMs = 812,
        notices = emptyList(),
        failure = failure
    )

    @Test
    fun successfulReportIsOkAndMentionsKeyFacts() {
        val summary = report()
        assertTrue(summary.ok)

        val text = summary.summaryLines().joinToString("\n")
        assertTrue(text.contains("握手成功"))
        assertTrue(text.contains("ILCE-7CM2"))
        assertTrue(text.contains("3.00"))
        assertTrue(text.contains("1532"))
        assertTrue(text.contains("GetObject=是"))
        assertTrue(text.contains("812 ms"))
    }

    @Test
    fun failureReportIsNotOkAndLeadsWithReason() {
        val failed = report(openCode = null, failure = "打开设备或声明 PTP 接口失败")
        assertFalse(failed.ok)
        assertTrue(failed.summaryLines().first().contains("打开设备或声明 PTP 接口失败"))
    }

    @Test
    fun sessionResponseCodeAloneCanFailTheReport() {
        assertFalse(report(openCode = PtpProtocol.RC_SESSION_ALREADY_OPEN).ok)
        assertFalse(report(openCode = PtpProtocol.RC_ACCESS_DENIED).ok)
        assertTrue(report(openCode = PtpProtocol.RC_OK).ok)
    }

    @Test
    fun samplesAreCountedAndListed() {
        val summary = report(samples = listOf(arwSample))
        assertEquals(1, summary.rawSampleCount)

        val text = summary.summaryLines().joinToString("\n")
        assertTrue(text.contains("DSC01234.ARW"))
        assertTrue(text.contains("RAW 候选 1"))
        assertTrue(text.contains("7008×4672"))
    }
}
