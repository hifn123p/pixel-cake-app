package com.hifn.pixelcake.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hifn.pixelcake.ui.components.GlassCard
import com.hifn.pixelcake.ui.components.SectionHeader
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing

/**
 * 关于（`docs/UI_DESIGN.md` §3.2）。
 *
 * **刻意做成 Sheet 而不是独立页面**：内容量不足以占满一屏，独立成页只会多一跳。
 * 但它**必须有** —— `NOTICE` 里列了 LibRaw 等弱 copyleft 组件的静态链接义务，
 * 以及两个 MediaPipe 模型的 Apache-2.0 署名要求，法律上需要一个用户可达的展示位。
 *
 * 版本号走 `PackageManager` 读取，**不用 `BuildConfig`** ——
 * AGP 8 起 `buildConfig` 默认关闭，本项目并未开启，引用它编译不过。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val version = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "v${info.versionName} (${info.longVersionCode})"
        }.getOrNull() ?: "v?"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = Radius.sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.page)
                .padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)
        ) {
            Column {
                Text("像素蛋糕 PixelCake", style = MaterialTheme.typography.displaySmall)
                Text(
                    version,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "本地 RAW 调色 · 人像精修",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            GlassCard {
                SectionHeader(title = "隐私", subtitle = "为什么可以放心")
                Spacer(Modifier.height(Spacing.s))
                Text(
                    "全部处理在本机完成：不联网、不上传、不收集。照片只在你选定的目录里进出。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            GlassCard {
                SectionHeader(title = "开源组件", subtitle = "随包分发的第三方代码与模型")
                Spacer(Modifier.height(Spacing.m))
                LicenseRow("LibRaw", "LGPL-2.1 OR CDDL-1.0", "native 全量 RAW 解码（按 CDDL-1.0 分支使用）")
                LicenseRow("LiteRT", "Apache-2.0", "端侧模型运行时")
                LicenseRow(
                    "MediaPipe SelfieMulticlass 256×256",
                    "Apache-2.0",
                    "皮肤分割（自动蒙版）"
                )
                LicenseRow(
                    "MediaPipe BlazeFace Sparse (Full Range)",
                    "Apache-2.0",
                    "人脸检测（液化锚点）"
                )
                LicenseRow("内置 LUT / 预设", "MIT / CC", "调色与影调预设")
            }

            Text(
                "完整声明（含 SHA-256、下载源、relink 义务的履行方式）见仓库根目录的 NOTICE 文件。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 一条开源组件声明。
 *
 * 层次只用「字重 + 颜色深浅」表达，不放大字号 —— 字号全 App 封顶 5 级。
 */
@Composable
private fun LicenseRow(name: String, license: String, usage: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                usage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            license,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
