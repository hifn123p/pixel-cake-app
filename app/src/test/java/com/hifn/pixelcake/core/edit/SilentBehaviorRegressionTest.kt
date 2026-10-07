package com.hifn.pixelcake.core.edit

import com.hifn.pixelcake.core.edit.retouch.ColorTransfer
import com.hifn.pixelcake.core.edit.retouch.ColorTransferParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 2026-10-07 全量审查的**回归防线**：每一条都在修一个「语法/类型/Lint 三层都看不出来、
 * 但真机上表现为功能无反应或效果错误」的缺陷。
 *
 * ## 为什么这些必须写成断言而不是注释
 *
 * 这批缺陷有一个共同点：**它们全部编译通过、静态检查全绿、运行时也不崩**。
 *
 * | 缺陷| 现场表现 |
 * |---|---|
 * | 分通道曲线只判红通道 | 拖「绿色/蓝色曲线」滑块画面完全不动 |
 * | 颗粒下标当成噪声值 | 颗粒看起来像「把平滑区域弄脏」，且 roughness 越强越糟 |
 * | 追色 bw 用常量 z-score | 「去色」变成「发灰发白」，亮度结构被压平 |
 * | 未知 LUT id 静默 no-op | 对象层「建了但拖参数没反应」 |
 *
 * 也就是说：**任何静态工具都不会报，只有断言会报**。
 *
 * ## 共同的反例写法
 *
 * 这四条里最值得注意的是颗粒：修复前后的映射在「幅度上界」「三通道同加」「会改变像素」
 * 这些已有断言下**全部一样**，只有**符号与单调性**不同。所以下面刻意断言
 * 「正噪声 ⇒ 正扰动」这个方向，而不是只断言「幅度 ≤ 27 级」。
 */
class SilentBehaviorRegressionTest {

    private fun rgb(packed: Int) = Triple(
        (packed shr 16) and 0xff,
        (packed shr 8) and 0xff,
        packed and 0xff
    )

    // —————————————————————— 1. 分通道曲线：逐通道判空 ——————————————————————

    /**
     * 只调绿色曲线时，绿色必须真的变。
     *
     * 修复前：[PixelProgram] 只用 `redLut` 判空，于是「红通道是恒等 ⇒ 整段跳过」，
     * 绿/蓝曲线表给了两张也白给 —— 而 UI 上那三个标签页各自独立写参数，
     * 用户看到的是「滑块能拖、数字在变、画面不动」。
     */
    @Test
    fun greenCurveAppliesWhenRedCurveUntouched() {
        val p = EditParams(greenPoints = listOf(0 to 0, 128 to 200, 255 to 255))
        val out = rgb(PixelProgram(p).srgbAtCenter(128, 128, 128))
        assertTrue(
            "只设绿色曲线时绿色必须抬升（修复前整段被跳过 ⇒ g≈r）；实际 r=${out.first} g=${out.second}",
            out.second > out.first + 5
        )
    }

    /** 蓝色曲线同理 —— 且它是三条里最后一条，最容易被「只看第一张表」的写法漏掉。 */
    @Test
    fun blueCurveAppliesWhenRedCurveUntouched() {
        val p = EditParams(bluePoints = listOf(0 to 0, 128 to 200, 255 to 255))
        val out = rgb(PixelProgram(p).srgbAtCenter(128, 128, 128))
        assertTrue(
            "只设蓝色曲线时蓝色必须抬升；实际 r=${out.first} b=${out.third}",
            out.third > out.first + 5
        )
    }

    /** 三条都动时仍要三通道各自生效（防止修成「只判一张表」的另一侧）。 */
    @Test
    fun allThreeChannelCurvesApplyTogether() {
        val p = EditParams(
            redPoints = listOf(0 to 0, 128 to 210, 255 to 255),
            greenPoints = listOf(0 to 0, 128 to 200, 255 to 255),
            bluePoints = listOf(0 to 0, 128 to 190, 255 to 255)
        )
        val out = rgb(PixelProgram(p).srgbAtCenter(128, 128, 128))
        assertTrue("红曲线抬得最多", out.first > out.second)
        assertTrue("绿曲线次之", out.second > out.third)
        assertTrue("蓝曲线也必须离开原值（修复前它停在 ~128）", out.third > 128 + 5)
    }

    /** 三条曲线都是恒等时，仍必须与完全不设一致（判空修复不能引入额外偏移）。 */
    @Test
    fun identityChannelCurvesStayNeutral() {
        val neutral = rgb(PixelProgram(EditParams()).srgbAtCenter(90, 90, 90))
        val withIdentity = rgb(
            PixelProgram(
                EditParams(
                    redPoints = listOf(0 to 0, 128 to 128, 255 to 255),
                    greenPoints = listOf(0 to 0, 128 to 128, 255 to 255),
                    bluePoints = listOf(0 to 0, 128 to 128, 255 to 255)
                )
            ).srgbAtCenter(90, 90, 90)
        )
        assertEquals("恒等曲线不得改变任何像素", neutral, withIdentity)
    }

    // —————————————————————— 2. 颗粒：下标 ≠ 值 ——————————————————————

    /**
     * 「正噪声 ⇒ 正扰动」的方向性断言 —— 这条是本组里最关键的一条。
     *
     * 查表下标 [index] 是「带符号字节 `and 0xff`」：`1` 对应噪声 **+1**，`255` 对应噪声 **−1**。
     * 修复前的 `(index - 128) / 128f` 会把这两个**反过来**（下标 1 → −0.992）。
     * 而「幅度 ≤ 27 级」「三通道同加」「位置不同扰动不同」这些已有断言在修复前后**都成立**，
     * 所以它们完全抓不到这个 bug —— 必须断言符号方向。
     */
    @Test
    fun grainShapeKeepsSignOfUnderlyingNoise() {
        val amp = 1f
        val pos = GrainNoise.shapeOf(1, 1f, amp)      // 噪声 +1
        val neg = GrainNoise.shapeOf(255, 1f, amp)     // 噪声 −1
        assertTrue("噪声为正（+1，下标 1）时扰动必须为正，实际 $pos", pos > 0f)
        assertTrue("噪声为负（−1，下标 255）时扰动必须为负，实际 $neg", neg < 0f)
        assertTrue("正负必须对称（同一个强度模型），实际 pos=$pos neg=$neg", abs(pos + neg) < 1e-3f)
    }

    /** 下标 0 =噪声 0 =无扰动（这条同时锁住「零值不被映射到最大幅度」）。 */
    @Test
    fun grainShapeIsZeroAtZeroNoise() {
        assertEquals("噪声 0（下标 0）不得产生任何扰动", 0f, GrainNoise.shapeOf(0, 1f, 1f), 0f)
    }

    /**
     * 单调性：|噪声|越大 ⇒ |扰动|越大（幂整形只改曲线形状，不许翻转）。
     *
     * ⚠️ 下标换算别搞错：符号扩展后 `index 127` 才是**最大正噪声 +127**，
     * 而 `index 1` 是最小正噪声 +1（`index 255` 是 −1）。这条断言曾经写反过 ——
     * 与上一条「方向」断言配对才完整：单调但整体反向，同样是错的映射。
     */
    @Test
    fun grainShapeIsMonotonicInNoiseMagnitude() {
        val tiny = abs(GrainNoise.shapeOf(1, 1f, 1f))       // 噪声 +1   → ≈0.008
        val mid = abs(GrainNoise.shapeOf(64, 1f, 1f))       // 噪声 +64  → 0.5
        val large = abs(GrainNoise.shapeOf(127, 1f, 1f))    // 噪声 +127 → ≈0.992
        assertTrue("tiny=$tiny mid=$mid large=$large 必须递增", tiny < mid)
        assertTrue("tiny=$tiny mid=$mid large=$large 必须递增", mid < large)
        // 负侧同样要单调（否则「下标被当成值」的反向映射在负半轴上仍可能看起来正常）
        assertTrue(
            "负侧也必须单调：|−127| 应大于 |−64|",
            abs(GrainNoise.shapeOf(129, 1f, 1f)) > abs(GrainNoise.shapeOf(192, 1f, 1f))
        )
    }

    /** roughness 的幂整形必须真的起作用（exp = 1 时才是恒等）。 */
    @Test
    fun grainRoughnessChangesShapeNotDirection() {
        val soft = GrainNoise.shapeOf(20, 1.6f, 1f)
        val hard = GrainNoise.shapeOf(20, 0.4f, 1f)
        assertTrue("两者符号必须一致（同一份噪声），soft=$soft hard=$hard", soft * hard > 0f)
        assertTrue(
            "soft(指数 1.6) 必须压低小噪声、hard(0.4) 必须抬高它（>1），soft=$soft hard=$hard",
            soft < 0.2f && hard > 0.2f
        )
    }

    /** 端到端：颗粒在整幅上必须是零均值附近的扰动（不是单向偏移）。 */
    @Test
    fun grainIsZeroMeanAcrossTheFrame() {
        val prog = PixelProgram(EditParams(grainAmount = 0.6f))
        val neutral = prog.srgbAtCenter(128, 128, 128)
        var sum = 0L
        var touched = 0
        val steps = 24
        // u,v 上取一片网格：瓦片里正负噪声各半 ⇒ 扰动和应当接近 0。
        for (iy in 0 until steps) {
            for (ix in 0 until steps) {
                val u = (ix + 0.5f) / steps
                val v = (iy + 0.5f) / steps
                val cur = prog.applySrgb8(128, 128, 128, u, v)
                if (cur != neutral) touched++
                sum += (cur and 0xff) - 128
            }
        }
        assertTrue(
            "颗粒必须改变像素（$steps x $steps 个采样点里只有 $touched 个变了）",
            touched > steps * steps / 4
        )
        // 允许的偏差：颗粒幅度上限约 ±15 级（amount 0.6 × GRAIN_GAIN 0.10 × 255），
        // 零均值残差应远小于「采样点数 × 幅度」。
        assertTrue(
            "颗粒必须是零均值附近的扰动而非单向偏移，sum=$sum（${steps * steps} 个采样）",
            abs(sum) < steps * steps * 3
        )
    }

    // —————————————————————— 3. 内置 LUT：opcode 收敛 ——————————————————————

    /**
     * 已知 id 必须生效。（opcode 由构造期从 `lutId` 收敛；未知 id 收敛成 0 = no-op。）
     * 这条同时防止「收敛时把某个已知 id 漏掉」——那会让某档 LUT 静默变 no-op。
     */
    @Test
    fun knownLutIdsStillTakeEffect() {
        val base = rgb(PixelProgram(EditParams()).srgbAtCenter(200, 140, 90))
        for (id in listOf("bw", "warm", "cool", "film")) {
            val out = rgb(PixelProgram(EditParams(lutId = id, lutIntensity = 1f)).srgbAtCenter(200, 140, 90))
            assertTrue(
                "内置 LUT '$id' 必须真的生效（opcode 收敛时漏掉 ⇒ 静默 no-op）；" +
                    "base=$base out=$out",
                out != base
            )
        }
    }

    /** 未知 id 必须**退化为无操作**（而不是抛异常或整幅异常）。 */
    @Test
    fun unknownLutIdIsNoOp() {
        val base = rgb(PixelProgram(EditParams()).srgbAtCenter(200, 140, 90))
        val weird = rgb(
            PixelProgram(EditParams(lutId = "bw2", lutIntensity = 1f)).srgbAtCenter(200, 140, 90)
        )
        assertEquals("未知 LUT id 必须是 no-op", base, weird)
    }

    // —————————————————————— 4. 追色 bw：必须保留亮度结构 ——————————————————————

    /**
     * bw 模式的 z-score 必须**逐像素**取。
     *
     * 修复前 `lumaZ` 在循环外算一次（是常量），于是 bw 实际执行的是
     * 「向常量灰偏移 + 按 intensity 混合」：明暗对比被压掉、亮部被拉向 248 附近，
     * 观感是「发灰发白」而不是「黑白」。
     *
     * 断言方式：亮像素与暗像素经 bw 之后**仍然分得开**，且间距不应塌到很小。
     * 修复前的常量实现里两者的输出差 = 常数 × (1−intensity) 的同一份值，
     * 与原像素的明暗无关 —— 用不同强度的输入对比就能分辨。
     */
    @Test
    fun colorTransferBwPreservesLuminanceStructure() {
        // 一张只有两个亮度值的极简图：0 与 255。
        val px = intArrayOf(
            0xff000000.toInt() or (0 shl 16) or (0 shl 8) or 0,
            0xff000000.toInt() or (255 shl 16) or (255 shl 8) or 255
        )
        val out = IntArray(2)
        System.arraycopy(px, 0, out, 0, 2)
        ColorTransfer.apply(out, 2, 1, ColorTransferParams(refId = "bw", intensity = 1f))

        val lo = out[0] and 0xff
        val hi = out[1] and 0xff
        assertEquals("bw 后仍必须是灰阶（去掉 chroma）", (out[0] shr 16) and 0xff, (out[0] shr 8) and 0xff)
        assertTrue("亮像素必须仍然比暗像素亮，lo=$lo hi=$hi", hi > lo)
        assertTrue(
            "bw 后必须仍有可观的动态范围（修复前被压成常量偏移，间距≈" + abs(hi - lo) + "）",
            hi - lo > 96
        )
    }

    /** 非 mono 参考仍走逐通道 z-score（这一条保护「修 mono 时别把普通路径一起改坏」）。 */
    @Test
    fun colorTransferNonMonoKeepsChannelBehavior() {
        val px = intArrayOf(
            0xff000000.toInt() or (220 shl 16) or (20 shl 8) or 20,
            0xff000000.toInt() or (10 shl 16) or (200 shl 8) or 20
        )
        val out = IntArray(2)
        System.arraycopy(px, 0, out, 0, 2)
        ColorTransfer.apply(out, 2, 1, ColorTransferParams(refId = "warm", intensity = 1f))
        // 暖参考：红通道应该被整体推向更高，红像素与绿像素的差距应当**保持或扩大**。
        val r0 = (out[0] shr 16) and 0xff
        val g0 = (out[0] shr 8) and 0xff
        assertTrue("warm 参考下红像素应仍显著高于绿像素，实际 r=$r0 g=$g0", r0 > g0 + 30)
    }
}