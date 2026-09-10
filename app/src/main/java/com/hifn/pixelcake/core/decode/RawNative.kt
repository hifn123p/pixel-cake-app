package com.hifn.pixelcake.core.decode

/**
 * LibRaw 的 JNI 封装。P1b 接入点：
 * - [getVersion] 返回 LibRaw 版本串，用于确认 NDK 管线链接的 LibRaw 正确。
 * - [openLinear] / [readLinearRows] / [closeLinear] 组成「16-bit 线性」解码会话：
 *   处理结果留在 native 侧，由 Kotlin 分带取走（33MP 全图一次性回传 JVM 必然 OOM）。
 *
 * native 库由 `init` 在首次访问时加载；方法名须与 cpp 中
 * `Java_com_hifn_pixelcake_core_decode_RawNative_*` 严格对应。
 */
object RawNative {
    init {
        System.loadLibrary("rawbridge")
    }

    external fun getVersion(): String

    /**
     * 打开一个线性解码会话：把 ARW 解马赛克为 **16-bit 线性 sRGB**（白点 = 65535）。
     *
     * 白平衡只作为初始基线（相机白平衡），曝光不自动拉伸，gamma 不做编码——
     * 这些全部交给 [com.hifn.pixelcake.core.edit.EditParams] 的参数栈在线性域重放。
     *
     * @param path        ARW 文件路径（LibRaw 需文件系统路径，调用方负责先把 Uri 落到临时文件）。
     * @param maxLongSide 目标长边上限（像素）；<=0 表示不缩放、输出全分辨率。
     * @param halfSize    true 时交 LibRaw 以 half_size=1 + user_qual=0 快速解（代理预览用：
     *                    解码量约 1/4、速度更快，质量足以预览）；false 为全质量（导出母版用）。
     * @return native 句柄；0 表示失败。**必须**由 [closeLinear] 释放。
     */
    external fun openLinear(path: String, maxLongSide: Int, halfSize: Boolean = false): Long

    /** 返回 [width, height, colors, bits]；句柄非法返回 null。 */
    external fun linearDims(handle: Long): IntArray?

    /** 返回相机白平衡乘子 [r, g, b, g2]（按绿通道归一），作为初始 WB 基线。 */
    external fun linearMeta(handle: Long): FloatArray?

    /**
     * 取 [y0, y0+rows) 段目标行，双线性重采样后写入 [out]（每像素 3 个 16-bit 线性分量）。
     * @return 实际写入行数；负数表示失败。
     */
    external fun readLinearRows(handle: Long, y0: Int, rows: Int, out: ShortArray): Int

    /** 释放会话与其持有的 native 处理结果。 */
    external fun closeLinear(handle: Long)

    val librawVersion: String get() = getVersion()
}
