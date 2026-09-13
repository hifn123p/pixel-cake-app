package com.hifn.pixelcake.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing

/**
 * 横向滚动的 chip 行（`docs/UI_DESIGN.md` §4.3 的二级工具条）。
 *
 * 预设、追色风格、工具选择都是「一组互斥小选项」，形态完全一致，所以只做一个控件。
 *
 * 两个刻意的决定：
 * - **横向滚动而不是换行**：换行会让面板高度随内容跳变（选 10 套预设时面板突然长高一大截），
 *   参数面板高度跳变是「廉价感」的常见来源。横向滚动高度恒定。
 * - **圆角统一走 [Radius.chip]**：Material 默认给的 8dp 不在本项目的圆角阶梯上，
 *   混进来就破了「只用四档圆角」的纪律。
 *
 * @param items    选项
 * @param selected 当前选中项；传 null 表示「无选中」（例如未选任何预设）
 */
@Composable
fun <T> GlassChipRow(
    items: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        items.forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                enabled = enabled,
                shape = Radius.chip,
                label = { Text(label(item)) }
            )
        }
    }
}
