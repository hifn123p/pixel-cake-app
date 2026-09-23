package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探测用的中灰值。暗角是**乘性**的、颗粒是**加性**的，中灰对两者都最敏感。
 *
 * 定成文件级 `const`（而不是类成员）是为了能当默认参数 —— 顺带也不占测试类的实例状态。
 */
private const val GRAY = 128

/**
 * 批次 3（暗角 / 颗粒）的**几何**护栏。
 *
 * ## 为什么这一批的测试必须单独一个文件
 *
 * 前两批的参数只依赖当前像素，测试铁律是「同输入 → 同输出」。这一批引入了**坐标**，
 * 于是多了三条**只有几何才有的**不变量，而它们全都是真机上「看起来只是有点怪」、
 * 从而极难被发现的那类错误：
 *
 * 1. **中心不动**：暗角乘性系数在 `d = 0` 处必须恰好是 1（不是 0.99）。
 *    差一点就是「调暗角，画面整体变暗」——用户会以为是曝光滑块坏了。
 * 2. **四角归一**：`d²` 的归一化必须让**四个角**恰好落在 1（而不是长边中点）。
 *    归一错了，暗角轮廓就不再对称 —— 而人眼对「左右不对称的暗角」其实不敏感，
 *    只会留下「这张图有点脏」的模糊印象。
 * 3. **长边归一化**：同一个归一化位置在**不同分辨率**下必须给出**逐位相同**的结果。
 *    这条就是「预览所见 = 导出所得」在几何量上的形式化 —— 本项目预览 2048、导出 7008，
 *    写成像素坐标会让导出的颗粒比预览粗 3.4 倍、暗角大小也跟着变。
 *
 * 第 3 条是本文件最值钱的断言：它把 `EditEngine` 里那行
 * `(x + 0.5f) / maxOf(w, h)` 的**口径**钉住了（[pixelAt] 复刻的就是它）。
 */
class EffectMathTest {

    private fun rgb(packed: Int) = Triple(
        (packed shr 16) and 0xff,
        (packed shr 8) and 0xff,
        packed and 0xff
    )

    /** 取红通道：灰阶输入下三通道相等，看一个就够。 */
    private fun red(t: Triple<Int, Int, Int>): Int = t.first

    /**
     * 按**引擎的口径**取一个像素：`u = (x + 0.5f) / max(w, h)`。
     *
     * 刻意复刻 `EditEngine` 的换算（而不是让测试自己另算一套）—— 否则「长边归一化」
     * 这条断言测的就只是测试自己的公式，产品里换成像素坐标也照样绿。
     */
    private fun pixelAt(
        p: EditParams,
        w: Int,
        h: Int,
        x: Int,
        y: Int,
        level: Int = GRAY
    ): Triple<Int, Int, Int> {
        val invL = 1f / maxOf(w, h)
        return rgb(
            PixelProgram(p, frameW = w, frameH = h)
                .applySrgb8(level, level, level, (x + 0.5f) * invL, (y + 0.5f) * invL)
        )
    }

    /** 直接给**归一化坐标**取值（用于「同一位置、不同分辨率」这类对照）。 */
    private fun coordAt(
        p: EditParams,
        w: Int,
        h: Int,
        u: Float,
        v: Float,
        level: Int = GRAY
    ): Triple<Int, Int, Int> =
        rgb(PixelProgram(p, frameW = w, frameH = h).applySrgb8(level, level, level, u, v))

    // ═══════════════════════ 暗角 ═══════════════════════

    /**
     * **中心恒不动**，而且是在**最极端的参数组合**下：`midpoint = 0` + `feather = 1`
     * 是唯一能把过渡区一路推到中心的组合（`mid − half` 会变成负数）。
     *
     * 所以表的第一格必须**逐位**等于 1f：只要它小于 1，用户一拖暗角量，整幅画面（包括需要被
     * 看清的中心主体）就跟着一起变暗 —— 那不是暗角，是「拿暗角当曝光滑块」。
     */
    @Test
    fun vignetteLutIsExactlyOneAtTheCentre() {
        val dark = ColorMath.buildVignetteLut(amount = -1f, midpoint = 0f, feather = 1f)
        val light = ColorMath.buildVignetteLut(amount = 1f, midpoint = 1f, feather = 1f)
        assertEquals("表的长度必须与索引口径一致", ColorMath.VIGNETTE_LUT_SIZE, dark.size)
        assertEquals("d² = 0 处必须恰好为 1（压暗方向）", 1.0, dark[0].toDouble(), 0.0)
        assertEquals("d² = 0 处必须恰好为 1（提亮方向）", 1.0, light[0].toDouble(), 0.0)
    }

    /**
     * 表的**末格 = 四角**必须吃满权重：`1 + amount · 0.8`。
     *
     * `0.8` 是 `ColorMath` 里 private 的 `VIGNETTE_GAIN`，所以这里写字面量：满量程压暗时四角
     * 保留约 20%，**不是 0** —— 「拖到底就把照片毁掉」的滑块是设计事故。改那个常量时本用例会红，
     * 那是有意的。
     */
    @Test
    fun vignetteLutReachesFullStrengthAtTheCorner() {
        val dark = ColorMath.buildVignetteLut(amount = -1f, midpoint = 0.5f, feather = 0.5f)
        val light = ColorMath.buildVignetteLut(amount = 0.5f, midpoint = 0.5f, feather = 0.5f)
        val last = ColorMath.VIGNETTE_LUT_SIZE - 1
        assertEquals("满量程压暗：四角保留约 20%", 0.2, dark[last].toDouble(), 0.01)
        assertEquals("半量程提亮：四角 ×1.4", 1.4, light[last].toDouble(), 0.01)
    }

    /**
     * 像素级：四角明显变暗，而**中心逐位不变**。
     *
     * 中心「逐位不变」不是巧合：该处 `d² ≈ 1e-4` 落在过渡起点（0.375）以内 ⇒ 权重恰好 0
     * ⇒ 乘性系数恰好 1.0f，而乘 1.0f 是位保持的。若哪天有人把中心系数改成 0.999（比如为了
     * 「更自然」），本用例立刻红 —— 那条改动在真机上只会表现为「照片整体暗了一点点」。
     */
    @Test
    fun vignetteDarkensCornersAndLeavesCentreUntouched() {
        val p = EditParams(vignetteAmount = -1f)
        val plain = pixelAt(EditParams(), 100, 100, 49, 49)
        val centre = pixelAt(p, 100, 100, 49, 49)
        val corner = pixelAt(p, 100, 100, 0, 0)

        assertEquals("中心必须逐位不变（系数恰好为 1.0f）", plain, centre)
        assertTrue(
            "四角必须明显变暗（中灰 $GRAY → 约 26），实际 ${red(corner)}",
            red(corner) in 20..32
        )
    }

    /**
     * **四角对称**：四个角的 `d²` 都必须归一化到 1 ⇒ 输出两两一致。
     *
     * 容差取 1 而不是 0：`u` 与 `1 − u` 在浮点下不是严格的镜像，可能差 1 ulp
     * （`0.5f/100f` 与 `99.5f/100f` 各自舍入）。但这里四角都处在**权重饱和区**（= 1），
     * 所以 1 ulp 的差异不会跨越表的格子 —— 真出现 2 级以上的差异，说明归一化算错了。
     */
    @Test
    fun vignetteCornersAreSymmetric() {
        val p = EditParams(vignetteAmount = -1f)
        val tl = red(pixelAt(p, 100, 100, 0, 0))
        val tr = red(pixelAt(p, 100, 100, 99, 0))
        val bl = red(pixelAt(p, 100, 100, 0, 99))
        val br = red(pixelAt(p, 100, 100, 99, 99))
        assertEquals("左上 / 右上必须一致", tl.toDouble(), tr.toDouble(), 1.0)
        assertEquals("左上 / 左下必须一致", tl.toDouble(), bl.toDouble(), 1.0)
        assertEquals("左上 / 右下必须一致", tl.toDouble(), br.toDouble(), 1.0)
    }

    /** 符号约定：**正 = 提亮四角**（与「四个影调滑块正 = 往亮推」同一条约定）。 */
    @Test
    fun positiveAmountBrightensCorners() {
        val plain = red(pixelAt(EditParams(), 100, 100, 0, 0))
        val light = red(pixelAt(EditParams(vignetteAmount = 1f), 100, 100, 0, 0))
        assertTrue("正 amount 必须提亮四角：$plain → $light", light > plain)
    }

    /** `amount = 0` ⇒ 整级跳过 ⇒ 与「没有暗角这回事」**逐位**相同。 */
    @Test
    fun vignetteIsSilentWhenAmountIsZero() {
        val corner = pixelAt(EditParams(vignetteAmount = 0f), 100, 100, 0, 0)
        assertEquals(pixelAt(EditParams(), 100, 100, 0, 0), corner)
    }

    /**
     * **长边归一化**：同一个归一化位置、同一个画幅比例，换分辨率必须**逐位相同**。
     *
     * 400×200 与 800×400 是同一比例的两种分辨率（对应本项目「预览 vs 导出」的关系）。
     * 传同一对 `(u, v)` 而不是同一像素坐标，是为了把「几何函数」与「像素中心取整」分开测 ——
     * 后者本来就会随分辨率差半个像素，混在一起会让这条断言永远飘。
     */
    @Test
    fun vignetteGeometryIsNormalisedToTheLongSide() {
        val p = EditParams(vignetteAmount = -0.8f, vignetteMidpoint = 0.4f, vignetteFeather = 0.6f)
        val small = coordAt(p, 400, 200, 0.31f, 0.17f)
        val large = coordAt(p, 800, 400, 0.31f, 0.17f)
        assertEquals("同一归一化位置在不同分辨率下必须逐位相同", small, large)
    }

    /**
     * 圆度只动**长边中点**、不动四角。
     *
     * 判据来自几何：`d²` 的归一化常数是按「四角 = 1」定的，所以改圆度不改变四角；而长边中点
     * 离四角多远，恰恰取决于轮廓是「正圆」还是「贴住画幅」。这里取 `midpoint = 1`，把长边中点
     * 放进过渡区（用默认参数时它已经在饱和区，两种圆度都吃满权重，测不出区别）。
     */
    @Test
    fun roundnessMovesTheLongEdgeNotTheCorners() {
        val square = EditParams(vignetteAmount = -1f, vignetteMidpoint = 1f)
        val round = EditParams(vignetteAmount = -1f, vignetteMidpoint = 1f, vignetteRoundness = 1f)
        val edgeSquare = red(pixelAt(square, 600, 400, 0, 199))
        val edgeRound = red(pixelAt(round, 600, 400, 0, 199))
        assertTrue(
            "负圆度（贴画幅）必须比正圆在长边中点上压得更狠：$edgeSquare vs $edgeRound",
            edgeSquare < edgeRound
        )
        assertEquals(
            "四角恒归一化到 d²=1 ⇒ 圆度不得改变四角",
            red(pixelAt(square, 600, 400, 0, 0)).toDouble(),
            red(pixelAt(round, 600, 400, 0, 0)).toDouble(),
            1.0
        )
    }

    // ═══════════════════════ 颗粒 ═══════════════════════

    /** `amount = 0` ⇒ 整级跳过 ⇒ 逐位相同（默认参数下不允许有任何可见变化）。 */
    @Test
    fun grainIsSilentWhenAmountIsZero() {
        val plain = pixelAt(EditParams(), 200, 200, 37, 91)
        val zero = pixelAt(EditParams(grainAmount = 0f), 200, 200, 37, 91)
        assertEquals(plain, zero)
    }

    /**
     * **确定性**：同一位置、同一参数必须永远得到同一个值 —— 包括**两个独立构建的实例**。
     *
     * 这条防的是「沸腾」：噪声若用 `Random` 或按帧序号扰动，拖动任意滑块时每一帧的噪点都不同，
     * 整幅画面会像开了锅。真机上这会被描述成「颗粒在抖」，而不是「颗粒是随机的」。
     */
    @Test
    fun grainIsDeterministicForTheSameNormalisedPosition() {
        val p = EditParams(grainAmount = 1f)
        val a1 = coordAt(p, 300, 200, 0.4123f, 0.6789f)
        val a2 = coordAt(p, 300, 200, 0.4123f, 0.6789f)
        assertEquals("同一个参数实例内必须逐位可复现", a1, a2)

        // 换一个**独立构建**的实例：噪声是进程级共享的常量瓦片，不是每个实例随机掷一次。
        val fresh = EditParams(grainAmount = 1f, grainSize = 0.35f, grainRoughness = 0.5f)
        assertEquals(
            "跨实例也必须逐位相同（否则每帧重渲都会换一套噪点）",
            a1,
            coordAt(fresh, 300, 200, 0.4123f, 0.6789f)
        )
    }

    /**
     * 颗粒必须**随位置变**：恒定偏移不是颗粒，是一次曝光微调。
     *
     * 取 16 个位置、要求至少出现 4 个互不相同的值 —— 概率上「全是同一个值」几乎不可能，
     * 但断言仍然留了余量（瓦片值量化成 8-bit，偶有相等）。
     */
    @Test
    fun grainVariesWithPosition() {
        val p = EditParams(grainAmount = 1f)
        val values = (0 until 16).map { i ->
            red(coordAt(p, 300, 200, 0.03f * i + 0.011f, 0.31f))
        }
        assertTrue(
            "颗粒必须随位置变化，实际 16 个采样只有 ${values.distinct().size} 种取值",
            values.distinct().size >= 4
        )
    }

    /**
     * 颗粒是**亮度**噪声：三通道加同一个偏移 ⇒ 中性灰必须仍然是中性灰（逐位相等），
     * 且扰动量必须落在增益预算内（`amount = 1` 时 ±0.10 × 255 ≈ ±26 级）。
     *
     * 「三通道同加」与「逐通道各加」的区别在真机上是「颗粒带彩色噪点」（像高 ISO 的彩噪），
     * 那是另一种东西，而且几乎不可能靠目视说清。
     */
    @Test
    fun grainKeepsGreyNeutralAndStaysWithinItsGainBudget() {
        val p = EditParams(grainAmount = 1f)
        var moved = false
        for (i in 0 until 24) {
            val t = coordAt(p, 300, 200, 0.04f * i + 0.02f, 0.31f)
            assertEquals("颗粒必须三通道同加，灰色不得被染色：$t", t.first, t.second)
            assertEquals("颗粒必须三通道同加，灰色不得被染色：$t", t.second, t.third)
            assertTrue(
                "扰动量必须落在 ±26 级（±0.10 × 255）内，实际 ${t.first}",
                t.first in (GRAY - 27)..(GRAY + 27)
            )
            if (t.first != GRAY) moved = true
        }
        assertTrue("amount = 1 时颗粒必须真的动到画面（否则参数是死的）", moved)
    }

    /**
     * 采样尺度是**长边相对**的：`grainSize` 越大 ⇒ 长边上格数越少 ⇒ 颗粒越粗。
     *
     * 断言「细 / 粗 ≈ 4×」是因为它是对数插值（1200 → 300）：等比而不是等差的量程，
     * 滑块在中间位置的手感才与两端一致。它也顺带钉住了一个**单位**约定 ——
     * 这里的数字是「长边上的格数」，不是「像素数」（像素数会让预览与导出差 3.4 倍）。
     */
    @Test
    fun grainCellCountIsFrameRelativeAndMonotonicInSize() {
        val fine = GrainNoise.sampleScale(0f)
        val mid = GrainNoise.sampleScale(0.5f)
        val coarse = GrainNoise.sampleScale(1f)
        assertTrue(
            "grainSize 越大颗粒必须越粗（长边上格数越少）：fine=$fine mid=$mid coarse=$coarse",
            fine > mid && mid > coarse
        )
        assertEquals("细 / 粗 = 4×（对数插值）", 4.0, (fine / coarse).toDouble(), 0.01)
        assertTrue(
            "格数必须是「长边上的个数」这个量级，不能退化成像素数：fine=$fine",
            fine / GrainNoise.TEXELS_PER_CELL in 200f..2000f
        )
    }

    /** 颗粒同样**不依赖分辨率**：同一归一化位置、不同画面尺寸必须逐位相同。 */
    @Test
    fun grainGeometryIsIndependentOfFrameSize() {
        val p = EditParams(grainAmount = 1f, grainSize = 0.2f)
        val small = coordAt(p, 300, 200, 0.271f, 0.133f)
        val large = coordAt(p, 1200, 800, 0.271f, 0.133f)
        assertEquals("颗粒的空间频率按长边归一化 ⇒ 换分辨率必须逐位相同", small, large)
    }
}
