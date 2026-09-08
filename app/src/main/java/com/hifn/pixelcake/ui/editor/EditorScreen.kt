package com.hifn.pixelcake.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.core.edit.EditParams
import kotlin.math.round

/**
 * 编辑界面（P1a 最小可用链路）。
 * 所有状态由上层 AppRoot 持有，这里只负责呈现与回调：滑块拖动 -> onParamChange(params.copy(...))。
 */
@Composable
fun EditorScreen(
    original: Bitmap,
    rendered: Bitmap?,
    renderVersion: Int,
    params: EditParams,
    canUndo: Boolean,
    canRedo: Boolean,
    status: String,
    onParamChange: (EditParams) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit
) {
    var showOriginal by remember { mutableStateOf(false) }
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
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            Modifier.fillMaxWidth().height(300.dp).padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (display != null) {
                Image(
                    bitmap = display,
                    contentDescription = "编辑预览",
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                showOriginal = true
                                awaitRelease()
                                showOriginal = false
                            }
                        )
                    }
                )
            } else {
                Text("渲染中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            if (showOriginal) "（查看原图）" else "（按住图片查看原图）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            AdjustSlider("曝光", params.exposureEv, -2f, 2f, 0.1f) { onParamChange(params.copy(exposureEv = it)) }
            AdjustSlider("对比度", params.contrast, -1f, 1f, 0.05f) { onParamChange(params.copy(contrast = it)) }
            AdjustSlider("饱和度", params.saturation, -1f, 1f, 0.05f) { onParamChange(params.copy(saturation = it)) }
            AdjustSlider("色温", params.temperature, -1f, 1f, 0.05f) { onParamChange(params.copy(temperature = it)) }
            AdjustSlider("色调", params.tint, -1f, 1f, 0.05f) { onParamChange(params.copy(tint = it)) }
            AdjustSlider("阴影", params.shadows, -1f, 1f, 0.05f) { onParamChange(params.copy(shadows = it)) }
            AdjustSlider("高光", params.highlights, -1f, 1f, 0.05f) { onParamChange(params.copy(highlights = it)) }
            AdjustSlider("LUT 强度", params.lutIntensity, 0f, 1f, 0.05f) { onParamChange(params.copy(lutIntensity = it)) }
            LutSelector(params.lutId) { onParamChange(params.copy(lutId = it)) }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onExport, Modifier.fillMaxWidth()) { Text("导出到相册") }
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
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("%.2f".format(value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = { v -> onValueChange(round(v / step) * step) },
            valueRange = min..max
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
