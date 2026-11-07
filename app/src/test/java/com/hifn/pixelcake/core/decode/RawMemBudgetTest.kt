package com.hifn.pixelcake.core.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B4：native 线性会话的内存预算解析（`/proc/meminfo` 的 MemAvailable 行）。
 * 解析与决策分离 —— 决策（预算阈值）在 Android 侧，解析是纯函数。
 */
class RawMemBudgetTest {

    @Test
    fun parsesMemAvailableKilobytes() {
        val text = """
            MemTotal:       16384000 kB
            MemFree:         2048000 kB
            MemAvailable:    8192000 kB
            Buffers:          102400 kB
        """.trimIndent()
        assertEquals(8192000L * 1024, RawLinearSource.memAvailableBytes(text))
    }

    @Test
    fun missingLine_returnsNull() {
        assertNull(RawLinearSource.memAvailableBytes("MemTotal: 100 kB\nSwapFree: 1 kB"))
    }

    @Test
    fun malformedValue_returnsNull() {
        assertNull(RawLinearSource.memAvailableBytes("MemAvailable:    not-a-number kB"))
    }

    @Test
    fun budgetConstants_coverNativeFloor() {
        // 33MP 全质量 native 下界 ≈ 458MB（审计口径）× 1.2 ≈ 550MB —— 全档预算必须盖住它
        assertTrue(RawLinearSource.NATIVE_FLOOR_FULL_BYTES >= 458L * 1024 * 1024)
        // 代理快速解（halfSize）是全幅的 1/4 ≈ 115MB，预算按 1.4× 收敛
        assertTrue(RawLinearSource.NATIVE_FLOOR_HALF_BYTES < RawLinearSource.NATIVE_FLOOR_FULL_BYTES)
        assertTrue(RawLinearSource.NATIVE_FLOOR_HALF_BYTES >= 115L * 1024 * 1024)
    }
}
