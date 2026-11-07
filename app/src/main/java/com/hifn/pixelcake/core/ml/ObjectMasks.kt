package com.hifn.pixelcake.core.ml

import com.hifn.pixelcake.core.edit.ObjectScope
import com.hifn.pixelcake.core.edit.RetouchMask
import com.hifn.pixelcake.core.edit.ScopePart
import kotlin.math.roundToInt

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
     * 命中测试（`UI3.0.html` §D「点哪修哪」）：给定**归一化**坐标，返回该点最可能的作用域。
     *
     * ## 为什么不复用 [maskFor]
     *
     * `maskFor` 要为每个作用域构建网格（求和 → 阈值羽化 → 3×3 平滑），首次命中要跑满 8 遍；
     * 而「点一下画面就选中对象」是**手势响应**动作，不能等几十毫秒。
     * 这里直接读 6 类原始概率做 argmax —— 一次查表、零分配。
     *
     * ## 什么时候返回 null
     *
     * 最大概率低于 [HIT_THRESHOLD] 时返回 null，表示「这一点不属于任何一类」
     * （天空、纯色背景、严重失焦）。**语义是「这次点击不改变当前选择」**，不是「没有对象」——
     * 让用户随手点一下空白就把好不容易选中的作用域清掉，是更坏的体验。
     *
     * @return **原生**作用域（`面部 / 身体 / 头发 / 衣服 / 配饰 / 背景`）。
     *   派生作用域（`人物` / `皮肤`）永远不会被点出来 —— 它们是多类的并集，
     *   而一个像素只属于一类；把 `人物` 作为点击结果等于让用户失去细分的入口。
     */
    fun scopeAt(u: Float, v: Float): ObjectScope? {
        val side = grids.side
        if (side <= 0) return null
        val x = (u.coerceIn(0f, 1f) * (side - 1)).roundToInt()
        val y = (v.coerceIn(0f, 1f) * (side - 1)).roundToInt()
        val base = (y * side + x) * SkinMaskPostProcess.CLASSES
        if (base < 0 || base + SkinMaskPostProcess.CLASSES > grids.probs.size) return null
        var bestClass = -1
        var bestP = 0f
        for (c in 0 until SkinMaskPostProcess.CLASSES) {
            val p = grids.probs[base + c]
            if (p > bestP) {
                bestP = p
                bestClass = c
            }
        }
        if (bestClass < 0 || bestP < HIT_THRESHOLD) return null
        val part = when (bestClass) {
            SkinMaskPostProcess.CLASS_BACKGROUND -> ScopePart.Background
            SkinMaskPostProcess.CLASS_HAIR -> ScopePart.Hair
            SkinMaskPostProcess.CLASS_BODY_SKIN -> ScopePart.BodySkin
            SkinMaskPostProcess.CLASS_FACE_SKIN -> ScopePart.FaceSkin
            SkinMaskPostProcess.CLASS_CLOTHES -> ScopePart.Clothes
            else -> ScopePart.Others
        }
        return ObjectScope.entries.firstOrNull { it.parts.size == 1 && it.parts.first() == part }
    }

    /**
     * 本图里**非空**的作用域集合（U4「空作用域禁用」的数据源）。
     *
     * ## 为什么不逐个构建网格再数像素
     *
     * `gridFor` 每个作用域要跑「求和 → 阈值羽化 → 3×3 平滑」全链路，8 个全建就是一次
     * 可观的预热；而判定「有没有」根本不需要羽化和平滑 —— 直接扫**原始概率**：
     * 单像素、单遍、零分配（除了返回的 Set），256×256 全图一遍就是约 6.5 万次循环，
     * 毫秒级。派生作用域（[ObjectScope.Person] / [ObjectScope.Skin]）按「求和」口径判
     * （与 `scopeProbability` 一致，只是不做阈值羽化），避免「两类各 0.4、求和 0.8」
     * 被逐类计数漏判。
     *
     * ## 判空阈值 [minPixels]
     *
     * 默认 48 像素（256×256 网格的 0.07%）：能被肉眼看见的对象远大于它，而分割噪声
     * （边缘抖出的零星几像素）小于它。**这是 UI 可用性判据，不是渲染判据** ——
     * 渲染侧永远用 `maskFor`，全 0 蒙版本来也是合法的恒等层。
     */
    fun coveredScopes(minPixels: Int = 48): Set<ObjectScope> {
        val side = grids.side
        if (side <= 0) return emptySet()
        val probs = grids.probs
        val counts = IntArray(ObjectScope.entries.size)
        val total = side * side
        val classes = SkinMaskPostProcess.CLASSES
        var px = 0
        var base = 0
        while (px < total) {
            val bg = probs[base + SkinMaskPostProcess.CLASS_BACKGROUND]
            val hair = probs[base + SkinMaskPostProcess.CLASS_HAIR]
            val body = probs[base + SkinMaskPostProcess.CLASS_BODY_SKIN]
            val face = probs[base + SkinMaskPostProcess.CLASS_FACE_SKIN]
            val clothes = probs[base + SkinMaskPostProcess.CLASS_CLOTHES]
            val others = probs[base + SkinMaskPostProcess.CLASS_OTHERS]
            // 原生 6 类：逐类计数（与 scopeAt 的 argmax 不同口径 —— 这里问的是
            // 「这个类在这张图里有没有可观的存在」，不是「这个像素属于谁」）。
            if (bg > LO) counts[ObjectScope.Background.ordinal]++
            if (hair > LO) counts[ObjectScope.Hair.ordinal]++
            if (body > LO) counts[ObjectScope.BodySkin.ordinal]++
            if (face > LO) counts[ObjectScope.FaceSkin.ordinal]++
            if (clothes > LO) counts[ObjectScope.Clothes.ordinal]++
            if (others > LO) counts[ObjectScope.Accessories.ordinal]++
            // 派生 2 类：求和口径（见上面 KDoc）
            if (face + body > LO) counts[ObjectScope.Skin.ordinal]++
            if (hair + body + face + clothes + others > LO) counts[ObjectScope.Person.ordinal]++
            base += classes
            px++
        }
        val out = LinkedHashSet<ObjectScope>()
        for (scope in ObjectScope.entries) {
            if (counts[scope.ordinal] >= minPixels) out.add(scope)
        }
        return out
    }

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
         * 命中测试的最低置信度（[ObjectMasks.scopeAt]）。
         *
         * 取值与羽化下界 [LO] 同值不是巧合：低于它连蒙版都不认为「属于」，
         * 那点击也就不该选中它 —— 两个判据必须同口径，否则会出现
         * 「点中了、但选中后蒙版全黑」这种自相矛盾的状态。
         */
        const val HIT_THRESHOLD = 0.35f

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
