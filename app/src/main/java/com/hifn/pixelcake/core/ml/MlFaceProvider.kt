package com.hifn.pixelcake.core.ml

import android.content.Context
import android.graphics.Bitmap
import com.hifn.pixelcake.diag.DebugLog

/**
 * 人脸检测的进程级提供者（P1p-2b）。
 *
 * 职责与 [MlMaskProvider] 对齐：
 * 1. **懒加载**模型（首次真正需要时才建，避免拖慢冷启动）；GPU→CPU 级联在 [LiteRtFaceDetector] 内完成；
 * 2. **每图一次检测 + 缓存**：以调用方给的 [key] 标识源图，预览与导出复用同一结果；
 * 3. **降级**：模型缺失 / 推理失败 → 返回 `null`，调用方回退「按蒙版质心猜」（P1 行为）；
 *    发生 `OutOfMemoryError` 则**本会话直接关闭**人脸检测，避免反复触发；
 * 4. 全程打 [DebugLog.TAG_ML] 日志。
 *
 * **不持有 Bitmap**：只缓存几张脸的小对象；letterbox 用的临时 Bitmap 用完即回收。
 *
 * ⚠️ **与蒙版 provider 的关键差异 —— `null` 与「空列表」语义不同**：
 * - `null`：检测**不可用**（模型没起来 / 推理失败）⇒ 调用方应**降级**；
 * - `emptyList()`：检测**成功但图里没有人脸** ⇒ 调用方同样要降级，但这是「正常的无人脸」，
 *   两者对调用方的处理恰好一致（都回退质心），区分开只是为了日志与排障。
 */
object MlFaceProvider {

    private var model: FaceDetector? = null
    private var modelResolved = false

    /** OOM 等致命失败后置位：本会话不再尝试人脸检测。 */
    private var disabled = false

    private var cacheKey: String? = null
    private var cacheFaces: List<FaceDetection>? = null

    /** 已加载模型的加速器名（`GPU` / `CPU`）；未加载时为 `null`。 */
    val accelerator: String? get() = model?.acceleratorName

    /**
     * 对 [src] 做一次人脸检测，结果按**面积降序**（最大脸在前）。
     *
     * `@Synchronized`：预览重渲与导出可能并发进入（都在 `Dispatchers.Default`），
     * 串行化避免同一 key 被重复推理、缓存字段被交错写坏。
     *
     * @param key 源图标识（建议 `uri + 尺寸`）；相同 key 命中缓存
     * @return 见类 KDoc 对 `null` / 空列表的区分；**不抛异常**
     */
    @Synchronized
    fun facesFor(context: Context, src: Bitmap, key: String): List<FaceDetection>? {
        if (disabled) return null
        if (cacheFaces != null && cacheKey == key) return cacheFaces

        val detector = ensureModel(context) ?: return null
        return try {
            val side = detector.side
            if (src.width <= 0 || src.height <= 0) return null
            val t = LetterboxTransform(src.width, src.height, side, side)

            // 边框预置为 letterbox 规定的补齐色（黑），与 [-1,1] 归一化口径一致
            val argb = IntArray(side * side) { LetterboxTransform.BORDER_ARGB }
            if (t.scaledW == src.width && t.scaledH == src.height) {
                src.getPixels(argb, 0, side, t.offsetX, t.offsetY, src.width, src.height)
            } else {
                val scaled = Bitmap.createScaledBitmap(src, t.scaledW, t.scaledH, true)
                scaled.getPixels(argb, 0, side, t.offsetX, t.offsetY, t.scaledW, t.scaledH)
                if (scaled !== src) scaled.recycle()
            }

            val norms = detector.detect(argb) ?: return null
            val faces = norms.map { t.toSource(it) }.sortedByDescending { it.area }
            cacheKey = key
            cacheFaces = faces
            DebugLog.i(
                DebugLog.TAG_ML,
                "face detect ready",
                mapOf(
                    "key" to key,
                    "count" to faces.size,
                    "accel" to detector.acceleratorName,
                    // 最大脸的像素尺寸与分数 —— 真机核对「框住的是不是脸」的关键线索
                    "topW" to (faces.firstOrNull()?.width ?: -1f),
                    "topH" to (faces.firstOrNull()?.height ?: -1f),
                    "topScore" to (faces.firstOrNull()?.score ?: -1f),
                ),
            )
            faces
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) {
                disabled = true
                DebugLog.e(DebugLog.TAG_ML, "OOM -> face detect disabled for this session", mapOf("key" to key))
            } else {
                DebugLog.e(
                    DebugLog.TAG_ML,
                    "face detect failed",
                    mapOf("key" to key, "err" to (t.message ?: t.javaClass.simpleName)),
                )
            }
            null
        }
    }

    /** 同步懒加载（重复调用只解析一次）。 */
    private fun ensureModel(context: Context): FaceDetector? {
        model?.let { return it }
        synchronized(this) {
            model?.let { return it }
            if (modelResolved) return null
            modelResolved = true
            val m = LiteRtFaceDetector.createOrNull(context.applicationContext)
            model = m
            if (m == null) {
                DebugLog.w(
                    DebugLog.TAG_ML,
                    "face detector unavailable -> callers fall back to mask centroid",
                )
            } else {
                DebugLog.i(
                    DebugLog.TAG_ML,
                    "face detector loaded",
                    mapOf("accel" to m.acceleratorName, "side" to m.side, "asset" to LiteRtFaceDetector.MODEL_ASSET),
                )
            }
            return m
        }
    }

    /** 源图变了（重新打开/换图）时调用，丢掉上一次的缓存。 */
    fun invalidate() {
        cacheKey = null
        cacheFaces = null
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
