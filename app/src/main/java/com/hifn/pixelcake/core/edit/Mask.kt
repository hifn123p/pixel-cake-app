package com.hifn.pixelcake.core.edit

import kotlin.math.sqrt

/**
 * retouch 蒙版抽象（P1b-4 / `docs/PHASE_DESIGN_HISTORY.md`（P1b 部分） §4）。
 * - P1：画笔栅格（`RasterMask.fromStrokes`）。
 * - P1+：ML 皮肤概率图实现同一接口，UI 无需改动。
 *
 * `sample` 返回 [0..1] 作用强度；`resampleTo` 用于预览→导出时同一 Mask 按目标分辨率对齐，
 * 保证「预览所见即导出所得」。
 *
 * **`null` 语义（全仓统一约定，勿再各算子自行解释）**：蒙版参数为 `null` = **未圈定作用域 → 该算子不执行**，
 * 而不是「全局生效」。皮肤类算子（`NeutralGray` 磨皮、`Beauty` 液化）都按此处理，全仓见
 * `docs/PHASE_DESIGN_HISTORY.md`（P1b 部分） §4。相机批量链路正是靠这条约定（`CameraBatch` 传 `mask = null`）避免把背景一起磨/形变；
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

/**
 * 画笔描迹蒙版（**B2 网格化版**）。
 *
 * ## 为什么从「全幅 FloatArray」改成「低分辨率网格 + 双线性采样」
 *
 * 旧实现 `fromStrokes` 直接物化 `FloatArray(w*h)`：33MP 导出档就是 **131MB** 的整幅分配，
 * 而画笔蒙版的真实信息量（几个圆形描迹 + 线性羽化）远不需要逐像素存储 —— 与 `FloatGrid`
 * （ML 蒙版，256×256）同一条「低分辨率网格按需上采样」的路线。
 * 网格边长按短边 1/4 取、钳在 256..1024（[Companion.gridSideFor]）：导出档 ≤ 4MB，
 * 双线性上采样的边缘软化与描迹自带的线性羽化叠加，视觉不可分辨。
 *
 * ## 精度口径（与旧版的差别）
 *
 * 描迹**圆心**与网格节点对齐处取值精确；**边缘**因网格插值最多软 ~2 个网格单元
 * （导出档 ~13px）。蒙版是「作用强度」而不是几何选择 —— 磨皮/液化对边缘 13px 的
 * 过渡带不敏感（旧版在同处本来就是线性羽化带）。语义不变：0..1 强度、圆外为 0、
 * 多描迹逐点取 max 累加（coerceIn 0..1）。
 *
 * `resampleTo` 与目标尺寸无关 ⇒ 共享同一份网格（预览↔导出天然同一蒙版，零大分配）。
 */
class RasterMask private constructor(
    private val grid: FloatArray,
    private val side: Int,
    val w: Int,
    val h: Int
) : RetouchMask {

    override fun sample(px: Int, py: Int): Float {
        if (px < 0 || py < 0 || px >= w || py >= h) return 0f
        // 像素坐标 → 网格坐标（端点对齐：px=0→0，px=w-1→side-1），再双线性插值。
        val gx = px * (side - 1).toFloat() / (w - 1).coerceAtLeast(1)
        val gy = py * (side - 1).toFloat() / (h - 1).coerceAtLeast(1)
        val x0 = gx.toInt().coerceIn(0, side - 1)
        val y0 = gy.toInt().coerceIn(0, side - 1)
        val x1 = (x0 + 1).coerceAtMost(side - 1)
        val y1 = (y0 + 1).coerceAtMost(side - 1)
        val tx = gx - x0
        val ty = gy - y0
        val v00 = grid[y0 * side + x0]
        val v10 = grid[y0 * side + x1]
        val v01 = grid[y1 * side + x0]
        val v11 = grid[y1 * side + x1]
        return (v00 * (1f - tx) + v10 * tx) * (1f - ty) + (v01 * (1f - tx) + v11 * tx) * ty
    }

    /** 只替换目标尺寸（共享网格），不重采样任何数据。目标尺寸未变时返回自身。 */
    override fun resampleTo(nw: Int, nh: Int): RetouchMask =
        if (nw == w && nh == h) this else RasterMask(grid, side, nw, nh)

    companion object {

        /**
         * 网格边长：短边 1/4，钳在 256..1024。
         * 下限 256 保证小图上笔触仍有足够形状；上限 1024 把内存钉死在 4MB。
         */
        internal fun gridSideFor(w: Int, h: Int): Int =
            (minOf(w, h) / 4).coerceIn(256, 1024)

        /**
         * 把若干画笔描迹累加成 0..1 网格蒙版（线性羽化软边）。
         *
         * 描迹在**像素空间**是圆 —— 网格按 x/y 各自缩放半径（椭圆栅格化），
         * 上采样回像素空间后仍是圆（否则非正方形图上笔触会变成椭圆）。
         */
        fun fromStrokes(w: Int, h: Int, strokes: List<BrushStroke>): RasterMask {
            val side = gridSideFor(w, h)
            val grid = FloatArray(side * side)
            val sx = (side - 1).toFloat() / (w - 1).coerceAtLeast(1)
            val sy = (side - 1).toFloat() / (h - 1).coerceAtLeast(1)
            for (s in strokes) {
                val rx = (s.r * sx).coerceAtLeast(0.5f)
                val ry = (s.r * sy).coerceAtLeast(0.5f)
                val cx = s.x * sx
                val cy = s.y * sy
                val gx0 = (cx - rx).toInt().coerceAtLeast(0)
                val gx1 = (cx + rx).toInt().coerceAtMost(side - 1)
                val gy0 = (cy - ry).toInt().coerceAtLeast(0)
                val gy1 = (cy + ry).toInt().coerceAtMost(side - 1)
                for (gy in gy0..gy1) {
                    for (gx in gx0..gx1) {
                        // 归一化椭圆距离：1 = 描迹边缘（强度 0），0 = 圆心（满强度）
                        val nx = (gx - cx) / rx
                        val ny = (gy - cy) / ry
                        val d = sqrt(nx * nx + ny * ny)
                        if (d > 1f) continue
                        val v = s.strength * (1f - d)
                        val idx = gy * side + gx
                        grid[idx] = if (v > grid[idx]) v else grid[idx]
                    }
                }
            }
            return RasterMask(grid, side, w, h)
        }
    }
}

/**
 * 两张蒙版**逐点取最大**的合并（P1p-1，见 `docs/PHASE_DESIGN_HISTORY.md`（P1+ 部分） §7）。
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
