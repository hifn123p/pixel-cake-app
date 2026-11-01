package com.hifn.pixelcake.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 品牌色（UI 3.0「Aperture」）。
 *
 * 注意：本项目**刻意不启用 Material You 动态取色**。
 * 调色类应用的编辑界面必须是中性、可预测的灰底，
 * 否则系统主题色会干扰用户对照片色彩的判断。
 * 动态取色只允许用在「设置 / 关于」等非编辑页面。
 *
 * ## UI 3.0 的品牌语言：暗房与安全灯
 *
 * 强调色从紫罗兰切换为**琥珀**（#FFB454 系）：琥珀与肤色同处暖域，作选中态时与照片
 * 「互不打架」；紫在深底上自带「AI 产品」的冷感，与「暗房工具」的定位相悖。
 * 琥珀只允许出现在四处：选中态、拖动中的填充与拇指、蒙版轮廓、主 CTA。
 * 中性阶整体偏暖（黄相 +2），与肤色、木、胶片同一温度。
 */
val Seed = Color(0xFFB8791A)  // 浅色主题强调色：深琥珀（浅底上直接用亮琥珀对比度不足）
val Ink = Color(0xFF26241F)
val NeutralSurface = Color(0xFFF1F0EB)
val NeutralSurfaceDark = Color(0xFF121211)
val Ok = Color(0xFF0F7B4F)
val Warn = Color(0xFF9A6700)
val Bad = Color(0xFFB3261E)

/**
 * 深色主题下的品牌强调色：**琥珀安全灯 #FFB454**。
 *
 * 与 Deck(#20201E) 的对比度 ≈ 9.8 : 1，远超 WCAG AA；「降饱和派生」的旧理由
 * （紫在深底的光晕问题）对琥珀不成立——琥珀本身就是低饱和暖色。
 * 凡是需要「当前选中 / 关键动作」的强调色，一律走 `MaterialTheme.colorScheme.primary`，
 * 不要在调用点直接写本值。
 */
val SeedOnDark = Color(0xFFFFB454)

// ———————————————————————————————————————————————————————————————
// 灰阶（UI 3.0 暖石墨系）。原则：1 主色 + 6 灰阶 + 3 语义色。
// 新增色值一律从下面这几组里取，不再引入「差不多但不一样」的颜色 ——
// 颜色数量一旦失控，界面立刻显出廉价感。
// ———————————————————————————————————————————————————————————————

/** 工作台底色。编辑页**强制深色、不受系统主题影响** —— 照片必须是页面上唯一的彩色主体 */
val WorkspaceBg = Color(0xFF121211)

/** 暗色主题上的正文色（暖白） */
val OnDarkSurface = Color(0xFFF0EEE9)

/** 灰阶 6 级：层次只用「深浅 + 字重」表达，不靠堆字号 */
val Gray0 = Color(0xFFFFFFFF) // 卡片底（浅色主题）
val Gray1 = Color(0xFFF5F4F0) // 实心降级底（浅色主题）
val Gray2 = Color(0xFFE9E8E2) // 分割线（浅色主题，极浅灰）
val Gray3 = Color(0xFFBEBBB2) // 禁用态
val Gray4 = Color(0xFF7A776E) // 辅助文字（浅色主题）
val Gray5 = Color(0xFF3B3933) // 次级正文（浅色主题）
// Gray6 == Ink（0xFF26241F）标题色，不重复定义

/** 玻璃高光描边（**实心降级**路径专用；浮层的液态描边见下面的 `LiquidEdge*`） */
val GlassBorderDark = Color(0x24FFFFFF) // α 0.14
val GlassBorderLightOpaque = Color(0x14000000) // α 0.08，实心降级时改用深色描边

// ———————————————————————————————————————————————————————————————
// 液态玻璃（浮层专用；沿用 v2.1 已验收的「顶亮底暗边缘受光」三段渐变）。
// UI 3.0 进一步收敛：blur 只允许出现在顶栏与把手段，控制台/工具栏实心。
// ———————————————————————————————————————————————————————————————

/** 液态玻璃本体：顶亮 → 中透 → 底回光（三段，0 / 0.48 / 1） */
val LiquidTopDark = Color(0x26FFFFFF) // α 0.15
val LiquidMidDark = Color(0x0BFFFFFF) // α 0.045
val LiquidBottomDark = Color(0x16FFFFFF) // α 0.085

val LiquidTopLight = Color(0xC4FFFFFF) // α 0.77
val LiquidMidLight = Color(0xADFFFFFF) // α 0.68
val LiquidBottomLight = Color(0xBAFFFFFF) // α 0.73

/** 液态玻璃描边：一条渐变描边同时承担「外描边 + 顶部镜面高光 + 底部反光」 */
val LiquidEdgeTopDark = Color(0x5CFFFFFF) // α 0.36
val LiquidEdgeBottomDark = Color(0x1AFFFFFF) // α 0.10

val LiquidEdgeTopLight = Color(0x2E000000) // α 0.18
val LiquidEdgeBottomLight = Color(0x12000000) // α 0.07

/** 外投影：没有它，浮层是「贴」在工作台上而不是「浮」着 */
val LiquidShadowDark = Color(0x66000000) // α 0.40
val LiquidShadowLight = Color(0x1F000000) // α 0.12

/**
 * 分段控件（TabBar）的选中指示块。编辑器工具栏已改为琥珀软底胶囊（见 `EditorRail`），
 * 这组 token 仅服务底部 TabBar；选中语义已由 `primary`（琥珀）承接。
 */
val SegmentFillTopDark = Color(0x4DFFFFFF) // α 0.30
val SegmentFillBottomDark = Color(0x1FFFFFFF) // α 0.12
val SegmentEdgeDark = Color(0x70FFFFFF) // α 0.44，顶部 inset 高光
val SegmentShadowDark = Color(0x52000000) // α 0.32
val SegmentEdgeLight = Color(0xE6FFFFFF) // α 0.90，浅色下顶部亮线改白
val SegmentShadowLight = Color(0x1A000000) // α 0.10

/** 滑块轨道。深色下压亮档：轨道要比面板**略亮**才看得出来，但又不能抢过拇指 */
val SliderTrackDark = Color(0x1CFFFFFF) // α 0.11
val SliderTrackLight = Color(0x17000000) // α 0.09

/** 白色拇指的投影。拇指是纯白实心圆，没有它会在浅色轨道上「飘」起来 */
val SliderThumbShadow = Color(0x70000000) // α 0.44

// ———————————————————————————————————————————————————————————————
// 容器色阶（5 档 × 2 主题，**档位越高 = 越亮 = 越「浮」**）。
// UI 3.0：整体换成暖石墨（黄相 +2），与 Deck #20201E / 琥珀同一温度。
// ———————————————————————————————————————————————————————————————

/** 暗色容器：Lowest 最暗（凹陷/页面底）→ Highest 最亮（浮起控件） */
val ContainerDarkLowest = Color(0xFF0E0E0D)
val ContainerDarkLow = Color(0xFF191917)
val ContainerDark = Color(0xFF20201E)
val ContainerDarkHigh = Color(0xFF2A2A27)
val ContainerDarkHighest = Color(0xFF343430)

/**
 * 浅色容器：Lowest 最暗灰（凹陷/页面底）→ Highest 纯白（卡片/浮起控件）。
 * UI 3.0 换成暖纸调（纸 = 自适应材质的亮档，见 UI3.0.html §2.2）。
 */
val ContainerLightLowest = Color(0xFFEFEEE9)
val ContainerLightLow = Color(0xFFF5F4EF)
val ContainerLight = Color(0xFFF4F1EA)
val ContainerLightHigh = Color(0xFFFCFBF8)
val ContainerLightHighest = Color(0xFFFFFFFF)

/** 实心容器的极淡描边：只在明度接近时兜底分割，**不是装饰**，不可加粗 */
val ContainerBorderDark = Color(0x14FFFFFF) // α 0.08
val ContainerBorderLight = Color(0x12000000) // α 0.07

// ———————————————————————————————————————————————————————————————
// 工作台语义色（UI 3.0 §9.1 真值表：成功/警告/错误/信息）。
//
// 与上面 [Ok] / [Warn] / [Bad]（v2.1 的浅色语义，仍服务浅色页）不同，这一组是
// **深色工作台**（编辑器 / 系留仪表盘 / 模型中心）的状态徽章用色：
// 深底上的亮语义色，对比度已按 Deck #20201E 校验 ≥4.5:1。
// ⚠️ 浅色页不要用这组（亮绿/亮黄压浅纸底会发飘）。
// ———————————————————————————————————————————————————————————————
val WsOk = Color(0xFF4CC38A)
val WsWarn = Color(0xFFE8B34B)
val WsErr = Color(0xFFE5645C)
val WsInfo = Color(0xFF6FB3FF)

// ———————————————————————————————————————————————————————————————
// 首页成片卡的占位渐变（金色时刻）。
//
// ⚠️ 它象征「这里将是一张照片」（卡片语义 = 成片位），**不是品牌色板**——
// 品牌面只有暖石墨 + 琥珀（暗房语言由页面底色与选中态承载，渐变只出现在这张卡里）。
// 四个停靠点从暗房紫黑过渡到琥珀金：讲的是「底片 → 出片」的叙事。
// 入库原因（§9.3-E）：任何 >3 处引用的颜色不得硬编码在 Composable 里。
// ———————————————————————————————————————————————————————————————
val HeroCardTop = Color(0xFF241833)
val HeroCardMid = Color(0xFF5C3450)
val HeroCardWarm = Color(0xFFB96A4C)
val HeroCardBottom = Color(0xFFE9A25C)
