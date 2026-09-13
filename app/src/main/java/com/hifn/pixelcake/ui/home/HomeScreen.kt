package com.hifn.pixelcake.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.ui.components.ActionTile
import com.hifn.pixelcake.ui.components.ActionTileDivider
import com.hifn.pixelcake.ui.components.GlassCard
import com.hifn.pixelcake.ui.components.SectionHeader
import com.hifn.pixelcake.ui.theme.Bad
import com.hifn.pixelcake.ui.theme.Ok
import com.hifn.pixelcake.ui.theme.Spacing
import com.hifn.pixelcake.ui.theme.Warn
import java.io.File

/**
 * 调色台（原「首页」，`docs/UI_DESIGN.md` §3.2）。
 *
 * **空态即导入页** —— 这里刻意**不单独做一个导入页面**：导入没有任何需要独占屏幕的内容，
 * 独立成页只会把「首页 → 导入 → 编辑器」变成三跳。所以导入入口就直接长在调色台的顶部。
 *
 * 布局约定（整个 UI 改版都遵守）：
 * - 页面左右边距走 `Spacing.page`，卡片之间走 `Spacing.cardGap`；
 * - 卡片一律 [GlassCard]：**不画边框、不投阴影**，层次靠「半透明底 + 1px 高光描边 + 留白」；
 * - 调试日志入口已移到设置页（它不属于日常调色流程）。
 *
 * @param loading 正在解码（33MP ARW 的代理线性解码耗时可见，必须给反馈，避免"点了没反应"）
 * @param message 状态消息（解码失败提示 / 载入结果），为空则不显示
 * @param onOpenLocalFile 相机直连拉取的本地缓存文件 → 打开编辑器（P2）
 */
@Composable
fun HomeScreen(
    onImportPhoto: () -> Unit,
    onImportArw: () -> Unit,
    loading: Boolean = false,
    message: String = "",
    onOpenLocalFile: (File) -> Unit = {}
) {
    val context = LocalContext.current
    val caps = remember { context.probeCapabilities() }
    val profile = remember(caps) { caps.resolutionProfile() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.page,
            end = Spacing.page,
            top = Spacing.xxl,
            bottom = Spacing.xxxl
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)
    ) {
        item {
            Column(modifier = Modifier.padding(bottom = Spacing.m)) {
                Text("调色台", style = MaterialTheme.typography.displaySmall)
                Text(
                    "本地 RAW 调色 · 人像精修。全程端侧处理，照片不出本机。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }

        // ---- 空态：导入入口（整块可点的动作卡，拇指友好）----
        item {
            GlassCard {
                SectionHeader(
                    title = "开始",
                    subtitle = "选一张照片，或直接连相机拉图"
                )
                Spacer(Modifier.height(Spacing.s))
                ActionTile(
                    title = "从相册选择",
                    subtitle = "JPEG / HEIF，走 8-bit sRGB 管线",
                    onClick = onImportPhoto,
                    enabled = !loading,
                    trailing = "选择"
                )
                ActionTileDivider()
                ActionTile(
                    title = "打开 ARW 文件",
                    subtitle = "16-bit 线性 RAW，预览与导出一致",
                    onClick = onImportArw,
                    enabled = !loading,
                    accent = true,
                    trailing = "打开"
                )
                if (loading) {
                    Spacer(Modifier.height(Spacing.m))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            message.ifEmpty { "正在解码…" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (message.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.m))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---- P2：相机 USB 直连（检测 → 授权 → PTP 会话 → 列图 → 单张 / 批量套预设）----
        item {
            CameraPanel(
                longEdge = profile.fullResLongEdge,
                onOpenLocalFile = onOpenLocalFile
            )
        }

        item {
            GlassCard {
                SectionHeader(title = "设备与显示", subtitle = "能力实测，用于判断管线档位")
                Spacer(Modifier.height(Spacing.m))
                InfoRow("厂商 / 型号", "${caps.manufacturer} ${caps.model}")
                InfoRow("芯片平台", caps.soc)
                InfoRow("系统版本", "Android ${caps.androidVersion} (API ${caps.sdkInt})")
                InfoRow("主 ABI", caps.abi)
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
            GlassCard {
                SectionHeader(
                    title = "内存预算",
                    subtitle = "A7C2 33MP · RGBA16F 单张全幅占用"
                )
                Spacer(Modifier.height(Spacing.m))
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
            GlassCard {
                SectionHeader(title = "路线图", subtitle = "已完成与下一步")
                Spacer(Modifier.height(Spacing.m))
                RoadmapStep(1, "工程骨架 + CI", done = true)
                RoadmapStep(2, "NDK + LibRaw（A7C2 ARW 解码）", done = true)
                RoadmapStep(3, "16-bit 线性渲染管线（预览 / 导出同源）", done = true)
                RoadmapStep(4, "人像算子（磨皮 / 液化 / 祛瑕 / 追色）", done = true)
                RoadmapStep(5, "内置人像预设 10 套", done = true)
                RoadmapStep(6, "局部调整与画笔蒙版", done = true)
                RoadmapStep(7, "端侧 AI（皮肤分割自动蒙版 + 人脸检测液化锚点）", done = true)
                RoadmapStep(8, "A7C2 直连（USB PTP 拉图 + 批量套预设）", done = true)
                RoadmapStep(9, "UI 改版（本阶段）", done = false)
                RoadmapStep(10, "NAS 联动（P3）", done = false)
            }
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
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (done) "✓" else "·",
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
