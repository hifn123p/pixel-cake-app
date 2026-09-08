package com.hifn.pixelcake.core.decode

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat { JPEG, PNG }

/** 把位图写入系统相册 Pictures/PixelCake（API 29+ 作用域存储，无需写权限）。 */
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PixelCake")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        try {
            cr.openOutputStream(uri)?.use { os ->
                bitmap.compress(
                    if (format == ExportFormat.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,
                    quality.coerceIn(0, 100),
                    os
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                cr.update(uri, values, null, null)
            }
            uri
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) {}
            null
        }
    }
}
