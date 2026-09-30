# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-30（本地）
> 关联提交：`3ffc58ddb930868782db6ee91babecea03670429`（**versionName `0.4.2` / versionCode `7` —— 本轮已发版**）
> 本轮主题：**真机反馈第四轮 + 第五轮修复，发布 `v0.4.2`**

## 结论：✅ 一次全绿发版 —— main 跑绿后打 tag，5 job 全过（含 Publish），Release 已发布

| 阶段 | 运行 | ref / 提交 | 结论 |
|---|---|---|---|
| ① main 推修复 + 抬版本号 | run **`36663128099`** | `3ffc58d`（main） | ✅ **4 job 全绿**（`Publish` 非 tag 跳过） |
| ② tag 触发发布 | run **`36663981140`** | `v0.4.2`（`3ffc58d`） | ✅ **5 job 全绿**（含 `Publish GitHub Release`） |

> 🚀 **Release 已发布**：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.4.2>
> 附件 `pixelcake-v0.4.2-release.apk`（**29.01 MB**），已供真机（一加15）下载验收。

## 任务（Job）总览 — run `36663981140`（tag run，最终绿）

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest`（**33 测试文件 / 244 个 `@Test` 全过**） |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Check signing secrets | ✅ success | 探测到 `KEYSTORE_BASE64` |
| Signed Release | ✅ success | 解 PKCS12 keystore → `assembleRelease`（R8）→ 签名 APK + mapping |
| Publish GitHub Release | ✅ success | `v*` tag 触发，上传 release APK 并创建 Release（`contents: write`） |

> 与 main run `36663128099` 的唯一差别：后者为非 tag push，`Publish` 按设计 **⏭️ skip**。
> 两步走的**闸门设计生效**：只有「main 已绿」的 commit 才被打了 tag。

## 本轮产出物（Artifacts）— run `36663981140` / `36663128099`

| Artifact | 大小 | 保留 |
|---|---|---|
| `pixelcake-release-3ffc58d…` | 20.79 MB | 90 天 |
| `pixelcake-debug-3ffc58d…` | 31.12 MB | 90 天 |
| `pixelcake-mapping-3ffc58d…` | 2.18 MB | 90 天 |
| `lint-report-3ffc58d…` | 0.03 MB | 7 天 |

**Release 附件**：`pixelcake-v0.4.2-release.apk`（29.01 MB）。

## 本轮提交

| 提交 | 说明 |
|---|---|
| `0d02888` | `fix(ui)`：真机反馈第四轮 + 第五轮修复（6 文件，`+267 / −67`） |
| `3ffc58d` | `chore(release)`：抬版本 `0.4.1 → 0.4.2`（`versionCode 6 → 7`） |

### 真机反馈第四轮（用户报 3 条 → 实为 2 个根因）

| 报障 | 根因 | 修法 |
|---|---|---|
| ① 人像页「下面的菜单和进度条全部挤在一行、相互覆盖」<br>③「最下面的功能描述也和菜单/进度条挤在一起」 | **同一个根因**：`EditorScreen` 用 `Crossfade` 淡换分类内容，而 `Transition.Crossfade` 的内容容器是 **`Box`**（已核 `compose-animation:1.9.5` 源码，**没有**「不带动画就直接输出」的提前返回分支）；`ParamPanel` 输出的却是一串**纵向兄弟节点** ⇒ 全被叠在面板左上角 | `ParamPanel` body **自带一层 `Column(Modifier.fillMaxWidth())`**（容器该由输出方负责，`ParamScopeBar` 一直这么写）。原先「对象作用域 + 整幅阶段 ⇒ 提前 `return`」改写成 `else` 分支 —— **非内联 lambda 里不允许非局部返回**，直接留 `return` 编译不过 |
| ② 拖动过程中预览窗口出现**一根横线**、上下颜色不一致且闪动 | 渲染把新一帧写进**正在显示**的那张位图：`MainActivity` 里 `existing = rendered`（屏幕上那张），写发生在 `Dispatchers.Default`；`EditEngine` 是**分带**写（`setPixels` 每带推进一次 generation id）⇒ 合成器据此重传纹理 ⇒ 屏幕上是「写了一半」的图，**当前带边界就是那根横线**；每批从顶部重扫 ⇒ 持续闪动 | 预览改**双缓冲**：新增 `renderSpare`（下一批的写入目标），`rendered` 对渲染协程**只读**；`RenderBatch.reused` 区分「借来的块」（丢弃批次时只回收**新建的**）；提交时交换 `previous=rendered; rendered=target; renderSpare=previous`；`onBack` 把三块一起退役。稳态**零额外分配**（两块互相轮换） |

**历史台账**：`git log -S "Crossfade("` ⇒ 引入于 `ca5410b`（UI-1b）。⇒ `v0.3.0` 第三轮真机反馈那句
「调色栏下面的参数菜单全部挤在一起」**就是同一个 bug**，当时的诊断（`docs/UI_DESIGN.md` §4.0.3：
面板太矮 + 层级丢失）只覆盖了一半。本轮已在 §4.0.3 末尾补 2026-09-25 更正，并新增 **§4.0.4**。
另外批次 1~4 把渲染明显变慢（81 项参数 + 邻域算子），撕裂窗口被放大 —— 很可能是它到这一轮才被报上来的原因。

### 真机反馈第五轮（用户报 3 个现象 → 实为 1 个根因）

| 报障 | 单一根因 | 修法 |
|---|---|---|
| 点击底部「调色台 / 设置」时：**变灰底** + **两端直角** + **两按钮中间一条分割线** | 分段项只写了 `selectable(selected = …, onClick = …)`，**静默**落到「去 `LocalIndication` 取 M3 涟漪」的那个重载（`foundation:1.9.5` `Selectable.kt:140` —— 该重载**根本没有 `indication` 参数**，实现体 `useLocalIndication = true`）。涟漪的**颜色** ⇒ 整格变灰；涟漪边界是**矩形** ⇒ 与 `Radius.pill` 冲突、两端读成直角；相邻两格矩形**共用一条边** ⇒ 读成中缝 | 改成 4 命名参形式并显式传 `null`：`.selectable(selected=…, interactionSource=null, indication=null, onClick=…)` —— 只有 `Selectable.kt:230` 那个重载有 `indication` ⇒ 一旦写出即**唯一确定**，不会再静默落回。**安全性已证**：`Clickable.kt:697` 有 `indication == null` 快路径（不挂 `Modifier.indication`）；`initializeIndicationAndInteractionSourceIfNeeded()` 里 `indicationFactory?.let{…}` 恒不进入 ⇒ 那句 `!!` **永不可达** |

**顺手修掉的两件事**：① `GlassSegmentedBar`（**编辑器一级工具条**：人像/调色/曲线/细节/效果/预设）有同一处漏法，
一并修掉（调用点 `EditorToolbar.kt:104`）；② `GlassSegmentedBar` 的 KDoc 原写「底部 TabBar 与编辑器一级条
**共用同一个控件**」—— **这句是错的**（`AppShell.kt:117` 有一份自己的 `private fun GlassTabBar`），
正是这句假话让「改一处查两处」这个动作少做了一次，已改成事实 + 「改一处必须同步查另一处」。
`docs/OBJECT_TONE_DESIGN.md` 新增 **§13.6**（逐条定性：两条与本批无关，一条是本批新文案暴露的老问题；
§12.2 的 6 条真机验收项**一条都没被消掉**）。

## ⭐ 复盘（供后人少走弯路）

### 布局类：**先怀疑容器，再怀疑尺寸**
`Crossfade` / `AnimatedContent` 的内容容器都是 `Box` ⇒ **内容必须是单一节点**，多节点会被叠在同一位置。
现象会伪装成「参数太挤」，把排查引向高度 / 间距 / 字号 —— `docs/UI_DESIGN.md` §4.0.3 就是这么偏掉的，
而且一带就是 4 个版本。输出一串兄弟节点就必须自己包 `Column`。

### 渲染类：**正在被显示的位图是只读的**
分带 `setPixels` 每带推进 generation id ⇒ 合成器重传纹理 ⇒ 撕裂横线。逐帧重渲**至少两块缓冲**
（写一块、显一块）。另外：位图回收三原则里「**读者不唯一**的位图不手动回收」正与此同源。

### 交互类：**「代码里写不出这个参数」≠「没有这个行为」**
`selectable` 有一个**根本没有 `indication` 参数**的重载，代码里看不到 `indication` 恰恰说明它落到了那一个。
判断默认行为必须去读**实际解析版本**的源码，不能凭「我没写」反推。传 `null` 之前要先把它下游 `!!` 的
**可达性**证掉（否则真机上可能是崩溃而不是丑，代价完全不对等）。

### 排障通法
查这类「几个版本前就该发现」的问题时，`git log -S "<可疑 API>("` 比通读代码快得多 ——
它一次就把「引入点」和「用户第一次报障的时间」对上了。

## 🔐 安全边界（重要）

- 入库的 `app/debug.keystore` 是 Android 标准调试密钥，口令（`android`/`androiddebugkey`）为**公开约定、非机密**；`.gitignore` 用 `!debug.keystore` 有意反忽略，只为让 debug 签名跨构建稳定。
- ⚠️ **release keystore 绝不能入库**：仅存于 GitHub Secrets，CI 解到 `$RUNNER_TEMP` 临时落盘、job 结束即删。
- 若将来**误提交**真实密钥/口令，仅删文件**不够** —— 必须用 `git filter-repo`（或 BFG）清理**历史**并立刻轮换。
- ⚠️ **已推送的 tag 不要删除/移动**（`git push --force --tags` 属历史改写）。发错版本的正确做法是**发下一个版本**并在 notes 里说明。

## 历史回归记录

| Run | 提交 / ref | 结论 | 失败点 |
|---|---|---|---|
| `34866770056` | `f28ccc5`（main） | ❌ failure | `EditorScreen.kt:149 Unresolved reference 'EditorStatus'`（与并行会话抢跑，定义文件未提交） |
| `34868197276` | `d29179e`（main） | ✅ success | 无（补齐定义后全绿） |
| `35312379991` | `71fc0ba`（main） | ❌ failure | `Set up Android SDK`：`Failed to find package 'tools'`（action 默认 packages 含已下架包） |
| `35312573435` | `1bc9f9e`（main） | ❌ failure | `ParamSlider.kt:126` 实验性 Material3 API 未 opt-in |
| `35313027938` | `b3d609d`（main） | ✅ success | 无 |
| `35313769515` | `d5eb37d`（main） | ✅ success | 无（docs-only） |
| `35615284665` | `746f196`（main） | ✅ success | 无（v0.2.0 版本号推送） |
| `35616709639` | `v0.2.0`（tag） | ✅ success | 无（5 job 全绿，Release 已发布） |
| `35617373327` | `7877fda`（main） | ✅ success | 无（docs-only） |
| `35691497560` | `4ecf169`（main） | ✅ success | 无（v0.3.0 十文件大改） |
| `35692247567` | `v0.3.0`（tag） | ✅ success | 无（5 job 全绿，Release 已发布） |
| `35692631309` | `382c2aa`（main） | ✅ success | 无（docs-only） |
| `35694905892` | `4a1a8bc`（main） | ✅ success | 无 |
| `35695500853` | `v0.3.1`（tag） | ✅ success | 无（5 job 全绿，Release 已发布） |
| `35891490851` | `fce0163`（main） | ❌ failure | `DetailPassTest` 2 条逐位比对失败（分带 halo 少了 `rFine`） |
| `35949465017` | `04e211d`（main） | ✅ success | 无（197 测试全过，4 job 绿） |
| `35950523249` | `77cd337`（main） | ✅ success | 无（v0.4.0 版本号推送，4 job 绿） |
| `35951354601` | `v0.4.0`（tag） | ✅ success | 无（5 job 全绿含 Publish，Release 已发布） |
| `35951969525` | `3c18150`（main） | ✅ success | 无（docs-only） |
| `35993853528` | `6915249`（main） | ❌ failure | `MlMaskProvider.kt:63/86` `mapOf` 含可空 `accelerator` ⇒ value 类型可空，与 `Map<String, Any>?` 不匹配 |
| `35994832403` | `4fd16f1`（main） | ✅ success | 无（33 文件 / 244 测试全过，4 job 绿） |
| `35996731862` | `83f4435`（main） | ✅ success | 无（docs-only） |
| `35998171813` | `917dd65`（main） | ✅ success | 无（v0.4.1 版本号推送，4 job 绿） |
| `36003780194` | `v0.4.1`（tag） | ✅ success | 无（5 job 全绿含 Publish，Release 已发布） |
| `36004335738` | `f735a7d`（main） | ✅ success | 无（docs-only） |
| `36663128099` | `3ffc58d`（main） | ✅ success | 无（第四/五轮修复 + v0.4.2 版本号，4 job 绿） |
| **`36663981140`** | **`v0.4.2`（tag）** | ✅ **success** | **无（5 job 全绿含 Publish，Release 已发布）** |

## 后续步骤

1. **真机（一加15）验收 `v0.4.2`**，本批三条 / 两类：
   - **bug 1/3**：参数面板内容是否恢复**纵向排布**（**人像页最先看**；再抽查调色 / 颜色 / 曲线 / 细节 / 效果）；
   - **bug 2**：拖动时那根**横线**是否消失（重点看清晰度 / 锐化这类**邻域算子**，它们的带边界最显眼）；
   - **第五轮**：底部「调色台 / 设置」按下**不应**再有灰底 / 直角 / 中缝；编辑器一级工具条同样；
     选中态仍应是强调色胶囊指示块滑动（**选中态没被动过**）。
2. 仍待真机确认的历史项：批次 5 的 6 条（`docs/OBJECT_TONE_DESIGN.md` §12.2）/ `TONE_ZONE_GAIN=0.35` 是否过猛 /
   批次 1~4 的预览↔导出一致性 / 锐化晚于磨皮 / A2 涂抹落点 / A1 导出内存日志 / 自动蒙版 / A7C2 直连 / 人脸锚点。
3. 下一版若要发：先抬 `versionName`/`versionCode`（当前 `0.4.2` / `7`）→ push main 跑绿 → 再打 tag，两步走。
4. 新修改按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
5. ⚠️ **已知待办**：第一版真实设置迁移落地时**必须同批补 `SettingsMigrationTest`**（当前 v0→v1 为空迁移）。
6. ⚠️ **环境类失败预警**：`Failed to find package 'tools'` = 上游 SDK 变动，改 workflow `packages`，别改代码。
7. ⚠️ **Material3 实验性 API**：用 `Slider` 自定义 `thumb` 等须加 `@OptIn(ExperimentalMaterial3Api::class)`。
8. ⚠️ **日志埋点传可空值**：`DebugLog` 的 kv 是 `Map<String, Any>`（值**非空**）。往 `mapOf` 里塞
   `String?` 这类可空值会让整张 map 被推断成可空交集类型而编译失败 —— 一律 `?: 兜底`，并建议显式写
   `mapOf<String, Any>(...)`。
9. ⚠️ **`Crossfade` / `AnimatedContent` 的内容必须是单一节点**；**`selectable` / `clickable` / `toggleable`
   必须显式传 `indication = null`**（否则静默取 `LocalIndication`，出现方形灰底涟漪）。两条都已写进
   `docs/UI_DESIGN.md`，改一处**必须同步查** `GlassSegmentedBar` ↔ `AppShell.GlassTabBar`。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 main 一次跑绿（4 job）→ 打 `v0.4.2` tag → 5 job 全绿含 Publish → Release 已发布。*
