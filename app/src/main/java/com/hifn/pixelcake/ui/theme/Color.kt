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
val NeutralSurface = Color(0xFFF7F7F8)
val NeutralSurfaceDark = Color(0xFF121215)
val Ok = Color(0xFF0F7B4F)
val Warn = Color(0xFF9A6700)
val Bad = Color(0xFFB3261E)

// ———————————————————————————————————————————————————————————————
// UI 改版新增（`docs/UI_DESIGN.md` §2.1）。原则：1 主色 + 6 灰阶 + 3 语义色。
// 新增色值一律从下面这几组里取，不再引入「差不多但不一样」的颜色 ——
// 颜色数量一旦失控，界面立刻显出廉价感。
// ———————————————————————————————————————————————————————————————

/** 工作台底色。编辑页**强制深色、不受系统主题影响** —— 照片必须是页面上唯一的彩色主体 */
val WorkspaceBg = Color(0xFF0E0E10)
val WorkspaceSurface = Color(0xFF16161A)
val WorkspaceSurfaceHigh = Color(0xFF1F1F25)

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
