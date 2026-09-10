package com.hifn.pixelcake.core.decode

import com.hifn.pixelcake.diag.DebugLog
import java.io.Closeable

/**
 * 一次「16-bit 线性」RAW 解码会话（[RawNative.openLinear] 的 Kotlin 包装）。
 *
 * 关键约束：33MP 的线性结果约 196MB，绝不能一次性搬进 JVM 堆。
 * 所以这里只暴露**分带读取** [readRows]，由 [com.hifn.pixelcake.core.edit.EditEngine]
 * 一整带一整带地渲染进目标位图；只有代理分辨率（默认长边 <= 2048，约 16MB）
 * 才允许用 [readAll] 整张取回。
 *
 * 必须 [close]，否则 native 侧的处理结果不会释放。
 */
class RawLinearSource private constructor(
    private var handle: Long,
    val width: Int,
    val height: Int,
    val bits: Int,
    /** 相机白平衡乘子 [r, g, b, g2]（按绿通道归一），作为初始 WB 基线。 */
    val cameraWhiteBalance: FloatArray
) : Closeable {

    /** 取 [y0, y0+rows) 段目标行写入 [out]（每像素 3 个 16-bit 分量）。返回实际行数，负数失败。 */
    fun readRows(y0: Int, rows: Int, out: ShortArray): Int {
        val h = handle
        if (h == 0L) return -1
        return RawNative.readLinearRows(h, y0, rows, out)
    }

    /** 整张取回为 [LinearImage]。仅用于代理分辨率；全分辨率请分带，否则必然 OOM。 */
    fun readAll(bandRows: Int = 64): LinearImage? {
        if (handle == 0L) return null
        val data = ShortArray(width * height * 3)
        val band = ShortArray(bandRows * width * 3)
        var y = 0
        while (y < height) {
            val rows = minOf(bandRows, height - y)
            val got = readRows(y, rows, band)
            if (got != rows) {
                DebugLog.e(
                    DebugLog.TAG_DECODE, "raw readAll failed",
                    mapOf("y" to y, "rows" to rows, "got" to got)
                )
                return null
            }
            System.arraycopy(band, 0, data, y * width * 3, rows * width * 3)
            y += rows
        }
        return LinearImage(width, height, data)
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            RawNative.closeLinear(h)
        }
    }

    companion object {
        /** 打开解码会话；失败返回 null（已打日志）。
         * @param halfSize 透传给 [RawNative.openLinear]：true=代理快速解，false=全质量（默认）。 */
        fun open(path: String, maxLongSide: Int, halfSize: Boolean = false): RawLinearSource? {
            val handle = try {
                RawNative.openLinear(path, maxLongSide, halfSize)
            } catch (t: Throwable) {
                DebugLog.e(
                    DebugLog.TAG_DECODE, "raw openLinear threw",
                    mapOf("err" to (t.message ?: t.javaClass.simpleName))
                )
                0L
            }
            if (handle == 0L) {
                DebugLog.e(DebugLog.TAG_DECODE, "raw openLinear failed", mapOf("path" to path))
                return null
            }
            val dims = RawNative.linearDims(handle)
            if (dims == null || dims.size < 4) {
                RawNative.closeLinear(handle)
                DebugLog.e(DebugLog.TAG_DECODE, "raw linearDims failed", mapOf("path" to path))
                return null
            }
            val wb = RawNative.linearMeta(handle) ?: floatArrayOf(1f, 1f, 1f, 1f)
            DebugLog.i(
                DebugLog.TAG_DECODE, "raw linear opened",
                mapOf(
                    "w" to dims[0], "h" to dims[1], "colors" to dims[2], "bits" to dims[3],
                    "wb" to wb.take(3).joinToString("/")
                )
            )
            return RawLinearSource(handle, dims[0], dims[1], dims[3], wb)
        }
    }
}
