package com.hifn.pixelcake.core.edit

/**
 * 一键增强（UI 3.0 §09 顶栏 wand）：从直方图分布推一组保守的建议参数。
 *
 * ## 为什么是启发式而不是模型
 *
 * 「自动增强」在 UI3.0 规划里拆成两步：v1 = 纯启发式（本文，零模型、零延迟、可 JVM 单测）；
 * v2 = 小型回归 CNN（P6，见 docs/ONDEVICE_MODEL_PLAN.md M3）。v1 先把交互链路（wand 按钮 →
 * 建议值 → 走撤销栈 → 可重置）打通，v2 只换掉 suggest 的内部实现，UI 零改动。
 *
 * ## 输入：**峰值归一化**的 RGB 直方图
 *
 * 与 `EditorScreen` 的 produceState 输出同口径（`Histogram.normalize` 后的三条曲线）。
 * 百分位从 cumsum/total 计算 —— 线性归一化不改变 CDF 形状，所以峰值归一化安全。
 *
 * ## 建议策略（保守三件套）
 *
 * - **曝光**：把中位亮度推向 0.45（略偏亮的人像基准），±0.7 EV 上限；
 * - **对比度**：分布范围（p95−p05）窄于 0.55 ⇒ 提对比（最大 +0.3）；
 * - **白平衡**：R 中位 vs B 中位差 > 20 级 ⇒ 反向微调（±0.1）；
 * - **不做**：饱和度 / 鲜艳度 / HSL —— 从一维直方图推断色彩状态太不可靠，宁可不给（§9.4）。
 */
object AutoEnhance {

    fun suggest(h: Triple<FloatArray, FloatArray, FloatArray>): EditParams {
        val bins = minOf(h.first.size, h.second.size, h.third.size)
        if (bins < 2) return EditParams()
        // 亮度曲线 = 三通道加权和（与 ColorMath / DetailPass 的 luma 权重一致）
        val lum = FloatArray(bins) { i ->
            0.2126f * h.first[i] + 0.7152f * h.second[i] + 0.0722f * h.third[i]
        }
        val p05 = percentile(lum, 0.05f) / 255f
        val p50 = percentile(lum, 0.50f) / 255f
        val p95 = percentile(lum, 0.95f) / 255f

        // 曝光：中位亮度 → 0.45（保守 ±0.7 EV）
        val mid = (p05 + p95) * 0.5f
        val exposure = ((0.45f - mid) * 2.0f).coerceIn(-0.7f, 0.7f)

        // 对比度：动态范围窄于 0.55 ⇒ 提（最大 +0.3，足够可感但不粗暴）
        val range = p95 - p05
        val contrast = if (range < 0.55f && range > 0.01f) ((0.55f - range) * 1.5f).coerceIn(0f, 0.3f) else 0f

        // 白平衡：R 中位 vs B 中位差 > 20 级 ⇒ 反向微调 ±0.1
        val medR = percentile(h.first, 0.5f)
        val medB = percentile(h.third, 0.5f)
        val temperature = when {
            medR > medB + 20f -> -0.1f
            medB > medR + 20f -> 0.1f
            else -> 0f
        }

        return EditParams(exposureEv = exposure, contrast = contrast, temperature = temperature)
    }

    /** 从峰值归一化曲线取第 [p] 分位的 bin 下标（0..255）。线性归一化不改 CDF 形状。 */
    private fun percentile(curve: FloatArray, p: Float): Float {
        var sum = 0f
        for (v in curve) sum += v
        if (sum <= 0f) return 128f
        val target = sum * p
        var acc = 0f
        for (i in curve.indices) {
            acc += curve[i]
            if (acc >= target) return i.toFloat()
        }
        return (curve.size - 1).toFloat()
    }

    private fun mean(curve: FloatArray): Float {
        if (curve.isEmpty()) return 0f
        var s = 0f
        for (v in curve) s += v
        return s / curve.size
    }
}
