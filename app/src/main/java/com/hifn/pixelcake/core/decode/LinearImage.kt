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

    /** 占用字节数，用于内存日志。 */
    val sizeBytes: Int get() = data.size * 2
}
