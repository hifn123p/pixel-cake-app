package com.hifn.pixelcake.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.core.edit.EditParams
import com.hifn.pixelcake.core.edit.RetouchState
import com.hifn.pixelcake.core.edit.preset.Preset
import kotlin.math.round

/**
 * 编辑界面（P1a 最小可用链路 + P1b 人像精修）。
 * 所有状态由上层 AppRoot 持有，这里只负责呈现与回调：滑块拖动 -> onParamChange(...)/onRetouchChange(...)，
 * 松手/选择类动作 -> onParamCommit()/onRetouchCommit() 入撤销栈。
 *
 * 预览图支持三种指针交互（由 [retouchTool] 决定）：
 *  - "none"   ：按住图片查看原图（原图对比）；
 *  - "skin"   ：拖动涂抹皮肤作用区（磨皮/液化蒙版），坐标归一化 [0..1] 经 onBrushStroke 上报；
 *  - "blemish"：点击脏点位置，追加祛瑕描迹，经 onInpaintStroke 上报。
 */
@Composable
fun EditorScreen(
    original: Bitmap,
    rendered: Bitmap?,
    renderVersion: Int,
    params: EditParams,
    retouch: RetouchState,
    retouchTool: String,
    brushRadius: Float,
    inpaintRadius: Float,
    inpaintCount: Int,
    presets: List<Preset>,
    activePresetId: String,
    canUndo: Boolean,
    canRedo: Boolean,
    status: String,
    exporting: Boolean = false,
    onParamChange: (EditParams) -> Unit,
    onParamCommit: () -> Unit = {},
    onRetouchChange: (RetouchState) -> Unit,
    onRetouchCommit: () -> Unit = {},
    onToolChange: (String) -> Unit,
    onBrushStroke: (Float, Float) -> Unit,
    onBrushRadiusChange: (Float) -> Unit,
    onInpaintStroke: (Float, Float) -> Unit,
    onInpaintRadiusChange: (Float) -> Unit,
    onClearMask: () -> Unit,
    onClearInpaint: () -> Unit,
    onPreset: (Preset) -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
    onCancelExport: () -> Unit = {},
    onBack: () -> Unit
) {
    var showOriginal by remember { mutableStateOf(false) }
    // 预览图实测尺寸，用于把指针坐标归一化（与显示缩放/letterbox 解耦）。
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    // renderVersion 每次重渲自增，确保本可组合项重组并重绘当前(已被原位修改的)Bitmap。
    val display: ImageBitmap? = run {
        val _v = renderVersion
        if (showOriginal) original.asImageBitmap() else rendered?.asImageBitmap()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("编辑", style = MaterialTheme.typography.titleMedium)
            Row {
                TextButton(onClick = onUndo, enabled = canUndo) { Text("撤销") }
                TextButton(onClick = onRedo, enabled = canRedo) { Text("重做") }
                TextButton(onClick = onReset) { Text("重置") }
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            Modifier.fillMaxWidth().height(300.dp).padding(4.dp).onSizeChanged { previewSize = it },
            contentAlignment = Alignment.Center
        ) {
            if (display != null) {
                val modifier = when (retouchTool) {
                    "skin" -> Modifier.fillMaxSize().pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val sx = previewSize.width.toFloat().coerceAtLeast(1f)
                            val sy = previewSize.height.toFloat().coerceAtLeast(1f)
                            val nx = (change.position.x / sx).coerceIn(0f, 1f)
                            val ny = (change.position.y / sy).coerceIn(0f, 1f)
                            onBrushStroke(nx, ny)
                        }
                    }
                    "blemish" -> Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val sx = previewSize.width.toFloat().coerceAtLeast(1f)
                            val sy = previewSize.height.toFloat().coerceAtLeast(1f)
                            val nx = (offset.x / sx).coerceIn(0f, 1f)
                            val ny = (offset.y / sy).coerceIn(0f, 1f)
                            onInpaintStroke(nx, ny)
                        }
                    }
                    else -> Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                showOriginal = true
                                awaitRelease()
                                showOriginal = false
                            }
                        )
                    }
                }
                Image(bitmap = display, contentDescription = "编辑预览", modifier = modifier)
            } else {
                Text("渲染中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            when (retouchTool) {
                "skin" -> "（皮肤画笔：在图上拖动涂抹磨皮/液化作用区）"
                "blemish" -> "（祛瑕：点击脏点/瑕疵位置）"
                else -> "（按住图片查看原图）"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // ---- 人像精修（P1b-4 / P1b-6，全算子 UI + 预设）----
            Text("人像精修", style = MaterialTheme.typography.titleSmall)

            // 预设（参数栈，P1b-6）：高亮当前生效预设
            PresetRow(presets, activePresetId, onPreset)

            // 工具选择：关闭 / 皮肤 / 祛瑕
            Spacer(Modifier.height(8.dp))
            Text("工具", style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("none" to "关闭", "skin" to "皮肤", "blemish" to "祛瑕").forEach { (id, name) ->
                    FilterChip(
                        selected = retouchTool == id,
                        onClick = { onToolChange(id) },
                        label = { Text(name) }
                    )
                }
            }

            // 皮肤画笔作用区
            if (retouchTool == "skin") {
                AdjustSlider("磨皮强度", retouch.neutralGray.strength, 0f, 1f, 0.05f,
                    { onRetouchChange(retouch.copy(neutralGray = retouch.neutralGray.copy(strength = it))) }, onRetouchCommit)
                AdjustSlider("磨皮半径", retouch.neutralGray.radiusNorm, 0.002f, 0.05f, 0.002f,
                    { onRetouchChange(retouch.copy(neutralGray = retouch.neutralGray.copy(radiusNorm = it))) }, onRetouchCommit)
                AdjustSlider("笔刷大小", brushRadius, 0.005f, 0.15f, 0.005f, onBrushRadiusChange, {})
                Button(onClick = onClearMask, Modifier.fillMaxWidth()) { Text("清除皮肤蒙版") }
            }

            // 祛瑕（瑕疵点）
            if (retouchTool == "blemish") {
                AdjustSlider("瑕疵点半径", inpaintRadius, 0.002f, 0.04f, 0.002f, onInpaintRadiusChange, {})
                Text("已标记瑕疵点：$inpaintCount", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onClearInpaint, Modifier.fillMaxWidth()) { Text("清除瑕疵点") }
            }

            // 美型液化（始终可用；需先涂皮肤蒙版才有作用域）
            Spacer(Modifier.height(8.dp))
            Text("美型", style = MaterialTheme.typography.bodyMedium)
            AdjustSlider("瘦脸", retouch.beauty.slimFace, 0f, 1f, 0.05f,
                { onRetouchChange(retouch.copy(beauty = retouch.beauty.copy(slimFace = it))) }, onRetouchCommit)
            AdjustSlider("收下颌", retouch.beauty.slimJaw, 0f, 1f, 0.05f,
                { onRetouchChange(retouch.copy(beauty = retouch.beauty.copy(slimJaw = it))) }, onRetouchCommit)
            AdjustSlider("大眼", retouch.beauty.eyeEnlarge, 0f, 1f, 0.05f,
                { onRetouchChange(retouch.copy(beauty = retouch.beauty.copy(eyeEnlarge = it))) }, onRetouchCommit)

            // 追色（全局色彩风格）
            Spacer(Modifier.height(8.dp))
            ColorTransferRow(
                selected = retouch.colorTransfer.refId,
                intensity = retouch.colorTransfer.intensity,
                onSelect = { onRetouchChange(retouch.copy(colorTransfer = retouch.colorTransfer.copy(refId = it))) },
                onIntensity = { onRetouchChange(retouch.copy(colorTransfer = retouch.colorTransfer.copy(intensity = it))) },
                onCommit = onRetouchCommit
            )

            // ---- 基础调色（tonal，点态管线）----
            Spacer(Modifier.height(8.dp))
            Text("基础调色", style = MaterialTheme.typography.titleSmall)
            AdjustSlider("曝光", params.exposureEv, -2f, 2f, 0.1f, { onParamChange(params.copy(exposureEv = it)) }, onParamCommit)
            AdjustSlider("对比度", params.contrast, -1f, 1f, 0.05f, { onParamChange(params.copy(contrast = it)) }, onParamCommit)
            AdjustSlider("饱和度", params.saturation, -1f, 1f, 0.05f, { onParamChange(params.copy(saturation = it)) }, onParamCommit)
            AdjustSlider("色温", params.temperature, -1f, 1f, 0.05f, { onParamChange(params.copy(temperature = it)) }, onParamCommit)
            AdjustSlider("色调", params.tint, -1f, 1f, 0.05f, { onParamChange(params.copy(tint = it)) }, onParamCommit)
            AdjustSlider("阴影", params.shadows, -1f, 1f, 0.05f, { onParamChange(params.copy(shadows = it)) }, onParamCommit)
            AdjustSlider("高光", params.highlights, -1f, 1f, 0.05f, { onParamChange(params.copy(highlights = it)) }, onParamCommit)
            AdjustSlider("LUT 强度", params.lutIntensity, 0f, 1f, 0.05f, { onParamChange(params.copy(lutIntensity = it)) }, onParamCommit)
            LutSelector(params.lutId) {
                onParamChange(params.copy(lutId = it))
                onParamCommit()
            }
        }

        Spacer(Modifier.height(8.dp))
        if (exporting) {
            Button(onClick = onCancelExport, Modifier.fillMaxWidth()) { Text("取消导出") }
        } else {
            Button(onClick = onExport, Modifier.fillMaxWidth()) { Text("导出到相册") }
        }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AdjustSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("%.2f".format(value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = { v -> onValueChange(round(v / step) * step) },
            valueRange = min..max,
            // F08：只在松手时提交一次撤销点，否则一次拖动会往历史里塞几十条
            onValueChangeFinished = onValueChangeFinished
        )
    }
}

@Composable
private fun LutSelector(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "none" to "无", "warm" to "暖调", "cool" to "冷调", "bw" to "黑白", "film" to "胶片"
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("滤镜 (LUT)", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (id, name) ->
                FilterChip(
                    selected = selected == id,
                    onClick = { onSelect(id) },
                    label = { Text(name) }
                )
            }
        }
    }
}

@Composable
private fun PresetRow(presets: List<Preset>, selectedId: String, onPreset: (Preset) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("预设（参数栈）", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { p ->
                FilterChip(
                    selected = selectedId == p.id,
                    onClick = { onPreset(p) },
                    label = { Text(p.name) }
                )
            }
        }
    }
}

@Composable
private fun ColorTransferRow(
    selected: String,
    intensity: Float,
    onSelect: (String) -> Unit,
    onIntensity: (Float) -> Unit,
    onCommit: () -> Unit
) {
    val options = listOf(
        "none" to "无",
        "portra" to "波特拉",
        "fuji" to "富士",
        "retro" to "复古",
        "morandi" to "莫兰迪",
        "jp" to "日系"
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("追色风格", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (id, name) ->
                FilterChip(
                    selected = selected == id,
                    onClick = { onSelect(id); onCommit() },
                    label = { Text(name) }
                )
            }
        }
        if (selected != "none") {
            AdjustSlider("追色强度", intensity, 0f, 1f, 0.05f, onIntensity, onCommit)
        }
    }
}
