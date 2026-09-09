package com.hifn.pixelcake.ui.home

/**
 * 按设备内存档位选择导出 / 代理分辨率（详见 DEV_PLAN §6.2）。
 * 仅用于启动快照与后续导出决策，不影响编辑链路本身。
 *
 * 全分辨率 RGBA_8888 ≈ 131MB（A7C II 33MP）：
 *   - 旗舰 ≥12GB：全分辨率，代理 2048
 *   - 中高 8–12GB：全分辨率，代理 2048
 *   - 中端 6–8GB：全分辨率，代理 1536
 *   - 低 <6GB：长边封顶 4096，代理 1024
 *
 * 注：此前这里带一个 `bitmapConfig` 字段（旗舰档写 "RGBA_F16"），
 * 但全代码落位图处一律硬编码 ARGB_8888，该字段从未被读取，属死字段（FIX_LIST F18）。
 * 真正要用 F16 需要整条管线（解码输出 → 渲染缓冲 → 编码）一起换，单独改字段只会误导。
 */
data class ResolutionProfile(
    val tier: String,
    val fullResLongEdge: Int,
    val proxyLongEdge: Int
)

fun DeviceCapabilities.resolutionProfile(): ResolutionProfile {
    val memGb = totalMemMiB / 1024
    return when {
        memGb >= 12 -> ResolutionProfile("flagship", 7008, 2048)
        memGb >= 8  -> ResolutionProfile("high", 7008, 2048)
        memGb >= 6  -> ResolutionProfile("mid", 7008, 1536)
        else        -> ResolutionProfile("low", 4096, 1024)
    }
}
