package com.hifn.pixelcake.core.decode

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.hifn.pixelcake.diag.DebugLog
import kotlinx.coroutines.CancellationException
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

    /**
     * P0-2 M-1：从来源照片**复制**进导出文件的最小 EXIF 集（存在才写、缺了不补）。
     * 拍摄参数（光圈/快门/ISO/焦距）与机身型号是修图 App 里用户真正还关心的元数据；
     * GPS 与复杂标签刻意**不**复制 —— 位置是隐私，宁缺勿错。
     */
    private val EXIF_COPY_TAGS = arrayOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH
    )

    /**
     * @param isCancelled 取消检查点（P1-3 A3）：插入记录前、压缩完成后各查一次。
     *   「压缩途中取消」查不到 —— 但文件落地后由完成后的那次检查兜住并**删除**，
     *   不留幽灵文件。判据：取消后相册里**没有**该文件。
     * @param exifFrom 来源照片 uri（P0-2 M-1）：非 null 时回写最小 EXIF 集；
     *   方向恒写 1（Orientation 已在 `Decoder.applyExifOrientation` 烘焙进像素）。
     *   EXIF 写失败**绝不**让导出失败 —— 元数据是加分项，成片本体已经落盘。
     */
    suspend fun export(
        context: Context,
        bitmap: Bitmap,
        format: ExportFormat = ExportFormat.JPEG,
        quality: Int = 92,
        isCancelled: () -> Boolean = { false },
        exifFrom: Uri? = null
    ): Uri? = withContext(Dispatchers.IO) {
        // 插入记录**之前**就查一次：取消发生在排队等待期时，连 MediaStore 记录都不该出现。
        if (isCancelled()) return@withContext null
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
            val written = if (isCancelled()) {
                false
            } else {
                cr.openOutputStream(uri)?.use { os ->
                    bitmap.compress(fmt, quality.coerceIn(0, 100), os)
                } ?: false
            }
            if (!written) throw IllegalStateException("bitmap.compress failed for $name")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            // update 返回 0（行未更新）时若照样返回 uri，这条 IS_PENDING=1 的记录就永久
            // 隐藏 —— 恰好复刻本函数注释里要堵的那个症状。抛出去走下方 catch 的
            // delete 分支：宁可报「导出失败」，也不留一条看不见也删不掉的记录。
            if (cr.update(uri, values, null, null) == 0) {
                throw IllegalStateException("IS_PENDING clear failed for $name")
            }
            // 压缩**完成之后**才发现取消：文件已经存在于相册，必须删掉 ——
            // 否则「取消导出」提示弹了、相册里却躺着一张用户没要的幽灵文件（A3 判据）。
            if (isCancelled()) {
                try { cr.delete(uri, null, null) } catch (_: Throwable) { /* 清理路径刻意吞 CE（闸门豁免） */ }
                return@withContext null
            }
            writeMinimalExif(cr, uri, exifFrom)
            uri
        } catch (c: CancellationException) {
            // 协程取消不是失败：清掉 IS_PENDING 记录后**原样上抛**（与 Decoder/CameraBatch
            // 同一纪律 —— 吞掉它会把「取消」伪装成「导出失败」）。
            try { cr.delete(uri, null, null) } catch (_: Throwable) { /* 清理路径刻意吞 CE（闸门豁免） */ }
            throw c
        } catch (t: Throwable) {
            // ⚠️ 必须 Throwable 而不是 Exception：33MP 的 compress 是 OOM 高发点，
            // OutOfMemoryError 逃出去会让 `IS_PENDING=1` 的记录永久隐藏 ——
            // 用户在相册里看不到也删不掉，系统清理前一直占着存储。
            DebugLog.e(
                DebugLog.TAG_DECODE, "export failed",
                mapOf("fmt" to format.name, "err" to (t.message ?: t.javaClass.simpleName))
            )
            try { cr.delete(uri, null, null) } catch (_: Throwable) { /* 清理路径刻意吞 CE（闸门豁免） */ }
            null
        }
    }

    /**
     * 导出侧回写最小 EXIF 集（P0-2 M-1）。
     *
     * 走 `openFileDescriptor("rw")` + `ExifInterface(fd)` **就地改写**：`saveAttributes()`
     * 会重写整个文件，比起「读回内存再写」少一次全幅拷贝。任何失败只打 WARN ——
     * 调用方语义是「EXIF 是加分项」：成片已落盘，元数据写不进去不该让导出报错。
     */
    private fun writeMinimalExif(cr: ContentResolver, out: Uri, from: Uri?) {
        try {
            val pfd = cr.openFileDescriptor(out, "rw") ?: return
            pfd.use { fd ->
                val exif = ExifInterface(fd.fileDescriptor)
                // 旋转已在解码侧烘焙进像素 ⇒ 导出文件的方向恒为「正」。
                // 不写这一条的话，某些相册 App 会按来源方向把已经正立的图再转一次。
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                if (from != null) {
                    try {
                        cr.openInputStream(from)?.use { s ->
                            val src = ExifInterface(s)
                            for (tag in EXIF_COPY_TAGS) {
                                val v = src.getAttribute(tag)
                                if (!v.isNullOrEmpty() && exif.getAttribute(tag).isNullOrEmpty()) {
                                    exif.setAttribute(tag, v)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        DebugLog.w(
                            DebugLog.TAG_DECODE, "exif source read failed",
                            mapOf("err" to (t.message ?: t.javaClass.simpleName))
                        )
                    }
                }
                exif.saveAttributes()
            }
        } catch (t: Throwable) {
            DebugLog.w(
                DebugLog.TAG_DECODE, "exif write failed",
                mapOf("err" to (t.message ?: t.javaClass.simpleName))
            )
        }
    }
}
