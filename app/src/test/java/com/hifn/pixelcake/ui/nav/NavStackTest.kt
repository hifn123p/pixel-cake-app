package com.hifn.pixelcake.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2 导航栈不变量：push/pop 语义 / 栈底保护 / replaceTop / resetTo。
 * `mutableStateListOf` 在纯 JVM 上同样工作（快照状态不依赖 Android）。
 */
class NavStackTest {

    private fun stack() = NavStack(AppScreen.Workspace)

    @Test
    fun initial_isWorkspace() {
        assertEquals(AppScreen.Workspace, stack().current)
    }

    @Test
    fun push_thenPop_returnsToWorkspace() {
        val nav = stack()
        nav.push(AppScreen.Editor)
        assertEquals(AppScreen.Editor, nav.current)
        assertEquals(2, nav.depth)
        assertTrue(nav.pop())
        assertEquals(AppScreen.Workspace, nav.current)
        assertEquals(1, nav.depth)
    }

    @Test
    fun pop_atRoot_returnsFalseAndKeepsWorkspace() {
        val nav = stack()
        assertFalse("栈底永不弹出（App 必须始终有可显示页面）", nav.pop())
        assertEquals(AppScreen.Workspace, nav.current)
    }

    @Test
    fun editorReenter_pushPopPush() {
        val nav = stack()
        nav.push(AppScreen.Editor)
        nav.pop()
        nav.push(AppScreen.Editor)
        assertEquals(AppScreen.Editor, nav.current)
        assertTrue(nav.pop())
        assertEquals(AppScreen.Workspace, nav.current)
    }

    @Test
    fun replaceTop_swapsInPlace() {
        val nav = stack()
        nav.push(AppScreen.Editor)
        nav.replaceTop(AppScreen.Workspace)
        assertEquals(AppScreen.Workspace, nav.current)
        assertEquals(2, nav.depth)
    }

    @Test
    fun resetTo_clearsToSingle() {
        val nav = stack()
        nav.push(AppScreen.Editor)
        nav.push(AppScreen.Editor)
        nav.resetTo(AppScreen.Workspace)
        assertEquals(1, nav.depth)
        assertEquals(AppScreen.Workspace, nav.current)
    }
}
