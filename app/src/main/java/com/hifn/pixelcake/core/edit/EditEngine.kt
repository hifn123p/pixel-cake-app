package com.hifn.pixelcake.core.edit

import android.graphics.Bitmap

/**
 * 把 [EditParams] 应用到位图上。
 * [renderInto] 复用同一个目标位图，避免滑块拖动时每帧重新分配 ~16MB 代理图。
 */
object EditEngine {

    fun renderInto(target: Bitmap, base: Bitmap, p: EditParams) {
        val w = base.width
        val h = base.height
        val px = IntArray(w * h)
        base.getPixels(px, 0, w, 0, 0, w, h)
        val lut = ColorMath.buildLumaLut(p.lumaPoints)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            val (nr, ng, nb) = ColorMath.processPixel(r, g, b, p, lut)
            px[i] = (0xff shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        target.setPixels(px, 0, w, 0, 0, w, h)
    }

    fun render(base: Bitmap, p: EditParams, recycleBase: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        renderInto(out, base, p)
        if (recycleBase) base.recycle()
        return out
    }
}
