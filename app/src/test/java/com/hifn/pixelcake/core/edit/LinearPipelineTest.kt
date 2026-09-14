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
 * 本文件同时是 **M1 的处置前置**：M1（高光裁顶不可恢复 + 逐通道独立裁顶导致高光偏色）要改
 * `PixelProgram` 的裁顶位置，改之前必须先有「现在到底是什么行为」的量化描述。
 * 其中两个 `@Test` 是**刻画测试（characterization test）**——它们断言的是**当前实现的实际行为**，
 * 而不是期望行为；修 M1 时它们**应当失败**，失败即说明修复生效，届时连同实现一起更新。
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
     */
    @Test
    fun linearAndSrgbEntriesAgreeOnSameInput() {
        val p = PixelProgram(EditParams())
        for (v in intArrayOf(0, 1, 17, 64, 128, 200, 254, 255)) {
            assertEquals(
                "applyLinear 与 applySrgb8 在 v=$v 上必须同源",
                p.applySrgb8(v, v, v),
                p.applyLinear(lin8(v), lin8(v), lin8(v))
            )
        }
    }

    /** 线性中灰（0.5）编码后应落在 sRGB 约 0.735 ⇒ 188/255。三通道必须相等（默认参数无色偏）。 */
    @Test
    fun neutralLinearMidGrayEncodesToExpectedSrgb() {
        val out = rgb(PixelProgram(EditParams()).applyLinear(32768, 32768, 32768))
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
        val neutral = rgb(PixelProgram(EditParams()).applyLinear(16384, 16384, 16384))
        val boosted = rgb(PixelProgram(EditParams(exposureEv = 1f)).applyLinear(16384, 16384, 16384))

        assertTrue("+1EV 必须提亮（线性域乘 2），neutral=${neutral.first} boosted=${boosted.first}",
            boosted.first > neutral.first)
        assertTrue("线性 0.25 经 +1EV 到 0.5 应编码到 sRGB≈188，实际 ${boosted.first}",
            boosted.first in 186..190)
    }

    /** −1EV 在线性域 = 除 2：线性 0.5（32768）→ 0.25，编码值应从 ~188 降到 ~137。 */
    @Test
    fun negativeExposureDividesLinearValue() {
        val darkened = rgb(PixelProgram(EditParams(exposureEv = -1f)).applyLinear(32768, 32768, 32768))
        assertTrue("−1EV 必须压暗，实际 ${darkened.first}", darkened.first in 135..140)
    }

    /** 高光滑块的作用方向：正值**回收**高光（压暗亮部），不是提亮。 */
    @Test
    fun highlightsSliderRecoversHighlightsOnLinearPath() {
        val neutral = rgb(PixelProgram(EditParams()).applyLinear(49152, 49152, 49152))
        val recovered = rgb(PixelProgram(EditParams(highlights = 1f)).applyLinear(49152, 49152, 49152))
        assertTrue(
            "highlights 正值必须压暗亮部（回收），neutral=${neutral.first} recovered=${recovered.first}",
            recovered.first < neutral.first
        )
    }

    // ——————————————— 以下两条是 M1 的刻画测试 ———————————————
    // 它们断言**当前实现的实际行为**，不是期望行为。修 M1（线性域软压肩）时应当红。

    /**
     * **刻画 M1-a：白点以上被硬砍，且 highlights 滑块无法恢复。**
     *
     * 现状：`applyLinear` 先乘增益再调 `linear16ToSrgb`，而后者在 `v >= 65535` 处直接返回 1f
     * （见 `ColorMath.linear16ToSrgb`）。所以 +1EV 打在已经到白点的像素上，结果与不加曝光**完全相同**
     * —— 信息在编码那一刻就没了，后面 `finish()` 里的高光滑块只剩 sRGB 域可操作，恢复不回来。
     *
     * 修好 M1（线性域做软压肩 / 把曝光增益加在色调映射之前）后，本断言应改为
     * `boosted.first > neutral.first`。
     */
    @Test
    fun whitePointClipIsNotRecoverableByExposure() {
        val p = PixelProgram(EditParams())
        val neutral = rgb(p.applyLinear(65535, 65535, 65535))
        val boosted = rgb(PixelProgram(EditParams(exposureEv = 1f)).applyLinear(65535, 65535, 65535))
        assertEquals("M1 现状：白点像素加曝光后不应有任何变化（信息已丢）", neutral.first, boosted.first)
        assertEquals(255, boosted.first)
    }

    /**
     * **刻画 M1-b：逐通道独立裁顶 ⇒ 高光偏色。**
     *
     * 现状：R 已到白点（65535）而 G/B 还没到时，+1EV 只把 R 砍在 65535，G/B 却按 2× 真实增益上去了。
     * 结果是 R:G 比例被压缩（255:180 → 255:245），高光区域**朝青色偏**，且偏多少取决于增益幅度。
     *
     * 这是与 FIX_LIST F07 同源的问题：正确做法是在线性域对整幅亮度做软压肩，保住三通道比例。
     */
    @Test
    fun perChannelClipShiftsHighlightHue() {
        val p = PixelProgram(EditParams())
        val neutral = rgb(p.applyLinear(65535, 30000, 30000))
        val boosted = rgb(PixelProgram(EditParams(exposureEv = 1f)).applyLinear(65535, 30000, 30000))

        assertEquals("R 已在白点，加曝光后仍是 255", 255, boosted.first)
        assertTrue(
            "M1 现状：G 通道会被 2× 增益推高（${neutral.second} → ${boosted.second}），" +
                "而 R 被砍住不动 ⇒ R:G 比例被压缩即高光偏色",
            boosted.second > neutral.second + 50
        )
    }
}
