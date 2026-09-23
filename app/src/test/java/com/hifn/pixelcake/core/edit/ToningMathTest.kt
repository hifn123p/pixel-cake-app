package com.hifn.pixelcake.core.edit

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 1 / 2 新增的**分区数学**护栏（`docs/TONING_DESIGN.md` §2.1 / §2.2 / §2.3）。
 *
 * ## 为什么这些必须单测，而不是只靠真机目视
 *
 * 新加的 45 个参数全部依赖「权重函数」：[ColorMath.buildToneLut] 的四区高斯、
 * [ColorMath.buildGradingLuts] 的分区归一化 + 亮度减除、[ColorMath.buildHueBandLut]
 * 的通道过渡。这些函数的**形状**（哪里开始起作用、会不会串扰、中性输入是否真的中性）
 * 在真机上极难判断 —— 用户只会看到「调了 A，B 也变了」，然后把结论归结为「手感怪」。
 * 数值断言能把「怪」钉成一个具体数字。
 *
 * 另一类断言是**不变量**：中性灰不得被染色、中性参数不得动画面。
 * 它们是把「加参数」与「改观感」隔开的防火墙 —— 只要这些不变量成立，
 * 「新增 45 个默认中性的参数」就**不可能**改变既有预设与既有照片的渲染结果。
 *
 * 本文件只覆盖**批次 1 / 2**（纯逐像素的分区数学）⇒ 坐标一律走 [srgbAtCenter]。
 * 批次 3 的暗角 / 颗粒**依赖坐标**，其几何护栏在 `EffectMathTest`。
 */
class ToningMathTest {

    // ——————————————— 线性域软压肩（M1 的修法） ———————————————

    @Test
    fun shoulderIsIdentityAtOrBelowKnee() {
        val knee = 60000f
        val span = 65535f - knee
        assertEquals(
            "压肩起点处必须恰好为 1（不压）",
            1.0,
            ColorMath.shoulderScale(knee, knee, span).toDouble(),
            1e-6
        )
        assertEquals(
            "压肩起点以下必须完全不动作",
            1.0,
            ColorMath.shoulderScale(1234f, knee, span).toDouble(),
            1e-6
        )
    }

    /**
     * 压肩的**唯一硬承诺**：无论输入多大，输出都落在 `(knee, 白点]` 内且系数恒 ≤ 1。
     *
     * 「用不完但也永不越界」是有理函数 `t/(1+t)` 的性质：`t → ∞` 时输出 → 白点但取不到。
     * 只要这条成立，编码表就永远不会遇到「索引超出 65535」那种必须硬钳的情况。
     */
    @Test
    fun shoulderAlwaysLandsBetweenKneeAndWhitePoint() {
        val white = 65535f
        val knee = white * 0.98f
        val span = white - knee
        for (mx in intArrayOf(65535, 70000, 131070, 500000)) {
            val k = ColorMath.shoulderScale(mx.toFloat(), knee, span)
            assertTrue("系数必须 ≤ 1，mx=$mx k=$k", k <= 1f)
            val out = mx * k
            assertTrue(
                "压肩后必须落在 (knee, 白点] 内，mx=$mx out=$out knee=$knee",
                out > knee && out <= white
            )
        }
    }

    // ——————————————— 四区影调 ———————————————

    /** 中性参数必须**逐项为 0**（不是「很小」）—— 这是 [PixelProgram] 整段跳过的依据。 */
    @Test
    fun toneLutIsExactlySilentWhenAllZonesAreNeutral() {
        val lut = ColorMath.buildToneLut(0f, 0f, 0f, 0f)
        assertEquals(256, lut.size)
        for (i in lut.indices) {
            assertEquals("中性影调在 i=$i 处必须恰好为 0", 0.0, lut[i].toDouble(), 1e-6)
        }
    }

    /**
     * 区的**两端互不串扰**：黑场只动最暗一段、白场只动最亮一段。
     *
     * 这两条断言的取值都是**精确 0** 而不是「接近 0」：`wBlack(1) = clamp((0.25−1)/0.25, 0, 1) = 0`、
     * `wWhite(0) = clamp((0−0.75)/0.25, 0, 1) = 0`，乘法之后就是 0。
     * 中间两区（阴影/高光是高斯）两端只会「很小」，所以不纳入精确断言。
     */
    @Test
    fun toneZoneEndsDoNotCrosstalk() {
        val blackOnly = ColorMath.buildToneLut(blacks = 1f, shadows = 0f, highlights = 0f, whites = 0f)
        assertTrue("黑场必须能抬起真黑（L=0 处为正偏移），实际 ${blackOnly[0]}", blackOnly[0] > 0f)
        assertEquals("黑场不得动白场（L=1 处权重恰为 0）", 0.0, blackOnly[255].toDouble(), 1e-6)

        val whiteOnly = ColorMath.buildToneLut(blacks = 0f, shadows = 0f, highlights = 0f, whites = 1f)
        assertTrue("白场必须能压低真白，实际 ${whiteOnly[255]}", whiteOnly[255] > 0f)
        assertEquals("白场不得动黑场（L=0 处权重恰为 0）", 0.0, whiteOnly[0].toDouble(), 1e-6)
    }

    // ——————————————— 彩色分级 ———————————————

    /** 中性分级必须**逐项为 0**：否则「只要打开分级 Tab」画面就变（那是不可接受的默认行为）。 */
    @Test
    fun gradingLutsAreExactlySilentWhenNeutral() {
        val luts = ColorMath.buildGradingLuts(ColorGrading())
        assertEquals(3, luts.size)
        for (c in 0..2) {
            for (i in 0..255) {
                assertEquals("中性分级 R/G/B#$c[$i] 必须恰好为 0", 0.0, luts[c][i].toDouble(), 1e-6)
            }
        }
    }

    /**
     * **中性灰不变量**：给某个分区染色时，该处的**亮度偏移必须约为 0**。
     *
     * 着色向量在构建时减掉了自身亮度（`tint − luma(tint)`），而 `0.2126 + 0.7152 + 0.0722 = 1`，
     * 所以「着色的同时整体提亮」不会发生。这条不成立的话，「阴影压青」会顺手把暗部也提亮，
     * 用户会以为自己在拖曝光滑块 —— `docs/TONING_DESIGN.md` §8 真机验收第 4 条测的就是它。
     */
    @Test
    fun gradingTintDoesNotChangeLuminance() {
        val g = ColorGrading(shadows = GradingBand(hue = 0.5f, sat = 1f)) // hue 0.5 = 180° = 青
        val luts = ColorMath.buildGradingLuts(g)
        val i = 51 // L = 0.20，正是阴影区的峰

        val luma = 0.2126f * luts[0][i] + 0.7152f * luts[1][i] + 0.0722f * luts[2][i]
        assertEquals("着色不该连带改变亮度", 0.0, luma.toDouble(), 0.005)

        assertTrue(
            "但三个通道不能都一样（否则等于没染色），实际 R=${luts[0][i]} B=${luts[2][i]}",
            abs(luts[0][i] - luts[2][i]) > 0.01f
        )
        assertTrue(
            "青色 = 减红加蓝绿：R 必须被压、G/B 必须被抬",
            luts[0][i] < 0f && luts[1][i] > 0f
        )
    }

    // ——————————————— HSL 混色的通道归属 ———————————————

    /**
     * 色相 → 相邻通道的映射必须**覆盖每一个整数度**：下标合法、权重在 0..1、
     * 且两个通道不同（相同就没有混合的意义）。
     *
     * 这是「8 个通道做成 360 项查表」的正确性边界：映射表建错一格，
     * 该色相上的像素就会被**错误通道**的滑块控制，而真机上表现为「某个颜色怎么调都不动」。
     */
    @Test
    fun hueBandLutCoversEveryDegree() {
        val (a, b, w) = ColorMath.buildHueBandLut()
        assertEquals(360, a.size)
        assertEquals(360, b.size)
        assertEquals(360, w.size)
        for (d in 0..359) {
            assertTrue("第一通道下标越界：d=$d a=${a[d]}", a[d] in 0 until HslBands.COUNT)
            assertTrue("第二通道下标越界：d=$d b=${b[d]}", b[d] in 0 until HslBands.COUNT)
            assertTrue("混合权重必须在 0..1：d=$d w=${w[d]}", w[d] in 0f..1f)
            assertTrue("相邻两通道必须不同：d=$d a=${a[d]} b=${b[d]}", a[d] != b[d])
        }
    }

    /**
     * **HSL 的不变量：只有饱和度参数时，中性灰必须逐位不变。**
     *
     * 中性灰没有色相（`delta == 0` ⇒ `h = 0` ⇒ 落到红色通道），若实现里漏了
     * 「`s = delta / mx`」这一层，红色通道的饱和度滑块就会**给所有灰色加红** ——
     * 而用户只是在调「红色通道的浓度」，这属于「A 滑块改了 B」的经典串扰。
     *
     * 断言用 `assertEquals(Int, Int)`（逐位相等，不设容差）：整条路径都是同一批浮点运算，
     * 容差会掩盖「差一档」的实现错误。
     */
    @Test
    fun hslSaturationDoesNotTintNeutralGray() {
        val gray = 128
        val neutral = PixelProgram(EditParams()).srgbAtCenter(gray, gray, gray)
        val desat = PixelProgram(EditParams(hsl = HslMix().withSat(0, -1f))).srgbAtCenter(gray, gray, gray)
        val oversat = PixelProgram(EditParams(hsl = HslMix().withSat(3, 1f))).srgbAtCenter(gray, gray, gray)

        assertEquals("中性灰无色相 ⇒ 调『红』通道饱和度不得改变它", neutral, desat)
        assertEquals("中性灰无色相 ⇒ 调『黄』通道饱和度不得改变它", neutral, oversat)
    }
}
