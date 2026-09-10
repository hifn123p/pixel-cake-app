package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 撤销/重做：以 [EditSnapshot] 为粒度，undo/redo 必须**同时**回退 tonal 与 retouch。 */
class EditHistoryTest {

    @Test
    fun undoRedoRestoresParamsAndRetouch() {
        val h = EditHistory()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)

        val s1 = EditSnapshot(
            params = EditParams(exposureEv = 1f),
            retouch = RetouchState(neutralGray = NeutralGrayParams(strength = 0.5f))
        )
        h.push(s1)
        assertTrue(h.canUndo)
        assertEquals(1f, h.current.params.exposureEv, 1e-6f)
        assertEquals(0.5f, h.current.retouch.neutralGray.strength, 1e-6f)

        val s2 = EditSnapshot(
            params = EditParams(exposureEv = -0.5f),
            retouch = RetouchState(beauty = BeautyParams(slimFace = 0.3f))
        )
        h.push(s2)
        assertEquals(-0.5f, h.current.params.exposureEv, 1e-6f)

        // undo -> 回到 s1（params 与 retouch 同时回退，retouch 的 beauty 应归零）
        assertTrue(h.undo())
        assertEquals(1f, h.current.params.exposureEv, 1e-6f)
        assertEquals(0.5f, h.current.retouch.neutralGray.strength, 1e-6f)
        assertEquals(0f, h.current.retouch.beauty.slimFace, 1e-6f)

        // redo -> 回到 s2
        assertTrue(h.redo())
        assertEquals(-0.5f, h.current.params.exposureEv, 1e-6f)
        assertEquals(0.3f, h.current.retouch.beauty.slimFace, 1e-6f)
    }

    @Test
    fun pushClearsRedoStack() {
        val h = EditHistory()
        h.push(EditSnapshot(EditParams(exposureEv = 1f)))
        h.push(EditSnapshot(EditParams(exposureEv = 2f)))
        h.undo()
        assertTrue(h.canRedo)
        h.push(EditSnapshot(EditParams(exposureEv = 3f)))
        assertFalse("push 后应清空 redo 栈", h.canRedo)
        assertEquals(3f, h.current.params.exposureEv, 1e-6f)
    }

    @Test
    fun resetClearsStacksAndRestoresDefault() {
        val h = EditHistory()
        h.push(EditSnapshot(EditParams(exposureEv = 1f)))
        h.reset()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertEquals(0f, h.current.params.exposureEv, 1e-6f)
        assertEquals(0f, h.current.retouch.neutralGray.strength, 1e-6f)
    }
}
