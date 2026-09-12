package com.hifn.pixelcake.core.ml

/**
 * 分割概率 → 皮肤蒙版的**纯函数**后处理（零 Android / 零 LiteRT 依赖，可 JVM 单测）。
 *
 * 模型：MediaPipe `selfie_multiclass_256x256`（Apache-2.0，见 `app/src/main/assets/models/` 与根 `NOTICE`）。
 * 输出为 **channel-last** 的 `[1, 256, 256, 6]` float32 概率，通道顺序见 [CLASS_BACKGROUND]…[CLASS_OTHERS]。
 *
 * ⚠️ **channel-last 假设**：媒体管线的输出张量形状是 `[1,H,W,C]`，故展平后第 `i` 个像素的
 * 第 `c` 类概率位于 `i * CLASSES + c`。调用方（[LiteRtSkinMaskModel]）已校验输出长度，
 * 若未来换用 NCWH 布局的模型，必须同步改这里的索引方式。
 */
object SkinMaskPostProcess {

    const val CLASS_BACKGROUND = 0
    const val CLASS_HAIR = 1
    const val CLASS_BODY_SKIN = 2
    const val CLASS_FACE_SKIN = 3
    const val CLASS_CLOTHES = 4
    const val CLASS_OTHERS = 5

    /** 类别数（= 每像素占用的浮点数个数）。 */
    const val CLASSES = 6

    /**
     * channel-last 概率 → **皮肤概率**（`body-skin + face-skin`，截断到 [0,1]）。
     *
     * 只取「皮肤」两类，是为了让磨皮**不糊衣服、不磨头发**——这正是选 multiclass 而非
     * 「人/背景」二类模型的全部理由。
     *
     * @param probs 长度必须 ≥ `side * side * CLASSES`
     */
    fun skinProbability(probs: FloatArray, side: Int): FloatArray {
        require(side > 0) { "side must be > 0, got $side" }
        val n = side * side
        require(probs.size >= n * CLASSES) {
            "probs.size=${probs.size} < ${n * CLASSES}"
        }
        val out = FloatArray(n)
        for (i in 0 until n) {
            val base = i * CLASSES
            val p = probs[base + CLASS_BODY_SKIN] + probs[base + CLASS_FACE_SKIN]
            out[i] = if (p < 0f) 0f else if (p > 1f) 1f else p
        }
        return out
    }

    /**
     * 阈值 + 羽化：`v <= lo → 0`；`v >= hi → 1`；之间线性映射。
     *
     * `hi <= lo` 时退化为硬阈值（`v >= hi` 记 1）——只用于极端参数，正常路径 `lo < hi`。
     * **允许 `out === v`（原地）**。
     */
    fun thresholdAndFeather(v: FloatArray, lo: Float, hi: Float, out: FloatArray = v): FloatArray {
        val span = hi - lo
        for (i in v.indices) {
            val x = v[i]
            out[i] = if (span <= 0f) {
                if (x >= hi) 1f else 0f
            } else {
                ((x - lo) / span).coerceIn(0f, 1f)
            }
        }
        return out
    }

    /**
     * 3×3 均值平滑（对 256×256 网格开销可忽略），用于软化「256→全分辨率」上采样后的硬边。
     * **允许 `out === v`（原地）**——内部用临时数组，不会读到半更新值。
     */
    fun smooth3x3(v: FloatArray, side: Int, out: FloatArray = v): FloatArray {
        require(side > 0) { "side must be > 0, got $side" }
        val n = side * side
        require(v.size >= n) { "v.size=${v.size} < $n" }
        val tmp = FloatArray(n)
        for (y in 0 until side) {
            val yFrom = maxOf(0, y - 1)
            val yTo = minOf(side - 1, y + 1)
            for (x in 0 until side) {
                val xFrom = maxOf(0, x - 1)
                val xTo = minOf(side - 1, x + 1)
                var sum = 0f
                var cnt = 0
                for (yy in yFrom..yTo) {
                    for (xx in xFrom..xTo) {
                        sum += v[yy * side + xx]
                        cnt++
                    }
                }
                tmp[y * side + x] = sum / cnt
            }
        }
        System.arraycopy(tmp, 0, out, 0, n)
        return out
    }
}
