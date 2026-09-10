package com.hifn.pixelcake.core.edit.preset

import com.hifn.pixelcake.core.edit.BeautyParams
import com.hifn.pixelcake.core.edit.ColorTransferParams
import com.hifn.pixelcake.core.edit.EditParams
import com.hifn.pixelcake.core.edit.NeutralGrayParams
import com.hifn.pixelcake.core.edit.RetouchState

/**
 * 参数栈预设（P1b-6 / `docs/P1b_DESIGN.md` §6）。
 *
 * 每个预设是「[EditParams]（tonal 调色）+ [RetouchState]（人像精修）」的快照组合，
 * 一键套用即同时覆盖两套参数。预设可与 ColorTransfer 的参考风格联动（见各预设的
 * `colorTransfer.refId`），统一调性体验。
 *
 * 当前以内置 Kotlin 数据表实现（零 Android 资源 IO，纯 JVM 可测）；后续若需用户自定义 /
 * 外部分发的 `.cube` 滤镜，再外置为 `assets/preset/` 下的 JSON 预设（接口不变）。
 *
 * 共 10 套：原图 / 日系 / 胶片 / 复古 / 莫兰迪 / 奶油肌 / 波特拉 / 黑白 / 冷调 / 暖调。
 * `none`（原图）恒为首项，作为「无预设」兜底与手动调整的复位目标。
 */
data class Preset(
    val id: String,
    val name: String,
    val params: EditParams = EditParams(),
    val retouch: RetouchState = RetouchState()
)

object Presets {
    val ALL: List<Preset> = listOf(
        Preset("none", "原图"),
        Preset(
            id = "jp", name = "日系",
            params = EditParams(exposureEv = 0.3f, contrast = -0.1f, saturation = 0.1f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "jp", intensity = 0.6f))
        ),
        Preset(
            id = "film", name = "胶片",
            params = EditParams(contrast = 0.15f, saturation = 0.05f, temperature = 0.1f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "portra", intensity = 0.5f))
        ),
        Preset(
            id = "retro", name = "复古",
            params = EditParams(saturation = -0.2f, contrast = -0.05f, temperature = 0.15f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "retro", intensity = 0.6f))
        ),
        Preset(
            id = "morandi", name = "莫兰迪",
            params = EditParams(saturation = -0.35f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "morandi", intensity = 0.7f))
        ),
        Preset(
            id = "creamy", name = "奶油肌",
            params = EditParams(),
            retouch = RetouchState(
                neutralGray = NeutralGrayParams(strength = 0.4f, radiusNorm = 0.02f),
                beauty = BeautyParams(slimFace = 0.2f),
                colorTransfer = ColorTransferParams(refId = "portra", intensity = 0.3f)
            )
        ),
        Preset(
            id = "portra", name = "波特拉",
            params = EditParams(contrast = 0.05f, saturation = 0.08f, temperature = 0.08f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "portra", intensity = 0.55f))
        ),
        Preset(
            id = "bw", name = "黑白",
            params = EditParams(saturation = 0f, lutId = "bw", lutIntensity = 1f),
            retouch = RetouchState()
        ),
        Preset(
            id = "cool", name = "冷调",
            params = EditParams(temperature = -0.15f, lutId = "cool", lutIntensity = 0.8f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "fuji", intensity = 0.4f))
        ),
        Preset(
            id = "warm", name = "暖调",
            params = EditParams(temperature = 0.15f, lutId = "warm", lutIntensity = 0.8f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "retro", intensity = 0.35f))
        )
    )

    /** 按 id 查预设；找不到返回 null（UI 用 "none" 兜底）。 */
    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }
}
