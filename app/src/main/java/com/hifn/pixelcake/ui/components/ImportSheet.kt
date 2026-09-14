package com.hifn.pixelcake.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing

/**
 * 导入方式的唯一入口（由首页右上角「＋」唤起，`docs/UI_DESIGN.md` §3.2）。
 *
 * ## 为什么把三个入口从首页收进这里
 *
 * 旧版首页把「从相册选择 / 打开 ARW / 相机直连」三张卡片平铺在首屏 —— 首页于是变成了一张
 * **表单**：三个并列的按钮谁都不比谁重要，用户每打开一次 App 都要重新在三个等价选项里做决定。
 * 收进 Sheet 之后：首页回到「展示」的角色，而「怎么开始」只在**用户真的要开始时**问一次。
 *
 * ## 顺序即推荐度
 *
 * 相册（日常，最高频）→ ARW（本 App 的核心能力）→ 相机（需要插线，最低频）。
 * 把最高频的放最上面，而不是按「功能重要性」排。
 *
 * 三项都用 [ActionTile]（整块可点 + 按下缩放），不用 Material `Button`：
 * 并列的实心按钮会把强调色铺满整屏（`§2.4` 验收清单），是「廉价感」的典型来源。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    onPickPhoto: () -> Unit,
    onPickArw: () -> Unit,
    onConnectCamera: () -> Unit,
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
                .padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text("开始", style = MaterialTheme.typography.displaySmall)
            Text(
                "照片全程留在本机，不上传、不联网。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.s))

            ActionTile(
                title = "从相册选择",
                subtitle = "JPEG / HEIF · 8-bit sRGB 管线",
                onClick = onPickPhoto,
                trailing = "选择"
            )
            ActionTileDivider()

            ActionTile(
                title = "打开 RAW 文件",
                subtitle = "ARW · 16-bit 线性管线，预览与导出一致",
                onClick = onPickArw,
                accent = true,
                trailing = "打开"
            )
            ActionTileDivider()

            ActionTile(
                title = "连接相机",
                subtitle = "USB 直连 A7C2，浏览卡内照片并批量套预设",
                onClick = onConnectCamera,
                trailing = "连接"
            )
        }
    }
}
