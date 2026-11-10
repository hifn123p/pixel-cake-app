package com.hifn.pixelcake.core.decode

/**
 * 16-bit 线性 sRGB 底图：每像素 3 个分量，取值 0..65535，白点 = 65535。
 *
 * 这是 ARW 的**编辑母版**——由 LibRaw 解出后缓存在 Kotlin 侧，
 * 参数栈每次重渲都作用在这份线性数据上，而不是作用在相机烘焙过的 JPEG 上。
 * JPEG/HEIF 不需要它，走 8-bit sRGB 的 [android.graphics.Bitmap] 路径
 * （渲染前先经 [com.hifn.pixelcake.core.edit.ColorMath.SRGB8_TO_LINEAR16] 转线性，
 * 与 RAW 共用同一条管线）。
 */
class LinearImage(
    val width: Int,
    val height: Int,
    /** 行优先、每像素 3 分量的 16-bit 线性数据，长度 = width * height * 3。 */
    val data: ShortArray
) {
    init {
        require(width > 0 && height > 0) { "bad size ${width}x${height}" }
        require(data.size == width * height * 3) {
            "data size ${data.size} != width*height*3 (${width * height * 3})"
        }
    }

    /**
     * Q5：q×90°（顺时针）旋转。16-bit 数据**纯下标重排**（零精度损失），
     * q 奇数时宽高互换。纯函数，JVM 可测（`LinearImageRotateTest`）。
     */
    fun rotateQuarter(quarterTurns: Int): LinearImage {
        val q = ((quarterTurns % 4) + 4) % 4
        if (q == 0) return this
        val w = width
        val h = height
        val out = ShortArray(data.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val s = (y * w + x) * 3
                val dx: Int
                val dy: Int
                when (q) {
                    1 -> { dx = h - 1 - y; dy = x }
                    2 -> { dx = w - 1 - x; dy = h - 1 - y }
                    else -> { dx = y; dy = w - 1 - x }
                }
                val nw = if (q == 2) w else h
                val d = (dy * nw + dx) * 3
                out[d] = data[s]
                out[d + 1] = data[s + 1]
                out[d + 2] = data[s + 2]
            }
        }
        return if (q == 2) LinearImage(w, h, out) else LinearImage(h, w, out)
    }

    /** 占用字节数，用于内存日志。 */
    val sizeBytes: Int get() = data.size * 2
}
