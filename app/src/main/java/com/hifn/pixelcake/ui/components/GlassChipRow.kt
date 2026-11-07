package com.hifn.pixelcake.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing

/**
 * chip 组选项（`docs/UI_DESIGN.md` §4.3 的二级工具条）。
 *
 * 预设、追色风格、工具选择、二级分组、色相通道都是「一组互斥小选项」，形态完全一致，
 * 所以只做一个控件。
 *
 * - **默认按可用宽度换行**（`FlowRow`）：常用选项无需横向寻找；高度随内容增加，
 *   但面板本身可纵向滚动，不再让一排选项被截断。
 * - **圆角统一走 [Radius.chip]**：Material 默认给的 8dp 不在本项目的圆角阶梯上，
 *   混进来就破了「只用四档圆角」的纪律。
 *
 * ## [singleRow]：钉死单行、横向滚动（2026-10-30 新增）
 *
 * 换行在**面板里**是有代价的：这些 chip 行钉在滚动之外（见 `ParamPanel`），
 * 行数一多就**直接吃掉参数内容的高度**。真机实测：作用域行 9 项排成 3 排占约 190dp、
 * 二级行 3~5 项排成 2 排占约 100dp，两者合计 290dp 常驻 —— 6.7 寸屏的参数区只有约 555dp，
 * 扣掉它们和卡片内边距，**装直方图加两个滑块就满了**，用户必须先缩小预览才能调参。
 *
 * 所以面板里的 chip 行改为**单行 + 横向滚动**：高度恒定一行，任何项数都不再侵占内容区。
 * 代价是要横滑才能看到末尾几项；对「9 个作用域」「5 个细节分组」这类低频决策，
 * 横滑比「每次调参都要先缩小预览」便宜得多。半露的那个 chip 本身就是可滑的视觉提示。
 *
 * ## [compact]：收紧内边距（2026-10-30 新增）
 *
 * `FilterChip` 自带左右内边距，而 label 槽里又包了一层 [Spacing.m] —— 两者叠加后
 * 每个双字 chip 约 97dp，一行只放得下 3 个。面板里把它们收到 [Spacing.s]，
 * 同样宽度能多放 1~2 个，减少横滑距离。**卡片类调用点不要开**：那里留白是呼吸感。
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
 * @param enabled  整行统一开关
 * @param itemEnabled 逐项开关（批次 5 新增）。与 [enabled] 是**与**关系，默认 `null` = 全部可点。
 *   存在的理由：作用域 chip 行里「整图」永远可用，而 8 个对象作用域在模型不可用时必须逐个禁用 ——
 *   用整行的 [enabled] 会把「整图」也一起灰掉，那就等于告诉用户「这个功能坏了」。
 *   **禁用态是必须的**：若让用户点进一个不会生效的作用域，他调半天的滑块一个像素都不会变，
 *   而界面上没有任何东西解释为什么。
 * @param singleRow 钉死单行并横向滚动（面板用）。默认 false = 按宽度换行。
 * @param compact   收紧 label 的左右内边距（面板用）。默认 false。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun <T> GlassChipRow(
    items: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    itemEnabled: ((T) -> Boolean)? = null,
    swatch: ((T) -> Color)? = null,
    singleRow: Boolean = false,
    compact: Boolean = false
) {
    // ⚠️ chip 的生成逻辑必须只有一份：`singleRow` 只换容器，不换内容。
    // 两处各写一遍 forEach 迟早会漂移（一边记得传 `itemEnabled`、另一边忘了）。
    val chipRow: @Composable () -> Unit = {
        items.forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                enabled = enabled && (itemEnabled?.invoke(item) ?: true),
                shape = Radius.chip,
                label = {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = if (compact) Spacing.s else Spacing.m
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val dot = swatch?.invoke(item)
                        if (dot != null) {
                            Box(modifier = Modifier.size(Spacing.s).background(dot, Radius.pill))
                            Spacer(Modifier.width(Spacing.xs))
                        }
                        // ⚠️ 必须显式给 labelMedium：`FilterChip` 内部会把 `LocalTextStyle`
                        // 置成 labelLarge（14sp），不写这一行就等于**默认 14sp** ——
                        // 比 Token 规定的 chip 字号（12sp）大一档，双字 chip 因此宽出约 8dp，
                        // 一行少放一个，间接把参数区的高度吃掉。这条也解释真机反馈的「字体偏大」。
                        Text(label(item), style = MaterialTheme.typography.labelMedium)
                    }
                }
            )
        }
    }

    if (singleRow) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            chipRow()
        }
    } else {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            chipRow()
        }
    }
}
