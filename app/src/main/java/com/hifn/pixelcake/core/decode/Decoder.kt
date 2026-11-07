package com.hifn.pixelcake.core.decode

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.hifn.pixelcake.arw.ArwFullDecoder
import com.hifn.pixelcake.arw.ArwPreviewDecoder
import com.hifn.pixelcake.diag.DebugLog
import kotlinx.coroutines.CancellationException
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
 * 预览与导出都从**同一条路径**取底图（PHASE_DESIGN_HISTORY.md（审查台账） F03），保证所见即所得。
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
            } catch (c: CancellationException) {
                // 协程取消（用户退出编辑页等）必须原样上抛：吞掉它会把「取消」伪装成
                // 「无法解码该文件」报给用户（与 CameraBatch / 导出协程同一纪律）。
                throw c
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
            } catch (c: CancellationException) {
                throw c
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
        //
        // ⚠️ 这一步会抛（代理档 2048 时 `readAll` 也要 ~16MB，紧张环境仍可能 OOM）。
        // 抛异常时 `decodeToProxy` 的 `catch (t: Throwable)` 会把异常吞掉并返回 null，
        // 于是**这份 65MB 的缓存文件路径从未交给任何人** ⇒ 彻底泄漏。
        // F21 修的是「拷贝失败时留半截文件」，没修「拷贝成功但后续失败时留完整文件」。
        //
        // P1-15：抛异常时缓存已被 `releaseCache` 释放，此时**绝不能**再把
        // `rawCachePath` 交给调用方 —— 导出会拿着一个已删除的路径去 `RawLinearSource.open`，
        // 静默得到「RAW 导出失败」。用 [cacheReleased] 区分「缓存已释放」与「缓存健在但
        // 本次代理解不出线性母版（退回占位图管线，导出仍可全分辨率重解）」这两种 null。
        var cacheReleased = false
        val linear = try {
            ArwFullDecoder.decodeLinearProxy(cache.absolutePath, longEdge)
        } catch (t: Throwable) {
            ArwFullDecoder.releaseCache(cache.absolutePath)
            cacheReleased = true
            DebugLog.e(
                DebugLog.TAG_DECODE, "arw: linear proxy threw",
                mapOf("err" to (t.message ?: t.javaClass.simpleName))
            )
            null
        }
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
            rawCachePath = if (cacheReleased) null else cache.absolutePath
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
            // P0-2 M-1：方向必须**在解码前**单独读（上面的 bounds 流已被读尽）。
            // RAW 走 LibRaw 会应用机身 flip，JPEG/HEIF 不会 ⇒ 同机同景方向可能不一致；
            // 这里把旋转烘焙进像素，配合导出侧回写 Orientation=1（见 `Exporter`），两头都是正的。
            val orientation = readExifOrientation(cr, uri)
            cr.openInputStream(uri)?.use { s2 ->
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = false
                }
                val raw = BitmapFactory.decodeStream(s2, null, opts) ?: return null
                val bmp = applyExifOrientation(raw, orientation)
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

    /** 读 EXIF 方向；任何失败（非图片 / IO / 解析）都按「无需旋转」处理，绝不因此拒绝解码。 */
    private fun readExifOrientation(cr: ContentResolver, uri: Uri): Int = try {
        cr.openInputStream(uri)?.use { s ->
            ExifInterface(s).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (t: Throwable) {
        DebugLog.w(
            DebugLog.TAG_DECODE, "exif orientation read failed",
            mapOf("uri" to uri.toString(), "err" to (t.message ?: t.javaClass.simpleName))
        )
        ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * EXIF 方向值 → 显示修正的旋转角（度）。翻转类方向（2/4/5/7）返回 -1，由
     * [applyExifOrientation] 走镜像分支。**纯函数，JVM 单测覆盖**（`ExifOrientationTest`）。
     */
    internal fun orientationRotationDegrees(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> -1
    }

    /**
     * 把 EXIF 方向烘焙进像素（P0-2 M-1）。
     *
     * 矩阵口径（与「正 = 往亮推」一样，这里也只允许一种写法）：
     * `postScale/postRotate` 是**后乘** —— 后 post 的变换先作用到输入向量。逐个推导过：
     *  - TRANSPOSE(5) = 「先水平镜像、再顺转 90°」：`postScale(-1,1)` 先写、`postRotate(90)` 后写；
     *  - TRANSVERSE(7) = 「先顺转 90°、再水平镜像」：`postRotate(90)` 先写、`postScale(-1,1)` 后写。
     * 写反顺序会把 5/7 转成对方的镜像（竖拍带镜像的方向在真机上会左右翻）。
     */
    internal fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postScale(-1f, 1f)
                m.postRotate(90f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    internal fun computeSample(ow: Int, oh: Int, longEdge: Int): Int {
        if (ow <= 0 || oh <= 0 || longEdge <= 0) return 1
        val longest = max(ow, oh)
        val ratio = longest.toFloat() / longEdge.toFloat()
        var s = 1
        while (s * 2 <= ratio) s *= 2
        // 超预算再对折：AOSP 惯例会停在「对折后就小于目标」的那一档（如 33MP 源 + 2048
        // 目标 → 3504×2336 ≈ 31MB），比「长边 ≤ longEdge」的代理预算大 3 倍且整会话常驻。
        // 超过 1.5 倍才再降半档（7008 → 1752、4000 → 2000），小幅超预算保留以免预览过软。
        if (longest / s > longEdge * 3 / 2) s *= 2
        return s
    }
}
