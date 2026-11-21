package com.hifn.pixelcake.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import com.hifn.pixelcake.ui.settings.AppSettings
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing
import java.io.File

/**
 * 相机直连 Sheet —— 给已有的 [CameraPanel] 一个**模态容器**。
 *
 * ## 为什么是 Sheet 而不是首页的常驻卡片
 *
 * 相机流程是「插线 → 握手 → 列图 → 拉图/批量」的一次性长流程，其间用户不该在首页与其它卡片
 * 之间来回看；模态容器把注意力锁在这件事上，也顺手让首页回到「展示」的形态。
 *
 * ## ⚠️ 关闭 Sheet 会断开相机连接（刻意）
 *
 * [CameraPanel] 内部用 `DisposableEffect` 兜底释放 USB，且批量任务是挂在它自己的
 * `rememberCoroutineScope()` 上的。所以：
 * - **离开本 Sheet = 释放 USB 会话**。这比「会话在后台悄悄挂着、相机端留半开状态」更安全；
 * - **批量导入过程中不要关闭本 Sheet**，否则任务会随作用域取消。
 *   下方的提示条就是在讲这两件事，别删。
 *
 * 注意：这与改动前的行为**量级相同**（旧版面板放在 `LazyColumn` 的 item 里，
 * 滑出屏幕同样会 dispose），只是从「不确定何时被回收」变成「一个明确的位置」。
 *
 * [CameraPanel] 保持**零逻辑改动**：§4.0.9 已完成它的视觉迁移（`OutlinedCard` →
 * `GlassCard` 实心容器 + 全量 `Spacing` token），在 Sheet 里就是标准内容块，
 * 不再另加标题，避免出现两层标题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSheet(
    longEdge: Int,
    settings: AppSettings,
    onOpenLocalFile: (File) -> Unit,
    onDismiss: () -> Unit
) {
    // v0.7.2（审计 C-F1）：批量/传输进行中**拦截一切关闭路径**（下拉 / 返回 / 点遮罩）。
    // 原版三路都能把 Sheet 收掉，挂在其作用域上的批量任务随之静默取消 ——
    // 用户无从知道第几张导出成功。KDoc 的「不要关」从提示文字升级为真实约束；
    // 「退出」途径只剩系统杀进程，与既有语义一致。
    var panelBusy by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !panelBusy }
    )

    BackHandler(enabled = panelBusy) { /* 忙时吞掉返回：等效于拦截下拉关闭 */ }

    ModalBottomSheet(
        onDismissRequest = { if (!panelBusy) onDismiss() },
        sheetState = sheetState,
        shape = Radius.sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page)
                .padding(bottom = Spacing.xxl)
        ) {
            Text(
                "关闭本面板会断开相机连接；批量进行中本面板被锁定（完成后可关闭）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.s)
            )
            CameraPanel(
                longEdge = longEdge,
                settings = settings,
                onOpenLocalFile = onOpenLocalFile,
                onBusyChange = { panelBusy = it }
            )
        }
    }
}
