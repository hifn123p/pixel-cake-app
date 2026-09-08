package com.hifn.pixelcake.ui.home

/**
 * 按设备内存档位选择导出 / 代理分辨率（详见 DEV_PLAN §6.2）。
 * 仅用于启动快照与后续导出决策，不影响编辑链路本身。
 *
 * 全分辨率 RGBA_8888 ≈ 131MB、RGBA_F16 ≈ 262MB（A7C II 33MP）：
 *   - 旗舰 ≥12GB：全分辨率 + RGBA_F16，代理 2048
 *   - 中高 8–12GB：全分辨率 RGBA_8888，代理 2048
 *   - 中端 6–8GB：全分辨率 RGBA_8888，代理 1536
 *   - 低 <6GB：长边封顶 4096，代理 1024
 */
data class ResolutionProfile(
    val tier: String,
    val bitmapConfig: String,
    val fullResLongEdge: Int,
    val proxyLongEdge: Int
)

fun DeviceCapabilities.resolutionProfile(): ResolutionProfile {
    val memGb = totalMemMiB / 1024
    return when {
        memGb >= 12 -> ResolutionProfile("flagship", "RGBA_F16", 7008, 2048)
        memGb >= 8  -> ResolutionProfile("high", "RGBA_8888", 7008, 2048)
        memGb >= 6  -> ResolutionProfile("mid", "RGBA_8888", 7008, 1536)
        else        -> ResolutionProfile("low", "RGBA_8888", 4096, 1024)
    }
}
