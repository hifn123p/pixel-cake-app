# PixelCake UI 设计审计报告

**审计日期**：2026-11-21
**审计范围**：仅 UI 设计（布局 / 视觉层级 / 配色可读性 / 触控目标 / 适配 / 无障碍），**不含功能正确性**
**审计依据**：`app/src/main/java/com/hifn/pixelcake/ui/**` 静态代码审阅 + `docs/UI_DESIGN.md`（UI 3.0「Aperture」）+ `audit-art/shots/` 真机截图交叉核对
**审计基线**：`main` 工作树，121 个 `.kt`，UI 相关约 34 个文件
**方法说明**：本次由主代理直接逐文件审阅。并行辅助审计代理因 API 用量限流（429）中途失败，**其结果未被采用，本报告不含其任何结论**。

---

## 一、总体评价

整体观感是**克制、统一、有明确设计语言**的，明显高于一般工具类 App 的平均线。做得好的地方：

1. **Token 体系完整且被遵守**：`Spacing` / `Radius` / `Type` / `Color` 都是具名 token，`ui/` 里几乎搜不到裸 dp 间距；圆角严格走 12 / 20 / 24 / 28 + 胶囊五档。
2. **材质语义清晰**：`GlassCard` 默认实心容器、玻璃只用于浮层，符合「玻璃是点缀不是语言」的现代共识；`CardMaterial` / `ContainerLevel` 把这个判据做进了 API。
3. **图标同源**：`AppIcons` 用同一套 24×24 线性 PathData（1.7 圆头），无 material-icons 混搭，线条粗细与端点完全一致。
4. **状态表达有双编码**：预设缩略图「描边 + 对勾徽章」、Rail「底色 + 图标色 + 文字色」、导出回执「按钮形态 + 状态色」，不依赖单一通道。
5. **无障碍意识强**：`selectable(role=Role.Tab)`、整行 `toggleable(role=Role.Switch)`、图标按钮的 `contentDescription`、`inertUnless` 隐身即不可点——这些是多数项目会漏的。
6. **动效纪律**：空间/效果弹簧分族、`tween` 补 `durationFor`（系统关动画时不误动）、按下缩放 0.97，符合 M3 Expressive 取向。

**问题密度不高，但有 2 个会直接破坏首屏观感的布局缺陷，以及一组系统性的对比度 / 触控目标不达标**。以下按严重程度排序。

---

## 二、P0 —— 会直接破坏画面（应先修）

### P0-1「继续上次编辑」胶囊钉在屏幕左上角，压住状态栏与标题

**文件**：`app/src/main/java/com/hifn/pixelcake/ui/home/HomeScreen.kt:163-188`

```kotlin
if (hasSavedSession) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.m)          // 只有 12dp 顶部间距
            .clip(Radius.pill)...
```

该 `Row` 位于外层 `Box(Modifier.fillMaxSize())` 内、主 `Column` 之后，**没有 `align(...)`、没有 `statusBarsPadding()`、没有水平 padding**。`Box` 的默认对齐是 `TopStart`，因此它会：

- 叠在状态栏上（edge-to-edge 下内容从 y=0 起画）；
- 与同为顶部的 `HomeTopBar`（标题「调色台」）**重叠**；
- 左右贴边，与顶栏的 `Spacing.page` 边距不齐。

**用户可见影响**：有历史会话时首页顶部出现一条压住标题和系统状态的胶囊，读起来像渲染错误。
**修复**：给它 `align(Alignment.TopCenter)` 或放进 `Column` 中作为 HeroCard 之上的兄弟节点，同时补 `statusBarsPadding()` + `padding(horizontal = Spacing.page)`。

### P0-2 相机直连 Sheet 内容不可滚动，照片一多即被裁掉

**文件**：
- `ui/home/CameraSheet.kt:74-92`（`Column` 无 `verticalScroll` / `LazyColumn`）
- `ui/home/CameraPanel.kt:699`（`for (photo in list.photos)` 逐行渲染全部照片）

`CameraSheet` 的 `Column` 只有 `fillMaxWidth().padding(...)`；`CameraPanel` 在其中一次性堆叠：说明文案 → 检测按钮 → 设备单选 → 握手按钮 → 系留开关/chips → 参数行 → 刷新/断开 → 照片摘要 → **全部照片行（每行 44dp）** → 批量预设横滚 → 批量按钮 → 进度 → 日志。

**用户可见影响**：卡内照片超过十几张时，下方的批量按钮与日志**永远到不了**（ModalBottomSheet 不会替内容滚动）。
**修复**：`CameraSheet` 的 `Column` 换成 `LazyColumn`（照片行做 `items`），或至少包 `verticalScroll`。

---

## 三、P1 —— 明显不合理，影响可读性 / 可点性

### P1-1 首页成片卡上的白字压在亮金色渐变上，对比度不足

**文件**：`ui/home/HomeScreen.kt:325-335, 366-404`

文本块用 `Arrangement.Bottom` 锚在卡片底部，而底部渐变停靠点是 `HeroCardBottom #E9A25C` → `HeroCardGlow #F2C078`：

| 元素 | 字号 | 颜色 | 底色 | 对比度 |
|---|---|---|---|---|
| 「开始一次修图」 | titleMedium 20sp | `Color.White` | #E9A25C | **2.15 : 1** |
| 副标题 | labelSmall 11sp | White @85% | #E9A25C | **≈2.0 : 1** |
| 三个入口标签 | labelSmall 11sp | White | 半透明黑胶囊 | ≈4.0 : 1（边缘） |

WCAG AA 要求小字 4.5:1、大字 3:1，三项均不达标。真机截图 `01_home.png` 中副标题确实发灰、几乎读不出。
**修复**：底部文案区加一层局部暗色遮罩（如 `Brush.verticalGradient` 从 `#00000000` 到 `#00000099`），或把文字区换成 `Ink` 深色并压在浅色胶囊上。

### P1-2 浅色主题的琥珀 `Seed #B8791A` 作小字不达标

**文件**：`ui/theme/Color.kt:20`、`ui/theme/Theme.kt:24-25`、调用点如 `ui/settings/AboutSheet.kt:130`（`color = MaterialTheme.colorScheme.primary` + labelSmall）

| 组合 | 对比度 | 要求 |
|---|---|---|
| `#B8791A` on `#F4F1EA`（正文/标签） | **3.22 : 1** | 4.5 : 1 |
| White on `#B8791A`（Button 文字） | **3.63 : 1** | 4.5 : 1 |
| `#B8791A` on `#F6E7CB`（primaryContainer） | 2.97 : 1 | 4.5 : 1 |

浅色下凡是以 `colorScheme.primary` 作**文字**的地方（关于页许可协议、首页「继续上次编辑」、选中 chip 标签）都不达标。深色主题的 `SeedOnDark #FFB454` 反而是合规的。
**修复**：为浅色主题单独给一个「强调文字色」（更深的琥珀，如 `#8A5A0B` 系），与用于图形/填充的 `Seed` 分开；或把浅色 primary 整体压深。

### P1-3 编辑器 Rail 未选中标签对比度 2.97 : 1

**文件**：`ui/editor/Rail.kt:203-204`

```kotlin
val contentColor = if (selected) MaterialTheme.colorScheme.primary
else MaterialTheme.colorScheme.outline      // WorkspaceColors.outline = #6B6963
```

`#6B6963` on `#20201E` = **2.97 : 1**，而 `labelSmall` 是 11sp 小字（要求 4.5:1）。注释里刻意选 `outline` 以避免「像次要标题」，但代价是**六个工具里五个的标签都读不清**（截图 `61b_session_restored` 可见未选中项明显发暗）。
**修复**：改用 `onSurfaceVariant`（#A5A29A，6.4:1），用字重/透明度表达层级而不是靠压暗对比度。

### P1-4 预设缩略图选中对勾：白字压琥珀底，1.76 : 1

**文件**：`ui/components/PresetThumbRow.kt:180-196`

```kotlin
.background(accent)              // #FFB454（深色主题）
Text("✓", color = Color.White)   // 1.76 : 1
```
**修复**：`color = MaterialTheme.colorScheme.onPrimary`（#3A2A05，7.87:1），与 `ExportSheet` 主按钮同口径。

### P1-5 参数滑块的 ±/− 步进钮只有 34dp，低于 48dp 触控下限

**文件**：`ui/components/ParamSlider.kt:156-177, 296-317`

两侧 `.size(34.dp)` 的 `Box` 是真实点击热区。KDoc 写「触控 = 整个 56dp 盒」只对**中间滑杆**成立，两侧步进钮仍是 34dp。项目自己的 `Spacing.controlHeight = 48.dp` 也写明「可点击控件的最小高度」。
**修复**：热区扩到 48dp（视觉圆点可保持 34dp，用 `Modifier.size(48.dp).wrapContentSize(Alignment.Center)` 分离视觉与命中区）。

### P1-6 顶栏图标按钮 40dp + 相邻无缝隙

**文件**：`ui/editor/EditorScreen.kt:1985`（`IconAction` `.size(40.dp)`）

注释承认「44 仍放不下」并把问题推给「相邻间距补偿」，但这些按钮**之间并没有加间距**（`Row` 无 `Arrangement.spacedBy`），补偿不成立。同样的 44dp 还出现在 `ui/home/HomeScreen.kt:228`（设置按钮）。
**修复**：视觉图标 20dp，命中区扩到 48dp 并显式留 8dp 间距；或把低频动作（历史 / 自动增强）收进溢出菜单，给高频动作腾出 48dp。

### P1-7 设置页 chip 组不换行也不横滚，自定义预设一多就溢出

**文件**：`ui/settings/SettingsScreen.kt:703-715`（`ChipSelector`）

```kotlin
Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
    options.forEach { FilterChip(...) }
}
```
`cameraPresetOptions` 会拼上用户自定义预设（数量无上限），`Row` 既不 `FlowRow` 也不 `horizontalScroll` ⇒ 超出屏幕的部分直接不可见、不可达。
**修复**：换 `FlowRow`（与 `GlassChipRow` 默认形态一致），或与编辑器一致的「单行 + 横滚 + 渐隐提示」。

### P1-8 提示文案里的 `**` 被原样渲染

**文件**：`ui/editor/ParamPanel.kt:672`

```kotlin
Hint("…层是**叠加量**：作用在整图调色之后…")
```
`Hint()` 内部是普通 `Text`，Compose 不解析 Markdown，用户会看到**字面的两个星号**。
**修复**：删掉 `**`，或改用 `AnnotatedString` 加粗。

---

## 四、P2 —— 一致性 / 适配债

| # | 问题 | 位置 | 说明 |
|---|---|---|---|
| P2-1 | `TextButton` 一律 `heightIn(min = 44.dp)` | `SettingsScreen` / `ParamPanel` / `CameraPanel`（20+ 处） | 项目自身 `Spacing.controlHeight = 48.dp`，44dp 既低于自己的 token 也低于 Android 48dp 建议。建议收敛到 `Spacing.controlHeight` |
| P2-2 | `ExportSheet` / `AboutSheet` / `ModelCenterSheet` 的 `Column` 均不可滚动 | `ExportSheet.kt:88`、`AboutSheet.kt:50`、`ModelCenterSheet.kt:52` | 内容较长时（尤其 `fontScale 1.3` / 小屏）底部内容被 Sheet 边缘裁掉。统一加 `verticalScroll` 或改 `LazyColumn` |
| P2-3 | 「导出」胶囊文字宽度硬编码 `width(52.dp)` + `softWrap=false` | `EditorScreen.kt:2050` | 「导出中」3 字在 13sp 正常缩放下约 45dp，`fontScale 1.3` 时约 58dp ⇒ 会被截断。建议去掉固定宽度，改 `wrapContentWidth` + 外层 `IntrinsicSize` 保证整 pill 不被 Row 压缩 |
| P2-4 | 预设缩略图名称胶囊：白字压 55% 黑 | `PresetThumbRow.kt:166-179` | 亮图上 55% 黑的合成底约 #737373，白字 ≈4.0:1（11sp 不达标）。建议黑底提到 65%–72% |
| P2-5 | 人像「自动蒙版」开关行无最小高度 | `ParamPanel.kt:715-733` | 两行文字 + Switch，触控高度随文案行数变化，短文案时不足 48dp |
| P2-6 | 收起态 Deck 展开按钮用 `AppIcons.Reset` | `EditorScreen.kt:1323-1327` | 语义是「展开面板」却画了个环形箭头（重置），图标语义错位。应改 `ChevronUp` / `ExpandLess` 类图标 |
| P2-7 | 一级底部 TabBar 只有 2 项 | `ui/shell/AppShell.kt:49-52` | Android/M3 惯例是 3–5 项；2 项用底部 TabBar 略显空旷，且每项宽度极大（≈170dp）。可考虑顶部 segmented 或保留但收窄 |
| P2-8 | 首页成片卡上半部是纯渐变空场 | `HomeScreen.kt:263-364` | 文案与入口全部锚在底部，卡片约 60% 高度是无信息的渐变 + 一枚 40dp 光圈标。作为「作品位」在没有历史成片时略显空。可在空态放一句引导文案或降低卡片高度占比 |

---

## 五、P3 —— 打磨项

1. `SettingsScreen.kt:452-454` 连续两个相同的 `Spacer(Modifier.height(Spacing.s))`，多出 12dp 死空白。
2. `EditorScreen.kt:157-177` 的 KDoc 长篇论证「S 档 248」，但常量实为 `DECK_HEIGHT_S = 176.dp`（176 是 v0.7.3 合并 deck-top 后的正确值）——注释已腐化，会误导后续改动。
3. `GlassCircleButton.kt` 全仓库无调用点（死组件），默认 40dp 也低于 48dp。要么接线要么删除。
4. `ParamSlider` 的标签行高度写死 22dp，`fontScale 1.3` 下 labelMedium 行高 ≈23.4dp + 数值胶囊内边距，会与 62% 位置的轨道发生轻微视觉贴合；建议标签行改 `heightIn(min=22.dp)`。
5. `CameraPanel.kt:534` 的整行 `toggleable` 文案是「默认修图参数」，但它控制的是系留开关（`tetherOn`）——**这是文案与语义不符**（虽偏功能，但从 UI 文案角度也应修正为「系留自动导出」之类）。

---

## 六、验收清单（修完建议逐条过）

- [ ] 首页有历史会话时，「继续上次编辑」不与状态栏 / 标题重叠，左右与 `Spacing.page` 对齐
- [ ] 相机直连面板在 20+ 张照片时可滚动到底部的批量按钮与日志
- [ ] 首页成片卡副标题在真机上清晰可读（对比度 ≥ 4.5:1）
- [ ] 浅色主题下 `colorScheme.primary` 作文字的地方全部 ≥ 4.5:1
- [ ] Rail 未选中标签在深色工作台上 ≥ 4.5:1
- [ ] 预设缩略图选中对勾 ≥ 4.5:1
- [ ] 所有可点控件命中区 ≥ 48dp（步进钮、顶栏图标、首页设置按钮）
- [ ] 设置页 chip 组在 8+ 个选项时不溢出屏幕
- [ ] 面板/Sheet 在 `fontScale 1.3` + 小屏下不裁切内容
- [ ] 代码里不再出现会被原样渲染的 Markdown 星号

---

## 七、备注

- 本报告**只评审 UI**，未评价任何功能逻辑、渲染管线或性能。
- `audit-art/shots/` 中部分截图（如 `11_deck_*.png`、`25_presets.png`、`51_export_sheet.png`）存在同一占位图被复用的情况，无法作为对应界面的视觉证据；本报告对这些界面的判断以**代码静态证据**为准，并在正文标注。
- 建议优先级：**P0-1 / P0-2 → P1-1 / P1-3 → P1-5 / P1-6 → 其余**。
