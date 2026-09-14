package com.hifn.pixelcake.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hifn.pixelcake.core.edit.EditParams
import com.hifn.pixelcake.core.edit.RetouchState
import com.hifn.pixelcake.core.edit.ToneCurve
import com.hifn.pixelcake.core.edit.preset.Preset
import com.hifn.pixelcake.ui.components.GlassCard
import com.hifn.pixelcake.ui.components.GlassChipRow
import com.hifn.pixelcake.ui.components.ParamSlider
import com.hifn.pixelcake.ui.components.PresetThumbRow
import com.hifn.pixelcake.ui.theme.Spacing

/** 皮肤画笔 / 祛瑕 的子模式 id。与上层 `retouchTool` 的字符串约定一致。 */
private const val TOOL_NONE = "none"
private const val TOOL_SKIN = "skin"
private const val TOOL_BLEMISH = "blemish"

/** 追色风格可选项。 */
private val COLOR_TRANSFER_OPTIONS = listOf(
    "none" to "无",
    "portra" to "波特拉",
    "fuji" to "富士",
    "retro" to "复古",
    "morandi" to "莫兰迪",
    "jp" to "日系"
)

/** LUT 可选项。 */
private val LUT_OPTIONS = listOf(
    "none" to "无",
    "warm" to "暖调",
    "cool" to "冷调",
    "bw" to "黑白",
    "film" to "胶片"
)

/**
 * 参数面板（`docs/UI_DESIGN.md` §4.3 三级工具条的第 3 级）。
 *
 * ## 设计决定：只显示当前分类的参数
 *
 * 旧版把**全部**参数塞进一条长滚动条（约 30 个滑块），用户想调「色温」要先滚过 20 个
 * 磨皮/液化的滑块 —— 这是「仪表盘式」界面最典型的失败：把复杂度原样丢给用户排序。
 * 改成按 [category] 分组后，任意时刻屏幕上只有 3~7 个滑块，且位置固定（同一个滑块永远
 * 出现在面板的同一个高度），肌肉记忆才能建立。
 *
 * ## 曲线 / LUT 不做成 Sheet
 *
 * 它们**本身就是一级分类的内容**，再弹一层 Sheet 等于同一个概念套两层壳。
 * 小到面板能装下就放在面板里 —— 判据是「内容量」而不是「概念上够不够独立」。
 *
 * @param onDraggingChange 任一滑块开始/结束拖动。上层据此隐藏非参数 UI（隐形式交互）。
 */
@Composable
fun ParamPanel(
    category: EditorCategory,
    params: EditParams,
    retouch: RetouchState,
    retouchTool: String,
    brushRadius: Float,
    inpaintRadius: Float,
    inpaintCount: Int,
    autoMaskEnabled: Boolean,
    presets: List<Preset>,
    activePresetId: String,
    // 预设 id → 缩略图（由上层按**原图**渲染一次，见 `MainActivity.buildPresetThumbs`）。缺失即占位。
    presetThumbs: Map<String, Bitmap> = emptyMap(),
    onParamChange: (EditParams) -> Unit,
    onParamCommit: () -> Unit,
    onRetouchChange: (RetouchState) -> Unit,
    onRetouchCommit: () -> Unit,
    onToolChange: (String) -> Unit,
    onBrushRadiusChange: (Float) -> Unit,
    onInpaintRadiusChange: (Float) -> Unit,
    onClearMask: () -> Unit,
    onClearInpaint: () -> Unit,
    onAutoMaskChange: (Boolean) -> Unit,
    onPreset: (Preset) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier) {
        when (category) {
            EditorCategory.Portrait -> PortraitParams(
                retouch, retouchTool, brushRadius, inpaintRadius, inpaintCount,
                autoMaskEnabled, onRetouchChange, onRetouchCommit, onToolChange,
                onBrushRadiusChange, onInpaintRadiusChange, onClearMask, onClearInpaint,
                onAutoMaskChange, onDraggingChange
            )

            EditorCategory.Tone -> ToneParams(params, onParamChange, onParamCommit, onDraggingChange)
            EditorCategory.Curve -> CurveParams(params, onParamChange, onParamCommit, onDraggingChange)
            EditorCategory.Lut -> LutParams(params, onParamChange, onParamCommit, onDraggingChange)
            EditorCategory.Preset -> PresetParams(presets, activePresetId, presetThumbs, onPreset)
        }
    }
}

// ———————————————————————————————————————————————————————————————
// 各分类内容
// ———————————————————————————————————————————————————————————————

@Composable
private fun PortraitParams(
    retouch: RetouchState,
    tool: String,
    brushRadius: Float,
    inpaintRadius: Float,
    inpaintCount: Int,
    autoMaskEnabled: Boolean,
    onRetouchChange: (RetouchState) -> Unit,
    onRetouchCommit: () -> Unit,
    onToolChange: (String) -> Unit,
    onBrushRadiusChange: (Float) -> Unit,
    onInpaintRadiusChange: (Float) -> Unit,
    onClearMask: () -> Unit,
    onClearInpaint: () -> Unit,
    onAutoMaskChange: (Boolean) -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    // 作用域开关放最前：它决定「下面这些滑块作用在哪」，是前置语义而不是某个参数
    GroupLabel("作用域")
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("自动蒙版（AI 皮肤识别）", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (autoMaskEnabled) {
                    "磨皮/美型只作用于识别到的皮肤区，画笔涂抹可补正"
                } else {
                    "已关闭：作用于整幅或画笔涂抹区"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = autoMaskEnabled, onCheckedChange = onAutoMaskChange)
    }

    // 画布工具：改变「点图片会发生什么」，因此是一级参数之前的选择
    Spacer(Modifier.height(Spacing.m))
    GroupLabel("画布工具")
    GlassChipRow(
        items = listOf(TOOL_NONE, TOOL_SKIN, TOOL_BLEMISH),
        selected = tool,
        label = { id -> when (id) { TOOL_SKIN -> "皮肤画笔"; TOOL_BLEMISH -> "祛瑕"; else -> "关闭" } },
        onSelect = onToolChange
    )

    when (tool) {
        TOOL_SKIN -> {
            GroupLabel("磨皮")
            ParamSlider(
                label = "磨皮强度", value = retouch.neutralGray.strength,
                valueRange = 0f..1f, step = 0.05f,
                onValueChange = {
                    onRetouchChange(retouch.copy(neutralGray = retouch.neutralGray.copy(strength = it)))
                },
                onValueChangeFinished = onRetouchCommit,
                onDraggingChange = onDraggingChange
            )
            ParamSlider(
                label = "磨皮半径", value = retouch.neutralGray.radiusNorm,
                valueRange = 0.002f..0.05f, step = 0.002f, format = { "%.3f".format(it) },
                onValueChange = {
                    onRetouchChange(retouch.copy(neutralGray = retouch.neutralGray.copy(radiusNorm = it)))
                },
                onValueChangeFinished = onRetouchCommit,
                onDraggingChange = onDraggingChange
            )
            ParamSlider(
                label = "笔刷大小", value = brushRadius,
                valueRange = 0.005f..0.15f, step = 0.005f, format = { "%.3f".format(it) },
                onValueChange = onBrushRadiusChange,
                onValueChangeFinished = {},
                onDraggingChange = onDraggingChange
            )
            ClearButton("清除皮肤蒙版", onClearMask)
        }

        TOOL_BLEMISH -> {
            GroupLabel("祛瑕")
            ParamSlider(
                label = "瑕疵点半径", value = inpaintRadius,
                valueRange = 0.002f..0.04f, step = 0.002f, format = { "%.3f".format(it) },
                onValueChange = onInpaintRadiusChange,
                onValueChangeFinished = {},
                onDraggingChange = onDraggingChange
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已标记瑕疵点", style = MaterialTheme.typography.bodyMedium)
                Text(
                    inpaintCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ClearButton("清除瑕疵点", onClearInpaint)
        }
    }

    GroupLabel("美型")
    ParamSlider(
        label = "瘦脸", value = retouch.beauty.slimFace, valueRange = 0f..1f, step = 0.05f,
        onValueChange = { onRetouchChange(retouch.copy(beauty = retouch.beauty.copy(slimFace = it))) },
        onValueChangeFinished = onRetouchCommit, onDraggingChange = onDraggingChange
    )
    ParamSlider(
        label = "收下颌", value = retouch.beauty.slimJaw, valueRange = 0f..1f, step = 0.05f,
        onValueChange = { onRetouchChange(retouch.copy(beauty = retouch.beauty.copy(slimJaw = it))) },
        onValueChangeFinished = onRetouchCommit, onDraggingChange = onDraggingChange
    )
    ParamSlider(
        label = "大眼", value = retouch.beauty.eyeEnlarge, valueRange = 0f..1f, step = 0.05f,
        onValueChange = { onRetouchChange(retouch.copy(beauty = retouch.beauty.copy(eyeEnlarge = it))) },
        onValueChangeFinished = onRetouchCommit, onDraggingChange = onDraggingChange
    )

    GroupLabel("追色")
    GlassChipRow(
        items = COLOR_TRANSFER_OPTIONS,
        selected = COLOR_TRANSFER_OPTIONS.firstOrNull { it.first == retouch.colorTransfer.refId },
        label = { it.second },
        onSelect = { (id, _) ->
            onRetouchChange(retouch.copy(colorTransfer = retouch.colorTransfer.copy(refId = id)))
            onRetouchCommit()
        }
    )
    if (retouch.colorTransfer.refId != "none") {
        ParamSlider(
            label = "追色强度", value = retouch.colorTransfer.intensity,
            valueRange = 0f..1f, step = 0.05f,
            onValueChange = {
                onRetouchChange(retouch.copy(colorTransfer = retouch.colorTransfer.copy(intensity = it)))
            },
            onValueChangeFinished = onRetouchCommit, onDraggingChange = onDraggingChange
        )
    }
}

@Composable
private fun ToneParams(
    params: EditParams,
    onParamChange: (EditParams) -> Unit,
    onCommit: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    // 顺序遵循「先曝光后色彩、先整体后局部」的暗房习惯：曝光 → 对比 → 影调 → 色彩。
    // 随机排序会让用户在滑块之间来回找，这是调色界面最容易踩的坑。
    GroupLabel("影调")
    ToneSlider("曝光", params.exposureEv, -2f, 2f, 0.1f, { p, v -> p.copy(exposureEv = v) }, params, onParamChange, onCommit, onDraggingChange)
    ToneSlider("对比度", params.contrast, -1f, 1f, 0.05f, { p, v -> p.copy(contrast = v) }, params, onParamChange, onCommit, onDraggingChange)
    ToneSlider("阴影", params.shadows, -1f, 1f, 0.05f, { p, v -> p.copy(shadows = v) }, params, onParamChange, onCommit, onDraggingChange)
    ToneSlider("高光", params.highlights, -1f, 1f, 0.05f, { p, v -> p.copy(highlights = v) }, params, onParamChange, onCommit, onDraggingChange)

    GroupLabel("色彩")
    ToneSlider("色温", params.temperature, -1f, 1f, 0.05f, { p, v -> p.copy(temperature = v) }, params, onParamChange, onCommit, onDraggingChange)
    ToneSlider("色调", params.tint, -1f, 1f, 0.05f, { p, v -> p.copy(tint = v) }, params, onParamChange, onCommit, onDraggingChange)
    ToneSlider("饱和度", params.saturation, -1f, 1f, 0.05f, { p, v -> p.copy(saturation = v) }, params, onParamChange, onCommit, onDraggingChange)
}

@Composable
private fun CurveParams(
    params: EditParams,
    onParamChange: (EditParams) -> Unit,
    onCommit: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    val points = params.lumaPoints
    val black = ToneCurve.black(points)
    val mid = ToneCurve.mid(points)
    val white = ToneCurve.white(points)
    val asInt: (Float) -> String = { it.toInt().toString() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GroupLabel("亮度曲线")
        if (!ToneCurve.isIdentity(points)) {
            TextButton(onClick = {
                onParamChange(params.copy(lumaPoints = ToneCurve.IDENTITY))
                onCommit()
            }) { Text("重置曲线") }
        }
    }
    ParamSlider(
        label = "黑场", value = black.toFloat(),
        valueRange = ToneCurve.BLACK_RANGE.start..ToneCurve.BLACK_RANGE.endInclusive,
        step = 1f, format = asInt,
        onValueChange = {
            onParamChange(params.copy(lumaPoints = ToneCurve.points(black = it.toInt(), mid = mid, white = white)))
        },
        onValueChangeFinished = onCommit, onDraggingChange = onDraggingChange
    )
    ParamSlider(
        label = "中间调", value = mid.toFloat(),
        valueRange = ToneCurve.MID_RANGE.start..ToneCurve.MID_RANGE.endInclusive,
        step = 1f, format = asInt,
        onValueChange = {
            onParamChange(params.copy(lumaPoints = ToneCurve.points(black = black, mid = it.toInt(), white = white)))
        },
        onValueChangeFinished = onCommit, onDraggingChange = onDraggingChange
    )
    ParamSlider(
        label = "白场", value = white.toFloat(),
        valueRange = ToneCurve.WHITE_RANGE.start..ToneCurve.WHITE_RANGE.endInclusive,
        step = 1f, format = asInt,
        onValueChange = {
            onParamChange(params.copy(lumaPoints = ToneCurve.points(black = black, mid = mid, white = it.toInt())))
        },
        onValueChangeFinished = onCommit, onDraggingChange = onDraggingChange
    )
    Text(
        "曲线用三个锚点表达（黑场 / 中间调 / 白场）。锚点模型分辨率无关，与预设一一对应。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.s)
    )
}

@Composable
private fun LutParams(
    params: EditParams,
    onParamChange: (EditParams) -> Unit,
    onCommit: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    GroupLabel("滤镜")
    GlassChipRow(
        items = LUT_OPTIONS,
        selected = LUT_OPTIONS.firstOrNull { it.first == params.lutId },
        label = { it.second },
        onSelect = { (id, _) ->
            onParamChange(params.copy(lutId = id))
            onCommit()
        }
    )
    ParamSlider(
        label = "LUT 强度", value = params.lutIntensity, valueRange = 0f..1f, step = 0.05f,
        onValueChange = { onParamChange(params.copy(lutIntensity = it)) },
        onValueChangeFinished = onCommit, onDraggingChange = onDraggingChange
    )
    Text(
        "内置 LUT 均为 MIT / CC 授权，可随包分发。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.s)
    )
}

/**
 * 预设（`docs/UI_DESIGN.md` §1.5 的 **A 档**：卡片带**真实缩略图**，不再是纯文字 chip）。
 *
 * 缩略图由上层按**原图**渲染（每个预设一张，只在换图时算一次）—— 所以这里的职责只是「怎么摆」，
 * 不含任何渲染逻辑。`presetThumbs` 缺失时 [PresetThumbRow] 会显示占位底色，不会崩也不会空白。
 */
@Composable
private fun PresetParams(
    presets: List<Preset>,
    activePresetId: String,
    presetThumbs: Map<String, Bitmap>,
    onPreset: (Preset) -> Unit
) {
    GroupLabel("预设（参数栈）")
    PresetThumbRow(
        items = presets,
        selected = presets.firstOrNull { it.id == activePresetId },
        label = { it.name },
        thumb = { presetThumbs[it.id] },
        onSelect = onPreset
    )
    Text(
        if (activePresetId == "none") {
            "缩略图按**当前照片**渲染，所以每个预设的效果是所见即所得。" +
                "选一个作为起点，再在其它分类里微调；手动改动任一参数后高亮会取消。"
        } else {
            "已套用预设；手动改动任一参数即视为「已偏离预设」，高亮取消。"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.s)
    )
}

// ———————————————————————————————————————————————————————————————
// 小工具
// ———————————————————————————————————————————————————————————————

/**
 * 调色分类的滑块。把「(EditParams) -> EditParams」的写法收敛到一处，
 * 避免七个滑块各写一遍 `params.copy(...)` 而在某一行写错字段。
 *
 * [apply] 收 `(EditParams, Float) -> EditParams`：`EditParams` 是**不可变** data class，
 * 只能靠 `copy(...)` 产出新值 —— 写成 `{ params.exposureEv = it }` 是编译不过的。
 */
@Composable
private fun ToneSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    apply: (EditParams, Float) -> EditParams,
    params: EditParams,
    onParamChange: (EditParams) -> Unit,
    onCommit: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    ParamSlider(
        label = label,
        value = value,
        valueRange = min..max,
        step = step,
        onValueChange = { onParamChange(apply(params, it)) },
        onValueChangeFinished = onCommit,
        onDraggingChange = onDraggingChange
    )
}

/** 面板内的分组小标题。层次靠字重 + 颜色，不靠加字号（字号全 App 封顶 5 级）。 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs)
    )
}

/** 面板内的次要动作用文字按钮，不用实心按钮 —— 实心按钮会把视线从预览图抢走。 */
@Composable
private fun ClearButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}
