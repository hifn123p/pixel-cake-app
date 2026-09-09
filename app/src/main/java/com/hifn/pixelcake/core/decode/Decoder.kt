package com.hifn.pixelcake.core.decode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.hifn.pixelcake.arw.ArwFullDecoder
import com.hifn.pixelcake.arw.ArwPreviewDecoder
import com.hifn.pixelcake.diag.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 解码结果。
 *
 * @param isRaw true 表示来源为 ARW（[bitmap] 只是**秒开占位图**，真正的编辑母版见 [linear]）。
 * @param linear ARW 的 16-bit 线性编辑母版；JPEG/HEIF 为 null（走 8-bit sRGB 管线）。
 * @param rawCachePath ARW 缓存文件绝对路径，导出时用于全分辨率重解；退出编辑时由调用方删除。
 */
data class DecodedImage(
    val bitmap: Bitmap,
    val mime: String,
    val width: Int,
    val height: Int,
    val isRaw: Boolean,
    val linear: LinearImage? = null,
    val rawCachePath: String? = null
)

/**
 * 按扩展名/mime 路由解码：
 *  - ARW：内嵌 JPEG 只作**秒开占位**，同时解出 LibRaw 的 16-bit 线性母版作为修图源；
 *    LibRaw 失败时降级为纯占位图（[linear] 为 null，按 8-bit sRGB 管线继续）；
 *  - JPEG/HEIF：BitmapFactory 带 inSampleSize 降采样到 longEdge。
 *
 * 预览与导出都从**同一条路径**取底图（FIX_LIST F03），保证所见即所得。
 */
object Decoder {

    /** 代理分辨率解码（编辑预览用），降采样到 longEdge，并备好线性母版。 */
    suspend fun decodeToProxy(context: Context, uri: Uri, longEdge: Int): DecodedImage? =
        withContext(Dispatchers.IO) {
            try {
                val cr = context.contentResolver
                val mime = cr.getType(uri).orEmpty().lowercase()
                val isArw = mime.contains("arw") || uri.toString().endsWith(".arw", ignoreCase = true)
                if (isArw) decodeArw(context, uri, longEdge) else decodeBitmap(context, uri, longEdge, mime)
            } catch (t: Throwable) {
                DebugLog.e(
                    DebugLog.TAG_DECODE, "decode error",
                    mapOf("uri" to uri.toString(), "err" to (t.message ?: t.javaClass.simpleName))
                )
                null
            }
        }

    /**
     * JPEG/HEIF 的全分辨率解码（导出用）。
     * ARW 不走这里——全分辨率 RAW 由 [com.hifn.pixelcake.core.edit.EditEngine.renderLinearFile]
     * 直接从 [DecodedImage.rawCachePath] 分带渲染，避免把整幅线性图搬进堆。
     */
    suspend fun decodeFullRes(context: Context, uri: Uri, longEdge: Int): DecodedImage? =
        withContext(Dispatchers.IO) {
            try {
                val cr = context.contentResolver
                val mime = cr.getType(uri).orEmpty().lowercase()
                val isArw = mime.contains("arw") || uri.toString().endsWith(".arw", ignoreCase = true)
                if (isArw) null else decodeBitmap(context, uri, longEdge, mime)
            } catch (t: Throwable) {
                DebugLog.e(
                    DebugLog.TAG_DECODE, "decodeFullRes error",
                    mapOf("uri" to uri.toString(), "err" to (t.message ?: t.javaClass.simpleName))
                )
                null
            }
        }

    private suspend fun decodeArw(context: Context, uri: Uri, longEdge: Int): DecodedImage? {
        // 1) 秒开占位：内嵌 JPEG 预览（F04 之后能取到 7008×4672 那张）
        val placeholder = ArwPreviewDecoder.decodePreview(context, uri, longEdge)
            ?: return null
        // 2) 落到缓存，供 LibRaw 使用（导出还要再解一次全分辨率，故先不删）
        val cache = ArwFullDecoder.copyToCache(context, uri)
        if (cache == null) {
            DebugLog.w(DebugLog.TAG_DECODE, "arw: no cache, fallback to preview-only")
            return DecodedImage(placeholder, "image/arw", placeholder.width, placeholder.height, isRaw = true)
        }
        // 3) 解出代理分辨率的线性母版
        val linear = ArwFullDecoder.decodeLinearProxy(cache.absolutePath, longEdge)
        if (linear == null) {
            DebugLog.w(DebugLog.TAG_DECODE, "arw: linear proxy failed, fallback to preview-only")
        }
        return DecodedImage(
            bitmap = placeholder,
            mime = "image/arw",
            // 有线性母版时以它为准（这才是修图源）；否则用占位图尺寸
            width = linear?.width ?: placeholder.width,
            height = linear?.height ?: placeholder.height,
            isRaw = true,
            linear = linear,
            rawCachePath = cache.absolutePath
        )
    }

    private fun decodeBitmap(context: Context, uri: Uri, longEdge: Int, mime: String): DecodedImage? {
        val cr = context.contentResolver
        val stream = cr.openInputStream(uri) ?: return null
        stream.use {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(it, null, bounds)
            val ow = bounds.outWidth
            val oh = bounds.outHeight
            if (ow <= 0 || oh <= 0) return null
            val sample = computeSample(ow, oh, longEdge)
            cr.openInputStream(uri)?.use { s2 ->
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = false
                }
                val bmp = BitmapFactory.decodeStream(s2, null, opts) ?: return null
                return DecodedImage(
                    bmp,
                    mime.ifEmpty { "image/jpeg" },
                    bmp.width,
                    bmp.height,
                    false
                )
            }
        }
        return null
    }

    private fun computeSample(ow: Int, oh: Int, longEdge: Int): Int {
        val ratio = max(ow, oh).toFloat() / longEdge.toFloat()
        var s = 1
        while (s * 2 <= ratio) s *= 2
        return s
    }
}
