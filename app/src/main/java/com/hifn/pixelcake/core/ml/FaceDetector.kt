package com.hifn.pixelcake.core.ml

/**
 * 人脸检测模型的抽象（P1p-2b）。
 *
 * **抽出接口的目的与 [SkinMaskModel] 完全一致**：
 * 1. **可测**：`android.graphics.Bitmap` 与 LiteRT 原生库在 JVM 单测里都不可用，
 *    有了接口就能注入 fake，把「letterbox 换算 / 坐标投回 / 降级分支」测透；
 * 2. **可换**：将来换 `short_range` 或换 NPU 版本（P1p-3）时，上层 [MlFaceProvider] 不用动。
 *
 * **约定**：实现类**不得向上抛异常** —— 推理失败一律返回 `null`，由调用方按
 * `docs/P1p_DESIGN.md` §9 的降级链回退（退回「按蒙版质心猜」这一 P1 行为）。
 *
 * **坐标口径**：返回的是**[张量归一化坐标]**（相对模型输入方图，`[0,1]`），
 * 不是源图像素 —— 因为投回源图需要源图尺寸，那是 [LetterboxTransform] 的职责。
 * 实现方**不得**自行做 letterbox（预处理由 [MlFaceProvider] 完成）。
 */
interface FaceDetector {

    /** 模型输入张量边长（正方形，本设计 192）。 */
    val side: Int

    /** 实际生效的加速器名（`GPU`/`CPU`/…），用于日志与真机核对。 */
    val acceleratorName: String

    /**
     * 检测。
     *
     * @param argb 长度须为 `side * side` 的 **已 letterbox** ARGB_8888 像素
     *             （越界区域应填 [LetterboxTransform.BORDER_ARGB]）
     * @return 张量归一化坐标下的人脸（可能为空列表 = 成功但**无人脸**）；
     *         **失败返回 `null`** —— 两者语义不同，调用方据此区分「降级」与「图里没人」
     */
    fun detect(argb: IntArray): List<NormFace>?

    fun close()
}
