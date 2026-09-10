package com.hifn.pixelcake.core.edit.retouch

import com.hifn.pixelcake.core.edit.BrushStroke
import com.hifn.pixelcake.core.edit.RasterMask
import org.junit.Assert.assertEquals
import org.junit.Test

class RasterMaskTest {

    @Test
    fun strokeInsideIsStrongOutsideIsZero() {
        val w = 100
        val h = 100
        val mask = RasterMask.fromStrokes(w, h, listOf(BrushStroke(50, 50, 20, 1f)))
        assertEquals(1f, mask.sample(50, 50), 0.001f)
        assertEquals(0f, mask.sample(5, 5), 0.001f)
    }

    @Test
    fun resampleToScalesCoordinates() {
        val mask = RasterMask.fromStrokes(100, 100, listOf(BrushStroke(50, 50, 30, 1f)))
        val r = mask.resampleTo(50, 50)
        assertEquals(1f, r.sample(25, 25), 0.001f)
        assertEquals(0f, r.sample(2, 2), 0.001f)
    }
}
