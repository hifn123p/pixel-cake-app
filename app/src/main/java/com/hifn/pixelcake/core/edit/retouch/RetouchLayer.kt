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

    fun apply(bitmap: Bitmap, state: RetouchState, mask: RetouchMask?) {
        val w = bitmap.width
        val h = bitmap.height

        if (state.neutralGray.strength > 0f) {
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            val m = mask?.resampleTo(w, h)
            NeutralGray.apply(px, w, h, state.neutralGray, m)
            bitmap.setPixels(px, 0, w, 0, 0, w, h)
        }

        // Phase 3+：beauty（液化 remap）/ inpaint（stroke 补洞）/ colorTransfer（全局统计）在此追加。
    }
}
