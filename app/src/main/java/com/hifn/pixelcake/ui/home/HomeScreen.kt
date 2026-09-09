package com.hifn.pixelcake.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hifn.pixelcake.diag.DebugLog
import com.hifn.pixelcake.ui.theme.Bad
import com.hifn.pixelcake.ui.theme.Ok
import com.hifn.pixelcake.ui.theme.Warn

/**
 * 首页：设备能力实测面板 + 编辑链路入口。
 *
 * ARW 走「打开 ARW 文件」入口：内嵌 JPEG 只作秒开占位，
 * 真正的修图源是 LibRaw 解出的 16-bit 线性母版（P1b 已启用，不再是"仅预览"）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onImportPhoto: () -> Unit, onImportArw: () -> Unit) {
    val context = LocalContext.current
    val caps = remember { context.probeCapabilities() }

    var logExported by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PixelCake") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        "本地 RAW 调色 · 广色域管线",
                        style = MaterialTheme.typography.displaySmall
                    )
                    Text(
                        "第 1 步：工程骨架与 CI。以下为设备能力实测，用于验证技术方案假设。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            item {
                SectionCard("设备") {
                    InfoRow("厂商 / 型号", "${caps.manufacturer} ${caps.model}")
                    InfoRow("芯片平台", caps.soc)
                    InfoRow("系统版本", "Android ${caps.androidVersion} (API ${caps.sdkInt})")
                    InfoRow("主 ABI", caps.abi)
                }
            }

            item {
                SectionCard("显示与色彩") {
                    InfoRow(
                        "广色域 (P3)",
                        if (caps.wideColorGamut) "支持" else "不支持",
                        if (caps.wideColorGamut) Ok else Bad
                    )
                    InfoRow("HDR", caps.hdrTypes.joinToString(" / ").ifEmpty { "不支持" })
                    InfoRow("刷新率", "${caps.refreshRateHz.toInt()} Hz")
                }
            }

            item {
                SectionCard("内存预算（A7C2 33MP · RGBA16F）") {
                    InfoRow("单缓冲", "${caps.fp16SingleMiB} MiB")
                    InfoRow("三缓冲", "${caps.fp16TripleMiB} MiB")
                    InfoRow("当前可用", "${caps.availMemMiB} / ${caps.totalMemMiB} MiB")
                    InfoRow(
                        "全分辨率处理",
                        if (caps.fullResFeasible) "可行" else "内存不足，需降采样",
                        if (caps.fullResFeasible) Ok else Warn
                    )
                }
            }

            item {
                SectionCard("导入（编辑链路）") {
                    Button(
                        onClick = onImportPhoto,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Text("导入照片（JPEG / HEIF）")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onImportArw,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("打开 ARW 文件（16-bit 线性 RAW 修图）")
                    }
                }
            }

            item {
                SectionCard("调试日志") {
                    InfoRow(
                        "当前状态",
                        if (logExported) "已触发系统分享" else "就绪",
                        if (logExported) Ok else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { logExported = DebugLog.export(context) },
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text("导出调试日志")
                    }
                }
            }

            item {
                SectionCard("路线图") {
                    // 与 DEV_PLAN v3.0 的 P1a/P1b 对齐；旧的 8 步版（fp16 + EGL P3 /
                    // 16bit TIFF / MediaPipe）已被 v3.0 删除，这里是真机上看得到的信息（F16）
                    RoadmapStep(1, "工程骨架 + CI", done = true)
                    RoadmapStep(2, "NDK + LibRaw（A7C2 ARW 解码）", done = true)
                    RoadmapStep(3, "16-bit 线性渲染管线（预览/导出同源）", done = true)
                    RoadmapStep(4, "人像算子（磨皮 / 液化 / 祛瑕 / 追色）", done = false)
                    RoadmapStep(5, "内置人像预设 ~10 套", done = false)
                    RoadmapStep(6, "局部调整与蒙版", done = false)
                    RoadmapStep(7, "A7C2 直连（USB PTP 拉图）", done = false)
                    RoadmapStep(8, "AI 接入（TFLite + NNAPI）", done = false)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor
        )
    }
}

@Composable
private fun RoadmapStep(index: Int, title: String, done: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            if (done) "[x]" else "[ ]",
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) Ok else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "  $index. $title",
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
