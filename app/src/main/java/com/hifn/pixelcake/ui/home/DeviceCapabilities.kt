package com.hifn.pixelcake.ui.home

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.Display

/**
 * 一加 15 / A7C2 的关键尺寸常量。
 * 用于核算 fp16 管线在真机上的内存可行性。
 */
private const val A7C2_WIDTH = 7008L
private const val A7C2_HEIGHT = 4672L
/** RGBA16F：4 通道 x 2 字节 */
private const val FP16_BYTES_PER_PIXEL = 8L
private const val MIB = 1024L * 1024L

data class DeviceCapabilities(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val androidVersion: String,
    val sdkInt: Int,
    val abi: String,
    /** 屏幕是否支持广色域（Display P3 及以上） */
    val wideColorGamut: Boolean,
    val hdrTypes: List<String>,
    val refreshRateHz: Float,
    val totalMemMiB: Long,
    val availMemMiB: Long,
    val fp16SingleMiB: Long,
    val fp16TripleMiB: Long,
    /** 可用内存是否足以支撑全分辨率 fp16 管线（含三缓冲 + 系统余量） */
    val fullResFeasible: Boolean
)

fun Context.probeCapabilities(): DeviceCapabilities {
    val display = display
    val memInfo = ActivityManager.MemoryInfo().also {
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }

    val single = A7C2_WIDTH * A7C2_HEIGHT * FP16_BYTES_PER_PIXEL / MIB
    val triple = single * 3
    val availMiB = memInfo.availMem / MIB

    return DeviceCapabilities(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        soc = Build.SOC_MODEL,
        androidVersion = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        wideColorGamut = display?.isWideColorGamut ?: false,
        // supportedHdrTypes 是 IntArray?，orEmpty() 只对 Array/List/String 有定义，
        // 基本类型数组不适用，因此先 map 再兜底
        hdrTypes = display?.hdrCapabilities?.supportedHdrTypes?.map(::hdrTypeName) ?: emptyList(),
        refreshRateHz = display?.mode?.refreshRate ?: 0f,
        totalMemMiB = memInfo.totalMem / MIB,
        availMemMiB = availMiB,
        fp16SingleMiB = single,
        fp16TripleMiB = triple,
        // 三缓冲之外再留一倍冗余给 Compose UI、位图缓存与系统抖动
        fullResFeasible = availMiB >= triple * 2
    )
}

private fun hdrTypeName(type: Int): String = when (type) {
    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
    else -> "Unknown($type)"
}
