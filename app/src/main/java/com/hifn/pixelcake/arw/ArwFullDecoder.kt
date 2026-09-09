package com.hifn.pixelcake.arw

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.hifn.pixelcake.core.decode.DecodedImage
import com.hifn.pixelcake.core.decode.RawImage
import com.hifn.pixelcake.core.decode.RawNative
import com.hifn.pixelcake.diag.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * P1b 接入点：RAW 全分辨率解码。
 *
 * 当前由 [useLibRaw] 总开关控制：
 *  - false：走 Kotlin 内嵌预览回退（与 M0b/P1a 行为一致，约 1616px）；
 *  - true ：走 LibRaw 全量解马赛克（P1b-2 实现，P1b-3 翻转开关启用）。
 *
 * LibRaw 需要文件系统路径，故先把 Uri 落到应用缓存临时文件，再交给 JNI，用完即删。
 */
object ArwFullDecoder {

    /** P1b 总开关：false=走 Kotlin 预览回退；true=走 LibRaw 全量解码。 */
    var useLibRaw: Boolean = false

    suspend fun decodeFull(context: Context, uri: Uri, longEdge: Int): DecodedImage? =
        if (useLibRaw) decodeViaLibRaw(context, uri, longEdge)
        else decodeViaPreview(context, uri, longEdge)

    private suspend fun decodeViaPreview(context: Context, uri: Uri, longEdge: Int): DecodedImage? =
        withContext(Dispatchers.IO) {
            val bmp = ArwPreviewDecoder.decodePreview(context, uri, longEdge) ?: return@withContext null
            DecodedImage(bmp, "image/arw", bmp.width, bmp.height, true)
        }

    /** 经 JNI 调 LibRaw 全量解马赛克 ARW → sRGB 8bit Bitmap。失败时回退预览，保证链路不中断。 */
    private suspend fun decodeViaLibRaw(context: Context, uri: Uri, longEdge: Int): DecodedImage? =
        withContext(Dispatchers.IO) {
            val tmp = copyToTemp(context, uri) ?: run {
                DebugLog.e(DebugLog.TAG_DECODE, "libraw: copy arw to temp failed", mapOf("uri" to uri.toString()))
                return@withContext decodeViaPreview(context, uri, longEdge)
            }
            try {
                val raw: RawImage = RawNative.decodeFull(tmp.absolutePath, longEdge)
                    ?: run {
                        DebugLog.e(DebugLog.TAG_DECODE, "libraw: decodeFull returned null", mapOf("path" to tmp.absolutePath))
                        return@withContext decodeViaPreview(context, uri, longEdge)
                    }
                val bmp = raw.toBitmap()
                    ?: run {
                        DebugLog.e(DebugLog.TAG_DECODE, "libraw: buildBitmap failed", mapOf("w" to raw.width, "h" to raw.height))
                        return@withContext decodeViaPreview(context, uri, longEdge)
                    }
                DecodedImage(bmp, "image/arw", bmp.width, bmp.height, isRaw = false)
            } catch (t: Throwable) {
                DebugLog.e(DebugLog.TAG_DECODE, "libraw decode error", mapOf("err" to (t.message ?: t.javaClass.simpleName)))
                decodeViaPreview(context, uri, longEdge)
            } finally {
                if (!tmp.delete()) {
                    DebugLog.w(DebugLog.TAG_DECODE, "libraw: temp file delete failed", mapOf("path" to tmp.absolutePath))
                }
            }
        }

    /** 把 Uri 内容流式拷贝到应用缓存临时文件，避免把整份 ARW 读进 JVM 堆。 */
    private fun copyToTemp(context: Context, uri: Uri): File? = runCatching {
        val ext = if (uri.toString().endsWith(".arw", ignoreCase = true)) "arw" else "raw"
        val file = File(context.cacheDir, "rawbridge_${System.nanoTime()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out -> input.copyTo(out) }
        }
        file
    }.getOrNull()

    /** RGBA_8888 字节流 → ARGB_8888 Bitmap（逐行 setPixels，避免字节序歧义、且不占大块额外内存）。 */
    private fun RawImage.toBitmap(): Bitmap? = runCatching {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        val px = pixels
        for (y in 0 until height) {
            val base = y * width * 4
            for (x in 0 until width) {
                val o = base + x * 4
                val r = px[o].toInt() and 0xFF
                val g = px[o + 1].toInt() and 0xFF
                val b = px[o + 2].toInt() and 0xFF
                val a = px[o + 3].toInt() and 0xFF
                row[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(row, 0, width, 0, y, width, 1)
        }
        bmp
    }.getOrNull()
}
