package com.hifn.pixelcake.core.edit.retouch

import android.graphics.Bitmap
import com.hifn.pixelcake.core.edit.RetouchMask
import com.hifn.pixelcake.core.edit.RetouchState

/**
 * retouch 整图 pass 编排（P1b-4 / `docs/P1b_DESIGN.md` §2）。
 *
 * 在 [EditEngine] 的 tonal 分带渲染**之后**调用，对已物化的目标 Bitmap（8-bit）施加
 * 空间算子。复用已存在的目标 Bitmap，不额外搬 16-bit 母版，符合 F05 内存纪律。
 * 预览（代理）与导出（全分辨率）用同一算法 + 同一 Mask（经 `resampleTo` 对齐），
 * 保证「预览所见即导出所得」。
 */
object RetouchLayer {

    /**
     * 一次 [Bitmap.getPixels] 取出整图，按序施加四个算子（各算子内部在参数为空时 no-op），
     * 最后统一 [Bitmap.setPixels] 写回。比每算子各取一次省 3 趟整图拷贝，且算子间共享
     * 同一份中间像素，液化/祛瑕/追色依次叠加自然。
     *
     * 顺序（见 `docs/P1b_DESIGN.md` §2）：磨皮 → 液化 → 祛瑕 → 追色。
     *  - 磨皮 / 液化受 [mask] 调制（皮肤画笔作用域）；
     *  - 祛瑕按 [RetouchState.inpaint] 描迹补洞，不看 mask；
     *  - 追色为全局算子，无视 mask。
     */
    fun apply(bitmap: Bitmap, state: RetouchState, mask: RetouchMask?) {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val m = mask?.resampleTo(w, h)
        NeutralGray.apply(px, w, h, state.neutralGray, m)
        Beauty.apply(px, w, h, state.beauty, m)
        Inpaint.apply(px, w, h, state.inpaint)
        ColorTransfer.apply(px, w, h, state.colorTransfer)
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
    }
}
