package com.hifn.pixelcake.ui.editor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2：编辑器会话生命周期决策的回归测试 —— 「导出期间不手动回收」从 1300 行
 * Activity 里的内联判断，变成有名字、有测试的纯函数。
 */
class EditorSessionLifecycleTest {

    @Test
    fun exporting_retiresNothing() {
        val out = EditorSessionLifecycle.planRetirement(
            exporting = true, candidates = listOf("rendered", "compareBase", null)
        )
        assertTrue("导出期间归属权共享，全部交 GC（不手动回收）", out.isEmpty())
    }

    @Test
    fun idle_retiresNonNullInOrder() {
        val out = EditorSessionLifecycle.planRetirement(
            exporting = false, candidates = listOf("rendered", null, "compareBase", "renderSpare")
        )
        assertEquals(listOf("rendered", "compareBase", "renderSpare"), out)
    }

    @Test
    fun retireInLock_releasesInOrder() = runTest {
        val released = mutableListOf<String>()
        val resources = listOf<Any>("rendered", "compareBase", "renderSpare")
        EditorSessionLifecycle.retireInLock(Mutex(), resources) {
            released.add(it as String)
        }
        assertEquals(listOf("rendered", "compareBase", "renderSpare"), released)
    }

    @Test
    fun planRetirement_neverReorders() {
        // 回收顺序 = 候选顺序：不做任何「优化」—— 顺序本身是 onBack 注释里
        // 「先摘引用、后回收」纪律的一部分。
        val out = EditorSessionLifecycle.planRetirement(false, listOf(3, 1, 2))
        assertEquals(listOf(3, 1, 2), out)
    }
}
