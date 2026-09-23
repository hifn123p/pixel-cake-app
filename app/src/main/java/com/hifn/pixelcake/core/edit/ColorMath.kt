package com.hifn.pixelcake.core.edit

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 纯函数像素运算（无 Android 依赖，可 JVM 单测）。
 *
 * 编辑只发生在「参数栈」[EditParams] 上，真正落像素时由 [EditEngine] 调用 [PixelProgram]。
 * 管线的输入统一是 **16-bit 线性 sRGB**（0..65535，白点 65535）：
 *  - ARW：LibRaw 直接输出线性数据（见 `raw_bridge.cpp`，`output_bps=16` + 线性 gamma）；
 *  - JPEG/HEIF：先经 [SRGB8_TO_LINEAR16] 转线性，再走同一条管线。
 * 两端同源，保证「预览所见即导出所得」。
 *
 * 管线顺序（`docs/TONING_DESIGN.md` §3）。⚠️ **顺序本身就是观感的一部分** ——
 * 同一组参数换个顺序就是另一个效果，重构时不要无声改动：
 *   线性域：白平衡 → 曝光 → 高光压肩 → sRGB 编码
 *   sRGB 域：影调四区(黑场/阴影/高光/白场) → 对比度 → 去朦胧 → 亮度曲线 → 自然饱和度
 *            → 饱和度 → HSL 混色 → 彩色分级 → 分通道曲线 → 内置 LUT → **暗角 → 颗粒**
 *
 * 末两级（批次 3）与前 13 级有一处**本质区别**：它们需要知道「这个像素在画面里的哪儿」，
 * 即需要一对**长边归一化坐标** `(u, v)`（口径见 [EditParams] 末尾「效果」那一段）。
 */
object ColorMath {

    /** 8-bit sRGB -> 16-bit 线性。输入只有 256 种取值，精确表即可，不存在精度损失。 */
    val SRGB8_TO_LINEAR16: IntArray = IntArray(256) { i ->
        (srgbToLinear(i / 255f) * 65535f + 0.5f).toInt().coerceIn(0, 65535)
    }

    /**
     * 16-bit 线性 -> sRGB 编码表，索引 = `round(线性值 * 65535)`，共 65536 项（约 256KB）。
     *
     * 此前只有 256 项且建在线性域均匀网格上：线性 0~1/255 已对应 sRGB 0~0.19，
     * 暗部整段塌进第一格，必然出色带。加密到 65536 项后每格宽度 = 1/65535，
     * 暗部台阶宽度降到原来的 1/256，肉眼不可辨（FIX_LIST F07）。
     */
    private val LINEAR16_TO_SRGB: FloatArray = FloatArray(65536) { i ->
        linearToSrgb(i / 65535f)
    }

    /**
     * 线性域查表编码为 sRGB，返回 0..1。
     * 只在 v >= 白点时钳到 1（这是真实的高光截断，不是提前 clamp 掉可恢复的高光）。
     */
    fun linear16ToSrgb(v: Float): Float = when {
        v <= 0f -> 0f
        v >= 65535f -> 1f
        else -> LINEAR16_TO_SRGB[(v + 0.5f).toInt()]
    }

    fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    fun linearToSrgb(c: Float): Float {
        val x = if (c <= 0f) 0f else if (c >= 1f) 1f else c
        return if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1 / 2.4f) - 0.055f
    }

    fun applyContrast(c: Float, contrast: Float): Float =
        (c - 0.5f) * (1f + contrast) + 0.5f

    /** 由控制点（x,y ∈ 0..255，按 x 升序）构建 256 项亮度 LUT。 */
    fun buildLumaLut(points: List<Pair<Int, Int>>): IntArray {
        val pts = if (points.isEmpty()) listOf(0 to 0, 255 to 255) else points.sortedBy { it.first }
        val lut = IntArray(256)
        var seg = 0
        for (i in 0..255) {
            while (seg < pts.lastIndex && i > pts[seg + 1].first) seg++
            val (x0, y0) = pts[seg]
            val (x1, y1) = if (seg < pts.lastIndex) pts[seg + 1] else pts[seg]
            val t = if (x1 == x0) 0f else (i - x0).toFloat() / (x1 - x0)
            lut[i] = (y0 + (y1 - y0) * t.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        }
        return lut
    }

    // ═══════════════════════════════════════════════════════════════
    // 立体分区的 LUT 构建器（`docs/TONING_DESIGN.md` §2.1 / §2.3）
    //
    // 这些函数把「若干个滑块」在**渲染开始前**塌缩成一张查表，逐像素路径上就只剩
    // 查表与加减。放在 [ColorMath] 而不是 [PixelProgram] 里的唯一理由是**可 JVM 单测**：
    // 权重函数的形状（哪里开始起作用、会不会串扰）是肉眼极难从真机上判断的东西。
    // ═══════════════════════════════════════════════════════════════

    /**
     * 四区影调的总强度系数。
     *
     * 加法会轻微压缩对比（把黑抬高一点、把白压低一点），所以每个滑块不按 1.0 满量程走，
     * 而是 0.35 —— 缺掉的对比由后面的对比度 / 曲线补回。真机比对认为「滑块不够劲」时改这一个常量。
     */
    private const val TONE_ZONE_GAIN = 0.35f

    private fun gauss(x: Float, mu: Float, sigma: Float): Float {
        val d = (x - mu) / sigma
        return exp(-0.5f * d * d)
    }

    /** 黑场权重：L=0 处为 1，到 0.25 衰减为 0（只动最暗的一段）。 */
    private fun wBlack(l: Float): Float {
        val t = ((0.25f - l) / 0.25f).coerceIn(0f, 1f)
        return t * t
    }

    /** 阴影权重：峰在 L≈0.22。 */
    private fun wShadow(l: Float): Float = gauss(l, 0.22f, 0.20f)

    /** 高光权重：峰在 L≈0.78。 */
    private fun wHighlight(l: Float): Float = gauss(l, 0.78f, 0.20f)

    /** 白场权重：L=1 处为 1，到 0.75 衰减为 0（只动最亮的一段）。 */
    private fun wWhite(l: Float): Float {
        val t = ((l - 0.75f) / 0.25f).coerceIn(0f, 1f)
        return t * t
    }

    /**
     * 四区影调响应 LUT（256 项，索引 = `round(L * 255)`）。
     *
     * 返回的是**该亮度处要加到 R/G/B 上的偏移量**（三通道同加，保持色相）。
     * 四个区用交叠的权重而不是硬分区：硬分区会在分区边界上留下可见的亮度台阶
     * （渐变的天空最先暴露），这是所有「分区调色」实现里最常见的破绽。
     */
    fun buildToneLut(blacks: Float, shadows: Float, highlights: Float, whites: Float): FloatArray {
        val out = FloatArray(256)
        for (i in 0..255) {
            val l = i / 255f
            out[i] = TONE_ZONE_GAIN * (
                blacks * wBlack(l) +
                    shadows * wShadow(l) +
                    highlights * wHighlight(l) +
                    whites * wWhite(l)
                )
        }
        return out
    }

    /**
     * `hue`（0..1）→ 满饱和满明度的单位色向量，写入 [out]（长度 ≥ 3）。
     *
     * 只在构建 LUT 时调用（每次渲染至多 4 次），所以这里允许写外部缓冲、不追求零分配。
     */
    fun hueToRgbUnit(hue: Float, out: FloatArray) {
        val h = (((hue % 1f) + 1f) % 1f) * 6f
        val sec = h.toInt()
        val f = h - sec
        val q = 1f - f
        when (sec) {
            0 -> { out[0] = 1f; out[1] = f; out[2] = 0f }
            1 -> { out[0] = q; out[1] = 1f; out[2] = 0f }
            2 -> { out[0] = 0f; out[1] = 1f; out[2] = f }
            3 -> { out[0] = 0f; out[1] = q; out[2] = 1f }
            4 -> { out[0] = f; out[1] = 0f; out[2] = 1f }
            else -> { out[0] = 1f; out[1] = 0f; out[2] = q }
        }
    }

    /**
     * 彩色分级 → 3 条 256 项 RGB 偏移 LUT。
     *
     * 两个刻意的决定：
     * - **减去 tint 自身的亮度**：否则「往红染」会连带把画面整体提亮，用户想调的是**色相**而不是亮度；
     * - **三个真实分区的权重归一化到和为 1**：不归一的话「阴影+中间调+高光全拉满」会叠成三倍强度，
     *   同一个滑块在拉满后还会互相放大，手感会变得不可预测。
     *   （`global` 不参与归一化 —— 它的语义就是「整幅均匀再加一层」。）
     */
    fun buildGradingLuts(g: ColorGrading): Array<FloatArray> {
        val out = Array(3) { FloatArray(256) }

        // blending 越大过渡越硬（sigma 越小）；balance 正方向把分区界线整体往亮部挪
        val sigma = 0.34f - 0.24f * g.blending.coerceIn(0f, 1f)
        val shift = g.balance.coerceIn(-1f, 1f) * 0.25f
        val c0 = 0.20f - shift
        val c1 = 0.50f - shift
        val c2 = 0.80f - shift

        val bands = arrayOf(g.shadows, g.midtones, g.highlights)
        val tint = Array(3) { FloatArray(3) }
        val kArr = FloatArray(3)
        val lArr = FloatArray(3)
        for (z in 0..2) {
            val b = bands[z]
            hueToRgbUnit(b.hue, tint[z])
            val tl = 0.2126f * tint[z][0] + 0.7152f * tint[z][1] + 0.0722f * tint[z][2]
            tint[z][0] -= tl
            tint[z][1] -= tl
            tint[z][2] -= tl
            kArr[z] = b.sat.coerceIn(0f, 1f) * 0.20f
            lArr[z] = b.lum.coerceIn(-1f, 1f) * 0.25f
        }

        val gt = FloatArray(3)
        hueToRgbUnit(g.global.hue, gt)
        val gtl = 0.2126f * gt[0] + 0.7152f * gt[1] + 0.0722f * gt[2]
        gt[0] -= gtl
        gt[1] -= gtl
        gt[2] -= gtl
        val gk = g.global.sat.coerceIn(0f, 1f) * 0.20f
        val gl = g.global.lum.coerceIn(-1f, 1f) * 0.25f

        for (i in 0..255) {
            val l = i / 255f
            var w0 = gauss(l, c0, sigma)
            var w1 = gauss(l, c1, sigma)
            var w2 = gauss(l, c2, sigma)
            val sum = w0 + w1 + w2
            if (sum > 1e-6f) {
                w0 /= sum
                w1 /= sum
                w2 /= sum
            }
            val k0 = w0 * kArr[0]; val l0 = w0 * lArr[0]
            val k1 = w1 * kArr[1]; val l1 = w1 * lArr[1]
            val k2 = w2 * kArr[2]; val l2 = w2 * lArr[2]
            val dl = l0 + l1 + l2 + gl
            out[0][i] = tint[0][0] * k0 + tint[1][0] * k1 + tint[2][0] * k2 + gt[0] * gk + dl
            out[1][i] = tint[0][1] * k0 + tint[1][1] * k1 + tint[2][1] * k2 + gt[1] * gk + dl
            out[2][i] = tint[0][2] * k0 + tint[1][2] * k1 + tint[2][2] * k2 + gt[2] * gk + dl
        }
        return out
    }

    /**
     * 色相（整度 0..359）→ 相邻两个通道索引与混合权重。
     *
     * 返回 `(idxA, idxB, wB)`：`wB` 是第二个通道的权重，`idxA` 拿 `1 - wB`。
     * 逐像素路径只做 3 次数组读 + 2 次乘加，**不在这里做任何浮点色相运算** ——
     * 那是每晚一帧都要付 33MP 次的代价，「把能提前算的都提前算」正是 [PixelProgram] 存在的理由。
     *
     * 通道之间线性过渡（不做硬切）：硬切会在色相过渡带上留下可见的色块边界，
     * 渐变天空 / 肤色边缘最先暴露，而这两样恰好是本 App 的主场景。
     */
    fun buildHueBandLut(): Triple<IntArray, IntArray, FloatArray> {
        val centers = HslBands.CENTERS
        val n = centers.size
        val a = IntArray(360)
        val b = IntArray(360)
        val w = FloatArray(360)
        for (d in 0..359) {
            var i0 = n - 1
            for (i in 0 until n) {
                val lo = centers[i]
                var hi = centers[(i + 1) % n]
                if (hi <= lo) hi += 360f
                var dd = d.toFloat()
                if (dd < lo) dd += 360f
                if (dd >= lo && dd < hi) {
                    i0 = i
                    break
                }
            }
            val next = (i0 + 1) % n
            val lo = centers[i0]
            var hi = centers[next]
            if (hi <= lo) hi += 360f
            var dd = d.toFloat()
            if (dd < lo) dd += 360f
            val t = ((dd - lo) / (hi - lo)).coerceIn(0f, 1f)
            a[d] = i0
            b[d] = next
            w[d] = t
        }
        return Triple(a, b, w)
    }

    /**
     * 线性域软压肩的统一缩放系数（`docs/TONING_DESIGN.md` §5）。
     *
     * ## 为什么按**最大通道**取系数，而不是逐通道各压各的
     *
     * 逐通道压 = 逐通道裁顶：R 到白点被砍住、G/B 却还在按真实增益往上走 ⇒ **R:G 比例被压缩**，
     * 高光区朝青色偏，偏多少还取决于增益幅度（审计 M1-b，`LinearPipelineTest` 有刻画用例）。
     * 取一个**公共系数**乘到三通道上，比例天然不变，色相在高光里也保住了。
     *
     * @param mx   线性域三通道的最大值
     * @param knee 压肩起点（线性值）。`>= 白点` 时本函数恒返回 1（不压）
     * @param span `白点 - knee`，即压肩的可用空间
     * @return 三通道公共缩放系数，恒 ≤ 1
     */
    fun shoulderScale(mx: Float, knee: Float, span: Float): Float {
        if (mx <= knee || span <= 0f) return 1f
        val t = (mx - knee) / span
        // 有理压肩：t → ∞ 时 target → knee + span = 白点，所以 headroom 用不完但也永不越界
        val target = knee + (t / (1f + t)) * span
        return target / mx
    }

    // ═══════════════════════════════════════════════════════════════
    // 需要坐标的两个阶段（批次 3：暗角 / 颗粒）
    //
    // 与上面几节的区别只有一个：这里的输出**依赖像素在画面里的位置**。所以
    // `PixelProgram.applyLinear` / `applySrgb8` 从批次 3 起多收一对**长边归一化坐标** `(u, v)`。
    //
    // 坐标口径只有一条规矩，写在 `EditParams` 末尾「效果」那一段：
    // **`u = (x + 0.5f) / max(w, h)`、`v = (y + 0.5f) / max(w, h)`** —— 长边归一化，
    // 绝不出现「按各自的宽/高归一化」，更不出现像素坐标。
    //
    // 把「依赖位置的量」提前算成表（而不是逐像素算）依旧是这两级的设计主轴：
    // 暗角按**半径平方**索引成 1024 项的表（省掉逐像素开方），颗粒把噪声预烘成瓦片。
    // ═══════════════════════════════════════════════════════════════

    /**
     * 暗角响应表长度。索引 = `round(d² · (SIZE − 1))`。
     *
     * 用 **d²** 而不是 d 当索引，是为了把逐像素的 `sqrt` 彻底省掉：`d² = dx² + dy²` 只需
     * 两次乘加，而 `sqrt` 在 33MP 的导出上是几十毫秒的纯开销。代价是表的「径向分辨率」
     * 在近中心处偏粗（d 与 d² 在那里是非线性关系）—— 但近中心恰好是权重为 0、
     * 不该有任何变化的地方，所以这个代价落在空处。
     *
     * 1024 而非 256：过渡区（d ≈ 0.6）附近 d² 的一格 ≈ 0.0039，换算成权重台阶是 1%，
     * 乘 255 就是 2~3 级灰度 ⇒ 渐变天空会出色带。1024 把它压到 0.2 级，肉眼不可辨。
     */
    const val VIGNETTE_LUT_SIZE = 1024

    /**
     * 满量程时四角保留的**最低**亮度比例（= 1 − 0.8）。
     *
     * 不取 1.0（即不做到纯黑）：那是一个「拖到底就把照片毁掉」的滑块。
     * 真机若反馈「暗角不够狠」，改的是这一个常量，而不是放宽滑块量程。
     */
    private const val VIGNETTE_GAIN = 0.8f

    /**
     * 标准 smoothstep：`x ≤ e0 → 0`、`x ≥ e1 → 1`，中间走 `3t² − 2t³`。
     *
     * 用它而不是线性斜坡的唯一理由：它在两端的一阶导为 0 ⇒ 过渡的两端**没有折线**。
     * 线性斜坡在起点/终点各留一道可见的折痕，渐变天空最先暴露（与四区影调、
     * 色相通道不用硬切是同一条理由）。
     */
    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val span = e1 - e0
        if (span <= 1e-6f) return if (x < e0) 0f else 1f
        val t = ((x - e0) / span).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 暗角 → 1024 项**乘性**系数表（索引 = `round(d² · (SIZE−1))`，`d` ∈ [0,1] 是归一化半径）。
     *
     * ## 返回乘性系数而不是加法偏移
     *
     * 中心（`d = 0`）恒为 1 ⇒ **暗角永远不会动画面中心**，这是「暗角」与「影调四区」最本质的
     * 区别：四区是给整幅加同一个亮度偏移（能抬真黑），暗角是光学衰减（在 0 处恒为 0）。
     * 加法做不到「只压四角」——它会把纯黑的角落抬起来。
     *
     * ## 三组参数各自映射到哪一段
     *
     * ```
     * mid  = 1 − 0.80·(1 − midpoint)      半强度半径，0.20..1.00
     * half = 0.05 + 0.35·feather          过渡半宽，0.05..0.40
     * w(d) = smoothstep(clamp(mid−half, ≥0), min(mid+half, 1.2), d)
     * f(d) = 1 + amount·0.8·w(d)          正 = 提亮四角，负 = 压暗四角
     * ```
     *
     * 两个刻意的夹取：
     * - `mid − half` **下夹 0**：不夹的话 `midpoint = 0` + `feather = 1` 会让权重在中心处
     *   就已经大于 0 —— 表现为「调暗角，画面中心跟着一起变暗」，而用户要的是「只压四角」；
     * - `mid + half` **上夹 1.2**：允许过渡延伸到 `d = 1`（四角）之外，这样 feather 才真的
     *   能把四角「化开」，否则 feather 拉满时的四角会停在半强度。
     *
     * ## 四角的 `d` 恒等于 1
     *
     * `d²` 的归一化常数由 `PixelProgram` 按**画幅比例与圆度**算出（见其 `vigInvNorm2`），
     * 保证四个角（不是长边中点）恰好落在 `d = 1`。所以本表只需覆盖 `d ∈ [0, 1]`。
     */
    fun buildVignetteLut(amount: Float, midpoint: Float, feather: Float): FloatArray {
        val a = amount.coerceIn(-1f, 1f) * VIGNETTE_GAIN
        val mid = 1f - 0.80f * (1f - midpoint.coerceIn(0f, 1f))
        val half = 0.05f + 0.35f * feather.coerceIn(0f, 1f)
        val lo = (mid - half).coerceAtLeast(0f)
        val hi = (mid + half).coerceAtMost(1.2f)
        val last = (VIGNETTE_LUT_SIZE - 1).toFloat()
        val out = FloatArray(VIGNETTE_LUT_SIZE)
        for (i in 0 until VIGNETTE_LUT_SIZE) {
            out[i] = 1f + a * smoothstep(lo, hi, sqrt(i / last))
        }
        return out
    }
}

/**
 * 颗粒噪声瓦片（`docs/TONING_DESIGN.md` §2.4）。
 *
 * ## 为什么是「预烘一张瓦片」而不是逐像素算噪声
 *
 * 两个都想要的东西逼出了这个结构：
 * - **确定性**：同一个位置的像素在相邻两帧必须拿到同一个噪声值，否则拖动滑块时整幅画面
 *   会「沸腾」（噪点每帧重掷）。`Random` 做不到，必须用 `(x, y)` 的**哈希**；
 * - **便宜**：哈希本身不贵，但「模糊一次让噪声像胶片颗粒而不是数码噪点」必须花邻居信息，
 *   逐像素做就成了邻域算子（本项目批次 4 才做的事）。
 *
 * 所以：把噪声**预烘**成一张 [SIZE]² 的瓦片，逐像素只做「一次乘 + 一次取整 + 一次查表」。
 * 模糊也在烘焙时按**环绕边界**做掉 ⇒ 瓦片左右上下相接无缝。
 *
 * ## 为什么「尺寸」不体现在瓦片里，而体现在采样密度里
 *
 * 瓦片只有一张、进程内建一次（`grainAmount = 0` 的用户**一次都不付**）。颗粒的粗细由
 * **采样步长**决定：`grainSize` → 「长边上铺多少格」→ 相邻像素在瓦片里跨多远。
 * 这样同一张瓦片能给出从细到粗的一整段，而无需为每个尺寸烘一张。
 *
 * ⚠️ 采样密度必须按**长边**算（`u` 是长边归一化坐标），否则预览与导出的颗粒粗细不一致 ——
 * 这是这一批最容易写错、且真机上「看起来只是有点怪」从而极难发现的地方。
 */
object GrainNoise {

    /** 瓦片边长（纹素）。512² = 256KB（`ByteArray`），一次烘焙在毫秒级。 */
    const val SIZE = 512

    /** 瓦片内横向的噪声「格」数。 */
    const val CELLS_PER_TILE = 128

    /** 一格占几个纹素。4 ⇒ 相邻纹素强相关（模糊后），格与格之间才独立。 */
    const val TEXELS_PER_CELL = SIZE / CELLS_PER_TILE

    /** 取模掩码。`SIZE` 是 2 的幂 ⇒ 用 `and` 代替 `%`，且天然处理负数（环绕）。 */
    const val MASK = SIZE - 1

    /** [EditParams.grainSize] = 0 / 1 时，整幅画面（**长边**）上铺多少格。对数插值。 */
    private const val CELLS_FINE = 1200f
    private const val CELLS_COARSE = 300f

    /** 烘焙后把噪声的**标准差**归一化到这个值（见 [build] 的说明）。 */
    private const val TARGET_STD = 0.33

    /**
     * `u → 瓦片下标` 的缩放系数：`idx = (u * sampleScale(size)).toInt() and MASK`。
     *
     * 三个因子的含义：`u`（长边归一化坐标，整幅画面 0..1）× 长边上要铺的格数 × 每格纹素数。
     * `grainSize` 0→1 对数插值：**0 = 细（1200 格）→ 1 = 粗（300 格）**，即「格数越少颗粒越粗」。
     */
    fun sampleScale(size: Float): Float =
        CELLS_FINE * (CELLS_COARSE / CELLS_FINE).pow(size.coerceIn(0f, 1f)) * TEXELS_PER_CELL

    /**
     * 瓦片本体。`by lazy` ⇒ 第一次真正用到颗粒时才烘焙
     * （默认 `grainAmount = 0` 的用户一次都不付；`by lazy` 默认线程安全，
     * 而 `EditEngine` 确实会从多个渲染线程同时调用进来）。
     */
    val tile: ByteArray by lazy { build() }

    /** 整数哈希（xorshift 混合）。取高位做噪声，`(x, y)` 相同的输入永远得到相同的输出。 */
    private fun hash(x: Int, y: Int): Int {
        var h = x * 0x27d4eb2d + y * 0x165667b1
        h = h xor (h ushr 15)
        h *= 0x2545f491
        h = h xor (h ushr 13)
        h *= 0x27d4eb2d
        return h xor (h ushr 16)
    }

    private fun build(): ByteArray {
        val n = SIZE * SIZE
        val a = FloatArray(n)
        for (y in 0 until SIZE) {
            val row = y * SIZE
            for (x in 0 until SIZE) {
                a[row + x] = hash(x, y).toFloat() / 2147483648f      // ∈ [−1, 1)
            }
        }
        // 两次 3×3 盒式模糊（**环绕**取样 ⇒ 瓦片四边无缝）。白噪声直接采样像「数码噪点」，
        // 模糊之后才像胶片颗粒；同时它把内容带限到 ~2 纹素，细颗粒档位下的欠采样才不会
        // 变成「另一种噪点」（结构与分辨率有关的那种）。
        val b = FloatArray(n)
        box3(a, b)
        box3(b, a)
        // 归一化：两次 3×3 盒式模糊会把方差压掉约两个数量级（÷81），不归一的话颗粒几乎看不见。
        // 目标 std 取 0.33（不是 1.0）：这样 ±1 相当于 ±3σ，裁顶只发生在约 0.3% 的纹素上；
        // 若归一到 std = 1，则约 1/3 的纹素会被裁平，颗粒会出现成片的死白/死黑。
        var s = 0.0
        var s2 = 0.0
        for (v in a) {
            s += v
            s2 += v.toDouble() * v
        }
        val mean = (s / n).toFloat()
        val k = (TARGET_STD / sqrt((s2 / n - mean.toDouble() * mean).coerceAtLeast(1e-12))).toFloat()
        val out = ByteArray(n)
        for (i in 0 until n) {
            out[i] = ((a[i] - mean) * k * 127f).roundToInt().coerceIn(-127, 127).toByte()
        }
        return out
    }

    /** 3×3 盒式模糊，**环绕**边界（`and MASK` 对负数同样成立）⇒ 瓦片拼接无可见缝。 */
    private fun box3(src: FloatArray, dst: FloatArray) {
        for (y in 0 until SIZE) {
            val y0 = ((y - 1) and MASK) * SIZE
            val y1 = y * SIZE
            val y2 = ((y + 1) and MASK) * SIZE
            for (x in 0 until SIZE) {
                val x0 = (x - 1) and MASK
                val x2 = (x + 1) and MASK
                dst[y1 + x] = (
                    src[y0 + x0] + src[y0 + x] + src[y0 + x2] +
                        src[y1 + x0] + src[y1 + x] + src[y1 + x2] +
                        src[y2 + x0] + src[y2 + x] + src[y2 + x2]
                    ) * (1f / 9f)
            }
        }
    }
}
