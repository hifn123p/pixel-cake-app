package com.hifn.pixelcake.arw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TIFF 族魔数预过滤（v0.7.7 安全审计 MEDIUM-1 收口）的纯 JVM 回归：
 * 进入 LibRaw 原生解析器前必须验过 `II*\0` / `MM\0*` 魔数，
 * JPEG/PNG/截断/空文件一律拒绝。
 */
class ArwMagicTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(vararg bytes: Int): File =
        tmp.newFile("x.raw").apply {
            writeBytes(bytes.map { it.toByte() }.toByteArray())
        }

    @Test
    fun `little endian II magic passes`() {
        assertTrue(ArwFullDecoder.hasTiffMagic(file(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00)))
    }

    @Test
    fun `big endian MM magic passes`() {
        assertTrue(ArwFullDecoder.hasTiffMagic(file(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x08)))
    }

    @Test
    fun `jpeg soi rejected`() {
        assertFalse(ArwFullDecoder.hasTiffMagic(file(0xFF, 0xD8, 0xFF, 0xE0)))
    }

    @Test
    fun `png signature rejected`() {
        assertFalse(ArwFullDecoder.hasTiffMagic(file(0x89, 0x50, 0x4E, 0x47)))
    }

    @Test
    fun `short header rejected`() {
        assertFalse(ArwFullDecoder.hasTiffMagic(file(0x49, 0x49)))
    }

    @Test
    fun `empty file rejected`() {
        assertFalse(ArwFullDecoder.hasTiffMagic(tmp.newFile("e.raw").apply { writeBytes(ByteArray(0)) }))
    }
}
