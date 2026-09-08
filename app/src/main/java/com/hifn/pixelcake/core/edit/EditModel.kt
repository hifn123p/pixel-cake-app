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

/** 撤销/重做历史：持有当前 [EditParams]，push 新状态即记录旧状态。 */
class EditHistory(initial: EditParams = EditParams()) {
    private val undoStack = mutableListOf<EditParams>()
    private val redoStack = mutableListOf<EditParams>()
    var current: EditParams = initial
        private set

    fun push(next: EditParams) {
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

    fun reset(p: EditParams = EditParams()) {
        undoStack.clear()
        redoStack.clear()
        current = p
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
}
