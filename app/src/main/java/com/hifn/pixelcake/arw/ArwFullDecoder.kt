package com.hifn.pixelcake.arw

import android.content.Context
import android.net.Uri
import com.hifn.pixelcake.core.decode.LinearImage
import com.hifn.pixelcake.core.decode.RawLinearSource
import com.hifn.pixelcake.diag.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ARW 的 LibRaw 接入点（P1b）。
 *
 * 这里**不再产出「相机烘焙过的 8-bit 图」**，而是产出 **16-bit 线性**编辑母版
 * （[LinearImage] / [RawLinearSource]）。白平衡只作为初始基线，曝光/曲线/滤镜
 * 全部留给参数栈在线性域重放——否则「RAW 修图」与「预览 JPEG 修图」没有区别。
 *
 * LibRaw 需要文件系统路径，故先把 Uri 落到应用缓存临时文件。
 * 该临时文件在**导出（全分辨率重解）之前不能删**，由调用方在退出编辑时清理。
 */
object ArwFullDecoder {

    /**
     * 把 Uri 内容流式拷贝到应用缓存临时文件，避免把整份 ARW 读进 JVM 堆。
     * 失败返回 null；异常路径下不留半截临时文件（FIX_LIST F21）。
     */
    fun copyToCache(context: Context, uri: Uri): File? {
        val ext = if (uri.toString().endsWith(".arw", ignoreCase = true)) "arw" else "raw"
        val file = File(context.cacheDir, "rawbridge_${System.nanoTime()}.$ext")
        return try {
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                // F21：此前 `?.use` 的返回值被丢弃，会返回一个 0 字节文件，
                // 让调用方绕一大圈才发现打不开。这里直接判空返回 null。
                file.delete()
                DebugLog.e(
                    DebugLog.TAG_DECODE, "arw copy: openInputStream null",
                    mapOf("uri" to uri.toString())
                )
                return null
            }
            input.use { src -> file.outputStream().use { dst -> src.copyTo(dst) } }
            if (file.length() <= 0L) {
                file.delete()
                DebugLog.e(
                    DebugLog.TAG_DECODE, "arw copy: empty file",
                    mapOf("uri" to uri.toString())
                )
                return null
            }
            DebugLog.i(DebugLog.TAG_DECODE, "arw copy ok", mapOf("bytes" to file.length()))
            file
        } catch (t: Throwable) {
            file.delete()
            DebugLog.e(
                DebugLog.TAG_DECODE, "arw copy failed",
                mapOf("err" to (t.message ?: t.javaClass.simpleName))
            )
            null
        }
    }

    /**
     * 解出**代理分辨率**的 16-bit 线性母版（整张取回并缓存，供滑块实时重渲）。
     * 全分辨率不允许走这里——33MP × 3 × 2B ≈ 196MB，必然 OOM；
     * 请改用 [com.hifn.pixelcake.core.edit.EditEngine.renderLinearFile] 分带渲染。
     */
    suspend fun decodeLinearProxy(path: String, longEdge: Int): LinearImage? =
        withContext(Dispatchers.IO) {
            val src = RawLinearSource.open(path, longEdge)
            if (src == null) {
                DebugLog.e(DebugLog.TAG_DECODE, "arw linear proxy: open failed", mapOf("path" to path))
                return@withContext null
            }
            try {
                val img = src.readAll()
                if (img != null) {
                    DebugLog.i(
                        DebugLog.TAG_DECODE, "arw linear proxy ready",
                        mapOf(
                            "w" to img.width, "h" to img.height,
                            "mb" to img.sizeBytes / 1024 / 1024,
                            "wb" to src.cameraWhiteBalance.take(3).joinToString("/")
                        )
                    )
                }
                img
            } finally {
                src.close()
            }
        }

    /** 删除 [copyToCache] 产生的临时文件（退出编辑 / 换图时调用）。 */
    fun releaseCache(path: String?) {
        if (path.isNullOrEmpty()) return
        val f = File(path)
        if (f.exists() && !f.delete()) {
            DebugLog.w(DebugLog.TAG_DECODE, "arw cache delete failed", mapOf("path" to path))
        }
    }
}
