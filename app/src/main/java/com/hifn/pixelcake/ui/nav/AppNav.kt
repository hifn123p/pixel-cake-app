package com.hifn.pixelcake.ui.nav

import androidx.compose.runtime.mutableStateListOf

/**
 * App 的顶层路由（T2 完整形态，审计 P1-12）。
 *
 * 刻意只有两个值：**工作台**（2-Tab 外壳：调色台/设置）与**全屏编辑器**。
 * - 弹层（导出单 / 历史 / 关于 / 模型中心）**不是路由** —— 它们是所属页面的局部状态，
 *   由各处的 `BackHandler` 先行消费（弹层不进栈，返回键语义才正确）；
 * - 模型中心走设置页的 Sheet（与 AboutSheet 同一模式），同样不进栈。
 * 将来若加「系留期间可修图」「独立模型中心页」，先在这里加路由值再接页面
 * —— 这正是 T2 把「编辑页开关布尔」升级成栈的全部意义：加页面 = 加枚举值，
 * 而不是再发明一个 `xxOpen` 布尔。
 */
sealed interface AppScreen {
    /** 2-Tab 工作台（调色台 / 设置）。栈底，永不 pop。 */
    data object Workspace : AppScreen

    /** 全屏编辑器。进入 = push，退出（editorOnBack 安全回收流程）= pop。 */
    data object Editor : AppScreen
}

/**
 * 最小导航栈（T2）。
 *
 * ## 为什么不用 androidx.navigation
 *
 * 两个路由、无参数传递、无深链 —— 引一整套导航库属于「逼代码去凑依赖」。
 * 一个 compose 状态栈 + 单测就是本页面的全部需求（DEV_PLAN 依赖现状的既定口径）。
 *
 * ## 可观察性
 *
 * 栈是 [mutableStateListOf] ⇒ `current` 的读取发生在组合里，push/pop 自动触发重组
 * —— 调用方不需要再维护一份镜像布尔（v0.6.5 之前 `editorOpen` 布尔与返回键处理
 * 各写一份，正是 T2 要消灭的漂移源）。
 */
class NavStack<T : Any>(initial: T) {

    private val stack = mutableStateListOf(initial)

    /** 当前栈顶路由。 */
    val current: T get() = stack.last()

    /** 栈深（含栈底）。诊断用。 */
    val depth: Int get() = stack.size

    /** 压入新路由。 */
    fun push(screen: T) {
        stack.add(screen)
    }

    /**
     * 弹出栈顶。栈底**永不弹出**（App 必须始终有一个可显示的页面）。
     * @return true = 确实弹了一层；false = 已经在栈底（调用方据此决定是否交还系统）
     */
    fun pop(): Boolean =
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            true
        } else {
            false
        }

    /** 原位替换栈顶（用于「同一页面重新初始化」类操作）。 */
    fun replaceTop(screen: T) {
        stack[stack.lastIndex] = screen
    }

    /** 清空并回到 [screen]（诊断/测试用；生产路径只有 pop/push）。 */
    fun resetTo(screen: T) {
        stack.clear()
        stack.add(screen)
    }
}
