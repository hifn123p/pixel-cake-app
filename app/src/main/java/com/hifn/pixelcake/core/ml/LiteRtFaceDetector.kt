package com.hifn.pixelcake.core.ml

import android.content.Context
import com.hifn.pixelcake.diag.DebugLog
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment

/**
 * [FaceDetector] 的 LiteRT 实现（P1p-2b，`docs/P1p_DESIGN.md` §15）。
 *
 * 与 [LiteRtSkinMaskModel] 同构：**LiteRT v2 `CompiledModel`**、`Accelerator.GPU → CPU` 显式级联、
 * 一切失败收敛为 `null`。模型文件随包：
 * `app/src/main/assets/models/face_detection_full_range_sparse.tflite`
 * （MediaPipe BlazeFace Sparse Full Range，Apache-2.0，见根 `NOTICE`）。
 *
 * **预处理**（précis 自 `face_detection.pbtxt`）：输入 `float32 [1,192,192,3]`，
 * RGB 归一到 **`[-1, 1]`**（`pixel/127.5 - 1`；letterbox 边框 = 0（黑）⇒ −1）。
 *
 * **输出**：`regressors [1,2304,16]` + `classificators [1,2304,1]`。两个张量的**顺序**在
 * 不同导出里可能互换（sparse 版里名字是 `Identity` / `Identity_1`），故这里**按元素个数**认领
 * （`2304*16` 为框、`2304` 为分数），不依赖下标。
 */
class LiteRtFaceDetector private constructor(
    private val env: Environment?,          // 持有引用：Environment 生命周期必须覆盖 model
    private val model: CompiledModel,
    override val side: Int,
    override val acceleratorName: String,
) : FaceDetector {

    private val inputs = model.createInputBuffers()
    private val outputs = model.createOutputBuffers()

    /** 复用的输入缓冲，避免每次推理重新分配 ~1.1MB。 */
    private val rgb = FloatArray(side * side * 3)

    /** anchor 只与模型规格有关，构造期算一次（2304 个，可忽略）。 */
    private val anchors = FaceAnchors.generate(FaceAnchors.FULL_RANGE)

    override fun detect(argb: IntArray): List<NormFace>? {
        if (argb.size < side * side) return null
        var k = 0
        for (i in 0 until side * side) {
            val p = argb[i]
            // [-1, 1]：对齐 pbtxt 的 output_tensor_float_range
            rgb[k++] = ((p shr 16) and 0xff) / 127.5f - 1f
            rgb[k++] = ((p shr 8) and 0xff) / 127.5f - 1f
            rgb[k++] = (p and 0xff) / 127.5f - 1f
        }
        return try {
            inputs[0].writeFloat(rgb)
            model.run(inputs, outputs)
            val floats = outputs.map { it.readFloat() }
            // 按元素个数认领：不依赖输出张量下标（见类 KDoc）
            val boxes = floats.firstOrNull { it.size >= NUM_BOXES * FaceDetectionPostProcess.NUM_COORDS }
            val scores = floats.firstOrNull {
                it.size >= NUM_BOXES && it.size < NUM_BOXES * FaceDetectionPostProcess.NUM_COORDS
            }
            if (boxes == null || scores == null) {
                DebugLog.e(
                    DebugLog.TAG_ML,
                    "face detection output shape unexpected",
                    mapOf("sizes" to floats.joinToString(",") { it.size.toString() }),
                )
                null
            } else {
                FaceDetectionPostProcess.decodeAndNms(boxes, scores, anchors, DECODE)
            }
        } catch (t: Throwable) {
            DebugLog.e(DebugLog.TAG_ML, "face detection failed", kv("err", t))
            null
        }
    }

    override fun close() {
        runCatching { model.close() }
        runCatching { env?.close() }
    }

    companion object {

        /** 随包模型路径（相对 `assets/`）。 */
        const val MODEL_ASSET = "models/face_detection_full_range_sparse.tflite"

        /** 模型输入边长。 */
        const val SIDE = 192

        /** 输出锚框数（与 `num_boxes` 一致）。 */
        const val NUM_BOXES = 2304

        /** 解码参数（pbtxt：scale 192、阈值 0.6、IOU 0.3）。 */
        private val DECODE = FaceDetectionPostProcess.DecodeOptions.FULL_RANGE

        private fun kv(key: String, t: Throwable): Map<String, Any> =
            mapOf(key to (t.message ?: t.javaClass.simpleName))

        /**
         * 依次尝试 GPU → CPU 创建模型；全部失败返回 `null`（调用方回退）。
         * 失败一律记 [DebugLog.TAG_ML] 日志，便于真机回传定位。
         */
        fun createOrNull(context: Context): FaceDetector? {
            val appCtx = context.applicationContext
            val env = runCatching { Environment.create(appCtx) }.getOrElse { t ->
                DebugLog.w(DebugLog.TAG_ML, "Environment.create failed (face)", kv("err", t))
                null
            }
            var lastErr: Throwable? = null
            for (accel in listOf(Accelerator.GPU, Accelerator.CPU)) {
                val built = runCatching {
                    CompiledModel.create(
                        appCtx.assets,
                        MODEL_ASSET,
                        CompiledModel.Options(accel),
                        env,
                    )
                }.getOrElse { t ->
                    lastErr = t
                    DebugLog.w(
                        DebugLog.TAG_ML,
                        "CompiledModel.create failed (face)",
                        mapOf("accel" to accel.name, "err" to (t.message ?: t.javaClass.simpleName)),
                    )
                    null
                }
                if (built != null) {
                    // 构造（含 createInputBuffers / anchor 生成）也可能抛，一并收敛为 null
                    val instance = runCatching {
                        LiteRtFaceDetector(env, built, SIDE, accel.name)
                    }.getOrElse { t ->
                        lastErr = t
                        runCatching { built.close() }
                        DebugLog.w(
                            DebugLog.TAG_ML,
                            "face detector init failed",
                            mapOf("accel" to accel.name, "err" to (t.message ?: t.javaClass.simpleName)),
                        )
                        null
                    }
                    if (instance != null) return instance
                }
            }
            DebugLog.e(
                DebugLog.TAG_ML,
                "face detector unavailable",
                kv("lastErr", lastErr ?: IllegalStateException("unknown")),
            )
            return null
        }
    }
}
