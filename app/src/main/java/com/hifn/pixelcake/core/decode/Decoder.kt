package com.hifn.pixelcake.core.decode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.hifn.pixelcake.arw.ArwPreviewDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** 解码结果。isRaw=true 表示来自 ARW 内嵌预览（已烘焙，仅适合预览/导出，不适合作为修图源）。 */
data class DecodedImage(
    val bitmap: Bitmap,
    val mime: String,
    val width: Int,
    val height: Int,
    val isRaw: Boolean
)

/**
 * 按扩展名/mime 路由解码：
 *  - ARW：纯 Kotlin 取内嵌 JPEG 预览（零 NDK），降采样到 longEdge；
 *  - JPEG/HEIF：BitmapFactory 带 inSampleSize 降采样到 longEdge。
 * 都不全量载入，避免 65MB ARW / 33MP JPEG 占内存。
 */
object Decoder {
    /** 代理分辨率解码（编辑预览用），降采样到 longEdge。 */
    suspend fun decodeToProxy(context: Context, uri: Uri, longEdge: Int): DecodedImage? =
        decodeInternal(context, uri, longEdge)

    /**
     * 导出用全分辨率解码，与 [decodeToProxy] 同一路由与采样逻辑，
     * 仅 longEdge 传入 fullResLongEdge（JPEG/HEIF 基本按原图长边采样）。
     * 注意：ARW 当前仍取内嵌预览（全量解码待 P1b），故 RAW 导出实为预览分辨率。
     */
    suspend fun decodeFullRes(context: Context, uri: Uri, longEdge: Int): DecodedImage? =
        decodeInternal(context, uri, longEdge)

    private suspend fun decodeInternal(context: Context, uri: Uri, longEdge: Int): DecodedImage? =
        withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val mime = cr.getType(uri).orEmpty().lowercase()
            val isArw = mime.contains("arw") || uri.toString().endsWith(".arw", ignoreCase = true)
            if (isArw) {
                val bmp = ArwPreviewDecoder.decodePreview(context, uri, longEdge) ?: return@withContext null
                return@withContext DecodedImage(bmp, "image/arw", bmp.width, bmp.height, true)
            }
            val stream = cr.openInputStream(uri) ?: return@withContext null
            stream.use {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(it, null, bounds)
                val ow = bounds.outWidth
                val oh = bounds.outHeight
                if (ow <= 0 || oh <= 0) return@withContext null
                val sample = computeSample(ow, oh, longEdge)
                cr.openInputStream(uri)?.use { s2 ->
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inMutable = false
                    }
                    val bmp = BitmapFactory.decodeStream(s2, null, opts) ?: return@withContext null
                    return@withContext DecodedImage(
                        bmp,
                        mime.ifEmpty { "image/jpeg" },
                        bmp.width,
                        bmp.height,
                        false
                    )
                }
            }
            return@withContext null
        }

    private fun computeSample(ow: Int, oh: Int, longEdge: Int): Int {
        val ratio = max(ow, oh).toFloat() / longEdge.toFloat()
        var s = 1
        while (s * 2 <= ratio) s *= 2
        return s
    }
}
