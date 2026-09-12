package com.hifn.pixelcake.core.edit.retouch

import com.hifn.pixelcake.core.edit.BeautyParams
import com.hifn.pixelcake.core.edit.ColorTransferParams
import com.hifn.pixelcake.core.edit.FullMask
import com.hifn.pixelcake.core.edit.InpaintStroke
import com.hifn.pixelcake.core.edit.NeutralGrayParams
import com.hifn.pixelcake.core.edit.RetouchMask
import com.hifn.pixelcake.core.edit.RetouchState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RetouchLayer` 分带编排的等价性（FIX_LIST R10 方案 A 收尾：`RetouchLayer.px` 流式化）。
 *
 * 参照实现 = 「整幅版」：一次取出整图 → **按设计顺序依次**跑四个算子 → 写回。
 * 分带版（[RetouchLayer.apply]）必须与之**逐位相同**。
 *
 * 这是「把整幅缓冲换成流式、却没悄悄改画质」的唯一可信护栏 —— 单靠 review 判断不了。
 * 后续再动 [RetouchLayer] / [NeutralGray] / [Beauty] / [Inpaint] / [ColorTransfer] 都必须让它保持绿。
 */
class RetouchLayerTest {

    /** 内存仓储：与 `Bitmap.getPixels/setPixels` 同语义（供 JVM 单测替代 Android Bitmap）。 */
    private class MemStore(override val w: Int, override val h: Int, val data: IntArray) : PixelStore {
        override fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
            for (row in 0 until height) {
                val s = (y + row) * w + x
                val d = offset + row * stride
                for (col in 0 until width) pixels[d + col] = data[s + col]
            }
        }

        override fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
            for (row in 0 until height) {
                val s = offset + row * stride
                val d = (y + row) * w + x
                for (col in 0 until width) data[d + col] = pixels[s + col]
            }
        }
    }

    /** 椭圆作用域，明显小于整幅 —— 用来验证「液化只在有界的源行跨度上开条带」。 */
    private val ellipse = object : RetouchMask {
        override fun sample(px: Int, py: Int): Float {
            val dx = (px - 30) / 18.0
            val dy = (py - 80) / 40.0
            return if (dx * dx + dy * dy <= 1.0) 1f else 0f
        }

        override fun resampleTo(w: Int, h: Int) = this
    }

    private fun noisy(w: Int, h: Int, seed0: Long = 4242L): IntArray {
        val px = IntArray(w * h)
        var seed = seed0
        for (i in px.indices) {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            val v = (seed % 256).toInt()
            val g = ((seed / 7) % 256).toInt()
            px[i] = 0xff000000.toInt() or (v shl 16) or (g shl 8) or (((v + g) / 2) and 0xff)
        }
        return px
    }

    /** 「整幅版」参照实现：一次取整图，按 磨皮 → 液化 → 祛瑕 → 追色 依次施加。 */
    private fun reference(
        src: IntArray, w: Int, h: Int, state: RetouchState, mask: RetouchMask?,
        anchor: RetouchLayer.FaceAnchor? = null
    ): IntArray {
        val px = src.copyOf()
        val m = mask?.resampleTo(w, h)
        NeutralGray.apply(px, w, h, state.neutralGray, m)
        // 液化锚点口径必须与编排一致：`RetouchLayer.beautyPhase` 把 `faceX/Y` 当 `centroid`、
        // `eyeX/Y` 当 `eyeCentroid`；`anchor == null` 时两者都为 null ⇒ Beauty 自行求蒙版质心（P1 口径）。
        Beauty.apply(
            px, w, h, state.beauty, m,
            centroid = anchor?.let { it.faceX to it.faceY },
            eyeCentroid = anchor?.let { it.eyeX to it.eyeY }
        )
        Inpaint.apply(px, w, h, state.inpaint)
        ColorTransfer.apply(px, w, h, state.colorTransfer)
        return px
    }

    private fun assertStreamingMatchesFullFrame(
        w: Int, h: Int, state: RetouchState, mask: RetouchMask?,
        anchor: RetouchLayer.FaceAnchor? = null
    ) {
        val src = noisy(w, h)
        val expected = reference(src, w, h, state, mask, anchor)
        val store = MemStore(w, h, src.copyOf())
        RetouchLayer.apply(store, state, mask, anchor)
        val i = expected.indices.firstOrNull { expected[it] != store.data[it] }
        if (i != null) {
            throw AssertionError(
                "流式与整幅不一致：首个差异 idx=$i (x=${i % w}, y=${i / w})，" +
                    "整幅=0x${Integer.toHexString(expected[i])} 流式=0x${Integer.toHexString(store.data[i])}"
            )
        }
    }

    /** 四个算子全开 + 小蒙版：磨皮走多带、液化走有界条带、祛瑕走小块、追色走逐带统计。 */
    @Test
    fun allOperatorsMatchFullFrame() {
        assertStreamingMatchesFullFrame(
            61, 300,
            RetouchState(
                neutralGray = NeutralGrayParams(strength = 0.7f, radiusPx = 4, threshold = 40),
                beauty = BeautyParams(slimFace = 0.5f, slimJaw = 0.3f, eyeEnlarge = 0.2f),
                inpaint = listOf(InpaintStroke(12, 20, 3), InpaintStroke(45, 250, 2)),
                colorTransfer = ColorTransferParams(refId = "portra", intensity = 0.8f)
            ),
            ellipse
        )
    }

    /** 蒙版覆盖整幅（[FullMask]）：液化源跨度退化为整幅，仍须与整幅版一致。 */
    @Test
    fun fullFrameMaskMatchesFullFrame() {
        assertStreamingMatchesFullFrame(
            53, 290,
            RetouchState(
                neutralGray = NeutralGrayParams(strength = 1f, radiusPx = 6, threshold = 30),
                beauty = BeautyParams(slimFace = 0.4f),
                colorTransfer = ColorTransferParams(refId = "jp", intensity = 0.6f)
            ),
            FullMask
        )
    }

    /**
     * 人脸锚点（P1p-2c）下的等价性 —— 这条用例是**「眼心参与源行跨度」**那处改动的护栏。
     *
     * `ellipse` 的支撑行是 `[40, 120]`、质心 `(30, 80)`。这里刻意让眼心落到 `y=18` —— 既在脸框中心
     * 上方，也**高出蒙版顶 40**：`eyeEnlarge` 把源点拉向眼心，蒙版顶那几行的源行会落到 36 附近。
     * 若 `beautyPhase` 仍按旧的 `min(蒙版顶, cy)` 取下界（= 39），这些取样就落到条带外被 clamp 回
     * 边界 ⇒ 与整幅版不一致，此用例立刻红。
     */
    @Test
    fun faceAnchorWithEyeAboveMaskTopMatchesFullFrame() {
        assertStreamingMatchesFullFrame(
            57, 320,
            RetouchState(
                neutralGray = NeutralGrayParams(strength = 0.6f, radiusPx = 3, threshold = 35),
                beauty = BeautyParams(slimFace = 0.5f, slimJaw = 0.4f, eyeEnlarge = 0.6f),
                colorTransfer = ColorTransferParams(refId = "jp", intensity = 0.7f)
            ),
            ellipse,
            RetouchLayer.FaceAnchor(faceX = 30f, faceY = 80f, eyeX = 28f, eyeY = 18f)
        )
    }

    /**
     * 锚点偏离蒙版（脸心偏到蒙版右侧、眼心远在蒙版**下方**）：跨度按锚点保守扩张后，
     * 仍须与整幅版逐位一致 —— 也就是「有界」和「正确」两个要求同时成立。
     */
    @Test
    fun faceAnchorOffsetFromMaskMatchesFullFrame() {
        assertStreamingMatchesFullFrame(
            50, 280,
            RetouchState(
                beauty = BeautyParams(slimFace = 0.7f, slimJaw = 0.5f, eyeEnlarge = 0.5f),
                inpaint = listOf(InpaintStroke(15, 22, 2))
            ),
            ellipse,
            RetouchLayer.FaceAnchor(faceX = 44f, faceY = 70f, eyeX = 46f, eyeY = 200f)
        )
    }

    /** 只磨皮：纯分带路径（多带 + 顶部 halo carry）。 */
    @Test
    fun neutralGrayOnlyMatchesFullFrame() {
        assertStreamingMatchesFullFrame(
            40, 520,
            RetouchState(neutralGray = NeutralGrayParams(strength = 0.9f, radiusPx = 5, threshold = 25)),
            FullMask
        )
    }

    /** 无追色（不必跑统计两趟）+ 描迹彼此靠近，验证小块顺序施加与整幅一致。 */
    @Test
    fun beautyAndInpaintWithoutColorTransferMatchFullFrame() {
        assertStreamingMatchesFullFrame(
            48, 275,
            RetouchState(
                beauty = BeautyParams(slimJaw = 0.6f, eyeEnlarge = 0.4f),
                inpaint = listOf(InpaintStroke(20, 20, 2), InpaintStroke(24, 26, 3))
            ),
            ellipse
        )
    }

    /** `mask == null`：磨皮/液化不执行，祛瑕/追色照旧 —— 与整幅版（同样传 null）一致。 */
    @Test
    fun nullMaskMatchesFullFrameReference() {
        assertStreamingMatchesFullFrame(
            44, 270,
            RetouchState(
                neutralGray = NeutralGrayParams(strength = 1f, radiusPx = 4, threshold = 40),
                beauty = BeautyParams(slimFace = 0.5f),
                inpaint = listOf(InpaintStroke(10, 10, 2)),
                colorTransfer = ColorTransferParams(refId = "retro", intensity = 1f)
            ),
            null
        )
    }

    /** 参数全空：应当一个像素都不动（也不该有任何读写）。 */
    @Test
    fun allNoOpLeavesPixelsUntouched() {
        val w = 33
        val h = 300
        val src = noisy(w, h)
        val store = MemStore(w, h, src.copyOf())
        RetouchLayer.apply(store, RetouchState(), FullMask)
        assertTrue("全空参数不应改动任何像素", src.contentEquals(store.data))
    }
}
