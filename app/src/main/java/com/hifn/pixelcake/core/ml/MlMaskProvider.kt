package com.hifn.pixelcake.core.ml

import android.content.Context
import android.graphics.Bitmap
import com.hifn.pixelcake.diag.DebugLog

/**
 * ML 蒙版的进程级提供者（P1p-1）。
 *
 * 职责（`docs/P1p_DESIGN.md` §6/§9）：
 * 1. **懒加载**模型（首次真正需要时才建，避免拖慢冷启动）；GPU→CPU 级联在 [LiteRtSkinMaskModel] 内完成；
 * 2. **每图一次推理 + 缓存**：以调用方给的 [key] 标识源图；预览与导出复用同一蒙版对象
 *    （`MlSkinMask.resampleTo` 只换尺寸、共享网格），因此「预览所见 = 导出所得」；
 * 3. **降级**：模型缺失 / 推理失败 → 返回 `null`，由调用方回退「画笔蒙版 → `FullMask`」；
 *    发生 `OutOfMemoryError` 则**本会话直接关闭**自动蒙版，避免反复触发；
 * 4. 全程打 [DebugLog.TAG_ML] 日志，便于真机回传日志核对「是否真的走了 GPU / 模型是否加载成功」。
 *
 * 注意：本对象**不持有 Bitmap**，只缓存 256×256 的浮点网格（≈256KB）。
 */
object MlMaskProvider {

    private var model: SkinMaskModel? = null
    private var modelResolved = false

    /** OOM 等致命失败后置位：本会话不再尝试自动蒙版。 */
    private var disabled = false

    private var cacheKey: String? = null
    private var cacheMask: MlSkinMask? = null

    /** 自动蒙版当前是否可用（模型已加载且未因 OOM 关闭）。 */
    val available: Boolean get() = !disabled && model != null

    /** 已加载模型的加速器名（`GPU` / `CPU`）；模型未加载时为 `null`。供 UI 展示与真机验收核对。 */
    val accelerator: String? get() = model?.acceleratorName

    /**
     * 为 [src] 生成皮肤蒙版；返回对象的 [MlSkinMask.resampleTo] 可安全用于任意目标尺寸。
     *
     * `@Synchronized`：编辑器的预览重渲与导出可能并发进入（两者都在 `Dispatchers.Default`），
     * 串行化可避免同一 key 被重复推理、以及缓存字段被交错写坏。
     *
     * @param key 源图标识（建议 `uri + 尺寸`）；相同 key 命中缓存，不重复推理
     * @return 失败返回 `null`（**不抛异常**）
     */
    @Synchronized
    fun skinMaskFor(context: Context, src: Bitmap, key: String): MlSkinMask? {
        if (disabled) return null
        if (cacheMask != null && cacheKey == key) return cacheMask

        val m = ensureModel(context) ?: return null
        return try {
            val side = m.side
            val small = if (src.width == side && src.height == side) {
                src
            } else {
                Bitmap.createScaledBitmap(src, side, side, true)
            }
            val argb = IntArray(side * side)
            small.getPixels(argb, 0, side, 0, 0, side, side)
            if (small !== src) small.recycle()

            val probs = m.inferProbs(argb) ?: return null
            val mask = MlSkinMask.fromProbs(probs, side, src.width, src.height)
            cacheKey = key
            cacheMask = mask
            DebugLog.i(
                DebugLog.TAG_ML,
                "skin mask ready",
                mapOf("key" to key, "grid" to mask.gridSide, "accel" to m.acceleratorName),
            )
            mask
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) {
                disabled = true
                DebugLog.e(DebugLog.TAG_ML, "OOM -> auto mask disabled for this session", mapOf("key" to key))
            } else {
                DebugLog.e(
                    DebugLog.TAG_ML,
                    "skin mask failed",
                    mapOf("key" to key, "err" to (t.message ?: t.javaClass.simpleName)),
                )
            }
            null
        }
    }

    /** 同步懒加载（重复调用只解析一次）。 */
    private fun ensureModel(context: Context): SkinMaskModel? {
        model?.let { return it }
        synchronized(this) {
            model?.let { return it }
            if (modelResolved) return null
            modelResolved = true
            val m = LiteRtSkinMaskModel.createOrNull(context.applicationContext)
            model = m
            if (m == null) {
                DebugLog.w(
                    DebugLog.TAG_ML,
                    "auto mask unavailable -> callers fall back to brush/FullMask",
                )
            } else {
                DebugLog.i(
                    DebugLog.TAG_ML,
                    "skin mask model loaded",
                    mapOf(
                        "accel" to m.acceleratorName,
                        "side" to m.side,
                        "asset" to LiteRtSkinMaskModel.MODEL_ASSET,
                    ),
                )
            }
            return m
        }
    }

    /** 源图变了（重新打开/换图）时调用，丢掉上一次的蒙版缓存。 */
    fun invalidate() {
        cacheKey = null
        cacheMask = null
    }

    /** 完全重置（含模型与失败标记）；供调试/测试用。 */
    fun reset() {
        runCatching { model?.close() }
        model = null
        modelResolved = false
        disabled = false
        invalidate()
    }
}
