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
 * ## 为什么是这 7 个、且刻意不叫「工具」
 *
 * 顺序即**使用频次**：人像（本 App 的主场景）→ 调色 → 颜色 → 曲线 → 细节 → 效果 → 预设。
 * 用户最常做的是「磨皮 + 美型」，所以它排第一而不是藏在二级菜单里。
 *
 * 「画布工具」（皮肤画笔 / 祛瑕）**不作为分类**，而是 人像 分类下的子模式 ——
 * 它们改变的是「点图片会发生什么」，属于人像工作流的一部分；单独提升为一级分类会让人
 * 误以为它是一个独立功能。
 *
 * ## ⚠️ 2026-09-23：`Lut` 被 `Color` 取代（`docs/TONING_DESIGN.md` §6.2）
 *
 * 批次 1 / 2 把可调项从 10 项扩到 64 项，若仍让「调色」一个分类装下 20 多个滑块，
 * 就正好落回 [ParamPanel] KDoc 批判过的「仪表盘式界面」。重排的判据是**概念层级**：
 * LUT 与 HSL 混色、彩色分级一样都是「给画面着色」，单独占一个一级分类是层级错位，
 * 于是 LUT 降为「颜色」分类下的二级 chip。
 *
 * ## ⚠️ 2026-09-23：批次 3 加入 `Effect`（第 6 个）
 *
 * 暗角 / 颗粒是新的一「批」参数，且它们**不是调色**：调色改的是「这个像素什么颜色」，
 * 效果改的是「画面哪里亮、哪里带噪点」。层级上它属于**收尾**（做完颜色最后加质感），
 * 所以排在「曲线」之后、「预设」之前 —— `预设` 留在最右端当稳定的肌肉记忆锚点。
 *
 * ## ⚠️ 2026-09-23：批次 4 加入 `Detail`（第 7 个）
 *
 * 细节（降噪 / 清晰度 / 纹理 / 锐化）同样属于**收尾**，排在 `效果` **之前**：先定锐度与噪点
 * （决定「画面有多少信息」），再加暗角与颗粒（决定「画面上加什么质感」）—— 反过来做，
 * 颗粒会被降噪当成噪声压掉一部分，同一组参数的手感会随拖动顺序漂移。
 * `预设` 仍然留在最右端。
 *
 * ## ⚠️ 一级分类的宽度上限：**7**（并更正上一轮偏保守的「6」）
 *
 * 判据（写清楚，附推导，免得下次再估一遍）：一行 `labelMedium` 的双汉字标签约 **24dp**，
 * 两侧各留 ≥8dp 呼吸 ⇒ **每项至少 40dp**。工具条的 `contentPadding` 是 0，
 * 条内 `padding(Spacing.xs)` 每侧 4dp，最窄的常见屏宽 320dp 下可用宽 = **312dp**
 * ⇒ `312 / 7 = 44.6dp` 够用，`312 / 8 = 39dp` 不够 ⇒ **上限 7**。
 *
 * 上一轮把上限写成 6 是估计偏保守（当时按「每项约 56dp」倒推），判据本身没变、数字算错了。
 * ⇒ **加到第 8 个才必须让工具条横向可滚**；在此之前请用上面这套推导复核，不要凭「看着挤」下结论。
 */
enum class EditorCategory(val label: String) {
    Portrait("人像"),
    Tone("调色"),
    Color("颜色"),
    Curve("曲线"),
    Detail("细节"),
    Effect("效果"),
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
