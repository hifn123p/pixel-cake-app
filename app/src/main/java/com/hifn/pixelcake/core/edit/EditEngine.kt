package com.hifn.pixelcake.core.edit

import android.graphics.Bitmap
import com.hifn.pixelcake.core.decode.LinearImage
import com.hifn.pixelcake.core.decode.RawLinearSource
import com.hifn.pixelcake.core.edit.retouch.RetouchLayer
import com.hifn.pixelcake.diag.DebugLog
import java.util.concurrent.Executors

/**
 * 把 [EditParams]（+ retouch 层）应用到位图上。
 *
 * 三条入口共用同一条像素管线（[PixelProgram]），只是底图来源不同：
 *  - [renderIntoSrgb]        ：底图是 8-bit sRGB 位图（JPEG/HEIF，或 ARW 的秒开占位图）；
 *  - [renderIntoLinear]      ：底图是 [LinearImage]（ARW 的 16-bit 线性母版，滑块实时预览走这条）；
 *  - [renderLinearFile]      ：导出专用，全分辨率 RAW 边解码边分带渲染，不落 JVM 堆。
 *
 * 内存与性能（FIX_LIST F05/F06/F08；P1b-4 `docs/P1b_DESIGN.md` §2）：
 *  - 一律**分带**处理：一次只持有 `BAND_ROWS` 行的源/目标缓冲，
 *    不再出现「整幅 IntArray + jbyteArray + Bitmap」同时在世的 500MB 峰值；
 *  - 带内按 CPU 核心切片并行，每帧只构建一次 [PixelProgram]，逐像素零装箱；
 *  - 带与带之间检查取消回调，滑块连续拖动时可协作取消，不再排队积压；
 *  - tonal 渲染完成后，若传入 [RetouchState] 则在已物化的目标 Bitmap 上跑 retouch 整图 pass
 *    （磨皮/液化/祛瑕/追色），复用目标 Bitmap，不额外搬 16-bit 母版。
 *
 * **参数顺序约定**：`retouch` / `mask` 放在**取消/进度 lambda 之前**，使尾随 lambda 永远绑定到
 * `isCancelled` / `onProgress`（避免 Kotlin 尾随 lambda 误绑末尾参数的编译错误，见 CI run `34481989723`）。
 */
object EditEngine {

    /** 每带行数。越大并行效率越高、内存占用越大；32 行在 33MP 下约 2.7MB 源缓冲。 */
    private const val BAND_ROWS = 32

    /** 小于这个像素数的带直接串行，避免线程切换开销盖过收益。 */
    private const val PARALLEL_THRESHOLD = 4096

    /** 跨核分片的线程池（懒初始化，大小对齐可用核心数）。 */
    private val pool by lazy {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        Executors.newFixedThreadPool(cores) { r ->
            Thread(r, "pxcake-render").apply { isDaemon = true }
        }
    }

    private val cores: Int get() = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

    /** 8-bit sRGB 底图 -> 目标位图（复用同一目标位图，避免每帧重分配）。 */
    fun renderIntoSrgb(target: Bitmap, base: Bitmap, p: EditParams) {
        val w = base.width
        val h = base.height
        if (target.width != w || target.height != h) {
            DebugLog.e(
                DebugLog.TAG_EDIT, "render size mismatch",
                mapOf("target" to "${target.width}x${target.height}", "base" to "${w}x$h")
            )
            return
        }
        val program = PixelProgram(p)
        val band = IntArray(BAND_ROWS * w)
        var y = 0
        while (y < h) {
            val rows = minOf(BAND_ROWS, h - y)
            val count = rows * w
            base.getPixels(band, 0, w, 0, y, w, rows)
            runBand(count) { i ->
                val cp = band[i]
                band[i] = program.applySrgb8((cp shr 16) and 0xff, (cp shr 8) and 0xff, cp and 0xff)
            }
            target.setPixels(band, 0, w, 0, y, w, rows)
            y += rows
        }
    }

    /** 16-bit 线性底图 -> 目标位图。返回 false 表示被取消。 */
    fun renderIntoLinear(
        target: Bitmap,
        base: LinearImage,
        p: EditParams,
        retouch: RetouchState? = null,
        mask: RetouchMask? = null,
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        val w = base.width
        val h = base.height
        if (target.width != w || target.height != h) {
            DebugLog.e(
                DebugLog.TAG_EDIT, "render size mismatch",
                mapOf("target" to "${target.width}x${target.height}", "base" to "${w}x$h")
            )
            return false
        }
        val program = PixelProgram(p)
        val band = IntArray(BAND_ROWS * w)
        var y = 0
        while (y < h) {
            if (isCancelled()) return false
            val rows = minOf(BAND_ROWS, h - y)
            val count = rows * w
            val base3 = y * w * 3
            runBand(count) { i ->
                val o = base3 + i * 3
                band[i] = program.applyLinear(
                    base.data[o].toInt() and 0xffff,
                    base.data[o + 1].toInt() and 0xffff,
                    base.data[o + 2].toInt() and 0xffff
                )
            }
            target.setPixels(band, 0, w, 0, y, w, rows)
            y += rows
        }
        if (retouch != null) RetouchLayer.apply(target, retouch, mask)
        return true
    }

    /**
     * 全分辨率 RAW 导出：打开一次线性会话，边取行边渲染，全程不把整幅线性图搬进 JVM 堆。
     *
     * @param onProgress 0..100 的进度回调，返回 false 表示取消。
     * @return 渲染完成的位图；失败返回 null。
     */
    fun renderLinearFile(
        path: String,
        maxLongSide: Int,
        p: EditParams,
        retouch: RetouchState? = null,
        mask: RetouchMask? = null,
        onProgress: (Int) -> Boolean = { true }
    ): Bitmap? {
        val src = RawLinearSource.open(path, maxLongSide) ?: return null
        return try {
            val w = src.width
            val h = src.height
            val target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val program = PixelProgram(p)
            val srcBand = ShortArray(BAND_ROWS * w * 3)
            val dstBand = IntArray(BAND_ROWS * w)
            var y = 0
            var ok = true
            while (y < h && ok) {
                val rows = minOf(BAND_ROWS, h - y)
                if (src.readRows(y, rows, srcBand) != rows) {
                    DebugLog.e(DebugLog.TAG_EDIT, "export readRows failed", mapOf("y" to y))
                    ok = false
                    break
                }
                val count = rows * w
                runBand(count) { i ->
                    val o = i * 3
                    dstBand[i] = program.applyLinear(
                        srcBand[o].toInt() and 0xffff,
                        srcBand[o + 1].toInt() and 0xffff,
                        srcBand[o + 2].toInt() and 0xffff
                    )
                }
                target.setPixels(dstBand, 0, w, 0, y, w, rows)
                y += rows
                ok = onProgress(y * 100 / h)
            }
            if (ok) {
                if (retouch != null) RetouchLayer.apply(target, retouch, mask)
                DebugLog.i(DebugLog.TAG_EDIT, "export linear ok", mapOf("w" to w, "h" to h))
                target
            } else {
                target.recycle()
                null
            }
        } finally {
            src.close()
        }
    }

    /** 在一个带内按核心切片并行执行 [block]（各片写不同下标，天然无竞争）。 */
    private inline fun runBand(count: Int, crossinline block: (Int) -> Unit) {
        val n = cores
        if (count < PARALLEL_THRESHOLD || n == 1) {
            for (i in 0 until count) block(i)
            return
        }
        val chunk = (count + n - 1) / n
        val jobs = ArrayList<java.util.concurrent.Future<*>>(n)
        for (c in 0 until n) {
            val start = c * chunk
            if (start >= count) break
            val end = minOf(start + chunk, count)
            jobs.add(pool.submit {
                for (i in start until end) block(i)
            })
        }
        // 等所有分片完成再落盘，保证 setPixels 看到完整结果。
        jobs.forEach { it.get() }
    }

    fun render(base: Bitmap, p: EditParams, recycleBase: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        renderIntoSrgb(out, base, p)
        if (recycleBase) base.recycle()
        return out
    }
}
