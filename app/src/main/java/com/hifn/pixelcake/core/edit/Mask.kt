package com.hifn.pixelcake.core.edit

import kotlin.math.sqrt

/**
 * retouch 蒙版抽象（P1b-4 / `docs/P1b_DESIGN.md` §4）。
 * - P1：画笔栅格（`RasterMask.fromStrokes`）。
 * - P1+：ML 皮肤概率图实现同一接口，UI 无需改动。
 *
 * `sample` 返回 [0..1] 作用强度；`resampleTo` 用于预览→导出时同一 Mask 按目标分辨率对齐，
 * 保证「预览所见即导出所得」。
 *
 * **`null` 语义（全仓统一约定，勿再各算子自行解释）**：蒙版参数为 `null` = **未圈定作用域 → 该算子不执行**，
 * 而不是「全局生效」。皮肤类算子（`NeutralGray` 磨皮、`Beauty` 液化）都按此处理，全仓见
 * `docs/P1b_DESIGN.md` §4。相机批量链路正是靠这条约定（`CameraBatch` 传 `mask = null`）避免把背景一起磨/形变；
 * 代价是**批量与「未涂抹蒙版的编辑器」都不会执行皮肤类算子** —— 如需全局效果，调用方应显式传
 * [FullMask]（或任何全幅覆盖的蒙版），而不是依赖 `null` 的隐含含义。
 */
interface RetouchMask {
    fun sample(px: Int, py: Int): Float
    fun resampleTo(w: Int, h: Int): RetouchMask
}

/**
 * 全幅恒强蒙版：`sample` 恒为 1，**O(1) 内存**（注意不是一整张 `FloatArray` —— 33MP 下那是 131MB）。
 *
 * 这是调用方表达「**未圈定局部作用域 ⇒ 作用域就是整幅**」的显式手段：
 * 编辑器在用户没画任何画笔描迹、且未启用自动蒙版时用它（见 `RetouchScale.editorSkinMask`），
 * 从而保留「滑杆一拖就有可见效果」；
 * 相机批量链路则显式传 `null`，表达「不执行」。两者都写在**调用点**，`null` 于是只有一种含义。
 */
object FullMask : RetouchMask {
    override fun sample(px: Int, py: Int) = 1f
    override fun resampleTo(w: Int, h: Int) = this
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

/**
 * 两张蒙版**逐点取最大**的合并（P1p-1，见 `docs/P1p_DESIGN.md` §7）。
 *
 * 用于「ML 皮肤蒙版 ∪ 用户画笔描迹」：画笔是用户**显式补正**（例如 ML 漏了脖子/耳朵），
 * 取 `max` 才符合直觉；若取 `min` 会出现「涂了反而没效果」。
 *
 * 内存：自身不持有任何像素，只存两个引用 —— 与 [FullMask] 同属 O(1) 量级，
 * 不引入任何整幅分配（33MP 下 `FloatArray(w*h)` 就是 131MB）。
 */
class MaxMask(private val a: RetouchMask, private val b: RetouchMask) : RetouchMask {
    override fun sample(px: Int, py: Int): Float {
        val x = a.sample(px, py)
        val y = b.sample(px, py)
        return if (x >= y) x else y
    }

    override fun resampleTo(w: Int, h: Int): RetouchMask =
        MaxMask(a.resampleTo(w, h), b.resampleTo(w, h))
}
