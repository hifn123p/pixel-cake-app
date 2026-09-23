package com.hifn.pixelcake.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing

/**
 * 横向滚动的 chip 行（`docs/UI_DESIGN.md` §4.3 的二级工具条）。
 *
 * 预设、追色风格、工具选择、二级分组、色相通道都是「一组互斥小选项」，形态完全一致，
 * 所以只做一个控件。
 *
 * 两个刻意的决定：
 * - **横向滚动而不是换行**：换行会让面板高度随内容跳变（选 10 套预设时面板突然长高一大截），
 *   参数面板高度跳变是「廉价感」的常见来源。横向滚动高度恒定。
 * - **圆角统一走 [Radius.chip]**：Material 默认给的 8dp 不在本项目的圆角阶梯上，
 *   混进来就破了「只用四档圆角」的纪律。
 *
 * ## [swatch]：可选的色块槽（批次 2 新增）
 *
 * HSL 混色与彩色分级都要「用颜色本身当选项名」—— 让用户在「红 / 橙 / 黄」三个词里挑，
 * 不如直接把三个色块摆出来。色块画在 `label` 槽**内部**而不是走 `FilterChip` 的
 * `leadingIcon`：icon 槽的尺寸与左右间距由 Material 按 18dp 图标语义决定，塞一个 8dp 圆点
 * 会得到一圈看不见的留白；而 `label` 是我们自己的 Row，间距可以严格落回 [Spacing] 梯级。
 *
 * @param items    选项
 * @param selected 当前选中项；传 null 表示「无选中」（例如未选任何预设）
 * @param swatch   可选的色块取值函数；为 null 时就是纯文字 chip（绝大多数调用点如此）
 */
@Composable
fun <T> GlassChipRow(
    items: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    swatch: ((T) -> Color)? = null
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
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dot = swatch?.invoke(item)
                        if (dot != null) {
                            Box(modifier = Modifier.size(Spacing.s).background(dot, Radius.pill))
                            Spacer(Modifier.width(Spacing.xs))
                        }
                        Text(label(item))
                    }
                }
            )
        }
    }
}
