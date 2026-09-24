package com.hifn.pixelcake.core.ml

import com.hifn.pixelcake.core.edit.ScopePart

/**
 * 分割概率 → 各路蒙版的**纯函数**后处理（零 Android / 零 LiteRT 依赖，可 JVM 单测）。
 *
 * 模型：MediaPipe `selfie_multiclass_256x256`（Apache-2.0，见 `app/src/main/assets/models/` 与根 `NOTICE`）。
 * 输出为 **channel-last** 的 `[1, 256, 256, 6]` float32 概率，通道顺序见 [CLASS_BACKGROUND]…[CLASS_OTHERS]。
 *
 * ⚠️ **channel-last 假设**：媒体管线的输出张量形状是 `[1,H,W,C]`，故展平后第 `i` 个像素的
 * 第 `c` 类概率位于 `i * CLASSES + c`。调用方（[LiteRtSkinMaskModel]）已校验输出长度，
 * 若未来换用 NCWH 布局的模型，必须同步改这里的索引方式。
 *
 * ## 6 类全部被使用（批次 5 起）
 *
 * 批次 5 之前这里只有 [skinProbability]：把 `body-skin + face-skin` 加起来给磨皮用，
 * **另外 4 类概率算完即弃**。对象作用域把「背景 / 头发 / 衣服 / 其他」也接了出来（见 [scopeProbability]），
 * 于是**推理成本一个字都没涨** —— 那些概率本来就在每次推理的输出里。
 *
 * ## 这里是「语义槽位 → 模型通道号」的**唯一**映射点
 *
 * [ScopePart] 刻意不带数字（见其 KDoc），编号只在这个 `when` 里出现一次。
 * `when` 是穷尽的 ⇒ 将来给 [ScopePart] 加槽位时漏映射会**编译不过**，而不是静默指错通道。
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

    /** 磨皮用的「皮肤」= 身体皮肤 ∪ 面部皮肤（批次 5 起由 [scopeProbability] 承担）。 */
    private val SKIN_PARTS = setOf(ScopePart.BodySkin, ScopePart.FaceSkin)

    /**
     * 语义槽位 → 模型输出通道号。**全仓唯一的映射点**（见类 KDoc）。
     *
     * 穷尽 `when` ⇒ 加槽位必编译报错。
     */
    fun classIndexOf(part: ScopePart): Int = when (part) {
        ScopePart.Background -> CLASS_BACKGROUND
        ScopePart.Hair -> CLASS_HAIR
        ScopePart.BodySkin -> CLASS_BODY_SKIN
        ScopePart.FaceSkin -> CLASS_FACE_SKIN
        ScopePart.Clothes -> CLASS_CLOTHES
        ScopePart.Others -> CLASS_OTHERS
    }

    /**
     * channel-last 概率 → **若干类之和**（截断到 `[0,1]`）。
     *
     * softmax 的六类之和恒为 1，所以「除背景」（`ObjectScope.Person`）既等于 `1 − P(背景)`、
     * 也等于其余五类之和。实现取**求和**，理由是它对「输出未归一化的模型」同样成立，
     * 而且只有一处 `coerceIn` —— 用 `1 − P(背景)` 就多了一个「什么时候该减」的分支。
     *
     * ## 求和顺序被**钉死**在 [ScopePart.entries] 上
     *
     * 浮点加法不满足结合律：遍历调用方给的 `Set` 时，`HashSet` 的迭代顺序会让「同一组槽位」
     * 得到相差 1ulp 的结果 —— 进而在极端像素上让「预览」与「导出」分叉。
     * 固定成枚举顺序就消除了这一类不确定性，代价是每次调用多一次 ≤6 元素的过滤。
     *
     * @param probs 长度必须 ≥ `side * side * CLASSES`
     * @param parts 成员槽位（顺序无关；实际累加顺序由枚举决定）
     */
    fun scopeProbability(probs: FloatArray, side: Int, parts: Set<ScopePart>): FloatArray {
        require(side > 0) { "side must be > 0, got $side" }
        val n = side * side
        require(probs.size >= n * CLASSES) {
            "probs.size=${probs.size} < ${n * CLASSES}"
        }
        val members = ScopePart.entries.filter { it in parts }.map { classIndexOf(it) }
        val out = FloatArray(n)
        for (i in 0 until n) {
            val base = i * CLASSES
            var p = 0f
            for (c in members) p += probs[base + c]
            out[i] = if (p < 0f) 0f else if (p > 1f) 1f else p
        }
        return out
    }

    /**
     * channel-last 概率 → **皮肤概率**（`body-skin + face-skin`，截断到 [0,1]）。
     *
     * 只取「皮肤」两类，是为了让磨皮**不糊衣服、不磨头发**——这正是选 multiclass 而非
     * 「人/背景」二类模型的全部理由。批次 5 起它退化为 [scopeProbability] 的一个特例，
     * **对外行为逐位不变**（`SkinMaskPostProcessTest` 里有等价性断言钉住这次重构）。
     *
     * @param probs 长度必须 ≥ `side * side * CLASSES`
     */
    fun skinProbability(probs: FloatArray, side: Int): FloatArray =
        scopeProbability(probs, side, SKIN_PARTS)

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
     * 3×3 均值平滑（对 256×256 网格开销可忽略）。
     *
     * ⚠️ 它作用在**上采样之前**的低分辨率网格上。调用顺序（见 [MlSkinMask.fromProbs]）是
     * `thresholdAndFeather` → **本函数** → 交给 `FloatGrid` 做双线性上采样 ⇒ 消除的是
     * 「阈值化在 256 网格上切出的硬边」，让之后的上采样不会把这些硬边放大成可见台阶。
     * 本 KDoc 曾写作「软化**上采样后**的硬边」，与实现顺序相反（结论相同但会误导后人
     * 去别处找上采样后的平滑，因而漏掉真正的位置）。
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
