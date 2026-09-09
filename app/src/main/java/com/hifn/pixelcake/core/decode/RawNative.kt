package com.hifn.pixelcake.core.decode

/**
 * LibRaw 的 JNI 封装。P1b 接入点：
 * - [getVersion] 当前返回占位串，仅用于验证 NDK 管线（librawbridge.so 可加载）。
 * - 后续提交（P1b-2/3）将新增 `decodeFull(...)`，用 LibRaw 把 ARW 全量解马赛克为 RGBA 缓冲。
 *
 * native 库由 [load] 在首次访问时加载；命名须与 cpp 中
 * `Java_com_hifn_pixelcake_core_decode_RawNative_*` 严格对应。
 */
object RawNative {
    init {
        System.loadLibrary("rawbridge")
    }

    external fun getVersion(): String
}
