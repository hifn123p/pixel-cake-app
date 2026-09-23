package com.hifn.pixelcake.core.edit

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 由 [EditParams] 预编译出的逐像素运算程序。
 *
 * 存在的意义是性能：此前 `processPixel` 每像素返回 `Triple`，内部四个子函数再各返回
 * 一个 `Triple`，33MP 导出约 1.6 亿次对象分配 + 装箱，与「亚秒级」的注释自相矛盾
 * （FIX_LIST F06）。这里把参数在 **渲染开始前** 塌缩成标量字段**与查表**，逐像素路径上
 * 只有浮点乘加与查表，零分配、零装箱。
 *
 * ## 塌缩成查表，而不是逐像素算权重（批次 1 / 2 / 3 的关键设计）
 *
 * 批次 1 / 2 / 3 把可调项从 10 项扩到 71 项，而**逐像素代价必须基本不变** —— 否则
 * 「加参数」等于「把预览拖慢」，用户会直接感受到「变卡了」。做法是：凡是「依赖亮度」的
 * 分区权重（影调四区、彩色分级四区）、「依赖色相」的通道归属（HSL 8 通道）、
 * 「依赖位置」的暗角响应，一律在构造期算成表：
 *
 * | 阶段 | 逐像素代价 |
 * |---|---|
 * | 影调四区（4 个滑块） | 1 次查表 + 3 次加法 |
 * | 彩色分级（4 区 × H/S/L + 混合 + 平衡） | 3 次查表 + 3 次加法 |
 * | HSL 混色（8 通道 × H/S/L） | 3 次查表 + 6 次乘加 + 一次 HSV 往返 |
 * | 分通道曲线（3 条） | 3 次查表 |
 * | 暗角（批次 3） | 4 次乘加 + 1 次查表（**无开方**） |
 * | 颗粒（批次 3） | 2 次乘 + 2 次取整 + 2 次查表 |
 *
 * ## 每个阶段在参数中性时**整段跳过**
 *
 * `toneLut` / `redLut` / `vigLut` / `grainTile` 等为 `null` 就表示「该参数是中性值，不执行」。
 * 这不是微优化：绝大多数用户绝大多数时候只动其中一两个滑块，而分段跳过的分支
 * 预测得极好（分支条件对一整帧恒定）。**没有这一步，「加了 61 个参数」会变成
 * 「所有照片都变慢」，哪怕用户一个都没碰。**
 *
 * ## ⚠️ 批次 3 起，管线多了一个自变量：**坐标**
 *
 * 暗角与颗粒要看「这个像素在画面里的哪儿」，所以 [applyLinear] / [applySrgb8] 从批次 3 起
 * 多收一对**长边归一化坐标** `(u, v)`：
 *
 *     u = (x + 0.5f) / max(w, h)        v = (y + 0.5f) / max(w, h)
 *
 * 口径只有这一条，理由见 `EditParams` 末尾「效果」那一段（同一组几何量在
 * 预览 / 代理 / 导出三种尺寸下占画面的**比例**必须相同）。
 *
 * 画面尺寸只在**构造期**用来算暗角的中心与归一化常数，逐像素路径上不存在任何除法。
 *
 * ⚠️ `u` / `v` **刻意不给默认值**。给一个 `0.5f` 之类的默认值，会让「某个调用点忘了传坐标」
 * 退化成「暗角 / 颗粒静默失效」——而那正是这一批最需要防的错误。**编译不过比跑错好。**
 *
 * ## 逐像素路径的**零分配**是不可退让的约束
 *
 * 因此 HSL 的 HSV 往返**内联在 [finish] 里、只用局部变量**：
 * - 不能用 `FloatArray(3)` 之类的临时缓冲（那正是 `Triple` 方案的翻版）；
 * - **更不能**用一个挂在实例上的共享 scratch 数组 —— `EditEngine.runBand` 会把同一个
 *   `PixelProgram` 实例交给多个线程同时调用，共享可变缓冲是数据竞争。
 *
 * 白平衡与曝光都折叠进「线性 16-bit -> sRGB」那一步的缩放系数里。
 */
class PixelProgram(
    params: EditParams,
    /**
     * 覆盖亮度曲线的 LUT（导出/测试可传入预构建的实例）。传 `null` 时按
     * [EditParams.lumaPoints] 现建。
     */
    lumaLutOverride: IntArray? = null,
    /**
     * 画面尺寸（像素）。**只**用于暗角几何：坐标是长边归一化的，所以「画面中心在哪」
     * 与「四角离中心多远」都取决于画幅比例。
     *
     * 默认 1×1（正方形）⇒ 单测不传也能得到自洽的几何（中心 = (0.5, 0.5)、四角 = 4 个对称点）。
     * **真实渲染三条路径（`EditEngine`）都必须传真尺寸**，否则暗角在 3:2 画幅上会算成正圆轮廓。
     */
    frameW: Int = 1,
    frameH: Int = 1
) {
    // ——————————————— 线性域 ———————————————

    private val gainR: Float
    private val gainG: Float
    private val gainB: Float

    /** 高光压肩起点与可用空间（线性值）。见 [ColorMath.shoulderScale]。 */
    private val shoulderKnee: Float
    private val shoulderSpan: Float

    // ——————————————— sRGB 域 ———————————————

    /** 影调四区响应（256 项）。`null` = 四个滑块都是中性值 ⇒ 不执行。 */
    private val toneLut: FloatArray?

    private val contrast: Float

    private val vibrance: Float
    private val saturation: Float
    private val dehazeOffset: Float
    private val dehazeInv: Float

    private val lumaLut: IntArray = lumaLutOverride ?: ColorMath.buildLumaLut(params.lumaPoints)
    private val lumaActive: Boolean = !isIdentityLut(lumaLut)
    private val redLut: IntArray? = curveOrNull(params.redPoints)
    private val greenLut: IntArray? = curveOrNull(params.greenPoints)
    private val blueLut: IntArray? = curveOrNull(params.bluePoints)

    private val hslHue: FloatArray?
    private val hslSat: FloatArray?
    private val hslLum: FloatArray?

    /** 色相（整度）→ 相邻两通道索引 + 第二通道权重。三者要么同时非空、要么同时为 null。 */
    private val bandA: IntArray?
    private val bandB: IntArray?
    private val bandW: FloatArray?

    private val gradeR: FloatArray?
    private val gradeG: FloatArray?
    private val gradeB: FloatArray?

    private val lutId: String = params.lutId
    private val lutIntensity: Float = params.lutIntensity.coerceIn(0f, 1f)

    // ——————————————— 需要坐标的两级（批次 3）———————————————

    /** 暗角响应（[ColorMath.VIGNETTE_LUT_SIZE] 项，索引 = d²）。`null` = amount 为 0 ⇒ 不执行。 */
    private val vigLut: FloatArray?

    /** 长边归一化坐标系下的画面中心（= 半宽 / 长边、半高 / 长边）。 */
    private val vigCx: Float
    private val vigCy: Float

    /** 圆度系数：+1 → `kx = ky = 1`（像素正圆），−1 → 轮廓贴住画幅（见 [init] 的推导）。 */
    private val vigKx: Float
    private val vigKy: Float

    /** `1 / (四角处的 dx² + dy²)`：乘上去让**四角的 d² 恒等于 1** ⇒ 响应表只需覆盖 d ∈ [0,1]。 */
    private val vigInvNorm2: Float

    /** 颗粒瓦片。`null` = grainAmount 为 0 ⇒ 不执行。 */
    private val grainTile: ByteArray?

    /**
     * 瓦片字节（0..255）→ **已经乘好强度与增益**的带符号浮点。
     *
     * 把 `pow`（[EditParams.grainRoughness] 的幂整形）与强度一起折叠进这张 256 项的表里：
     * 逐像素就只剩一次查表，而不是一次 `powf`（33MP 上是几十毫秒）。
     * 瓦片非空时本表必定非空；瓦片为 `null` 时它是长度 0 的占位。
     */
    private val grainShape: FloatArray

    /** `u → 瓦片下标` 的缩放（见 [GrainNoise.sampleScale]）。瓦片为 `null` 时是 0。 */
    private val grainScale: Float

    init {
        val ev = 2f.pow(params.exposureEv)
        // 与旧 applyWhiteBalance 语义一致：色温>0 偏暖(增 R 减 B)；色调>0 偏品红(增 R/B 减 G)
        val wr = 1f + params.temperature * 0.28f + params.tint * 0.14f
        val wg = 1f - params.tint * 0.16f
        val wb = 1f - params.temperature * 0.28f + params.tint * 0.08f
        gainR = wr * ev
        gainG = wg * ev
        gainB = wb * ev

        // 高光压肩（`docs/TONING_DESIGN.md` §5，修审计 M1）。
        //
        // 默认（recovery = 0）只留一条**极窄**的肩：纯白 255 会落到 254（掉一级，肉眼不可辨），
        // 但它换来的是「高光里的三通道比例不再被破坏」—— 逐通道各压各的会让 R:G 被压缩、
        // 高光朝青色偏（M1-b，`LinearPipelineTest` 有刻画用例）。这一步是**默认开启**的，
        // 因为「偏色」不是用户能通过某个滑块关掉的东西，它就是个 bug。
        //
        // 拉高 recovery 则把起点一路下移（0.98 → 0.40 白点），这才真正给出「找回被推爆的高光」
        // 的 headroom。**不做成默认就下移到 0.40**：那会让所有既有预设的观感整体改变。
        val recovery = params.highlightRecovery.coerceIn(0f, 1f)
        shoulderKnee = 65535f * (0.98f - 0.58f * recovery)
        shoulderSpan = (65535f - shoulderKnee).coerceAtLeast(1f)

        toneLut = if (params.blacks == 0f && params.shadows == 0f &&
            params.highlights == 0f && params.whites == 0f
        ) {
            null
        } else {
            ColorMath.buildToneLut(params.blacks, params.shadows, params.highlights, params.whites)
        }

        contrast = params.contrast

        vibrance = params.vibrance
        saturation = params.saturation
        dehazeOffset = -params.dehaze.coerceIn(-1f, 1f) * 0.10f
        dehazeInv = if (dehazeOffset == 0f) 1f else 1f / (1f - dehazeOffset)

        val hsl = params.hsl
        if (hsl.isIdentity) {
            hslHue = null
            hslSat = null
            hslLum = null
            bandA = null
            bandB = null
            bandW = null
        } else {
            hslHue = FloatArray(HslBands.COUNT) { hsl.hueOf(it) }
            hslSat = FloatArray(HslBands.COUNT) { hsl.satOf(it) }
            hslLum = FloatArray(HslBands.COUNT) { hsl.lumOf(it) }
            val bands = ColorMath.buildHueBandLut()
            bandA = bands.first
            bandB = bands.second
            bandW = bands.third
        }

        val grading = params.grading
        if (grading.isNeutral) {
            gradeR = null
            gradeG = null
            gradeB = null
        } else {
            val luts = ColorMath.buildGradingLuts(grading)
            gradeR = luts[0]
            gradeG = luts[1]
            gradeB = luts[2]
        }

        // ———— 批次 3：暗角 ————
        //
        // 几何一律在**长边归一化**坐标系里算（`invL = 1 / max(w, h)`）。三条渲染路径
        // （预览 / 代理 / 导出）传进来的 `(u, v)` 是同一口径，所以这里算出的中心、
        // 四角距离、归一化常数在三种尺寸下**逐个相同** ⇒ 暗角形状不随分辨率漂移。
        if (params.vignetteAmount == 0f) {
            vigLut = null
        } else {
            vigLut = ColorMath.buildVignetteLut(
                params.vignetteAmount, params.vignetteMidpoint, params.vignetteFeather
            )
        }
        val invL = 1f / max(frameW, frameH).coerceAtLeast(1).toFloat()
        vigCx = 0.5f * frameW * invL
        vigCy = 0.5f * frameH * invL
        // 圆度：roundness = +1 → kx = ky = 1（在像素里是**正圆**）；−1 → kx = w/L、ky = h/L
        // （轮廓**贴住画幅**：长边中点与四角的距离几乎一样远）。
        val r01 = (params.vignetteRoundness.coerceIn(-1f, 1f) + 1f) * 0.5f
        val aspectX = frameW * invL
        val aspectY = frameH * invL
        vigKx = aspectX + (1f - aspectX) * r01
        vigKy = aspectY + (1f - aspectY) * r01
        val corner2 = vigCx * vigCx * vigKx * vigKx + vigCy * vigCy * vigKy * vigKy
        vigInvNorm2 = if (corner2 <= 1e-9f) 0f else 1f / corner2

        // ———— 批次 3：颗粒 ————
        if (params.grainAmount <= 0f) {
            grainTile = null
            grainShape = EMPTY_SHAPE
            grainScale = 0f
        } else {
            grainTile = GrainNoise.tile
            grainScale = GrainNoise.sampleScale(params.grainSize)
            // roughness = 0.5 恰好是**恒等**（指数 = 1）：0 → 更软（1.6），1 → 更硬（0.4）。
            // 幂整形必须**带符号**做：`(−0.5f).pow(0.4f)` 是 NaN（负数的非整数次幂无定义），
            // 而噪声有一半是负的。
            val exp = 1.6f - 1.2f * params.grainRoughness.coerceIn(0f, 1f)
            val amp = params.grainAmount.coerceIn(0f, 1f) * GRAIN_GAIN
            grainShape = FloatArray(256) { b ->
                val n = (b - 128) / 128f
                (if (n < 0f) -(-n).pow(exp) else n.pow(exp)) * amp
            }
        }
    }

    /**
     * 输入 16-bit 线性 RGB（0..65535）+ **长边归一化坐标** `(u, v)`，返回打包好的 0xAARRGGBB。
     *
     * ⚠️ 线性值**可以超过白点**（曝光增益会把它们推上去）。超出部分由 [ColorMath.shoulderScale]
     * 以「三通道公共系数」压回白点以内，**不是**逐通道各砍各的 —— 后者会破坏高光区的
     * 三通道比例即造成高光偏色。
     *
     * @param u `(x + 0.5f) / max(w, h)`，x 为**绝对**像素列号（不是带内下标）
     * @param v `(y + 0.5f) / max(w, h)`，y 为绝对像素行号
     */
    fun applyLinear(r16: Int, g16: Int, b16: Int, u: Float, v: Float): Int =
        processLinear(r16 * gainR, g16 * gainG, b16 * gainB, u, v)

    /** 输入 8-bit sRGB（0..255），先转线性再走同一条管线，保证与 RAW 同源。坐标口径同 [applyLinear]。 */
    fun applySrgb8(r8: Int, g8: Int, b8: Int, u: Float, v: Float): Int =
        processLinear(
            ColorMath.SRGB8_TO_LINEAR16[r8] * gainR,
            ColorMath.SRGB8_TO_LINEAR16[g8] * gainG,
            ColorMath.SRGB8_TO_LINEAR16[b8] * gainB,
            u, v
        )

    /**
     * 线性输入的公共前段：**公共系数软压肩 → sRGB 编码 → 交由 [finish] 走 sRGB 域管线**。
     *
     * 两条入口（`applyLinear` / `applySrgb8`）必须都走这里：一旦有人只改其中一条的
     * 前段处理，「预览所见 = 导出所得」就断了，而 `LinearPipelineTest.linearAndSrgbEntriesAgreeOnSameInput`
     * 正是这条不变量的护栏。
     *
     * `(u, v)` 只是原样透传给 [finish]（暗角 / 颗粒两级要用）—— 前段的两级是纯逐像素的，
     * **不要**把它们改成依赖坐标（那会让「白平衡与曝光在两种输入下同源」这条性质失效）。
     */
    private fun processLinear(rl: Float, gl: Float, bl: Float, u: Float, v: Float): Int {
        var r = rl
        var g = gl
        var b = bl
        val mx = max(r, max(g, b))
        if (mx > shoulderKnee) {
            val k = ColorMath.shoulderScale(mx, shoulderKnee, shoulderSpan)
            r *= k
            g *= k
            b *= k
        }
        return finish(
            ColorMath.linear16ToSrgb(r),
            ColorMath.linear16ToSrgb(g),
            ColorMath.linear16ToSrgb(b),
            u, v
        )
    }

    /**
     * sRGB 域管线。顺序见 `ColorMath` 的类文档 —— **顺序本身就是观感的一部分**。
     *
     * 每个阶段都用「参数中性 ⇒ 整段跳过」的形式写，这是「加了 61 个参数但默认不变慢」的
     * 全部秘密所在。⚠️ 跳过条件必须与构造期的 `null` 判断**逐一对应**：
     * 构造期给了表、这里忘了加分支，等于白算；构造期给了 `null`、这里没判空，直接 NPE。
     *
     * 前 11 级是**纯逐像素**的（只看 r/g/b）；末两级（暗角 / 颗粒，批次 3）额外需要
     * 长边归一化坐标 `(u, v)`。这就是为什么 [applyLinear] / [applySrgb8] 必须收坐标：
     * 它们不是给前 11 级用的，而是给这条管线的最后两级用的。
     */
    private fun finish(r0: Float, g0: Float, b0: Float, u: Float, v: Float): Int {
        var r = r0
        var g = g0
        var b = b0

        // ── 影调四区（黑场 / 阴影 / 高光 / 白场）──────────────────────
        // 三通道加同一个偏移 ⇒ 色相与饱和度都不变，只动明暗。加法（而非乘法）才能抬起真黑：
        // 乘法在 0 处恒为 0，拉不动纯黑。
        val tl = toneLut
        if (tl != null) {
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val d = tl[(lum * 255f + 0.5f).toInt().coerceIn(0, 255)]
            r += d
            g += d
            b += d
        }

        // ── 对比度 ────────────────────────────────────────────────
        if (contrast != 0f) {
            r = ColorMath.applyContrast(r, contrast)
            g = ColorMath.applyContrast(g, contrast)
            b = ColorMath.applyContrast(b, contrast)
        }
        // 夹一次：以下多个阶段都要拿 r/g/b 算「当前亮度」并当 LUT 索引。越界既会让偏移量
        // 算错，也可能让索引算出非法值 —— 而 `.coerceIn` 会**静默**把它修正成一个看似合理
        // 的结果，从而把「算错了」伪装成「算对了」。
        r = r.coerceIn(0f, 1f)
        g = g.coerceIn(0f, 1f)
        b = b.coerceIn(0f, 1f)

        // ── 去朦胧（近似版：抬黑点 + 线性拉伸）──────────────────────
        // 完整版需要暗通道先验估雾浓度，属邻域算子（批次 4）。这里给的是「黑点 + 对比」的
        // 一阶近似：正 dehaze 压黑点（去雾），负 dehaze 抬黑点（加雾）。
        if (dehazeOffset != 0f) {
            r = ((r - dehazeOffset) * dehazeInv).coerceIn(0f, 1f)
            g = ((g - dehazeOffset) * dehazeInv).coerceIn(0f, 1f)
            b = ((b - dehazeOffset) * dehazeInv).coerceIn(0f, 1f)
        }

        // ── 亮度曲线 ──────────────────────────────────────────────
        if (lumaActive) {
            val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            val idx = (lum * 255f + 0.5f).toInt().coerceIn(0, 255)
            val target = lumaLut[idx] / 255f
            // 按「曲线前后亮度之比」整体缩放 ⇒ 保持色相。lum→0 时比值无意义，退回 1（不动）。
            val ratio = if (lum <= 1e-4f) 1f else target / lum
            r = (r * ratio).coerceIn(0f, 1f)
            g = (g * ratio).coerceIn(0f, 1f)
            b = (b * ratio).coerceIn(0f, 1f)
        }

        // ── 自然饱和度：按当前饱和度**反比**加权 ────────────────────
        // 已经很艳的地方几乎不动 ⇒ 肤色（本来就偏饱和）不会被推成塑料橘，而灰调背景会先亮起来。
        // 这正是它与「饱和度」的分工，也是它必须排在饱和度**之前**的原因。
        if (vibrance != 0f) {
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val chroma = if (mx <= 1e-5f) 0f else (mx - mn) / mx
            val f = 1f + vibrance * (1f - chroma)
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            r = (l + (r - l) * f).coerceIn(0f, 1f)
            g = (l + (g - l) * f).coerceIn(0f, 1f)
            b = (l + (b - l) * f).coerceIn(0f, 1f)
        }

        // ── 饱和度 ────────────────────────────────────────────────
        if (saturation != 0f) {
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val f = 1f + saturation
            r = (l + (r - l) * f).coerceIn(0f, 1f)
            g = (l + (g - l) * f).coerceIn(0f, 1f)
            b = (l + (b - l) * f).coerceIn(0f, 1f)
        }

        // ── HSL 混色（8 通道 × H/S/L）──────────────────────────────
        if (bandA != null) {
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val delta = mx - mn

            // Hue（度）。`mx == r` 这类浮点相等是安全的：mx 就是它们中的一个。
            val h = when {
                delta <= 1e-6f -> 0f
                mx == r -> {
                    val x = 60f * (((g - b) / delta) % 6f)
                    if (x < 0f) x + 360f else x
                }
                mx == g -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }

            // 通道归属查表（3 次读 + 2 次乘加），不做任何浮点色相运算
            val deg = h.toInt().coerceIn(0, 359)
            val i0 = bandA!![deg]
            val i1 = bandB!![deg]
            val w1 = bandW!![deg]
            val hArr = hslHue!!
            val sArr = hslSat!!
            val lArr = hslLum!!
            val dh = hArr[i0] + (hArr[i1] - hArr[i0]) * w1
            val ds = sArr[i0] + (sArr[i1] - sArr[i0]) * w1
            val dl = lArr[i0] + (lArr[i1] - lArr[i0]) * w1

            val h2 = (((h + dh * HslBands.HUE_SWING_DEG) % 360f) + 360f) % 360f
            val s0 = if (mx <= 1e-6f) 0f else delta / mx
            val s2 = (s0 * (1f + ds)).coerceIn(0f, 1f)
            val v2 = (mx + dl * 0.25f).coerceIn(0f, 1f)

            // HSV → RGB：内联 + 只用局部变量（零分配约束，见类文档）
            val h6 = h2 / 60f
            val sec = h6.toInt()
            val fr = h6 - sec
            val p = v2 * (1f - s2)
            val q = v2 * (1f - s2 * fr)
            val t = v2 * (1f - s2 * (1f - fr))
            when (sec) {
                0 -> {
                    r = v2; g = t; b = p
                }
                1 -> {
                    r = q; g = v2; b = p
                }
                2 -> {
                    r = p; g = v2; b = t
                }
                3 -> {
                    r = p; g = q; b = v2
                }
                4 -> {
                    r = t; g = p; b = v2
                }
                else -> {
                    r = v2; g = p; b = q
                }
            }
        }

        // ── 彩色分级（阴影 / 中间调 / 高光 / 全局）────────────────────
        val gr = gradeR
        if (gr != null) {
            val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            val idx = (lum * 255f + 0.5f).toInt().coerceIn(0, 255)
            r = (r + gr[idx]).coerceIn(0f, 1f)
            g = (g + gradeG!![idx]).coerceIn(0f, 1f)
            b = (b + gradeB!![idx]).coerceIn(0f, 1f)
        }

        // ── 分通道曲线（R / G / B）────────────────────────────────
        val rl = redLut
        if (rl != null) {
            r = rl[(r * 255f + 0.5f).toInt().coerceIn(0, 255)] / 255f
            g = greenLut!![(g * 255f + 0.5f).toInt().coerceIn(0, 255)] / 255f
            b = blueLut!![(b * 255f + 0.5f).toInt().coerceIn(0, 255)] / 255f
        }

        // ── 内置 LUT ──────────────────────────────────────────────
        if (lutIntensity > 0f && lutId != "none") {
            when (lutId) {
                "bw" -> {
                    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    r = l
                    g = l
                    b = l
                }
                "warm" -> {
                    r = (r * (1f + 0.16f * lutIntensity)).coerceIn(0f, 1f)
                    b = (b * (1f - 0.12f * lutIntensity)).coerceIn(0f, 1f)
                }
                "cool" -> {
                    r = (r * (1f - 0.12f * lutIntensity)).coerceIn(0f, 1f)
                    b = (b * (1f + 0.16f * lutIntensity)).coerceIn(0f, 1f)
                }
                "film" -> {
                    val c = 0.16f * lutIntensity
                    r = ColorMath.applyContrast((r * (1f + 0.08f * lutIntensity)).coerceIn(0f, 1f), c)
                    g = ColorMath.applyContrast(g, c)
                    b = ColorMath.applyContrast((b * (1f - 0.06f * lutIntensity)).coerceIn(0f, 1f), c)
                    val l2 = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val f2 = 1f + 0.12f * lutIntensity
                    r = l2 + (r - l2) * f2
                    g = l2 + (g - l2) * f2
                    b = l2 + (b - l2) * f2
                }
            }
        }

        // ── 暗角（批次 3）─────────────────────────────────────────
        // 乘法而非加法：暗角是**光学衰减**，中心恒为 1 ⇒ 天然不动画面中心。加法做不到
        // 「只压四角」—— 它会把纯黑的角落抬起来（那是影调四区，不是暗角）。
        // 整段跳过（`amount = 0` ⇒ vigLut 为 null）：没开暗角的用户一次乘加都不付。
        val vl = vigLut
        if (vl != null) {
            val dx = (u - vigCx) * vigKx
            val dy = (v - vigCy) * vigKy
            val d2 = (dx * dx + dy * dy) * vigInvNorm2
            // 钳位只防浮点误差（四角算出来是 1.0 ± 1ulp），不是用来掩盖算错的 ——
            // 真算错时会落在表的同一处、看上去「暗角形状有点怪」，而不是抛异常。
            val f = vl[(d2 * VIG_LAST + 0.5f).toInt().coerceIn(0, VIG_LAST_I)]
            r *= f
            g *= f
            b *= f
        }

        // ── 颗粒（批次 3）─────────────────────────────────────────
        // 三通道加**同一个**偏移 ⇒ 只动明暗，不悄悄改色相与饱和度（与影调四区同一条约定）。
        // 刻意排在暗角**之后**：真实胶片里颗粒长在乳剂上、暗角发生在镜头里，两者独立
        // ⇒ 被压暗的角落会「显得」颗粒更明显 —— 这正是胶片扫描件的观感。
        // 反过来做（颗粒再被暗角乘一遍）会让四角的颗粒一起变干净，那是数码味的。
        val gt = grainTile
        if (gt != null) {
            val gx = (u * grainScale).toInt() and GrainNoise.MASK
            val gy = (v * grainScale).toInt() and GrainNoise.MASK
            val d = grainShape[gt[gy * GrainNoise.SIZE + gx].toInt() and 0xff]
            r += d
            g += d
            b += d
        }

        val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    private companion object {
        /**
         * [EditParams.grainAmount] = 1 时的最大亮度偏移（±0.10，sRGB 0..1 域）。
         *
         * 与 [ColorMath.VIGNETTE_GAIN] 同理：真机若反馈「颗粒太猛 / 太弱」，改的是**这一个常量**，
         * 不要去动量程 —— 量程决定的是手感分辨率，改量程会让用户在别的地方失去精度。
         */
        const val GRAIN_GAIN = 0.10f

        /** 颗粒关闭时的占位（长度 0）⇒ 逐像素路径上不必对 `grainShape` 二次判空。 */
        val EMPTY_SHAPE = FloatArray(0)

        /** 暗角表的末位下标（索引换算用）。 */
        const val VIG_LAST_I = ColorMath.VIGNETTE_LUT_SIZE - 1

        /** 同上，浮点形式：索引 = `round(d² · VIG_LAST)`。 */
        val VIG_LAST = VIG_LAST_I.toFloat()
    }
}

/** LUT 是否恒等（`lut[i] == i`）。恒等 ⇒ 该阶段可以整段跳过。 */
private fun isIdentityLut(lut: IntArray): Boolean {
    for (i in lut.indices) if (lut[i] != i) return false
    return true
}

/** 控制点 → 256 项 LUT；恒等曲线返回 `null`。 */
private fun curveOrNull(points: List<Pair<Int, Int>>): IntArray? {
    val lut = ColorMath.buildLumaLut(points)
    return if (isIdentityLut(lut)) null else lut
}
