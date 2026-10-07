package com.hifn.pixelcake.core.edit

/**
 * P1b-4 人像算子参数（独立于 tonal 的 [EditParams]，作为 retouch 附加层）。
 * 见 `docs/PHASE_DESIGN_HISTORY.md`（P1b 部分） §3：扁平 [EditParams] 保持不动，retouch 走这里，
 * 撤销/重做与 tonal 同生命周期管理。
 */
data class NeutralGrayParams(
    val strength: Float = 0f,
    val radiusPx: Int = 4,
    val threshold: Int = 24,
    /** 模糊半径（占图像短边比例，分辨率无关，保证预览/导出所见即所得）。渲染时换算为 radiusPx。 */
    val radiusNorm: Float = 0.01f
)

/**
 * 人体部位细分美容参数。
 *
 * 把「人像精修」从「整张脸 + 眼睛」细分为：
 * - 头部/脸型（脸型、下巴、额头）
 * - 眼部（放大、祛黑眼圈）
 * - 唇部（增润、提亮）
 * - 身体/躯干（磨皮）
 * - 腿部（磨皮、拉长）
 * - 手部（去黄、提亮）
 *
 * 每个部位独立的强度，让用户可以「只修皮肤，不动轮廓」或「只放大眼睛」。
 */
data class BodyPartBeautyParams(
    /** 头部/脸型缩放 −1..1（负 = 缩）。 */
    val head: Float = 0f,
    /** 下巴整形 −1..1。 */
    val jaw: Float = 0f,
    /** 额头宽度 −1..1。 */
    val forehead: Float = 0f,
    /** 眼部放大 0..1。 */
    val eyeEnlarge: Float = 0f,
    /** 眼部祛黑眼圈 −1..1（正 = 祛）。 */
    val eyeDarkCircle: Float = 0f,
    /** 唇部增润 0..1。 */
    val lipPlump: Float = 0f,
    /** 唇部提亮 −1..1。 */
    val lipBrighten: Float = 0f,
    /** 面部磨皮（NeutralGray 级别）0..1。 */
    val faceSkin: Float = 0f,
    /** 身体/躯干磨皮 0..1。 */
    val bodySkin: Float = 0f,
    /** 腿部磨皮 0..1。 */
    val legSkin: Float = 0f,
    /** 腿部拉长 0..1。 */
    val legLength: Float = 0f,
    /** 手部去黄 −1..1（正 = 去黄提亮）。 */
    val handBrighten: Float = 0f,
    /** 手部细节/清晰度 −1..1。 */
    val handDetail: Float = 0f,
) {
    val isIdentity: Boolean get() =
        head == 0f && jaw == 0f && forehead == 0f && eyeEnlarge == 0f &&
            eyeDarkCircle == 0f && lipPlump == 0f && lipBrighten == 0f &&
            faceSkin == 0f && bodySkin == 0f && legSkin == 0f &&
            legLength == 0f && handBrighten == 0f && handDetail == 0f
}

/**
 * 人像精修总开关：哪些部位的美容生效。
 *
 * 当用户只想局部精修（如只放大眼睛、不做磨皮）时，通过此开关控制。
 *
 * ⚠️ **当前只有「头部/眼部/面部磨皮」三个开关真正接进了管线**（它们控制的是
 * [BeautyParams.slimFace] / [slimJaw] / [wiredEyeEnlarge] 这三个**已实现**的量）。
 * 其余四个开关对应的部位级算子尚未实现，UI 已不再暴露它们（见 [BodyPartBeautyParams]
 * 的「接线状态」一节）—— 暴露一个不生效的开关比不暴露更糟：用户会以为功能坏了。
 */
data class RetouchSwitches(
    val enableHead: Boolean = true,
    val enableEyes: Boolean = true,
    val enableLips: Boolean = false,
    val enableFaceSkin: Boolean = true,
    val enableBodySkin: Boolean = false,
    val enableLegs: Boolean = false,
    val enableHands: Boolean = false,
)

data class BeautyParams(
    val slimFace: Float = 0f,
    val slimJaw: Float = 0f,
    val eyeEnlarge: Float = 0f,
    /** 细部位美容（批次 5）。 */
    val bodyParts: BodyPartBeautyParams = BodyPartBeautyParams(),
    /** 哪些部位生效。 */
    val switches: RetouchSwitches = RetouchSwitches(),
    val skinSmoothingStrength: Float = 0f,
    val skinDetail: Float = 0.5f,
    val teethWhiten: Float = 0f,
    val hairRefine: Float = 0f,
    val browEnhance: Float = 0f,
    val eyelashEnhance: Float = 0f,
    val noseSlim: Float = 0f,
    val cheekboneEnhance: Float = 0f,
    val lipColor: Float = 0f,
    val underEyeBrighten: Float = 0f,
    val neckRefine: Float = 0f,
    val shoulderRefine: Float = 0f,
    val waistSlender: Float = 0f,
) {
    /**
     * **真正驱动液化管线**的大眼量。
     *
     * ⚠️ 历史上这里有两个**同名不同字段**的「大眼」：[eyeEnlarge] 与
     * [BodyPartBeautyParams.eyeEnlarge]，而渲染侧只读前者 ⇒ 用户在「眼部」页拖的那条
     * 完全不生效（而 UI 上它看起来和其他滑块毫无区别）。现在统一取**两者较大值**，
     * 两处入口都只认这一个判据，`Beauty.apply` / `RetouchLayer` 的 early-return
     * 也因此与真正会执行的算子一致。
     */
    val wiredEyeEnlarge: Float get() = maxOf(eyeEnlarge, bodyParts.eyeEnlarge)

    /**
     * 是否真的会改变像素（供 [RetouchLayer] 判断要不要进液化阶段）。
     *
     * 只认**已接线**的三项：瘦脸 / 下颌 / 大眼。其余字段（`skinSmoothingStrength`、
     * `teethWhiten`、`noseSlim` …）目前**没有任何算子读取**，把它们算进来会让
     * 「拖了没反应的滑块」白白触发一次包围盒扫描 + 蒙版质心计算。
     */
    val hasWiredLiquify: Boolean
        get() = slimFace > 0f || slimJaw > 0f || wiredEyeEnlarge > 0f
}

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
