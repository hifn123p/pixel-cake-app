package com.hifn.pixelcake.core.edit

/**
 * 非破坏编辑参数栈（与 DEV_PLAN §3.3 的 operations 对应）。
 * 所有调整只是改这里的字段，像素仅在渲染时物化。
 */
data class EditParams(
    val exposureEv: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val lumaPoints: List<Pair<Int, Int>> = listOf(0 to 0, 255 to 255),
    val lutId: String = "none",
    val lutIntensity: Float = 0.8f
)

/**
 * 一次可撤销的**完整编辑快照**：tonal 参数 [EditParams] + 人像精修 [RetouchState]。
 *
 * P1b 之前撤销栈只存 [EditParams]，导致「磨皮/液化/追色」等 retouch 改动不可撤销（P1 要求
 * 非破坏编辑栈可撤销/重做）。改为以快照为粒度后，一次 undo/redo 会同时回退调色与人像精修，
 * 语义与「一步操作」一致。
 *
 * 注：画笔蒙版/瑕疵描迹属「工具选择态」，由 UI 层单独持有，不入此快照（撤销不回退画笔轨迹）。
 */
data class EditSnapshot(
    val params: EditParams = EditParams(),
    val retouch: RetouchState = RetouchState()
)

/** 撤销/重做历史：持有当前 [EditSnapshot]，push 新状态即记录旧状态。 */
class EditHistory(initial: EditSnapshot = EditSnapshot()) {
    private val undoStack = mutableListOf<EditSnapshot>()
    private val redoStack = mutableListOf<EditSnapshot>()
    var current: EditSnapshot = initial
        private set

    fun push(next: EditSnapshot) {
        undoStack.add(current)
        current = next
        redoStack.clear()
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.add(current)
        current = undoStack.removeAt(undoStack.lastIndex)
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.add(current)
        current = redoStack.removeAt(redoStack.lastIndex)
        return true
    }

    fun reset(s: EditSnapshot = EditSnapshot()) {
        undoStack.clear()
        redoStack.clear()
        current = s
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
}
