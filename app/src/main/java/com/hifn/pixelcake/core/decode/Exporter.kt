package com.hifn.pixelcake.core.decode

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import com.hifn.pixelcake.diag.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat { JPEG, PNG }

/**
 * 把位图写入系统相册 Pictures/PixelCake（作用域存储，无需任何读取权限）。
 *
 * minSdk 36 > Q，因此 `RELATIVE_PATH` / `IS_PENDING` 一定可用——
 * 此前那些 `if (Build.VERSION.SDK_INT >= Q)` 判断是死代码（PHASE_DESIGN_HISTORY.md（审查台账） F20），已移除。
 */
object Exporter {
    suspend fun export(
        context: Context,
        bitmap: Bitmap,
        format: ExportFormat = ExportFormat.JPEG,
        quality: Int = 92
    ): Uri? = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val (mime, ext) = when (format) {
            ExportFormat.JPEG -> "image/jpeg" to "jpg"
            ExportFormat.PNG -> "image/png" to "png"
        }
        val name = "PixelCake_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$ext"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PixelCake")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        try {
            // ⚠️ 两条静默失败路径必须在这里堵住（否则相册里出现 0 字节图、界面却提示成功）：
            //   ① `openOutputStream` 返回 null（provider 异常 / 条目已被回收）⇒ 一个字节都没写；
            //   ② `compress` 返回 false（位图已 recycle、编码器内部失败）。
            // 两者以前都被 `?.use` / 返回值丢弃吞掉，然后照样把 IS_PENDING 清 0 并返回 uri。
            val fmt = if (format == ExportFormat.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            val written = cr.openOutputStream(uri)?.use { os ->
                bitmap.compress(fmt, quality.coerceIn(0, 100), os)
            } ?: false
            if (!written) throw IllegalStateException("bitmap.compress failed for $name")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            uri
        } catch (t: Throwable) {
            // ⚠️ 必须 Throwable 而不是 Exception：33MP 的 compress 是 OOM 高发点，
            // OutOfMemoryError 逃出去会让 `IS_PENDING=1` 的记录永久隐藏 ——
            // 用户在相册里看不到也删不掉，系统清理前一直占着存储。
            DebugLog.e(
                DebugLog.TAG_DECODE, "export failed",
                mapOf("fmt" to format.name, "err" to (t.message ?: t.javaClass.simpleName))
            )
            try { cr.delete(uri, null, null) } catch (_: Throwable) {}
            null
        }
    }
}
