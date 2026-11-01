package com.hifn.pixelcake.ui.editor

/**
 * 编辑器一级工具分类（UI 3.0「Aperture」§4.3 三级工具条的第 1 级）。
 *
 * ## 六个分类 = 工作流顺序
 *
 * **主体（人像）→ 光（明暗）→ 色（颜色/曲线）→ 收尾（细节，含暗角/颗粒）→ 风格（预设，含 LUT 滤镜）**。
 * 顺序即使用频次：用户最常做的是「磨皮 + 美型」，所以人像排第一。
 *
 * 「画布工具」（皮肤画笔 / 祛瑕）不作为分类，而是人像分类下的子模式 ——
 * 它们改变的是「点图片会发生什么」，属于人像工作流的一部分。
 *
 * ## 一级分类的宽度上限：7
 *
 * Rail（`EditorRail`）是等分胶囊：375dp 屏宽 − 2×10dp 边距 − 2×6dp 内距 = 343dp，
 * 6 项每项约 57dp，容纳 22dp 图标 + 11sp 标签绰绰有余；上限 7 的推导
 * （`312 / 7 = 44.6` 够、`312 / 8 = 39` 不够）仍然成立——加到第 8 个才必须横向滚动。
 *
 * 挂载形态见 [EditorRail]（UI 3.0：Rail 切换的是 Deck 内容，不再是「面板的开关」）。
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
 * ## 三态各自的理由
 *
 * - [Layerable]（明暗 / 颜色 / 曲线）：参数全是逐像素项 ⇒ 可以按对象作用。
 * - [WholeImageOnly]（细节，含暗角 / 颗粒）：**整幅阶段**。细节是邻域算子
 *   （要一张低频参考图），暗角是画面几何、颗粒是整幅确定性噪声 —— 按对象施加讲不通
 *   （§7）。作用域行仍然显示，那是用户在空态里唯一的出口。
 * - [NotApplicable]（人像 / 预设）：**与作用域无关**。人像的画笔/磨皮/美型有自己的蒙版口径
 *   （`RetouchScale.editorSkinMask`），预设是一整套参数栈；把它们塞进「按对象分层」是概念错位。
 *   不显示作用域行 ⇒ 也省下约 48dp 的常驻高度。
 */
enum class ScopeMode(val showsScopeBar: Boolean, val layerable: Boolean) {
    Layerable(true, true),
    WholeImageOnly(true, false),
    NotApplicable(false, false)
}
