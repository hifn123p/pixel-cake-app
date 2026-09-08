package com.hifn.pixelcake.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.hifn.pixelcake.ui.theme.Bad
import com.hifn.pixelcake.ui.theme.Ok
import com.hifn.pixelcake.ui.theme.Warn
import com.hifn.pixelcake.diag.DebugLog

/**
 * 第 1 步的落地页。
 *
 * 不是 Hello World —— 而是设备能力实测面板。
 * 把它装到一加 15 上，可以直接验证技术方案中的三条核心假设：
 *   1. 屏幕是否真的支持广色域（决定 Display P3 管线是否成立）
 *   2. HDR 能力（决定 Ultra HDR 输出是否可用）
 *   3. 可用内存 vs fp16 三缓冲占用（决定能否全分辨率实时处理）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val caps = remember { context.probeCapabilities() }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
                SectionCard("权限") {
                    InfoRow(
                        "读取照片",
                        if (granted) "已授权" else "未授权",
                        if (granted) Ok else Warn
                    )
                    if (!granted) {
                        Button(
                            onClick = { launcher.launch(Manifest.permission.READ_MEDIA_IMAGES) },
                            modifier = Modifier.padding(top = 12.dp)
                        ) {
                            Text("授权读取照片")
                        }
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
                    RoadmapStep(1, "工程骨架 + CI", done = true)
                    RoadmapStep(2, "NDK + LibRaw（A7C2 ARW 解码）", done = false)
                    RoadmapStep(3, "fp16 渲染管线 + EGL P3", done = false)
                    RoadmapStep(4, "基础调色 GPU 化", done = false)
                    RoadmapStep(5, "广色域输出（Ultra HDR / 16bit TIFF）", done = false)
                    RoadmapStep(6, "3D LUT 滤镜与局部调整", done = false)
                    RoadmapStep(7, "磨皮与液化", done = false)
                    RoadmapStep(8, "AI 接入（MediaPipe + 自托管模型）", done = false)
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
