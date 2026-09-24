package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToInt

// ———————————————————————————————————————————————————————————————
// 与 `DetailPass` 内部常量一致的**参照副本**
//
// 这份副本是刻意的重复：参照必须能在「不知道实现用了哪些常量」的前提下独立成立。
// 唯一的共享事实是**参数语义**（阈值单位是 sRGB 级数、权重和为 1），而不是实现细节。
// 改 `DetailPass` 里任何一个常量时，这里必须一起改 —— 不一致会让测试立刻变红，
// 这正是我们要的（比静默通过一份过期的参照安全得多）。
// ———————————————————————————————————————————————————————————————

private const val R_Y = 0.2126f
private const val G_Y = 0.7152f
private const val B_Y = 0.0722f

private const val NR_LUMA_T = 0.10f * 255f
private const val NR_CHROMA_T = 0.35f * 255f
private const val CLARITY_T = 0.20f * 255f
private const val TEXTURE_T = 0.06f * 255f
private const val SHARP_MASK_T = 0.10f * 255f

private const val K_CLARITY = 0.35f
private const val K_TEXTURE = 0.35f
private const val K_SHARP = 0.70f

/**
 * 一组「所有算子都开着」的参数。
 *
 * 用**非默认**的半径 / 细节 / 蒙版值，是为了让 [naiveDetail] 的每一条分支都被走到 ——
 * 全默认值下 `sharpenRadius = 1` / `*Detail = 0.5` 恰好是「最没有特征」的一档。
 */
private val P = EditParams(
    sharpenAmount = 0.8f,
    sharpenRadius = 1.4f,
    sharpenDetail = 0.35f,
    sharpenMasking = 0.4f,
    nrLuminance = 0.5f,
    nrLuminanceDetail = 0.3f,
    nrColor = 0.6f,
    nrColorDetail = 0.7f,
    clarity = 0.5f,
    texture = -0.4f
)

/**
 * 批次 4（降噪 / 清晰度 / 纹理 / 锐化）的护栏。
 *
 * ## 这个文件最值钱的断言是 `everySizeMatchesNaiveReference`
 *
 * 本阶段是全项目唯一的**邻域**阶段，也是唯一有**两条实现路径**（`applyWhole` / `applyBanded`）
 * 的算子。两条路径共用同一个 `core()`，差异只在「窗口怎么裁」—— 而窗口裁剪恰恰是最容易
 * 出 off-by-one 的地方，且错了以后**画面看起来完全正常**（只是某些行的对比度差了半级），
 * 真机上根本发现不了。
 *
 * 所以这里在测试内**独立**写了一个朴素参照（[naiveDetail]：全幅、直接卷积、不滑动累加、
 * 半径自己算），再拿它去扫一串高度，**跨过** `h <= band + 2·rMax` 这条分流线。
 * 分带路径与整幅路径都必须与它**逐位相同** —— 这一条就同时钉住了三件事：
 *
 * 1. 整幅路径本身正确；
 * 2. 分带路径的 halo 留存 / `carry` 复用没有算错行；
 * 3. 分流阈值（`h <= band + 2·rMax`）没有 off-by-one。
 *
 * ## 刻意**不**断言的东西
 *
 * 跨分辨率逐位相等。邻域算子的输出依赖核半径与邻域内容，半径按长边的固定比例等比缩放，
 * 得到的是**同一个观感**而不是同一个数字 —— 写这种断言就是假护栏（`DetailPass` 的类 KDoc
 * 已说明本阶段只承诺「观感一致」）。这里改为断言半径本身的两条性质：
 * 只依赖长边（[radiusDependsOnlyOnLongSide]）、随长边单调（[fineRadiusIsMonotonicInSharpenRadius]）。
 */
class DetailPassTest {

    // ————————————————————— 被测行为 —————————————————————

    /** 全中性 ⇒ 一个像素都不动。这是「不开细节的用户零成本」这条承诺的底线。 */
    @Test
    fun neutralIsNoOp() {
        val w = 32
        val h = 24
        val px = randomPixels(w * h, seed = 7L)
        val before = px.copyOf()

        assertTrue("全默认参数必须判为中性", DetailPass.isNeutral(EditParams()))
        DetailPass.apply(px, w, h, EditParams())
        assertArrayEquals(before, px)
    }

    /**
     * 只动**修饰量**（半径 / 细节 / 蒙版 / 保护）时仍判中性。
     *
     * 这条守的是 `isNeutral` 的判据本身：它只看强度类字段。若哪天有人把它改成「任一字段非默认即生效」，
     * 就会得到一个**没有任何像素变化、但每次重渲都白跑一遍邻域**的版本 —— 那是最难查的性能问题
     * （画面完全正常，只是莫名变慢）。第 2 个断言守的是另一半：两个 `*Detail` 也是修饰量。
     */
    @Test
    fun modifierOnlyChangesStayNeutral() {
        assertTrue(
            "半径/细节/蒙版都是修饰量，强度为 0 时不该让整段生效",
            DetailPass.isNeutral(
                EditParams(sharpenRadius = 3f, sharpenDetail = 1f, sharpenMasking = 1f)
            )
        )
        assertTrue(
            "两个 *Detail 同样是修饰量",
            DetailPass.isNeutral(EditParams(nrLuminanceDetail = 0f, nrColorDetail = 0f))
        )
    }

    /**
     * `haloRows` 与 `radii` 必须是同一个口径（中性时为 0 ⇒ 调用方不必分带）。
     *
     * ⚠️ 口径是 `rFine + rCoarse`，**不是** `rCoarse`：粗层 `b2 = blur(blur(raw, rFine), rCoarse)`
     * 是复合核，纵向支撑 = 两层半径之和。这一条与 `everySizeMatchesNaiveReference` 是同一件事的
     * 两种表述 —— 前者测「口径声明」，后者测「实际窗口」；只改一个足以让另一条红。
     */
    @Test
    fun haloRowsFollowsRadius() {
        assertEquals(0, DetailPass.haloRows(100, 100, EditParams()))
        val r = DetailPass.radii(4000, 3000, P)
        assertEquals(
            r[0] + r[1],
            DetailPass.haloRows(4000, 3000, P)
        )
    }

    /**
     * 半径只由**长边**决定：把宽高互换，半径必须一模一样。
     *
     * 这条是「横构图与竖构图用同一套观感口径」的形式化。若实现里误用 `w`（而不是 `maxOf(w, h)`），
     * 横拍与竖拍的照片会得到差 1.5 倍的锐化半径，而单看一张照片是看不出来的。
     */
    @Test
    fun radiusDependsOnlyOnLongSide() {
        assertEquals(
            DetailPass.radii(7008, 4672, P).toList(),
            DetailPass.radii(4672, 7008, P).toList()
        )
    }

    /** 长边翻倍 ⇒ `rFine` 翻倍（允许整数化误差，这里恰好整除）。 */
    @Test
    fun fineRadiusIsMonotonicInSharpenRadius() {
        var last = 0
        for (sr in listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f)) {
            val r = DetailPass.radii(4000, 3000, EditParams(sharpenAmount = 1f, sharpenRadius = sr))[0]
            assertTrue("sharpenRadius=$sr 时半径不应回退（上次 $last，本次 $r）", r >= last)
            last = r
        }
        // 长边 1000 -> 2000，半径 1 -> 2
        assertEquals(
            2 * DetailPass.radii(1000, 800, EditParams(sharpenAmount = 1f))[0],
            DetailPass.radii(2000, 1600, EditParams(sharpenAmount = 1f))[0]
        )
    }

    /**
     * ⭐ 跨过 `h <= band + 2·halo` 分流线的一串高度，两条路径都必须与朴素参照逐位相同。
     *
     * 高度集合刻意在 130..137 之间密集取值 —— 那是 `band(128) + 2·halo` 的分界附近
     * （本用例里 `rFine = 1`、`rCoarse = 2` ⇒ `halo = 3` ⇒ 分界在 134）。
     * 宽度取 17（质数）以避开任何「宽度是 2 的幂」的隐含假设。
     *
     * ⚠️ 这条抓到过一个真实缺陷：halo 曾按 `rCoarse` 取值（少了 `rFine`），
     * 失败**只**出现在每两条带的接缝两侧各一行，且只差 1~2 级 —— 真机上绝对看不出来。
     */
    @Test
    fun everySizeMatchesNaiveReference() {
        for (h in intArrayOf(1, 2, 5, 63, 64, 129, 130, 131, 132, 133, 134, 135, 136, 137, 200, 300, 320)) {
            val w = 17
            val px = randomPixels(w * h, seed = h.toLong())
            val expected = naiveDetail(px, w, h, P)
            val actual = px.copyOf()
            DetailPass.apply(actual, w, h, P)
            assertArrayEquals("h=$h（分流线在 134：<=134 走整幅，>=135 走分带）", expected, actual)
        }
    }

    /** 宽图（跨多带、`w` 远大于 `rMax`）的等价性，补一条与上面不同长宽比的用例。 */
    @Test
    fun wideImageMatchesNaiveReference() {
        val w = 64
        val h = 300
        val px = randomPixels(w * h, seed = 20260923L)
        val expected = naiveDetail(px, w, h, P)
        val actual = px.copyOf()
        DetailPass.apply(actual, w, h, P)
        assertArrayEquals(expected, actual)
    }

    /**
     * 锐化在**阶梯边缘**上必须把亮的推更亮、暗的推更暗。
     *
     * ⚠️ 这条同时守住了 `sharpenMasking = 0 ⇒ 全图锐化` 的特判：`guard` 在阈值 0 时返回 0
     * （「细节 = 100% ⇒ 不做降噪」的语义），若把 `tMask` 直接交给 `guard`，蒙版为 0 时
     * 锐化会**完全失效**（偏移恒为 0）—— 而蒙版为 0 正是默认值，于是「锐化滑块没反应」。
     */
    @Test
    fun sharpenIncreasesEdgeContrast() {
        val w = 32
        val h = 16
        val lo = 100
        val hi = 156
        val mid = w / 2
        val px = IntArray(w * h) { i -> if (i % w < mid) gray(lo) else gray(hi) }

        val out = px.copyOf()
        DetailPass.apply(out, w, h, EditParams(sharpenAmount = 1f, sharpenRadius = 1f))

        val leftAfter = red(out[8 * w + (mid - 1)])
        val rightAfter = red(out[8 * w + mid])
        assertTrue("边缘左侧应被推暗（$lo -> $leftAfter）", leftAfter < lo)
        assertTrue("边缘右侧应被推亮（$hi -> $rightAfter）", rightAfter > hi)
    }

    /**
     * 蒙版拉高后，**强边缘**处的锐化量必须被衰减（但仍然 > 0）。
     *
     * 阶梯特意做得比 `SHARP_MASK_T`（25.5 级）更高：低于阈值时 `guard` 恒为 1，
     * 蒙版形同虚设，那个用例测不出任何东西。
     */
    @Test
    fun sharpenMaskingProtectsStrongEdges() {
        val w = 32
        val h = 16
        val lo = 80
        val hi = 190
        val mid = w / 2
        val px = IntArray(w * h) { i -> if (i % w < mid) gray(lo) else gray(hi) }

        val noMask = px.copyOf()
        DetailPass.apply(noMask, w, h, EditParams(sharpenAmount = 1f, sharpenMasking = 0f))
        val masked = px.copyOf()
        DetailPass.apply(masked, w, h, EditParams(sharpenAmount = 1f, sharpenMasking = 1f))

        val shiftNoMask = abs(red(noMask[8 * w + (mid - 1)]) - lo)
        val shiftMasked = abs(red(masked[8 * w + (mid - 1)]) - lo)
        assertTrue("蒙版必须真的削掉一部分锐化量（$shiftMasked 应 < $shiftNoMask）", shiftMasked < shiftNoMask)
        assertTrue("但仍应有锐化，不能削成 0", shiftMasked > 0)
    }

    /**
     * 亮度降噪的「细节」拉满 ⇒ 该级**完全不改像素**（阈值 0 ⇒ `guard` 返回 0）。
     *
     * 这是「要细节就别降噪」这条语义的实现方式，也是浮点比较里少数用 `assertArrayEquals`
     * 而不是容差比较的地方 —— 它必须**逐位**不变，差一位就说明乘性缩放引入了舍入。
     */
    @Test
    fun maxLuminanceDetailDisablesLuminanceNr() {
        val w = 24
        val h = 16
        val px = randomPixels(w * h, seed = 99L)
        val before = px.copyOf()

        DetailPass.apply(px, w, h, EditParams(nrLuminance = 1f, nrLuminanceDetail = 1f))
        assertArrayEquals(before, px)
    }

    /**
     * 色度降噪**不改变亮度**。
     *
     * 实现里由 `Y = R_Y·r + G_Y·g + B_Y·b` 反解 `g`，三权重之和为 1 ⇒ 亮度应恰为 `yd`。
     * 这里造一张「亮度处处相等、色度随机」的图（用 `g` 的反解来构造），然后断言输出的亮度
     * 与输入的亮度在 1 级以内 —— 容差来自 `clamp8` 的 `roundToInt`（±0.5）与浮点累加。
     */
    @Test
    fun chromaNrPreservesLuma() {
        val w = 20
        val h = 20
        val luma = 128f
        val rnd = Random(4242L)
        val px = IntArray(w * h) {
            val r = 100 + rnd.nextInt(56)
            val b = 100 + rnd.nextInt(56)
            val g = ((luma - R_Y * r - B_Y * b) / G_Y).roundToInt().coerceIn(0, 255)
            pack(r, g, b)
        }

        val out = px.copyOf()
        DetailPass.apply(out, w, h, EditParams(nrColor = 1f))

        for (i in out.indices) {
            val yIn = lumaOf(px[i])
            val yOut = lumaOf(out[i])
            assertTrue("像素 $i 的亮度被改动：$yIn -> $yOut", abs(yOut - yIn) <= 1.5f)
        }
    }

    // ————————————————————— 朴素参照（独立实现）—————————————————————

    /**
     * 朴素参照：全幅、直接卷积、半径自己算、严格照参数语义逐像素施加。
     *
     * 「独立」体现在三处：**不**调用 `DetailPass.radii` / `guard` / `clamp8`，
     * 模糊用**双层直接求和**而不是滑动累加。所以它能抓到的错误包括
     * 「`radii` 口径漂了」「滑动累加的滑窗加减方向错了」「clamp 边界用补零」——
     * 这些都是「实现与自己的旧版本保持一致、但与规格不符」的典型。
     */
    private fun naiveDetail(src: IntArray, w: Int, h: Int, p: EditParams): IntArray {
        if (DetailPass.isNeutral(p)) return src.copyOf()

        val l = maxOf(w, h)
        val rFine = maxOf(1, (l / 1000f * p.sharpenRadius.coerceIn(0.5f, 3f)).roundToInt())
        val rCoarse = maxOf(rFine + 1, (l / 120f).roundToInt())

        val b1 = naiveBlur(src, w, h, rFine)
        val b2 = naiveBlur(b1, w, h, rCoarse)

        val nrLum = p.nrLuminance.coerceIn(0f, 1f)
        val nrChr = p.nrColor.coerceIn(0f, 1f)
        val clarity = p.clarity.coerceIn(-1f, 1f)
        val texture = p.texture.coerceIn(-1f, 1f)
        val sharpen = p.sharpenAmount.coerceIn(0f, 1f)
        val tLum = NR_LUMA_T * (1f - p.nrLuminanceDetail.coerceIn(0f, 1f))
        val tChr = NR_CHROMA_T * (1f - p.nrColorDetail.coerceIn(0f, 1f))
        val tMask = SHARP_MASK_T * p.sharpenMasking.coerceIn(0f, 1f)
        val detail = p.sharpenDetail.coerceIn(0f, 1f)

        val out = IntArray(w * h)
        for (i in src.indices) {
            val c = src[i]
            var rf = ((c shr 16) and 0xff).toFloat()
            var gf = ((c shr 8) and 0xff).toFloat()
            var bf = (c and 0xff).toFloat()
            val y0 = R_Y * rf + G_Y * gf + B_Y * bf
            val y1 = lumaOf(b1[i])
            val y2 = lumaOf(b2[i])

            // (A) 亮度降噪：三通道等比缩放（保色相）
            var yd = y0
            if (nrLum > 0f) {
                val dy = y1 - y0
                yd = y0 + dy * nrLum * naiveGuard(dy, tLum)
                val k = if (y0 > 1e-3f) yd / y0 else 1f
                rf *= k
                gf *= k
                bf *= k
            }

            // (B) 色度降噪：拉 (R−Y, B−Y)，再由亮度反解 G
            if (nrChr > 0f) {
                val cr = rf - yd
                val cb = bf - yd
                val b2c = b2[i]
                val cr2 = ((b2c shr 16) and 0xff).toFloat() - y2
                val cb2 = (b2c and 0xff).toFloat() - y2
                val f = nrChr * naiveGuard(maxOf(abs(cr2 - cr), abs(cb2 - cb)), tChr)
                val crn = cr + (cr2 - cr) * f
                val cbn = cb + (cb2 - cb) * f
                rf = yd + crn
                bf = yd + cbn
                gf = yd - (R_Y * crn + B_Y * cbn) / G_Y
            }

            // (C)(D)(E) 三个「三通道同加」的偏移
            var off = 0f
            if (clarity != 0f) {
                val low = yd - y2
                off += clarity * K_CLARITY * low * naiveGuard(low, CLARITY_T)
            }
            if (texture != 0f) {
                val mid = y1 - y2
                off += texture * K_TEXTURE * mid * naiveGuard(mid, TEXTURE_T)
            }
            if (sharpen > 0f) {
                val hi = yd - y1
                val mid = y1 - y2
                val m = if (tMask <= 0f) 1f else naiveGuard(hi, tMask)
                off += sharpen * K_SHARP * m * (hi + detail * mid)
            }
            rf += off
            gf += off
            bf += off

            out[i] = pack(naiveClamp8(rf), naiveClamp8(gf), naiveClamp8(bf))
        }
        return out
    }

    /** 分离式盒式模糊，**直接求和**（先横后纵，与实现的 `boxPass` 调用顺序一致）。 */
    private fun naiveBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val win = r * 2 + 1
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sr = 0
                var sg = 0
                var sb = 0
                for (k in -r..r) {
                    val v = src[row + (x + k).coerceIn(0, w - 1)]
                    sr += (v shr 16) and 0xff
                    sg += (v shr 8) and 0xff
                    sb += v and 0xff
                }
                tmp[row + x] = pack(sr / win, sg / win, sb / win)
            }
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sr = 0
                var sg = 0
                var sb = 0
                for (k in -r..r) {
                    val v = tmp[(y + k).coerceIn(0, h - 1) * w + x]
                    sr += (v shr 16) and 0xff
                    sg += (v shr 8) and 0xff
                    sb += v and 0xff
                }
                out[y * w + x] = pack(sr / win, sg / win, sb / win)
            }
        }
        return out
    }

    /** 与 `DetailPass.guard` 同语义：`t ≤ 0 ⇒ 0`；`|d| ≤ t ⇒ 1`；否则 `t/|d|`。 */
    private fun naiveGuard(d: Float, t: Float): Float {
        if (t <= 0f) return 0f
        val a = abs(d)
        return if (a <= t) 1f else t / a
    }

    private fun naiveClamp8(v: Float): Int = v.roundToInt().coerceIn(0, 255)

    private fun lumaOf(packed: Int): Float =
        R_Y * ((packed shr 16) and 0xff) + G_Y * ((packed shr 8) and 0xff) + B_Y * (packed and 0xff)

    // ————————————————————— 小工具 —————————————————————

    private fun pack(r: Int, g: Int, b: Int): Int =
        0xff000000.toInt() or (r shl 16) or (g shl 8) or b

    private fun gray(v: Int): Int = pack(v, v, v)

    private fun red(packed: Int): Int = (packed shr 16) and 0xff

    private fun randomPixels(n: Int, seed: Long): IntArray {
        val rnd = Random(seed)
        return IntArray(n) { pack(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
    }
}
