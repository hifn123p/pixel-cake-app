package com.hifn.pixelcake.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hifn.pixelcake.core.decode.ExportFormat
import com.hifn.pixelcake.ui.theme.Ok
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing

/**
 * 状态类别 —— **UI 必须按它呈现，绝不能拿文案字符串判断**（审计 M2）。
 *
 * 此前 `ExportSheet` 用 `status.startsWith("已导出")` 决定颜色：文案是给用户看的，
 * 一旦改一个字（「已导出」→「导出完成」）就会**静默改掉业务逻辑**，且不会有任何编译错误。
 */
enum class StatusKind { Info, Success, Error }

/**
 * 编辑器状态行的一条内容：类别 + 文案。
 *
 * 二者必须一起传递，避免出现「文案说成功、类别说失败」的不一致状态（分开两个参数就迟早会漏设一个）。
 * [text] 为空表示不显示状态行。
 */
data class EditorStatus(
    val kind: StatusKind = StatusKind.Info,
    val text: String = ""
) {
    val visible: Boolean get() = text.isNotEmpty()
}

/**
 * 导出面板（`docs/UI_DESIGN.md` §3.2）。
 *
 * ## 为什么是 Sheet 而不是常驻底栏
 *
 * 导出是**整场编辑只做一次的终局动作**。旧版把它（含格式选择 + 状态文案）常驻在编辑页底部，
 * 结果是「每次调参数都要先绕过一整块导出 UI」。收进 Sheet 后，编辑页底部只剩参数面板，
 * 导出从顶栏一个按钮进入 —— 一次性动作不该占用常驻空间。
 *
 * 导出期间 Sheet 保持展开，因为**进度与取消都需要一个停靠位**；关掉 Sheet 就不该再显示进度，
 * 所以进度文案留在 Sheet 里而不是浮在画面上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    exportFormat: ExportFormat,
    onExportFormatChange: (ExportFormat) -> Unit,
    exporting: Boolean,
    status: EditorStatus,
    onExport: () -> Unit,
    onCancelExport: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { if (!exporting) onDismiss() },
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
            verticalArrangement = Arrangement.spacedBy(Spacing.m)
        ) {
            Text("导出", style = MaterialTheme.typography.displaySmall)
            Text(
                "导出到系统相册 Pictures/PixelCake，无需任何权限。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                listOf(ExportFormat.JPEG to "JPEG", ExportFormat.PNG to "PNG").forEach { (fmt, name) ->
                    FilterChip(
                        selected = exportFormat == fmt,
                        onClick = { onExportFormatChange(fmt) },
                        enabled = !exporting,
                        shape = Radius.chip,
                        label = { Text(name) }
                    )
                }
            }
            Text(
                if (exportFormat == ExportFormat.PNG) {
                    "PNG 无损、体积大，适合还要继续后期的照片。"
                } else {
                    "JPEG 质量 92，体积小，适合直接发出去。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                // 源图分辨率 ≠ 渲染分辨率（见 memory 里的硬约定）：
                // RAW 走「边解码边分带渲染」的全分辨率路径，非 RAW 则退到代理分辨率。
                "RAW 按全分辨率导出（边解码边分带渲染，不把 196MB 线性图搬进堆）；" +
                    "非 RAW 走代理分辨率。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (status.visible) {
                Text(
                    status.text,
                    style = MaterialTheme.typography.bodyMedium,
                    // 按**结构化类别**取色，而不是按文案前缀猜（审计 M2）。
                    // 顺带修掉一个实际体验问题：此前失败/取消与普通提示同色（灰色），
                    // 导出失败在视觉上几乎无法与「正在生成导出…」区分。
                    color = when (status.kind) {
                        StatusKind.Success -> Ok
                        StatusKind.Error -> MaterialTheme.colorScheme.error
                        StatusKind.Info -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(Modifier.height(Spacing.xs))

            if (exporting) {
                OutlinedButton(onClick = onCancelExport, modifier = Modifier.fillMaxWidth()) {
                    Text("取消导出")
                }
            } else {
                Button(
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth().height(Spacing.controlHeight)
                ) {
                    Text(if (exportFormat == ExportFormat.PNG) "导出到相册（PNG）" else "导出到相册（JPEG）")
                }
            }
        }
    }
}
