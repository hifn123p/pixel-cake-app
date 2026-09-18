package com.hifn.pixelcake.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 品牌色。
 *
 * 注意：本项目**刻意不启用 Material You 动态取色**。
 * 调色类应用的编辑界面必须是中性、可预测的灰底，
 * 否则系统主题色会干扰用户对照片色彩的判断。
 * 动态取色只允许用在「设置 / 关于」等非编辑页面。
 */
val Seed = Color(0xFF7C5CFF)
val Ink = Color(0xFF1B1B1F)
val NeutralSurface = Color(0xFFF0F0F3)
val NeutralSurfaceDark = Color(0xFF121215)
val Ok = Color(0xFF0F7B4F)
val Warn = Color(0xFF9A6700)
val Bad = Color(0xFFB3261E)

/**
 * 深色主题下的品牌强调色（[Seed] 的「降饱和 + 提亮」派生色）。
 *
 * `Seed`（#7C5CFF，HSL 252/100/68）直接铺在深底上**同时踩两个坑**：
 *
 * 1. **对比度不够**：与深色容器 `ContainerDark`（#1B1B22）的对比度只有 **4.11 : 1**，
 *    低于 WCAG AA 正文要求的 4.5 : 1 —— 选中态文字在暗色下其实是「勉强能看」；
 * 2. **饱和度过高**：大面积高饱和紫在深底上会产生光晕渗透（halation），久看易疲劳，
 *    也会抢走照片本身的颜色（这与「照片是页面上唯一彩色主体」直接冲突）。
 *
 * 因此深色主题改用 HSL 252/87/75：**降饱和约 13%**（落在 M3E 建议的 10~15% 区间内），
 * 同时把明度提到 75%。与 `ContainerDark` 底的对比度升到 **6.04 : 1**，稳过 AA。
 *
 * 色相（252）与 [Seed] **完全一致** —— 它不是另一个颜色，只是「同一件衣服在暗处的版本」。
 * 凡是需要「当前选中 / 关键动作」的强调色，一律走 `MaterialTheme.colorScheme.primary`，
 * 不要在调用点直接写 [Seed]（否则深色主题会退回高饱和、低对比度的旧值）。
 */
val SeedOnDark = Color(0xFF9C86F7)

// ———————————————————————————————————————————————————————————————
// UI 改版新增（`docs/UI_DESIGN.md` §2.1）。原则：1 主色 + 6 灰阶 + 3 语义色。
// 新增色值一律从下面这几组里取，不再引入「差不多但不一样」的颜色 ——
// 颜色数量一旦失控，界面立刻显出廉价感。
// ———————————————————————————————————————————————————————————————

/** 工作台底色。编辑页**强制深色、不受系统主题影响** —— 照片必须是页面上唯一的彩色主体 */
val WorkspaceBg = Color(0xFF0E0E10)

/** 暗色主题上的正文色（P1 起沿用，抽出来给玻璃 token 复用） */
val OnDarkSurface = Color(0xFFE6E1E5)

/** 灰阶 6 级：层次只用「深浅 + 字重」表达，不靠堆字号 */
val Gray0 = Color(0xFFFFFFFF) // 卡片底（浅色主题）
val Gray1 = Color(0xFFF4F4F6) // 实心降级底（浅色主题）
val Gray2 = Color(0xFFE8E8EC) // 分割线（浅色主题，极浅灰）
val Gray3 = Color(0xFFBFBFC6) // 禁用态
val Gray4 = Color(0xFF7A7A83) // 辅助文字（浅色主题）
val Gray5 = Color(0xFF3A3A40) // 次级正文（浅色主题）
// Gray6 == Ink（0xFF1B1B1F）标题色，不重复定义

/** 玻璃高光描边（**实心降级**路径专用；浮层的液态描边见下面的 `LiquidEdge*`） */
val GlassBorderDark = Color(0x24FFFFFF) // α 0.14
val GlassBorderLightOpaque = Color(0x14000000) // α 0.08，实心降级时改用深色描边

// ———————————————————————————————————————————————————————————————
// 液态玻璃（UI 方案 B「克制液态」；方案对比见 docs/UI_DESIGN.md §2.1）
//
// 与旧版「一块平色 tint」的区别：**明度在同一个面内自上而下变化**。
// 平色的半透明面板只会被读成「磨砂亚克力」—— 因为它没有厚度；玻璃之所以像液体，
// 靠的是顶亮底暗的那圈**边缘受光** + 面内渐变。模糊只负责「让下层不干扰读数」，不负责「贵」。
//
// 方向约定：光从上方来 ⇒ 顶亮底暗。明暗两套主题都遵守，调用点不需要按主题翻转。
// α 刻意都压得很低：没有可透的下层，玻璃就只是一块灰塑料。
//
// ⚠️ 这组值取代了旧的 `GlassTintLight` / `GlassTintDark` / `GlassBorderLight`（已删除）。
// ———————————————————————————————————————————————————————————————

/** 液态玻璃本体：顶亮 → 中透 → 底回光（三段，0 / 0.48 / 1） */
val LiquidTopDark = Color(0x26FFFFFF) // α 0.15
val LiquidMidDark = Color(0x0BFFFFFF) // α 0.045
val LiquidBottomDark = Color(0x16FFFFFF) // α 0.085

val LiquidTopLight = Color(0xC4FFFFFF) // α 0.77
val LiquidMidLight = Color(0xADFFFFFF) // α 0.68
val LiquidBottomLight = Color(0xBAFFFFFF) // α 0.73

/**
 * 液态玻璃描边：**一条渐变描边同时承担「外描边 + 顶部镜面高光 + 底部反光」**。
 *
 * 为什么合并成一条：在 Compose 里把「外描边 + 两条 inset 高光」画成三层，要么自定义 Shape
 * 轮廓、要么嵌套 padding 挤布局；而这三者在视觉上本就只表达同一件事 —— **边缘受光**。
 * 一条顶亮底暗的渐变描边就能得到同样的「厚度」，且仍是一次 O(1) 绘制。
 */
val LiquidEdgeTopDark = Color(0x5CFFFFFF) // α 0.36
val LiquidEdgeBottomDark = Color(0x1AFFFFFF) // α 0.10

/**
 * 浅色主题的描边必须**压暗**：白玻璃上再叠白描边等于没描边，
 * 而旧的 α.35 白描边在浅底上会显脏（方案 B 风险点②）⇒ 改用 α.18 深色。
 */
val LiquidEdgeTopLight = Color(0x2E000000) // α 0.18
val LiquidEdgeBottomLight = Color(0x12000000) // α 0.07

/** 外投影：没有它，浮层是「贴」在工作台上而不是「浮」着 */
val LiquidShadowDark = Color(0x66000000) // α 0.40
val LiquidShadowLight = Color(0x1F000000) // α 0.12

/**
 * 分段控件（TabBar / 一级工具条）的选中指示块。
 *
 * 深色下用**白渐变**：iOS 的选中态是「一个被光打到的实体」，而平色的强调色块读起来只是
 * 「一块高亮」。浅色下不能用白（白底白块 = 不可见）⇒ 退回强调色淡染，只保留顶部那条亮线。
 */
val SegmentFillTopDark = Color(0x4DFFFFFF) // α 0.30
val SegmentFillBottomDark = Color(0x1FFFFFFF) // α 0.12
val SegmentEdgeDark = Color(0x70FFFFFF) // α 0.44，顶部 inset 高光
val SegmentShadowDark = Color(0x52000000) // α 0.32
val SegmentEdgeLight = Color(0xE6FFFFFF) // α 0.90，浅色下顶部亮线改白
val SegmentShadowLight = Color(0x1A000000) // α 0.10

/** 滑块轨道。深色下压亮档：轨道要比玻璃面**略亮**才看得出来，但又不能抢过拇指 */
val SliderTrackDark = Color(0x1CFFFFFF) // α 0.11
val SliderTrackLight = Color(0x17000000) // α 0.09

/** 白色拇指的投影。拇指是纯白实心圆，没有它会在浅色轨道上「飘」起来 */
val SliderThumbShadow = Color(0x70000000) // α 0.44

// ———————————————————————————————————————————————————————————————
// 容器色阶（M3E `surfaceContainerLowest` ~ `surfaceContainerHighest` 的本地化）。
//
// ## 为什么需要它
//
// 玻璃从「整套设计语言」收敛为「浮层专用」之后（`docs/UI_DESIGN.md` §1.6），页面内的
// **承载型容器**（卡片、分组、参数面板）改用**实心**色 —— 容器本来就不需要透出下层，
// 半透明只会让层次变浑，还强迫每一层都去画那根高光描边。
//
// ## 本地约定：**档位越高 = 越亮 = 越「浮」**
//
// 明暗两个主题都遵守这一条，所以 [ContainerLevel] 的映射在两个主题下方向一致、
// 调用点**不需要**按主题翻转。这是刻意与 M3 官方命名反着来的 —— 官方在浅色主题里
// `surfaceContainerLowest` 才是最亮的白，对使用者是反直觉的；我们只借它的**分档思想**，
// 不借名字的方向。
//
// 5 档是**系统明度梯**（每档固定 ΔL），不是 §2.1 警告的那种「差不多但不一样」的散色。
// ———————————————————————————————————————————————————————————————

/** 暗色容器：Lowest 最暗（凹陷/页面底）→ Highest 最亮（浮起控件） */
val ContainerDarkLowest = Color(0xFF0C0C10)
val ContainerDarkLow = Color(0xFF15151A)
val ContainerDark = Color(0xFF1B1B22)
val ContainerDarkHigh = Color(0xFF24242C)
val ContainerDarkHighest = Color(0xFF2E2E37)

/**
 * 浅色容器：Lowest 最暗灰（凹陷/页面底）→ Highest 纯白（卡片/浮起控件）。
 *
 * 方向与暗色一致（越亮越浮）。浅色主题的明度余量很小（卡片基本就是白），
 * 所以 `Container` 与 `Highest` 只差 4 级 —— 这是物理限制，靠 [ContainerBorderLight]
 * 那条极淡描边兜底分割。
 */
val ContainerLightLowest = Color(0xFFEDEDF1)
val ContainerLightLow = Color(0xFFF4F4F7)
val ContainerLight = Color(0xFFFBFBFD)
val ContainerLightHigh = Color(0xFFFDFDFF)
val ContainerLightHighest = Color(0xFFFFFFFF)

/** 实心容器的极淡描边：只在明度接近时兜底分割，**不是装饰**，不可加粗 */
val ContainerBorderDark = Color(0x14FFFFFF) // α 0.08
val ContainerBorderLight = Color(0x12000000) // α 0.07
