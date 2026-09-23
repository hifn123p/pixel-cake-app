package com.hifn.pixelcake.core.edit

import android.graphics.Bitmap
import com.hifn.pixelcake.diag.DebugLog

/**
 * [DetailPass] 的 Android 侧桥接：把 `Bitmap` 包成 [DetailPass.Plane]，并统一记录耗时。
 *
 * ## 为什么单独一个文件，而不是塞进 `DetailPass` 或 `EditEngine`
 *
 * - **不塞进 [DetailPass]**：那个文件刻意做到**零 Android 依赖**（纯 Kotlin 运算，可在 JVM 单测里
 *   跑等价性回归，与 `ColorMath` 的约定一致）。`Bitmap` 只要出现在同一个文件里，这个性质就没了 ——
 *   所以「需要 Android 的那一点点」被挤到这一个文件。
 * - **不塞进 [EditEngine]**：那样 [EditEngine] 就要多出「一个私有类 + 一段计时逻辑」两处与三条入口
 *   无关的成员，而三条入口各自只需要**一行调用**。抽成文件级函数后，入口处的改动收敛成一行。
 *
 * ## 为什么是 internal 而不是 public
 *
 * 它不是产品 API，只是 `core/edit` 内部的分工：唯一的调用方是 [EditEngine]。
 */
internal class BitmapPlane(private val bmp: Bitmap) : DetailPass.Plane {

    override val width: Int get() = bmp.width
    override val height: Int get() = bmp.height

    /**
     * 语义对齐 `Bitmap.getPixels`：读出的若干行在 `dst` 里是**紧排**的（步长 = 宽）。
     *
     * 传 `stride = width` 而不是 `dst` 的真实步长：`Bitmap.getPixels` 的 `stride` 是**像素数**步长，
     * 只有在等于 `width` 时行才是连续的。这里的所有缓冲都是按 `行 × 宽` 紧排分配的，所以两者相等。
     */
    override fun readRows(y0: Int, rows: Int, dst: IntArray, dstOffset: Int) {
        bmp.getPixels(dst, dstOffset, width, 0, y0, width, rows)
    }

    override fun writeRows(y0: Int, rows: Int, src: IntArray, srcOffset: Int) {
        bmp.setPixels(src, srcOffset, width, 0, y0, width, rows)
    }
}

/**
 * 细节阶段的最慢日志阈值（毫秒）。
 *
 * ⚠️ 与 `EditEngine.SLOW_RENDER_MS` 同为 150ms，但**不能共用一个常量**：那个是 `object EditEngine`
 * 的私有成员，把它改成 internal 只为了共享一个数字，会让「哪些东西是引擎的对外面」变模糊。
 * 两处都是「超过就不正常」的经验值，各自贴着各自的上下文（一个是整帧、一个是单阶段）更清楚。
 * 改其一时记得看一眼另一个。
 */
private const val SLOW_DETAIL_MS = 150L

/**
 * 在已物化的目标位图上跑一遍细节（批次 4），并记录耗时。
 *
 * 三条渲染入口（[EditEngine.renderIntoSrgb] / [EditEngine.renderIntoLinear] /
 * [EditEngine.renderLinearFile]）都必须在**人像精修之后**调用它，理由见 [DetailPass] 的类 KDoc
 * （锐化必须晚于磨皮，否则磨皮会把刚锐出来的边缘糊掉）。
 *
 * 中性时直接返回 —— 这条短路与 [DetailPass.isNeutral] 是**同一个判据**：两处不一致就会出现
 * 「白跑一遍邻域但没改任何像素」这种最难查的性能问题（画面正常，只是莫名变慢）。
 *
 * @param logAlways 导出走 `true`（低频操作，且这正是要盯的性能基线）；实时预览走 `false`
 *   （拖动滑块时每帧都写日志既刷屏、又因为 `DebugLog` 每次 `flush` 而反过来拖慢渲染，
 *   把要测的东西本身污染掉 —— 这条教训来自批次 1 的慢渲染 WARN）。
 */
internal fun applyDetailPass(target: Bitmap, p: EditParams, logAlways: Boolean) {
    if (DetailPass.isNeutral(p)) return
    val t0 = System.nanoTime()
    DetailPass.apply(BitmapPlane(target), p)
    val ms = (System.nanoTime() - t0) / 1_000_000
    if (logAlways || ms >= SLOW_DETAIL_MS) {
        DebugLog.i(
            DebugLog.TAG_EDIT, "detail pass",
            mapOf("w" to target.width, "h" to target.height, "ms" to ms)
        )
    }
}
