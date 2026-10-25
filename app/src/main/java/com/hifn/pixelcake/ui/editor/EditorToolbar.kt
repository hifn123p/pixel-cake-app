package com.hifn.pixelcake.ui.editor

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.ui.components.GlassSegmentedBar
import com.hifn.pixelcake.ui.theme.Spacing

/**
 * 编辑器一级工具分类（`docs/UI_DESIGN.md` §4.3 三级工具条的第 1 级，§4.0.10 重排）。
 *
 * ## ⚠️ 2026-10-25 §4.0.10：按功能重排为 **6 个**（原 7 个）
 *
 * | 旧 | 新 | 动作 |
 * |---|---|---|
 * | `调色`（影调/白平衡/偏好） | **`明暗`**（明暗/白平衡/氛围） | 更名——它管的全部是「光」而不是「色」 |
 * | `颜色`（混色/分级/LUT） | **`颜色`**（混色/分级） | LUT 移入 `预设`（两者同为「一键风格」） |
 * | `效果`（暗角/颗粒） | 并入 **`细节`** | 5 个二级：锐化/降噪/质感/暗角/颗粒——同为收尾阶段 |
 * | `预设` | **`预设`**（风格/滤镜） | 吸收 LUT 成为一个二级分组 |
 *
 * 判据与 §4.3 一脉相承：**概念层级**。暗角/颗粒与锐化/降噪都是「收尾」（先定信息量再加质感），
 * 单独占一个一级位是层级错位；LUT 与风格预设都是「一键换风格」，分放两处会让用户
 * 在「预设」和「颜色→LUT」之间来回找。合并后一级只剩 **6** 个（上限 7 的推导见下方），
 * 工具条更宽松，且工作流顺序不变：**主体（人像）→ 光（明暗）→ 色（颜色/曲线）→ 收尾（细节）→ 风格（预设）**。
 *
 * ## 为什么「人像」排第一且不叫「工具」
 *
 * 顺序即**使用频次**：用户最常做的是「磨皮 + 美型」，所以它排第一。
 * 「画布工具」（皮肤画笔 / 祛瑕）**不作为分类**，而是 人像 分类下的子模式 ——
 * 它们改变的是「点图片会发生什么」，属于人像工作流的一部分。
 *
 * ## ⚠️ 一级分类的宽度上限：**7**
 *
 * 判据：一行 `labelMedium` 的双汉字标签约 24dp，两侧各留 ≥8dp 呼吸 ⇒ **每项至少 40dp**。
 * 工具条 `contentPadding` 是 0，条内 `padding(Spacing.xs)` 每侧 4dp，最窄的常见屏宽 320dp
 * 下可用宽 = **312dp** ⇒ `312 / 7 = 44.6dp` 够用，`312 / 8 = 39dp` 不够 ⇒ **上限 7**。
 * 当前 6 个，还留有 1 个名额（将来「裁剪 / 局部」等可入）。
 */
enum class EditorCategory(val label: String, val scopeMode: ScopeMode) {
    Portrait("人像", ScopeMode.NotApplicable),
    Light("明暗", ScopeMode.Layerable),
    Color("颜色", ScopeMode.Layerable),
    Curve("曲线", ScopeMode.Layerable),
    Detail("细节", ScopeMode.WholeImageOnly),
    Preset("预设", ScopeMode.NotApplicable)
}

/**
 * 一级分类与**对象作用域**（批次 5）的关系。三个状态各自解决一个界面判断，
 * 详见 `docs/OBJECT_TONE_DESIGN.md` §9.1（作用域行钉在滚动之外）与 §9.4（整幅阶段的空态）。
 *
 * ## 为什么是三个状态、而不是两个 `Boolean`
 *
 * 「显示作用域行」与「参数能否进对象层」是**两件独立的事**，而它们的组合恰好有第三种：
 * 细节 / 效果**能显示**作用域行（否则用户在空态里没有出口切回整图），
 * 但它们的参数**不能**进层。若用两个布尔，`when` 就失去穷尽性检查，
 * 将来加分类时也可能漏掉一处判断；三态枚举让「这个分类怎么参与作用域」变成一个**必须回答**的问题。
 *
 * ## 三态各自的理由
 *
 * - [Layerable]（明暗 / 颜色 / 曲线）：参数全是逐像素项 ⇒ 可以按对象作用。
 * - [WholeImageOnly]（细节，§4.0.10 起含暗角 / 颗粒）：**整幅阶段**。细节是邻域算子
 *   （要一张低频参考图），暗角是画面几何、颗粒是整幅确定性噪声 —— 按对象施加讲不通
 *   （§7）。作用域行仍然显示，那是用户在空态里唯一的出口。
 * - [NotApplicable]（人像 / 预设）：**与作用域无关**。人像的画笔/磨皮/美型有自己的蒙版口径
 *   （`RetouchScale.editorSkinMask`），预设是一整套参数栈；把它们塞进「按对象分层」是概念错位。
 *   不显示作用域行 ⇒ 也省下约 48dp 的常驻高度（这正是「只在逐像素分类下显示」的落地）。
 */
enum class ScopeMode(val showsScopeBar: Boolean, val layerable: Boolean) {
    Layerable(true, true),
    WholeImageOnly(true, false),
    NotApplicable(false, false)
}

/**
 * 编辑器一级工具条。
 *
 * 复用 [GlassSegmentedBar]（与底部 TabBar 同一控件）—— 两者视觉与行为完全一致，
 * 差别只在挂在哪一层。分开写两份必然出现「改了一处忘了另一处」的漂移。
 *
 * 高度与顶栏统一为 48dp（= [Spacing.controlHeight]）：保留足够触控高度，
 * 同时让两条导航控件对齐，不与照片争夺多余空间。
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
