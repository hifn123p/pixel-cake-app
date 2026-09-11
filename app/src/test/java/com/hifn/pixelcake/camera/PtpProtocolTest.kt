package com.hifn.pixelcake.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 PoC-2：PTP 容器编解码（纯 JVM，无 Android 依赖）。
 *
 * 这一层是本阶段唯一能被 CI 真实验证的部分——真机上相机怎么回我们管不了，
 * 但「我们发出去的字节是否合规、读回来的头是否解得对」必须是确定的。
 */
class PtpProtocolTest {

    @Test
    fun commandContainerHas12ByteLittleEndianHeader() {
        val bytes = PtpProtocol.encodeCommand(PtpProtocol.OP_GET_OBJECT_INFO, listOf(0x0000002A), 7)
        assertEquals(16, bytes.size)

        // length = 16（u32 小端）
        assertEquals(0x10, bytes[0].toInt() and 0xFF)
        assertEquals(0x00, bytes[1].toInt() and 0xFF)
        assertEquals(0x00, bytes[2].toInt() and 0xFF)
        assertEquals(0x00, bytes[3].toInt() and 0xFF)

        assertEquals(PtpProtocol.CONTAINER_COMMAND, PtpProtocol.readU16(bytes, 4))
        assertEquals(PtpProtocol.OP_GET_OBJECT_INFO, PtpProtocol.readU16(bytes, 6))
        assertEquals(7, PtpProtocol.readU32(bytes, 8))
        assertEquals(0x2A, PtpProtocol.readU32(bytes, 12))
    }

    @Test
    fun encodeCommandWithoutParamsIsExactlyHeader() {
        val bytes = PtpProtocol.encodeCommand(PtpProtocol.OP_GET_DEVICE_INFO, emptyList(), 1)
        assertEquals(PtpProtocol.HEADER_SIZE, bytes.size)
        val header = PtpProtocol.parseHeader(bytes)
        assertNotNull(header)
        assertEquals(0, header!!.payloadLength)
        assertEquals(PtpProtocol.CONTAINER_COMMAND, header.type)
    }

    @Test
    fun encodeCommandCarriesThreeParams() {
        val bytes = PtpProtocol.encodeCommand(
            PtpProtocol.OP_GET_OBJECT_HANDLES,
            listOf(0x00010001, 0, PtpProtocol.HANDLE_ALL),
            9
        )
        assertEquals(PtpProtocol.HEADER_SIZE + 12, bytes.size)
        assertEquals(0x00010001, PtpProtocol.readU32(bytes, 12))
        assertEquals(0, PtpProtocol.readU32(bytes, 16))
        assertEquals(-1, PtpProtocol.readU32(bytes, 20))
    }

    @Test
    fun parseHeaderRejectsShortBuffer() {
        assertNull(PtpProtocol.parseHeader(ByteArray(PtpProtocol.HEADER_SIZE - 1)))
    }

    @Test
    fun parseHeaderRejectsIllegalLength() {
        val bad = ByteArray(PtpProtocol.HEADER_SIZE)
        PtpProtocol.putU32(bad, 0, 5)
        assertNull(PtpProtocol.parseHeader(bad))
    }

    @Test
    fun parseHeaderAcceptsUnknownLengthMarker() {
        val unknown = ByteArray(PtpProtocol.HEADER_SIZE)
        PtpProtocol.putU32(unknown, 0, -1)
        PtpProtocol.putU16(unknown, 4, PtpProtocol.CONTAINER_DATA)
        PtpProtocol.putU16(unknown, 6, PtpProtocol.OP_GET_OBJECT)

        val header = PtpProtocol.parseHeader(unknown)
        assertNotNull(header)
        assertEquals(PtpProtocol.CONTAINER_DATA, header!!.type)
        assertTrue(header.payloadLength < 0)
    }

    @Test
    fun dataPhaseIsExplicitlyDeclared() {
        assertTrue(PtpProtocol.hasDataPhase(PtpProtocol.OP_GET_DEVICE_INFO))
        assertTrue(PtpProtocol.hasDataPhase(PtpProtocol.OP_GET_STORAGE_IDS))
        assertTrue(PtpProtocol.hasDataPhase(PtpProtocol.OP_GET_OBJECT))
        assertFalse(PtpProtocol.hasDataPhase(PtpProtocol.OP_OPEN_SESSION))
        assertFalse(PtpProtocol.hasDataPhase(PtpProtocol.OP_CLOSE_SESSION))
    }

    @Test
    fun namesAreReadable() {
        assertEquals("OpenSession", PtpProtocol.operationName(PtpProtocol.OP_OPEN_SESSION))
        assertEquals("GetObjectHandles", PtpProtocol.operationName(PtpProtocol.OP_GET_OBJECT_HANDLES))
        assertEquals("OK", PtpProtocol.responseName(PtpProtocol.RC_OK))
        assertEquals("SessionAlreadyOpen", PtpProtocol.responseName(PtpProtocol.RC_SESSION_ALREADY_OPEN))
        assertEquals("Data", PtpProtocol.containerTypeName(PtpProtocol.CONTAINER_DATA))
        assertEquals("Response", PtpProtocol.containerTypeName(PtpProtocol.CONTAINER_RESPONSE))
    }

    @Test
    fun unknownCodesFallBackToHex() {
        assertEquals("0x2ABC", PtpProtocol.responseName(0x2ABC))
        assertEquals("0x1000", PtpProtocol.operationName(0x1000))
    }

    @Test
    fun hexHelpersAreFixedWidth() {
        assertEquals("0x054C", PtpProtocol.hex4(0x054C))
        assertEquals("0x00010001", PtpProtocol.hex8(0x00010001))
        assertEquals("0xFFFFFFFF", PtpProtocol.hex8(-1))
    }
}
