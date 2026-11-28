package com.hifn.pixelcake

import android.app.Application
import com.hifn.pixelcake.diag.DebugLog
import com.hifn.pixelcake.ui.home.probeCapabilities
import com.hifn.pixelcake.ui.home.resolutionProfile

/**
 * Application 入口。
 * M0a 在此初始化调试日志模块，并在启动时 dump 设备能力 + 分辨率选档快照，
 * 真机联调时第一手就能拿到 OOM 排查所需的硬件信息。
 */
class PixelCakeApp : Application() {

    override fun onCreate() {
        super.onCreate()

        DebugLog.init(this)
        // v0.9.0：3D cube LUT 注册表锚定 App 上下文（PixelProgram 构造期懒解析用）
        com.hifn.pixelcake.core.edit.CubeLutRegistry.init(this)

        // v0.7.7（性能审计 P3-5）：probeCapabilities 含 binder 查询，启动主线程不再
        // 同步等待 —— 后台线程探测，快照晚几百毫秒落 logcat，用户无感。
        Thread {
            runCatching {
                val caps = probeCapabilities()
                val profile = caps.resolutionProfile()
                DebugLog.dumpBoot(
                    buildString {
                        append("manufacturer=").append(caps.manufacturer)
                        append(" model=").append(caps.model)
                        append(" soc=").append(caps.soc)
                        append(" android=").append(caps.androidVersion)
                        append("(").append(caps.sdkInt).append(")")
                        append(" abi=").append(caps.abi)
                        append(" totalMem=").append(caps.totalMemMiB).append("MiB")
                        append(" availMem=").append(caps.availMemMiB).append("MiB")
                        append(" wideColorGamut=").append(caps.wideColorGamut)
                        append(" tier=").append(profile.tier)
                        append(" fullRes=").append(profile.fullResLongEdge)
                        append(" proxy=").append(profile.proxyLongEdge)
                    }
                )
            }.onFailure {
                DebugLog.e(
                    DebugLog.TAG_ERROR,
                    "boot snapshot failed",
                    mapOf("err" to (it.message ?: it.javaClass.simpleName))
                )
            }
        }.start()
    }
}
