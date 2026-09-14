# PixelCake UI 设计规范（草案 v0.1）

> 状态：**大纲框架 · 待评审**。本文只定义视觉与交互骨架、Token 与落地顺序，**不含实现代码**。
> 目标：为 PixelCake 建立一套「iOS 26 Liquid Glass 观感 + Android 原生实现」的界面语言，
> 以 美图秀秀 / Snapseed / 醒图 为对标基线，同时守住本 App 的专业向定位。

---

## 1. 竞品基线（只看界面骨架，不评功能）

| 维度 | 美图秀秀 | Snapseed | 醒图 | PixelCake 取舍 |
|---|---|---|---|---|
| 首屏 | 大卡片功能入口 + 社区信息流 | 单张大图 + 打开按钮 | 白底极简 + 横向热点模板 | 极简工作台：单入口「导入」+ 设备能力面板 |
| 编辑页导航 | 底部大图标 Tab + 二级列表 | 底部「工具/预设/导出」→ 工具网格 → **右侧竖排滑块** | 底部整合工具条 + 二级面板 | 底部**悬浮玻璃工具条**（分段）+ 参数抽屉 |
| 参数调节 | 底部横向滑块 + 数值 | 右侧竖排滑块（左手不挡图） | 底部滑块 | 底部玻璃卡滑块 + 长按数值微调 |
| 原图对比 | 右上角按住 | 右上角「眼睛」 | 左上角 | 预览区**长按对比** + 顶栏常驻按钮 |
| 撤销 | 左上角 | 右下角**多步历史栈** | 左上角 | 顶栏撤销/重做，对齐 Snapseed 的历史栈 |
| 视觉风格 | 粉白、贴纸化、重装饰 | **深色极简、纯工具感** | 白底扁平、轻盈 | **深色中性工作台 + 玻璃浮层** |
| 拇指热区 | 底部 | 底部 + 右侧 | 底部 | 底部 60% 屏高以内 |

### 1.1 三条结论

1. **工作台必须去装饰**。预览区占 ≥55% 屏高，控件按需浮出。醒图的扁平留白 + Snapseed 的浮层工具是正解；美图秀秀的重装饰风格不适合专业向调色工具。
2. **调色面必须中性**。页面上唯一的彩色主体应当是照片本身。这与现有 `ui/theme/Color.kt` 中刻意关闭 Material You 动态色的决定一致，**继续保留**。
3. **Liquid Glass 可直接映射到 Android**。Apple 的四支柱（半透明+深度 / 空间层级 / 动态响应 / 跨端统一）在 Compose 侧的等价实现是：`RenderEffect.createBlurEffect` + `Modifier.graphicsLayer` + 高光描边 + 顶层浮层 `Surface`（详见 §2）。

### 1.2 iOS 26 Liquid Glass 四支柱 → Android 映射

| Apple 支柱 | 含义 | Compose 等价物 |
|---|---|---|
| Translucency & Depth | 实时高斯模糊 + 折射 | `RenderEffect.createBlurEffect(24f)` / `Modifier.blur`，backdrop 采样下层 Bitmap |
| Spatial Hierarchy | 控件悬浮于内容之上、分深度 | 顶层 `Box` + `Surface(tonalElevation)`，内容滚动在玻璃之下 |
| Dynamic Response | 玻璃随背景着色、滚动时形变 | `Brush` 叠加随底图主色微调；`NestedScrollConnection` 驱动高度动画 |
| Unified Cross-Platform | 全平台一致 | Compose Multiplatform 无关；此处只做一致 Token 化 |

已知代价（来自公开评测）：实时模糊 + 折射对 GPU 很贵，且在小屏幕上会分散注意力。
→ 本项目的对策见 §6 的「玻璃性能护栏」。

### 1.3 同类 App 的三种设计思路

调研 Darkroom / Snapseed / Lightroom Mobile / VSCO / Affinity / Polarr 后的归纳 —— **抄思路，不抄外观**。

| 流派 | 代表 | 目标 | 核心手法 |
|---|---|---|---|
| **隐形式** | Darkroom · Snapseed | 让用户忘掉「我在用修图 App」 | 中性单色底、极限克制的控制点、**拖参数时隐藏全部 UI** |
| **仪器式** | Lightroom Mobile · Affinity · Polarr | 把专业能力搬进手机，用**秩序**消化复杂度 | 分类侧栏 + 明确命名 + 可回退历史栈 + 可自定义工作区 |
| **审美先行式** | VSCO · 醒图 | 卖的是**审美方向**，不是参数 | 大图预览为主、预设先于参数、一致的影调身份、安静的社区 |

**PixelCake 方向**：取「隐形式」的骨架 + 「仪器式」的秩序（分层工具条 / 历史栈），
**不做**「审美先行式」的社区与预设运营 —— 我们的定位是本地、精确、可复现的专业工具。

### 1.4 五条可复用的设计信条

1. **显而易见优先**。Darkroom 创始人原话：「好的设计就是显而易见的设计」——
   显而易见 ⇒ 学得快、用得顺，因为不用想。他们甚至会**逐个数出完成一个动作要点几下**再优化。
2. **照片是唯一主体**。Lightroom Mobile 的杀手细节：**拖参数时整条编辑侧栏消失**，
   只留照片和滑块，松手立刻恢复。零成本，却是最明显的质感分水岭。
3. **用秩序消化复杂度**，不是砍功能。Snapseed 的 `Stacks` 允许退回**任意一步** ——
   复杂度本身不是问题，**不可撤销**才是。
4. **不做魔术，做精确**。Darkroom 明确不做 AI 魔术棒，定位清晰反而赢得专业用户信任，
   与本项目「全本地处理、结果可复现」一致。
5. **一致性 > 灵感**。高级感的绝大部分不是创意，是纪律。

### 1.5 「审美先行式」的取舍：我们要的只有中间那层

**修正 §1.3 的措辞**：审美先行式的内核**不是社区**，而是「**预设是产品的最小可复用单元，参数只是预设的展开**」。
VSCO 的护城河也不是社交，是 `A4 / C1 / G3` 这些代号构成的**一致影调身份**。
我们要拒绝的是**社区运营**，不是审美先行式本身。

复核代码后发现，这套机制在本项目里**已经存在大半**：

| 已有 | 位置 |
|---|---|
| 预设 = `EditParams` + `RetouchState` 的**参数栈快照**（跨工具：调色 + 人像） | `core/edit/preset/Presets.kt` |
| 批量链路**只认预设**，全程不暴露任何参数 | `CameraPanel` 选预设 → `CameraBatch.run(preset = …)` |
| 编辑页可套用预设 | `ParamPanel.PresetParams` |

**缺口**：`Presets` 是硬编码 `object`（仅 10 套内置，注释里写明「后续若需用户自定义…再外置」），
编辑页调好的参数**存不回去**，也**喂不到批量链路** —— 「预设 ← 参数」这一环没接上。

| 档 | 取什么 | 代价 | 建议 |
|---|---|---|---|
| **A 轻** | 预设卡片带**真实缩略图**（用当前图渲染），不再是纯文字 chip | 低，纯 UI | ✅ **已落地**（2026-09-14，见 §1.6.2） |
| **B 中** | 预设升为产品枢纽：**「当前参数另存为预设」+ 持久化预设库**，编辑成果可直接用于批量 / NAS | 中 | ✅ 推荐，但**跨出 UI 层** |
| **C 重** | 社区 / 模板市场 / 内容运营 | 高（账号 + 后端 + 审核） | ❌ 不采纳，与「全本地处理」定位冲突 |

**范围提醒**：B 需要改 `core/edit/preset/Presets.kt`（`object` → 可扩展库 + 持久化），
**违反本阶段「只动 UI 层」的硬约束**。两种干净做法：
1. UI 阶段先留出「另存为预设」入口（按钮在、提示待实现），零风险、UI 一次成型；
2. 把 B 单独立为后续阶段（如 P4「预设库」），独立设计 + 独立测试，不混进 UI 的 diff。

**当前倾向**：选 2 —— UI 先把「预设是枢纽」在视觉上讲清楚，能力随后补上。

---

### 1.6 2026 主流趋势对照（2026-09-13 调研）

搜了 2026 年的主流共识（Figma 趋势报告、M3 Expressive 官方口径、多份动效/玻璃实务规范），逐条对照本项目 token。

**外部最大的变化：Material 3 Expressive**（Google I/O 2025 发布，Android 16 起为默认）。它不是换皮，而是重建了四层：
**动效物理**（弹簧取代固定时长插值）、**自适应色彩**（三套调色板经 HCT 调和并存）、
**深度语义化**（组件声明 depth role，不再用 elevation 数值）、**组件表达力**（形状形变、强调字重）。
对我们的直接意义：**「弹簧 + 形状 + 强调字重」在 Android 上是当前默认预期，不是可选风格。**

> ⚠️ M3 Expressive 同时强调：**professional apps 只在 hero moment 选择性使用这些手段，amateur apps 才会到处均匀地加**。
> 这条与我们「克制优先」的既有取舍一致 —— 不要因为有了新玩具就全屏弹跳。

**结论：本项目 token 体系与主流高度吻合（14 项对照，10 项已对齐、4 项待调整）。**

**已对齐、后续重构不要改坏的**：

| 项 | 共识 | 我们的值 |
|---|---|---|
| 动效时长 | 微交互 100–150 / 标准 UI 150–250 / 模态与布局 200–300；**UI 动效不超 300ms** | 150 / 240 / 320ms |
| 按下反馈 | `scale(0.95–0.98)`，100–160ms | `pressedScale = 0.97` |
| 进场缓动 | **强 ease-out**；**绝不把 ease-in 用于进场**（最常见错误） | `easingOut = (0.16, 1, 0.3, 1)`，且只用于进场 |
| 合成属性 | 只动 `transform` / `opacity` | `pressScale` 走 `graphicsLayer` |
| 深色底色 | **不用纯黑**（`#0F1419`–`#121212`，避免 OLED 拖影与光晕） | `WorkspaceBg = #0E0E10` |
| 玻璃描边 | 1px 半透明白 —— *"没有它就只是一个模糊的盒子"* | `Glass.borderWidth` 1dp，α 0.14 |
| 实时模糊 | 代价高，避免 | 静态模糊底图（导入时一次性生成） |
| 无障碍逃生舱 | Reduced Transparency / Increased Contrast 是**必须项** | `LocalLowTransparency`、`reduceMotion()` |
| 色调方向 | 双轨并行：多巴胺高饱和（消费/潮玩） vs **自然大地低饱和**（健康/金融/办公/**专业工具**） | 走后者：极简、低饱和、中性调色面 |
| 质感 | 细噪点 / 柔光光晕可中和"冰冷的塑料感" | hero 柔光光晕 |

**待调整的 4 项**（按建议优先级）：

1. **弹簧没有按属性分类**。M3E 要求分两族：**Spatial**（位置 / 尺寸 / 旋转 / 形状）用回弹，**damping ≈ 0.6**；
   **Effects**（颜色 / 透明度）用**临界阻尼，damping = 1.0**。并明确：**绝不把空间弹簧用于颜色或透明度**
   —— alpha 冲过 100% 再回落，看起来就是坏的。
   我们现在的 `springSoft()`(0.8) / `springSnappy()`(0.9) 是**按手感分**的，不是按属性分的。
   → 拆成 `springSpatial()` / `springEffects()` 两族，调用点按属性选。

2. **深色模式的强调色没有降饱和**。共识：深色下品牌强调色降饱和 **10–15%**，降低眼睛疲劳。
   我们只有一组 `Seed #7C5CFF`，浅色深色共用。
   → 增加深色专用的降饱和变体，只在 `PixelCakeWorkspaceTheme` / 暗色主题使用。

3. **玻璃当前是"整套设计语言"，而 2026 共识是"点缀"**。多个来源口径一致：
   做得好的产品把玻璃用在**上下文浮层**（导航栏、浮动工具条、迷你播放器）、**短预览面**（1–2 行卡片、chip、紧凑摘要）、
   **品牌高光时刻**（hero、onboarding、升级弹窗）；**不适用**于长阅读面、表单、密集表格。
   我们目前 `glassSurface` 覆盖 TabBar + 全部卡片 + 工具条 + Sheet。
   → **这是方向性选择，需要拍板**：收敛为「浮层用玻璃、内容面用实心或半实心」，还是保持现状。
   （注意：我们的玻璃**没有真实 blur**，所以性能上不受"层数"约束；这条纯粹是视觉层级的问题。）

4. **尚未采纳 M3 Expressive 的新 token 与组件**：`MaterialTheme.motionScheme`（不要把 spring 参数硬编码在业务代码里）、
   `FloatingToolbar`（胶囊形、随内容漂移 —— 形态正合我们的编辑器工具条）、`ContainedLoadingIndicator`、
   wavy progress、以及 `surfaceContainerLowest`~`surfaceContainerHighest`（**5 级容器色取代基于透明度的 elevation**）。
   当前 `composeBom = 2025.11.01`，**可能**已包含 material3 1.4（M3E 组件所在版本），
   但**必须在 CI 上验证 API 是否存在，不要凭记忆写**。

#### 1.6.1 落地结果（2026-09-13 当晚一次做完）

用户口径：「按你的全部一次性开发完」。4 项全部收口：3 项完全落地，1 项有条件落地。

| # | 项 | 落地内容 | 状态 |
|---|---|---|---|
| 1 | 弹簧分族 | `Motion` 删除 `springSoft`(0.8) / `springSnappy`(0.9)，改为**空间族** `springSpatialFast` / `springSpatial`（阻尼 **0.6**）+ **效果族** `springEffects`（阻尼 **1.0**）。全部调用点按属性重挂 | ✅ |
| 2 | 深色强调色降饱和 | 新增 `SeedOnDark = #9C86F7`（HSL 252/87/75，降饱和 ≈13%）。`DarkColors.primary` 与 `WorkspaceColors.primary` 改用它 | ✅ |
| 3 | 玻璃收敛为浮层专用 | `GlassCard` 默认材质改为**实心容器**（新增 `CardMaterial` 枚举）；玻璃只留在 TabBar / 分段条 / 圆形按钮 / 胶囊提示 / 首屏展示位 / 首页状态卡。新增 `Color.kt` 容器色阶（5 档 × 2 主题）+ `ContainerLevel` + `Modifier.containerSurface()` | ✅ |
| 4 | M3E 新 token / 组件 | 5 级容器色**已按本地口径落地**；`motionScheme` / `FloatingToolbar` / wavy progress **未采纳** | ⚠️ 部分 |

**三处与建议稿的偏差**（都是「按实际代码收敛」，不是偷工）：

1. **透明度一档仍用 `tween`，没有换成效果族弹簧。** 建议稿写的是「效果族弹簧也管透明度」，
   但落地时发现：本 App 里所有 alpha 动画都是**「受手势驱动、时长必须确定」**的场景
   （拖动时隐藏 chrome、胶囊提示自行退场）。用弹簧会让收敛时间随刚度浮动、不可预期。
   最终口径改为：**颜色 → 效果族弹簧；透明度 → `tween` + `durationFor`**。
   顺带修掉一个真 bug：**7 处 `tween` 漏写 `durationFor`**，系统开了「移除动画」App 仍会动
   （`EditorScreen` 5 处、`AppShell` 2 处）。
2. **空间族只做 2 档，没有第 3 档「大幅整屏位移」。** 编辑器是**硬切**进入的
   （退出时立刻回收源位图，套转场会有「画到已回收 Bitmap」的崩溃风险），全 App 没有
   任何调用点需要大位移档。留一个没人用的档位，只会让下一个人选错。
3. **容器色阶按「越亮越浮」重排。** M3 官方在浅色主题里 `surfaceContainerLowest` 才是最亮的白，
   对使用者反直觉。本项目两个主题统一为**档位越高越亮**，调用点不必按主题翻转方向。

**为什么「深色降饱和」是修 bug 而不只是审美**：`Seed #7C5CFF` 与 `ContainerDark #1B1B22`
的对比度只有 **4.11 : 1**，低于 WCAG AA 正文要求的 4.5 : 1 —— 深色下的选中态文字本来就不够清晰。
`SeedOnDark` 把它提到 **6.04 : 1**。降饱和只是顺带收益（同时削弱深底上的光晕渗透）。

#### 1.6.2 后续补做（2026-09-14）

一次「UI 按钮 ↔ 实际操作」的可达性审计（逐条反查控件 → 回调 → 引擎分支）之后补了三件事。
审计结论：**无死按钮、无空壳开关**；只有下面 1 处文档过度声明 + 1 处真 bug + 1 处轻量口径缺口。

| # | 项 | 内容 | 性质 |
|---|---|---|---|
| 1 | **A 档落地：预设缩略图** | 新增 `ui/components/PresetThumbRow.kt`；`MainActivity.buildPresetThumbs()` 按**原图**渲染 10 套预设（192×192，换图时算一次）。§1.5 的 A 档此前只标了「✅ 采纳」却没实现，本轮补上 | 功能补做（不只是改文档） |
| 2 | **`LocalDarkTheme`：强制深色主题下 token 取错色板** | 新增 `LocalDarkTheme` CompositionLocal，由 `PixelCakeTheme` / `PixelCakeWorkspaceTheme` 显式下发；`Glass.kt` 的三处 `isSystemInDarkTheme()` 全部改用**生效主题** | **真 bug 修复** |
| 3 | **半径滑块不触发重渲** | `MainActivity` 的 `snapshotFlow` 补上 `brushRadius` / `inpaintRadius` | 口径修正（原先只拖半径滑块，预览不跟随） |

**第 2 项为什么是 bug 而不是洁癖**：`PixelCakeWorkspaceTheme` 只换 `colorScheme`，**不会**改变系统
`uiMode`；而 `isSystemInDarkTheme()` 读的是 `LocalConfiguration`。于是**系统处于浅色模式**时，
编辑页（强制深色）里的 `containerSurface` / `rememberGlassTint` 会取到**浅色**色板
⇒ 一块近白的参数面板压在深色工作台上。这与 UI-2 修过的「照片周围一圈浅色」是同一类错误的第二代：
**主题强制换肤后，任何「按系统猜颜色」的代码都会猜错**。所以深色与否必须由主题层显式下发。

**A 档的三个刻意口径**（写在 `buildPresetThumbs` 的 KDoc 里）：
1. **从原图渲染**，与当前编辑无关 —— 否则用户一调参，10 张缩略图跟着变，参照系消失；
2. **只应用影调 + 磨皮 + 追色，丢掉液化与祛瑕** —— 几何形变要人脸锚点，缩略图阶段不跑检测，
   「蒙版质心猜」会把 192px 小图拧得很难看，反而失真；
3. **换图时算一次**（10 张 192×192，几十毫秒，`Dispatchers.Default`），不随参数变化重算。

**尺寸取舍**：192px = 64dp @3x（面板显示 64dp）；10 张合计约 1.5MB，**上界固定**（预设套数），
因此不做回收 —— 回收要处理「当前帧还在画、已被 recycle」的竞态，为 1.5MB 冒崩溃风险不划算。

---

## 2. 设计语言（五个支柱）

| 支柱 | 取值 | 落地位置 |
|---|---|---|
| 玻璃材质 | 不用真模糊：alpha 0.72 + 1px 高光描边(α.35)；质感由静态模糊底图提供 | `ui/theme/Glass.kt` → `Modifier.glassSurface()` |
| Squircle 形状 | 圆角阶梯 12 / 20 / 28 / 36 dp | `ui/theme/Radius.kt` |
| 中性调色面 | 工作台深色 `#0E0E10`；浅色底座 `#F0F0F3`（卡片 `#FBFBFD`） | `ui/theme/Color.kt` / `Theme.kt` |
| 浮动控制层 | 玻璃工具条 / 悬浮 TabBar / 抽屉浮起（**玻璃只此一处**，其余走容器色阶） | `ui/shell/*`、`ui/editor/*` |
| iOS 弹性动效 | 弹簧按属性分族（空间 0.6 / 效果 1.0）+ `tween` 兜底 | `ui/theme/Motion.kt` |

### 2.1 Token 明细

| 类别 | 取值 | 说明 |
|---|---|---|
| 圆角 | 12 / 20 / 28 / 36 dp | 小控件 / 卡片 / 抽屉 / 手机外壳。Compose 无原生 squircle，用大圆角近似 |
| 玻璃 | blur 24dp，fill alpha 0.72，描边 1px `Color.White.copy(alpha=0.35f)` | 关键护栏：**只模糊静态底图**，滚动/拖动不重算 |
| 阴影 | 0 / 8 / 24 dp 三级，仅用于「浮起」的控件 | 普通卡片靠描边 + 底色分层，不用阴影（对齐扁平原则） |
| 强调色（浅 / 深**两值**） | 浅色 `Seed = #7C5CFF`；**深色 `SeedOnDark = #9C86F7`**（降饱和 ≈13%） | 只用于选中态 / CTA / 滑块轨道；**调用点一律写 `colorScheme.primary`**，不直接写 `Seed` —— 深底上 `Seed` 对比度仅 4.11:1，`SeedOnDark` 为 6.04:1 |
| 底座 | 工作台深色 `#0E0E10`；浅色 `#F0F0F3`（卡片 `#FBFBFD`）；暗色 `#121215`（卡片 `#1B1B22`） | 新增「工作台深色」方案，编辑页可强制使用。浅色底座从 `#F7F7F8` 压到 `#F0F0F3`，让**实心卡片**有 ≈11 级明度差可分层 |
| 容器色阶 | 5 档 × 2 主题（`ContainerLevel`：Lowest → Highest） | **档位越高 = 越亮 = 越「浮」**，明暗方向一致。承载型容器走 `containerSurface()`，不再用玻璃（§1.6.1） |
| 字阶 | 保留 displaySmall 24 / titleMedium 16 / bodyMedium 14 / labelMedium 12，**新增 11sp caption** | 参数数值建议等宽字体，避免拖动时宽度跳动 |
| 栅格 | 页面左右边距 **24dp**，卡片内边距 16dp，卡片间距 12dp，控件最小高 44dp | 全部走 `Spacing` token。**原稿写 20dp 不在尺度上**，故取 24dp —— 顺带落实「留白 +30%」这条最高性价比的改进 |

### 2.2 「高级感」从哪来：先减后加

调研三份实务资料的共同结论：**高级感 ≈ 减少视觉噪音，把省下的注意力投给一致性**。
所谓「完美不是没有什么可以增加，而是没有什么可以减少」。

| 减掉（视觉噪音） | 换成（一致性投入） |
|---|---|
| 重边框 / 满屏分割线 | 留白 + 底色差分层（分割线用 `#F0F0F0` 级浅灰） |
| 8 种以上字号 | 字号收到 **4–5 级**，字重最多 3 种（Regular / Medium / Semibold） |
| 高饱和主色铺满 | 主色**只做强调**（按钮 / 选中态 / 重点文字），大面积用中性色 |
| 多种圆角混用 | 圆角统一走 §2.1 的 token（避免「按钮 4px、卡片 12px、输入框全圆」） |
| 图标风格混搭 | 一套同族图标，粗细与端点统一 |
| 四周扩散的浓黑阴影 | 只留**垂直偏移的干净阴影**，且只有「浮起」的控件才有 |

### 2.3 「廉价感」反面清单（逐条对照本项目现状）

| # | 会毁掉高级感的做法 | 本项目现状 |
|---|---|---|
| 1 | 到处描边框 | ⚠️ `EditorScreen` 的 `OutlinedCard` 给每个分组都描边 → 改**底色差 + 留白**分层 |
| 2 | 一屏铺开 20+ 控件 | ⚠️ 现状正是一条超长 `Column` → 见 §4.3 三级分类 |
| 3 | 玻璃铺满全屏 | ✅ 已约束：`GlassCard` 默认实心容器，玻璃只留浮层（§1.6.1） |
| 4 | 主色大面积使用 | ✅ 已约束（Material You 已关，主色只做强调） |
| 5 | 阴影乱堆 | ✅ 已约束（只有「浮起」的控件有阴影） |
| 6 | 字号继续增加 | ⚠️ 现有 24/16/14/12 + 新增 11sp = **5 级，已到上限**，不再新增 |
| 7 | 图标风格混搭 | ⚠️ Material 默认图标 + 自绘需统一为同族同粗细 |
| 8 | 拖参数时不隐藏 UI | ⚠️ **最廉价的质感提升点，当前完全没做** → 见 §5 动效表 |

### 2.4 可量化的验收清单

把「高级感」这种主观判断降维成可勾选项，UI-4 阶段逐条过：

- [ ] 间距只用 `4 / 8 / 12 / 16 / 24 / 32 / 48`，代码里 grep 不出其他魔法数字
- [ ] 字号 ≤ 5 级，字重 ≤ 3 种
- [ ] 颜色 = 1 主色 + 6 灰阶 + 3 语义色
- [ ] 圆角全部来自 `Radius` token（4 档 + 胶囊），不出现第二种来源
- [ ] 卡片**无边框**，靠底色差 + 留白分层
- [ ] **拖拽参数时，非参数类 UI 全部隐藏**
- [ ] 每个动作的点击数 ≤ Snapseed 的对应动作
- [ ] 动效统一缓动，按下缩放 0.96–0.98，转场 200–300ms

---

## 3. 信息架构与导航

### 3.1 现状
`MainActivity` 用 `var screen by remember { mutableStateOf("home") }` + `when (screen)` 硬切两屏，
无转场、无返回栈、状态全部散落在 `AppRoot` 的约 40 个 `remember` 里。

### 3.2 页面清点（Page Inventory）

结论先行：**用户能看到的独立界面共 8 个，但真正需要独立路由的只有 3 个**。
判据只有一条 —— **能用浮层解决的，绝不新开一个页面**。

| 界面 | 载体 | 是否必需 | 判据 |
|---|---|---|---|
| 调色台 | 一级 Tab | ✅ 必需 | 主入口；**展示优先 + 右上角「＋」收口全部导入方式**，见 §3.2.2 |
| 编辑器 | 全屏页 | ✅ 必需 | 唯一值得独占屏幕的界面（预览 ≥55% 屏高） |
| 设置 | 一级 Tab | ✅ 必需 | 收纳「改一次就不动」的项，见 §3.2.1 |
| 导入页 | ⛔ 不单独建 | **不要** | 做成**「＋」唤起的 Sheet**（相册 / ARW / 连接相机）。独立成页会多一跳，且它没有任何需要独占屏幕的内容 |
| 导出页 | 底部 Sheet | **要功能，不要页面** | 导出是编辑的最后一步 —— 用户此刻**必须能同时看见照片**，还要能随时反悔继续调。整页会遮住预览 |
| 关于页 | Sheet | **要功能，不要页面** | 版本 + 开源许可 + 隐私声明。`NOTICE` 已列 4 条依赖（LibRaw / LibRaw-cmake / LiteRT / 两个 `.tflite`），法律上需要一个**可达**的展示位，Sheet 足够 |
| 相机连接 | Sheet | ✅ 必需 | A7C2 USB 直连的进度 / 取消 / 批量列表（P2 已有逻辑，只缺 UI） |
| 相册历史 | 一级 Tab | ⏸ 延后 | 现阶段无持久化数据源 → 做了就是空壳，拉低完成度观感。真正归宿是 P3 NAS（待处理 / 已处理 / 失败） |
| 批处理导出 | 全屏页 | ⏸ 延后 | 多选 + 队列 + 进度 + 重试属 P3；单张导出用 Sheet 即可 |
| 调试日志 | 设置页内条目 | ✅ 必需 | 现有 `DebugLog` + FileProvider 导出，从首页移到设置更合理 |

#### 3.2.1 设置页的边界（最容易走偏）

- **放**：默认导出格式 / 位深、ML 加速策略（GPU→CPU 自动降级开关）、外观（跟随系统 / 强制深色工作台）、缓存与存储占用、调试日志导出、关于入口。
- **不放**：任何调色参数、预设内容、LUT 管理 —— 那些是编辑页的活。
- 判据：**每次编辑都要用的，不该进设置；一年才改一次的，不该占编辑页。**

#### 3.2.2 调色台首页 = 展示位 + 唯一动作（2026-09-13 二次修订）

**修订史**：

1. **首版**把「从相册选择 / 打开 ARW / 相机直连」三张卡片平铺在首屏 → 首页读起来像一张**表单**：
   三个等价选项谁都不比谁重要，用户每次打开都要重新在三个平等选项里做决定。**卡片平铺 ≠ 高级感。**
2. **第二版**改成「展示优先」（作品位 + 2×2 规格表 + 工程信息折叠区）→ 用户反馈
   **「首页介绍不对，删了吧，极简风格」**。规格罗列与设备参数出现在首屏，会被读成
   **产品说明书**；而首屏应该是**作品**的位置。

**当前形态（极简）**：整屏只有「一个展示位 + 一个动作」。

| 层 | 内容 | 说明 |
|---|---|---|
| 顶栏（**不随滚动**） | 左「调色台」+ 右「＋」 | 主操作在任何滚动位置都一点即达（§1.4 信条 1） |
| 展示位（`weight(1f)` 占满余下高度） | 玻璃底 + 极淡品牌光晕（`colorScheme.primary` α .18 径向）+ 居中「＋」；**整块可点** | 与右上角「＋」**同一语义**（都开 Sheet）；不做第二个语义不同的入口 |
| 「＋」Sheet | 相册 → ARW → 连接相机（顺序 = 推荐度） | 三项都是 `ActionTile`，不用并列实心 Button（会铺满强调色） |

**三条刻意决定**：

1. **首页不放任何说明性文字**。「开始一张新的作品」「这台设备能做什么」这类句子是
   **产品在解释自己** —— 一个「＋」已经足够表达「从这里开始」，不需要再说一遍。
2. **删除 2×2 规格表与工程信息折叠区**。设备能力仍在需要处直接计算（相机 Sheet 要用
   `ResolutionProfile.fullResLongEdge`），只是**不再往首屏摆**。排障类信息若仍需一个可达位置，
   应放**设置页**，不是首屏。
3. **不做「最近作品」缩略图** —— 需要持久化历史，现阶段没有数据源（相册历史已排到 P3）。
   造假数据或空壳比不做更糟。

**相机面板移到 Sheet 的副作用（必须知道）**：`CameraPanel` 用 `DisposableEffect` 兜底释放 USB，
批量任务挂在它自己的 `rememberCoroutineScope()` 上 ⇒ **关闭 Sheet = 释放 USB 会话 + 取消批量任务**。
这在量级上与改动前相同（旧版放在 `LazyColumn` 的 item 里，滑出屏幕同样 dispose），
但从「不确定何时被回收」变成「一个明确的位置」。Sheet 内有常驻提示讲这件事。
**`CameraPanel` 本次零改动**（其 token 迁移是独立待办）。

### 3.3 导航外形

**App Shell** = 底部悬浮玻璃 TabBar + 页面容器。主流程 `导入 → 编辑器（全屏） → 导出 sheet`。

| 方案 | Tab 数 | 内容 | 取舍 |
|---|---|---|---|
| **A（推荐）** | **2** | `调色台` / `设置` | 页面数最少（= 现状 + 1），**零空 Tab**，改动风险最低 |
| B（预留） | 3 | `调色台` / `相册历史` / `设置` | 为 P3 预留位置，但现阶段无数据源 → 空壳 |

**推荐 A**：等 P3 有真实数据（待处理/已处理/失败列表）再把 `相册历史` 提为第三个 Tab。
届时 TabBar 对齐 iOS 26 行为：**下滚收缩为窄条、上滚展开、进编辑器整体隐藏**。

所有二级面板统一为 `ModalBottomSheet`（圆角 28dp + scrim），不再内联展开。

### 3.4 转场
- Tab 间切换：淡入淡出 + 轻微位移（Tab 是平级的，不做左右推）。
- 进入编辑器：**共享元素**，调色台缩略图放大为预览区（`SharedTransitionLayout`，Compose 1.7+）。
- 退出编辑器：反向共享元素 + 底栏从下方浮入。
- Sheet 开合：`ModalBottomSheet` 自带 spring；**scrim 不做模糊**（省 GPU）。

---

## 4. 编辑器界面结构

自顶向下四层浮动结构：

| 层 | 高度 | 内容 |
|---|---|---|
| ① 玻璃顶栏 | 56dp | 返回 / 撤销 / 重做 / **原图对比** / 导出 |
| ② 预览区 | ≥55% 屏高 | 图片 + 双指缩放；液化、蒙版笔刷直接绘制在图上 |
| ③ 悬浮玻璃工具条 | 44dp，圆角胶囊 | 分段：人像 / 调色 / 曲线 / LUT / 导出；选中项指示块滑动 |
| ④ 参数玻璃卡 | 可拖拽展开/收起 | 圆角 28dp；滑块 + 数值气泡 |

### 4.1 对现有控件的替换关系

| 现有实现（`EditorScreen.kt`） | 目标形态 |
|---|---|
| `Column` + `height(300.dp)` 预览 | 弹性预览区，占屏高 ≥55%，随抽屉开合改变高度 |
| `FilterChip` 工具选择 | 玻璃分段控件（`工具条指示块`） |
| `Slider` + 旁边 `Text` 数值 | 玻璃轨道滑块 + 拖动时弹出的数值气泡 |
| `Switch`（自动蒙版） | 保留 Switch，加选中动画 + 触感反馈 |
| `autoMaskNote` / `liquifyNote` 纯文本回显 | 预览区上的**胶囊提示条**（2.5s 自动淡出） |
| `PresetRow` / `LutSelector` 文字列表 | 横向滚动玻璃卡带，卡片内嵌缩略图 |
| `OutlinedCard` 分组 | 玻璃卡 + 圆角 20dp + 描边分层 |
| 导出按钮内联在底部 | 移入底部 sheet |

### 4.2 状态收敛（强烈建议先做）
`EditorScreen` 目前约 40 个参数全部由 `AppRoot` 逐项传递。
在动 UI 之前先抽 `EditorUiState` data class（含 `retouch` / `params` / 各种 note），
`AppRoot → EditorScreen(uiState, onEvent)` 单向数据流。
**收益**：后续每次改 UI 的回归面从「40 个参数逐个核对」降到「一个 state 对象」。

### 4.3 工具栏的三级分类展示

现状是**一条超长纵向 `Column`**：人像精修 + 基础调色 + 曲线 + LUT + 导出全部一次铺开 ——
滚动很长、目标找不到，且与「预览区 ≥55% 屏高」直接冲突。改为**三级递进**：

| 级别 | 形态 | 上限 | 内容 |
|---|---|---|---|
| 一级 | 悬浮玻璃工具条上的**分段控件** | ≤5 | `人像` / `调色` / `曲线` / `LUT` / `预设`；选中项由玻璃指示块滑动标记 |
| 二级 | 参数面板内的 **chip 行** | 每项 ≤4 | `人像` → 皮肤 / 瑕疵 / 美型 / 色调迁移；`调色` → 色彩 / 白平衡 / 明暗 |
| 三级 | **滑块 + 数值气泡** | — | 拖动时数值放大弹出，长按归零 |

切一级时面板内容**交叉淡入**（不做左右推）；切二级只替换 chip 行下方的内容。
`曲线` / `LUT` / `预设` **无二级**，直接进参数面板。

参数归属表（现有控件 → 落位）：

| 现有控件 | 一级 | 二级 |
|---|---|---|
| `tool` chips（none/skin/blemish）、皮肤/瑕疵滑块、`AutoMaskRow` | 人像 | 皮肤 / 瑕疵 |
| `slimFace` / `slimJaw` / `eyeEnlarge` + `liquifyNote` | 人像 | 美型 |
| `ColorTransferRow` | 人像 | 色调迁移 |
| `exposure` / `contrast` / `saturation` | 调色 | 色彩 |
| `temperature` / `tint` | 调色 | 白平衡 |
| `shadows` / `highlights` | 调色 | 明暗 |
| `CurveRow` | 曲线 | 无二级（单页） |
| `LUT intensity` + `LutSelector` | LUT | 无二级（单页） |
| `PresetRow` / `activePresetId` | 预设 | 无二级（列表） |
| 导出格式 / 位深 / 导出按钮 | 顶栏「导出」→ Sheet | — |

**两个刻意的设计决定**：

1. **导出按钮不占工具条**，常驻玻璃顶栏右侧。导出是贯穿全程的意图，不该混进
   「正在调什么」的分段里，也避免用户找不到出口。
2. **预设提升为一级**。预设是**跨工具**的（一套预设可能同时改人像 + 调色 + LUT），
   塞进任何一个一级项都会语义错位。
   顺带把 `liquifyNote` / `autoMaskNote` 这类回显从面板里挪走，统一改为预览区上的
   **胶囊提示**（2.5s 自动淡出），不再占面板空间。

---

## 5. 动效规范

**总规矩：曲线按「动的是什么属性」挑，不按「快 / 慢」挑**（§1.6 / §1.6.1）。

| 动的属性 | 用哪个 | 参数 |
|---|---|---|
| 位移 / 尺寸 / 形状 | 空间族弹簧 | `dampingRatio = 0.6`。控件内小位移 → `springSpatialFast()`；格位 / 容器级 → `springSpatial()` |
| 颜色 | 效果族弹簧 | `dampingRatio = 1.0`（临界阻尼，`springEffects()`）——回弹会让颜色越过目标色再弹回，看着发脏 |
| 透明度 | `tween` + `Motion.durationFor()` | 手势驱动、时长必须确定，且必须能被无障碍开关坍缩为 0 |
| 按下反馈 | 空间族最硬一档 | `pressedScale = 0.97` |

> ⚠️ **绝不把回弹弹簧用在透明度上**：alpha 越过 1 之后被裁掉，观感是「闪一下」，比不做动画更糟。

| 场景 | 动效 | 实现 |
|---|---|---|
| Tab 切换 | 淡入 + 轻放大 0.98（**不做左右推**） | `AnimatedContent` + `fadeIn(tween(durationFor(base)))` + `scaleIn(springSpatial())` |
| 工具条 / TabBar 选中 | 指示块横向滑动 | `animateDpAsState` + `springSpatial()` |
| **拖拽参数（重点）** | **非参数 UI 整体淡出**，只留照片 + 正在动的滑块；松手立即恢复 | `AnimatedVisibility`（顶栏收高度）+ `alpha`（工具条只淡出、不移动） |
| 分类切换 | 面板内容淡换 | `Crossfade(tween(durationFor(base), easingOut))` |
| 滑块拖动 | 数值变强调色 + `1.12×` 放大 + 胶囊底 | `animateColorAsState(springEffects())` + `animateFloatAsState(springSpatialFast())` |
| 按下按钮 / 卡片 | 缩放至 0.97 | `animateFloatAsState(springSpatialFast())` + `indication = null` |
| 胶囊提示 | 浮在预览上，3.2s 后自行淡出 | `AnimatedVisibility` + `fadeIn/fadeOut(tween(durationFor(...)))` |
| 导出 | 导出去向由 Sheet 承载（点按即出，不加飞入动效） | `ExportSheet` |

**刻意没做的两项**（都在 §1.6.1 记了理由）：

- `SharedTransitionLayout`（导入 → 编辑的共享元素放大）：编辑器是硬切进入、退出时**立刻回收源位图**，
  套转场会出现「画到已回收 Bitmap」；收益（一次转场）远小于风险。
- TabBar 随滚动收缩高度：当前 TabBar 只有 2 项且页面内容都不长，加上去等于为一个不存在的场景写代码。

---

## 6. 无障碨与降级

| 场景 | 处理 | Android 可实现性 |
|---|---|---|
| 系统「移除动画」（`ANIMATOR_DURATION_SCALE == 0`） | 关闭全部位移类动画，时长档坍缩为 0 | ✅ `ValueAnimator.areAnimatorsEnabled()`，已封装为 `Motion.reduceMotion()` / `Motion.durationFor()` |
| 系统「高对比度文字」 | 玻璃切**实心底**（`Glass.of(opaque = true)`），描边加强 | ✅ `Settings.Secure.high_text_contrast_enabled` |
| ~~系统「降低透明度」~~ | ⚠️ **Android 没有这个公开 API**（那是 iOS 专有设置）。改为**应用内开关**「降低透明度」，与上一行联动 | ❌ 无系统 API |
| 设备不支持 `RenderEffect`（API < 31 或低端 GPU） | 玻璃降级为「半透明纯色 + 描边」 | ✅ minSdk 36 实际不会命中，保留兜底 |

> **更正**：初稿把 iOS 的「降低透明度」当成 Android 系统设置写进了降级策略，实际不成立，已改为应用内开关。

### 玻璃性能护栏（最重要的一条）
实时模糊在大图上极其昂贵。**只对导入时一次性生成的「模糊底图」做模糊**，
滚动、拖动、切页签时只做位移/透明度合成，绝不重算 blur。
若实测掉帧，大面积区域（预览区外框、整页背景）直接退回半透明纯色。

---

## 7. 落地顺序（2026-09-13 定稿）

用户决策：**先 UI 后能力** —— 第一轮把**所有页面的 UI 一次做完**，再做细节与动效，最后做渲染性能；
「预设库」（§1.5 的 B 档）排在这三件事之后，**单独立阶段**。

| 阶段 | 范围 | 状态 |
|---|---|---|
| **UI-1 基础层** | `ui/theme/` 新增 `Spacing` / `Radius` / `Motion` / `Glass`；`Color.kt` 补灰阶与工作台深色；`Type.kt` 补 caption 11sp；`Theme.kt` 加 `PixelCakeWorkspaceTheme`；`ui/components/GlassCard.kt`。**零业务改动** | ✅ 已提交 `ca22e91` |
| **UI-2 外壳与页面** | `ui/shell/AppShell.kt`（2 Tab：调色台 / 设置 + 玻璃 TabBar + 转场）；调色台首页（空态即导入）；`ui/settings/SettingsScreen.kt` + 关于 Sheet；`ui/components/GlassSegmentedBar.kt`（TabBar 与编辑器工具条共用） | ✅ 已落地 |
| **UI-3 编辑器** | 四段式布局（顶栏 / 预览 / 悬浮工具条 / 参数卡）；三级工具条；各 Sheet | ✅ 已落地（范围有调整，见 §7.2） |
| **UI-4 细节与动效** | §5 动效表逐项落地 + 无障碍降级（`Motion.reduceMotion()`） | ✅ 已落地（见 §7.3） |
| **UI-5 渲染性能** | 静态模糊底图、列表稳定 key、真机（一加15）帧率实测与降级 | 🔄 底图已落地；真机帧率实测待做 |
| （后续）预设库 | §1.5 的 B 档：`Presets` 由 `object` 改为可扩展库 + 持久化，编辑成果可喂批量 / NAS | ⏸ 排后 |

> **范围提醒**：UI-1 ~ UI-3 **只动 `ui/**` 与 `MainActivity`**；`core/edit/**`、`core/camera/**`、`ml/**`
> 一律不碰 —— 保护已绿 CI 的 P1p-2c 与待真机验收的 P2 / P1p-1c。
> 唯一例外是 `gradle/libs.versions.toml` + `app/build.gradle.kts` 新增
> `androidx.compose.animation:animation` 依赖（动效需要，BOM 管版本）。

### 7.1 UI-1 实际交付的文件

| 文件 | 内容 |
|---|---|
| `ui/theme/Spacing.kt` | 7 档间距 token + 语义名（`page` / `cardInner` / `cardGap` / `sectionGap` / `controlHeight`） |
| `ui/theme/Radius.kt` | 4 档圆角阶梯 `chip`/`card`/`sheet`/`shell` + `pill` |
| `ui/theme/Motion.kt` | 时长档 `fast`/`base`/`slow`、`easingOut`/`easingIn`、`springSoft`/`springSnappy`、`pressedScale`、`reduceMotion()`、`durationFor()`、`Modifier.pressScale()` |
| `ui/theme/Glass.kt` | `GlassTint`、`Glass.of()`、`Modifier.glassSurface()`、`LocalLowTransparency` |
| `ui/theme/Backdrop.kt` | `blurredBackdrop()`：静态模糊底图（见 §7.4） |
| `ui/theme/Color.kt` | 工作台深色 3 值 + 灰阶 6 级 + 玻璃描边/底色 |
| `ui/theme/Theme.kt` | `PixelCakeWorkspaceTheme`（编辑页恒深色） |
| `ui/theme/Type.kt` | `labelSmall` 11sp —— 字号封顶 5 级 |
| `ui/components/GlassCard.kt` | `GlassCard` / `SectionHeader` / `CapsuleNote` |
| `ui/components/GlassSegmentedBar.kt` | 泛型分段玻璃条（TabBar 与编辑器一级工具条共用） |
| `ui/components/GlassChipRow.kt` | 横向滚动 chip 行（统一 `Radius.chip`） |
| `ui/components/ParamSlider.kt` | 参数滑块 + 拖动状态上报 |
| `ui/components/ActionTile.kt` | 动作卡（按下缩放，替代实心 Button） |

### 7.2 UI-3 与原计划的偏差（刻意缩小范围）

| 原计划 | 实际做法 | 理由 |
|---|---|---|
| 导出 · LUT · 曲线 · 相机连接 **四个 Sheet** | **只做导出 Sheet**。曲线 / LUT 直接作为一级分类的面板内容；相机连接保持调色台上的卡片 | 曲线与 LUT **本身就是一级分类**（§4.3），再弹一层 Sheet 是同一概念套两层壳；相机面板已有可用实现，改成 Sheet 属于纯搬运。判据是「内容量够不够独占一屏」，不是「概念上够不够独立」 |
| 抽 `EditorUiState`（§4.2） | **未做**，`AppRoot` 状态保持原样 | 用户定的顺序是「先 UI 后能力」，状态收敛是**架构**不是 UI；且它要动 `MainActivity` 里那条已通过真机验收的渲染协程，风险与收益不成比例。留在 UI 之后的独立阶段 |
| `GlassToolbar.kt` | 落到 `ui/editor/EditorToolbar.kt` + 共用 `GlassSegmentedBar` | 一级工具条与 TabBar 行为完全一致，分成两个文件必然漂移 |
| 气泡滑块（跟着拇指飘） | 数值做成**标签行右侧的胶囊**，拖动时变强调色 + 轻微放大 | 跟着拇指走要依赖 Material3 `Slider` **未公开**的轨道内边距才能对齐，一改版就偏位。见 `ParamSlider` 文件头 |

### 7.3 UI-4 实际落地项

| 动效 | 落地位置 |
|---|---|
| 拖动时隐藏非参数 UI | `EditorScreen`：顶栏 `AnimatedVisibility` 收起（把高度让给预览）；工具条**只做透明度淡出**（收掉高度会让参数面板下移 44dp，手指按着的滑块会在拖动中跑掉） |
| 参数值变化反馈 | `ParamSlider`：拖动中数值变强调色 + `1.12×` 放大 + 胶囊底 |
| 分类切换淡换 | `EditorScreen`：`Crossfade(tween(Motion.durationFor(base)))` |
| Tab 转场 | `AppShell`：淡入 + 轻放大（**不做左右推** —— 左右推是层级导航语意，用在平级 Tab 上会让人「迷路」） |
| 指示块滑动 | `GlassSegmentedBar` / `GlassTabBar`：`animateDpAsState` + `springSpatial` |
| 按下缩放 | `Modifier.pressScale()`（0.97）+ `indication = null`（避免缩放与涟漪两层反馈叠加） |
| 无障碍降级 | `Motion.reduceMotion()` 读 `ValueAnimator.areAnimatorsEnabled()`（API 26+，minSdk 36） |
| 状态说明不再占面板 | `CapsuleNote` 浮在预览上，3.2s 后自动淡出（`autoMaskNote` / `liquifyNote`） |

### 7.4 UI-5 静态模糊底图（已落地）

**做法**：照片 → 缩到 28px 宽（`Canvas` 画进**全新**位图，避免 `createScaledBitmap` 在尺寸相同时返回同一实例而就地改掉用户照片）→ 两轮半径 1 的**可分离盒式模糊**（边缘 clamp，否则四周出现黑边）→ 拉伸铺满（`FilterQuality.Low`，约 38× 放大，双线性本身就完成了大部分「模糊」）。

**为什么这不是「实时 backdrop blur」**：整条链只在 `remember(original)` 里跑一次 —— 换图才算一次，拖动 / 滚动 / 切分类**零重算**。28×19 的小图，两轮模糊共约 6k 次运算。

**不透明度 0.26**：底图的作用是给玻璃层提供可透出的色彩信息，不是当壁纸；调高会让界面自身变彩色，与「照片是唯一彩色主体」冲突。

**顺带修掉的一个真 bug**：编辑页原来**没有自己铺底**——外层 `Surface` 位于 `PixelCakeWorkspaceTheme` **之外**，浅色模式下会在编辑页背后画浅灰底，出现「照片周围一圈浅色」。现在 `EditorScreen` 自己 `background(colorScheme.background)`（在 Workspace 主题内 = `WorkspaceBg`）。

**待办（真机）**：一加15 上实测帧率；若大面积区域掉帧，把参数面板降级为实心（`GlassCard` 默认已是实心容器；玻璃浮层可由 `LocalLowTransparency` 一键切换）。

### 7.5 UI-4b 趋势对齐（弹簧分族 / 深色强调色 / 玻璃收敛）

在 UI-4 之后追加的一轮，内容与理由见 §1.6.1。落地清单：

| 文件 | 改动 |
|---|---|
| `ui/theme/Motion.kt` | 删 `springSoft` / `springSnappy`；新增 `springSpatialFast` / `springSpatial` / `springEffects`；`pressScale` 改走 `springSpatialFast` |
| `ui/theme/Color.kt` | 新增 `SeedOnDark`；`NeutralSurface` 调为 `#F0F0F3`（拉开卡片明度差）；新增容器色阶 5 档 × 2 主题 + 容器描边 2 值；删除已被容器色阶取代的 `WorkspaceSurface` / `WorkspaceSurfaceHigh` |
| `ui/theme/Glass.kt` | 新增 `ContainerLevel` + `ContainerLevel.containerColor()` + `Modifier.containerSurface()`；实心降级档改用 `ContainerDarkHigh` |
| `ui/theme/Theme.kt` | 深色 / 工作台 `primary` → `SeedOnDark`；surface 家族 → 容器色阶 |
| `ui/components/GlassCard.kt` | 新增 `CardMaterial` 枚举，**默认实心容器**；移除 `opaque` 参数 |
| `ui/components/ParamSlider.kt` | 颜色 → `springEffects`；缩放 → `springSpatialFast`；`Seed` → `colorScheme.primary` |
| `ui/components/GlassSegmentedBar.kt` | 指示块 → `springSpatial`；`Seed` → `colorScheme.primary` |
| `ui/components/ActionTile.kt` | `Seed` → `colorScheme.primary` |
| `ui/shell/AppShell.kt` | 缩放 / 指示块 → `springSpatial`；`Seed` → `colorScheme.primary`；2 处 `tween` 补 `durationFor` |
| `ui/editor/EditorScreen.kt` | 5 处 `tween` 补 `durationFor`（修「系统关了动画 App 仍在动」） |
| `ui/home/HomeScreen.kt` | 展示位光晕 → `colorScheme.primary`；状态卡显式 `CardMaterial.Glass` |
| `ui/settings/SettingsScreen.kt` | 6 张卡自动落到实心容器（**无需改调用点**）；注释同步 |

> 这一轮的 diff **仍然只落在 `ui/**`**，`core/edit/**`、`core/camera/**`、`ml/**` 零改动。

---

## 8. 待拍板决策点

| # | 决策 | 推荐 |
|---|---|---|
| 1 | 编辑页是否**强制深色工作台**（不随系统主题）？ | **是**。照片是唯一彩色主体，浅色外壳会干扰判色 |
| 2 | Tab 数：2 个（调色台 / 设置）还是 3 个（含相册历史）？ | **2 个**（方案 A）。`相册历史` 等 P3 有数据源再加 |
| 3 | 玻璃强度：真模糊 vs 半透明+描边？ | **不用真模糊**：玻璃永远浮在一张**静止**的预览图上，实时 blur 代价高而收益为零 → 半透明 + 1px 高光描边，质感交给导入时一次性生成的静态模糊底图（§7.4）。**已拍板** |
| 4 | 是否先做 `EditorUiState` 状态收敛？ | **是**，且必须排在 UI-2 之前 |
| 5 | 导出 / 关于 / 相机连接：Sheet 还是独立页面？ | **Sheet**（见 §3.2） |
| 6 | 「预设」是否作为独立一级工具？ | **是**。预设跨工具，做平级分段最自然（见 §4.3） |
| 7 | 「审美先行式」取到哪一档（A 缩略图 / B 预设枢纽 / C 社区）？ | **A + B**，且 B **单独立阶段**，不混进 UI 的 diff（见 §1.5） |

---

## 9. 文件地图（实际改动，✅ 全部已落地）

```
app/src/main/java/com/hifn/pixelcake/
├── ui/theme/
│   ├── Spacing.kt         [新增] 7 档间距 + 语义名
│   ├── Radius.kt          [新增] 圆角阶梯常量
│   ├── Glass.kt           [新增] 玻璃材质 + 实心容器（ContainerLevel / containerSurface）+ LocalLowTransparency + LocalDarkTheme（生效主题）
│   ├── Backdrop.kt        [新增] 静态模糊底图（缩略 + 盒式模糊 + 拉伸）
│   ├── Motion.kt          [新增] 弹簧分族（springSpatialFast / springSpatial / springEffects）+ tween 档 + pressScale
│   ├── Color.kt           [修改] 工作台深色 + 灰阶 6 级 + 玻璃色 + SeedOnDark + 容器色阶 5×2
│   ├── Theme.kt           [修改] PixelCakeWorkspaceTheme（恒深色）+ 两个主题均下发 LocalDarkTheme
│   └── Type.kt            [修改] 11sp caption；bodySmall 别名到 caption
├── ui/components/
│   ├── GlassCard.kt        [新增] 通用卡片（默认实心容器，CardMaterial 可切玻璃）/ SectionHeader / CapsuleNote
│   ├── GlassSegmentedBar.kt[新增] 泛型分段玻璃条（TabBar 与工具条共用）
│   ├── GlassChipRow.kt     [新增] 横向滚动 chip 行
│   ├── PresetThumbRow.kt   [新增] 预设缩略图行（A 档：真实缩略图，选中态 2px 强调描边）
│   ├── GlassCircleButton.kt[新增] 圆形玻璃按钮（顶栏「＋」）
│   ├── ImportSheet.kt      [新增] 「＋」唤起的开始 Sheet（相册 / ARW / 相机）
│   ├── ParamSlider.kt      [新增] 参数滑块 + 拖动状态上报
│   └── ActionTile.kt       [新增] 动作卡（按下缩放，替代实心 Button）
├── ui/shell/
│   └── AppShell.kt         [新增] 2-Tab 外壳 + 玻璃 TabBar + 页面转场
├── ui/editor/
│   ├── EditorScreen.kt     [重构] 四段式布局 + 静态模糊底图 + 拖动隐藏
│   ├── EditorToolbar.kt    [新增] 一级工具条（复用 GlassSegmentedBar）
│   ├── ParamPanel.kt       [新增] 按分类渲染的参数卡（预设分类走 PresetThumbRow 缩略图）
│   └── ExportSheet.kt      [新增] 导出 Sheet
├── ui/settings/
│   ├── SettingsScreen.kt   [新增] 设置页
│   └── AboutSheet.kt       [新增] 关于 Sheet（含开源组件署名）
├── ui/home/
│   ├── HomeScreen.kt       [重构] 「调色台」：极简（展示位 + 右上角「＋」）
│   ├── CameraSheet.kt      [新增] 相机面板的模态容器
│   └── CameraPanel.kt      [未动] P2 直连逻辑，token 迁移是独立待办
└── MainActivity.kt         [修改] 接入 AppShell（2 Tab），编辑器全屏置于外壳之外；预设缩略图生成 + 渲染监听含半径参数
```

**没有落地的两项**（刻意，见 §7.2）：`ui/editor/EditorUiState.kt`（状态收敛属「能力」阶段）、
`GlassToolbar.kt`（并入 `EditorToolbar.kt` + 共用 `GlassSegmentedBar`）。

**约束**：本次改造**只动 UI 层**，`core/edit/**`、`core/camera/**`、`ml/**` 一律不碰，
以免影响已通过 CI 的 P1p-2c 与待真机验收的 P2/P1p-1c。

---

## 10. 静态预览

`docs/ui_preview.html` —— 按上述 token 真值复刻的四个界面静态预览（调色台 / 编辑器 / 拖动中 / 设置+关于）
与 token 表、验收清单自查。用于在无法本地构建的情况下评审版式与密度；真实动效与手势仍需真机核对。
