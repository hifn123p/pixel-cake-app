package com.hifn.pixelcake.core.ml

import android.content.Context
import com.hifn.pixelcake.diag.DebugLog
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment

/**
 * [SkinMaskModel] 的 LiteRT 实现（`docs/P1p_DESIGN.md` §2.2）。
 *
 * 用 **LiteRT v2 `CompiledModel`**（不是已废弃的 TFLite + NNAPI）：
 * - 依赖 `com.google.ai.edge.litert:litert`（**仅 Google Maven**）；
 * - v2 上 GPU 加速器已并入主包，通过 `CompiledModel.Options(Accelerator.GPU)` 选择；
 * - 加速器按 **GPU → CPU** 显式级联，任一档失败即回落，绝不因此让功能不可用（§9 降级链）。
 *
 * 模型文件随包：`app/src/main/assets/models/selfie_multiclass_256x256.tflite`
 * （MediaPipe，Apache-2.0；`aaptOptions/noCompress` 保证可从 assets 直接 mmap）。
 *
 * 本类**不抛异常**：一切都收敛为 `null`。
 */
class LiteRtSkinMaskModel private constructor(
    private val env: Environment?,          // 持有引用：Environment 生命周期必须覆盖 model
    private val model: CompiledModel,
    override val side: Int,
    override val acceleratorName: String,
) : SkinMaskModel {

    private val inputs = model.createInputBuffers()
    private val outputs = model.createOutputBuffers()

    /** 复用的输入缓冲，避免每次推理重新分配 ~0.75MB。 */
    private val rgb = FloatArray(side * side * 3)

    override fun inferProbs(argb: IntArray): FloatArray? {
        if (argb.size < side * side) return null
        var k = 0
        for (i in 0 until side * side) {
            val p = argb[i]
            rgb[k++] = ((p shr 16) and 0xff) / 255f
            rgb[k++] = ((p shr 8) and 0xff) / 255f
            rgb[k++] = (p and 0xff) / 255f
        }
        return try {
            inputs[0].writeFloat(rgb)
            model.run(inputs, outputs)
            val out = outputs[0].readFloat()
            if (out.size >= side * side * SkinMaskPostProcess.CLASSES) out else null
        } catch (t: Throwable) {
            DebugLog.e(DebugLog.TAG_ML, "inference failed", kv("err", t))
            null
        }
    }

    override fun close() {
        runCatching { model.close() }
        runCatching { env?.close() }
    }

    companion object {

        /** 随包模型路径（相对 `assets/`）。 */
        const val MODEL_ASSET = "models/selfie_multiclass_256x256.tflite"

        /** 模型输入边长。 */
        const val SIDE = 256

        private fun kv(key: String, t: Throwable): Map<String, Any> =
            mapOf(key to (t.message ?: t.javaClass.simpleName))

        /**
         * 依次尝试 GPU → CPU 创建模型；全部失败返回 `null`（调用方回退）。
         *
         * 失败一律记 [DebugLog.TAG_ML] 日志，便于真机回传日志定位（模型缺失 / 加速器不可用 / ABI 不符…）。
         */
        fun createOrNull(context: Context): SkinMaskModel? {
            val appCtx = context.applicationContext
            val env = runCatching { Environment.create(appCtx) }.getOrElse { t ->
                DebugLog.w(DebugLog.TAG_ML, "Environment.create failed", kv("err", t))
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
                        "CompiledModel.create failed",
                        mapOf("accel" to accel.name, "err" to (t.message ?: t.javaClass.simpleName)),
                    )
                    null
                }
                if (built != null) {
                    // 构造（含 createInputBuffers/createOutputBuffers）也可能抛，一并收敛为 null
                    val instance = runCatching {
                        LiteRtSkinMaskModel(env, built, SIDE, accel.name)
                    }.getOrElse { t ->
                        lastErr = t
                        runCatching { built.close() }
                        DebugLog.w(
                            DebugLog.TAG_ML,
                            "model buffers init failed",
                            mapOf("accel" to accel.name, "err" to (t.message ?: t.javaClass.simpleName)),
                        )
                        null
                    }
                    if (instance != null) return instance
                }
            }
            DebugLog.e(DebugLog.TAG_ML, "skin mask model unavailable", kv("lastErr", lastErr ?: IllegalStateException("unknown")))
            return null
        }
    }
}
