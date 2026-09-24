package com.hifn.pixelcake.core.ml

import com.hifn.pixelcake.core.edit.ObjectScope
import com.hifn.pixelcake.core.edit.RetouchMask

/**
 * 一次分割推理的**全部**作用域蒙版（批次 5，`docs/OBJECT_TONE_DESIGN.md` §3 / §8）。
 *
 * ## 存在意义：把「已经算出来的 4 路概率」接出来
 *
 * 模型每次推理都会输出 6 类概率，而批次 5 之前只有「皮肤」那一路被 `MlSkinMask` 用了，
 * 其余 4 类算完即弃。本类持有**原始概率**，按需把任意一个作用域（含两个派生作用域）变成蒙版
 * ——**推理成本一个字都没涨**。
 *
 * ## 内存纪律（33MP 下的硬约束）
 *
 * | 项 | 量级 |
 * |---|---|
 * | 原始概率（常驻） | `256×256×6×4B` ≈ **1.5MB** |
 * | 单个作用域网格 | `256×256×4B` = **256KB**，**懒构建**、按作用域缓存 |
 * | 8 个作用域全用 | ≈ **2MB**（上界） |
 * | 整幅蒙版 | **0** —— 一条都不物化（`FloatArray(7008×4672)` 是 **131MB**） |
 *
 * ## 网格与目标尺寸**无关**，所以跨分辨率共享
 *
 * 网格是 256×256 的分割结果，与目标图尺寸无关；目标尺寸只在采样时当分母用
 * （见 `FloatGrid.sample`）。因此 [resampleTo] 返回的是**共享同一份网格缓存**的新实例，
 * 换个分辨率**不会重建任何网格**、更不会分配 `FloatArray(w*h)`。
 * 「预览 2048 ↔ 导出 7008」于是天然是同一份蒙版 —— 这是「预览所见 = 导出所得」在蒙版上的落点。
 *
 * ## 与自动蒙版的关系
 *
 * [ObjectScope.Skin] 走的是与 `MlSkinMask.fromProbs` **完全相同的阈值与平滑口径**
 * （`lo = 0.35` / `hi = 0.65` / 一次 3×3 平滑），所以「皮肤作用域」与「磨皮用的自动蒙版」
 * 在数值上就是同一个蒙版 —— 用户不会看到「磨皮圈出来的皮肤」与「皮肤层圈出来的皮肤」不一样。
 */
class ObjectMasks private constructor(
    private val grids: Grids,
    private val targetW: Int,
    private val targetH: Int
) {

    /** 网格边长（= 模型输入边长），供日志/调试展示。 */
    val gridSide: Int get() = grids.side

    /** 已构建的网格数（上界 8）。用于日志与「懒构建」的单测断言。 */
    fun builtGridCount(): Int = grids.builtCount

    /**
     * 取得 [scope] 的蒙版。**首次调用**才构建该作用域的网格，之后命中缓存。
     *
     * ⚠️ 派生作用域（[ObjectScope.Person] / [ObjectScope.Skin]）在这里各自走**完整**链路
     * （求和 → 阈值羽化 → 平滑），而不是拿子蒙版取 `max` —— 理由见 `ObjectScope` 的 KDoc
     * 与 `docs/OBJECT_TONE_DESIGN.md` §3.1（`thresholdAndFeather` 非线性，`max` 不可换入）。
     *
     * 永不抛异常、永不返回 `null`：某作用域在本图里为空时得到的是**合法的全 0 蒙版**，
     * 那是「这张图里没有这个对象」的物理事实，与「无法提供蒙版」不是一回事。
     */
    fun maskFor(scope: ObjectScope): RetouchMask =
        ObjectMask(grids.gridFor(scope), targetW, targetH, scope)

    /**
     * 换目标尺寸：**共享同一份网格缓存**，不重建任何网格（见类 KDoc）。
     * 尺寸未变时返回自身。
     */
    fun resampleTo(w: Int, h: Int): ObjectMasks =
        if (w == targetW && h == targetH) this else ObjectMasks(grids, w, h)

    /**
     * 一次推理的全部网格（与目标尺寸无关 ⇒ 跨分辨率、跨 `resampleTo` 共享）。
     *
     * `gridFor` 加 `@Synchronized`：编辑器的预览重渲与导出会并发进入（两者都在
     * `Dispatchers.Default` 上），串行化避免同一作用域被构建两次、以及缓存槽被交错写坏。
     * 网格本身是不可变的（`FloatGrid.data` 建好后只读），所以读侧无需同步。
     */
    private class Grids(val probs: FloatArray, val side: Int) {

        private val cache = arrayOfNulls<FloatGrid>(ObjectScope.entries.size)

        val builtCount: Int
            get() = cache.count { it != null }

        @Synchronized
        fun gridFor(scope: ObjectScope): FloatGrid {
            cache[scope.ordinal]?.let { return it }
            val v = SkinMaskPostProcess.scopeProbability(probs, side, scope.parts)
            SkinMaskPostProcess.thresholdAndFeather(v, LO, HI, v)
            SkinMaskPostProcess.smooth3x3(v, side, v)
            val g = FloatGrid(v, side, side)
            cache[scope.ordinal] = g
            return g
        }
    }

    companion object {

        /**
         * 概率羽化区间。与 `MlSkinMask.fromProbs` 的默认值**必须一致** ——
         * 否则「皮肤作用域」与「自动蒙版」会圈出不同的皮肤，见类 KDoc。
         */
        const val LO = 0.35f
        const val HI = 0.65f

        /**
         * 由模型输出的 6 通道概率构造。
         *
         * @param probs 长度 ≥ `side * side * CLASSES` 的 channel-last 概率（**被持有引用**，
         *   调用方不应再改它；`MlMaskProvider` 缓存的正是这一份）
         * @param side 模型输入边长（= 256）
         * @param targetW/targetH 目标图（预览或全分辨率）尺寸
         */
        fun fromProbs(probs: FloatArray, side: Int, targetW: Int, targetH: Int): ObjectMasks =
            ObjectMasks(Grids(probs, side), targetW, targetH)
    }
}

/**
 * 单个作用域的蒙版：`RetouchMask` 的网格实现（与 `MlSkinMask` 同构，只多带一个 [scope]）。
 *
 * 内部只持一张低分辨率网格（≈256KB），按需双线性采样到目标分辨率 ——
 * 与 `MlSkinMask` / `FullMask` 一样属于「O(1) 量级」的蒙版实现。
 *
 * ⚠️ **没有把 `MlSkinMask` 抽成基类**：那会动到一个已被 5 个测试覆盖、且刚通过真机验收的类，
 * 而两个类加起来只有 30 行、且语义不同（这个带 [scope]，是「对象作用域」的概念）。
 * 若将来出现第三种网格蒙版，再去抽基类；现在新增 30 行比动它便宜。
 */
class ObjectMask(
    private val grid: FloatGrid,
    private val targetW: Int,
    private val targetH: Int,
    /** 本蒙版对应的作用域。渲染不读它，仅供日志/排障与将来的蒙版缩略图。 */
    val scope: ObjectScope
) : RetouchMask {

    override fun sample(px: Int, py: Int): Float {
        if (px < 0 || py < 0 || px >= targetW || py >= targetH) return 0f
        return grid.sample(px, py, targetW, targetH)
    }

    /** 只替换目标尺寸（共享网格），**不物化整幅蒙版**。目标尺寸未变时返回自身。 */
    override fun resampleTo(w: Int, h: Int): RetouchMask =
        if (w == targetW && h == targetH) this else ObjectMask(grid, w, h, scope)
}
