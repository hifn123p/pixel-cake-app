package com.hifn.pixelcake.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.core.decode.ExportFormat
import com.hifn.pixelcake.diag.DebugLog
import com.hifn.pixelcake.ui.components.GlassCard
import com.hifn.pixelcake.ui.components.SectionHeader
import com.hifn.pixelcake.ui.theme.Ok
import com.hifn.pixelcake.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置页（`docs/UI_DESIGN.md` §3.2.1）。
 *
 * ## 这个页面刻意做得很薄
 *
 * 判据：**每次编辑都要用的，不该进设置；一年才改一次的，不该占编辑页。**
 * 所以这里只有「默认值、系统级开关、存储、关于」四类，**绝不放**任何调色参数、预设内容、LUT 管理。
 *
 * 页面内分组一律用 [GlassCard] 承载（**默认实心容器材质**，§1.6：承载型容器不用玻璃），
 * 卡片之间靠留白分隔 —— 不画粗边框、不画分割线，只留 1px 极淡描边兜底分割。
 *
 * @param exportFormat 默认导出格式（由上层持有，编辑器导出处复用同一取值，避免两处不一致）
 * @param autoMaskEnabled 自动蒙版默认开关（同上，与编辑器的开关是同一个状态）
 * @param lowTransparency 「降低透明度」开关。**必须由上层持有**：它通过
 *   `LocalLowTransparency`（见 `ui/theme/Glass.kt`）影响全 App 的玻璃材质，
 *   放在本页内部 state 里的话，切走再回来就复位、且无法影响其它页面。
 */
@Composable
fun SettingsScreen(
    exportFormat: ExportFormat,
    onExportFormatChange: (ExportFormat) -> Unit,
    autoMaskEnabled: Boolean,
    onAutoMaskChange: (Boolean) -> Unit,
    lowTransparency: Boolean,
    onLowTransparencyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showAbout by remember { mutableStateOf(false) }
    var logExported by remember { mutableStateOf(false) }
    var cacheMiB by remember { mutableStateOf<Long?>(null) }

    // 目录遍历放 IO 线程：缓存目录可能有上千个条目，绝不能在主线程 walk。
    LaunchedEffect(Unit) {
        cacheMiB = withContext(Dispatchers.IO) { context.cacheDir.dirSizeBytes() / (1024L * 1024L) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.page,
            end = Spacing.page,
            top = Spacing.xxl,
            bottom = Spacing.xxxl
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)
    ) {
        item {
            Column(modifier = Modifier.padding(bottom = Spacing.m)) {
                Text("设置", style = MaterialTheme.typography.displaySmall)
                Text(
                    "默认值与系统级开关。调色参数都在编辑器里，不在这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }

        item {
            GlassCard {
                SectionHeader(
                    title = "导出默认值",
                    subtitle = "决定编辑器里「导出」的初始格式"
                )
                Spacer(Modifier.height(Spacing.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    listOf(ExportFormat.JPEG to "JPEG", ExportFormat.PNG to "PNG").forEach { (fmt, name) ->
                        FilterChip(
                            selected = exportFormat == fmt,
                            onClick = { onExportFormatChange(fmt) },
                            label = { Text(name) }
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    if (exportFormat == ExportFormat.PNG) {
                        "PNG 无损，体积大；需要二次后期时选它。"
                    } else {
                        "JPEG 体积小，适合直接发出去。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            GlassCard {
                SectionHeader(
                    title = "AI 与性能",
                    subtitle = "端侧推理，照片不出本机"
                )
                Spacer(Modifier.height(Spacing.m))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("默认开启自动蒙版", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "磨皮与液化只作用于 AI 识别出的皮肤区；画笔涂抹可补正",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = autoMaskEnabled, onCheckedChange = onAutoMaskChange)
                }
                Spacer(Modifier.height(Spacing.s))
                Text(
                    "推理优先走 GPU，失败自动降级到 CPU。加速器状态在编辑器里实时显示。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            GlassCard {
                SectionHeader(title = "外观", subtitle = "编辑页始终使用深色工作台")
                Spacer(Modifier.height(Spacing.m))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("降低透明度", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "毛玻璃换成实心底，提升文字可读性",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = lowTransparency, onCheckedChange = onLowTransparencyChange)
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    // Android 没有 iOS 那种「降低透明度」系统开关（详见 docs/UI_DESIGN.md §6 的更正），
                    // 所以这里必须是应用内开关，不能指望跟随系统。
                    "注：Android 没有系统级的「降低透明度」开关，因此这是应用内设置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            GlassCard {
                SectionHeader(
                    title = "存储",
                    subtitle = "缓存用于 RAW 预览与导出中转"
                )
                Spacer(Modifier.height(Spacing.s))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        cacheMiB?.let { "占用 $it MiB" } ?: "统计中…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = {
                        context.cacheDir.clearChildren()
                        cacheMiB = 0L
                    }) { Text("清理缓存") }
                }
            }
        }

        item {
            GlassCard {
                SectionHeader(
                    title = "调试",
                    subtitle = "排查真机问题时导出给开发者"
                )
                Spacer(Modifier.height(Spacing.s))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (logExported) "已触发系统分享" else "就绪",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (logExported) Ok else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { logExported = DebugLog.export(context) }) {
                        Text("导出调试日志")
                    }
                }
            }
        }

        item {
            GlassCard {
                SectionHeader(title = "关于", subtitle = "版本、开源许可与隐私声明")
                Spacer(Modifier.height(Spacing.s))
                TextButton(onClick = { showAbout = true }) { Text("查看详情") }
            }
        }
    }

    if (showAbout) {
        AboutSheet(onDismiss = { showAbout = false })
    }
}

/** 递归统计目录占用（字节）。放在 IO 线程调用。 */
private fun File.dirSizeBytes(): Long =
    walkBottomUp().filter { it.isFile }.sumOf { it.length() }

/** 清空目录内容但保留目录本身。 */
private fun File.clearChildren() {
    listFiles()?.forEach { child ->
        runCatching { child.deleteRecursively() }
    }
}
