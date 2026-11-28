package com.hifn.pixelcake.core.edit

import android.content.Context

/**
 * v0.9.0：3D 立方体 LUT（DEV_PLAN §3.4 形态 B 落地）。
 *
 * ## 为什么是自制二进制而不是 .cube 文本
 *
 * `.cube` 文本每像素一行「r g b」：33³ = 35937 行 ≈ **700KB/张**（文本浮点冗长），
 * 10 张就是 7MB——与「APK 还有余量，但不塞赘肉」的口径冲突。自定义二进制
 * `PCB3`（33³ × 3 × uint16 = **216KB/张**）同为无损、加载更快（无文本解析），
 * 生成器公式确定性强（`EditCropTest` 风格的逐点单测把守），授权风险为零
 * （全部公式自研，不引第三方色表——DEV_PLAN 提到的 MIT/CC 源是「可用选项」，非必须）。
 *
 * 格式：`"PCB3"`(4B) + version u16 + size u16 + `size³ × 3 × uint16LE`
 * （行主序、R 最快，与 .cube 规范同序；值 0..65535 线性映射 0..1）。
 *
 * ## 采样
 *
 * 三线性插值（8 角点）。栅格点处**精确等于表值**（floor/frac 处理保证无半像素偏移）；
 * 越界输入钳进 [0,1]。逐像素路径只有浮点乘加与数组读——与 PixelProgram 的
 * 「逐像素只有乘加与查表」纪律一致。
 */
class CubeLut private constructor(
    val size: Int,
    private val data: ShortArray
) {

    /** 三线性采样：输入输出都是归一化 [0,1]；`out[0..2]` = R/G/B。 */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val n = size
        val fx = (r.coerceIn(0f, 1f) * (n - 1))
        val fy = (g.coerceIn(0f, 1f) * (n - 1))
        val fz = (b.coerceIn(0f, 1f) * (n - 1))
        val x0 = fx.toInt().coerceIn(0, n - 1)
        val y0 = fy.toInt().coerceIn(0, n - 1)
        val z0 = fz.toInt().coerceIn(0, n - 1)
        val x1 = (x0 + 1).coerceAtMost(n - 1)
        val y1 = (y0 + 1).coerceAtMost(n - 1)
        val z1 = (z0 + 1).coerceAtMost(n - 1)
        val tx = fx - x0
        val ty = fy - y0
        val tz = fz - z0

        val strideZ = 3          // R 最快：z（蓝）变化时跨 3 个 short
        val strideY = 3 * n      // y（绿）变化时跨一整行 z
        val strideX = 3 * n * n  // x（红）变化时跨一整片 y
        for (c in 0 until 3) {
            val c000 = data[x0 * strideX + y0 * strideY + z0 * strideZ + c].toInt() and 0xffff
            val c100 = data[x1 * strideX + y0 * strideY + z0 * strideZ + c].toInt() and 0xffff
            val c010 = data[x0 * strideX + y1 * strideY + z0 * strideZ + c].toInt() and 0xffff
            val c110 = data[x1 * strideX + y1 * strideY + z0 * strideZ + c].toInt() and 0xffff
            val c001 = data[x0 * strideX + y0 * strideY + z1 * strideZ + c].toInt() and 0xffff
            val c101 = data[x1 * strideX + y0 * strideY + z1 * strideZ + c].toInt() and 0xffff
            val c011 = data[x0 * strideX + y1 * strideY + z1 * strideZ + c].toInt() and 0xffff
            val c111 = data[x1 * strideX + y1 * strideY + z1 * strideZ + c].toInt() and 0xffff
            val s = 1f / 65535f
            val v00 = (c000 * s) * (1f - tx) + (c100 * s) * tx
            val v10 = (c010 * s) * (1f - tx) + (c110 * s) * tx
            val v01 = (c001 * s) * (1f - tx) + (c101 * s) * tx
            val v11 = (c011 * s) * (1f - tx) + (c111 * s) * tx
            val v0 = v00 * (1f - ty) + v10 * ty
            val v1 = v01 * (1f - ty) + v11 * ty
            out[c] = v0 * (1f - tz) + v1 * tz
        }
    }

    companion object {

        private const val MAGIC = "PCB3"

        /** 解析 `PCB3` 二进制；结构不符返回 null（调用方按「资产损坏」降级为 no-op）。 */
        fun parse(bytes: ByteArray): CubeLut? = runCatching {
            if (bytes.size < 12) return@runCatching null
            if (!(bytes[0] == 'P'.code.toByte() && bytes[1] == 'C'.code.toByte() &&
                    bytes[2] == 'B'.code.toByte() && bytes[3] == '3'.code.toByte())
            ) {
                return@runCatching null
            }
            val version = readU16(bytes, 4)
            if (version != 1) return@runCatching null
            val size = readU16(bytes, 6)
            if (size < 2 || size > 128) return@runCatching null
            val expect = size * size * size * 3
            if (bytes.size < 8 + expect * 2) return@runCatching null
            val data = ShortArray(expect)
            for (i in 0 until expect) {
                data[i] = readU16(bytes, 8 + i * 2).toShort()
            }
            CubeLut(size, data)
        }.getOrNull()

        private fun readU16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
    }
}

/**
 * LUT 资产注册表：`assets/luts/<id>.pcub3` 懒解析 + 进程级缓存。
 *
 * 与 `AppSettings` 同一条「取（并记住）」纪律：`PixelCakeApp.onCreate` 里 `init(this)`，
 * 之后 `PixelProgram`（core 层，无 Context）在**构造期**解析一次——逐像素路径零字符串
 * 比较、零资产 I/O。未 init 或资产缺失/损坏 ⇒ 返回 null，调用方按 no-op 降级。
 */
object CubeLutRegistry {

    @Volatile
    private var appContext: Context? = null

    private val cache = HashMap<String, CubeLut?>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 调用方：PixelProgram 构造期（主线程/Default 各一次），热路径禁止。 */
    @Synchronized
    fun get(id: String): CubeLut? {
        if (id.isBlank() || id.contains('/') || id.contains('\\') || id.contains("..")) return null
        if (cache.containsKey(id)) return cache[id]
        val ctx = appContext ?: return null
        val lut = runCatching {
            ctx.assets.open("luts/$id.pcub3").use { input ->
                val buf = ByteArray(input.available())
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n < 0) break
                    off += n
                }
                CubeLut.parse(buf.copyOf(off))
            }
        }.getOrNull()
        cache[id] = lut
        return lut
    }
}
