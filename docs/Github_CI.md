# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-10-01（本地）
> 关联提交：`ebb894907493090f550f2d61ea9c46b0d79c05f6`（**versionName `0.4.3` / versionCode `8` —— 本轮已发版**）
> 本轮主题：**真机反馈第六轮 + 第七轮修复，发布 `v0.4.3`**

## 结论：✅ 一次全绿发版 —— main 跑绿后打 tag，5 job 全过（含 Publish），Release 已发布

| 阶段 | 运行 | ref / 提交 | 结论 |
|---|---|---|---|
| ① main 推修复 + 抬版本号 | run **`36810389089`** | `ebb8949`（main） | ✅ **4 job 全绿**（`Publish` 非 tag 跳过） |
| ② tag 触发发布 | run **`36814801731`** | `v0.4.3`（`ebb8949`） | ✅ **5 job 全绿**（含 `Publish GitHub Release`） |

> 🚀 **Release 已发布**：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.4.3>
> 附件 `pixelcake-v0.4.3-release.apk`（**29.01 MB**），已供真机（一加15）下载验收。

## 任务（Job）总览 — run `36814801731`（tag run，最终绿）

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest`（**33 测试文件 / 244 个 `@Test` 全过**） |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Check signing secrets | ✅ success | 探测到 `KEYSTORE_BASE64` |
| Signed Release | ✅ success | 解 PKCS12 keystore → `assembleRelease`（R8）→ 签名 APK + mapping |
| Publish GitHub Release | ✅ success | `v*` tag 触发，上传 release APK 并创建 Release（`contents: write`） |

> 与 main run `36810389089` 的唯一差别：后者为非 tag push，`Publish` 按设计 **⏭️ skip**。
> 两步走的**闸门设计生效**：只有「main 已绿」的 commit 才被打了 tag。

## 本轮产出物（Artifacts）— run `36814801731` / `36810389089`

| Artifact | 大小 | 保留 |
|---|---|---|
| `pixelcake-release-ebb8949…` | 20.79 MB | 90 天 |
| `pixelcake-debug-ebb8949…` | 31.12 MB | 90 天 |
| `pixelcake-mapping-ebb8949…` | 2.18 MB | 90 天 |
| `lint-report-ebb8949…` | 0.03 MB | 7 天 |

**Release 附件**：`pixelcake-v0.4.3-release.apk`（29.01 MB）。

## 本轮提交

| 提交 | 说明 |
|---|---|
| `8c08166` | `fix(ui/preview)`：真机反馈第六轮 + 第七轮修复（4 文件，`+201 / −25`） |
| `ebb8949` | `chore(release)`：抬版本 `0.4.2 → 0.4.3`（`versionCode 7 → 8`） |

### 真机反馈第六轮（用户报：「工具栏菜单/进度条没有对应文字，且像素过大、占比不合适」）

**根因不是缺 label，而是颜色。** 证据链（全部取自本工程实际解析版本 `material3:1.4.0`，由 BOM `2025.11.01` 解析）：

1. `LocalContentColor` 的**默认值就是 `Color.Black`**；
2. **只有 `Surface` 会 provide 它**（`Surface.kt`：`contentColor = contentColorFor(color)` + `CompositionLocalProvider(...)`）；
3. **`MaterialTheme` 不下发它** —— 实现体只有 colorScheme / shapes / typography / motionScheme；
4. 全 App 唯一的 `Surface(` 在 `MainActivity.kt`，位于 `PixelCakeWorkspaceTheme` **之外**、用的是**外层**主题的 `surface`；
5. 编辑页参数面板链路全程**没有任何 `Surface`**：`Box(weight)` → `GlassCard`（是 Modifier，不是 `Surface`）→ `Column` → 滚动 `Column` → `Crossfade` → `ParamPanel`。

⇒ 卡片底 `ContainerDark #1B1B22` + 文字 `Ink #1B1B1F` ≈ **1:1 对比度 = 看不见**。
**只在 App 处于浅色主题（白天）时现形** —— 深色主题下继承到的恰好也是浅色值，所以一直没被发现。

**修法（2 文件、零 API 面）**：
- `ui/theme/Theme.kt`：`PixelCakeWorkspaceTheme` 补 `LocalContentColor provides WorkspaceColors.onSurface`
  —— 取这个值是因为「这正是假如这里有一层 `Surface` 会得到的值」⇒ **深色主题下零变化**，只在浅色下把「看不见」变回「看得见」；
- `ui/components/ParamSlider.kt`：label 显式 `color = MaterialTheme.colorScheme.onSurface`（与面板内其它 `Text` 同口径）。

**同一根因当年只修了一半（关键教训）**：`EditorScreen` 的注释**早就写着**「外层 `Surface` 位于
`PixelCakeWorkspaceTheme` 之外」—— 当年照它修了**底色**，**漏了文字颜色**。
⇒ 遇到这类事实，要顺着 `background` / `contentColor` / `LocalDarkTheme` / 系统栏图标**四条各查一遍**。

**「差点误判」也值得记**：一开始把「菜单项没文字」也算进同一 bug（`GlassChipRow` 的 `Text` 看着也没写 `color`），
读 M3 源码后推翻 —— `FilterChip` → `SelectableChip` → **`Surface(...)`**（自带 contentColor 解析）⇒ chip 文案一直是好的。
⇒ **「没写 `color` 的 `Text`」清单必须再按「在不在 `Surface` 内」分类，不能直接当结论。**
（客观密度数据：每个滑块行 ≈76dp，影调页一屏约 4.8 个滑块。**本轮刻意不改密度** —— 属独立决策。）

### 真机反馈第七轮（用户报：「预览窗口闪动、滑动参数卡、会出现按行按列扫描的条纹」）

**三个现象一个根因：半成品位图被当成成品提交上屏。**

- `EditEngine.renderIntoLinear` 的协作取消是「在下一个分带边界 **`return false`**」，而这个 `false` 的语义是
  「**这张位图只写到了第 k 带**」；`MainActivity` 的调用点写 `{ !renderJob.isActive }`，**返回值被直接丢掉**；
  出锁后唯一的丢弃判据是 `batch.epoch`（**图片**代次），而拖参数时 `imageEpoch` **一次都不会变**
  ⇒ 每帧被取消的半成品都通过检查、被换到前台。缝在第 k 带，k 每帧不同 ⇒ 就是那道会移动的横纹。
- **「卡」的真身不是渲染慢**：`.conflate().collectLatest` 对**每一次**快照都取消在跑的那帧，
  而一帧重渲要 100~300ms ⇒ **没有一个渲染被允许跑完**（算力全花在「启动→被砍」）。
- 洗清「并行分带」嫌疑只用了一处证据：`runBand` 用 `jobs.forEach { it.get() }` **同步等齐**所有分片后才 `setPixels`
  ⇒ 「带内按行分片」**不可能**产生列方向半成品。

**修法（只改 1 个代码文件）**：`RenderBatch` 增 `completed: Boolean` + `ms: Long`；出锁判据改为
`if (!batch.completed || batch.epoch != imageEpoch.get())`；`.conflate().collectLatest` → `.conflate().collect`
（参数变化不再取消在跑的那帧，能取消它的只剩「换图 / 退出编辑器」）；`import collectLatest` 换成 `collect`。
顺手给每帧 `render done` 日志加上 `ms`（完整帧单帧耗时）与 `layers`，供真机直接报数。
**`EditEngine.kt` 一行未改** —— `BAND_ROWS` / 并行粒度都不需要动，动它等于拿内存换一个没被证实的原因。

## ⭐ 复盘（供后人少走弯路）

1. **「控件没文字」先怀疑颜色，不要先怀疑布局**：`Ink` 压在 `ContainerDark` 上差 1 级明度，肉眼就是「这里什么都没有」，
   很容易被读成「控件缺 label」。
2. **自定义主题换 `colorScheme` 时，必须同时补 `LocalContentColor`** —— 否则子树里所有没写 `color` 的 `Text`
   都去继承**外层**主题的值。这是**主题层的雷**，不是调用点的锅。
3. **「协作取消」是一个必须被消费的返回值**：被取消时**正常 `return false`** 使得「忘接返回值」在语法 / 类型 / lint 上全都合法。
   分带 / 分块写共享缓冲的算子，必须把「写完了没有」显式带出来。
4. **「图片代次」与「这一批画完了没有」是两个正交的量**：单靠「有没有被取消」反推 completion 在拖动期间每帧都假。
5. **「拖动时才出现、手指一停就消失」的画面缺陷，先怀疑「半成品被提交」**，
   判据是「一条位置每帧变化的直线 / 接缝」，而不是去翻算法。
6. **深浅相反才现形的 bug，验收必须两种主题各看一次。**

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
| **`36814801731`** | **`v0.4.3`（tag）** | ✅ **success** | **无（5 job 全绿含 Publish，Release 已发布）** |

## 后续步骤

1. **真机（一加15）验收 `v0.4.3`**，本批两类：
   - **第六轮**：浅色 App 主题下打开编辑页 → 调色页**每个滑块的左侧标签应可读**（曝光 / 对比度 / 高光 …）；
     顶栏「编辑」、人像页「自动蒙版（AI 皮肤识别）」「已标记瑕疵点」应一并恢复；
     **深色主题下应当毫无变化**（这是本轮修复的设计约束）。看完再决定「进度条/菜单是否仍显过大」。
   - **第七轮**：拖任意参数滑块 → 预览应**只有完整帧**（不再出现横扫的横线 / 条纹），且**每帧都在动**；
     手指停下瞬间最后一帧就是松手时的参数；换图 / 退出编辑器不应「先闪一下上一张」。
   - **请回传日志**：`render done` 里的 **`ms`**（这是**完整帧**的单帧耗时）—— 拖「曝光」时的典型值。
     若仍偏大，下一轮才有明确目标去啃（届时才轮到 `BAND_ROWS` / 二次 pass / 并行粒度）。
2. 仍待真机确认的历史项：第四/五轮已发布项、批次 5 的 6 条（`docs/OBJECT_TONE_DESIGN.md` §12.2）、
   `TONE_ZONE_GAIN=0.35` 是否过猛、批次 1~4 的预览↔导出一致性、锐化晚于磨皮、A7C2 直连、人脸锚点。
3. 下一版若要发：先抬 `versionName`/`versionCode`（当前 `0.4.3` / `8`）→ push main 跑绿 → 再打 tag，两步走。
4. 新修改按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
5. ⚠️ **已知待办**：第一版真实设置迁移落地时**必须同批补 `SettingsMigrationTest`**（当前 v0→v1 为空迁移）。
6. ⚠️ **环境类失败预警**：`Failed to find package 'tools'` = 上游 SDK 变动，改 workflow `packages`，别改代码。
7. ⚠️ **Material3 实验性 API**：用 `Slider` 自定义 `thumb` 等须加 `@OptIn(ExperimentalMaterial3Api::class)`。
8. ⚠️ **日志埋点传可空值**：`DebugLog` 的 kv 是 `Map<String, Any>`（值**非空**）—— 一律 `?: 兜底`。
9. ⚠️ **`Crossfade` / `AnimatedContent` 的内容必须是单一节点**；**`selectable` / `clickable` / `toggleable`
   必须显式传 `indication = null`**；**自定义主题换 `colorScheme` 时必须同时补 `LocalContentColor`**。
   三条都已写进 `docs/UI_DESIGN.md`。
10. ⚠️ **执行 git commit 时不要在 `-m` 里用反引号**：bash 会把它当命令替换执行，提交信息里被反引号包住的
    标识符会被**静默吃掉**（本轮实测：`LocalContentColor`、`batch.epoch` 等全部消失）。改用 `git commit -F <文件>`。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 main 一次跑绿（4 job）→ 打 `v0.4.3` tag → 5 job 全绿含 Publish → Release 已发布。*
