package com.hifn.pixelcake.arw

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.hifn.pixelcake.diag.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * 把 ARW 内嵌预览 JPEG 解码成 [Bitmap]，供 Compose 的 Image 显示（M0b 仅预览）。
 * 解码用平台 BitmapFactory，无 NDK。
 */
object ArwPreviewDecoder {

    /** 基于 ContentResolver 的随机读取源：只跳到目标偏移再读预览段，不全量加载。 */
    private class ContentArwSource(
        private val resolver: ContentResolver,
        private val uri: Uri
    ) : ArwByteSource {
        override val size: Long
            get() = runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            }.getOrDefault(0L)

        override fun read(offset: Long, length: Int): ByteArray {
            resolver.openInputStream(uri)?.use { return readAt(it, offset, length) }
                ?: throw IllegalStateException("cannot open uri $uri")
        }

        private fun readAt(stream: InputStream, offset: Long, length: Int): ByteArray {
            val buffered = if (stream is BufferedInputStream) stream else BufferedInputStream(stream)
            var skipped = 0L
            while (skipped < offset) {
                val s = buffered.skip(offset - skipped)
                if (s <= 0L) break
                skipped += s
            }
            val out = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = buffered.read(out, read, length - read)
                if (n < 0) break
                read += n
            }
            return out.copyOf(read)
        }
    }

    /**
     * 解码 ARW 内嵌预览为 Bitmap，**只作为秒开占位图**（修图源是 LibRaw 的线性母版）。
     * @param maxEdge 目标最长边(px)，按设备 proxy 档位传入。
     *
     * 注：A7C II 的 ARW 里挂了三张内嵌 JPEG（1616×1080 / 160×120 / **7008×4672**），
     * F04 之后 [ArwContainer] 会沿 IFD 链收齐并按体积取最大的那张，
     * 所以这里拿到的已经是全分辨率预览，不再是 1616px。
     */
    suspend fun decodePreview(
        context: Context,
        uri: Uri,
        maxEdge: Int = 2048
    ): Bitmap? = withContext(Dispatchers.IO) {
        val source = ContentArwSource(context.contentResolver, uri)
        val jpeg = ArwPreviewExtractor.extract(source) ?: return@withContext null
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            opts.inSampleSize = computeSampleSize(opts.outWidth, opts.outHeight, maxEdge)
            opts.inJustDecodeBounds = false
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            if (bmp != null) {
                DebugLog.i(
                    DebugLog.TAG_DECODE, "arw preview decoded",
                    mapOf("w" to bmp.width, "h" to bmp.height)
                )
            } else {
                DebugLog.w(DebugLog.TAG_DECODE, "arw preview decode returned null")
            }
            bmp
        }.getOrElse { e ->
            DebugLog.e(
                DebugLog.TAG_DECODE, "arw preview decode failed",
                mapOf("err" to (e.message ?: e.javaClass.simpleName))
            )
            null
        }
    }

    private fun computeSampleSize(w: Int, h: Int, maxEdge: Int): Int {
        if (w <= 0 || h <= 0) return 1
        val longEdge = maxOf(w, h)
        var s = 1
        while (longEdge / (s * 2) >= maxEdge) s *= 2
        return s
    }
}
