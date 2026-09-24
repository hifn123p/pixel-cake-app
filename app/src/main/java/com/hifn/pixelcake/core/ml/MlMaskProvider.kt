package com.hifn.pixelcake.core.ml

import android.content.Context
import android.graphics.Bitmap
import com.hifn.pixelcake.diag.DebugLog

/**
 * ML 蒙版的进程级提供者（P1p-1，批次 5 起扩出对象作用域）。
 *
 * 职责（`docs/P1p_DESIGN.md` §6/§9、`docs/OBJECT_TONE_DESIGN.md` §8）：
 * 1. **懒加载**模型（首次真正需要时才建，避免拖慢冷启动）；GPU→CPU 级联在 [LiteRtSkinMaskModel] 内完成；
 * 2. **每图一次推理 + 缓存**：以调用方给的 [key] 标识源图；
 * 3. **一次推理、两种产物**：缓存的是**原始 6 类概率**，皮肤蒙版（[skinMaskFor]）与
 *    各对象作用域蒙版（[objectMasksFor]）都从这一份构造。若不这样做，同时开自动蒙版与
 *    对象作用域会**对同一张图跑两遍模型**（Pixel 6 基准 GPU ≈71ms / CPU ≈218ms）；
 * 4. **降级**：模型缺失 / 推理失败 → 返回 `null`，由调用方回退「画笔蒙版 → `FullMask`」
 *    （对象作用域则回退「只支持整图」）；发生 `OutOfMemoryError` 则**本会话直接关闭**，
 *    避免反复触发；
 * 5. 全程打 [DebugLog.TAG_ML] 日志，便于真机回传日志核对「是否真的走了 GPU / 模型是否加载成功」。
 *
 * 注意：本对象**不持有 Bitmap**，只缓存 256×256 的概率（≈1.5MB）与由它派生的网格（每个 ≈256KB，
 * 懒构建）。**绝不物化整幅蒙版** —— 33MP 下 `FloatArray(7008×4672)` 是 131MB。
 */
object MlMaskProvider {

    private var model: SkinMaskModel? = null
    private var modelResolved = false

    /** OOM 等致命失败后置位：本会话不再尝试自动蒙版。 */
    private var disabled = false

    /** 概率缓存的 key（含源图标识与尺寸）。只有它匹配才认为缓存有效。 */
    private var cacheKey: String? = null
    private var cacheProbs: FloatArray? = null
    private var cacheSide: Int = 0

    /** 由 [cacheProbs] 派生的两种产物，各自懒构建。key 变化时一起作废。 */
    private var cacheSkin: MlSkinMask? = null
    private var cacheObjects: ObjectMasks? = null

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
        if (!ensureProbs(context, src, key)) return null
        cacheSkin?.let { return it }
        val probs = cacheProbs ?: return null
        val mask = MlSkinMask.fromProbs(probs, cacheSide, src.width, src.height)
        cacheSkin = mask
        DebugLog.i(
            DebugLog.TAG_ML,
            "skin mask ready",
            mapOf("key" to key, "grid" to mask.gridSide, "accel" to accelerator),
        )
        return mask
    }

    /**
     * 为 [src] 生成**全部对象作用域**的蒙版（批次 5）。
     *
     * 与 [skinMaskFor] 共用同一份概率缓存 ⇒ 两者同时使用时**只推理一次**。
     * 各作用域的网格按需构建（用户建了几层就算几路，见 [ObjectMasks.builtGridCount]）。
     *
     * @return 失败返回 `null`（**不抛异常**）；调用方据此把对象作用域降级为「只支持整图」
     */
    @Synchronized
    fun objectMasksFor(context: Context, src: Bitmap, key: String): ObjectMasks? {
        if (!ensureProbs(context, src, key)) return null
        cacheObjects?.let { return it }
        val probs = cacheProbs ?: return null
        val masks = ObjectMasks.fromProbs(probs, cacheSide, src.width, src.height)
        cacheObjects = masks
        DebugLog.i(
            DebugLog.TAG_ML,
            "object masks ready",
            mapOf("key" to key, "side" to masks.gridSide, "accel" to accelerator),
        )
        return masks
    }

    /**
     * 保证 [key] 对应的**原始 6 类概率**已在缓存里（必要时跑一次推理）。
     *
     * ⚠️ **先成功、后写缓存**：`inferProbs` 失败时绝不写入 —— 若先把 `cacheKey` 改成新 key
     * 再赋值 probs，一次失败的推理就会把**上一张图**的概率留在「新 key」名下，
     * 之后所有调用都会命中一个属于别人的缓存。这类错误不会崩，只会让蒙版安静地套错图。
     */
    private fun ensureProbs(context: Context, src: Bitmap, key: String): Boolean {
        if (disabled) return false
        if (cacheProbs != null && cacheKey == key) return true
        if (src.width <= 0 || src.height <= 0) return false

        val m = ensureModel(context) ?: return false
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

            val probs = m.inferProbs(argb) ?: return false
            cacheKey = key
            cacheProbs = probs
            cacheSide = side
            // 换了图 ⇒ 派生产物必须一起作废（它们是从旧 probs 构建的）。
            cacheSkin = null
            cacheObjects = null
            true
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) {
                disabled = true
                DebugLog.e(DebugLog.TAG_ML, "OOM -> auto mask disabled for this session", mapOf("key" to key))
            } else {
                DebugLog.e(
                    DebugLog.TAG_ML,
                    "segment inference failed",
                    mapOf("key" to key, "err" to (t.message ?: t.javaClass.simpleName)),
                )
            }
            false
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

    /** 源图变了（重新打开/换图）时调用，丢掉上一次的概率与派生蒙版。 */
    @Synchronized
    fun invalidate() {
        cacheKey = null
        cacheProbs = null
        cacheSide = 0
        cacheSkin = null
        cacheObjects = null
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
