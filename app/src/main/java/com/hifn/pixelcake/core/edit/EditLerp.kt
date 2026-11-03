package com.hifn.pixelcake.core.edit

import kotlin.math.roundToInt

/**
 * 参数栈的「强度」插值（UI 3.0 §F 强度环）。
 *
 * ## 语义
 *
 * `amount = 1` → 原样；`amount = 0` → 逐像素零效果；中间值 = 每个**强度类**参数
 * 向中性等比回退。只回退「强度」字段（0 = 无效果的量），**保留「形状」字段**
 * （暗角羽化 / 锐化半径 / 降噪细节等）—— 与 Lightroom 预设强度同口径：
 * 用户拧的是「多重」，不是「换个形状」。
 *
 * ## RetouchState 的范围
 *
 * 只回退**已接线**的强度字段（磨皮 / 瘦脸 / 大眼 / 追色强度）——
 * 死字段（teethWhiten 等）不参与（§9 纪律 6：不接线的不假装生效）。
 */

/** 需要向中性回退的 [EditParams] 强度字段逐个列出；形状字段（半径/细节/羽化等）不动。 */
fun EditParams.lerped(amount: Float): EditParams {
    if (amount >= 1f) return this
    fun f(v: Float): Float = v * amount
    fun pts(cur: List<Pair<Int, Int>>): List<Pair<Int, Int>> =
        if (cur.isEmpty()) cur
        else cur.map { (x, y) ->
            val dy = ToneCurve.IDENTITY.firstOrNull { it.first == x }?.second ?: y
            x to (dy + (y - dy) * amount).roundToInt().coerceIn(0, 255)
        }
    return copy(
        exposureEv = f(exposureEv),
        contrast = f(contrast),
        blacks = f(blacks),
        shadows = f(shadows),
        highlights = f(highlights),
        whites = f(whites),
        highlightRecovery = f(highlightRecovery),
        temperature = f(temperature),
        tint = f(tint),
        vibrance = f(vibrance),
        saturation = f(saturation),
        dehaze = f(dehaze),
        hsl = HslMix(
            hue = hsl.hue.map { it * amount },
            sat = hsl.sat.map { it * amount },
            lum = hsl.lum.map { it * amount },
        ),
        grading = grading.copy(
            shadows = grading.shadows.copy(sat = grading.shadows.sat * amount, lum = grading.shadows.lum * amount),
            midtones = grading.midtones.copy(sat = grading.midtones.sat * amount, lum = grading.midtones.lum * amount),
            highlights = grading.highlights.copy(sat = grading.highlights.sat * amount, lum = grading.highlights.lum * amount),
            global = grading.global.copy(sat = grading.global.sat * amount, lum = grading.global.lum * amount),
        ),
        lumaPoints = pts(lumaPoints),
        redPoints = pts(redPoints),
        greenPoints = pts(greenPoints),
        bluePoints = pts(bluePoints),
        // 中性 = 0（不是默认 0.8）：amount=0 ⇒ LUT 整段跳过（PixelProgram 的 lutOp 判据）
        lutIntensity = f(lutIntensity),
        vignetteAmount = f(vignetteAmount),
        grainAmount = f(grainAmount),
        sharpenAmount = f(sharpenAmount),
        nrLuminance = f(nrLuminance),
        nrColor = f(nrColor),
        clarity = f(clarity),
        texture = f(texture),
    )
}

/** 人像精修层的强度插值：只回退**已接线**的强度字段（§9 纪律 6），半径 / 阈值 / 蒙版不动。 */
fun RetouchState.lerped(amount: Float): RetouchState {
    if (amount >= 1f) return this
    fun f(v: Float): Float = v * amount
    return copy(
        neutralGray = neutralGray.copy(strength = f(neutralGray.strength)),
        beauty = beauty.copy(
            slimFace = f(beauty.slimFace),
            slimJaw = f(beauty.slimJaw),
            eyeEnlarge = f(beauty.eyeEnlarge),
            bodyParts = beauty.bodyParts.copy(eyeEnlarge = f(beauty.bodyParts.eyeEnlarge)),
        ),
        colorTransfer = colorTransfer.copy(intensity = f(colorTransfer.intensity)),
    )
}
