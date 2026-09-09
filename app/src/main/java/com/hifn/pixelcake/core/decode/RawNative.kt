package com.hifn.pixelcake.core.decode

/**
 * LibRaw 的 JNI 封装。P1b 接入点：
 * - [getVersion] 返回 LibRaw 版本串，用于确认 NDK 管线链接的 LibRaw 正确。
 * - [decodeFull] 用 LibRaw 把 ARW 全量解马赛克为 RGBA 缓冲。失败返回 null。
 *
 * native 库由 [load] 在首次访问时加载；命名须与 cpp 中
 * `Java_com_hifn_pixelcake_core_decode_RawNative_*` 严格对应。
 */
object RawNative {
    init {
        System.loadLibrary("rawbridge")
    }

    external fun getVersion(): String

    /**
     * LibRaw 全量解马赛克为 RGBA。失败返回 null。
     * @param path        ARW 文件路径（LibRaw 需文件系统路径，调用方负责先把 Uri 落到临时文件）。
     * @param maxLongSide 目标长边上限（像素）；<=0 表示不缩放、输出全分辨率。
     */
    external fun decodeFull(path: String, maxLongSide: Int): RawImage?

    val librawVersion: String get() = getVersion()
}
