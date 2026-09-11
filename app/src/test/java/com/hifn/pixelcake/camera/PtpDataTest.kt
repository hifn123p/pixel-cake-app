package com.hifn.pixelcake.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * P2 PoC-3：PTP 数据集解析（纯 JVM）。
 *
 * 用「手工按 PTP 规范拼出的字节」反向验证解析器：真机上相机回的字节长什么样无法预先知道，
 * 但**规范定义的字节布局是确定的**——只要解析器的字段顺序/宽度与规范一致，
 * 真机上出问题时就能把责任范围收窄到「相机没按规范回」。
 */
class PtpDataTest {

    /** 极简小端字节流构造器（仅测试用）。 */
    private class Buf {
        private val out = ByteArrayOutputStream()

        fun u8(v: Int): Buf {
            out.write(v and 0xFF)
            return this
        }

        fun u16(v: Int): Buf {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            return this
        }

        fun u32(v: Int): Buf {
            u16(v and 0xFFFF)
            u16((v ushr 16) and 0xFFFF)
            return this
        }

        fun u64(v: Long): Buf {
            u32((v and 0xFFFFFFFFL).toInt())
            u32(((v ushr 32) and 0xFFFFFFFFL).toInt())
            return this
        }

        /** PTP 字符串：长度字节含结尾 NUL。 */
        fun str(s: String): Buf {
            val withNul = s + "\u0000"
            u8(withNul.length)
            for (c in withNul) u16(c.code)
            return this
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    // ---------------- PtpReader ----------------

    @Test
    fun readsPtpStrings() {
        assertEquals("ILCE-7CM2", PtpReader(Buf().str("ILCE-7CM2").bytes()).string())
        // 长度字节为 0 → 空串
        assertEquals("", PtpReader(ByteArray(1)).string())
        // 长度 1、内容是 NUL → 空串
        assertEquals("", PtpReader(Buf().str("").bytes()).string())
    }

    @Test
    fun readsU16List() {
        val payload = Buf().u32(3).u16(0x1001).u16(0x1009).u16(0xB101).bytes()
        assertEquals(listOf(0x1001, 0x1009, 0xB101), PtpReader(payload).u16List())
    }

    @Test
    fun readsU32List() {
        val payload = Buf().u32(2).u32(0x00010001).u32(0x00020002).bytes()
        assertEquals(listOf(0x00010001, 0x00020002), PtpReader(payload).u32List())
    }

    @Test
    fun invalidArrayCountYieldsEmptyList() {
        val invalid = Buf().u32(-1).bytes()
        assertTrue(PtpReader(invalid).u16List().isEmpty())
        assertTrue(PtpReader(invalid).u32List().isEmpty())
    }

    @Test
    fun readsU64() {
        assertEquals(0x100000007L, PtpReader(Buf().u64(0x100000007L).bytes()).u64())
    }

    @Test
    fun readerIsSafeWhenTruncated() {
        val reader = PtpReader(ByteArray(3))
        assertEquals(0, reader.u32())
        assertEquals(0L, reader.u64())
        assertEquals("", reader.string())
    }

    // ---------------- DeviceInfo ----------------

    private fun deviceInfoPayload(): ByteArray = Buf()
        .u16(100)                                       // StandardVersion
        .u32(0x00000000)                                // VendorExtensionID
        .u16(100)                                       // VendorExtensionVersion
        .str("SONY EXT")                                // VendorExtensionDesc
        .u16(0)                                         // FunctionalMode
        .u32(2).u16(0x1001).u16(0x1009)                 // OperationsSupported
        .u32(0)                                         // EventsSupported
        .u32(0)                                         // DevicePropertiesSupported
        .u32(0)                                         // CaptureFormats
        .u32(1).u16(0x3801)                             // ImageFormats
        .str("SONY")
        .str("ILCE-7CM2")
        .str("3.00")
        .str("1234567")
        .bytes()

    @Test
    fun parsesDeviceInfo() {
        val info = parseDeviceInfo(deviceInfoPayload())
        assertNotNull(info)
        assertEquals("SONY", info!!.manufacturer)
        assertEquals("ILCE-7CM2", info.model)
        assertEquals("3.00", info.deviceVersion)
        assertEquals("1234567", info.serialNumber)
        assertEquals("SONY EXT", info.vendorExtensionDesc)
        assertEquals(listOf(0x1001, 0x1009), info.operationsSupported)
        assertEquals(listOf(0x3801), info.imageFormats)
        assertTrue(info.hasOperation(PtpProtocol.OP_GET_OBJECT))
        assertFalse(info.hasOperation(PtpProtocol.OP_GET_STORAGE_INFO))
        assertTrue(info.headline().contains("ILCE-7CM2"))
    }

    @Test
    fun rejectsTruncatedHostPayloads() {
        assertNull(parseDeviceInfo(ByteArray(10)))
        assertNull(parseStorageInfo(ByteArray(10)))
        assertNull(parseObjectInfo(ByteArray(10)))
        assertTrue(parseStorageIds(ByteArray(2)).isEmpty())
    }

    // ---------------- StorageIDs / Handles ----------------

    @Test
    fun parsesStorageIds() {
        val payload = Buf().u32(1).u32(0x00010001).bytes()
        assertEquals(listOf(0x00010001), parseStorageIds(payload))
    }

    @Test
    fun parsesObjectHandles() {
        val payload = Buf().u32(3).u32(5).u32(6).u32(7).bytes()
        assertEquals(listOf(5, 6, 7), parseObjectHandles(payload))
    }

    // ---------------- StorageInfo ----------------

    @Test
    fun parsesStorageInfo() {
        val payload = Buf()
            .u32(0x00010001)                                // StorageID
            .u16(4)                                         // StorageType = 可移动 RAM
            .u16(2)                                         // FilesystemType
            .u16(0)                                         // AccessCapability = 读写
            .u64(128L * 1024 * 1024 * 1024)                 // MaxCapacity
            .u64(64L * 1024 * 1024 * 1024)                  // FreeSpaceInBytes
            .u32(1234)                                      // FreeSpaceInImages
            .str("Memory Card")
            .str("SLOT1")
            .bytes()

        val info = parseStorageInfo(payload)
        assertNotNull(info)
        assertEquals(0x00010001, info!!.storageId)
        assertEquals(4, info.storageType)
        assertEquals("可移动 RAM（存储卡）", info.storageTypeName())
        assertEquals("读写", info.accessName())
        assertEquals(64L * 1024, info.freeSpaceMiB())
        assertEquals(128.0, info.capacityGiB(), 0.01)

        val label = info.label()
        assertTrue(label.contains("SLOT1"))
        assertTrue(label.contains("1234"))
    }

    // ---------------- ObjectInfo ----------------

    private fun objectInfoPayload(filename: String, format: Int): ByteArray = Buf()
        .u32(0x00010001)                                // StorageID
        .u16(format)                                    // ObjectFormat
        .u16(0)                                         // ProtectionStatus
        .u32(26 * 1024 * 1024)                          // ObjectCompressedSize
        .u16(0x3801)                                    // ThumbFormat
        .u32(5000)                                      // ThumbCompressedSize
        .u32(160)                                       // ThumbPixWidth
        .u32(107)                                       // ThumbPixHeight
        .u32(7008)                                      // ImagePixWidth
        .u32(4672)                                      // ImagePixHeight
        .u32(14)                                        // ImageBitDepth
        .u32(-1)                                        // ParentObject = 0xFFFFFFFF（根）
        .u16(0)                                         // AssociationType
        .u32(0)                                         // AssociationDesc
        .u32(42)                                        // SequenceNumber
        .str(filename)
        .str("20260910T181500")
        .str("20260910T181500")
        .bytes()

    @Test
    fun parsesObjectInfoForArw() {
        val info = parseObjectInfo(objectInfoPayload("DSC01234.ARW", PtpProtocol.FORMAT_ARW))
        assertNotNull(info)
        assertEquals("DSC01234.ARW", info!!.filename)
        assertEquals("arw", info.extension)
        assertTrue(info.looksLikeRaw)
        assertFalse(info.looksLikeJpeg)
        assertEquals(7008, info.imageWidth)
        assertEquals(4672, info.imageHeight)
        assertEquals(14, info.imageBitDepth)
        assertEquals(26.0, info.sizeMiB(), 0.01)
        assertEquals("20260910T181500", info.captureDate)
        assertTrue(info.label().contains("ARW"))
    }

    @Test
    fun parsesObjectInfoForJpeg() {
        // 相机常把格式报成「未定义」，因此判定必须以文件名为准
        val info = parseObjectInfo(objectInfoPayload("DSC01234.JPG", PtpProtocol.FORMAT_UNDEFINED))
        assertNotNull(info)
        assertEquals("jpg", info!!.extension)
        assertTrue(info.looksLikeJpeg)
        assertFalse(info.looksLikeRaw)
    }
}
