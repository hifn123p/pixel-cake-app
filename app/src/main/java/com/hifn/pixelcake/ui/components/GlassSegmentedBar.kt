package com.hifn.pixelcake.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.ui.theme.Motion
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing
import com.hifn.pixelcake.ui.theme.glassSurface
import com.hifn.pixelcake.ui.theme.rememberGlassTint
import com.hifn.pixelcake.ui.theme.segmentIndicator

/**
 * 分段玻璃条：底部 TabBar 与编辑器的一级工具条**共用同一个控件**。
 *
 * 两者视觉与行为完全一致（等宽格位 + 选中指示块滑动），差别只在外部修饰符
 * （TabBar 需要 `navigationBarsPadding` 并悬浮在底部）。做成一个泛型控件，
 * 避免同一处交互写两遍、改一处漏一处。
 *
 * 实现要点：各项**等宽**，所以指示块的位移直接用 `maxWidth / items.size × index`，
 * 不需要逐项测量 —— 这是这里唯一容易写复杂的地方，等宽可以完全绕开。
 *
 * 指示块位移是**空间属性** → [Motion.springSpatial]（阻尼 0.6，滑动到位时轻微回弹）。
 * 强调色取 `colorScheme.primary`（深色主题下是降饱和版本），不直接写 `Seed`。
 *
 * ## 指示块的材质走共享 modifier（UI-6）
 *
 * 原先这里与 `AppShell.GlassTabBar` 各写了一遍「强调色平色块 + 圆角」，
 * 属于审计 L3 记的两套近似实现 —— 现在统一走 [segmentIndicator]
 * （白渐变 + 顶部亮线 + 投影，浅色主题自动退回强调色淡染）。
 * 这样「选中态长什么样」全 App 只有一处定义。
 *
 * @param items    分段项
 * @param selected 当前项
 * @param label    取显示文案
 * @param height   条高（TabBar 56dp / 工具条 44dp）
 */
@Composable
fun <T> GlassSegmentedBar(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.page)
) {
    if (items.isEmpty()) return
    val tint = rememberGlassTint()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding)
            .height(height)
            .glassSurface(tint, Radius.pill)
            .padding(Spacing.xs)
    ) {
        val itemWidth = maxWidth / items.size
        val index = items.indexOf(selected).coerceAtLeast(0)
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * index,
            animationSpec = Motion.springSpatial(),
            label = "segIndicator"
        )
        val accent = MaterialTheme.colorScheme.primary

        // 先画指示块，文字压在其上
        Box(
            modifier = Modifier
                .offset(x = indicatorX)
                .width(itemWidth)
                .fillMaxHeight()
                .segmentIndicator(Radius.pill)
        )

        Row(modifier = Modifier.fillMaxSize()) {
            items.forEach { item ->
                val isSelected = item == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = isSelected, onClick = { onSelect(item) }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(item),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
