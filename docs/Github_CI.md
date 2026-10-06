# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-10-06（本地）
> 关联提交：`a8a93ce`（**versionName `0.4.6` / versionCode `11` —— 本轮已发版**）
> 本轮主题：**合入 `agents/project-analysis-and-improvement` worktree 分支的代码复审修正，发布 `v0.4.6`**

## 结论：✅ 两轮 main 后全绿发版 —— 首轮红（`imageEpoch` 局部变量前向引用）→ 修复后 main 绿 → 打 tag，5 job 全过（含 Publish），Release 已发布

| 阶段 | 运行 | ref / 提交 | 结论 |
|---|---|---|---|
| ① main 推「合并 + 抬版本号」 | run **`37440627734`** | `7a8ea25`（main） | ❌ **failure**（`compileDebugKotlin` 1 类：`MainActivity.kt:158` `Unresolved reference 'imageEpoch'`） |
| ② main 推修复 | run **`37441305549`** | `97bf4a9`（main） | ✅ **4 job 全绿**（`Publish` 非 tag 跳过） |
| ③ tag 触发发布 | run **`37443544216`** | `v0.4.6`（`97bf4a9`） | ✅ **5 job 全绿**（含 `Publish GitHub Release`） |
| ④ main 推本报告 | run **`37445115490`** | `a8a93ce`（main） | ✅ **4 job 全绿**（`Publish` 非 tag 跳过） |

> 🚀 **Release 已发布**：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.4.6>
> 附件 `pixelcake-v0.4.6-release.apk`（**29.06 MB**），已供真机（一加15）下载验收。

## 任务（Job）总览 — run `37443544216`（tag run，最终绿）

| Job | 结论 | 说明 |
|---|---|---|
| Check signing secrets | ✅ success | 探测到 `KEYSTORE_BASE64` |
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest` 全过 |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Signed Release | ✅ success | 解 PKCS12 keystore → `assembleRelease`（R8）→ 签名 APK + mapping |
| Publish GitHub Release | ✅ success | `v*` tag 触发，上传 release APK 并创建 Release（`contents: write`） |

> 与 main run `37441305549` 的唯一差别：后者为非 tag push，`Publish` 按设计 **⏭️ skip**。
> 两步走的**闸门设计生效**：只有「main 已绿」的 commit 才被打了 tag。

## 🔀 本轮的「合并」性质：worktree 分支 → main

本轮不是常规的功能开发，而是把**第二个 worktree** 的工作合回 `main`：

```
D:/AI_Project                                            [main]
D:/AI_Project.worktrees/project-analysis-and-improvement  [agents/project-analysis-and-improvement]
```

合入时两个分支指针相同（都是 `b0176d0`），worktree 侧有 **13 个文件的未提交改动**。
处理顺序：**先在 worktree 里提交 → 再 `git merge --no-ff` 合入 main**。

| 提交 | 说明 |
|---|---|
| `0dd6784` | `fix(review)`：代码复审修正（13 文件，`+147 / −94`）—— **提交于 worktree 分支** |
| `758ee95` | `merge`：合入 `agents/project-analysis-and-improvement`（`--no-ff`，保留分支历史） |
| `7a8ea25` | `chore(release)`：抬版本 `0.4.5 → 0.4.6`（`versionCode 10 → 11`） |
| `97bf4a9` | `fix(edit)`：修首轮编译错（`imageEpoch` 前向引用，1 文件，`+21 / −18`） |

## ❌ 本轮真实故障：首轮 `37440627734` main 红

**报错（Build 与 Lint 两个 job 同一处，预编译步即红）**：

```
e: .../MainActivity.kt:158:75  Unresolved reference 'imageEpoch'.
e: .../MainActivity.kt:158:75  Argument type mismatch: actual type is 'MatchGroup?', but 'Int' was expected.
> Task :app:compileDebugKotlin FAILED
```

**根因：局部变量按声明顺序可见 —— 前向引用。**

批次复审给「对象作用域预热」的 `LaunchedEffect(imported)`（原 155–164 行）新加了 `imageEpoch.get()`
实参，而 `val imageEpoch = remember { AtomicInteger(0) }` 的声明**在其后的原 200 行**。

⚠️ 注意那条**迷惑性的连带错误**：编译器找不到 `imageEpoch` 这个局部符号后，把该标识符解析成了
其它同名候选，于是额外报出 `actual type is 'MatchGroup?'`。**看到「Unresolved reference + 类型不匹配」
同时出现在同一列，第一反应应是「符号没找到」，而不是去查那个类型。**

**修法（`97bf4a9`）**：把「注释块 + 声明」整体**上移**到 `LaunchedEffect` 之前
（`objectScopesAvailable` 声明之后），并在 KDoc 补一条警示：声明必须靠前。
修后 `imageEpoch` 只声明一次，12 处引用全部在其后。

**顺带核对**（同一批改动把 `mlCacheKey` 由 2 参改为 4 参）：
- 声明 4 参，3 个调用点全部传 4 参 ✓
- 尺寸取 `src.bitmap.width/height`（`Int`）、代次取 `imageEpoch.get()` / `epochAtStart`（`Int`）✓

## 📦 本轮改动内容（复审驱动）

### 1. ML 缓存键正确性（核心修复）

`mlCacheKey` 原为 `uri + 预览位图尺寸`，在「同 URI 重开」「同尺寸连拍」场景下会**错误命中缓存**，
把上一张的人脸/分割结果当成当前图的。改为 **`uri + 尺寸 + epoch`（会话代次）**。

- 尺寸取 `src.bitmap`（导入代理尺寸），**不取 `src.linear`** —— 后者在 RAW 路径下是**线性代理尺寸**，
  与导入探测 / 导出路径传的键不同，会把同一张图拆成两个键导致缓存复用失效（每次导出重跑推理）。
- `epoch` 取 `imageEpoch`：语义为「换图 / 退出编辑器时 +1」，**一次编辑会话内恒定**
  ⇒ 同一张图的预览 / 导出 / 对象作用域探测**共用同一蒙版**（只推理一次），换图必然失效。
- **不采用** `identityHashCode`（位图回收后新实例可能落在同地址 ⇒ 碰撞）与 `generationId`
  （语义是「像素内容被改动」，而渲染**每 32 行**就 `setPixels` 一次，键会每帧变化）。

### 2. 缓存失效的线程与时机

- `MlFaceProvider.invalidate()` / `reset()`、`MlMaskProvider.reset()` 补 **`@Synchronized`**
  （与 `objectMasksFor` / `facesFor` 的并发访问串行化）。
- 换图时 `invalidate()` 挪进 `withContext(Dispatchers.Default)`（与推理同线程）；
  退出编辑器时挪进 `scope.launch(Dispatchers.Default)`，不再阻塞主线程。
- `decodeToProxy` 失败路径提前置 `loading = false`，避免失败后 loading 悬挂。

### 3. UI 尺寸回调

| 文件 | 变更 |
|---|---|
| `GlassChipRow.kt` / `PresetThumbRow.kt` | 横向滚动 → **`FlowRow` 自适应换行**，选项不再被截断或藏在水平滚动区；补 `@OptIn(ExperimentalLayoutApi::class)` |
| `ParamSlider.kt` | 拇指 24dp → **20dp**（保留 Material Slider 48dp 触控热区）；移除整行上下额外留白 |
| `GlassSegmentedBar.kt` | 移除选中项内联 `Modifier.padding` |
| `CameraPanel.kt` | 进度条收窄至 72% 宽 / 3dp 高并居中 |
| `EditorScreen.kt` / `EditorToolbar.kt` | 顶栏与一级工具条统一 48dp（`Spacing.controlHeight`），注释同步 |

### 4. 文档同步

- `README.md`：特性表由「规划中」更新为「已实现」，模块树与技术栈对齐当前实现。
- `docs/UI_DESIGN.md` / `docs/UI_REDESIGN.md`：控件高度（44→48dp）、chip 行改为换行组、滑块拇指尺寸等与实现对齐。

## ⭐ 复盘（供后人少走弯路）

1. **局部变量前向引用 = `Unresolved reference`，与顶层 `val` 前向引用（`must be initialized`）是两回事。**
   局部变量按**声明顺序**可见，写在后面就是**找不到符号**（不是「能用但危险」）。
   本轮踩坑原因：给一个**靠前的** `LaunchedEffect` 新增实参，而变量声明在靠后位置。
2. **「Unresolved reference + 类型不匹配」出现在同一行同一列 ⇒ 先怀疑符号没找到。**
   `MatchGroup?` 那条纯属编译器解析失败后的连带噪音，追它只会浪费时间。
3. **静态闸门要再加一条：跨行检查「变量使用点是否早于其声明」**。
   本轮已建立的三类闸门（具名参数合法性 / `Pair`·`Triple` 实参个数 / 惯用类缺 import）
   **扫不出**这类错误 —— 它的符号存在、名字也对，只是**顺序错了**。
   注意粗粒度扫描会因**不同函数里同名变量**而产生大量假阳性，必须以「声明位置之后的首次使用」为判据。
4. **一处编译错 = Build 与 Lint 两个 job 同时红**，且都在**预编译步**就失败。
   判据：**真错** = 有 `e: file:///...kt:行:列`；**infra 抖动** = Build 绿而 Lint 挂、日志只有 `hs_err_pid*.log`。
5. **多 worktree 协作的正确顺序：先在各自 worktree 内提交，再合入 main。**
   本轮合入时两分支指针相同、worktree 侧仅有未提交改动 ⇒ 真正的动作是「提交 + 合并」，
   而不是「合并两个已分叉的分支」。

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
| `36663981140` | `v0.4.2`（tag） | ✅ success | 无（5 job 全绿含 Publish，Release 已发布） |
| `36664396924` | `2bd0a94`（main） | ✅ success | 无（docs-only） |
| `36810389089` | `ebb8949`（main） | ✅ success | 无（第六/七轮修复 + v0.4.3 版本号，4 job 绿） |
| `36814801731` | `v0.4.3`（tag） | ✅ success | 无（5 job 全绿含 Publish，Release 已发布） |
| `36815683102` | `3c3ee98`（main） | ✅ success | 无（docs-only） |
| `37192930775` | `2633e34`（main） | ❌ failure | `GlassChipRow.kt:76/81`：`FilterChip` 无 `contentPadding` 参数 + 缺 `foundation.layout.size` 导入 |
| `37193138300` | `f0bcaea`（main） | ✅ success | 无（修复后 4 job 绿） |
| `37193562293` | `v0.4.4`（tag） | ✅ success | 无（5 job 全绿含 Publish，Release 已发布） |
| `37203167123` | `60af310`（main） | ❌ failure | `Presets.kt:109/303/318` 非法具名参数 `hue`/`blues`；`ParamPanel.kt:54` 顶层属性前向引用；`ParamPanel.kt:779/785` 局部函数缺 `@Composable` |
| `37203592131` | `54c2898`（main） | ❌ failure | `ParamPanel.kt:75` `List<Serializable>` 类型推断；`CurveCanvas.kt:106/111` `Path.cubicBezier` 不存在 |
| `37203885993` | `3e2d1b6`（main） | ❌ failure | `CurveCanvas.kt:123-127` `Triple` 只给 2 参 ⇒ `No value passed for parameter 'third'` |
| `37204072934` | `b582da0`（main） | ✅ success | 无（修复后 4 job 绿） |
| `37204595671` | `v0.4.5`（tag） | ✅ success | 无（5 job 全绿含 Publish，Release 已发布） |
| `37204925691` | `b0176d0`（main） | ✅ success | 无（docs-only） |
| `37440627734` | `7a8ea25`（main） | ❌ failure | `MainActivity.kt:158` `Unresolved reference 'imageEpoch'`（局部变量前向引用）+ 连带 `MatchGroup?` 类型不匹配 |
| `37441305549` | `97bf4a9`（main） | ✅ success | 无（修复后 4 job 绿） |
| **`37443544216`** | **`v0.4.6`（tag）** | ✅ **success** | **无（5 job 全绿含 Publish，Release 已发布）** |
| `37445115490` | `a8a93ce`（main） | ✅ success | 无（docs-only，4 job 绿） |

## 后续步骤

1. **真机（一加15）验收 `v0.4.6`**，本批重点看：
   - **ML 缓存键**：同一张图反复进出编辑器 / 连续导出，确认**不会用错图的蒙版**；
     以及「同尺寸连拍的两张不同照片」切换时结果正确（这是本轮修的核心问题）。
   - **chip 行换行**：预设 / 追色 / 工具选择等长列表是否**完整可见**（不再需要横向滑动找），
     面板高度随内容增长是否可接受。
   - **滑块拇指 20dp**：触控是否仍顺手（Material 48dp 热区保留）。
   - **顶栏 / 工具条 48dp**：上下是否对齐、视觉是否协调。
   - **相机面板进度条**：72% 宽 / 3dp 高是否清晰可读。
   - **两种主题（浅 / 深）各看一次** —— 历史教训：深浅相反才现形的 bug（`LocalContentColor`）必须两主题都验。
2. 仍待真机确认的历史项：第六轮面板文字（浅色主题）、第七轮拖参数「只有完整帧 + 每帧都在动」
   + `render done` 的 `ms` 报数、`TONE_ZONE_GAIN=0.35` 是否过猛、批次 1~4 预览↔导出一致性、
   锐化晚于磨皮、A7C2 直连、人脸锚点。
3. ⚠️ **`docs/CURVE_HISTOGRAM_DESIGN.md` 仍不存在** —— `Histogram.kt` 的 KDoc 引用了它。
   下次接线 `ui/editor/curves/` 包时一并补上（或修正 KDoc 引用）。
4. 下一版若要发：先抬 `versionName`/`versionCode`（当前 `0.4.6` / `11`）→ push main 跑绿 → 再打 tag，两步走。
5. 新修改按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
6. ⚠️ **多 worktree**：`agents/project-analysis-and-improvement` 位于
   `D:/AI_Project.worktrees/project-analysis-and-improvement`。合入 main 前先在该 worktree 内提交。
7. ⚠️ **已知待办**：第一版真实设置迁移落地时**必须同批补 `SettingsMigrationTest`**（当前 v0→v1 为空迁移）。
8. ⚠️ **环境类失败预警**：`Failed to find package 'tools'` = 上游 SDK 变动，改 workflow `packages`，别改代码。
9. ⚠️ **Material3 实验性 API**：用 `Slider` 自定义 `thumb` 等须加 `@OptIn(ExperimentalMaterial3Api::class)`；
   `FlowRow` 须加 `@OptIn(ExperimentalLayoutApi::class)`（`androidx.compose.foundation.layout`）。
10. ⚠️ **日志埋点传可空值**：`DebugLog` 的 kv 是 `Map<String, Any>`（值**非空**）—— 一律 `?: 兜底`。
11. ⚠️ **`Crossfade` / `AnimatedContent` 的内容必须是单一节点**；**`selectable` / `clickable` / `toggleable`
    必须显式传 `indication = null`**；**自定义主题换 `colorScheme` 时必须同时补 `LocalContentColor`**。
12. ⚠️ **执行 git commit 时不要在 `-m` 里用反引号**：bash 会把它当命令替换执行。改用 `git commit -F <文件>`。
13. ℹ️ **网络环境**：本机 hosts 把 `github.com` 指向 `127.0.0.1`（黑洞），直连 22 端口失败；
    push 需走 `ssh.github.com:443`（`GIT_SSH_COMMAND="ssh -p 443 -o HostName=ssh.github.com"`）。
    **不改仓库/全局配置**，只在单条 push 命令上临时覆盖。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 main 首轮红（`imageEpoch` 局部变量前向引用，连带一条迷惑性的 `MatchGroup?` 类型错误）→ 修复 → main 一次跑绿（4 job）→ 打 `v0.4.6` tag → 5 job 全绿含 Publish → Release 已发布。*
