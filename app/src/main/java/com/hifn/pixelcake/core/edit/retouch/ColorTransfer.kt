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
 * 参考 Rust `crates/engine/src/retouch/color_transfer.rs`（实施时精读）。
 * 纯函数、零 Android 依赖，便于 JVM 单测。
 */
object ColorTransfer {

    /** 内置参考风格的目标均值(R,G,B) / 标准差(R,G,B)。值为 sRGB 0..255 空间的手调近似值。 */
    private data class Ref(val mr: Float, val mg: Float, val mb: Float, val sr: Float, val sg: Float, val sb: Float)

    private val REFS: Map<String, Ref> = mapOf(
        "portra" to Ref(140f, 128f, 118f, 55f, 52f, 50f), // 暖、柔和
        "fuji" to Ref(118f, 128f, 138f, 52f, 55f, 55f),   // 冷、偏青绿
        "retro" to Ref(135f, 125f, 115f, 40f, 38f, 36f),  // 低反差、暖旧
        "morandi" to Ref(130f, 130f, 128f, 35f, 35f, 35f),// 低饱和、灰调
        "jp" to Ref(162f, 160f, 156f, 45f, 45f, 45f)      // 高调、明亮
    )

    /** 已知参考 id（供 UI 枚举；"none" 表示不追色）。 */
    val REF_IDS: Set<String> get() = REFS.keys

    fun apply(pixels: IntArray, w: Int, h: Int, params: ColorTransferParams) {
        val ref = REFS[params.refId] ?: return
        val intensity = params.intensity.coerceIn(0f, 1f)
        if (intensity <= 0f) return

        // 第一趟：源图逐通道均值
        var sr = 0.0; var sg = 0.0; var sb = 0.0
        for (p in pixels) {
            sr += (p shr 16) and 0xff
            sg += (p shr 8) and 0xff
            sb += p and 0xff
        }
        val n = pixels.size.toDouble().coerceAtLeast(1.0)
        val mr = sr / n; val mg = sg / n; val mb = sb / n

        // 源图逐通道标准差（防除以 0 夹到 1.0）
        var vr = 0.0; var vg = 0.0; var vb = 0.0
        for (p in pixels) {
            val dr = ((p shr 16) and 0xff) - mr
            val dg = ((p shr 8) and 0xff) - mg
            val db = (p and 0xff) - mb
            vr += dr * dr; vg += dg * dg; vb += db * db
        }
        val sdr = sqrt(vr / n).coerceAtLeast(1.0)
        val sdg = sqrt(vg / n).coerceAtLeast(1.0)
        val sdb = sqrt(vb / n).coerceAtLeast(1.0)

        // 第二趟：z-score 重映射 + 强度混合
        for (i in pixels.indices) {
            val a = pixels[i] and 0xff000000.toInt()
            val or = (pixels[i] shr 16) and 0xff
            val og = (pixels[i] shr 8) and 0xff
            val ob = pixels[i] and 0xff
            val zr = ((or - mr) / sdr).toFloat()
            val zg = ((og - mg) / sdg).toFloat()
            val zb = ((ob - mb) / sdb).toFloat()
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
