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
 * 分段玻璃条：编辑器的一级工具条（人像 / 调色 / 曲线 / 细节 / 效果 / 预设）用它绘制。
 *
 * 各项**等宽**，所以指示块的位移直接用 `maxWidth / items.size × index`，不需要逐项测量
 * —— 这是这里唯一容易写复杂的地方，等宽可以完全绕开。
 *
 * 指示块位移是**空间属性** → [Motion.springSpatial]（阻尼 0.6，滑动到位时轻微回弹）。
 * 强调色取 `colorScheme.primary`（深色主题下是降饱和版本），不直接写 `Seed`。
 *
 * ⚠️ **底部 TabBar（`AppShell.GlassTabBar`）是它的一份近似副本，两者并不共用。**
 * TabBar 版多一个 `navigationBarsPadding`、指示块多内缩 `Spacing.xs`、内容区没有纵向内边距；
 * 合并前要先统一这三处视觉差异，所以暂时**没有合**。后果是：
 * **改本文件的分段项时必须同步检查 `AppShell.kt`，反之亦然** —— 两处曾同时漏掉
 * `indication = null`（见下节「按压反馈」）。
 *
 * ## 指示块的材质走共享 modifier（UI-6）
 *
 * 原先这里与 `AppShell.GlassTabBar` 各写了一遍「强调色平色块 + 圆角」，
 * 属于审计 L3 记的两套近似实现 —— 现在统一走 [segmentIndicator]
 * （白渐变 + 顶部亮线 + 投影，浅色主题自动退回强调色淡染）。
 * 这样「选中态长什么样」全 App 只有一处定义。
 *
 * ## 按压反馈：**不画涟漪**（`indication = null`）
 *
 * 分段项必须走 `selectable(selected, interactionSource, indication, …)` 这个重载并显式传
 * `indication = null`。只写 `selectable(selected, onClick)` 会落到另一个重载
 * （`foundation:1.9.5` 的 `selection/Selectable.kt:140`，其实现体写死
 * `useLocalIndication = true`）⇒ 去 `LocalIndication` 取 M3 涟漪，于是：
 *
 * 1. 涟漪的**颜色** ⇒ 按下时整个格位变灰（不是本项目的强调色选中块）；
 * 2. 涟漪的**边界是矩形** ⇒ 与 `Radius.pill` 的胶囊冲突，两端露出直角；
 * 3. 相邻两格的矩形**共用一条边** ⇒ 读成「两个按钮中间有一条分割线」。
 *
 * **一条根因，三个现象。** 全 App 其余 6 处可点区域（`ActionTile` / `EditorScreen` ×2 /
 * `HomeScreen` / `GlassCircleButton` / `PresetThumbRow`）都显式传了 `indication = null`
 * （口径见 `docs/UI_DESIGN.md` 的「按下缩放」一行），被漏掉的只有这里与 `AppShell.GlassTabBar`。
 *
 * 这里**不加** `Modifier.pressScale`：分段项的可见内容只有一行文字，缩放它读起来是
 * 「文字抖了一下」，而承载选中态的指示块是它的**兄弟**节点、不会跟着缩，反而更怪。
 * 反馈由「指示块滑动 + 内容转场」承担，两者都是点击即生效。也正因为不需要观测按压，
 * `interactionSource` 直接传 `null` —— `indication == null` 时
 * `clickableWithIndicationIfNeeded` 走「no need for indication」快路径（`Clickable.kt:697`），
 * 既不会去 `composed`、也不会创建指示节点（因此那句 `interactionSource!!`
 * 永远不会被执行到），还省一次分配。
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
                        // ⚠️ 必须显式传 `indication = null`（理由见本文件 KDoc「按压反馈」）。
                        // 只写 `selectable(selected, onClick)` 会落到「取 LocalIndication」的重载，
                        // 在胶囊里画出方形灰底 —— 就是用户报的「灰底 + 直角 + 中缝」。
                        // `interactionSource = null` 见同一节说明（本项不做按下缩放）。
                        .selectable(
                            selected = isSelected,
                            interactionSource = null,
                            indication = null,
                            onClick = { onSelect(item) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(item),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.xs)  // 增加水平内边距，避免文字拥挤
                    )
                }
            }
        }
    }
}
