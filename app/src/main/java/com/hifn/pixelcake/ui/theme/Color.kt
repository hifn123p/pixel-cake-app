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

/** 玻璃高光描边 */
val GlassBorderLight = Color(0x59FFFFFF) // α 0.35
val GlassBorderDark = Color(0x24FFFFFF) // α 0.14
val GlassBorderLightOpaque = Color(0x14000000) // α 0.08，实心降级时改用深色描边

/** 玻璃底 */
val GlassTintLight = Color(0xB8FFFFFF) // α 0.72
val GlassTintDark = Color(0xB81C1C22) // α 0.72

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
