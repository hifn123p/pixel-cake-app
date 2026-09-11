package com.hifn.pixelcake.camera

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.hifn.pixelcake.core.decode.Decoder
import com.hifn.pixelcake.core.decode.ExportFormat
import com.hifn.pixelcake.core.decode.Exporter
import com.hifn.pixelcake.core.edit.EditEngine
import com.hifn.pixelcake.core.edit.RetouchScale
import com.hifn.pixelcake.core.edit.preset.Preset
import com.hifn.pixelcake.core.edit.retouch.RetouchLayer
import com.hifn.pixelcake.diag.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * P2 PoC-5：相机批量流水线 —— 「拉图 → 套预设 → 导出到相册」。
 *
 * 每张照片的临时文件在**导出后立即删除**，所以无论拉多少张，峰值磁盘占用只有一张的量级。
 * 另有 [pruneOldFiles] 兜底清理超过 1 小时的残留（单张拉取进编辑器的那一份会留到编辑结束）。
 *
 * 复用 P1 管线，零算法改动：
 * - RAW：`EditEngine.renderLinearFile`（边解码边分带渲染，不把整幅线性图搬进堆）
 * - JPEG/HEIF：`Decoder.decodeFullRes` → `renderIntoSrgb` + `RetouchLayer` 整图 pass
 *
 * **取消语义**：只在**文件边界**判断取消。中途停止 `GetObject` 的数据阶段会让数据流与响应错位、
 * 会话必须废弃，因此不做「半张拉取就中断」。
 */
object CameraBatch {

    private const val CACHE_DIR = "camera"
    private const val STALE_FILE_MS = 60L * 60L * 1000L
    private const val JPEG_QUALITY = 92

    /** 进度快照（UI 直接渲染）。 */
    data class Progress(
        val index: Int,
        val total: Int,
        val filename: String,
        val stage: String,
        val bytesDone: Long = 0L,
        val bytesTotal: Long = 0L
    ) {
        val percent: Int
            get() = if (bytesTotal <= 0L) 0 else ((bytesDone * 100L) / bytesTotal).toInt()

        fun text(): String = when {
            stage == "拉取中" && bytesTotal > 0L ->
                "($index/$total) $filename · 拉取中 $percent%（${bytesDone / 1024 / 1024}MB / ${bytesTotal / 1024 / 1024}MB）"
            else -> "($index/$total) $filename · $stage"
        }
    }

    /** 单张结果。 */
    data class ItemResult(
        val filename: String,
        val ok: Boolean,
        val outUri: String?,
        val message: String,
        val elapsedMs: Long
    )

    /** 一批的结果。 */
    data class Summary(
        val items: List<ItemResult>,
        val cancelled: Boolean,
        val elapsedMs: Long
    ) {
        val okCount: Int get() = items.count { it.ok }

        fun summaryLines(): List<String> {
            val out = ArrayList<String>()
            out.add(
                "批量完成：成功 $okCount / 共 ${items.size} 张，耗时 ${elapsedMs} ms" +
                    if (cancelled) "（已取消，剩余未处理）" else ""
            )
            for (item in items) {
                out.add(
                    if (item.ok) "  · ${item.filename} → 已导出（${item.elapsedMs} ms）"
                    else "  · ${item.filename} → 失败：${item.message}"
                )
            }
            return out
        }
    }

    /**
     * 跑一批。
     *
     * @param longEdge 输出长边上限（与编辑器导出同口径，例如 `profile.fullResLongEdge`）
     * @param isCancelled 在每张**开始前**判断一次
     */
    suspend fun run(
        context: Context,
        session: CameraSession,
        photos: List<CameraPhoto>,
        preset: Preset,
        longEdge: Int,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Summary = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext Summary(
                items = listOf(ItemResult("（缓存目录）", false, null, "无法创建缓存目录", 0L)),
                cancelled = false,
                elapsedMs = 0L
            )
        }
        pruneOldFiles(dir)

        val startedAll = System.currentTimeMillis()
        val items = ArrayList<ItemResult>()
        var cancelled = false

        // 整批兜底：任何未在单张层面处理的异常都不许穿出 run()——否则调用方 `scope.launch` 会
        // 在异常处中断、`busy` 永不复位，界面永久停在「批量处理中…」。取消信号（CancellationException）
        // 必须原样抛出，不能被兜底吞掉，否则破坏结构化并发。
        try {
            photos.forEachIndexed { index, photo ->
                if (isCancelled()) {
                    cancelled = true
                    return@forEachIndexed
                }
                val name = targetName(photo)
                val target = File(dir, name)
                val itemStarted = System.currentTimeMillis()

                onProgress(Progress(index + 1, photos.size, name, "拉取中"))
                val outcome = session.download(photo.handle, target) { done, total ->
                    onProgress(Progress(index + 1, photos.size, name, "拉取中", done, total))
                }

                if (!outcome.ok) {
                    runCatching { target.delete() }
                    items.add(
                        ItemResult(
                            name, false, null,
                            "拉取失败：${outcome.message}",
                            System.currentTimeMillis() - itemStarted
                        )
                    )
                    return@forEachIndexed
                }

                onProgress(Progress(index + 1, photos.size, name, "套预设 + 导出"))
                // 单张兜底：渲染/解码可能抛 OOM（`Error` 也算），一律转成失败项记入 items，
                // 绝不让一张毁掉整批（P2_DESIGN §D6「任何一步失败都返回带原因的报告」）。
                val result = try {
                    processOne(context, target, photo, preset, longEdge)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    val reason = t.javaClass.simpleName +
                        (t.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                    DebugLog.e(
                        DebugLog.TAG_CAMERA, "batch item failed",
                        mapOf("file" to name, "err" to reason)
                    )
                    ItemResult(name, false, null, "处理失败：$reason", 0L)
                } finally {
                    // 导完即删：批量场景峰值磁盘占用 = 一张
                    runCatching { target.delete() }
                }
                items.add(result.copy(elapsedMs = System.currentTimeMillis() - itemStarted))
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            DebugLog.e(
                DebugLog.TAG_CAMERA, "batch aborted",
                mapOf("err" to (t.message ?: t.javaClass.simpleName))
            )
            items.add(
                ItemResult("（批量中断）", false, null, "批量中断：${t.javaClass.simpleName}", 0L)
            )
        }

        Summary(items = items, cancelled = cancelled, elapsedMs = System.currentTimeMillis() - startedAll)
    }

    /** 单张：解码/渲染 → 导出到相册。 */
    private suspend fun processOne(
        context: Context,
        file: File,
        photo: CameraPhoto,
        preset: Preset,
        longEdge: Int
    ): ItemResult {
        val name = file.name
        return if (photo.isRaw) {
            // 尺寸用相机自报的原始像素算（相机没报则退化为 longEdge×longEdge，只会让半径偏小）
            val (w, h) = RetouchScale.fitLongEdge(
                photo.info.imageWidth,
                photo.info.imageHeight,
                longEdge
            )
            val bitmap = EditEngine.renderLinearFile(
                path = file.absolutePath,
                maxLongSide = longEdge,
                p = preset.params,
                retouch = RetouchScale.toRenderState(preset.retouch, w, h),
                mask = null
            ) { true }
            if (bitmap == null) {
                ItemResult(name, false, null, "RAW 渲染失败", 0L)
            } else {
                try {
                    export(context, bitmap, name)
                } finally {
                    bitmap.recycle()
                }
            }
        } else {
            val decoded = Decoder.decodeFullRes(context, Uri.fromFile(file), longEdge)
            if (decoded == null) {
                ItemResult(name, false, null, "解码失败", 0L)
            } else {
                var target: Bitmap? = null
                try {
                    val w = decoded.bitmap.width
                    val h = decoded.bitmap.height
                    target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    EditEngine.renderIntoSrgb(target, decoded.bitmap, preset.params)
                    // sRGB 路径不含 retouch，需在此补一趟整图 pass（与编辑器导出口径一致）
                    RetouchLayer.apply(target, RetouchScale.toRenderState(preset.retouch, w, h), null)
                    export(context, target, name)
                } finally {
                    target?.recycle()
                    decoded.bitmap.recycle()
                }
            }
        }
    }

    private suspend fun export(context: Context, bitmap: Bitmap, name: String): ItemResult {
        val uri: Uri? = Exporter.export(context, bitmap, ExportFormat.JPEG, JPEG_QUALITY)
        return if (uri != null) {
            DebugLog.i(
                DebugLog.TAG_CAMERA, "batch export ok",
                mapOf("file" to name, "uri" to uri.toString())
            )
            ItemResult(name, true, uri.toString(), "已导出", 0L)
        } else {
            DebugLog.w(DebugLog.TAG_CAMERA, "batch export failed", mapOf("file" to name))
            ItemResult(name, false, null, "导出失败", 0L)
        }
    }

    /**
     * 单张拉取的落盘路径（与批量同目录，统一清理）。
     * 顺手清掉超过 1 小时的残留，避免反复拉单张把缓存撑大。
     */
    fun newSingleTarget(context: Context, photo: CameraPhoto): File {
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        pruneOldFiles(dir)
        return File(dir, targetName(photo))
    }

    /**
     * 目标文件名 = [safeName] + **按类型补后缀**。
     *
     * 为什么必须补后缀：下游 `Decoder` 判定 RAW **只看后缀**（`.arw`），
     * 相机偶尔不给文件名（句柄命中却无 name），此时 `safeName` 只能给出 `cam_<handle>`，
     * 没有后缀会被当成 JPEG 走 `BitmapFactory` → 直接解码失败。按相机自报类型补一个即可。
     */
    fun targetName(photo: CameraPhoto): String {
        val base = safeName(photo.filename, photo.handle)
        if (base.contains('.')) return base
        return base + if (photo.isRaw) ".ARW" else ".JPG"
    }

    /** 清理超过 1 小时的残留（单张拉取进编辑器的文件会留到编辑结束，别误删近期文件）。 */
    private fun pruneOldFiles(dir: File) {
        val deadline = System.currentTimeMillis() - STALE_FILE_MS
        val files = dir.listFiles() ?: return
        var removed = 0
        for (file in files) {
            if (file.isFile && file.lastModified() < deadline && file.delete()) removed += 1
        }
        if (removed > 0) {
            DebugLog.i(DebugLog.TAG_CAMERA, "camera cache pruned", mapOf("removed" to removed))
        }
    }

    /**
     * 文件名清洗：**必须保留扩展名**——`.ARW` 决定走 LibRaw 线性管线，`.jpg` 决定走 sRGB 管线。
     * 相机给的文件名一般合法，这里只是防住 `:`、`/` 之类的意外字符。
     */
    fun safeName(filename: String, handle: Int): String {
        val cleaned = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.')
        return if (cleaned.isEmpty()) "cam_$handle" else cleaned
    }
}
