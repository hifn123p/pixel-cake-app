package com.hifn.pixelcake.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * [CameraPanel] 保持**零改动**：它自带的描边卡在 Sheet 里就是内容块，不再另加标题，
 * 避免出现两层标题。它的 token 迁移（旧圆角 / 旧间距）是独立待办。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSheet(
    longEdge: Int,
    onOpenLocalFile: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                .padding(bottom = Spacing.xxl)
        ) {
            Text(
                "关闭本面板会断开相机连接；批量导入过程中请保持本面板打开。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.s)
            )
            CameraPanel(longEdge = longEdge, onOpenLocalFile = onOpenLocalFile)
        }
    }
}
