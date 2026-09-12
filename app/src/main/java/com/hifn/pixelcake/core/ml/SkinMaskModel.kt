package com.hifn.pixelcake.core.ml

/**
 * 皮肤分割模型的抽象。
 *
 * **抽出接口有两个目的**：
 * 1. **可测**：`android.graphics.Bitmap` 与 LiteRT 原生库在 JVM 单测里都不可用，
 *    有了接口就能在测试里注入 fake（返回构造好的概率数组），把后处理/降级逻辑测透；
 * 2. **可换**：将来上人脸解析（P1p-3）或 NPU 版本模型时，上层的 [MlMaskProvider] 不用动。
 *
 * **约定**：实现类**不得向上抛异常**——推理失败一律返回 `null`，由调用方按
 * `docs/P1p_DESIGN.md` §9 的降级链回退（回退到画笔蒙版 → `FullMask`）。
 */
interface SkinMaskModel {

    /** 模型输入边长（正方形，本设计固定 256）。 */
    val side: Int

    /** 实际生效的加速器名（`GPU`/`CPU`/…），用于日志与真机核对。 */
    val acceleratorName: String

    /**
     * 推理。
     *
     * @param argb 长度必须为 `side * side` 的 ARGB_8888 像素（**缩放由调用方负责**，
     *             这样纯逻辑与 Android 位图处理解耦）
     * @return 长度 `side * side * 6` 的 channel-last 概率；失败返回 `null`
     */
    fun inferProbs(argb: IntArray): FloatArray?

    fun close()
}
