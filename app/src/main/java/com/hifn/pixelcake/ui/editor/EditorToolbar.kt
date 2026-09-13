package com.hifn.pixelcake.ui.editor

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.ui.components.GlassSegmentedBar
import com.hifn.pixelcake.ui.theme.Spacing

/**
 * 编辑器一级工具分类（`docs/UI_DESIGN.md` §4.3 三级工具条的第 1 级）。
 *
 * ## 为什么是这 5 个、且刻意不叫「工具」
 *
 * 顺序即**使用频次**：人像（本 App 的主场景）→ 调色 → 曲线 → LUT → 预设。
 * 用户最常做的是「磨皮 + 美型」，所以它排第一而不是藏在二级菜单里。
 *
 * 「画布工具」（皮肤画笔 / 祛瑕）**不作为分类**，而是 人像 分类下的子模式 ——
 * 它们改变的是「点图片会发生什么」，属于人像工作流的一部分；单独提升为一级分类会让人
 * 误以为它是一个独立功能。
 */
enum class EditorCategory(val label: String) {
    Portrait("人像"),
    Tone("调色"),
    Curve("曲线"),
    Lut("LUT"),
    Preset("预设")
}

/**
 * 编辑器一级工具条。
 *
 * 复用 [GlassSegmentedBar]（与底部 TabBar 同一控件）—— 两者视觉与行为完全一致，
 * 差别只在挂在哪一层。分开写两份必然出现「改了一处忘了另一处」的漂移。
 *
 * 高度刻意压到 44dp（= [Spacing.controlHeight]）：这是悬浮在预览图上的浮层，
 * 每多 1dp 就少 1dp 给画面。同时 44dp 仍是可点面积的下限，不会变得难按。
 */
@Composable
fun EditorToolbar(
    current: EditorCategory,
    onSelect: (EditorCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassSegmentedBar(
        items = EditorCategory.entries,
        selected = current,
        label = { it.label },
        onSelect = onSelect,
        modifier = modifier,
        height = Spacing.controlHeight,
        contentPadding = PaddingValues(horizontal = 0.dp)
    )
}
