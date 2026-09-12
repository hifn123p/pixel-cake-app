package com.hifn.pixelcake.core.ml

import com.hifn.pixelcake.core.edit.RetouchMask

/**
 * ML 皮肤蒙版：`RetouchMask` 的机器学习实现（P1p-1）。
 *
 * 内部只持一张**低分辨率**网格（[FloatGrid]，256×256 ≈ 256KB），按需双线性采样到目标分辨率。
 *
 * **内存硬约束（`docs/P1p_DESIGN.md` §8）**：
 * - `resampleTo(w, h)` **只换目标尺寸、共享同一网格**，绝不分配 `FloatArray(w*h)`
 *   —— 33MP 下那是 131MB，会毁掉 R10 好不容易压下来的 retouch 峰值；
 * - 与 [com.hifn.pixelcake.core.edit.FullMask] 一样，本类是「O(1) 量级」的蒙版实现。
 *
 * 蒙版语义与全仓约定一致：值 ∈ [0,1] 表示**作用强度**，`null`（而不是 0）才表示「不执行」。
 */
class MlSkinMask(
    private val grid: FloatGrid,
    private val targetW: Int,
    private val targetH: Int,
) : RetouchMask {

    /** 网格边长（≤256），供日志/调试展示。 */
    val gridSide: Int get() = grid.w

    override fun sample(px: Int, py: Int): Float {
        if (px < 0 || py < 0 || px >= targetW || py >= targetH) return 0f
        return grid.sample(px, py, targetW, targetH)
    }

    /**
     * 只替换目标尺寸（共享网格），**不物化整幅蒙版**。
     * 目标尺寸未变时直接返回自身，避免无谓分配。
     */
    override fun resampleTo(w: Int, h: Int): RetouchMask =
        if (w == targetW && h == targetH) this else MlSkinMask(grid, w, h)

    companion object {
        /**
         * 由模型输出的 6 通道概率构造蒙版。
         *
         * @param probs  长度 `side * side * 6` 的 channel-last 概率
         * @param side   模型输入边长（= 256）
         * @param targetW/targetH 目标图（预览或全分辨率）尺寸
         * @param lo/hi  皮肤概率的羽化区间：`<=lo` 完全不作用，`>=hi` 全强度
         * @param smooth 是否对网格做一次 3×3 平滑（软化上采样边缘，默认开）
         */
        fun fromProbs(
            probs: FloatArray,
            side: Int,
            targetW: Int,
            targetH: Int,
            lo: Float = 0.35f,
            hi: Float = 0.65f,
            smooth: Boolean = true,
        ): MlSkinMask {
            val skin = SkinMaskPostProcess.skinProbability(probs, side)
            SkinMaskPostProcess.thresholdAndFeather(skin, lo, hi, skin)
            if (smooth) SkinMaskPostProcess.smooth3x3(skin, side, skin)
            return MlSkinMask(FloatGrid(skin, side, side), targetW, targetH)
        }
    }
}
