package com.hifn.pixelcake.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import com.hifn.pixelcake.core.ml.MlFaceProvider
import com.hifn.pixelcake.core.ml.MlMaskProvider
import com.hifn.pixelcake.ui.components.GlassCard
import com.hifn.pixelcake.ui.components.SectionHeader
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing

/**
 * 端侧模型中心（UI 3.0 §N「模型中心」，v0.7.0）。
 *
 * 回答的是「AI 功能到底处于什么状态」这一类问题：分割 / 人脸两个模型有没有加载、
 * 跑在 GPU 还是 CPU、本会话有没有被 OOM 降级、概率缓存是否命中。数据全部来自
 * `MlMaskProvider` / `MlFaceProvider` 在 v2 时代就铺好的**只读诊断量**
 * （「让状态可见」正是这个页面存在的理由 —— 两个字段被改成 public 就能让
 * 自动蒙版静默停摆，而界面上没有任何东西会解释为什么）。
 *
 * **刻意做成 Sheet**（与 [AboutSheet] 同一模式）：内容量不足以占满一屏。
 * 刻意**不放**「预热 / 重新加载」按钮 —— 预热需要一张照片，入口在编辑器；
 * 诊断页只读不动，改错状态的入口比缺失状态展示更危险。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelCenterSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maskDiag = remember { MlMaskProvider.diagnostics() }
    val faceDiag = remember { MlFaceProvider.diagnostics() }

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
                Text("模型中心", style = MaterialTheme.typography.displaySmall)
                Text(
                    // 口径与实现一致：快照在**打开本页时**取一次（本页刻意无刷新按钮，
                    // 见类 KDoc「诊断页只读不动」）；本会话禁用模型后重开本页即可看到新状态。
                    "端侧推理的状态快照（只读）。每次打开本页时重新读取；关闭再打开即可看到最新状态。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            GlassCard {
                SectionHeader(title = "皮肤 / 对象分割", subtitle = "selfie_multiclass_256x256 · LiteRT")
                SpacerRows(maskDiag)
            }

            GlassCard {
                SectionHeader(title = "人脸检测", subtitle = "face_detection_full_range_sparse · LiteRT")
                SpacerRows(faceDiag)
            }

            Text(
                "模型随包内置（Apache-2.0，见关于页许可）。推理优先 GPU，失败自动降级 CPU；" +
                    "连续 OOM 达到阈值后本会话永久关闭（重启 App 恢复）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SpacerRows(diag: Map<String, String>) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        for ((k, v) in diag) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    k, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    v, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
