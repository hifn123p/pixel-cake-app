package com.hifn.pixelcake.core.edit.preset

import com.hifn.pixelcake.core.edit.retouch.ColorTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 预设数据完整性：数量、id 唯一，且引用的追色风格必须真实存在。 */
class PresetsTest {

    @Test
    fun hasTenBuiltins() {
        assertEquals("P1b 规格为 ~10 套内置预设", 10, Presets.ALL.size)
    }

    @Test
    fun idsAreUnique() {
        val ids = Presets.ALL.map { it.id }
        assertEquals("预设 id 应唯一", ids.size, ids.toSet().size)
    }

    @Test
    fun colorTransferRefsAreValid() {
        for (p in Presets.ALL) {
            val ref = p.retouch.colorTransfer.refId
            assertTrue(
                "预设 ${p.id} 引用了未知追色风格 $ref",
                ref == "none" || ref in ColorTransfer.REF_IDS
            )
        }
    }

    @Test
    fun noneIsFirstAndExpectedIdsPresent() {
        assertEquals("首项应为原图(none)", "none", Presets.ALL.first().id)
        val ids = Presets.ALL.map { it.id }.toSet()
        assertTrue(
            ids.containsAll(
                listOf("none", "jp", "film", "retro", "morandi", "creamy", "portra", "bw", "cool", "warm")
            )
        )
    }
}
