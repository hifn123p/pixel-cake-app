---
title: 像素蛋糕 App — 工程笔记（硬约束与踩坑清单）
status: active
created: 2026-10-06
updated: 2026-10-07
project: D:\AI_Project
description: 从本地工作记忆（.workbuddy/memory）提炼的、跨会话持久有效的工程纪律——仓库与工具链约定、推送/CI/发版实操、Compose 布局陷阱、渲染/并发纪律、设置与构建。这些条目每一条都是被真机 bug 或一次返工逼出来的，改动相关代码前请先读完对应小节。
---

# 工程笔记（硬约束与踩坑清单）

> **这份文档的存在理由**：本地工作记忆（`.workbuddy/memory/`）在 `.gitignore` 里，**不入库**。
> 换机器 / 换 IDE 之后那份记忆会断档，于是把**跨会话持久有效**的部分提炼到这里，随仓库一起走。
>
> 阅读顺序建议：**§1 先看**（它决定了「改完谁来编译」这个根本前提），然后按需要跳到 §2~§5。
> **本文每一条都是被一次真机 bug 或一轮返工逼出来的**，不是风格偏好 —— 看到「⚠️」请当作硬约束。
>
> 相关文档：[`DEV_PLAN.md`](DEV_PLAN.md)（计划）、[`PHASE_DESIGN_HISTORY.md`](PHASE_DESIGN_HISTORY.md)（阶段设计存档）、
> [`UI_DESIGN.md`](UI_DESIGN.md)（界面规范 + 真机反馈修正表 §4.0.x）、[`TONING_DESIGN.md`](TONING_DESIGN.md)、
> [`OBJECT_TONE_DESIGN.md`](OBJECT_TONE_DESIGN.md)、[`Github_CI.md`](Github_CI.md)（最近一次 CI 报告）。

## 目录

| 节 | 内容 |
|---|---|
| [§1](#1-仓库与工具链决定了一切前提) | 仓库与工具链（决定了一切前提） |
| [§2](#2-compose-布局硬约束) | Compose 布局硬约束 |
| [§3](#3-渲染与并发纪律) | 渲染与并发纪律 |
| [§4](#4-设置模型与依赖) | 设置、模型与依赖 |
| [§5](#5-构建ci-与发版) | 构建、CI 与发版 |
| [§6](#6-排障姿态三句话) | 排障姿态（三句话） |

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

- **圆角梯级 = 内容小、容器大**：内容（照片 / 缩略图）用 `Radius.chip`(14dp)，
  卡片 `Radius.card`(22dp)，面板 / 整表 `Radius.shell`(40dp)。
  给内容套容器半径 = 用装饰吃掉信息。
- **顶栏 / 工具条高度绑 `Spacing.controlHeight`（当前 48dp）**，不要写死 `44.dp` / `56.dp`。
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

**⚠️ push 必须走 `ssh.github.com:443`**：本机 `hosts` 把 `github.com` 黑洞到 `127.0.0.1`
（连带 `api.github.com` / `raw.githubusercontent.com`），直连 22 端口 = `Connection refused` / `reset`。
**只在单条命令上临时覆盖，不改仓库 / 全局配置**：

```bash
GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=no -o ConnectTimeout=20 -p 443 -o HostName=ssh.github.com" \
  git push origin main
```

- **全量推送**：`git add -A` 提交**全部**改动，不挑拣、不逐项询问；
  **fast-forward，绝不 force push / 改历史**。
- **commit / tag 的消息一律走实体文件**：`git commit -F <msgfile>`（`tag` 同理）。
  - ⚠️ `-m` 里的**反引号会被 bash 当命令替换执行** ⇒ 消息里被反引号包住的标识符
    **静默消失**（还吐 `xxx: command not found`）；
  - ⚠️ `-F /dev/stdin` 在本机 shell **读不到**（`/proc/self/fd/0` 不存在）⇒ 必须写**真实文件**。
- ⚠️ **本机 shell 会重复执行同一条命令** ⇒ `git commit` 回显 `nothing to commit, working tree clean`
  是**假警报**（其实第一次已提交成功）；`git tag -a X` 报 `already exists` 是**已建好**。
  应对：commit / tag **拆成单独命令**，用 `git log` / `git rev-parse HEAD` /
  `git ls-remote --tags` 复核；push 重复无害。
- 汇报前用 `git rev-parse HEAD origin/main` 确认两个 sha 相同（工作树与远程对齐）。
- CI 轮询须**前台 + 长超时**（脚本 `ci_status.py`，单次上限 1200s，超时按 `run_id` 续轮）。

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

`v0.1.0` → `v0.4.6` **全部齐全**（`v0.4.3` / `v0.4.4` / `v0.4.5` / `v0.4.6` 均已发布）。

- `v0.4.0` 调色批次 1~4（81 项）；`v0.4.1` 批次 5 对象作用域；
  `v0.4.4` UI Redesign v2.0；`v0.4.5` 细部位美容 + 44 套分类预设 + 追色 mono 去色 + 曲线/直方图；
  `v0.4.6` ML 缓存键改为会话代次 + 渲染提交判据（`completed`）+ `GlassChipRow`/`PresetThumbRow` 换行 + UI 尺寸回调。
- ⚠️ **任何用作判据的记忆值，用前先 `git grep` 复核一次**。
  （曾两次因为「记着的版本号 / 尺寸」而误判，两次都是记忆过期，不是代码有问题。）

### 5.6 待真机验收清单（至今未闭环）

A2 涂抹落点 / A1 导出内存日志 / 自动蒙版（P1p-1c）/ 人脸锚点（P1p-2c）/ A7C2 直连（P2）/
`TONE_ZONE_GAIN=0.35` 是否过猛 / 批次 3 预览↔导出一致性 / 批次 4 锐化必须晚于磨皮 /
批次 5 的 6 条（`OBJECT_TONE_DESIGN.md` §12.2）；**v0.4.6 核心**：同一张照片反复开关编辑器
时人脸 / 分割**不应**沿用上一张结果。**P3（NAS Docker 化 Rust 引擎）未启动**。

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

### 7.6 本次未做、留作后续

| 项 | 位置 | 为什么现在不做 |
|---|---|---|
| `MainActivity`（1200 行）拆 `RenderCoordinator` / `ExportController` / `EditDocument` | `MainActivity.kt` | 是本项目**最大**的改动面，需独立一轮；§7.2 第 4 条已先把最危险的三条判据钉死 |
| `chromeAlpha` 在 `EditorScreen` 主体读取 ⇒ 淡出期间每帧重组整个编辑页 | `EditorScreen.kt` | 需重构成 `@Stable RenderHolder`，与上一条同一轮做 |
| `curves/` 三个文件是**死代码**，且内含 3 个 P0（`pointerInput(Unit)` 捕获陈旧值、命中测试把 dp 当 px、拖动灵敏度 1:1） | `ui/editor/curves/` | 要么接回曲线分类（同批修那 3 条），要么整包删除。**当前默认「不接」** —— 曲线功能已由 `ParamPanel` 的 `CurveTab`（亮度/R/G/B）覆盖 |
| 部位级美容算法（面部/身体/腿部磨皮、唇部、祛黑眼圈、瘦头/额头、腿长、手部） | `core/edit/retouch/` | 需要新算子（分区蒙版强度 / 局部形变），不是接线能解决的 |
| `PtpTransport` 抽 `BulkIo` 接口以便 JVM 打桩 | `camera/PtpTransport.kt` | §7.4 已把最危险的两条堵住；打桩化收益很大，但会改动传输层结构 |
| ML 推理移出 `renderMutex`；分带缓冲复用到 `DetailPass` 之外的全部路径 | 多处 | 属于性能批次，需配合真机耗时日志验证 |

---
