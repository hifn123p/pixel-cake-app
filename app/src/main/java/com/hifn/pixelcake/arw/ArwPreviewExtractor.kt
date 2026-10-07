package com.hifn.pixelcake.arw

import com.hifn.pixelcake.diag.DebugLog

/**
 * 从 ARW 容器切片出内嵌预览 JPEG（纯 Kotlin，零 NDK）。
 * 只读取 [ArwContainer.previewJpegRange] 指向的那一段，不加载整张 RAW。
 */
object ArwPreviewExtractor {

    /**
     * 单个内嵌预览候选的硬上限。A7C II 的最大那张约 1.9MB，32MB 已是极端余量。
     *
     * 为什么必须有：`ArwContainer` 只校验 `offset + length <= source.size`，**不校验长度上限**；
     * 而这里会按长度 `ByteArray(len)` 再 `copyOf(read)` ⇒ 一个把0x0202 写成几十 MB 的畸形文件
     * 就能让分配翻倍直接 OOM。另外 `len` 用 Int：超过 2GB 时 `.toInt()` 变负数 ⇒
     * `ByteArray(负)` 抛 NegativeArraySizeException。
     */
    private const val MAX_PREVIEW_BYTES = 32L * 1024 * 1024

    /**
     * @return 内嵌预览 JPEG 的字节；找不到或读取失败返回 null。
     */
    fun extract(source: ArwByteSource): ByteArray? {
        val range = ArwContainer.previewJpegRange(source) ?: run {
            DebugLog.w(DebugLog.TAG_DECODE, "arw: no preview jpeg range found")
            return null
        }
        val lenLong = range.last - range.first + 1
        if (lenLong <= 2 || lenLong > MAX_PREVIEW_BYTES) {
            DebugLog.w(
                DebugLog.TAG_DECODE, "arw: preview candidate rejected",
                mapOf("offset" to range.first, "length" to lenLong, "cap" to MAX_PREVIEW_BYTES)
            )
            return null
        }
        val len = lenLong.toInt()
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
