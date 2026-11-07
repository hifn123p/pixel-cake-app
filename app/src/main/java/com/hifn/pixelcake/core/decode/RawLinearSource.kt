package com.hifn.pixelcake.core.decode

import com.hifn.pixelcake.diag.DebugLog
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * 一次「16-bit 线性」RAW 解码会话（[RawNative.openLinear] 的 Kotlin 包装）。
 *
 * 关键约束：33MP 的线性结果约 196MB，绝不能一次性搬进 JVM 堆。
 * 所以这里只暴露**分带读取** [readRows]，由 [com.hifn.pixelcake.core.edit.EditEngine]
 * 一整带一整带地渲染进目标位图；只有代理分辨率（默认长边 <= 2048，约 16MB）
 * 才允许用 [readAll] 整张取回。
 *
 * 必须 [close]，否则 native 侧的处理结果不会释放。
 *
 * ⚠️ 句柄用 [AtomicLong] 而不是普通 `var`：native 侧的 `closeLinear` 是
 * `dcraw_clear_mem(...) + delete s`，**不可逆**。普通字段下 (a) 另一个线程可能读到
 * 已被置 0 之前的旧值 → use-after-free；(b) `if (h != 0L) { handle = 0L; ... }`
 * 是非原子的 check-then-act，两个线程可同时通过判空 → double free = native crash。
 * 今天生产路径都在单协程内 close，但本类对外是 `Closeable`、Provider 又是并发的，
 * 安全性不能靠「调用方恰好都在单协程里」维持。
 */
class RawLinearSource private constructor(
    handle: Long,
    val width: Int,
    val height: Int,
    val bits: Int,
    /** 相机白平衡乘子 [r, g, b, g2]（按绿通道归一），作为初始 WB 基线。 */
    val cameraWhiteBalance: FloatArray
) : Closeable {

    private val handleRef = AtomicLong(handle)

    /**
     * 读写与释放的互斥锁。AtomicLong 只消灭了 double-free；「先快照 h、再进 JNI」与
     * 另一线程的 `close()`（getAndSet 后立刻释放 native 会话）之间仍有一个窗口 ——
     * close 恰好插进这两步时，`readLinearRows` 拿到的是悬垂指针（use-after-free）。
     * 本类对外是 `Closeable`、调用方又不止一个协程，安全性不能靠「调用方恰好串行」维持。
     */
    private val ioLock = Any()

    /** 取 [y0, y0+rows) 段目标行写入 [out]（每像素 3 个 16-bit 分量）。返回实际行数，负数失败。 */
    fun readRows(y0: Int, rows: Int, out: ShortArray): Int = synchronized(ioLock) {
        val h = handleRef.get()
        if (h == 0L) return -1
        // ⚠️ 必须在这里校验 out 的长度：native 侧是 `GetShortArrayElements` + 裸指针写，
        // 传错长度就是**越界写内存**（Java 侧兜不住）。
        if (y0 < 0 || rows <= 0 || y0 + rows > height) return -1
        val need = rows.toLong() * width.toLong() * 3L
        if (out.size < need) return -2
        return RawNative.readLinearRows(h, y0, rows, out)
    }

    /** 整张取回为 [LinearImage]。仅用于代理分辨率；全分辨率请分带，否则必然 OOM。 */
    fun readAll(bandRows: Int = 64): LinearImage? {
        if (handleRef.get() == 0L) return null
        // 护栏必须在**数据边界**上，而不是只写在 KDoc 里：
        // 全分辨率误调用会尝试分配 ShortArray(7008*4672*3) ≈ 196MB。
        val need = width.toLong() * height.toLong() * 3L * 2L
        if (need > MAX_READ_ALL_BYTES) {
            DebugLog.e(
                DebugLog.TAG_DECODE, "raw readAll refused",
                mapOf("w" to width, "h" to height, "needMB" to need / 1024 / 1024)
            )
            return null
        }
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

    override fun close() = synchronized(ioLock) {
        // getAndSet 原子取走：只有一个人拿得到句柄，另一个线程看到 0 直接返回。
        // 整段包在 ioLock 里 ⇒ 与 readRows 的 JNI 调用互斥，「close 后仍有一次在途读」
        // 这个 use-after-free 窗口从结构上关死。
        val h = handleRef.getAndSet(0L)
        if (h != 0L) RawNative.closeLinear(h)
    }

    companion object {
        /** [readAll] 的上限（字节）：代理长边 2048 约 25MB，留足余量；33MP 全幅约 196MB 直接拒绝。 */
        private const val MAX_READ_ALL_BYTES = 64L * 1024 * 1024

        /**
         * B4：native 线性会话的内存预算。审计口径 33MP 的 native 侧下界 ≈ 458MB
         * （LibRaw 解码中间态 + 16-bit 线性结果），× 1.2 安全系数。
         * `MemAvailable` 低于预算时**拒绝打开**——宁可让用户拿到「RAW 解码失败」，
         * 也不能让 lowmemorykiller 在 native 分配中途把整个 App 杀掉。
         */
        const val NATIVE_FLOOR_FULL_BYTES = 560L * 1024 * 1024

        /** 代理快速解（halfSize=true）时 native 结果只有全幅的 1/4，预算同步收敛。 */
        const val NATIVE_FLOOR_HALF_BYTES = 170L * 1024 * 1024

        /**
         * 从 `/proc/meminfo` 文本解析 MemAvailable（字节）。**纯函数，JVM 可测**。
         * @return 找不到该行 / 格式异常时返回 null（调用方按「无法判断」放行）。
         */
        internal fun memAvailableBytes(procMeminfo: String): Long? {
            for (line in procMeminfo.lineSequence()) {
                if (!line.startsWith("MemAvailable")) continue
                val kb = line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()
                    ?.toLongOrNull() ?: return null
                return kb * 1024L
            }
            return null
        }

        private fun nativeBudgetBytes(halfSize: Boolean): Long =
            if (halfSize) NATIVE_FLOOR_HALF_BYTES else NATIVE_FLOOR_FULL_BYTES

        /** 打开解码会话；失败返回 null（已打日志）。
         * @param halfSize 透传给 [RawNative.openLinear]：true=代理快速解，false=全质量（默认）。 */
        fun open(path: String, maxLongSide: Int, halfSize: Boolean = false): RawLinearSource? {
            // B4：开 native 会话**之前**查可用内存。native 分配不吃 Java 堆预算，
            // Java 侧的 OOM 兜底管不到它 —— lowmemorykiller 直接杀进程，连报错的机会都没有。
            val avail = try {
                memAvailableBytes(java.io.File("/proc/meminfo").readText())
            } catch (_: Throwable) {
                // 非 suspend：读不到就按「无法判断」放行（旧行为），不拦正常设备
                null
            }
            val budget = nativeBudgetBytes(halfSize)
            if (avail != null && avail < budget) {
                DebugLog.w(
                    DebugLog.TAG_DECODE, "raw openLinear refused: mem budget",
                    mapOf(
                        "availMB" to avail / 1024 / 1024,
                        "budgetMB" to budget / 1024 / 1024,
                        "halfSize" to halfSize
                    )
                )
                return null
            }
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
            // openLinear 成功后的两次 JNI 查询（NewIntArray/NewFloatArray 在堆耗尽时抛
            // OutOfMemoryError）必须也有兜底：此时 handle 已建（全分辨率下 native 侧
            // 约 196MB），不 closeLinear 就是「在最缺内存的时刻泄漏一大块 native 内存」。
            val dims: IntArray?
            val wb: FloatArray?
            try {
                dims = RawNative.linearDims(handle)
                // ⚠️ 还要挡住 native 返回 {0,0,0,0}：不校验的话 `LinearImage` 的 require 会抛，
                // 异常一路冒到 `Decoder.decodeToProxy` 的 catch，临时文件就再也无人回收了。
                if (dims == null || dims.size < 4 || dims[0] <= 0 || dims[1] <= 0) {
                    RawNative.closeLinear(handle)
                    DebugLog.e(DebugLog.TAG_DECODE, "raw linearDims invalid", mapOf("path" to path))
                    return null
                }
                wb = RawNative.linearMeta(handle)
            } catch (t: Throwable) {
                RawNative.closeLinear(handle)
                DebugLog.e(
                    DebugLog.TAG_DECODE, "raw dims/meta threw",
                    mapOf("err" to (t.message ?: t.javaClass.simpleName))
                )
                return null
            }
            val wbSafe = if (wb == null || wb.size < 4) floatArrayOf(1f, 1f, 1f, 1f) else wb
            DebugLog.i(
                DebugLog.TAG_DECODE, "raw linear opened",
                mapOf(
                    "w" to dims[0], "h" to dims[1], "colors" to dims[2], "bits" to dims[3],
                    "wb" to wbSafe.take(3).joinToString("/")
                )
            )
            return RawLinearSource(handle, dims[0], dims[1], dims[3], wbSafe)
        }
    }
}
