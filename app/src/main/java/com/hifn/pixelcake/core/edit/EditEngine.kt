package com.hifn.pixelcake.core.edit

import android.graphics.Bitmap
import kotlin.math.pow
import java.util.concurrent.Executors

/**
 * 把 [EditParams] 应用到位图上。
 * [renderInto] 复用同一个目标位图，避免滑块拖动时每帧重新分配 ~16MB 代理图。
 *
 * 性能：
 *  - 单像素运算走 [ColorMath] 的全局 srgb<->linear LUT，不再逐像素 pow
 *    （这是此前 2MP 渲染卡 3-5 秒的主因，现已消除）。
 *  - 整条像素循环按 CPU 核心分片并行，24MP 全分辨率导出也能压进亚秒级。
 */
object EditEngine {

    /** 跨核分片的线程池（懒初始化，大小对齐可用核心数）。 */
    private val pool by lazy {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        Executors.newFixedThreadPool(cores) { r ->
            Thread(r, "pxcake-render").apply { isDaemon = true }
        }
    }

    fun renderInto(target: Bitmap, base: Bitmap, p: EditParams) {
        val w = base.width
        val h = base.height
        val n = w * h
        val px = IntArray(n)
        base.getPixels(px, 0, w, 0, 0, w, h)

        // 预构建：亮度曲线 LUT 与曝光系数 ev 各只算一次，循环内只读。
        val lut = ColorMath.buildLumaLut(p.lumaPoints)
        val ev = 2f.pow(p.exposureEv)

        // 按核心数分片；每片写入独立索引区间。processPixel 为纯函数，
        // 只读 p/lut/ev，因此多线程写不同区间天然安全（无共享写、无伪共享热点）。
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val chunk = (n + cores - 1) / cores
        val jobs = ArrayList<java.util.concurrent.Future<*>>(cores)
        for (c in 0 until cores) {
            val start = c * chunk
            if (start >= n) break
            val end = Math.min(start + chunk, n)
            jobs.add(pool.submit {
                for (i in start until end) {
                    val cp = px[i]
                    val r = (cp shr 16) and 0xff
                    val g = (cp shr 8) and 0xff
                    val b = cp and 0xff
                    val (nr, ng, nb) = ColorMath.processPixel(r, g, b, p, lut, ev)
                    px[i] = (0xff shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            })
        }
        // 等所有分片完成再落盘，保证 setPixels 看到完整结果。
        jobs.forEach { it.get() }
        target.setPixels(px, 0, w, 0, 0, w, h)
    }

    fun render(base: Bitmap, p: EditParams, recycleBase: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        renderInto(out, base, p)
        if (recycleBase) base.recycle()
        return out
    }
}
