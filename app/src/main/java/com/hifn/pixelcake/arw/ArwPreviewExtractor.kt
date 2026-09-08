package com.hifn.pixelcake.arw

import com.hifn.pixelcake.diag.DebugLog

/**
 * 从 ARW 容器切片出内嵌预览 JPEG（纯 Kotlin，零 NDK）。
 * 只读取 [ArwContainer.previewJpegRange] 指向的那一段，不加载整张 RAW。
 */
object ArwPreviewExtractor {

    /**
     * @return 内嵌预览 JPEG 的字节；找不到或读取失败返回 null。
     */
    fun extract(source: ArwByteSource): ByteArray? {
        val range = ArwContainer.previewJpegRange(source) ?: run {
            DebugLog.w(DebugLog.TAG_DECODE, "arw: no preview jpeg range found")
            return null
        }
        val len = (range.last - range.first + 1).toInt()
        return try {
            val bytes = source.read(range.first, len)
            val isJpeg = bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
            if (isJpeg) {
                DebugLog.i(
                    DebugLog.TAG_DECODE, "arw preview extracted",
                    mapOf("offset" to range.first, "length" to len)
                )
                bytes
            } else {
                DebugLog.w(DebugLog.TAG_DECODE, "arw preview slice is not a JPEG (bad SOI)")
                null
            }
        } catch (e: Exception) {
            DebugLog.e(
                DebugLog.TAG_DECODE, "arw preview read failed",
                mapOf("err" to (e.message ?: e.javaClass.simpleName))
            )
            null
        }
    }
}
