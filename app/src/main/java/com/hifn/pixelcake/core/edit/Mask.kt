package com.hifn.pixelcake.core.edit

import kotlin.math.sqrt

/**
 * retouch 蒙版抽象（P1b-4 / `docs/P1b_DESIGN.md` §4）。
 * - P1：画笔栅格（`RasterMask.fromStrokes`）。
 * - P1+：ML 皮肤概率图实现同一接口，UI 无需改动。
 *
 * `sample` 返回 [0..1] 作用强度；`resampleTo` 用于预览→导出时同一 Mask 按目标分辨率对齐，
 * 保证「预览所见即导出所得」。
 */
interface RetouchMask {
    fun sample(px: Int, py: Int): Float
    fun resampleTo(w: Int, h: Int): RetouchMask
}

/** 画笔描迹（构建 skin 蒙版用）。 */
data class BrushStroke(val x: Int, val y: Int, val r: Int, val strength: Float = 1f)

class RasterMask(private val data: FloatArray, val w: Int, val h: Int) : RetouchMask {
    override fun sample(px: Int, py: Int): Float {
        if (px < 0 || py < 0 || px >= w || py >= h) return 0f
        return data[py * w + px]
    }

    override fun resampleTo(nw: Int, nh: Int): RetouchMask {
        if (nw == w && nh == h) return this
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) {
            val sy = (y * h / nh).coerceIn(0, h - 1)
            for (x in 0 until nw) {
                val sx = (x * w / nw).coerceIn(0, w - 1)
                out[y * nw + x] = data[sy * w + sx]
            }
        }
        return RasterMask(out, nw, nh)
    }

    companion object {
        /** 把若干画笔描迹累加成 0..1 栅格蒙版（线性羽化软边）。 */
        fun fromStrokes(w: Int, h: Int, strokes: List<BrushStroke>): RasterMask {
            val data = FloatArray(w * h)
            for (s in strokes) {
                val r = s.r.coerceAtLeast(1)
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        val x = s.x + dx
                        val y = s.y + dy
                        if (x < 0 || y < 0 || x >= w || y >= h) continue
                        val d = sqrt((dx * dx + dy * dy).toDouble())
                        if (d > r) continue
                        val v = s.strength * (1 - d / r).toFloat()
                        val idx = y * w + x
                        data[idx] = (data[idx] + v).coerceIn(0f, 1f)
                    }
                }
            }
            return RasterMask(data, w, h)
        }
    }
}
