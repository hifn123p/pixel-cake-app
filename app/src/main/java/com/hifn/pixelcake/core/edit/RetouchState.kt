package com.hifn.pixelcake.core.edit

/**
 * P1b-4 人像算子参数（独立于 tonal 的 [EditParams]，作为 retouch 附加层）。
 * 见 `docs/P1b_DESIGN.md` §3：扁平 [EditParams] 保持不动，retouch 走这里，
 * 撤销/重做与 tonal 同生命周期管理。
 */
data class NeutralGrayParams(
    val strength: Float = 0f,
    val radiusPx: Int = 4,
    val threshold: Int = 24
)

data class BeautyParams(
    val slimFace: Float = 0f,
    val slimJaw: Float = 0f,
    val eyeEnlarge: Float = 0f
)

data class InpaintStroke(
    val x: Int,
    val y: Int,
    val r: Int,
    val seed: Long = 0L
)

data class ColorTransferParams(
    val refId: String = "none",
    val intensity: Float = 0.6f
)

data class RetouchState(
    val neutralGray: NeutralGrayParams = NeutralGrayParams(),
    val beauty: BeautyParams = BeautyParams(),
    val inpaint: List<InpaintStroke> = emptyList(),
    val colorTransfer: ColorTransferParams = ColorTransferParams()
)
