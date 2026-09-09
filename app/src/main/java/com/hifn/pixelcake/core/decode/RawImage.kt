package com.hifn.pixelcake.core.decode

/**
 * LibRaw 全量解码的 native 回传结构。
 * [pixels] 为 RGBA_8888 交错字节流（R,G,B,A 每像素 4 字节），长度 = [width] * [height] * 4。
 * 由 [RawNative.decodeFull] 经 JNI 构造后回传，再在 Kotlin 侧转为 Bitmap。
 */
data class RawImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray
) {
    init {
        require(pixels.size == width * height * 4) {
            "pixels size ${pixels.size} != width*height*4 (${width * height * 4})"
        }
    }
}
