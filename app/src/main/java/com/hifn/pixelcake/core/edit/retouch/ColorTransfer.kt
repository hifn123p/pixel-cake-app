package com.hifn.pixelcake.core.edit.retouch

import com.hifn.pixelcake.core.edit.ColorTransferParams
import kotlin.math.sqrt

/**
 * 追色 / 色彩迁移（P1b-4 / Phase 5）。整图统计量匹配：把源图的逐通道均值/标准差
 * 映射到目标参考的均值/标准差（Reinhard 风格），按 intensity 与原图混合。
 *
 * 与磨皮/液化的「蒙版局部」不同，追色是**全局**算子（无 mask），整图统一调性，
 * 用于一键胶片/日系/莫兰迪等色彩风格。预览与导出各在自身整图上独立统计，
 * 同一算法 + 同一参考 → 所见即所得。
 *
 * **可流式**：源图统计量只是 6 个标量，所以调用方（`RetouchLayer`）可以「逐带累加 → 逐带施加」，
 * 不必把整幅图搬进堆。累加器跨带复用、按行序推进时，求和顺序与整幅循环**完全一致**，
 * 因此流式结果与 [apply] **逐位相同**（浮点求和不可交换，顺序必须守住）。
 *
 * 参考 Rust `crates/engine/src/retouch/color_transfer.rs`（实施时精读）。
 * 纯函数、零 Android 依赖，便于 JVM 单测。
 */
object ColorTransfer {

    /**
     * 内置参考风格的目标均值(R,G,B) / 标准差(R,G,B)。值为 sRGB 0..255 空间的手调近似值。
     *
     * [mono] 的含义见 [REFS] 里 `bw` 那条 —— **不能**靠「三通道均值相等」来实现去色。
     */
    private data class Ref(
        val mr: Float, val mg: Float, val mb: Float,
        val sr: Float, val sg: Float, val sb: Float,
        /** 是否共用一个亮度 z-score（真正去色）；为 false 时三通道各自映射。 */
        val mono: Boolean = false
    )

    private val REFS: Map<String, Ref> = mapOf(
        "portra" to Ref(140f, 128f, 118f, 55f, 52f, 50f), // 暖、柔和
        "fuji" to Ref(118f, 128f, 138f, 52f, 55f, 55f),   // 冷、偏青绿
        "retro" to Ref(135f, 125f, 115f, 40f, 38f, 36f),  // 低反差、暖旧
        "morandi" to Ref(130f, 130f, 128f, 35f, 35f, 35f),// 低饱和、灰调
        "jp" to Ref(162f, 160f, 156f, 45f, 45f, 45f),     // 高调、明亮
        // —— 批次 5 新增：与 `Presets` 的风格/风景/黑白分类对齐 ——
        "warm" to Ref(152f, 132f, 112f, 50f, 50f, 48f),   // 强暖，食物/日落
        "cool" to Ref(110f, 130f, 148f, 50f, 50f, 50f),   // 强冷，雪景/海景/蓝调
        // ⚠️ `mono = true` 是**必需**的，不能省：三通道目标值相等只保证「输出三通道的目标中心
        // 相同」，而每个通道的 z-score 仍来自各自的原图通道 —— 一个纯红像素 (230,60,60) 会
        // 得到三组差异很大的 z 值，被分别拉到 128 附近的不同偏移上，结果是**发青/发黄的伪黑白**。
        // 共用亮度 z-score 才是真正的去色（且保留了亮度结构，与 Reinhard 的意图一致）。
        "bw" to Ref(128f, 128f, 128f, 60f, 60f, 60f, mono = true)
    )

    /** 已知参考 id（供 UI 枚举；"none" 表示不追色）。 */
    val REF_IDS: Set<String> get() = REFS.keys

    /** 该参数是否真会改变像素（参考已知且 intensity > 0）。 */
    fun isActive(params: ColorTransferParams): Boolean =
        REFS.containsKey(params.refId) && params.intensity.coerceIn(0f, 1f) > 0f

    /** 源图统计量（逐通道均值 / 标准差）。 */
    data class Stats(
        val mr: Double, val mg: Double, val mb: Double,
        val sdr: Double, val sdg: Double, val sdb: Double
    )

    /** 第一趟：累加逐通道和到 [acc]（长度 ≥ 3）。跨带复用同一 [acc] 即可守住整幅求和顺序。 */
    fun accumulateSum(pixels: IntArray, length: Int, acc: DoubleArray) {
        var sr = acc[0]; var sg = acc[1]; var sb = acc[2]
        for (i in 0 until length) {
            val p = pixels[i]
            sr += (p shr 16) and 0xff
            sg += (p shr 8) and 0xff
            sb += p and 0xff
        }
        acc[0] = sr; acc[1] = sg; acc[2] = sb
    }

    /** 由逐通道和与总像素数求逐通道均值。 */
    fun meanOf(sum: DoubleArray, n: Double): DoubleArray =
        doubleArrayOf(sum[0] / n, sum[1] / n, sum[2] / n)

    /** 第二趟：按已知均值累加逐通道离差平方和到 [acc]（长度 ≥ 3）。 */
    fun accumulateVariance(pixels: IntArray, length: Int, mean: DoubleArray, acc: DoubleArray) {
        var vr = acc[0]; var vg = acc[1]; var vb = acc[2]
        val mr = mean[0]; val mg = mean[1]; val mb = mean[2]
        for (i in 0 until length) {
            val p = pixels[i]
            val dr = ((p shr 16) and 0xff) - mr
            val dg = ((p shr 8) and 0xff) - mg
            val db = (p and 0xff) - mb
            vr += dr * dr; vg += dg * dg; vb += db * db
        }
        acc[0] = vr; acc[1] = vg; acc[2] = vb
    }

    /** 汇总成 [Stats]（标准差防除零夹到 1.0）。 */
    fun statsOf(sum: DoubleArray, varSum: DoubleArray, n: Double): Stats {
        val d = n.coerceAtLeast(1.0)
        return Stats(
            sum[0] / d, sum[1] / d, sum[2] / d,
            sqrt(varSum[0] / d).coerceAtLeast(1.0),
            sqrt(varSum[1] / d).coerceAtLeast(1.0),
            sqrt(varSum[2] / d).coerceAtLeast(1.0)
        )
    }

    fun apply(pixels: IntArray, w: Int, h: Int, params: ColorTransferParams) {
        if (!isActive(params)) return
        val n = pixels.size.toDouble().coerceAtLeast(1.0)
        val sum = DoubleArray(3)
        accumulateSum(pixels, pixels.size, sum)
        val mean = meanOf(sum, n)
        val varSum = DoubleArray(3)
        accumulateVariance(pixels, pixels.size, mean, varSum)
        applyWithStats(pixels, pixels.size, params, statsOf(sum, varSum, n))
    }

    /** 用**已算好的**统计量施加（与 [apply] 的 z-score 公式逐位一致）。只处理前 [length] 个像素。 */
    fun applyWithStats(pixels: IntArray, length: Int, params: ColorTransferParams, stats: Stats) {
        val ref = REFS[params.refId] ?: return
        val intensity = params.intensity.coerceIn(0f, 1f)
        if (intensity <= 0f) return
        // mono 模式下三通道共用一个亮度 z-score（亮度权重 BT.601），这样才是真正的去色；
        // 若仍各用各的 z-score，像素会被「按各通道分别反推」⇒ 纯色像素会跑出彩边。
        val lumaZ = if (ref.mono) {
            ((stats.mr * 0.299 + stats.mg * 0.587 + stats.mb * 0.114) / stats.sdr).toFloat()
        } else 0f
        for (i in 0 until length) {
            val a = pixels[i] and 0xff000000.toInt()
            val or = (pixels[i] shr 16) and 0xff
            val og = (pixels[i] shr 8) and 0xff
            val ob = pixels[i] and 0xff
            val zr = if (ref.mono) lumaZ else ((or - stats.mr) / stats.sdr).toFloat()
            val zg = if (ref.mono) lumaZ else ((og - stats.mg) / stats.sdg).toFloat()
            val zb = if (ref.mono) lumaZ else ((ob - stats.mb) / stats.sdb).toFloat()
            val tr = (ref.mr + zr * ref.sr) * intensity + or * (1f - intensity)
            val tg = (ref.mg + zg * ref.sg) * intensity + og * (1f - intensity)
            val tb = (ref.mb + zb * ref.sb) * intensity + ob * (1f - intensity)
            val nr = tr.toInt().coerceIn(0, 255)
            val ng = tg.toInt().coerceIn(0, 255)
            val nb = tb.toInt().coerceIn(0, 255)
            pixels[i] = a or (nr shl 16) or (ng shl 8) or nb
        }
    }
}
