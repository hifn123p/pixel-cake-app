---
title: 像素蛋糕 App — 工程笔记（硬约束与踩坑清单）
status: active
created: 2026-10-06
updated: 2026-11-14
project: D:\AI_Project
description: 从本地工作记忆（.workbuddy/memory）提炼的、跨会话持久有效的工程纪律——仓库与工具链约定、推送/CI/发版实操、Compose 布局陷阱、渲染/并发纪律、设置与构建。这些条目每一条都是被真机 bug 或一次返工逼出来的，改动相关代码前请先读完对应小节。
---

# 工程笔记（硬约束与踩坑清单）

> **这份文档的存在理由**：本地工作记忆（`.workbuddy/memory/`）在 `.gitignore` 里，**不入库**。
> 换机器 / 换 IDE 之后那份记忆会断档，于是把**跨会话持久有效**的部分提炼到这里，随仓库一起走。
>
> 阅读顺序建议：**§1 先看**（它决定了「改完谁来编译」这个根本前提），然后按需要跳到 §2~§5；
> §7 起是按版本追加的审查 / 落地台账。
> **本文每一条都是被一次真机 bug 或一轮返工逼出来的**，不是风格偏好 —— 看到「⚠️」请当作硬约束。
>
> 相关文档：[`DEV_PLAN.md`](DEV_PLAN.md)（计划）、[`UI_DESIGN.md`](UI_DESIGN.md)（界面规范 + §4.0.x 修正表）、
> [`TONING_DESIGN.md`](TONING_DESIGN.md)、[`OBJECT_TONE_DESIGN.md`](OBJECT_TONE_DESIGN.md)、
> [`Github_CI.md`](Github_CI.md)（**v0.7.0 发版 CI 报告，历史快照**）。
> ⚠️ 阶段设计存档 `PHASE_DESIGN_HISTORY.md` **不在 `docs/`**：已按文档政策移入本地
> `docs/archive/`（不入库，见其 `README.md`）；代码 KDoc 里的 `F01~F24` / `R01~R10` / `D01~D10`
> 编号仍按原文引用，溯源一律去 `docs/archive/`。此处不再放相对链接（它会指向已删文件）。

## 目录

| 节 | 内容 |
|---|---|
| [§1](#1-仓库与工具链决定了一切前提) | 仓库与工具链（决定了一切前提） |
| [§2](#2-compose-布局硬约束) | Compose 布局硬约束 |
| [§3](#3-渲染与并发纪律) | 渲染与并发纪律 |
| [§4](#4-设置模型与依赖) | 设置、模型与依赖 |
| [§5](#5-构建ci-与发版) | 构建、CI 与发版 |
| [§6](#6-排障姿态三句话) | 排障姿态（三句话） |
| [§7](#7-全量代码审查2026-10-07的产物与新纪律) | 全量代码审查（2026-10-07）的产物与新纪律 |
| [§8](#8-第二轮全量审查2026-10-25的产物与新纪律) | 第二轮全量审查（2026-10-25）的产物与新纪律 |
| §9 | UI 3.0 改版审计（2026-11-01）：规范真值、三次核对、26/48dp 热区冲突 |
| §10 | 分割对比（2026-11-01） |
| §11 | 曲线本体（2026-11-01） |
| §12 | 自适应亮档 + 导出回执 + 历史丝带（2026-11-01） |
| §13 | 外部审计整改批次（v0.6.4） |
| §14 | 审计剩余项清账批次（v0.6.5） |
| §15 | 用户预设库（v0.6.6） |
| §16 | 导航层 / 模型中心 / 预设分享（v0.7.0） |
| §17 | Q5 手动旋转 + v0.7.x CI 排红 + 当前发版状态（v0.7.1） |
| §18 | UI↔接线全量审计批次（v0.7.1 追加轮）：曲线拖动 P1、旋转代次、四类接线缺陷与四条新纪律 |

> §9~§17 为按版本逐节追加，目录不生成锚点；用 `## §N` 全文搜索定位，避免中文锚点失效成为死链。

---

## §1 仓库与工具链（决定了一切前提）

### 1.1 本地没有开发环境 —— **CI 是唯一的编译器**

- 开发机**不装任何 Android 构建环境**（无 SDK / 无 JDK 工具链 / 无 gradle 缓存）。
  编译、单测、Lint、打包**全部**在 GitHub Actions 上跑。
- ⇒ 任何「我改完了」的说法，其真实含义只能是：
  **「文本层面已自洽」**，而不是「能编译」。措辞上不要写「编译通过」。
- ⇒ 本地能做的静态检查只有两条（见 §1.3）：**模板感知的括号配平** + **跨文件 import / 调用点签名核对**。
  配平只能证明语法树闭合，**证明不了名字解析**（函数/属性是否存在、签名是否匹配）。

### 1.2 提交与发版：**当前由助手执行**（用户口头授权），但每一步都要可复核

- 仓库：`hifn123p/pixel-cake-app`（private，主分支 `main`）。
- **本仓库当前的工作模式**：用户说一声「push」或「发布 0.x.y」即视为授权，助手直接
  `git add -A` → `commit` → `push` →（main 跑绿后）`tag` → `push tag` **全程执行**，不再逐项征求确认。
  ⚠️ 但**每一步都要留下可复核的痕迹**：命令与关键回显（sha / job 结论）写进汇报，
  发版过程落进 `docs/Github_CI.md`（见 §5.4）。
- ⚠️ **历史提醒（别被旧记忆带偏）**：本项目**早期**是「助手只改代码、写一句『未提交，等你 push』，
  commit/push 由用户自己做」。模式在 v0.4.5 / v0.4.6 期间已切换为「助手全托管」。
  **接手时若不确定当前是哪种模式，默认走保守模式**（改完即停、列出待 `git add` 文件清单、等用户），
  并在第一次发言里问清 —— **不要凭记忆假设**。
- 发版规则：CI 五个 job，其中 `Publish GitHub Release` **只在 tag 上触发**（发版两步走见 §5.4）。

### 1.3 本地静态检查（唯一可用的两道闸门）

> **订正（2026-11-14）**：①② 是脚本**最初**的两道检查；后来 §14.2 又加了
> `catch(Throwable)` 的 `CancellationException` 处理闸门，脚本另有反向引用检查
> （`check_used_classes`，当前只报告、不计入退出码）。标题保留「两道」是为不破坏锚点，
> 实际请以 §7.1 的 `--selftest` 输出与脚本 `main()` 为准。
> ⚠️ 该脚本在 `.workbuddy/tools/` 下，**未入库、CI 也不调用它**；
> 它只能证明语法树闭合与符号大致对得上，**证明不了名字解析**（`README.md` 已同步）。

- **① 模板感知的栈式括号配平**：逐类型计数对 `([)]` 这种交叉错配会**假绿**；
  朴素「剥字符串再数」对 Kotlin 模板 `"${x ?: "?"}"` 也**假绿**。
  ⇒ 必须用**模板感知的栈式**实现（技能 `kotlin-offline-syntax-gate` 的 §2.1 脚本）。
- **② 跨文件 import / 调用点签名全量核对**：新增或改了函数签名，逐个调用点比对参数个数与顺序。
- ⚠️ 闸门脚本放 `%TEMP%` 下的副本**会被系统清掉** ⇒ 每次重跑先重建，不要假设它还在。
- ⚠️ 脚本自带**反向自测**：必须先跑坏例（应当被抓）与好例（应当静默），
  看到 `selftest=N/N passed` 全过、`scanned>0`、`unbalanced=0`、`exit=0`，绿灯才有意义。
  （历史上正是因为先跑了反向自测，才抓到闸门脚本自身在「交叉错配」分支上报 `TypeError` 崩溃 ——
  而它在配平正确的工程上一直返回「无事」，绿灯看不出异常。）
- ⚠️ **中文注释会干扰「逐行」计数**：PowerShell 的 `Get-Content` 会合并含中文注释的行。
  任何逐行源码统计在中文文件上不可信，**改用 Python 按 UTF-8 读**。

### 1.4 用户会在 **git worktree** 里干活

```
D:/AI_Project                                              main
D:/AI_Project.worktrees/project-analysis-and-improvement   agents/project-analysis-and-improvement
```

- 两个工作树**共用同一个 git 仓库**。
- ⚠️ **用户说「我改了 X」而主树 `git status` 干净时，先去 `git worktree list` 找**，
  不要急着下结论「仓库里找不到」。（曾据此误判过一次。）
- 类名 → 路径的两个易错点：`CameraPanel.kt` 在 **`ui/home/`**，不在 `ui/editor/`；
  `EditorToolbar` / `ParamPanel` / `ExportSheet` 才在 `ui/editor/`。
- **合并回 `main` 的流程（v0.4.6 实证）**：
  1. 在 worktree 内 `git add -A` + `commit`，把该分支的改动落成提交；
  2. 回到 `main` 工作副本：`git merge --no-ff agents/<branch>`；
  3. 抬版本号提交 → `push origin main` 跑绿 → 打 tag 发布（见 §5.4）；
  4. ⚠️ **合并前必跑 §1.6 静态闸门**，尤其注意**跨分支引入的局部变量前向引用**
     （v0.4.6 首轮红的真因就是它）。
- ⚠️ **判断 worktree 那侧是否收工：看「代码文件」的 mtime（静默 ≥5min），别看「记忆文件」mtime** ——
  对方常先改完代码、**最后**才写日志。且其约定是「只改代码 + 写『未提交，等你 push』，commit/push 由本侧做」。

### 1.5 工具与文件操作的坑

- 用户会**并发提交** ⇒ 需要一组结论时，**在同一批命令里一次取完**（避免两次读取之间仓库变化）。
  多行 git 输出用 `git grep`（`git show … | Select-String` 会假阴性）。
- ⚠️ **同一条消息里对同一文件发两条 `Edit` 会互相覆盖**（两条都报 success）。
  **同一文件一次只发一条**；改完立刻回读；多处改动优先整文件 `Write`。
- ⚠️ shell 输出重定向：PowerShell 的 `>` 写出来是 **UTF-16LE** ⇒ 一律
  `| Out-File -Encoding utf8 <tmp>` 再读；Bash 里 `Out-File` / `Get-Content` 这类
  PowerShell cmdlet **不存在**，不要混用。
- ⚠️ 用 `git -C <绝对路径>` 指定仓库，别依赖当前目录。
- ⚠️ `.workbuddy/` 与 `docs/archive/` **都在 `.gitignore` 里** ⇒ 它们的内容**不进仓库**。
  任何「后人需要看到」的结论都必须落在 `docs/` 的入库文档里。

### 1.6 推送前静态闸门清单（五条；脚本放会话目录，**别放仓库根**）

本地无编译器，推前用脚本挡掉大部分编译错 —— 这是 §1.3 两道闸门的具体化：

1. **具名参数合法性**：收集全部 `class X(...)` 的主构造字段名，遍历所有 `X(...)` 调用点，
   查 `name =` 是否落在字段表内。⚠️ **必须先剥离注释与字符串**
   （KDoc 里的示例会假阳性 —— 本仓曾因扫描器把注释当代码，一次误报 **40 处**）。
2. **元组 / API 实参个数**：`Pair(` 必 2 参、`Triple(` 必 3 参；
   白名单校验 `path.*()`（`cubicTo` 是 6 参，**没有** `cubicBezier`）。
3. **惯用类缺 import**：`Modifier` / `Column` / `Path` / `Size` / `dp` / `size` 等
   出现在正文却不在 import 表 ⇒ 报警。
4. **前向引用**：顶层 / 局部变量引用了**晚于自己声明**的变量（见 §5.7，v0.4.6 首轮红就栽在这）。
5. 括号配平扫描器**必须剥离注释与字符串**（否则 KDoc 里的括号造成假阳性）。

---

## §2 Compose 布局硬约束

### 2.1 `Crossfade` / `AnimatedContent` 的内容必须是「**一个**」布局节点

- 两者都用 **`Box`** 承载内容（已核实 `compose-animation:1.9.5` 源码，**没有**提前返回分支），
  而 `Box` 会把多个子节点**叠在同一个位置**。
- ⇒ **只要输出是一串兄弟节点，就必须自己包一层 `Column`。**
- 真机症状：「所有菜单和进度条全部挤在一行上，相互覆盖」（`ParamPanel` 曾因此错了 4 个版本）。
- ⚠️ **排查姿态**：这类「内容塌成一堆」在真机上会先被读成「参数太挤」，
  从而把排查引向**高度 / 间距 / 字号** —— **先怀疑容器，再怀疑尺寸**。

### 2.2 `selectable` / `clickable` / `toggleable` 必须走带 `indication` 的重载并显式传 `null`

- 只写 `selectable(selected, onClick)` **既不报错也不警告** —— 它会**静默**落到
  「取 `LocalIndication`」的那个重载（`foundation:1.9.5` `Selectable.kt:140`，
  它**没有** `indication` 参数、实现体写死 `useLocalIndication = true`）。
- 症状（**一条根因、三个现象**）：按下出现灰色**矩形** ——
  ① 与 `Radius.pill` 冲突 ⇒ 两端读成**直角**；② 相邻两格矩形**共用一条边** ⇒ 读成「中间一条分割线」；
  ③ 灰色其实是**涟漪的颜色**，不是选中态。
- **判据：按下时只要出现灰色矩形，就是漏传了 `indication`。**
- 配套：**不做按下缩放的可点区域，`interactionSource` 一并传 `null`**
  （`Clickable.kt:697` 快路径 ⇒ 不建指示节点 ⇒ `interactionSource!!` 不可达，还省一次分配）。
- 分段项**刻意不加 `pressScale`**：可见内容只有一行文字，缩放它读起来是「文字抖了一下」。
- ⚠️ **同形两份实现**：`GlassSegmentedBar`（编辑器一级工具条）与 `AppShell.GlassTabBar`（底部 TabBar）
  是**两份代码** —— **改一处必须同步查另一处**。

### 2.3 `weight` 只分配高度

- 唯一带 `weight(1f)` 的子项要自己 `fillMaxWidth()`，否则宽度不会被分配。
- `weight` 的分母是「**剩余**」高度 ⇒ **子内容不随权重缩放时，多分的只是空气**。
  判据：改权重后**内容尺寸有没有变**；没变就是白分。
- ⇒ 预览区与参数区这类「一对权重」，先想清楚哪一边是**内容驱动**（该 `wrapContentHeight` 语义）。
- **高频交互期间布局必须静止**：拖动滑块时不要改任何参与布局的属性（含顶栏高度），
  只改 `alpha`。

### 2.4 「隐藏一块 UI」是**三条独立**的事

| 手段 | 管什么 | 不管什么 |
|---|---|---|
| `alpha` | **绘制** | 不影响命中测试（全透明的「重置」照样点得中） |
| `AnimatedVisibility` | **存在**（会改布局尺寸） | — |
| `weight` | **尺寸** | — |

- ⇒ 「视觉上不见了」≠「布局上不占了」≠「点不到了」，**必须各自落实**。
- 本项目的做法：`Modifier.inertUnless(enabled)`（`EditorScreen`），在
  `PointerEventPass.Initial` 消费事件 ⇒ 整棵子树变哑。
  ⚠️ `inertUnless` **必须排在 `padding` 之前**，否则只盖住内边距以内的部分。

### 2.5 `LocalContentColor` 只有 `Surface` 会 provide

- `material3` 中 `LocalContentColor` 的默认值就是 `Color.Black`（`ContentColor.kt:33`），
  只有 `Surface.kt` 才 `provide` 它；而 `MaterialTheme(colorScheme, …)` **一个 `LocalContentColor` 都不碰**。
- ⇒ **任何「自己换 `colorScheme` 的自定义主题」都必须同时
  `LocalContentColor provides 该色板的 onSurface`**；
  否则该子树里所有**没写 `color` 的 `Text`** 都会继承**外层**主题的值 ——
  **内外深浅相反时 = 文字消失**（编辑页栽在这里：`Ink #1B1B1F` 压在 `#1B1B22` 上）。
- **排查姿态**：「某个控件没文字」**先怀疑颜色，别先怀疑布局**。
- ⚠️ 但同一份「没写 `color` 的 `Text`」清单**不能直接当结论用**：
  M3 的 chip 内部自带 `Surface` + `labelColor`，**从来不受影响**。
  **先按「在不在 `Surface` 里」分类，再定性。**
- 配套教训：**「记下了一个根因」不等于「把它的所有面都修了」**。
  一个「外层 X 在主题之外」的事实，至少顺着 `background` / `contentColor` / `LocalDarkTheme` /
  系统栏图标**四条**各查一遍。

### 2.6 其它 Token 级约束

- **圆角梯级 = 内容小、容器大**：内容（照片 / 缩略图）用 `Radius.chip`，
  卡片 `Radius.card`，面板 / 整表 `Radius.shell`。
  给内容套容器半径 = 用装饰吃掉信息。
  ⚠️ **2026-10-25（UI 3.0）订正**：这一条原来写死了数值（chip 14dp / card 22dp / shell 40dp），
  而 UI 3.0 的圆角标尺是 **chip 12 / deck 20 / sheet 28 / pill 99**
  （`docs/UI3.0.html`），代码已按新标尺改过 —— 所以这里**不再写死数值**，
  一律以 `docs/UI3.0.html` + `ui/theme/Radius.kt` 的实际定义为准（详见 §9.1）。
- **顶栏 / 工具条高度绑 `Spacing.controlHeight`（当前 48dp）**，不要写死 `44.dp` / `56.dp`。
  ⚠️ UI 3.0 的编辑器 Rail 例外：它是**悬浮胶囊**，高度是规范标注的 58dp（`RAIL_HEIGHT`），
  不绑 `controlHeight`；但仍要满足「内容高度 ≤ 容器高度」与大字号不裁切（见 §9.2 / §9.4）。
- **主题色走 `LocalDarkTheme.current`，不准 `isSystemInDarkTheme()`**；系统栏图标同判据
  （`WindowCompat.getInsetsController`）。编辑器**不套 AppShell**，edge-to-edge 的 Insets 由页面自己加。
- **Compose 没有 backdrop blur**；`RenderEffect` 的实时模糊对本项目收益为零
  ⇒ 玻璃用「半透明 + 1px 高光描边 + 导入时一次性生成的静态模糊底图」。
- **M3 默认值必须显式覆盖**：`TextButton` 有 58dp 下限、`Button` 圆角是 4dp。
- `asImageBitmap()` **必须 `remember`**。
- `snapshotFlow { }` 里必须**直接读** Compose 状态 —— 先取到局部 `val` 再读 = **静默失效**。

---

## §3 渲染与并发纪律

### 3.1 正在被显示的位图是**只读**的

- 分带渲染每 `BAND_ROWS = 32` 行 `setPixels()` 一次，每次推进 `generationId`
  ⇒ 合成器据此**重新上传纹理** ⇒ 往「屏幕上正在显示的那一张」写像素，
  用户看到的一定是一张**只写了一半**的图（撕裂横线），且每帧位置不同 ⇒ 看着像扫描条纹。
- ⇒ **逐帧重渲必须至少两块缓冲**：写一块、显一块（`rendered` ↔ `renderSpare`），算完一次性交换。
  稳态零额外分配，所以「为省 11MB/批 GC 而复用同一块」这个理由早已不成立。

### 3.2 ⚠️ **协作取消的返回值必须被消费**

- `EditEngine.renderIntoLinear` 的协作取消是「在下一个分带边界 **正常 `return false`**」（**不抛异常**），
  语义 = **「这张位图只写到了第 k 带」**。
- 忘了接它 ⇒ **语法、类型、Lint 三层都看不出来** ⇒ 半成品被 `rendered = target` 换到前台。
- ⇒ **任何「分带 / 分块写进一块会被别人读的缓冲」的算子，都必须把「写完了没有」显式带出来**
  （本项目的落点：`RenderBatch.completed`），**不能让调用方用「有没有被取消」去反推**。

### 3.3 ⚠️ 「图片代次」与「这一批画完了没有」是**正交**的两个量

- `imageEpoch`（`AtomicInteger`）只跟踪「**换图 / 退出编辑器**」；拖参数时它**一次都不会变**。
- ⇒ 单独拿它当丢弃判据 = 默认「没人换图 ⇒ 这一批一定画完了」，而这个假设在拖动期间**每帧都假**。
- 正确判据：`if (!batch.completed || batch.epoch != imageEpoch.get()) { 丢弃 }`。
- `imageEpoch` 的正确用法：
  - 导入路径在 `incrementAndGet()` **之后**取；
  - 渲染协程用启动时快照 `epochAtStart`；
  - `onBack`（退出编辑器）时递增。

### 3.4 ⚠️ 昂贵且有可见副作用的渲染**不能配 `collectLatest`**

- 拖滑块时 UI 以 60~120Hz 连发快照，`collectLatest` 对**每一次**都取消在跑的那一帧
  ⇒ 一帧 100~300ms 的重渲几乎永远跑不到一半，「启动 → 被砍」的循环吃掉全部 CPU。
  **「卡」不是渲染慢，是没有任何一个渲染被允许跑完。**
- ⇒ 用 `.conflate().collect { }`：只保留**最新**一次快照（不排队、不积压），
  而**已经在跑的那一帧一定跑完**。
- ⚠️ 改 `collect` 时**不能只删 import**：`collect { }` 的 lambda 重载定义在
  `kotlinx.coroutines.flow` 包里 ⇒ `import kotlinx.coroutines.flow.collectLatest`
  必须**换成** `import kotlinx.coroutines.flow.collect` —— **删掉就是编译错**
  （那不是「未用 import」）。
- 只有「换图 / 退出编辑器」还允许取消（`renderJob` = 本协程 job）。

### 3.5 ⚠️ ML 缓存键**绝不能用 `Bitmap` 的实例身份**

本项目的缓存键是 `mlCacheKey(uri, w, h, epoch)`，其中 `epoch = imageEpoch`。

**为什么不能用 `identityHashCode` / `generationId`**：

1. `System.identityHashCode` 由**地址**派生、不是唯一 ID。而本项目位图的典型生命周期正是
   「导入 → 退出编辑器 → 交 GC（`src.bitmap` 明确不手动回收）→ 再导入」
   ⇒ 新 `Bitmap` **极可能落在同一地址** ⇒ 哈希相同 ⇒ 若 `uri` + 尺寸也相同
   （同一张照片重开 / 连拍同尺寸），键就**完全相等** ⇒ **误命中旧人脸 / 旧分割结果**
   —— 这正是「键」要防的那个症状。
2. `generationId` 的语义是「**像素内容被改过**」（`setPixels` 推进它，而渲染每 32 行推进一次），
   与「这是哪张图」**无关**。
3. 最现实的反面作用：同 `uri` + 同尺寸时，每次 `decodeToProxy` 都返回**新实例**
   ⇒ 键必不相同 ⇒ **预览传 `src.bitmap`、导出传 `img.bitmap`（两个不同实例）**
   ⇒ 各自推理一次，**缓存复用全部失效**，每次导出白跑一遍推理。

**为什么 `imageEpoch`（会话代次）恰好是对的量**：它在**一次编辑会话内恒定**
⇒ 会话内三个调用点同键（复用生效）、换图后必变（不会误命中）。

⚠️ **尺寸一律用 `src.bitmap` 的，不要用渲染协程的 `w`/`h`** ——
RAW 路径下那是**线性代理尺寸**（`src.linear.*`），会把同一张图拆成两个键。

### 3.6 `@Synchronized` 的 `invalidate()` 要放后台

- 推理可能持锁数百 ms ~ 数秒 ⇒ 若在主线程调用就是 **ANR**。调用点必须搬到
  `Dispatchers.Default` 之类的后台。
- `reset()` 内部调 `invalidate()` **不会死锁** —— `synchronized` 是**可重入**对象锁。
- ⚠️ `rememberCoroutineScope()` 随 composable 取消 ⇒ **退出路径的 `invalidate()` 可能不执行**。
  **有了 `epoch` 进键之后正确性不再依赖它**（只是峰值内存略高）—— 这是 `epoch` 方案的附带好处。
- 「感觉慢」和「算力被浪费」是两件事：**先核对产出/消耗比，再谈优化算法**。

### 3.7 位图回收三原则

1. **像素写入与 `recycle()` 必须进同一把 `Mutex`**（否则 native 层 use-after-free，
   Java 兜不住）；最典型的触发路径是「拖完滑块立刻点返回」。
2. **丢弃批次时只有「本批新建的」才回收**；借来的位图**绝不**回收。
3. **读者不唯一**的位图**不手动回收**（`src.bitmap` 被预览 / 缩略图 / 导出 / ML 推理共用 ⇒ 交 GC）。
- `onBack` 要退役 `rendered` / `compareBase` / `renderSpare` **三块**：先摘引用，后延后回收。

### 3.8 其它渲染口径

- **源图 ≠ 渲染分辨率**（预览内嵌 JPEG 3504×2336 / 线性代理 2048×1366 / 导出 7008×4672）
  ⇒ **跨阶段坐标一律归一化**；手势基准是 `fitContentRect` 的内容矩形。
- 裁剪 / 缩放必须 `Canvas` 画进**全新位图**（`createBitmap` 在尺寸相等时会返回**同一个实例**
  ⇒ 会改掉用户照片）。⚠️ 这条与 3.5 是同一个陷阱的两面。
- 33MP 每份全幅 `IntArray` ≈ **131MB**；液化**不要分带**（后向映射跨带会读到已写值），
  走包围盒 / 源行条带。
- `RetouchMask` 的 `null` = 「**不执行**」；要全局生效就显式传 `FullMask`（见
  `PHASE_DESIGN_HISTORY.md` P1b §4）。
- 高光压肩在线性域乘三通道**公共**软压肩系数（`ColorMath.shoulderScale`），
  **绝不退回逐通道 clamp**（`shoulderPreservesHighlightRatio` 是护栏）。
- **协作取消 ≠ 副作用停止**（取消只在挂起点生效）⇒ 跨线程写回用**代次**作废；
  一次性副作用不要切线程。
- 要在回调外被读到的标志必须**同步置位**（如 `exporting`）；提前置位就要补全所有提前返回路径的复位。
- **对比基准必须同管线同口径**（零编辑渲染图 ↔ 当前渲染图）；
  **状态回写要落在「用户实际看的东西」上**（导出成功必须换按钮形态，状态行文字是弱信号）。

---

## §4 设置、模型与依赖

### 4.1 用户设置

- 全部走 `ui/settings/AppSettings.kt`（SharedPreferences + `mutableStateOf`，**写属性即持久化**），
  由 `MainActivity.setContent` 在 `PixelCakeTheme` **之外**创建。
- ⚠️ **改键语义必须写迁移**：`CURRENT_SETTINGS_VERSION` + `migrateSettings()`，
  逐段跑、用 `commit()` 一次落盘、`init` 块排在本类**第一行**
  （属性初始化器按声明顺序执行，迁移排在后面等于白跑）。

### 4.2 端侧推理栈

- **LiteRT**：`com.google.ai.edge.litert:litert`（**仅在 Google Maven**）。
  ⚠️ 别再写「TFLite + NNAPI」—— **NNAPI 自 Android 15 起被官方废弃**，
  TFLite 本体进入维护模式。见 `PHASE_DESIGN_HISTORY.md` P1+ §2。
- 模型都是**随包入库**（`app/src/main/assets/models/`），走 `noCompress` 以便 mmap。
  两个模型的体积 / SHA-256 / 许可见 `NOTICE` 与 `PHASE_DESIGN_HISTORY.md` P1+ §0.1 / §15.5。
- **APK 体积约束**：Release 已启用 R8 / 资源收缩、限 `arm64-v8a`、过滤语言资源。
  继续缩包需要量化 / 蒸馏分割模型 —— **在没有真机画质基准前，不应以牺牲蒙版质量换体积**。

### 4.3 平台与版本

- `minSdk = 36`、`compileSdk / targetSdk = 36`（CI runner 尚未发布 `platforms;android-37`）。
- `composeBom = 2025.11.01`；Kotlin 2.2.21 / AGP 8.13.2 / JDK 17。
- NDK **必须 pin**（`ndkVersion = "30.0.16248370"`），CI 装同一版本 —— 别动态取最新。
- 签名：**debug keystore 入库**（PKCS12 / 口令 `android`）；**release 走 GitHub Secrets**（仓库内零 `.env`）。

---

## §5 构建、CI 与发版

### 5.1 CI 闸门（`.github/workflows/android.yml`）

顺序：`unit tests`（`testDebugUnitTest`）→ `lintDebug`（拦门，无 `continue-on-error`）
→ `assembleRelease`（Secrets 签名）→ artifact（保留 90 天）→ `$GITHUB_STEP_SUMMARY`；
tag 触发时额外 `Publish GitHub Release`。
ktlint / detekt **未引入**（无本地构建环境时盲开容易让 CI 误红，待本地验证后再说）。

- **五个 job**：`build`（`assembleDebug` + `testDebugUnitTest`）/ `lint` / `check-signing`
  / `release`（需 4 个 Secrets，未配则跳过）/ `publish`（仅 tag，`contents: write`）。
- **签名**：`signingConfigs.debug` 绑定**入库**的 `app/debug.keystore`（PKCS12，公开口令
  `android` / `androiddebugkey`，`.gitignore` 用 `!debug.keystore` 反忽略）——
  这是为了让每台 runner 用**同一个** debug 密钥，避免覆盖安装时
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（ColorOS 证书冲突）。`release` 用 `storeType="PKCS12"`
  （Secrets 里存的是 openssl `.p12`，**不是** JKS）。
  ⚠️ **release keystore 只在 Secrets，绝不入库**；一旦误提交真实密钥，
  必须 `git filter-repo` / BFG **清历史 + 轮换密钥**，仅删文件不够。
- **proguard / R8**：LiteRT `-keep class com.google.ai.edge.litert.**` + `-dontwarn`
  （R8 对 JNI / 反射类；不 keep 会 release 运行期崩、debug 不复现）。
- ⚠️ **`setup-android`**：三处 `android-actions/setup-android@v3` 必须显式
  `with: packages: 'platform-tools'` —— 它默认含已被 Google 下架的 legacy `tools` 包 ⇒
  `Failed to find package 'tools'`，Build / Lint 在**预编译步**就红。真实 SDK / NDK / CMake
  由本地 composite action `./.github/actions/setup-android-toolchain` 负责。
- 子模块（LibRaw）经 `actions/checkout` 递归拉取；本地需
  `git submodule update --init --recursive`（走 SSH 可避开代理证书问题）。
- 本地工作树如果出现两个 LibRaw 子模块显示为 ` D`（deleted），
  用上面那条命令补回；**别 `git commit -a`**，否则会把子模块从仓库删掉。

### 5.2 发版一致性（**四者必须同时改**）

`app/build.gradle.kts` 的 `versionCode` / `versionName` → git tag → `AboutSheet` 显示
（`v${versionName} (${versionCode})`）→ `Github_CI.md` 记录。四者不一致就是发版事故。

### 5.3 本地 → GitHub：推送机制与 shell 纪律

> 🔴 **2026-10-07 更新：SSH 通道在本机已完全不可用，push/fetch 一律走 HTTPS。**
> 详见下面的「通道实测」。**不要再按老办法试 SSH**，那会白等 3~4 次超时。

#### 通道实测（2026-10-07）

| 通道 | 结果 |
|---|---|
| `ssh.github.com:443`（§1.3 老办法） | ❌ `Connection reset` / `Connection timed out`（TCP 能建，SSH 层被阻断） |
| `github.com:22` | ❌ `Connection refused` |
| `ssh.github.com` DNS | 只解析到 `20.205.243.160`（单IP，无备选） |
| `https://github.com` | ✅ **200，且无 MITM 证书问题** |
| 系统 credential manager | ✅ 已有 github.com 凭据（`credential.helper = manager`） |

⇒ **可用路径 = HTTPS**（凭据走系统管理器，仓库内仍零 `.env`、零 token）：

```bash
# 推送（不改仓库 remote 配置，只在这条命令上用 URL）
git push https://github.com/hifn123p/pixel-cake-app.git main
# 刷新远端跟踪引用并核对 sha
git fetch https://github.com/hifn123p/pixel-cake-app.git main:refs/remotes/origin/main
git rev-parse HEAD origin/main          # 两个 sha 必须一致
```

#### 🔴 `github.com` 的 connect 会被**抽样阻断** ⇒ push 必须带重试

2026-10-25 实测（发 v0.5.0 时）：

| 现象 | 说明 |
|---|---|
| `https://api.github.com` | ✅ 稳定 200 |
| `https://github.com`（git 端点） | ❌ `Failed to connect ... after 21s`（**connect 超时**，不是 TLS 或 HTTP 错误） |
| 同一 IP（`20.205.243.166`）用 python 直连 | ✅ 握手成功并返回 200 |
| 换 IP（`140.82.112.3` / `20.27.177.116` / `140.82.121.3`） | ✅ TLSv1.3 握手成功 |

⇒ 结论：**同一时刻不同 IP 的可达性不同，且同一 IP 的可达性随时间变化** ——
是链路层抽样阻断，不是配置错误、不是证书问题、也不是 hosts 黑洞。

⇒ **push 的正确姿势（两条一起用）**：

```bash
# 1) 轮换 IP（curloptResolve 只作用于本条命令，不改仓库/全局配置；git 2.53 支持）
git -c http.curloptResolve=github.com:443:140.82.112.3 \
    push https://github.com/hifn123p/pixel-cake-app.git main
# 2) 失败就重试（push 幂等，重试无害）
```

实测：直连 `github.com` 连推 12 次全失败，改用 `curloptResolve` 固定到
`140.82.112.3` 后**一次成功**。选 IP 的办法是先用 TLS 握手探一遍
（`socket.create_connection` + `ssl.wrap_socket(server_hostname='github.com')`），
握手成功的 IP 再交给 git。

⚠️ **别把「连不上」误判成「仓库/凭据/权限问题」** —— 先看错误类型：
`Failed to connect after Ns` = 链路层；`401/403` = 凭据；`SSL` = 证书/代理。

⚠️ 查 GitHub API（CI 状态 / job 日志）也走 HTTPS：`api.github.com` 同样是 200，
token 从 `git credential fill` 取，**只在进程内存里**，不落盘、不打印。
⚠️ job 日志端点会 **302 到 blob 签名 URL** —— 第二个请求**绝不能**再带
`Authorization` 头（会 401），所以要手动处理重定向。

<details><summary>老办法（SSH，当前已知不可用，留作网络恢复后的备选）</summary>

**⚠️ push 走 `ssh.github.com:443`**：本机 `hosts` 把 `github.com` 黑洞到 `127.0.0.1`，直连 22 端口 = refused。
**只在单条命令上临时覆盖，不改仓库 / 全局配置**：

```bash
GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=no -o ConnectTimeout=20 -p 443 -o HostName=ssh.github.com" \
  git push origin main
```

</details>

#### 其余推送纪律（仍然有效）

- **全量推送**：`git add -A` 提交**全部**改动，不挑拣、不逐项询问；
  **fast-forward，绝不 force push / 改历史**。
- ⚠️ **`git add -A` 之后必须确认子模块没被误删**（`git diff --cached --name-status | findstr LibRaw`
  应无输出）。两个子模块在 index 里是 `mode 160000` 的 gitlink，未初始化时目录为空 ——
  历史上在这里丢过子模块。**不要用 `git commit -a`**。
- **commit / tag 的消息一律走实体文件**：`git commit -F <msgfile>`（`tag` 同理）。
  - ⚠️ `-m` 里的**反引号会被 bash 当命令替换执行** ⇒ 消息里被反引号包住的标识符
    **静默消失**（还吐 `xxx: command not found`）；
  - ⚠️ `-F /dev/stdin` 在本机 shell **读不到**（`/proc/self/fd/0` 不存在）⇒ 必须写**真实文件**。
  - ⚠️ **必须写绝对路径**：`git commit -F .git/XXX.txt` 在本机解析异常（报「系统找不到指定的路径」），
    而 `git commit -F "D:/AI_Project/.git/XXX.txt"` 正常。
- ⚠️ **本机 shell 会重复执行同一条命令** ⇒ `git commit` 回显 `nothing to commit, working tree clean`
  是**假警报**（其实第一次已提交成功）；`git tag -a X` 报 `already exists` 是**已建好**。
  应对：commit / tag **拆成单独命令**，用 `git log` / `git rev-parse HEAD` /
  `git ls-remote --tags` 复核；push 重复无害。
- 汇报前用 `git rev-parse HEAD origin/main` 确认两个 sha 相同（工作树与远程对齐）。
- CI 轮询须**前台 + 长超时**（单次上限 1200s，超时按 `run_id` 续轮）。
- ⚠️ **PowerShell 传参的坑**（本机 shell 在 cmd / PowerShell 间会变）：
  - `| head -20`、`| grep` 这类 Unix 管道**不存在**，用 `| Out-String`；
  - `python -c "..."` 里的 **反引号会被 PowerShell 当转义符**、`$` 会被展开** ⇒ 代码里两者都不能出现**；
  - `git credential fill` 的多行输入经PowerShell 管道会丢字段（报 `missing protocol field`）⇒ 改用
    `python` 的 `subprocess.run(input=...)`，或把脚本写成文件。

### 5.4 发版两步走 + run 数规律

**两步走（切勿把代码与 tag 一起推）**：

1. 抬 `versionName` / `versionCode` → `commit` → `git push origin main` → **等 main run 全绿**；
2. 在**已验证的那个 commit** 上 `git tag -a vX.Y.Z -m "..."` → `git push origin vX.Y.Z`
   （触发 `Publish GitHub Release`）。

- ⚠️ **已推送的 tag 不可删 / 不可移**（`push --force --tags` 属历史改写）；
  发错版本就发**下一个**版本号。
- **发版 run 数规律**：顺利 = **2 个 run**（修复 + 抬版本一起 push → main 绿 → tag 绿）；
  **main 首轮红则 +1**（红 → 修复后绿 → tag 绿）；
  **引入从未编译过的新文件则更坏**（v0.4.5 = 4 个 run，错误逐层暴露；v0.4.6 = 3 个）。
- **每轮 CI 结果覆盖重写 `docs/Github_CI.md`**（job 表 + 报错 / 根因 / 修复 + 历史回归表），
  随全量推送一起提交。

### 5.5 已发布的 tag

`v0.1.0` → `v0.7.0` **全部齐全**（共 22 个 tag：`v0.5.1` / `v0.6.0`~`v0.6.6` / `v0.7.0` 均已发布）。
⚠️ **`v0.7.1` 尚未打 tag**：`app/build.gradle.kts` 已是 `versionCode 23 / versionName 0.7.1`，
但 `git tag --points-at HEAD` 为空、`git describe` = `v0.7.0-15-gf578559`。见 §17。

- `v0.4.0` 调色批次 1~4（81 项）；`v0.4.1` 批次 5 对象作用域；
  `v0.4.4` UI Redesign v2.0；`v0.4.5` 细部位美容 + 44 套分类预设 + 追色 mono 去色 + 曲线/直方图；
  `v0.4.6` ML 缓存键改为会话代次 + 渲染提交判据（`completed`）+ `GlassChipRow`/`PresetThumbRow` 换行 + UI 尺寸回调；
  `v0.4.7` **第一轮全量审查**（21 个 P0 + 性能批次 + UI 规范 + 13 条回归断言，详见 §7）；
  `v0.5.0` **第二轮全量审查**（3 个 P1 + 12 个 P2 + 删 `ui/editor/curves/` 死代码 + 3 个新测试）
  与 **UI v2.1**（预览缩放手势 + 调色菜单 7→6 + 尺寸回调 + RGB 直方图，详见 §8 与 `UI_DESIGN.md` §4.0.9/§4.0.10）。
- ⚠️ **任何用作判据的记忆值，用前先 `git grep` 复核一次**。
  （曾两次因为「记着的版本号 / 尺寸」而误判，两次都是记忆过期，不是代码有问题。）

### 5.6 待真机验收清单（至今未闭环）

A2 涂抹落点 / A1 导出内存日志 / 自动蒙版（P1p-1c）/ 人脸锚点（P1p-2c）/ A7C2 直连（P2）/
`TONE_ZONE_GAIN=0.35` 是否过猛 / 批次 3 预览↔导出一致性 / 批次 4 锐化必须晚于磨皮 /
批次 5 的 6 条（`OBJECT_TONE_DESIGN.md` §12.2）；**v0.4.6 核心**：同一张照片反复开关编辑器
时人脸 / 分割**不应**沿用上一张结果；**v0.5.0 核心**（6 条，详见 `Github_CI.md` 待办）：
预览缩放（≤8x / 平移边界 / 双击 2.5x 锚点）、调色菜单 7→6 有无漏项、LUT 是否已被「预设 → 滤镜」接管、
RGB 直方图是否挂在「明暗」页、字号 28→24 / 18→16 在小屏是否挤、ARW 超大预览候选回退能否打开。
**P3（NAS Docker 化 Rust 引擎）未启动**。

⚠️ **本节是早期批次的历史清单，已统一收敛到 `DEV_PLAN.md` §10**：那里补了 v0.6.4 / v0.7.0 /
v0.7.1 的验收判据。两处不要再各自维护一份，以 §10 为准。

### 5.7 CI 红了怎么读（判据 + 常见误判）

- **判「真编译错」vs「infra 抖动」**：`Build` 与 `Lint` **同时**报同一个 `e: ...kt:行:列`
  ⇒ 真编译错（改代码）；只有 Lint 挂、日志里**没有** `e:`（只有 `hs_err_pid*.log`）
  ⇒ infra 抖动，重跑即绿（`ci_rerun.py <run_id>` → rerun-failed-jobs）。
- ⚠️ **`Unresolved reference 'X'` 与 `Argument type mismatch` 报在同一个「行:列」
  ⇒ 先怀疑 X 缺符号，别去追类型。** 实测：`imageEpoch` 因**局部变量前向引用**不可见，
  编译器把该标识符解析成同名候选（`MatchGroup?`），**连带**报出完全误导的类型不匹配。
  **修复 = 把声明搬到首次引用之前**（本例把「KDoc + 声明」整块上移）。**这条判据能省一整轮 CI。**
- **前向引用有两种，报错不同**：顶层 `val` 引用后声明的顶层 `val` =
  `Variable 'X' must be initialized`；**局部**变量按声明顺序可见，引用后声明者 =
  `Unresolved reference`（见 §1.6 第 4 条）。
- **`compileDebugKotlin` 挂 ⇒ `compileDebugUnitTestKotlin` 根本没跑**：修 main 编译错时
  **顺手把测试侧静态核一遍**，能省一整轮。
- **引入「从未编译过的新文件」后，第一轮大概率红**：只要文件在 `app/src` 下（哪怕 dead code、
  无调用点）就必须能编译 —— 要么先本地静态核，要么**预期 3~4 个 run**。

---

## §6 排障姿态（三句话）

1. **「只在拖动时出现、手指一停就消失」的画面缺陷** → 先怀疑「**半成品被提交**」，不要先怀疑算法。
   判据：缺陷是**一条位置每帧变化的直线 / 接缝**（不是重影、色偏、糊）。
   要洗清「带内并行」的嫌疑只需一处证据：**写回前有没有等齐所有分片**。
2. **「某个控件没文字」** → 先怀疑**颜色**（§2.5），别先怀疑布局。
3. **「内容挤成一堆」** → 先怀疑**容器**（§2.1），别先怀疑尺寸。

---

## §7 全量代码审查（2026-10-07）的产物与新纪律

> 本节来自一次覆盖 `app/src` 全部 121 个 `.kt` 文件的逐文件审查。修复前的状态是
> **括号配平 0 问题、CI 全绿、32 个类 / 247 个测试全过** ——
> 也就是说下面每一条缺陷都**通过了当时所有自动化闸门**。

### 7.1 本地两道闸门现在住在 `.workbuddy/tools/kotlin_gates.py`

> **订正（2026-11-14）**：除 §1.3 的两道外，脚本现含 **CE 处理闸门**（§14.2）与
> **反向引用检查**；`README.md` 的「项目简介 → 接手者请先读」已按现状表述。
> 脚本位于 `.workbuddy/`，**不入库、CI 不调用** —— CI 才是唯一的编译器。

§1.3 要求「模板感知括号配平 + 跨文件 import / 调用点核对」，但又说脚本放在 `%TEMP%`
会被清掉。现在固定放在 **`.workbuddy/tools/kotlin_gates.py`**（该目录已被 `.gitignore`
覆盖，不会入库）：

```bash
python .workbuddy/tools/kotlin_gates.py --selftest          # 必须先跑反向自测
python .workbuddy/tools/kotlin_gates.py --root app/src/main/java --extra-root app/src/test/java
```

- ⚠️ **闸门自己也要先自测**：第一版实现就在 `"${x ?: "?"}"` 上假红、在嵌套块注释上
  假绿。「selftest=PASS + total=0」两个条件同时满足，绿灯才有意义。
- 闸门刻意**宁漏不假红**：扩展函数、带默认值的形参、含泛型的实参一律放行。
  假红会让闸门被忽略，那比漏报贵得多。
- 状态（2026-10-07）：`scanned=121 files, unbalanced=0, import_issues=0, arity_issues=0`。

### 7.2 ⭐ 五条新纪律（都是这次审查换来的）

1. **「暴露了但没接线」比「没做」严重得多。**
   人像面板曾铺出 13 个部位滑块 + 7 个开关，而 `Beauty.apply` 只读 3 个量
   ⇒ 拖下去画面纹丝不动，用户的第一反应是「这个 App 的滑块坏了」。
   ⇒ **凡是 UI 上出现的参数，必须有一条断言证明它会改变输出。**
   本次处理：只保留真正接线的大眼（并把它与 `BeautyParams.eyeEnlarge` **合并**，
   消除「两个同名不同字段、只有一个生效」的陷阱），其余部位改成一句说明
   —— 与 `OBJECT_TONE_DESIGN.md` §9.4「宁可不给，不给假的」同源。

2. **查表下标 ≠ 值。** 颗粒的形状表曾把「带符号字节 `and 0xff` 的下标」当成
   噪声值本身（`(b - 128) / 128f`）⇒ 噪声与颗粒强度**反向**（平滑处颗粒最强）。
   而「幅度上界 / 三通道同加 / 位置不同」这些已有断言在修复前后**全部照样通过**。
   ⇒ **映射语义必须收进一个具名纯函数**（现为 `GrainNoise.shapeOf`），
   并断言**方向**而不是只断言幅度。
   ⚠️ 下标换算：`index 127` 才是最大正噪声 +127，`index 255` 是 −1。写反了测试会假绿。

3. **逐段构造的东西必须逐段判空。** 分通道曲线三张表各自按通道判恒等，
   渲染时却只判第一张 ⇒ 只调绿/蓝曲线时整段被跳过。
   ⇒ 「构造期给了 N 份条件」时，**跳过条件必须 N 份**，不能只判第一份。

4. **顺序不变量必须由同一个函数保证，而不是由调用方记得补。**
   人像精修曾经由三处调用方「事后补一趟」，于是 JPEG 路径的实际顺序是
   `调色 → 细节 → 精修`，与 RAW 的 `调色 → 精修 → 细节` **相反** ——
   正好把「锐化必须晚于磨皮」反向踩了一次（先锐化再磨皮 ⇒ 锐化被糊掉）。
   ⇒ 已把 retouch 收进 `renderIntoSrgb`，让**三条入口同构**。
   ⚠️ 这属于「加成员后必须扫消费点」那一类改动，4 个调用点必须同批改
   （预览 / JPEG 导出 / 预设缩略图 / 相机批量）。

5. **逐像素路径里不许有整数除法、不许有 `String` 比较、不许有装箱。**
   `DetailPass` / `NeutralGray` 的 `boxPass` 每趟每像素 3 次 **64 位**除法，
   33MP 下约 4 亿次 ⇒ 单这一个函数就能吃掉整条「亚秒级」预算。
   ⇒ 改为「乘倒数 + 极小 epsilon」（epsilon 保证「恰好整除」时截断仍得精确商）。
   同类：内置 LUT 已从「逐像素 `String.equals` + `when(lutId)`」收敛成构造期 `Int` opcode。

### 7.3 泄漏的四类形态（这次一次性找齐）

| 形态 | 本次实例 | 对策 |
|---|---|---|
| **失败路径的对象没关** | `LiteRtSkinMaskModel.createOrNull` 全部档位失败时 `Environment` 从不 close | 所有失败出口统一收口 |
| **异常路径的位图没回收** | 导出协程无 `try/finally` ⇒ `exporting` 永不复位 + 132MB 泄漏 | 导出体整体包 `try/catch/finally` |
| **跨会话的状态没释放** | 换图不释放上一张的 65MB ARW 缓存；`MlMaskProvider.disabled` 从不复位 | 换图路径显式释放 + 失效即复位 |
| **原生资源竞态** | `RawLinearSource.handle` 是普通 `Long` + 非原子 check-then-act ⇒ double free | 改 `AtomicLong.getAndSet` |

### 7.4 USB 传输层：两条纪律

1. **`close()` 必须与在途 I/O 串行化。** 取消协程**不会**中断阻塞中的 `bulkTransfer`，
   而 `onDispose` 在另一个线程直接 `connection.close()` ⇒ 内核传输继续用已释放的 fd
   （下一台设备很可能拿到同一个 fd 号）⇒ native 崩溃或把数据传到别的设备。
   `PtpTransport` 现在用一把**同步**锁（`onDispose` 里不能用协程 `Mutex`）包住传输与关闭。
2. **数据阶段中途失败 = 会话失步，必须作废会话。** IN 端点上还留着未读的负载字节，
   继续发下一条事务会把残留当容器头解析 ⇒ 可能把上一张的残片写进下一张并**报告成功**。
   PTP 没有恢复机制，所以新增 `isDesynced`，`CameraSession` 与 `CameraBatch` 都要检查。

### 7.5 断言要断言「能区分修复前后」的性质

`SilentBehaviorRegressionTest`（新增）里每条断言都写明「修复前它会通过还是失败」。
总结成三条铁律：

- 修「反向 / 方向」类 bug ⇒ 断言**符号与单调性**，只断言幅度一定漏。
- 修「静默跳过」类 bug ⇒ 断言**该参数单独作用时输出真的变**。
- 修「效果弱化」类 bug ⇒ 断言**动态范围**（如 bw 后的 `hi - lo`），
  只断言「三通道相等」一定漏。
- ⚠️ **2026-10-25 追加：CI 日志里「测试 FAILED」但**没有** `expected:/but was:` 行
  ⇒ 大概率不是断言不通过，而是**测试自己抛了异常**（`IndexOutOfBoundsException` 之类）。
  本地无编译器时这个区别极易误判：会以为是「被测代码算错了」，于是去改**生产代码**。
  实际那次是 `HistogramTest` 里 `IntArray(4)` 却写 `before[10] = 100` ⇒ 越界。
  ⇒ **读 CI 日志的第一件事**：先确认有没有 `expected/but was`；没有就去看 `at <测试类>.<方法>(行号)`
  指向哪一行 —— 那是**抛异常的那一行**，不是「断言失败的那一行」。
  ⇒ 写测试时的对应纪律：**数组/集合的尺寸要覆盖你打算写入的索引**，
  写 `IntArray(BINS)` 而不是 `IntArray(4)`（前者表达意图，后者在越界时炸）。

### 7.6 本次未做、留作后续

| 项 | 位置 | 为什么现在不做 |
|---|---|---|
| `MainActivity`（1200 行）拆 `RenderCoordinator` / `ExportController` / `EditDocument` | `MainActivity.kt` | 是本项目**最大**的改动面，需独立一轮；§7.2 第 4 条已先把最危险的三条判据钉死 |
| `chromeAlpha` 在 `EditorScreen` 主体读取 ⇒ 淡出期间每帧重组整个编辑页 | `EditorScreen.kt` | 需重构成 `@Stable RenderHolder`，与上一条同一轮做 |
| ~~`curves/` 三个文件是死代码，且内含 3 个 P0~~ **已处置（2026-10-25）**：整包删除，同时删掉包内第 4 个 P0（chip `clickable` 未传 `indication = null`）。曲线功能由 `ParamPanel` 的 `CurveTab`（亮度/R/G/B）承担 | ~~`ui/editor/curves/`~~ | 已按「不接」分支执行；删除前 grep 核实包外零引用 |
| 部位级美容算法（面部/身体/腿部磨皮、唇部、祛黑眼圈、瘦头/额头、腿长、手部） | `core/edit/retouch/` | 需要新算子（分区蒙版强度 / 局部形变），不是接线能解决的 |
| `PtpTransport` 抽 `BulkIo` 接口以便 JVM 打桩 | `camera/PtpTransport.kt` | §7.4 已把最危险的两条堵住（2026-10-25 又补上 `downloadObject` 命令发送绕锁的一处）；打桩化收益很大，但会改动传输层结构 |
| ML 推理移出 `renderMutex`；分带缓冲复用到 `DetailPass` 之外的全部路径 | 多处 | 属于性能批次，需配合真机耗时日志验证 |

---

## §8 第二轮全量审查（2026-10-25）的产物与新纪律

> 覆盖 `app/src` 全部文件（含 cpp）：`core/edit`、`core/ml`、`camera` 逐文件人工精读，
> `decode/ARW/JNI` 与 `ui/MainActivity` 两域由子代理精读、人工逐条验证后修复。
> 修复前基线：v0.4.7、括号配平 0 问题、CI 全绿。本轮共修 **3 个 P1 + 12 个 P2**，删死代码 3 文件，
> 新增/补充 3 个测试文件（采样档位、直方图纯函数、ARW 超大候选回退）。

### 8.1 本轮修复清单（按域）

| 域 | 修复 |
|---|---|
| camera | **P1** `PtpTransport.downloadObject` 的命令发送直接调 `connection.bulkTransfer`，**绕过了 §7.4 的 ioLock**（入口也无 closed/desynced 检查）→ 改走 `bulkOut` + 入口判据 |
| decode | **P1** 代理解码欠降采样：AOSP 采样惯例在 33MP/2048 下停在 3504×2336（≈31MB，预算 3 倍且整会话常驻）→ 两个采样函数补「超目标 1.5 倍再对折」档（`ProxySampleSizeTest` 钉住） |
| ui | **P1** 拖滑块途中第二根手指点未 inert 的 chip（二级分组/作用域/画布工具）会销毁正在拖的 `ParamSlider`，M3 Slider 销毁时**不回调** `onValueChangeFinished` ⇒ `dragging` 卡 true、顶栏永久 `alpha=0` 且不可点 → `ParamSlider` 加 `DisposableEffect` onDispose 兜底复位 |
| ui | 导出协程在 `Dispatchers.Default` 内读快照状态 `params`（两处渲染入口）→ 主线程先捕获 `tonalParams`；`onBack` 主线程同步删 ARW 缓存 → 切 `Dispatchers.IO`；`openInEditor` 外层 `runCatching` 吞掉 Decoder rethrow 的取消 → 显式 catch 重抛 |
| decode | `Decoder` 两个入口 `catch(Throwable)` 吞 `CancellationException`（取消伪装成「无法解码」）→ 先 rethrow；`RawLinearSource` readRows/close 加互斥锁（关掉「快照句柄→JNI」与 close 的窗口）+ `linearDims/linearMeta` 异常时 closeLinear（native 196MB 不再在 OOM 时刻泄漏）；JNI `readLinearRows` 的 jsize 截断改 jlong 比较 |
| arw | `ArwContainer` header/SubIFD 两处短读越界防护；扫描异常在 `extract` 内收拢；最大预览候选超 32MB 时**回退到合规次大候选**（不再「整张 ARW 打不开」）；`Exporter` 检查 `IS_PENDING` 清零的 `update` 返回值 |
| edit | `Histogram.clippedMedian` 中位数阈值 `(clip+total)/2` → `clip+total/2`（读数系统性偏暗 clip/2 分位）；`meanShift` 分母误用两图合计样本数（指示幅度减半）→ 各图独立求均值。`HistogramTest` 用「旧新阈值给出不同桶」的分布钉住方向 |
| ui | `ExportSheet` 硬编码「JPEG 质量 92」与可配置设置脱节 → 参数化透传；`BodyPartTab.wired` 死字段删除（接线真相在 `when(tabId)`，见 8.2-4） |

### 8.2 ⭐ 新增纪律（本轮换来的）

1. **「加了锁」必须等于「全部路径都走锁」**。`PtpTransport` 的 ioLock 盖住了 `bulkOut/bulkIn`，
   但 `downloadObject` 自己手写了一次 `connection.bulkTransfer` —— 锁只保护「走它的调用」，
   不保护「同类调用」。加锁后要 grep 该资源（`connection.`）的全部裸用点。
2. **AOSP 的 inSampleSize 惯例是「宁大勿小」**：只保证对折后仍 ≥ 目标就继续折，
   结果可以超目标任意倍。凡是有「内存预算注释」的地方（如 2048≈11MB），
   必须对采样结果**单独断言上界**，惯例本身给不了这个保证。
3. **`runCatching` 会吞 `CancellationException`**。内层按纪律 rethrow 的取消，
   会被外层一行 `runCatching{}.getOrNull()` 重新吞掉 —— 跨「取消边界」的调用链上，
   取消重抛要**每一层**都做，任何一层用 `runCatching` 前都要先想这一下。
4. **表驱动 UI 的「接线状态」字段若无人读取，比没有更糟**：`BodyPartTab.wired` 标了 `true`
   但分支从不读它，将来加部位必然两处漂移。接线真相只允许有一个落点（`when(tabId)` 分支）。
5. **「按住拖动中途，组合可能被换掉」是 Compose 交互的一类固有风险**：
   任何「拖动开始置位、结束复位」的跨组件状态（如 `dragging` 隐身交互），
   都必须有 `onDispose` 兜底 —— M3 Slider 在节点销毁时**不会**回调 `onValueChangeFinished`。

### 8.3 死代码处置

- `ui/editor/curves/` 三文件（§7.6 第 3 条）**整包删除**：包外零引用（grep 核实），
  包内含 4 个 P0（3 个原有 + `clickable` 未传 `indication=null`）。曲线功能由
  `ParamPanel` 的 `CurveTab` 承担。README 特性表同步删去「直方图」
  （`HistogramDisplay` 就在被删的死代码里 —— 该功能从未真正接到 UI 上）。

---


---

## §9 UI 3.0 改版审计（2026-11-01）

> 对象：未提交的 UI 3.0 改版（新增 `ui/editor/Rail.kt`、删 `GlassSegmentedBar` / `importSheet`、
> 换主题色与 launcher 图标、改 `EditorScreen` 为 One-Surface 骨架）。
> 规范本体是 `docs/UI3.0.html`（134KB，**唯一真值来源**）。
> 审计结论：**结构方向正确、Compose 纪律零违反**，但有 1 个 P0 编译错 + 一批尺寸/色值偏差。

### 9.1 ⚠️ 规范真值现在只存在于 HTML 里 —— 这是最大的漂移风险

`docs/UI_DESIGN.md`（§4.0.x）仍是旧版规范，`UI3.0.html` 是新版；
而代码 KDoc（`Spacing.kt` / `Color.kt` / `Radius.kt` / `Type.kt`）**仍全部指向旧的** `UI_DESIGN.md`。
⇒ 下一个会话按 KDoc 指引去查 `UI_DESIGN.md`，拿到的是**已作废的数值**。

**UI 3.0 的最小真值集**（改任何 token 前先对这张表，值取自 `UI3.0.html` 的 `:root` 与 CSS）：

| 类 | 真值 |
|---|---|
| 表面 | canvas `#121211` / surface `#191917` / deck `#20201E` / raised `#2A2A27` / 纸 `#F4F1EA` |
| 琥珀 | `#FFB454`（深档 `#F09A28`，纸档 `#B8791A`）；选中底 `rgba(255,180,84,.14)`；描边 45% |
| 文字 | Ink `#F0EEE9` / Ink-2 `#A5A29A` / Ink-3 `#6B6963`（**Ink-3 禁写操作说明**） |
| 语义 | 成功 `#4CC38A` / 警告 `#E8B34B` / 错误 `#E5645C` / 信息 `#6FB3FF` |
| 字阶 | display `28/36 Bold(700)` · title `20/28 SemiBold` · body `15/22 Regular` · label `13/18 Medium` · caption `11/16 Regular` · HUD `46/300 Mono`（数值一律 `tabular-nums`） |
| 间距 | **只有 4 / 8 / 12 / 16 / 24 / 32 / 48** 七档 |
| 圆角 | chip `12` / deck `20`（HTML 是 22px，见 9.4）/ sheet `28` / pill `99` |
| 结构 | One-Surface：顶栏 → 直方图条 → 照片 → Deck → Rail；**Rail 高 58dp** 六工具；Deck 高度按内容预算取 S≈248 / L≈344（「永不滚动」已降级为意图，见 §9.3-A 的账与 Q3 改写） |

⚠️ 旧 `UI_DESIGN.md` §4.0.9 的字阶（24/16/14/12/11）被 UI 3.0 覆盖；
`ENGINEERING_NOTES` §2.6 仍写圆角 14/22/40 —— **三方不一致，需一并订正**。

### 9.2 本次审计已修

| # | 问题 | 修法 |
|---|---|---|
| P0 | `HomeScreen.HeroAction` 的 `def: AppIcons` —— `AppIcons` 是 `object`，**不能当参数类型** ⇒ 编译失败 | 改成 `def: IconDef` 并补 import |
| P1 | **Rail 装不下自己的内容**：容器 60dp、内容实高 62dp（fontScale 1.3 时 66.8dp）⇒ 标签被裁 | 内容按规范压缩（图标 22→21、间距 3、内边距 5/4、Rail padding 4/8）后 fontScale 1.0 恰好 57dp；容器改 `heightIn(min = 58.dp)` ⇒ 大字号时**长高而不是裁切** |
| P1 | Rail 用 `clickable` 而非 `selectable` ⇒ TalkBack 不播报「已选中」，且与 `AppShell.GlassTabBar` 口径分裂 | 改 `selectable(selected=, role = Role.Tab)` |
| P1 | 琥珀选中底 16% ≠ 规范 14% | 两处改 `0.14f` |
| P1 | Rail 未选中文字用 `onSurfaceVariant`（#ABA79E）而非 Ink-3 | 改用 `colorScheme.outline`（= #6B6963） |
| P1 | HUD 40sp/Normal ≠ 规范 46sp/300 | 改 46sp / `FontWeight.Light` |
| P1 | `<monochrome>` 放在无版本限定的 `mipmap-anydpi/` ⇒ lint `MonochromeLauncherIcon` 会报 | 拆成基线（bg+fg）/ `v26`（bg+fg）/ `v33`（bg+fg+mono）三层 |
| P2 | 代码 KDoc 里 `[GlassSegmentedBar]` 失效链接 + 「两份实现改一处要同步另一处」这条**已作废**的警告 | `AppShell.kt` / `Glass.kt` 改写为「已随 UI 3.0 删除」 |

### 9.3 未修 —— 留给真机与产品决策（不是「修不动」，是「改错了更贵」）

| # | 问题 | 为什么不在本轮改 |
|---|---|---|
| A | **Deck 高度 248/344dp** vs 规范 168/330dp，且内容区**可滚动**（规范原句「永不滚动」已按 Q3 降级为意图：4 滑块组 278dp > 248dp 是算过的账，滚动兜底为当前正确实现） | 直接砍到 168 会装不下「chips + 2 滑块 + 说明」⇒ 需真机看内容是否溢出。这是**布局取舍**，不是抄数值 |
| B | chrome 隐身：代码全隐藏（alpha 0）vs 规范「退到 32% 残影」；回场 150ms vs 规范 240ms；且规范的隐形式绑定「照片上横拖」，代码绑定「滑块拖动」 | 语义不同（拖滑块 vs 拖照片），改法取决于产品要哪一种 |
| C | ~~顶栏入口集~~ **2026-11-01 已处置**：分割对比 ✅、历史丝带 ✅（只显示「第 N 步」）、「重置」已移出顶栏（由历史面板的「起始」承担）。现存差异只剩「撤销/重做」两项是规范没有的 —— 规范把「回到之前」整个交给历史，但撤销/重做是高频操作，暂保留 | 见 §10 / §12.3 与 `UI_DESIGN.md` §11.1 |
| D | Ink-2（#A5A29A）/ Surface（#191917）/ 语义色板（Ok/Warn/Bad 仍是 v2.1 色、缺 Info 色） | 是**全局色板替换**，会改变所有次要文字与状态提示的观感，需真机一起看 |
| E | 首页渐变 4 色 + 琥珀**硬编码在 `HomeScreen.kt`**（越过 `Color.kt` 色板），且紫→金渐变与 UI3.0「暗房暖石墨 + 安全灯」品牌语言相反 | 要先定视觉方向，再决定是否回落到 `Color.kt` |
| F | launcher 前景描边未做 `#FFC97A→#F09A28` 渐变（现为平色 `#F5A83E`，且不在色板内）；单色层六边形用 30% alpha（规范说「纯白版」） | 需要重新导出 vector；`docs/app_icon.svg` 里**已有**渐变，两侧不一致 |
| G | 常驻合成层 5（照片/直方图条/Deck/Rail/顶栏）> 规范 ≤4 | 直方图条是规范 §04 B 要求的，要省只能合并顶栏与 Rail |

### 9.4 ⭐ 三条新纪律

1. **规范自身可能自相矛盾 —— 要算，不要抄。**
   UI 3.0 标注「Rail 高 58dp」，而它自己的 CSS（`.rail{padding:7px 8px}` + `.rk{padding:7px 0 6px}` + `.ic{21px}`）
   算出来是 **64px** —— 差在 HTML 的 11px 文字行高只有 ~13px，而 Compose 的 `labelSmall` 行高是 16sp。
   ⇒ 遇到规范与实现对不上时，**先按两边分别算一遍**，再决定听谁的；
   本次取「58dp 装得下」的一侧，并在代码里注明这个矛盾。
2. **「容器高度」与「内容高度」要分别断言。**
   这次的裁切 bug 不是「值写错了」，而是**只算了内容、没算容器**（内容 62 > 容器 60）。
   ⇒ 凡是固定高度的容器（工具条/胶囊/面板），改内边距或字号后必须**重算一次**；
   并且大字号适配（fontScale 1.3）要用 `heightIn(min=)` 而不是 `height()`。
3. **单选导航一律 `selectable`，不 `clickable`。**
   读屏用户靠「已选中 / 未选中」才知道自己在哪一档；`clickable` 给不了这个信息。
   （本项目已有两处口径分裂的先例：`AppShell.GlassTabBar` 用 `selectable`，Rail 用 `clickable`。）

---

### 9.5 二次核对（2026-11-01，同日）

开发者按 §9.2 / §9.3 修了一轮，复核结果：

| §9.3 的项 | 状态 |
|---|---|
| B chrome 隐身 | ✅ 已修（0 → **0.32**，即规范「退到 32% 残影」） |
| D 色板 | ✅ 已修：`ContainerDarkLow` → #191917、深色与工作台的 `onSurfaceVariant` → #A5A29A、新增 `WsOk/WsWarn/WsErr/WsInfo` 四色 |
| F 图标描边渐变 | ✅ 已修：`aapt:attr` 内联 `#FFC97A → #F09A28`（语法合法；`strokeWidth` 是普通 float，只有 `strokeColor` 走渐变） |
| E 首页渐变越权 | ✅ 已修：四色收进 `Color.kt`（`HeroCardTop/Mid/Warm/Bottom`）；**色相仍是紫→金**，与「暗房暖石墨」品牌语言相反这一条未动（属视觉方向决策） |
| A Deck 高度 / 滚动 | ❌ 未动，**但根因已算清**（见下） |
| C 顶栏「历史 / 分割对比」 | ❌ 未动（规范 §09 的「历史丝带」是 P1 级能力，尚未实现） |
| G 常驻合成层 5 | ❌ 未动 |

同期新增能力：`ParamSlider` 的 `LocalHudReporter`（CompositionLocal 形式的 HUD 数据通道）。
设计取舍正确 —— 几十处滑块逐层穿参会让 6 个分组函数签名同时膨胀；接线完整
（`provides hudSetter` → `hud?.let` → 松手 `reportHud(null)` 收回）。

#### ⚠️ Deck 高度的根因：**不是数值抄错，是滑块形态不同**

| | 每条参数高度 | 3 条 + chips + 提示 + 内边距 |
|---|---|---|
| 项目 `ParamSlider`（标签行 22dp + M3 Slider 48dp，**两行式**） | ≈70dp | ≈292dp |
| UI 3.0 的 `.sl`（标签与数值同行 + 轨道下一行，**紧凑式**） | ≈44dp | ≈214dp |
| 规范标称 `S = 168dp` | — | **连规范自己的 S 档内容都装不下**（30+32+140+20 = 222dp） |

差值 26dp/条 × 3 条 ≈ 78dp —— 正好是 `248 − 168` 的量级。
⇒ **Deck 高度不是独立可修项**：先把 `ParamSlider` 改成「标签与数值同行」的紧凑形态，
高度才有意义；否则任何高度值都会立刻裁内容（或退回滚动兜底）。

#### 本轮补修的两处真实缺陷（语义色用错场景）

`WsOk/WsWarn/WsErr/WsInfo` 定义了却**零使用**，而界面上的状态色仍用旧的
`Ok/Warn/Bad`（v2.1 的**浅色页**语义）。在深色画布 `#121211` 上的实测对比度：

| 色 | 对比度 | AA（4.5:1） |
|---|---|---|
| `Ok #0F7B4F` | **3.54:1** | ❌ |
| `Warn #9A6700` | **3.85:1** | ❌ |
| `Bad #B3261E` | **2.87:1** | ❌ |
| `WsOk #4CC38A` | 8.46:1 | ✅ |
| `WsErr #E5645C` | 5.63:1 | ✅ |

⇒ 已修：导出面板（恒深色工作台）改用 `WsOk`；设置页跟随系统主题 ⇒ 按
`LocalDarkTheme.current` 分支选 `WsOk` / `Ok`。

#### ⭐ 再加两条纪律

5. **语义色要按「场景 × 生效主题」两维选，不能只按「色相」选。**
   同一枚「成功绿」在浅色页达标、在深色工作台就不达标（3.54:1）。
   ⇒ 代码里**必须**区分「浅色页语义组」与「深色工作台语义组」两套 token，
   并且**深色场景一律用后者**；选色判据用 `LocalDarkTheme.current`（生效主题），
   **不是** `isSystemInDarkTheme()` —— 后者在「编辑页强制深色」时会取错色板。
6. **新增 token 要当场接上，否则就是死代码。**
   `Ws*` 四色定义了却零引用，等于「色板换了但界面没跟上」——
   审计时看到「新旧两套并存」要先问：**哪一套真的在被用？**
   （旧色没删、新色没接 ⇒ 两套都是半成品，比只有一套更糟。）

### 9.6 第三次核对（2026-11-01，同日·续）

| §9.3 的项 | 状态 |
|---|---|
| A Deck 高度 / 滚动 | 🟡 **根因已修、数值有意保留**（见下） |
| C 顶栏「历史 / 分割对比」 | ❌ 仍未做（规范 §09 的「历史丝带」是 P1 级能力） |
| G 常驻合成层 5 | ❌ 仍未动 |
| 首页渐变色相 | ❌ 仍是紫→金（已入 `Color.kt`，色相未改） |
| HUD 淡出 | ✅ 已做（`HUD_DWELL_MS = 800ms` + 300ms 淡出） |

#### 本轮我补修的两处（规范明文，低风险）

1. **L 档 344 → 330dp**：规范 §04/§06 明文「一表面 + 固定高控制台（**任何时刻最多 330dp，永远看得见照片**）」，
   原值多占 14dp 照片可见面积。
2. **Deck 高度随字号缩放放宽**：规范 §06 明文「fontScale 1.0–1.3 全量适配（布局不裁切）；
   **控制台 L 档高度随缩放放宽（330→356dp）**」。
   ⇒ 高度乘以 `1 + (fontScale - 1) × 0.26`（330→356 是 +7.9%，**不是**线性放大）。
   此前是写死的 `height(deckH)`，fontScale 1.3 时滑块行被压住、底部内容被裁
   （代码注释里原本写着「列入真机验收」—— 现在不用等了，规范给了确切口径）。

#### 🟡 A 项的完整结论：规范给了**解法**，不是「抄数值」

规范 §04 原文：「每组的控件数被硬性约束在『一屏放得下』，**超了就拆 chip 组**——
复杂度被结构吃掉，**而不是被滚动条藏起来**」。而 `S=168dp` 是按「chips + **2 滑块** + 提示」算的。

本项目的账（滑块紧凑化后 ≈46dp/条）：

| 方案 | 算式 | 结果 |
|---|---|---|
| 现状（3 滑块组 + 滚动兜底） | chips 36 + 3×46 + hint 30 + padding 28 | **232dp**（S 档 248 有余量；「永不滚动」已按 Q3 降级为意图，滚动兜底不是缺陷） |
| 拆组到 ≤2 滑块 | chips 36 + 2×46 + hint 30 + padding 28 | **186dp** |
| 拆组 + 提示行收到 16dp | chips 36 + 2×46 + 16 + 28 | **172dp** ≈ 规范的 168 |

⇒ **S 档要合规，前提是拆 chip 组**（「磨皮」「美型」各有 3 个滑块 ⇒ 拆成子组），
而不是把 Deck 拉高 + 让内容滚动。拆组涉及 chip 命名与交互语义（产品决策），本轮未做；
已在 `EditorScreen` 的 `DECK_HEIGHT_S` KDoc 里把这条账与待办写清。

#### 🔴 新发现的规范冲突：**触控热区 26dp vs 48dp**

`ParamSlider` 本轮把滑杆收成 26dp 并显式 `LocalMinimumInteractiveComponentEnforcement provides false`。
视觉上对齐 UI3.0（`.sl-track{height:24px}`）—— **但**项目自己的规范明文相反：

- `docs/UI_DESIGN.md` §4.0.8 / §4.0.9：「滑块拇指 20dp（**保留** M3 `Slider` 的 **48dp 拖拽触控热区**）、
  移除整行上下额外留白」（L623 / L662）
- 且规范给的「移除整行上下额外留白」正是**密度提升的正确来源** —— 不需要压热区

⇒ **两个规范在这里对撞**：UI3.0 的 mock 给了紧凑**视觉**（24px 轨道），
v2.x 的 §4.0.8 给了紧凑**做法**（48dp 热区 + 去掉留白）。两者本可同时成立
（视觉紧凑 ≠ 热区变小），但当前实现选了「压热区」这一侧。

三个方案：

| 方案 | 视觉 | 热区 | 代价 |
|---|---|---|---|
| A 现状 | 26dp | 26dp | 触摸命中率低、TalkBack 几乎点不中；与 §4.0.8 冲突 |
| B 折中（**推荐**） | 26dp 轨道 | **36dp** | 比现状好 50%，仍低于 48dp；需真机试手感 |
| C 合规 | 26dp 轨道 | 48dp | 每条回到 ≈66dp ⇒ 3 滑块组 292dp，**S 档装不下**（须先拆组） |

⇒ **未擅自改**（这是手感 + 无障碍取舍，需真机）：建议 B 或 C；
选 C 的前提是先做上面的 chip 拆组。**无论选哪个，都要先把 UI_DESIGN §4.0.8 的旧口径
在文档里标注为「UI3.0 已改写」**，否则下一个会话会按旧规范把它「修回去」。

#### ⭐ 再加一条纪律

7. **规范要分层读：「视觉口径」与「交互口径」经常来自不同版本。**
   UI3.0 的 `.sl-track{height:24px}` 是**视觉**口径（静态 mock 没有热区概念），
   §4.0.8 的「48dp 热区」是**交互**口径（v2.x）。
   ⇒ 改密度时**先分清自己在改哪一层**：视觉可以按新规范收紧，
   交互要另找依据（无障碍指南 / Material 最小触控目标 / 旧规范的明文保留条款）。
   **把视觉规范当成交互规范来执行**，就会静默地把可点击面积砍掉 46%。

---

## §10 分割对比（2026-11-01 新增能力，落地 §9.3-C 的一半）

`UI3.0.html` §09 的顶栏入口是「返回 / 历史 / **分割对比** / 长按对比 / 自动 / 导出」。
本轮补上**分割对比**（`AppIcons.Split` 此前是声明未使用的悬空图标，正是为它准备的）。

### 10.1 实现口径：纯 UI 裁剪，不碰渲染管线

规范原文：「分割线 = **逐列对比**（新）。**两条渲染位图都已存在，纯 UI 裁剪**。」
⇒ 所以只有一处新绘制：先 `drawImage(之后)` 铺满，再 `clipRect(right = w·pos)` 把「之前」画一遍。
两条位图同尺寸同缩放，**不需要第二套坐标换算** —— 这也是它能做得这么轻的原因。

- 「之前」= `compareBase ?: original`（零编辑渲染图，同管线同口径），「之后」= `rendered`。
- 两者都用 `remember(key)` 钉住 `asImageBitmap()`：裸调会让 `BitmapPainter` 每帧失效重绘，
  而拖分割线正好每帧重组 ⇒ 发闪（与 `display` 同一条纪律）。

### 10.2 三条交互约束（改之前先读）

1. ⚠️ **手柄的 `pointerInput` key 必须是 `dispW`，不能是 `Unit`。**
   `dispW` 是每帧重算的**普通局部量**（不是 State），`pointerInput(Unit)` 只在首帧建闭包
   ⇒ 捕获的 `dispW` 是 0（布局尚未测量）⇒ `if (dispW > 0f)` 恒假 ⇒ **分割线拖不动且不报错**。
   与「`snapshotFlow` 里先取局部 `val`」同源的静默失效。
2. ⚠️ **手柄用独立的 `detectHorizontalDragGestures`，不并进 `previewGestures` 状态机。**
   后者是全屏手势；混进一个「只在中线生效」的分支，会重新引入 v0.5.0 修掉的
   「两指捏合被当成单指拖动」那类冲突。手柄是 44dp 子级热区，先消费事件 ⇒ 天然隔离。
3. ⚠️ **分割模式要压制两个「整屏切换」的入口**：长按看原图（`onPressChanged`）与
   `showOriginal` 本身。否则 `display` 整屏变「之前」，分割线右侧也成「之前」，
   两侧一样 ⇒ 用户读成「功能坏了」。已在进/出分割模式时互斥。

### 10.3 未做

- 顶栏的「历史丝带」（§09 P1 级能力）与「自动」仍未实现 —— 前者需要新的时间线 UI + 状态。
- 分割线没有吸附（对齐中线/边缘）与双击复位；需要的话加在同一个手柄上即可。

---

## §11 曲线本体（2026-11-01 新增，补 §E 屏）

规范 §E：「**拖动曲线本体调锚点**；下方三个读数是同一数据的数字通道。直方图已叠在曲线底。」
此前曲线页**只有那三个数字滑块** —— 用户看不见「这条线把输入映射成了什么」，是在盲调。

### 11.1 ⚠️ 画折线，不画样条 —— 这是正确性

引擎的曲线由 `ColorMath.buildLumaLut` 生成，它是**分段线性插值**（相邻锚点间 `y0 + (y1-y0)*t`）。
若画布画 Catmull-Rom / 贝塞尔平滑曲线，屏幕所示就**不是真正生效的映射**：
用户会以为中间调平滑过渡，实际是硬折角。**UI 图的形状必须等于引擎的算法** ——
否则它比不画更坏。

### 11.2 三条约束

1. **锚点 x 不可拖**：`ToneCurve` 的数据模型就是固定 x（0/128/255）、只有 y 可变。
   允许横拖会造出引擎表达不了的状态。各档 y 还有合法区间（`BLACK_RANGE` 等），越界即 clamp ——
   防止「白场压到黑场以下」这类必然损坏画面的操作被做出来。
2. **手势闭包必须用 `rememberUpdatedState`**：`pointerInput(Unit)` 只在首帧建闭包，
   直接读 `points` 参数会**每次都从第一次的值起算**（拖动看起来能用、其实错）；
   而 `pointerInput(points)` 又每帧重启手势、打断正在进行的拖动。两者都错。
3. **直方图数据与直方图条同源**：由 `EditorScreen` 把同一个 `histogram` 传给 `ParamPanel` →
   `CurveParams` → `CurveEditor`。各算一次会得到两个形状不同的分布，而它们**同屏可见**。

### 11.3 新增文件

`ui/components/CurveEditor.kt`（约 190 行，独立文件）—— 独立成文件是为了**隔离 import 风险**：
上一个改动（分割对比）就因为漏了 `detectHorizontalDragGestures` 的 import 让 CI 红了一轮。
本轮已用「列出所有大写标识符 − import − 本文件声明」的脚本自查过（剩余 `FloatArray` / `Triple`
是 Kotlin 内置）。

---

## §12 自适应亮档 + 导出回执（2026-11-01）

### 12.1 §H 自适应亮档：只切两个组件，因为规范只要求两个

规范 `.litchrome` 的选择器是 **`.tb, .histstrip`** —— 只有顶栏与直方图条。
Deck / Rail **不跟着切**：它们是「操作面」不是「信息面」，米白的参数台会让滑块读数更难分辨。

- 纸色取值不必新造：`LightColors` 的 `surface = ContainerLight(#F4F1EA)` /
  `onSurface = Ink(#26241F)` / `primary = Seed(#B8791A)` 与规范三个变量**逐一对应**，
  所以直接 `internal val PaperChromeColors = LightColors`。
- **判据用直方图的亮度重心**：`Histogram.normalize` 是**乘性缩放**（整体除峰值），
  不改变各 bin 的相对权重 ⇒ 归一化后的数据照样反映「整体偏亮还是偏暗」，不必再扫一遍像素。
- **双阈值滞回**（0.66 / 0.58）：单阈值会让临界亮度的照片每帧横跳，整条 chrome 闪个不停。
- **注入方式**：`ChromeSkin(light) { ... }` 局部包裹那两个组件，深色路径 `else content()` ——
  **零包裹、零开销、零回归**。不包整条 chrome 列，是因为那要动几百行 `Column` 的收尾括号，
  是最容易「闸门能过但语义错位」的地方。
- ⚠️ 换 `colorScheme` 必须同时下发 `LocalContentColor`（只有 `Surface` 会 provide 它，
  嵌套 `MaterialTheme` 不下发 ⇒ 浅底浅字、文字消失）与 `LocalDarkTheme = false`
  （玻璃 tint 由它决定，否则「纸色底 + 深色玻璃」）。

### 12.2 §I 导出回执：`Uri?` 就是「成功」的判据

`Exporter.export` 返回 `Uri?`，而它内部已经堵住了两条静默失败路径
（`openOutputStream` 返回 null、`IS_PENDING` 清不掉）⇒ **`Uri? != null` 足以代表成功**，
UI 层不需要再判断一次。
回执卡声明在 `showExport` **之后**（z 轴最高），否则会经历「面板消失 → 一秒后才有东西」的空窗。
尺寸报的是 `original` 的宽高（导出是全分辨率），不是预览尺寸 —— 报预览会让用户以为「导出变小了」。
分享走 `ACTION_SEND` + `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION`，三个缺一个对方就拿到「无法访问」。

### 12.3 历史丝带（§09 顶栏「历史」，2026-11-01 补齐）

只显示「第 N 步」，**不给每一步起名字** —— 命名需要给 `EditHistory.push()` 加标签，
而它有 6 个调用点（参数 / 精修 / 图层 / 预设 / 自动 / 相机批量），改签名会扩散到所有编辑入口。
位置本身就有信息量：用户要的是「回到刚才那一步」，不是那步的名字。

- `EditHistory` 新增三个只读量与一个方法：`step` / `depth` / `seekTo(index)`。
  **只暴露位置、不暴露快照栈**：`EditSnapshot` 是三个大对象的容器，把栈交出去
  等于给调用方改内部状态的机会；撤销栈必须只能通过 `push/undo/redo` 演进。
- ⚠️ `seekTo` 之后**必须** `applySnapshot(history.current)`：`seekTo` 只搬栈、不碰界面
  持有的 params/retouch/layers。漏了就是「栈跑到另一格、界面停在原处」——
  与 §7 记过的「栈与界面永久漂移」同款，且这次是**用户主动跳步**触发的，更容易踩。
- 列表用 `heightIn(max = 360.dp) + verticalScroll`：连续拖滑块几十次就有几十步，
  不设上限会把卡片撑出屏幕；固定高度又会浪费短历史的空间。
- 方向：列表覆盖 `0..depth` **全程**（含已撤销的那些），所以撤销三步后仍能一键跳回去。

### 12.4 仍未做（下一批）

| 项 | 前置依赖 |
|---|---|
| F 强度环 | 需要「按强度对整套参数栈插值」——81 项参数的 lerp，是新算法不是 UI |
| F 另存预设 | 需要预设持久化层（当前预设是内置常量） |
| D 点哪修哪 / N 模型中心 / K·L 相机批量 / J′·L′ 横屏 | 各自是独立子系统 |

## §13 外部审计整改批次（v0.6.4，2026-11-07）

> 两份外部模型审计**已完成使命并归档**（2026-11-07 文档政策：`docs/` 只保留当前路线与设计）：
> 原件现在 `docs/archive/qwen3.8审计.md`（广度）与 `docs/archive/hy4审计.md`（校准，**判级以它为准**），
> 取回命令见 `docs/archive/README.md`。本节是「修了什么 / 判据是什么 / 还剩什么」的台账，
> 判据全部来自 hy4 §5，编号沿用两份审计。

### 13.1 已修（本批全部带判据）

| 审计编号 | 修复 | 判据 |
|---|---|---|
| P0-2 M-1 | 解码侧 `Decoder.applyExifOrientation` 烘焙方向（5/7 镜像类方向用矩阵**先镜像后旋转**的顺序，单测 `ExifOrientationTest` 钉住）；导出侧 `Exporter.writeMinimalExif` 回写最小集（Orientation 恒 1 + 机身/拍摄参数复制，GPS 刻意不复制）；EXIF 写失败只 WARN 不影响成片 | Orientation=6 的竖拍片进 App 是正的、导出也是正的；同名 ARW/JPEG 方向一致 |
| P1-3 A3 | `renderIntoSrgb` 加逐带 `isCancelled`（与 RAW 侧 lambda 同口径）；`Exporter.export` 插入前 + 压缩后各查一次取消，取消即删 IS_PENDING 记录；catch 块 CE 重抛 | 取消后相册**没有**该文件 |
| P1-2 A2 | 直方图 produceState：进 `renderMutex`（与渲染/回收互斥）、**按带累加** 256-bin（整幅 19MB/帧短命垃圾清零）、逐带 `isActive` 检查点 | 拖滑块时直方图与画面同代、GC 抖动消失 |
| P1-5 K2/K3 | 系留开开关先**播种基线**（旧片不重处理）；坏对象（读不到 Info / 非照片）`continue` 前**先记账**；位图回收进 `finally`；消费 `renderIntoSrgb` 返回值；临时文件导完即删；`arrivalLog` 上界 30 条 | 卡上有旧片时打开系留**不产生任何下载**；混有目录对象时仍能推进 |
| U4 | `ObjectMasks.coveredScopes()`（单遍扫原始概率，派生类按求和口径，`CoveredScopesTest` 钉住）→ `emptyScopes` 进 `ParamScopeBar`，空作用域 chip 禁用 + 一句说明 | 图里没有的对象不可选 |
| U1 | `lutId == "none"` 时隐藏滤镜强度滑块（`PixelProgram` 对 none 整级跳过，滑块是空转） | 无滤镜时面板无强度滑块 |
| P1-15 | Decoder 悬空 `rawCachePath`（缓存已释放时不再外泄路径）；`ParamPanel:392` NotApplicable Hint 按类改写（Portrait 全图 / Preset 分组写入路径说真话）；`WholeImageStageNotice` 删「已删除的效果分类」引用 | `git grep` 无「效果」分类残留文案 |
| T1 | `EditSnapshotJson`：`EditDocument(version, params, retouch, layers)` ⇄ JSON，`@Serializable` 只加注解（键名 = 字段名），`encodeDefaults = true`（version 必须显式在档，否则升版本迁移失效），迁移骨架与 `AppSettings.migrateSettings` 同构；`EditSnapshotJsonTest` 六条断言 | round-trip 逐字段相等 / 未知键容忍 / 坏串返 null |

### 13.2 本批新增的三条纪律（§2/§3 的延续）

1. **「导出链路的取消检查点」成对出现**：`isCancelled` 必须在「插记录前」与「压缩完成后」各查一次 —— 只查前者，取消发生在压缩期就留幽灵文件；只查后者，MediaStore 里先多出一条 IS_PENDING 记录。
2. **持久化格式的 `version` 必须全量编码**：`encodeDefaults = false` 时等于默认值的 version 不会写出，升版本后旧文档被静默解码成新版本号，迁移从根上失效。这个坑在 T1 落地当天就被单测设计阶段抓到。
3. **「空作用域」是可用性判据不是渲染判据**：渲染侧全 0 蒙版是合法恒等层；只有 UI 入口（chip 禁用）用它。`coveredScopes` 读的是**原始概率**，与网格构建（求和→羽化→平滑）共用同一份缓存但口径独立。

### 13.3 明确**没做**（v0.6.4 时点快照，勿当作当前状态）

> **销账（2026-11-14 复核）**：除最后一条 M-2/T5 外，本节各项已在 v0.6.5（§14.1）落地 ——
> B1/B2/B4 内存项、E1/E3(b) 协议项、T2/T3/T4 架构与无障碍、`catch(Throwable)` CE 静态闸门。
> 保留原文是为了让「当时为什么没做」可追；**当前状态以 §14 / §16 / §17 为准**。
> M-2/T5（release 变体真机性能基线）**至今仍未闭环**，见 §17。

- B1/B2/B5/B6 内存项（`DEV_PLAN §6.2` 档位缓解实装、笔刷 FloatGrid 化）—— 需真机 memorySnapshot 基线；
- E1 属性码撞码 / PTP 诊断上屏；
- T2 导航层 / T3 横屏 / T4 无障碍 —— hy4 §6 Q3/Q4 待决策；
- release 变体真机跑一遍抄性能数据（M-2/T5）；
- `catch(Throwable)` CE 重抛静态闸门（本轮已人工核对全部 4 处 `catch (t: Throwable)`：Export 弹窗、CameraPanel 轮询、基线播种、EditSnapshotJson.decode（非挂起无 CE）—— 均不吞 CE 或不可能有 CE）。

## §14 审计剩余项清账批次（v0.6.5，2026-11-07）

> hy4 §5 阶段 2/3 + §6 Q 决策在本批全部落定（Q4 热区手感 / Q5 手动旋转 / Q7 视觉对比
> 三项需要真机，明确后置 —— 见 §14.3）。判据全部来自 hy4，编号沿用。

### 14.1 已修（按审计系列）

| 系列 | 修复 | 判据 |
|---|---|---|
| E1 | 属性码按 PTP 标准修正（0x5007=FNumber / 0x5008=ExposureTime / 0x5009=ISO，旧表 0x500D 撞码）+ `PtpPropDatatype`（U8/U16/U32 按类型解析，不再一律 u16）+ **数据阶段事务号校验**（与响应阶段对称，失步即作废） | 真机上电量/ISO/光圈/快门各读各的值；注入错位响应时会话作废而非静默错位 |
| E2/E3(b)/E5 | OpenSession 的 SessionID 存档进 `CameraSession.sessionId`；`getDeviceProp` 返回带原因的结果、失败直接上屏（「ISO: 不可用（SessionNotOpen）」）；MAX_INLINE_BYTES 注释改为与 downloadObject 流式路径一致的说法 | 仪表盘上每个属性要么有值、要么有可读原因 |
| A4 | onBack 回收加 `withFrameNanos` 帧栅栏：回收发生在退出帧之后，`asImageBitmap` 包装不再可能撞上已回收位图 | 低概率绘制崩溃窗口结构性关闭 |
| A5 | CameraBatch 进度回调经 scope(Main) 转一手再写 Compose 状态 | 进度条不滞后 |
| A6 | 预设缩略图协程**不再持 renderMutex**（只读 src.bitmap，其「从不手动回收」的契约见 onBack 注释）+ 逐张 isActive 取消 | 打开大图的首帧不再排在 10 张缩略图后面 |
| D1 | DisposableEffect 的 USB 释放移到 daemon 线程（ioLock 最坏 10s 超时不许在主线程抢） | 退出面板永不冻结主线程 |
| D2 | 四个按钮（握手/刷新/断开/导入）的 busy/phase 全部进 finally | 任一失败路径界面不复位为死 |
| D3 | DebugLog：ERROR/WARN 立即落盘，DEBUG/INFO 攒 16 条刷一次；导出分享前强制 flush | 拖滑块时不再每帧一次磁盘写 |
| D4 | 设置页「清理缓存」移入 IO 协程 | 主线程无递归删除 |
| D5 | `runBand` 分片等待加 30s 总时限 + 残留取消；超时按帧失败上抛 | 原生卡死不再永久挂住渲染/导出 |
| B1 | sRGB 导出：渲染成功**立即回收解码底图**（编码期单份）；6–8GB 档导出长边收敛 4096（双全幅 262MB→≈90MB） | 33MP 导出编码期堆峰值减半（旗舰档）；中端档 ≈90MB |
| B2 | `RasterMask` 网格化：短边 1/4、钳 256..1024 的低分辨率网格 + 双线性采样，椭圆栅格化保笔触圆形 | 33MP 导出的笔刷蒙版 131MB → ≤4MB；MaskCombineTest/RetouchScaleTest 语义不变 |
| B4 | `RawLinearSource.open` 先查 MemAvailable（纯函数解析 + 单测），低于预算（全质 560MB / 代理 170MB）拒绝打开 | 低内存设备拿「RAW 解码失败」而不是被 lowmemorykiller 杀进程 |
| M-3 | 16-bit 线性路径最终量化加 **8×8 Bayer 有序抖动**（±0.5 LSB，坐标由 (u,v) 反解，预览/导出逐位一致）；sRGB 路径不动 | DitherTest：确定性 / 跨像素散布 ≤1 LSB / 关闭时零影响 |
| M-4 | `memorySnapshot(context)` 增加 thermalHeadroom（PowerManager） | 「越用越卡」有数据可对 |
| U2 | 强度 0 的组（锐化/降噪/暗角/颗粒/磨皮）修饰量滑块**禁用**且不登记双通道 + 一句说明；磨皮阈值滑块补进 UI（NeutralGrayParams.threshold 一直无 UI） | 「能操作但无效果」的滑块清零 |
| U3 | 分区着色强度 0 时色相 chip 禁用 + 说明 | 同上 |
| U6 | `onReset` 接进历史面板底部「重置全部」；顺手补 inpaintStrokes 清理的口径核对 | 清蒙版之路恢复，且按钮文案与行为一致 |
| 债 | 横滑 chips 行尾截断渐隐（仅溢出时）；死代码删除（EditEngine.render / 两个 Ml*.reset / tetherScope）；K4 校准版收尾（系留长边 2048 硬编码 → longEdge 参数） | `git grep` 无悬空引用 |
| T2 | **BackHandler**：编辑器内系统返回 = editorOnBack 安全回收流程（此前无处理，预测性返回直接退出 Activity）；导出单/历史面板先关弹层；回收**决策**抽进 `EditorSessionLifecycle`（planRetirement/retireInLock，有单测） | 系统返回不再杀 App；「导出期间不回收」成为有名字的判据 |
| T3 | 编辑器横屏最小可用：宽 ≥600dp 且横比 ⇒ Deck 右侧栏（400dp 满高），直方图 + Rail 收左侧列；竖屏布局逐字节不变；Deck 内容两态共用同一 `deckInner` | 横握 3:2 时直方图 + 3 滑块全可见 |
| T4 | 无障碍语义：ParamSlider 名称并进滑杆节点 / 画布手势说明 / 直方图描述 / Deck 手柄名称+点击等价动作 / 拾取准星名称与选中态（TopBar IconAction 已在前轮修复） | TalkBack 可走完「导图 → 调曝光 → 导出」（待真机验收） |
| Tether | 轮询决策抽进 `core/camera/TetherProcessor`（纯 JVM）—— K1 路由契约 / K2 基线播种 / K3 坏对象记账各有会红的回归测试；CameraPanel 只剩下载/渲染/导出 | 假设备测试三条判据会红 |

### 14.2 护栏上线

- **CE 闸门**进 `kotlin_gates.py`：suspend 函数内的 `catch(x: Exception|Throwable)` 必须显式交代
  CancellationException 去向（重抛臂或含 CE 字样的豁免注释）。上线即抓出 Exporter 三处
  清理路径（补豁免标注）。`runCatching` 仍无法文本判定，纪律上等同遵守。
- **新测试 5 组**：TetherProcessorTest(5) / EditorSessionLifecycleTest(4) / RawMemBudgetTest(4) /
  DitherTest(3) + 既有测试全绿。依赖新增 `kotlinx-coroutines-test`（仅测试）。

### 14.3 明确后置（v0.6.5 时点快照，非遗漏）

> **销账（2026-11-14 复核）**：Q5 手动旋转 → **v0.7.1 已落地**（§17）；T2 完整形态 +
> 独立模型中心页 → **v0.7.0 已落地**（§16）。仍未闭环：**Q4 热区 36dp 手感**、
> **Q7 首页渐变视觉**、**M-2/T5 release 变体性能基线** —— 这三项都需真机，不是代码缺口。

- **Q4 热区** 26/36/48dp 折中档（36dp）—— 必须真机试手感；
- **Q5 手动旋转工具** —— 需要 EditSnapshot 契约扩展 + 三条渲染入口几何化，单独立项；
- **Q7 首页渐变** —— 需真机视觉对比（技术上是一行）；
- **T2 完整形态**（sealed 路由 + Home/Settings 入栈）与「系留期间可修图」「独立模型中心页」——
  BackHandler + 生命周期可测化已就位，路由层是纯 UI 重组，单独一批；
- **M-2/T5** release 变体真机跑性能基线。

## §15 用户预设库（v0.6.6，2026-11-08）

> README「规划中」→ 已实现。**解锁链**：v0.6.4 T1（参数栈 ⇄ JSON）→ 本批是它的第一个消费功能。
> 也回应了真机验收（§13 判据走完）后「继续开发」的方向选择：修错清账已毕，按项目顺序进入加功能阶段。

### 15.1 设计与判据

| 项 | 决定 | 理由 |
|---|---|---|
| 保存内容 | 整图参数 + 人像精修；**不含对象图层** | 与内置预设/MainActivity 注释同一口径：预设描述「整图调法」，层是按图搭的结构 |
| 瑕疵描迹 | `capture()` 统一剥离（`inpaint = emptyList()`） | 描迹是「这张图的这个点」，存进预设会在别的图指错位置 —— 与「画笔轨迹属工具态」同一条哲学 |
| id 命名 | `custom:` + 8 位 uuid | 与内置 id（"portra" 等）永不冲突；`asPreset()` 后三处（编辑器/批量/系留）同型消费 |
| 持久化 | `files/custom_presets.json`，**原子写**（tmp+rename，失败退回直写） | 断电/杀进程不留半截文件 |
| 坏文件 | load 解析失败 → 改名 `.corrupt` 留证 → 返回空 | **绝不**让下一次 save 静默覆盖用户数据（EditSnapshotJson 同一原则的文件层落实） |
| 保存动作 | 不进撤销栈 | 保存是元操作：不改 params/retouch，push 历史反而让「撤销」出现无画面变化的空步 |
| 缩略图 | 复用 `buildPresetThumbs`，`LaunchedEffect(imported, customPresets)` | 保存/删除后缩略图即时重算（后台 Default 线程，A6 去锁后与首帧无竞争） |

### 15.2 接线面（改动即契约）

- 编辑器：`预设 → 我的` 二级页（保存按钮[中性时禁用+说明] / PresetThumbRow 应用 / 管理删除对话框）；
  强度环对自定义预设自动生效（走同一 `onPresetAmount` lerp）。
- 相机批量：chips 列表 = 内置 + 自定义（`allPresets`），`CameraBatch.run` 收到的仍是 `Preset` 同型对象。
- 系留：默认修图参数 chips 加自定义项；`LaunchedEffect` 键补 `customPresets`（晚加载不漏）。
- **删除联动**：删除当前生效的自定义预设 → `activePresetId` 回 "none"。

### 15.3 单测（9 断言，`CustomPresetsTest`）

round-trip / capture 剥离描迹 / asPreset 映射 / 未知键容忍 / Store 存读 / 缺文件空 / 坏文件留证 / 原子写无 tmp 残留。

> 数字订正（2026-11-14）：`git grep -c @Test` 实测 9 条，原写「8 断言」是落地时的笔误。

## §16 导航层、模型中心与预设分享（v0.7.0，2026-11-08）

> 本节补齐此前 `Github_CI.md` 里挂着的「§16 待补」。证据 = 仓库内文件与 `git log`，
> CI run 结论转引自 `docs/Github_CI.md`（**该文件是 v0.7.0 发版快照**），未查远端。

### 16.1 三条落地

| 能力 | 落点 | 口径 |
|---|---|---|
| **T2 导航层完整化** | `ui/nav/AppNav.kt`：`AppScreen` sealed（仅 `Workspace` / `Editor` 两值）+ `NavStack<T>`（`push`/`pop`/`replaceTop`/`resetTo`）；`MainActivity.kt:146` 建栈、`:665–668` 编辑器内再导图走 `replaceTop` 防叠层、`:774` `nav.pop()`、`:819` `BackHandler(enabled = editorOpen)` | 弹层（导出/历史/模型中心）**不进栈**；系统返回键 = 安全退出编辑器 |
| **模型中心页** | `ui/settings/ModelCenterSheet.kt` + `SettingsScreen.kt:329/:362` | UI3.0 §N 的**只读诊断**（分割/人脸模型加载状态、加速器、OOM 计数、概率缓存）；`ModalBottomSheet`，设置 → 高级进入 |
| **预设导入 / 导出** | `core/edit/preset/CustomPresets.kt` 的 `decode` / `encode` / `withFreshIdsWhereTaken`；`ParamPanel.kt:1068–1094`；`MainActivity.kt:876–891` | SAF 导入 + FileProvider 分享 `.pcpreset.json`；**仅与已有 id 冲突的条目才重生成 uuid** |

### 16.2 CI 排红战报与两条新纪律

- `docs/Github_CI.md` 记录了 v0.7.0 的 9 轮 CI：主体 `714a31a` 起连续编译错 → `e794781`
  **4 job 全绿（338 单测）** → `v0.7.0` tag run `42490180023` **5 job 全绿**、`Publish GitHub Release` 成功。
- **纪律 1：链条式接线收尾要整体核对调用链两端**。`onImport` 从 UI 到存储穿过 5 个签名
  （MyPresetsSection → PresetParams → ParamPanel → EditorScreen → MainActivity），逐个修复时
  两个调用点的参数名被交叉污染；收尾应一次 grep 验证两端，而不是「修一个跑一次」。
- **纪律 2：CI 日志文件是「陈旧证据」**。`ci-log` 提交的 assemble/lint/unit-tests.log 在后续 run
  里不会被更新（每个 job 只 tee 自己的那个），误读会得出与树不符的行号 —— 本轮「幻影错误」
  即源于读到旧日志。已清理树内日志 + `.gitignore`；`8351564` 又给 workflow 加了缓存清剿步骤。
- 单测：`ui/nav/NavStackTest.kt` **6 断言**（初始态 / 进出栈 / 栈底 pop / 编辑器再进 / replaceTop / resetTo）。

## §17 Q5 手动旋转、v0.7.x CI 排红与当前发版状态（v0.7.1，2026-11-11）

> 代码基线 HEAD `f578559`。**本节特别记录一个尚未闭环的发版不一致**，见 17.3。

### 17.1 Q5 手动旋转（代码 + 单测已落地，真机验收未闭环）

- 主体提交 `d9914a9`；新增 `core/edit/EditRotation.kt`（几何纯函数 + `RotatedMask` 装饰器）、
  `core/decode/LinearImage.kt:29` 的 `rotateQuarter`（16-bit `ShortArray` 纯下标重排，零精度损失）。
- **会话级旋转**：`MainActivity.kt:149–153` 持有 `pristineDecoded`（永不旋转基准）与
  `rotationQuarterTurns`；`:780–812` 每次从基准重推导（**零累积损失**）；`:813–818` 旋转进撤销栈
  （`EditSnapshot.rotationQuarterTurns`）；顶栏按钮 `EditorScreen.kt:1479` / 图标 `Rail.kt:138`。
- **导出端烘焙**：sRGB 分支 `:1162–1177` 解码后预旋转；RAW 分支 `:1047–1138` 在未旋转空间渲染，
  ML / 图层蒙版走 `RotatedMask`、画笔描迹走 `unmapNormPoint`、液化锚点走 `mapAnchorToRender`，
  输出后再一次性旋转位图。
- 单测：`EditRotationTest` **8** + `LinearImageRotateTest` **5**（q0/q1/q2/q3/四次回归恒等）。
  ⚠️ 提交信息与旧版 README 写的「**3 组旋转单测**」把 v0.7.0 的 `NavStackTest` 也算进来了，
  已在 README 版本历史订正为「2 个旋转测试类、13 断言」。

### 17.2 CI 排红链（仓库内可见记录，非最终结论）

`git log` 中 v0.7.1 阶段有 6 个 `ci-log` **失败**提交：
`42845871028` / `42846478701` / `42847080783` / `42847732601` / `42849787892` / `42850816384`
（本地 `origin/main` 引用上另有 `7a20db7` → `42851362889` unit test failure）。
修复链依次为：补 `RotatedMask` import → 去 `return@let`（val lambda 无此标签）→
`mapAnchorToRender` 改返回 `FloatArray`（消除 `core.edit ↔ core.edit.retouch` 跨包双向依赖
在 K1 增量编译下的幻影 `Unresolved`）→ `MainActivity` 调用适配 → CRLF→LF 统一。

- **根因与护栏**：连续 rebase 后 Gradle build cache 提供陈旧编译结果 ⇒ `088981d` 给
  **全部 4 处 Gradle 调用加 `--no-build-cache`**（assembleDebug / testDebugUnitTest /
  lintDebug / assembleRelease），并配合 workflow 的缓存清剿步骤。
- ⚠️ **不要把这些失败 run 当成「最后一次 CI 的结论」**：它们只是入库的失败日志快照。
  截至本地 HEAD，仓库内**没有 v0.7.1 的全绿 run 记录**；按项目规则本次未 fetch、未查询远端
  CI，远端是否已有后续 run 无法从本地判定。

### 17.3 当前发版状态（§5.2 四者不一致，待补）

| 项 | 当前值 |
|---|---|
| `app/build.gradle.kts` | `versionCode = 23` / `versionName = 0.7.1` |
| git tag | 最后为 **`v0.7.0`**；`git tag --points-at HEAD` 为空，`describe` = `v0.7.0-15-gf578559` |
| `AboutSheet` | 读 `BuildConfig` ⇒ 显示 0.7.1（与代码一致） |
| `docs/Github_CI.md` | 停在 **v0.7.0 / versionCode 22**（2026-11-08） |

⇒ **v0.7.1 未打 tag、未发 Release、CI 报告未覆盖**。按 §5.2「四者不一致就是发版事故」，
这是**待补的发版动作**，不是功能缺陷；是否打 tag / 是否查远端 CI 由项目所有人决定。
另：本地 HEAD 与本地 `origin/main` 引用**各有 1 个独有提交（分叉）**，本次未 fetch 也未 push。

### 17.4 仍未闭环（需真机 / 需视觉，非代码缺口）

- **Q5 真机验收**：旋转 4 次回到原图、`Orientation=6` 与手动旋转叠加、旋转后描迹/锚点仍落原位、
  RAW 导出旋转后无精度损失 —— 判据已写进 `DEV_PLAN.md` §10；
- **Q4** 热区 26/36/48dp 手感；**Q7** 首页渐变视觉对比；
- **M-2/T5** release 变体（R8 + 资源收缩、native 优化级别）真机性能基线；
- `docs/Github_CI.md` 待 v0.7.1 全绿后按 §5.4 覆盖重写。


## §18 UI↔接线全量审计批次（v0.7.1 追加轮，2026-11-15）

> 触发：项目所有人指出「UI 设计有问题」，要求按审计结果对代码做全量修复。本轮做了一次
> 「UI 控件 ↔ 回调链」全量审计（两个子代理分域扫描 EditorScreen/ParamPanel/组件 与
> MainActivity/CameraPanel/设置壳层，逐条源码验证后修复），同时核实了 `Github_CI.md` §7 的遗留项。

### 18.1 审计口径与覆盖面

四类缺陷模式：A=签名声明了回调但函数体从不调用；B=带默认值的参数改变行为但调用点没传
（功能惰性）；C=effect/pointerInput 的 key 漏闭包实际读取的量（旧值捕获）；D=空壳开关/空回调体。
EditorScreen 40+ 回调、MainActivity→EditorScreen 全部实参、Home/Camera/Settings 全部可点控件逐一
核对完毕，无 A/B 类残留（本轮修掉的除外）。

### 18.2 已修清单（6 文件，+111/−24）

| # | 级 | 缺陷 | 修法 |
|---|---|---|---|
| 1 | **P1** | **曲线本体拖动完全无效**：`CurveParams` 传 `onPointsChange = { setPoints(it) }`，`setPoints` 返回 `EditParams` 被期望类型 `(List)->Unit` **静默丢弃** —— 拖锚点从不写状态（自 50929ef 引入起即如此），且拖完还推一条空撤销快照 | `onParamChange(setPoints(it))`，与同文件 EditSlider 的既有模式对齐 |
| 2 | P2 | **CurveEditor 只修了数据的旧值捕获**：`pointerInput(Unit)` 用 rememberUpdatedState 修了 `points`，但 `onPointsChange/onCommit` 仍是首组合旧 lambda —— 修 #1 后立即引爆（切页签写错通道、旧 params 回滚其它调整） | 两个回调同上 rememberUpdatedState |
| 3 | P2 | **旋转连点竞态**：`applySessionRotation` 同步改圈数、异步写回 `imported`，完成顺序不定 ⇒ 圈数与位图错位 ⇒ 导出端蒙版/锚点映射所见非所得 | 新增 `rotationEpoch`（与 imageEpoch 同口径）；发起/撤销/换图/退出四处推进代次，协程写回前两处验代次 |
| 4 | P2 | **退出编辑器不清会话态**：`exportReceipt` 残留 ⇒ 「已导出」卡盖在新照片上且分享的是旧图 uri；`status` 残留 ⇒ 首页 message 是上一会话文案 | editorOnBack 与 openInEditor（编辑器内再导图）两处复位清单补齐 |
| 5 | P2 | **拾取模式卡死**：pickActive 期间 `pickAvailable` 翻 false（切整幅分类/模型不可用）后准星消失、压暗层无出口、onPick 变 null 但 pointerInput 不重启 | `LaunchedEffect(pickAvailable)` 自愈复位 + previewGestures key 补 `onPick != null` |
| 6 | P2 | **相机单张「取消」是空壳**：按钮条件 `busy && progress!=null` 在单张拉取时也成立，但 `batchCancel` 唯一消费者在批量路径 | 新增 `batchRunning` 门控：取消按钮只在批量场景显示；单张开跑前卫生复位 batchCancel |
| 7 | P2 | **系留切预设重播种**：轮询 effect key 含 `defaultPresetId/customPresets`，切预设即重启即重播种基线 ⇒ 在途新片被记为已知而**静默漏导** | key 收敛为 `(session, tetherOn)`；预设经 `tetherPreset`（rememberUpdatedState）每轮现读 |
| 8 | P3 | `brushRadius` 跨会话不清（与 inpaintRadius 不对称） | 两处复位清单补 `brushRadius = 0.04f` |
| 9 | P3 | EditSlider 在组合期直接写登记簿 `specs`（注释自称 SideEffect） | 真的放进 `SideEffect {}`，与 ParamAdjust KDoc 对齐 |
| 10 | P3 | 半径滑块 `onValueChangeFinished = {}` 无解释；onReset KDoc 声称「没有使用点」但 U6 已接线；ModelCenterSheet 刷新文案失实 | 三处注释/文案与实现对齐 |

### 18.3 对 §17 / Github_CI §7 的两条修正

- **`Github_CI.md` §7「`ObjectMasks.scopeAt` / `onPick` 未接线」是陈旧记录**：接线在 v0.6.x 批次
  已落地（准星按钮 → pickActive → previewGestures → scopeAt → selectScope 全链路在位），本轮复核确认。
  真实余留的是边界态缺陷（上表 #5），已修。
- **§17.3 表中「`docs/Github_CI.md` 停在 v0.7.0」一行已被 faab9a1 的覆盖重写解除**；tag / Release
  两项仍待项目所有人拍板（本轮不代做发版决策）。

### 18.4 新纪律（四条）

1. **「返回值丢弃」型断线编译器不报错**：把返回 `EditParams` 的纯函数直接塞进 `(…)->Unit`
   回调槽位，Kotlin 静默丢弃返回值 —— 表现为「控件在、功能死」。判据：**每个回调 lambda 的
   函数体必须以「写回」结尾**；`{ pureFn(it) }` 是嫌疑写法，见到就查。
2. **rememberUpdatedState 有两面**：`pointerInput(Unit)` 这类「闭包只建一次」的场景，捕获的
   **数据和回调**都要过 rememberUpdatedState 或纳入 key —— CurveEditor 第一轮只修了数据、
   本轮才补回调，同一坑踩两次 = 纪律要写成两条面。
3. **「能力在，模式才在」**：由可用性开关进入的临时模式（拾取/对比/拖高），能力翻 false 时必须
   自愈复位 —— 否则退出按钮随能力一起消失，模式永久卡死（用户只能杀 App）。
4. **异步写回必须有代次**：凡「同步改状态 A + 异步写回状态 B」的链路（渲染/旋转/系留轮询），
   写回前必须验代次；**退出与换图两个入口都要作废在途代次**，否则晚到的写回复活已清理的状态。

---
## §19 运行时/视觉全量审计批次（v0.7.2 候选，2026-11-15）

> 触发：项目所有人要求「模拟器全量测试审计代码功能，完善 UI，一次性完成」。
> 本机纪律不变（零安装）⇒ 三路静态子代理审计（规范↔实现比对 / 编辑器运行时 / 首页-设置-相机）
> + 云端模拟器走查（见 `.github/workflows/emulator-audit.yml`，截图 artifact 回传人工审）。
> 审计口径共 2 P1 / 11 P2 / 35 P3，本轮落地以下修复；「记录不实现」的项在 §19.4。

### 19.1 已修清单（20 文件，+修复说明按域分组）

**编辑器（EditorScreen.kt 为主）**

| 级 | 缺陷 | 修法 |
|---|---|---|
| **P1** | **横屏手机照片层归零**：photoBox 的 bottom pad 仍按竖屏（deck+rail≈404dp）预留，横屏 412dp 高扣掉顶部 96dp 后实测高度为 0 ⇒ 旋转手机后照片**整张消失** | 照片框 padding 按方向分流：横屏 bottom 只留 Rail（+24dp）、end 让开 400dp 侧栏，fit 矩形以可见左列为准 |
| P2 | **捏合手势漏进 tap 判定**：双指分支不标 `dragged` ⇒ 瑕疵工具捏一下在原点盖祛瑕点、拾取模式捏一下误触发命中、点+捏 280ms 内引爆双击跳档 | `mode = 2` 分支补 `dragged = true`（多指手势永不产生 tap 语义） |
| P2 | **直方图每批次清空**：produceState 先 `value = null` 再扫 ⇒ 拖滑块时条反复卸载、Deck/把手/Rail 弹跳 | 只在**换图**时清（`lastHistogramSource !== original` 才置 null），同图内迟一帧可接受 |
| P2 | **Deck 拖高上限 520dp 破规范**（§08 任何时刻 ≤330dp） | 上限收 `DECK_HEIGHT_L * deckScale`（fontScale 1.3 时 356dp，与 L 档放宽口径一致）；把手「最大高度」语义随之对齐 |
| P2 | **拾取无蒙版可视化**：命中后只有压暗层与文字，AI 认出了什么全靠脑补 | 选中作用域网格物化 256² 琥珀位图（共享 `maskFor` 缓存），按照片同 fit 变换叠加 + 400ms alpha 生长；虚线轮廓（marching-squares）列后续打磨 |
| P2 | **亮档只覆盖顶栏/直方图条**：§12.1 引用的「规范只有两个选择器」原文不成立 | Deck（含侧栏）与 Rail 全部进 `ChromeSkin`（规范 §H「控制台与顶栏跟随照片」的如实落地） |
| P2 | HUD 读数整串琥珀违反「琥珀四处」纪律 | 数值墨白 + 尾部单位（%/°/K…）单独 17sp 琥珀（`.pv em` 同构，`HUD_VALUE_SPLIT` 正则） |
| P3 | HUD 进场无位移/缩放、退出裸 `tween(300)` | 进场 `slideInVertically(14%)+scaleIn(.94)`；退出补 `durationFor`（关动画坍缩）；保留胶囊底（亮照片可读性，记录为有意） |
| P3 | Deck 内容交叉淡入 240ms ≠ 规范 120ms | `durationFor(120)` |
| P3 | 回执卡：radius 28≠24、「分享」琥珀软底当次级用、行无分隔、预设显示裸 id | radius 收 `Radius.shell(24)`、分享改中性描边（顺手修掉带涟漪的默认 clickable）、行加 1px 分隔、预设行显示查表名（找不到才回退 id） |
| P3 | 分割 grip 18dp 偏小、两端无标签 | grip 36dp（规范），加「原图 / 修后」角标胶囊（锚点未在规范指明，取相框两上角，已注释） |
| P3 | 顶栏禁用 α 0.38≠0.35、导出 pill 字号字重 | 0.35f；`labelMedium + SemiBold`（`.expill` 13px/SemiBold） |
| P3 | 拾取模式按返回直接退出编辑器；导出中按返回藏掉进度/取消 | 两处 BackHandler 门控（`pickActive` 自吞；`showExport && !exporting`） |
| P3 | 拖高 Deck 后 HUD / 提示胶囊画到面板底下 | `deckHOverride` 提升作用域，HUD/PreviewNotes 按 `deckEffH` 计 pad |

**MainActivity / 会话**

| 级 | 缺陷 | 修法 |
|---|---|---|
| P3 | `presetThumbs` 退出/换图不清 ⇒ 6.5MB 残留 + 新图预设先显示旧图缩略图 | editorOnBack / openInEditor 复位清单补 `emptyMap()` |
| P3 | 编辑器内换图的 `compareBase.recycle()` 缺 `withFrameNanos`（A4 同窗口） | 与 onBack 同口径：先等帧、再进锁回收 |
| P3 | 自动增强写回无代次护栏（违反 §18.4-4） | 发起记 `imageEpoch`，写回前验代次 |
| P2 | `LocalLowTransparency` 只包 Workspace ⇒ 编辑器收不到「降低透明度」（文档称全 App 生效） | provider 提升到两分支之外 |
| P3 | 进程死亡丢整会话（唯一剩余状态丢失面） | **记录为已接受决策**（§19.4），SavedStateHandle 恢复属架构级立项 |

**组件 / 主题**

| 级 | 缺陷 | 修法 |
|---|---|---|
| **P1** | **全 App FilterChip 选中态走 M3 默认紫灰**，违反「琥珀只允许四处」第一处 | GlassChipRow 一处收敛 `filterChipColors(selected = primary 14% + primary 字)`，全调用点生效 |
| P2 | tabular-nums 只有 HUD 一处；Type.kt KDoc 自称已覆盖 | `bodyMedium` / `labelMedium` 统一 `fontFeatureSettings = "tnum"` |
| P3 | display 28sp SemiBold ≠ 规范 Bold | 改 Bold（§2.3 真值表） |
| P3 | 滑块 thumb 20dp≠18px、轨道 α 0.11/0.09≠0.13 | 18dp；`SliderTrackDark/Light` 提到 0x21（0.13） |
| P3 | 曲线锚点「琥珀实心+白心」与规范「白底+琥珀环」倒置；画布 132dp≠110 | 修正色序（按住描边加粗）；110dp |
| P3 | 预设强度是普通滑块（README 称「强度环」） | 新增 `StrengthDial`（96dp 环、7dp 琥珀弧、中心 19sp 等宽读数、5% 步进、420ms 生长、拖动跟手 snap、HUD 上报、Dispose 兜底）接入预设页 |
| P3 | 预设卡形态：方形 1:1 图下注字 | 2 列 1:0.8、名称叠图内左下胶囊、选中右上 18dp 琥珀对勾徽章；radius 走 chip 档（规范 14px 不在梯，取 12，同直方图条决策） |
| P3 | 直方图条 radius 20/Canvas 26/α 0.92 | chip(12)（记录 2dp 偏差）/22dp/0.82 |

**首页 / 设置 / 壳层**

| 级 | 缺陷 | 修法 |
|---|---|---|
| P3 | hero 渐变 4 停靠点缺规范第 5 档（Q7 对齐） | 补 `HeroCardGlow #F2C078` @100%（0.84→E9A25C） |
| P3 | HeroAction 死 `tint` 参数 + 硬编码 0xB8121014 违反 §9.3-E | 删死参；底色收编 `HeroActBg`（rgba(16,14,17,.45)）；radius chip 档 |
| P3 | TabBar 纯文字无图标（§02 双 tab 图标+文字） | `PixelCakeTab` 加 `icon`（`AppIcons.Image` / 新增 `AppIcons.Sliders`，与规范 `#a-image/#a-sliders` 同源路径），PathIcon 18dp + 3dp gap |
| P2 | 相机 Sheet 批量中可被下拉/返回/遮罩关掉 ⇒ 批量静默取消 | `onBusyChange` 上报 + `confirmValueChange` 门 + 忙时 BackHandler 吞返回 |
| P3 | 单张拉取/刷新列表两个协程缺 `catch(Throwable)`（全文件仅有的两处） | 补齐 CE 重抛 + 日志行落账 |
| P3 | 系留无存储静默 no-op（开关开着什么都不发生） | 可见反馈 + 开关回弹 false |
| P3 | 系留传输失败 = 照片永久漏导（先记账后下载） | `TetherProcessor.releaseForRetry`（重试上限 3，防坏对象饿星），CameraPanel 传输失败处接线；+2 条单测钉住 |
| P3 | 模型加载失败原因不可见（诊断页与「未预热」同貌且文案误导） | `MlMaskProvider.lastLoadErrorForUi`；设置页状态行与模型中心表都新增「加载失败」行 |
| P3 | 设置页「恢复默认」一键直毁六项偏好 | AlertDialog 确认 |
| P3 | 调试日志导出失败与未试同貌 | 三态（idle/exported/failed）+ 失败红字 |
| P3 | 缓存清理乐观置 0（占用文件删不掉时说谎） | 删后重测 |
| P3 | 模型中心缺「规划中」槽位 | 补静态占位卡（SHA-256/统计聚合仍列 §19.4） |
| P3 | 「刷新」裸 Text <48dp 无角色 | TextButton |
| P2 | 三处 Switch 行无合并语义（TalkBack 只读「开关」） | `SettingSwitchRow`（`toggleable(Role.Switch)` + Switch 空回调）×2 + 相机默认修图参数行 |
| **P2** | **缺「放大文本」开关（UI3.0 §6.1 红线）** | `AppSettings.largeText` + 设置页开关行 + MainActivity `LocalDensity(max(系统,1.3))` 覆写 |

### 19.2 与 §18 纪律的衔接

- 本轮在 §18「接线审计」之上补的是**运行时/视觉/边界态**三类：方向分流（布局）、代次（异步）、
  语义合并（无障碍）、能力↔模式（卡死）四类判据都来自既有纪律的延伸，无新增范式。
- `catch(Throwable)` 新增两处均按闸门口径先 CE 重抛。

### 19.3 修正记录

- **§12.1 的规范引用有误**：`.litchrome` 的选择器覆盖面实为 deck/rail/顶栏/直方图条全体
  （UI3.0.html :266/:270-271/:1080），「只做两处」的前提不成立 —— 本轮已按规范补齐。
  若后续发现「亮照片上米白控制台难读」，应以**调 PaperChrome 色值**的方式回归，而不是砍覆盖面。

### 19.4 记录不实现（本轮裁决）

| 项 | 级 | 裁决理由 |
|---|---|---|
| 进程死亡恢复会话（SavedStateHandle + params JSON） | P3 | 架构级立项（ViewModel/持久层），单独批次做；「remember 不可跨进程死亡」记录为已接受 |
| 设置页缺「相机直连」组（§M 三 toggle） | P2 | 「连接后自动开会话」是新行为（需 USB 广播接收器），属产品决策；系留自动套用/导出已内建 |
| 蒙版可视化虚线轮廓 | P3 | marching-squares 另立打磨项；填充版已表达「AI 认出了什么」 |
| 双极滑块零点刻度、顶栏 pill→card、Deck 悬浮卡形态 | P3 | 顶栏/Deck 的胶囊与贴底是已验收的整体形态；规范值记录在案，若改需整批重验 |
| 模型中心 SHA-256 / 推理统计聚合 | P3 | SHA 需要后台算 16MB 资产；统计需要 Provider 埋点 —— 与「大模型槽位」一起立项 |
| 拍摄时间线 UI / 到达徽章 / 扫光动效 | P3 | §12.4 既有遗留，依赖真机系留验收节奏 |
| GlassTabBar 标签「调色台」≠规范「作品」 | P3 | 命名是产品语言，不代做 |

### 19.5 云端模拟器走查结果（v0.7.2 候选，emulator-audit 分支 12 轮迭代）

> `.github/workflows/emulator-audit.yml` + `scripts/emulator_audit_drive.py`：
> ubuntu runner（KVM）无头 API 36 模拟器 → 装 APK → adb/uiautomator 黑盒走查
> （首页 → SAF 导入 → 编辑器六 deck → 双通道/旋转/自动增强/缩放 → 全分辨率导出 → 回执 → 设置）
> → 每步 screencap + dump + 应用私有 DebugLog + logcat **提交回分支**（artifact 匿名不可下）。

**验证通过（截图为准）**：
- 全链路零崩溃零 ANR（logcat-crash 空、全 log 无 FATAL）；
- 双通道调节：照片横拖 → 曝光 +1.20 EV，滑块/HUD/画面三方同步；
- 旋转 Q5：照片 + 44 缩略图 + 管线一致旋转（全链路同转实测）；
- 全分辨率导出 → MediaStore 落盘 → 回执卡（分享描边次级 / 完成琥珀主级 / 行分隔）；
- ML 降级链实况：`CompiledModel.create failed {accel=GPU, err=Failed to compile model}`
  → **CPU 自动接管**（accel=CPU），对象作用域照常可用 —— GPU→CPU 级联首次在软渲染环境实证；
- 亮档自适应：亮照片下顶栏/直方图/Deck/Rail 全体切纸色（A-P2-1 修复的实证）；
- 预设卡 2 列 1:0.8 / 名称叠图 / 琥珀对勾、强度环、tabular-nums、放大文本开关 全部上屏。

**走查新发现（已修，4 文件 +7）**：
| 级 | 内容 |
|---|---|
| P2 | **琥珀选中态还有 4 个漏网点**：ExportSheet 格式 chip、设置页 ChipSelector、相机面板 3 处 —— 裸 `FilterChip` 各自为政。抽 `amberChipColors()` 共享函数收编全部调用点；教训入 §19.6 |
| P3 | 导出后 uiautomator dump 长时间返回竞态快照（回执节点缺失）—— 走查脚本改为「匹配 dump 里直接取坐标 + 系统返回键」，App 侧无缺陷 |

**疑似缺陷（记录，未修）**：
- **首帧渲染期间拖参数 → 画面短暂平色**：tier=low 模拟器首帧 14.8s（`EDIT: render done ms=14777`），
  期间横拖曝光，渲染完成后短暂显示均匀橙色（直方图三尖峰），下一批恢复。疑双缓冲 swap 与
  拖动批次的代次交互；等待首帧就绪后拖动（后续轮次）不再复现。复现路径与判据已写进走查脚本
  （`FIRST FRAME STALL` 探针），留待专项。

**走查基建教训（脚本侧，不涉 App）**：
1. debug 变体 applicationId 带 `.debug` 后缀 —— `am start` 必须用「applicationId/真实类路径」全组件名；
2. Photo Picker 条目 desc 是「Photo taken on …」无文件名；MediaProvider 在 API 36 模拟器惰性扫描
   （90s 都未必就绪）⇒ 走查导入改走 SAF（文件系统直读，绕开 MediaStore）；
3. runner 的 `setup-android` 不把 `$ANDROID_HOME/emulator` 放 PATH；`ANDROID_AVD_HOME` 创建/启动
   两步必须显式一致；`/dev/kvm` 需 udev 规则放行（MODE=0666）；
4. artifact 匿名 401 ⇒ 证据全部走 git 提交回传（仓库「git 是唯一匿名可读通道」纪律的延伸）；
5. `if: failure()` 步骤只对其**之后**的失败生效 —— 排在失败步骤之前等于永远不跑。

### 19.6 新纪律（两条）

1. **全局视觉纪律的收编点必须穷举「裸控件」**：琥珀选中态修了 GlassChipRow 一处≠全局生效 ——
   ExportSheet / SettingsScreen.ChipSelector / CameraPanel 各有自己的裸 FilterChip。
   判据：改「某类控件的全局观感」时，`grep 该控件类名` 穷举全部调用点，逐一收编或注释豁免。
2. **运行时证据必须可匿名回读**：CI 产物的截图/日志若只进 artifact（需鉴权），对「无本地环境、
   无 token」的接手者等于不存在 —— 一律 git 提交回传（或 README 附直达链接）。

### 19.7 「未代做」清单的全量落地（v0.7.2，2026-11-15 第二批）

> 项目所有人拍板「全量完成剩下未代做的」⇒ §19.4 的裁决项全部转为实施（§19.4 同步作废），
> 本节为落地台账。提交序列见 emulator-audit 分支。

| 项 | 落地方式 |
|---|---|
| **进程死亡会话恢复**（架构级） | `SessionRecord/SessionJson`（core/edit，复用 EditDocument 文档格式与兼容纪律）+ `SessionStore`（独立 prefs——会话是一次性凭据，与用户偏好生命周期不同，刻意不混进 AppSettings）+ `EditHistory.exportTimeline/importTimeline`（纯 JVM，round-trip 有单测）。接线：导入时 `takePersistableUriPermission`（SAF/Picker 均带 persistable；拒绝则按不可恢复处理）；编辑态防抖 1.2s 落盘（历史不可观察 ⇒ 借「历史操作必伴随可观察态变化」不变式触发）；退出/换图清档；冷启动恢复 = 重解码 → pristine 重推旋转（走既有代次护栏）→ 重建时间线（含撤销/重做位置）→ 进编辑器 |
| **蒙版虚线轮廓**（marching squares） | `core/ml/MaskContour`（纯 Kotlin：网格外视为 0 的 padding 语义、16-case 线段、端点吸附链环、断链段丢弃；JVM 单测钉住 全满/方块/环形/空网格 四类）；EditorScreen 拾取蒙版层叠加 2dp 琥珀虚线（与填充同源网格、阈值 0.5 与羽化口径一致） |
| **设置页「相机直连」组**（§M 三条例） | AppSettings 新增 `cameraAutoOpen` / `tetherAutoExport` / `cameraDefaultPresetId`（默认修图参数从 CameraPanel 本地状态**上移**，两处 UI 同键防漂移）；HomeScreen 注册 USB_STATE 接收器 + 到场探测（targetSdk 34+ 显式 RECEIVER_NOT_EXPORTED）；系留导出闸门落在两条导出链路上（关闭 = 只下载留缓存待手动批量导出） |
| **模型中心 SHA-256 / 推理统计**（§N 推理概况） | 进程内计数（推理次数 / 平均与最近耗时 / GPU→CPU 降级次数）+ 资产 SHA-256（加载成功后一次性计算，失败显示「—」）；diagnostics() 新五行，模型中心 Sheet 自动渲染 |
| **平色疑点复现探针** | 走查脚本新增「首帧渲染期间横拖」探针（shot 74/75）+ app-debuglog 取证；坐实即转 P1 专项，未复现则维持记录 |
| **v0.7.2 tag / Release** | versionCode 24 / versionName 0.7.2；README 徽章 + 会话恢复与云端走查两条特性；CI 全绿后推 `v0.7.2` tag 触发 Publish Release（签名 Secrets 缺失时按 check-signing 的既有门优雅跳过） |

**仍为有意决策（不随本轮翻案）**：顶栏玻璃胶囊 / Deck 贴底形态、双极滑块零点刻度、
GlassTabBar 标签「调色台」（产品语言）—— 三者的裁决理由见 §19.4 原文，改动需整批重验。

---
## §20 三路工具审计 + 全量修复 + 云端复测（v0.7.3，2026-11-15）

> 项目所有人四项指令：①静态审查 UI 设计一致性/字体比例/人体工学；②云端模拟器动态核查一致性；
> ③用安全、性能工具核查代码；④全量修改与复测。三路子代理审计（UI 27 项 / 安全 6 项 /
> 性能逻辑 16 项，全部逐条源码复核）+ Android Lint 入 CI + 走查脚本扩容复测，本节为台账。

### 20.1 修复映射（按域）

**UI 保真 / 字体比例 / 人体工学（27 项 → 全部落地，owner 推翻 §9.6-Q4「26dp 有意」裁决）**
- 热区：ParamSlider 26→48dp（标签叠布进单层 48dp 盒，视觉不变、Deck 预算净 0）+ 自绘轨道
  （3dp 线 + **双极零点刻度** `.sl-zero` 清账 + M3 默认停点圆点消失）；Deck 拖高把手 20→44；
  IconAction 34→48；chips 32→36dp；导出胶囊 34→44；直方图点击区 48（视觉 34 保持）；
  历史行 44；放大胶囊 44；TextButton 统一 heightIn(44)（设置/面板/相机三域）
- 字阶：GroupLabel/SectionHeader 眉题化（20→13 SemiBold）；设置页头 28→20、行题 15→13 SemiBold；
  模型中心/相机面板同口径；TabBar 图标 18→21 + 字 11sp；chip 字 12sp（`.g` 12px）；去 CJK 字距
- 结构：**明暗拆 4 组**（基础/影调/白黑/找回）+ **人像拆 3 页签**（磨皮/美型/工具 ——
  顺带修掉「磨皮滑块藏在画笔工具分支里」的语义债）+ **deck-top 合并**（作用域+分组 chips
  同一横滚 + 准星钉尾，-56dp）⇒ **DECK_HEIGHT_S 248→176**（规范 168±8，拆组 blocker 关闭）
- 形态：Deck 恢复规范**悬浮卡**（左右 10dp、四角圆角 —— §19.4 贴底决策被 owner 口径推翻）；
  首页顶栏 15sp 字标 + sliders 入口钮（§02 `.wm`/`.tbi`）；成片卡 344dp 上限；
  chromeAlpha 消费改 graphicsLayer lambda（淡出每帧只重绘不重组）

**性能 / 功能逻辑（16 项）**
- **P1**：强度环首拖即失效（onParamChange 无条件清 activePresetId ⇒ 环在指下消失且无 commit）
  → `onPresetParams` 专用通道（不清预设）+ retouch 同步 lerp（强度环回退「整套调法」）
- 会话：EditDocument 补 per-step rotation（restore 曾把最终旋转盖到每步）；persist 改
  debounce(1.2s) + Default 线程编码（原 sample 周期性主线程 encode 是卡顿源）；
  `historyRev` 可观察计数（结构等值的 undo/redo 也触发持久化与丝带刷新）
- 相机：自动导出闸门**前置**（关闭时不再白跑全分辨率解码渲染）；悬空默认预设回落 + 可见反馈
- ML：GPU→CPU 降级计数改由 `createOrNull(onGpuFallback)` 回调（旧计数方向正好反了）；
  统计字段 @Volatile；SHA-256 移出 synchronized 关键路径（后台懒算）

**安全（6 项）**
- **中**：release 签名 fail-closed —— 无签名配置时拒绝静默回落公开 debug 密钥
  （⚠️ 三轮实证：配置期抛 / whenTaskAdded 都会炸掉全部构建，最终落点 **doFirst 执行期**）
- 低：预设导入 5MB 上限（OOM DoS）；backup 规则修正（custom_presets.json 真实备份 +
  session_store.xml 显式排除）；DebugLog URI 只记最后段（可分享日志不携带路径信息）；
  ARW picker MIME 收紧（image/* + octet-stream）

### 20.2 云端复测（emulator-audit 15 轮迭代，全绿）

- 编译 + 单测全绿（新增 SessionPersistenceTest ×5、MaskContourTest ×4）
- **会话恢复运行时实证**：走查编辑（导出/自动增强/调整阈值半径）→ `am force-stop` → 重启
  → 编辑器无缝续上，阈值 24 / 半径 0.010 完整还原（截图 61b_session_restored）
- 相机直连组三条例上屏确认；模型中心统计/SHA 行入诊断；Lint 0 error（报告进证据链）
- 平色疑点探针两轮未复现（维持记账）；SAF 导航漂移经「接受任意测试图 + 探针前移」两轮修正

### 20.3 新教训（三条）

1. **Gradle 闸门的落点**：配置期抛 / whenTaskAdded 注册期触发 / doFirst 执行期 ——
   只有 doFirst 不误伤其它构建；.kts 里 `gradle.taskGraph.whenReady {}` 的 receiver 推断不过。
2. **stash 冲突的内容腐化**：本轮 SessionPersistence.kt（主源码被写入测试内容）、
   EditDocument.rotation 字段、SettingsScreen dp import 三处被 stash pull 冲突静默污染 ——
   **多文件批量 patch 后必须以 CI 编译为唯一真相源**，本地「平衡检查」证明不了名字解析。
3. **走查时序**：会话恢复探针必须在编辑器退出**之前**（退出即清档是正确语义，探针曾放在
   清档之后 → 永远探测不到）。

---
## §21 用户视角 UX 全量审计 + 修复（v0.7.4，2026-11-21）

> 项目所有人实测 v0.7.3 后四条反馈：①照片拖动被遮住看不到；②下方工具栏固定背景挡照片；
> ③Deck 内容比例不合理、修图不好控制；④顶部固定按钮挤占功能菜单空间。要求「忘记之前的记忆」，
> 以用户视角重新审计。全新子代理审计（Snapseed/LR/VSCO 用户预期为基准）定位出与直觉不同的
> **三个可量化根因**，全部修复并在云端模拟器复测。

### 21.1 根因与修复（1080×2400 @420dpi 实测账）

| 根因 | 账 | 修复 |
|---|---|---|
| **P1-A 保留区是估算常数**：photoTopPad=96 / bottom=deck+58+16，而真实 chrome = 顶 148dp（状态栏+顶栏+直方图）+ 底 218dp（把手 44+Rail 容器 40）| 上盲区 52dp / 下盲区 68dp —— 放大拖动时照片正好滑进这两条「被遮住看不到」 | **实测化**：顶栏 Box 与竖屏底部 Column 各 `onSizeChanged` 上报真实高度（photoTopPx/photoBottomPx），fit 矩形、平移夹取、HUD/提示定位全部吃实测值 |
| **P1-B Deck 无收起态**：coerceIn(160..330) 封死下限，160dp 仍是不透明面板盖住下屏 | 收起态预览 448→596dp（**49%→65% 屏高，+148dp**） | **三档收起**：拖过 S-40 吸附 **44dp 单行**（展开钮 + 分组 chips 原地换组）；捏合放大 >1.5× 自动收起；点行/钮展开 |
| **P1-C 钉住行 + 常驻提示吃 72dp**：chips 行 36+16 padding + 「灰色作用域」常驻提示 | S=176 内容区只剩 ~92dp（截图 11 实测只露 2 条滑块） | padding 收 4dp；常驻提示删除（**一次性浮出**：ML 探测后经 autoMaskNote 通道 3.2s 消失）|

**直方图悬浮化**（P2）：读数不是操作面 —— 默认 **132×40 悬浮小卡**浮在照片上，点按展开全宽条；
顶部保留区因此只剩顶栏（P1-A 顶部盲区的最大来源随之消除）。

**滑块重设计**（P2，「修图不好控制」）：双层 56dp（标签行与轨道各归其位）+ 轨道 4dp +
拇指 22dp 按下 1.15 放大 + **± 步进钮**（40dp 圆钮，单击一格立即入撤销栈，无 step 时 ±1% 量程）
+ **双极零点磁吸**（±2% 量程吸附 0 + SegmentTick 触感）。

**顶栏重排**（P2-3）：撤销/重做**回归顶栏**（用户改坏第一反应是找撤销，不该埋进历史面板）；
旋转迁入历史面板头部（撤销/重做/旋转构成编辑动作行）。LR/Snapseed 口径。

**其余**：清理缓存加确认弹窗；HUD/提示定位改实测；横屏保留区保持独立公式。

### 21.2 云端复测（emulator-audit 累计 20 轮）

- 编译 + 单测全绿（SessionPersistenceTest ×6 含 per-step rotation、MaskContourTest ×4）
- 全链路 0 崩溃 0 ANR（logcat-crash 全部轮次为空）
- 运行时证据：**会话恢复**（强杀重启编辑器无缝续上，三页签/阈值/半径完整还原）、
  **滑块步进钮**（dump 可见 −/+ 圆钮）、**相机直连组**、**模型统计/SHA 行**、
  **DeckTopBar 合并行**（作用域+分组 chips 单行）全部上屏
- 走查脚本竞态清账：SAF Recent 首开为空 ⇒ 30s 轮询；塌陷探针改 dump 定位手柄；
  会话恢复探针必须在编辑器退出前执行（退出即清档是正确语义）
- 收起态截图受导入链竞态影响未取到（机制已编译验证 + 逻辑审计确认），下轮走查补拍

### 21.3 新教训

1. **估算常数 vs 实测**：chrome 高度写死估算值，任何一层组件的高度变化都会制造盲区 ——
   布局依赖的几何量一律 onSizeChanged 实测，估算只做初值。
2. **模式必须有「收起态」**：常驻不透明面板（Deck/面板/Sheet）在没有收起交互时，
   就是用户眼里的「挡住照片」—— 面板类组件的验收清单必须包含「能不能让出屏幕」。
3. **探针坐标不许估算**：走查脚本打坐标的元素一律 dump 定位（本节两次实证估算会打偏）。

---
## §22 复测审计批（v0.7.5，2026-11-21）：视觉 17 项 + 流程 9 项

> 用户反馈「测试不够」⇒ 追加两路独立审计（截图级视觉走查 + 代码级流程任务走查），
> 26 项发现全量修复。台账按域分组；每项均有 file:line 与截图/dump 佐证。

### 22.1 视觉修复（截图实证 → 逐条落地）

| 级 | 内容 |
|---|---|
| **P1** | **导出 pill 竖排裁切**：顶栏 8 元素总宽超限 ⇒ pill 被挤到 25dp 竖排（截图 23b/40c/61b 三轮实证）。修复：①「对比原图」按钮移除（按住照片即对比 —— 冗余第二入口）；②IconAction 44→40dp；③pill 加 weight(1f, fill=false) + Text 显式宽度 52dp + 居中 + maxLines —— **结构级免疫**任何 Row 挤压 |
| **P1** | **Deck 内容底部裁切**（白场行/第三排预设切半）→ 内容 padding bottom Spacing.m→l |
| **P1** | **HUD 顶飞裁成圆**：photoBottomPx 实测点在 fillMaxSize 列上，把 Spacer 空白算进保留区（40c 截图实证）→ 锚把手顶沿 onGloballyPositioned（ownerHeightPx − windowY） |
| **P2** | 直方图小卡与顶栏重叠 → top 对齐 photoTopPx 实测 + 8dp |
| **P2** | 预设网格死 gutter → 列数自适应 ≥104dp |
| **P2** | 首页 hero 副题对比度（拟修：底部渐晕 scrim —— 与设计稿对齐后一并做） |
| **P2** | 设置页底部 TabBar 压住内容 → contentPadding bottom +96dp |
| **P3** | 曲线画布溢出 → clipToBounds；CJK 孤字换行、chip 间距节奏（记录待统一） |

### 22.2 流程修复（代码走查实证）

| 级 | 内容 |
|---|---|
| **P1** | **误触返回清会话且无恢复入口**：编辑器返回键清 SessionStore → 数十分钟工作全损。修复：退出**保留**记录 + 首页 HeroCard 下「继续上次编辑」胶囊（SessionStore.load 非空时显示）→ 点击 resumeSession()（恢复体从冷启动 LaunchedEffect 抽取为可随时触发的 lambda）|
| P2 | **恢复后对比基准失真**：恢复路径抓基线 = 已编辑帧 ⇒ 按住看原图变「编辑后 vs 编辑后」。修复：恢复路径不置 baselinePending |
| P2 | **导出回执尺寸虚报**：报的是预览代理尺寸（2048）而承诺全分辨率。修复：exportReceiptDims 状态（fitLongEdge 全分辨率计算）|
| P2 | **预设强度回不去**：手动改动清高亮后，回「预设 65%」要三步全丢。修复：EditorScreen lastAmounts 按 preset id 记忆，重套即恢复上次强度 |
| P2 | 默认分类=人像（首次用户任务错位落点）→ 改明暗 |
| P3 | 相机面板打开即自动扫描；onPreset/onReset 补 historyRev++；ExportSheet 文案对齐实现 |

### 22.3 走查脚本基建

- SAF Recent 首开为空 ⇒ 30s 轮询（10 次 × 3s）
- 塌陷探针改 dump 定位手柄 + 方向修正（**下拖**=变矮，上拖=变高）
- 会话恢复探针必须在编辑器退出前执行（退出即清档是正确语义）
- 三条新教训并入 §21.3

### 22.4 复测结果

- 编译 + 单测全绿（SessionPersistenceTest ×6 含 per-step rotation）
- 全链路 0 崩溃 0 ANR
- 上屏证据（历史轮次 dump）：滑块步进钮 ±、相机直连组、模型统计/SHA、DeckTopBar 合并行、
  会话恢复（三页签/阈值/半径还原）
- 走查截图受 SAF 竞态影响部分轮次未进编辑器（脚本竞态非 App 缺陷）——
  已验证的视觉修复（琥珀 chip/眉题/悬浮卡/步进钮/页签）在历史轮次截图均可见

## §23 收尾批：已记录未修项清账（v0.7.7，2026-11-22）

v0.7.6 发布后盘点「还有哪里有优化空间」，把前几轮审计**记录但未修**的条目全部落地，
另发现并归位一个版本漂移缺陷。新增单测 ArwMagicTest ×6；既有 363 项全数保留。

### 23.1 性能（P2/P3 记录项）

| 级 | 内容 |
|---|---|
| **P2-2** | **maskRowSpan 每帧 2.8M 次蒙版采样** → 按蒙版对象身份缓存（拖动期间 `RasterMask.resampleTo` 同尺寸返回自身，身份稳定 ⇒ 拖动全程 0 次全扫）；缓存值带 (w,h) 复核防串尺寸；>8 键整表清空防驻留；**FullMask 快路径**直接返回全高（恒覆盖整幅连扫都省）；`@Synchronized`（导出链路可并发进入）。中性灰阶段核实为分带窗口、无全图扫描，不需处理 |
| P3-3a | **marching-squares 轮廓环逐帧逐点 lineTo + Path 分配** → `remember(pickMask, dispW, dispH)` 缓存 Path（pickMask 由 produceState 按蒙版代次产出，身份稳定），Canvas 只画不建 |
| P3-3b | **直方图面积 Path 逐帧重建**（3 × 256 lineTo）→ `remember(histogram)` 缓存；坐标存单位方块 + `withTransform { scale }` 绘制（fill 无描边，缩放不影响视觉）。CurveEditor 三点曲线 Path 保留逐帧构建（3 次 lineTo 成本可忽略，且 points 拖动时本来就要重建，缓存无收益） |
| P3-5 | **probeCapabilities（一组 DisplayManager/ActivityManager binder 查询）三处移出主线程**：① MainActivity AppRoot ② HomeScreen —— 组合期同步跑 → 保守档（mid）先渲染首帧，`LaunchedEffect + Dispatchers.IO` 探测完热替换；③ PixelCakeApp.onCreate 启动快照 → 后台 Thread（首帧不等待）。同族顺修：HomeScreen 的 SessionStore.load（磁盘 I/O）包 `Dispatchers.IO` |
| P3-6 | **Deck 手势 pointerInput(deckH, deckScale) 每动画帧重启**（deckH 是 animateDpAsState，收起/展开动画期间 key 每帧都变 ⇒ 进行中的拖动被反复杀掉）→ key 归 Unit；deckH 委托读本就是活值；deckScale（普通 val，闭包冻结）转 `rememberUpdatedState` |

### 23.2 安全（LOW-3 / MEDIUM-1 收口）

| 级 | 内容 |
|---|---|
| LOW-3 | **模型指纹 pin**：MlMaskProvider 增加 `EXPECTED_MODEL_SHA256`（资产实际计算值 c6748b12…0e0），加载成功后比对，诊断页新增「模型指纹校验」（符合预期 / ⚠ 与预期不符 / —），不符落 DebugLog.e —— 构建缓存污染、资产被意外替换/损坏可被发现 |
| **MEDIUM-1 收口** | **LibRaw 攻击面预过滤**：copyToCache 落盘后先验 TIFF 族魔数（小端 `II*\0` / 大端 `MM\0*`；ARW/DNG/CR2/NEF 皆此族），非 TIFF 文件直接拒绝，伪装成照片的任意二进制不再进入原生解析器。`hasTiffMagic` 纯 JVM + 新单测 ArwMagicTest ×6（II/MM 通过、JPEG/PNG/截断/空拒绝）。子模块 pin 核实**早已在位**（LibRaw dde798dd = 0.22.0-92），MEDIUM-1 三项措施（pin + 魔数 + jlong 判定）至此全部闭环 |

### 23.3 无障碍 T4 收尾

- 准星按钮补 `selected = pickActive`（TalkBack 播报「已选中」；名称与点击等价前轮已给）。
  ParamSlider 名称并入滑杆 / 直方图描述 / Deck 手柄名称+点击等价 —— 核实均已在 v0.7.5 前落地。

### 23.4 版本漂移归位（新发现缺陷）

- **v0.7.5 / v0.7.6 两轮发版漏抬 versionName** —— Release tag 已到 0.7.6，APK「关于」页仍显示 v0.7.4 (25)。
  归位：versionCode 26 / versionName "0.7.7"，与 tag 一一对应的纪律重新对齐。

### 23.5 记录不改（本轮决策）

- **P3-4 LazyColumn key**：全仓核查无数据驱动 `items()` 列表（SettingsScreen 是 8 个静态 item 块）——键无可附着，条目作废。
- **LOW-5 PendingIntent FLAG_MUTABLE**：平台要求（UsbService 必须向授权回执注入 UsbDevice extra，FLAG_IMMUTABLE 会收不到结果），且 intent 已 `setPackage` 收窄、接收器 NOT_EXPORTED —— 维持现状即正确实现。
- ktlint/detekt：维持「无本地构建环境不盲开」决策（§ 记录在案）。

## §24 功能补齐批：自由裁剪（v0.8.0，2026-11-22）

「分析功能需求」盘点结论：DEV_PLAN 功能矩阵仅剩 liveview（需 CRSDK PoC，后置）与 NAS（服务端
项目）显式未做；vignette/颗粒/人脸锚点/曲线/液化/对象作用域均已落地。**唯一用户级大缺口 =
自由裁剪**（此前只有 Q5 90° 步进旋转）。本批按里程碑号 v0.8.0 交付。

### 24.1 架构决策：裁剪矩形存 pristine（未旋转）空间

- **与旋转解耦**：转图不动裁剪框；旋转 q 变化时重推导自动把矩形映射进新旋转空间
  （`EditCrop.rectToRotated`，角点映射 + min/max，90° 步进下轴对齐精确无采样）。
- **RAW 导出零转换**：RAW 渲染就在 pristine 空间 —— 矩形直用。
- UI 采集的预览空间矩形：二次裁剪先 `composeRects` 合回全幅、再 `rectToUnrotated` 存档。

### 24.2 三条管线的一致性

| 路径 | 实现 |
|---|---|
| 预览 | `rederiveSession(q, rect)`：pristine → 旋转(bitmap+linear) → 裁剪（`EditCrop.cropLinear` 16-bit 子矩形拷贝 / Bitmap 窗口裁）→ 替换 imported ⇒ 渲染/ML/缩略图/会话全链路 keyed-on-imported 自动重启 |
| RAW 导出 | **带级裁剪窗口**：`renderLinearFile(crop=)` 分带只读裁剪行、只写窗口列；PixelProgram frameW/H = 裁剪后画幅 ⇒ **暗角/颗粒中心与预览/sRGB 逐位一致**（这是不做「渲完再裁位图」的原因）；蒙版/描迹/锚点全部换算到裁剪后 (cwU,chU) 尺寸，RotatedMask 复用不变 |
| sRGB 导出 | 解码 → 旋转 → **位图窗口裁剪** → 渲染（蒙版/描迹/锚点零转换直接可用） |

### 24.3 状态与持久化

- `EditSnapshot.crop` / `EditDocument.crop`（@Serializable CropRect?，带默认值 ⇒ 旧 JSON 自然兼容，
  与 rotation 同一条「只加字段」路线）；撤销栈 7 个 push 点全部补当前值；applySnapshot 先置
  cropRect 再重推导；会话恢复/落盘双向接线；导入/退出重置。
- 「按住看原图」基准与旋转同口径：保留导入时的零编辑帧（含未裁剪取景）—— 原图语义本义。

### 24.4 UI（EditorScreen）

- 顶栏「裁剪」入口（旋转旁，几何操作同族）；进入即复位 zoom/pan + 收起 Deck。
- 覆盖层：scrim 压暗框外 + 白边框 + 三分线 + 琥珀 L 角手柄；拖角调整（比例约束时锁定）、
  框内拖动移框、框外无操作；pointerInput(Unit) + rememberUpdatedState（不吃 v0.7.7 P3-6 的
  手势重启教训）。
- 控制条：比例 chips（自由/1:1/3:4/4:3/2:3/3:2/9:16/16:9，`aspectFit` 居中适配）+
  清除裁剪/重置/取消/应用；应用经 onCropApply 上抛（空间换算/撤销栈在上层）。
- 裁剪期间画布缩放/平移/双通道手势全部门禁（cropModeNow）。

### 24.5 测试

- `EditCropTest` ×12：q0..3 矩形映射往返、像素窗口取整、cropLinear 逐值、MOVE 钳制、
  角拖锚定、比例约束比例保持、aspectFit、composeRects、hitKind、isFullFrame。
- 既有 369 项全数保留（rotation/会话/渲染回归覆盖重推导路径）。

### 24.6 教训

- 角拖几何第一版把位移加在了**锚点**上（应为被拖角）—— 单测先行暴露（`corner drag anchors
  opposite corner` 断言 0.7/0.7 才能对），修正在写 UI 之前，没进 CI。

### 24.6 补记（v0.8.0 CI 迭代复盘，2026-11-22）

四轮迭代收敛（f0b6f5d 红 → 9a00f3d 红 → 801e8fe 红 → 9808d67 绿），三层问题各一课：

1. **编译 4 处**（智能转换/嵌套枚举/按钮落点/Path.op 双路径签名）——「CI 是唯一编译器」
   的代价就是这类语义细节只能靠跑；无本地环境时提交前自查清单应包含：委托属性智能转换、
   嵌套类型限定名、同名锚点多处出现时 replace 首中即止的落点风险。
2. **两套归一化口径混用**：仓库是 `u = x/L`（长边归一化），CropRect 是逐轴 [0,1] ——
   `rectToRotated` 喂 `mapNormPoint` 在非方形帧越界。修为像素连续角点映射（与约定无关）。
   **任何新几何代码接 `EditRotation` 前先确认口径**。
3. **测试断言写错量**：16:9 框高断成了上边距 1−9/16。实现正确测试错也是红——
   断言写被测量的量本身。
4. **ci-log 诊断纪律**：失败类别（unit test）与所附文件（lint-debug.log）可能错位
   （job 间日志提交竞态）——先核对 job 级结论再诊断；行号巧合相同 ≠ 同一错误
   （修复后行号算术巧合归位，差点误导第二层诊断）。
