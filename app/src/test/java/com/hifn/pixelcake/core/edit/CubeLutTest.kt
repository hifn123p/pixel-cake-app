package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * v0.9.0 3D cube LUT（PCB3 二进制）的纯 JVM 回归：
 * ① 解析（魔数/版本/尺寸/长度校验）；② 栅格点精确等于表值；
 * ③ 三线性插值在 2³ 线性场上精确；④ 损坏输入一律 null 降级。
 */
class CubeLutTest {

    private fun pcb3(size: Int, values: ShortArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write('P'.code); out.write('C'.code); out.write('B'.code); out.write('3'.code)
        out.write(1); out.write(0) // version u16 LE = 1
        out.write(size and 0xff); out.write((size shr 8) and 0xff)
        for (v in values) {
            val u = v.toInt() and 0xffff
            out.write(u and 0xff); out.write((u shr 8) and 0xff)
        }
        return out.toByteArray()
    }

    /** n³ 恒等表：value(r,g,b) = (r,g,b)。 */
    private fun identityBytes(size: Int): ByteArray {
        val n3 = size * size * size
        val vals = ShortArray(n3 * 3)
        var i = 0
        for (x in 0 until size) for (y in 0 until size) for (z in 0 until size) {
            vals[i++] = (x * 65535 / (size - 1)).toShort()
            vals[i++] = (y * 65535 / (size - 1)).toShort()
            vals[i++] = (z * 65535 / (size - 1)).toShort()
        }
        return pcb3(size, vals)
    }

    /** Long 打包（3 × 21-bit 定点）解码为 RGB。 */
    private fun unpack(p: Long): FloatArray = floatArrayOf(
        ((p shr 42) and 0x1FFFFFL) / CubeLut.FIX_ONE.toFloat(),
        ((p shr 21) and 0x1FFFFFL) / CubeLut.FIX_ONE.toFloat(),
        (p and 0x1FFFFFL) / CubeLut.FIX_ONE.toFloat()
    )

    @Test
    fun `parse identity cube and sample lattice points exactly`() {
        val lut = CubeLut.parse(identityBytes(3))
        assertNotNull(lut)
        lut!!
        assertEquals(3, lut.size)
        // 栅格点（n=3：0.5 → x = 0.5*2 = 1.0 整数栅格）必须精确等于表值
        var out = unpack(lut.sample(0f, 0f, 0f))
        assertEquals(0f, out[0], 1e-4f); assertEquals(0f, out[1], 1e-4f); assertEquals(0f, out[2], 1e-4f)
        out = unpack(lut.sample(0.5f, 0.5f, 0.5f))
        assertEquals(0.5f, out[0], 1e-4f); assertEquals(0.5f, out[1], 1e-4f); assertEquals(0.5f, out[2], 1e-4f)
        out = unpack(lut.sample(1f, 1f, 1f))
        assertEquals(1f, out[0], 1e-4f); assertEquals(1f, out[1], 1e-4f); assertEquals(1f, out[2], 1e-4f)
    }

    @Test
    fun `trilinear on linear field is exact at midpoint`() {
        // 2³ 恒等表 = 单位线性场：三线性插值对线性场是精确的 ⇒ 任意点 = 输入本身
        val lut = CubeLut.parse(identityBytes(2))!!
        val out = unpack(lut.sample(0.25f, 0.5f, 0.75f))
        assertEquals(0.25f, out[0], 1e-4f)
        assertEquals(0.5f, out[1], 1e-4f)
        assertEquals(0.75f, out[2], 1e-4f)
    }

    @Test
    fun `out of range input clamps`() {
        val lut = CubeLut.parse(identityBytes(2))!!
        val out = unpack(lut.sample(-0.5f, 2f, 0f))
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(1f, out[1], 1e-4f)
        assertEquals(0f, out[2], 1e-4f)
    }

    @Test
    fun `corrupt inputs all rejected`() {
        assertNull(CubeLut.parse(ByteArray(0)))
        assertNull(CubeLut.parse(ByteArray(8)))
        assertNull(CubeLut.parse("NOPE".toByteArray() + identityBytes(2)))
        // 版本错
        val bad = identityBytes(2).copyOf()
        bad[4] = 9
        assertNull(CubeLut.parse(bad))
        // 长度截断
        val short = identityBytes(3)
        assertNull(CubeLut.parse(short.copyOf(short.size - 2)))
    }

    @Test
    fun `size bounds enforced`() {
        // size=1 非法（<2）
        assertNull(CubeLut.parse(pcb3(1, ShortArray(3))))
        // size 合法上界内的 4³ 恒等表可解析
        val lut = CubeLut.parse(identityBytes(4))
        assertNotNull(lut)
        assertTrue(lut!!.size == 4)
    }
}
