package com.hifn.pixelcake.arw

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.hifn.pixelcake.diag.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
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

        /**
         * 文件长度。
         *
         * ⚠️ 必须**三级回退**，因为 `ArwContainer.previewJpegRange` 第一行就是
         * `if (source.size < 8) return null`：
         *   ① `openAssetFileDescriptor` 是 **provider 可选实现**，很多 DocumentsProvider
         *      （云备份、部分 MediaStore 代理）不实现 ⇒ 返回 null ⇒ size = 0 ⇒ 整条预览链路静默失效，
         *      用户看到的是「打开 ARW 没有任何底图」，日志里只有一句 no preview jpeg range found；
         *   ② 即便实现了，也可能返回 `AssetFileDescriptor.UNKNOWN_LENGTH`（**-1**）。
         * 以前只有 ①②，于是「我拿不到文件长度」与「这个文件没有预览」在日志里长得一模一样。
         */
        override val size: Long by lazy {
            val fromColumn = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
                } ?: 0L
            }.getOrDefault(0L)
            if (fromColumn > 0L) return@lazy fromColumn
            val fromAfd = runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            }.getOrDefault(0L)
            // AFD 可能报 UNKNOWN_LENGTH(-1)，再退到普通 fd 的 statSize
            if (fromAfd > 0L) fromAfd else runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }.getOrDefault(0L)
        }

        override fun read(offset: Long, length: Int): ByteArray {
            resolver.openInputStream(uri)?.use { return readAt(it, offset, length) }
                ?: throw IllegalStateException("cannot open uri $uri")
        }

        private fun readAt(stream: InputStream, offset: Long, length: Int): ByteArray {
            val buffered = if (stream is BufferedInputStream) stream else BufferedInputStream(stream)
            // ⚠️ `InputStream.skip` 的契约是「**可能**跳过 0 字节」，经 ContentResolver 包装的流
            // （尤其带压缩/代理的 provider）返回 0 很常见。以前的 `if (s <= 0L) break` 会带着
            // `skipped < offset` 继续往下 read ⇒ 读到的是**文件开头**的字节 ⇒ SOI 校验必然失败
            // ⇒ 返回 null ⇒ 又是「静默无预览」。现在改成逐字节兜底，并且跳不到位就**报错**，
            // 让上层走错误日志而不是拿错误数据当 JPEG。
            var skipped = 0L
            while (skipped < offset) {
                val s = buffered.skip(offset - skipped)
                if (s > 0L) {
                    skipped += s
                } else {
                    // 真正的 fallback：读一个字节丢掉（有些流 skip 恒返回 0 但 read 正常）
                    if (buffered.read() < 0) break
                    skipped++
                }
            }
            if (skipped < offset) {
                throw IOException("cannot seek to $offset (only $skipped bytes skipped)")
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
