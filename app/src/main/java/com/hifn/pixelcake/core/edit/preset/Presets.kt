package com.hifn.pixelcake.core.edit.preset

import com.hifn.pixelcake.core.edit.BeautyParams
import com.hifn.pixelcake.core.edit.BodyPartBeautyParams
import com.hifn.pixelcake.core.edit.ColorTransferParams
import com.hifn.pixelcake.core.edit.EditParams
import com.hifn.pixelcake.core.edit.NeutralGrayParams
import com.hifn.pixelcake.core.edit.RetouchSwitches
import com.hifn.pixelcake.core.edit.RetouchState

/**
 * 参数栈预设（`docs/PRESET_DESIGN.md` §1）。
 *
 * 按**风格 / 场景**分类，每类 6–12 套，总量 40+。
 * 预设是「参数栈快照」+「人像精修快照」的组合，一键套用同时覆盖两套参数。
 *
 * ## 分类逻辑
 *
 * - **风格**：色彩倾向（日系、胶片、复古、莫兰迪……）
 * - **人像**：以人物为主角的场景（日常、婚纱、街拍、自拍……）
 * - **风景**：自然/城市景观（日出、日落、雪景、夜景……）
 * - **食物**：餐饮摄影（暖调、清透、质感……）
 * - **黑白**：单色专属
 *
 * 每套预设除了 tonal 参数，还附带对应的 [ColorTransferParams.refId]，
 * 让「预设色调」与「追色风格」联动，统一调性体验。
 *
 * 当前以内置 Kotlin 数据表实现（零 Android 资源 IO，纯 JVM 可测）；后续若需用户自定义
 * / 外部分发的 `.cube` 滤镜，再外置为 `assets/preset/` 下的 JSON 预设（接口不变）。
 */
data class Preset(
    val id: String,
    val name: String,
    val category: PresetCategory,
    val params: EditParams = EditParams(),
    val retouch: RetouchState = RetouchState()
)

enum class PresetCategory(val label: String) {
    Style("风格"),
    Portrait("人像"),
    Landscape("风景"),
    Food("食物"),
    Bw("黑白")
}

object Presets {
    val ALL: List<Preset> = listOf(
        // ===== 原图兜底 =====
        Preset("none", "原图", PresetCategory.Style),
        Preset(
            id = "jp", name = "日系", category = PresetCategory.Style,
            params = EditParams(exposureEv = 0.3f, contrast = -0.1f, saturation = 0.1f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "jp", intensity = 0.6f))
        ),
        Preset(
            id = "film", name = "胶片", category = PresetCategory.Style,
            params = EditParams(contrast = 0.15f, saturation = 0.05f, temperature = 0.1f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "portra", intensity = 0.5f))
        ),
        Preset(
            id = "retro", name = "复古", category = PresetCategory.Style,
            params = EditParams(saturation = -0.2f, contrast = -0.05f, temperature = 0.15f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "retro", intensity = 0.6f))
        ),
        Preset(
            id = "morandi", name = "莫兰迪", category = PresetCategory.Style,
            params = EditParams(saturation = -0.35f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "morandi", intensity = 0.7f))
        ),
        Preset(
            id = "creamy", name = "奶油肌", category = PresetCategory.Portrait,
            params = EditParams(),
            retouch = RetouchState(
                neutralGray = NeutralGrayParams(strength = 0.4f, radiusNorm = 0.02f),
                beauty = BeautyParams(slimFace = 0.2f),
                colorTransfer = ColorTransferParams(refId = "portra", intensity = 0.3f)
            )
        ),
        Preset(
            id = "portra", name = "波特拉", category = PresetCategory.Style,
            params = EditParams(contrast = 0.05f, saturation = 0.08f, temperature = 0.08f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "portra", intensity = 0.55f))
        ),
        Preset(
            id = "bw", name = "黑白", category = PresetCategory.Bw,
            params = EditParams(saturation = 0f, lutId = "bw", lutIntensity = 1f),
            retouch = RetouchState()
        ),
        Preset(
            id = "cool", name = "冷调", category = PresetCategory.Style,
            params = EditParams(temperature = -0.15f, lutId = "cool", lutIntensity = 0.8f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "fuji", intensity = 0.4f))
        ),
        Preset(
            id = "warm", name = "暖调", category = PresetCategory.Style,
            params = EditParams(temperature = 0.15f, lutId = "warm", lutIntensity = 0.8f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "retro", intensity = 0.35f))
        ),

        // ===== 风格（追加）=====
        Preset(
            id = "faded", name = "褪色", category = PresetCategory.Style,
            params = EditParams(saturation = -0.4f, contrast = -0.1f, exposureEv = 0.2f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "retro", intensity = 0.5f))
        ),
        Preset(
            id = "cyber", name = "赛博", category = PresetCategory.Style,
            params = EditParams(saturation = 0.4f, contrast = 0.2f, temperature = -0.1f, hue = 0.1f),
            retouch = RetouchState()
        ),
        Preset(
            id = "vivid", name = "鲜艳", category = PresetCategory.Style,
            params = EditParams(saturation = 0.35f, contrast = 0.1f, vibrance = 0.2f),
            retouch = RetouchState()
        ),
        Preset(
            id = "matte", name = "消色", category = PresetCategory.Style,
            params = EditParams(saturation = -0.5f, contrast = 0.05f, clarity = 0.1f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "bw", intensity = 0.3f))
        ),
        Preset(
            id = "portrait_daily", name = "日常人像", category = PresetCategory.Portrait,
            params = EditParams(exposureEv = 0.1f, contrast = -0.05f, saturation = 0.05f, temperature = 0.05f),
            retouch = RetouchState(
                neutralGray = NeutralGrayParams(strength = 0.3f, radiusNorm = 0.015f),
                beauty = BeautyParams(
                    slimFace = 0.1f,
                    eyeEnlarge = 0.2f,
                    bodyParts = BodyPartBeautyParams(faceSkin = 0.4f, eyeEnlarge = 0.2f),
                    switches = RetouchSwitches(enableFaceSkin = true, enableEyes = true)
                ),
                colorTransfer = ColorTransferParams(refId = "jp", intensity = 0.5f)
            )
        ),
        Preset(
            id = "portrait_wedding", name = "婚纱", category = PresetCategory.Portrait,
            params = EditParams(exposureEv = 0.2f, contrast = -0.1f, saturation = 0.15f, temperature = 0.1f, highlights = 0.1f),
            retouch = RetouchState(
                neutralGray = NeutralGrayParams(strength = 0.5f, radiusNorm = 0.025f),
                beauty = BeautyParams(
                    slimFace = 0.15f,
                    eyeEnlarge = 0.4f,
                    skinSmoothingStrength = 0.6f,
                    underEyeBrighten = 0.3f,
                    lipColor = 0.2f,
                    bodyParts = BodyPartBeautyParams(
                        faceSkin = 0.6f,
                        eyeEnlarge = 0.4f,
                        lipPlump = 0.3f,
                        bodySkin = 0.4f
                    ),
                    switches = RetouchSwitches(
                        enableFaceSkin = true,
                        enableEyes = true,
                        enableLips = true,
                        enableBodySkin = true
                    )
                ),
                colorTransfer = ColorTransferParams(refId = "portra", intensity = 0.6f)
            )
        ),
        Preset(
            id = "portrait_street", name = "街拍", category = PresetCategory.Portrait,
            params = EditParams(contrast = 0.1f, saturation = 0.05f, temperature = -0.05f, clarity = 0.1f),
            retouch = RetouchState(
                neutralGray = NeutralGrayParams(strength = 0.2f, radiusNorm = 0.01f),
                beauty = BeautyParams(
                    slimFace = 0.05f,
                    eyeEnlarge = 0.15f,
                    bodyParts = BodyPartBeautyParams(faceSkin = 0.3f, eyeEnlarge = 0.15f),
                    switches = RetouchSwitches(enableFaceSkin = true, enableEyes = true)
                ),
                colorTransfer = ColorTransferParams(refId = "retro", intensity = 0.4f)
            )
        ),
        Preset(
            id = "portrait_selfie", name = "自拍", category = PresetCategory.Portrait,
            params = EditParams(exposureEv = 0.15f, contrast = -0.05f, saturation = 0.1f, temperature = 0.08f, vibrance = 0.15f),
            retouch = RetouchState(
                neutralGray = NeutralGrayParams(strength = 0.35f, radiusNorm = 0.02f),
                beauty = BeautyParams(
                    slimFace = 0.15f,
                    eyeEnlarge = 0.35f,
                    skinSmoothingStrength = 0.5f,
                    teethWhiten = 0.3f,
                    underEyeBrighten = 0.2f,
                    bodyParts = BodyPartBeautyParams(
                        faceSkin = 0.5f,
                        eyeEnlarge = 0.35f,
                        eyeDarkCircle = 0.3f
                    ),
                    switches = RetouchSwitches(enableFaceSkin = true, enableEyes = true, enableLips = true)
                ),
                colorTransfer = ColorTransferParams(refId = "jp", intensity = 0.55f)
            )
        ),
        Preset(
            id = "portrait_glam", name = "美妆", category = PresetCategory.Portrait,
            params = EditParams(exposureEv = 0.1f, contrast = 0.05f, saturation = 0.15f, temperature = 0.05f, highlights = 0.05f),
            retouch = RetouchState(
                neutralGray = NeutralGrayParams(strength = 0.45f, radiusNorm = 0.02f),
                beauty = BeautyParams(
                    slimFace = 0.1f,
                    eyeEnlarge = 0.45f,
                    skinSmoothingStrength = 0.55f,
                    skinDetail = 0.6f,
                    teethWhiten = 0.4f,
                    browEnhance = 0.3f,
                    eyelashEnhance = 0.3f,
                    lipColor = 0.35f,
                    underEyeBrighten = 0.25f,
                    bodyParts = BodyPartBeautyParams(
                        faceSkin = 0.55f,
                        eyeEnlarge = 0.45f,
                        lipPlump = 0.25f,
                        lipBrighten = 0.2f,
                        handBrighten = 0.1f,
                        handDetail = 0.1f
                    ),
                    switches = RetouchSwitches(
                        enableFaceSkin = true,
                        enableEyes = true,
                        enableLips = true,
                        enableHands = true
                    )
                ),
                colorTransfer = ColorTransferParams(refId = "portra", intensity = 0.5f)
            )
        ),
        Preset(
            id = "portrait_natural", name = "自然", category = PresetCategory.Portrait,
            params = EditParams(exposureEv = 0.05f, saturation = 0.02f, temperature = 0.02f),
            retouch = RetouchState(
                neutralGray = NeutralGrayParams(strength = 0.15f, radiusNorm = 0.01f),
                beauty = BeautyParams(
                    slimFace = 0.05f,
                    eyeEnlarge = 0.1f,
                    bodyParts = BodyPartBeautyParams(faceSkin = 0.2f, eyeEnlarge = 0.1f),
                    switches = RetouchSwitches(enableFaceSkin = true, enableEyes = true)
                )
            )
        ),
        Preset(
            id = "portrait_dramatic", name = "戏剧人像", category = PresetCategory.Portrait,
            params = EditParams(contrast = 0.3f, saturation = -0.1f, temperature = -0.05f, highlights = -0.1f, shadows = 0.1f, clarity = 0.2f),
            retouch = RetouchState(
                neutralGray = NeutralGrayParams(strength = 0.3f, radiusNorm = 0.015f),
                beauty = BeautyParams(
                    slimFace = 0.1f,
                    eyeEnlarge = 0.25f,
                    skinSmoothingStrength = 0.4f,
                    bodyParts = BodyPartBeautyParams(faceSkin = 0.4f, eyeEnlarge = 0.25f, bodySkin = 0.3f),
                    switches = RetouchSwitches(enableFaceSkin = true, enableEyes = true, enableBodySkin = true)
                ),
                colorTransfer = ColorTransferParams(refId = "retro", intensity = 0.5f)
            )
        ),
        Preset(
            id = "portrait_full", name = "全身精修", category = PresetCategory.Portrait,
            params = EditParams(exposureEv = 0.1f, contrast = -0.05f, saturation = 0.08f, temperature = 0.06f),
            retouch = RetouchState(
                neutralGray = NeutralGrayParams(strength = 0.4f, radiusNorm = 0.02f),
                beauty = BeautyParams(
                    slimFace = 0.15f,
                    eyeEnlarge = 0.25f,
                    skinSmoothingStrength = 0.55f,
                    neckRefine = 0.3f,
                    shoulderRefine = 0.3f,
                    waistSlender = 0.25f,
                    bodyParts = BodyPartBeautyParams(
                        faceSkin = 0.5f,
                        eyeEnlarge = 0.25f,
                        bodySkin = 0.5f,
                        legSkin = 0.45f,
                        legLength = 0.3f,
                        handBrighten = 0.25f,
                        handDetail = 0.2f
                    ),
                    switches = RetouchSwitches(
                        enableFaceSkin = true,
                        enableEyes = true,
                        enableBodySkin = true,
                        enableLegs = true,
                        enableHands = true
                    )
                ),
                colorTransfer = ColorTransferParams(refId = "portra", intensity = 0.5f)
            )
        ),
        Preset(
            id = "landscape_sunrise", name = "日出", category = PresetCategory.Landscape,
            params = EditParams(exposureEv = 0.3f, contrast = 0.1f, saturation = 0.15f, temperature = 0.2f, highlights = -0.1f, shadows = 0.15f, vibrance = 0.1f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "warm", intensity = 0.4f))
        ),
        Preset(
            id = "landscape_sunset", name = "日落", category = PresetCategory.Landscape,
            params = EditParams(exposureEv = 0.1f, contrast = 0.15f, saturation = 0.2f, temperature = 0.25f, highlights = -0.15f, shadows = 0.1f, vibrance = 0.15f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "warm", intensity = 0.5f))
        ),
        Preset(
            id = "landscape_snow", name = "雪景", category = PresetCategory.Landscape,
            params = EditParams(exposureEv = 0.4f, contrast = -0.1f, saturation = -0.05f, temperature = 0.1f, highlights = 0.2f, blues = 0.1f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "cool", intensity = 0.3f))
        ),
        Preset(
            id = "landscape_night", name = "夜景", category = PresetCategory.Landscape,
            params = EditParams(exposureEv = -0.3f, contrast = 0.2f, saturation = 0.05f, temperature = -0.1f, highlights = -0.2f, shadows = 0.3f, clarity = 0.1f),
            retouch = RetouchState()
        ),
        Preset(
            id = "landscape_foliage", name = "秋色", category = PresetCategory.Landscape,
            params = EditParams(contrast = 0.1f, saturation = 0.25f, temperature = 0.15f, highlights = -0.1f, shadows = 0.1f, vibrance = 0.2f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "fuji", intensity = 0.4f))
        ),
        Preset(
            id = "landscape_coast", name = "海景", category = PresetCategory.Landscape,
            params = EditParams(exposureEv = 0.15f, contrast = 0.05f, saturation = 0.1f, temperature = -0.1f, highlights = -0.1f, shadows = 0.05f, blues = 0.15f, vibrance = 0.1f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "cool", intensity = 0.4f))
        ),
        Preset(
            id = "landscape_mountain", name = "山景", category = PresetCategory.Landscape,
            params = EditParams(exposureEv = 0.1f, contrast = 0.15f, saturation = 0.05f, temperature = 0.05f, highlights = -0.1f, shadows = 0.15f, clarity = 0.15f),
            retouch = RetouchState()
        ),
        Preset(
            id = "landscape_forest", name = "森林", category = PresetCategory.Landscape,
            params = EditParams(exposureEv = 0.05f, contrast = 0.1f, saturation = 0.2f, temperature = -0.1f, highlights = -0.15f, shadows = 0.1f, vibrance = 0.15f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "fuji", intensity = 0.35f))
        ),
        Preset(
            id = "landscape_city", name = "城市", category = PresetCategory.Landscape,
            params = EditParams(exposureEv = 0.1f, contrast = 0.1f, saturation = 0.05f, temperature = 0.05f, highlights = -0.1f, shadows = 0.1f, clarity = 0.1f),
            retouch = RetouchState()
        ),
        Preset(
            id = "landscape_blue", name = "蓝调", category = PresetCategory.Landscape,
            params = EditParams(temperature = -0.2f, tint = 0.1f, saturation = -0.1f, contrast = 0.05f, highlights = -0.1f, shadows = 0.1f, vibrance = 0.05f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "cool", intensity = 0.5f))
        ),
        Preset(
            id = "landscape_golden", name = "金色", category = PresetCategory.Landscape,
            params = EditParams(exposureEv = 0.2f, contrast = 0.1f, saturation = 0.15f, temperature = 0.2f, highlights = -0.1f, shadows = 0.1f, vibrance = 0.1f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "warm", intensity = 0.45f))
        ),
        Preset(
            id = "landscape_misty", name = "雾气", category = PresetCategory.Landscape,
            params = EditParams(exposureEv = 0.25f, contrast = -0.15f, saturation = -0.1f, temperature = 0.05f, highlights = 0.15f, shadows = -0.05f, dehaze = -0.3f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "morandi", intensity = 0.4f))
        ),
        Preset(
            id = "food_warm", name = "美食暖调", category = PresetCategory.Food,
            params = EditParams(temperature = 0.2f, saturation = 0.15f, contrast = 0.05f, highlights = -0.1f, shadows = 0.1f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "warm", intensity = 0.5f))
        ),
        Preset(
            id = "food_fresh", name = "清新", category = PresetCategory.Food,
            params = EditParams(exposureEv = 0.15f, contrast = -0.05f, saturation = 0.1f, temperature = -0.05f, highlights = 0.1f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "cool", intensity = 0.3f))
        ),
        Preset(
            id = "food_appetite", name = "食欲", category = PresetCategory.Food,
            params = EditParams(contrast = 0.15f, saturation = 0.2f, temperature = 0.1f, highlights = -0.1f, shadows = 0.15f, vibrance = 0.15f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "portra", intensity = 0.3f))
        ),
        Preset(
            id = "food_dessert", name = "甜品", category = PresetCategory.Food,
            params = EditParams(exposureEv = 0.2f, contrast = -0.05f, saturation = 0.1f, temperature = 0.15f, highlights = 0.1f, shadows = -0.05f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "warm", intensity = 0.4f))
        ),
        Preset(
            id = "food_street", name = "街头小吃", category = PresetCategory.Food,
            params = EditParams(contrast = 0.1f, saturation = 0.1f, temperature = 0.05f, highlights = -0.05f, shadows = 0.1f, clarity = 0.1f),
            retouch = RetouchState(colorTransfer = ColorTransferParams(refId = "retro", intensity = 0.35f))
        ),
        Preset(
            id = "bw_high", name = "高调黑白", category = PresetCategory.Bw,
            params = EditParams(saturation = 0f, lutId = "bw", lutIntensity = 1f, exposureEv = 0.3f, contrast = -0.1f, highlights = 0.2f),
            retouch = RetouchState()
        ),
        Preset(
            id = "bw_low", name = "低调黑白", category = PresetCategory.Bw,
            params = EditParams(saturation = 0f, lutId = "bw", lutIntensity = 1f, exposureEv = -0.2f, contrast = 0.15f, shadows = 0.2f),
            retouch = RetouchState()
        ),
        Preset(
            id = "bw_film", name = "胶片黑白", category = PresetCategory.Bw,
            params = EditParams(saturation = 0f, lutId = "bw", lutIntensity = 1f, contrast = 0.1f, grainAmount = 0.3f, grainSize = 0.5f),
            retouch = RetouchState()
        ),
        Preset(
            id = "bw_portrait", name = "人像黑白", category = PresetCategory.Bw,
            params = EditParams(saturation = 0f, lutId = "bw", lutIntensity = 1f, contrast = 0.05f, highlights = -0.1f, shadows = 0.1f, clarity = 0.1f),
            retouch = RetouchState(
                neutralGray = NeutralGrayParams(strength = 0.3f, radiusNorm = 0.015f),
                beauty = BeautyParams(
                    slimFace = 0.1f,
                    eyeEnlarge = 0.2f,
                    bodyParts = BodyPartBeautyParams(faceSkin = 0.4f),
                    switches = RetouchSwitches(enableFaceSkin = true)
                )
            )
        ),
        Preset(
            id = "bw_drama", name = "戏剧黑白", category = PresetCategory.Bw,
            params = EditParams(saturation = 0f, lutId = "bw", lutIntensity = 1f, contrast = 0.3f, clarity = 0.2f, vignetteAmount = 0.3f),
            retouch = RetouchState()
        )
    )

    /** 按 id 查预设；找不到返回 null（UI 用 "none" 兜底）。 */
    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }

    /** 按分类过滤。 */
    fun byCategory(cat: PresetCategory): List<Preset> = ALL.filter { it.category == cat }
}
