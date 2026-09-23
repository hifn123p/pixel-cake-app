package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 16-bit 线性管线的护栏（审计 M5 缺口①）。
 *
 * **为什么必须补**：[ColorMathTest] 全部走 `applySrgb8`（8-bit 入口），而 `applyLinear`
 * 是 RAW 全分辨率导出**唯一**的入口 —— 这条路径此前**零测试**。F01/F03/F07 的核心承诺
 * （16-bit 不降位、线性域曝光/WB、高光不被提前砍）全都挂在它上面，换算子时静默劣化没有任何护栏。
 *
 * ## 2026-09-23：M1 已修，两条「刻画测试」翻正（`docs/TONING_DESIGN.md` §5）
 *
 * M1 = 「线性值 ≥ 白点被硬截断（`v >= 65535f -> 1f`）」+「逐通道各砍各的 ⇒ 高光偏色」。
 * 批次 1 在线性域加了一条**三通道公共比例**的软压肩（[ColorMath.shoulderScale]，
 * 起点由 [EditParams.highlightRecovery] 控制），于是：
 *
 * | 原刻画（断言 bug） | 现断言（断言修复） |
 * |---|---|
 * | `whitePointClipIsNotRecoverableByExposure` | [whitePointIsNotHardClippedByExposure] |
 * | `perChannelClipShiftsHighlightHue` | [shoulderPreservesHighlightRatio] |
 *
 * 两条都已**翻成真正的回归护栏**：谁再把压肩挪回「逐通道各砍各的」或把白点钳死，
 * CI 立刻红。另加 [highlightRecoveryRestoresBlownHighlights] 覆盖新参数的**唯一用途**
 * ——把已经被曝光推到白点以上的层次找回来。
 *
 * 坐标一律走 [srgbAtCenter] / [linearAtCenter]（画面中心），**两条入口必须传同一对** ——
 * 否则 [linearAndSrgbEntriesAgreeOnSameInput] 会拿「坐标不同」当「管线不同」。
 */
class LinearPipelineTest {

    private fun rgb(packed: Int) = Triple(
        (packed shr 16) and 0xff,
        (packed shr 8) and 0xff,
        packed and 0xff
    )

    /** 8-bit sRGB 值 -> 16-bit 线性（复用产品代码的表，避免测试里再抄一份换算）。 */
    private fun lin8(v: Int): Int = ColorMath.SRGB8_TO_LINEAR16[v]

    // ——————————————— 两条入口必须同源 ———————————————

    /**
     * `applyLinear` 与 `applySrgb8` 在**同一个线性输入**下必须给出**完全相同**的结果。
     *
     * 这条断言是「预览所见 = 导出所得」的结构性保证：JPEG 走 `applySrgb8`、RAW 走 `applyLinear`，
     * 一旦有人只改一条路径的参数折叠方式（例如把 WB 增益挪到编码之后），本用例立刻红。
     * 默认参数下 gainR/G/B 都是 1f，两侧调用的是同一个 `linear16ToSrgb(同一个 int)` ⇒ 可断言**逐位相等**。
     *
     * ⚠️ 批次 1 加入软压肩后这条更值钱了：压肩必须对两条入口**同源生效**，
     * 否则 RAW 与 JPEG 的高光滚落会不一致（白点 65535 这一档正是压肩唯一会碰到的取样点，
     * 它被 `255` 覆盖到，所以本用例确实在管压肩）。
     *
     * ⚠️ 批次 3 起两条入口都要收**坐标**：这里两侧传的是**同一对**（两个 helper 都钉在画面
     * 中心）—— 否则「同源」会被坐标差异掩盖，变成一个看着通过、其实没在测压肩的用例。
     */
    @Test
    fun linearAndSrgbEntriesAgreeOnSameInput() {
        val p = PixelProgram(EditParams())
        for (v in intArrayOf(0, 1, 17, 64, 128, 200, 254, 255)) {
            assertEquals(
                "applyLinear 与 applySrgb8 在 v=$v 上必须同源",
                p.srgbAtCenter(v, v, v),
                p.linearAtCenter(lin8(v), lin8(v), lin8(v))
            )
        }
    }

    /** 线性中灰（0.5）编码后应落在 sRGB 约 0.735 ⇒ 188/255。三通道必须相等（默认参数无色偏）。 */
    @Test
    fun neutralLinearMidGrayEncodesToExpectedSrgb() {
        val out = rgb(PixelProgram(EditParams()).linearAtCenter(32768, 32768, 32768))
        assertEquals("默认参数不得引入色偏", out.first, out.second)
        assertEquals("默认参数不得引入色偏", out.second, out.third)
        assertTrue(
            "线性 0.5 应编码到 sRGB≈188，实际 r=${out.first}",
            out.first in 186..190
        )
    }

    // ——————————————— 曝光方向性（线性域） ———————————————

    /**
     * +1EV 在线性域 = 乘 2：线性 0.25（16384）→ 0.5，编码值应从 ~137 升到 ~188。
     * 断言的是**方向 + 具体落点**，不是「有变化」——方向反转是这类代码最易犯的错，而「有变化」抓不到它。
     */
    @Test
    fun positiveExposureMultipliesLinearValue() {
        val neutral = rgb(PixelProgram(EditParams()).linearAtCenter(16384, 16384, 16384))
        val boosted = rgb(PixelProgram(EditParams(exposureEv = 1f)).linearAtCenter(16384, 16384, 16384))

        assertTrue("+1EV 必须提亮（线性域乘 2），neutral=${neutral.first} boosted=${boosted.first}",
            boosted.first > neutral.first)
        assertTrue("线性 0.25 经 +1EV 到 0.5 应编码到 sRGB≈188，实际 ${boosted.first}",
            boosted.first in 186..190)
    }

    /** −1EV 在线性域 = 除 2：线性 0.5（32768）→ 0.25，编码值应从 ~188 降到 ~137。 */
    @Test
    fun negativeExposureDividesLinearValue() {
        val darkened = rgb(PixelProgram(EditParams(exposureEv = -1f)).linearAtCenter(32768, 32768, 32768))
        assertTrue("−1EV 必须压暗，实际 ${darkened.first}", darkened.first in 135..140)
    }

    // ——————————————— 四区影调的方向性 ———————————————

    /**
     * 高光滑块的**符号**：正值 = **提亮**（Lightroom 口径，`EditParams` 类文档有完整说明）。
     *
     * 这里刻意取 `highlights = 0.3f` 而不是 1f：满量程下加法会把 L≈0.88 的像素直接顶到纯白，
     * 那个断言只能测出「撞了上限」，测不出「作用方向」。0.3 下落在 248，是**未撞顶**的真实读数
     * （算式：`L=0.8808`、`wHighlight(225/255)=0.8773`、`d=0.35·0.3·0.8773=0.0921`、
     * `0.8808+0.0921=0.9729 → 248`）。
     *
     * ⚠️ 改 [ColorMath] 的 `TONE_ZONE_GAIN` 时这条会跟着动 —— 那是**有意的**：
     * 它是「影调四区到底有多大力」的唯一量化出口，别把它放宽成一个连符号错了都能过的区间。
     */
    @Test
    fun highlightsSliderBrightensHighlightsOnLinearPath() {
        val neutral = rgb(PixelProgram(EditParams()).linearAtCenter(49152, 49152, 49152))
        val brighter = rgb(PixelProgram(EditParams(highlights = 0.3f)).linearAtCenter(49152, 49152, 49152))

        assertEquals("默认参数下中性输入不得有色偏", neutral.first, neutral.third)
        assertEquals("影调四区是加法偏移，不得引入色偏", brighter.first, brighter.third)
        assertTrue(
            "highlights 正值必须提亮亮部（Lightroom 口径），neutral=${neutral.first} brighter=${brighter.first}",
            brighter.first > neutral.first
        )
        assertTrue("应落在 248 附近（未撞白），实际 ${brighter.first}", brighter.first in 245..251)
    }

    // ——————————————— M1 修复后的行为（原刻画测试翻正） ———————————————

    /**
     * **M1 修复后：白点不再被硬截断，同一像素加 +1EV 后编码值仍能上升。**
     *
     * 修复前：`linear16ToSrgb` 在 `v >= 65535` 直接返回 1f ⇒ 白点像素加曝光**毫无变化**（都是 255）。
     * 修复后：默认压肩把白点压到 254（掉一级、肉眼不可辨），于是曝光仍有 1 级可走（254 → 255）。
     *
     * ⚠️ **不要把 `253..254` 放宽到 `255`** —— `neutral == 255` 恰好就是 M1 未修时的行为，
     * 这条断言的全部价值就在于区分这两者。
     */
    @Test
    fun whitePointIsNotHardClippedByExposure() {
        val neutral = rgb(PixelProgram(EditParams()).linearAtCenter(65535, 65535, 65535))
        val boosted = rgb(PixelProgram(EditParams(exposureEv = 1f)).linearAtCenter(65535, 65535, 65535))

        assertTrue(
            "默认压肩下落点是 254（保住三通道比例的那 0.6% headroom），实际 ${neutral.first}；" +
                "若为 255 说明压肩没生效、白点又被钳死了",
            neutral.first in 253..254
        )
        assertEquals("白点像素加 +1EV 后应到顶，而不是原地不动", 255, boosted.first)
    }

    /**
     * **M1 修复后：高光区的三通道比例被保住 ⇒ 不再偏色。**
     *
     * 修复前（逐通道裁顶）：R 已到白点被砍住、G/B 却按 2× 真实增益往上走
     * ⇒ `255:180 → 255:245`，R:G 比例被压缩，高光**朝青色偏**。
     *
     * 修复后（三通道公共系数）：曝光把 R/G 一起推进压肩，公共缩放几乎抵消了这次曝光
     * —— G 只从 180 动到 180，比例 `254:180` 与 `255:180` 等价。
     * 这正是「按最大通道取系数、三通道同乘」与「逐通道各砍各的」的分水岭。
     */
    @Test
    fun shoulderPreservesHighlightRatio() {
        val neutral = rgb(PixelProgram(EditParams()).linearAtCenter(65535, 30000, 30000))
        val boosted = rgb(PixelProgram(EditParams(exposureEv = 1f)).linearAtCenter(65535, 30000, 30000))

        assertEquals("R 已在白点，压肩后仍是最高通道", 255, boosted.first)
        assertTrue(
            "修复后 G 不该被单独推高（旧行为是 180 → 245），实际 ${neutral.second} → ${boosted.second}",
            boosted.second <= neutral.second + 3
        )
        val ratioBefore = neutral.second.toDouble() / neutral.first
        val ratioAfter = boosted.second.toDouble() / boosted.first
        assertEquals(
            "高光区 R:G 比例必须保住（旧行为偏 0.25，即高光偏青）",
            ratioBefore,
            ratioAfter,
            0.02
        )
    }

    /**
     * **高光找回：唯一能把「已经被推到白点以上」的层次找回来的参数。**
     *
     * 曝光 +1EV 打在白点像素上会彻底顶到 255（信息被困在压肩的上限里）。
     * 把 [EditParams.highlightRecovery] 拉到 1，压肩起点从 0.98 白点下移到 0.40 白点，
     * 同一个像素落到 236 —— 层次回来了，而不是靠「压暗」硬拉（其它滑块都只能压已存在的值）。
     */
    @Test
    fun highlightRecoveryRestoresBlownHighlights() {
        val blown = rgb(PixelProgram(EditParams(exposureEv = 1f)).linearAtCenter(65535, 65535, 65535))
        val recovered = rgb(
            PixelProgram(EditParams(exposureEv = 1f, highlightRecovery = 1f))
                .linearAtCenter(65535, 65535, 65535)
        )

        assertEquals("不找回时必然是纯白（信息在压肩上限处饱和）", 255, blown.first)
        assertTrue(
            "开启高光找回后应明显低于纯白，把层次让回来，实际 ${recovered.first}",
            recovered.first in 228..245
        )
        assertEquals("压肩是三通道公共系数 ⇒ 中性输入必须仍然中性", recovered.first, recovered.third)
    }
}
