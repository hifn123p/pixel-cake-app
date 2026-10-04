# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-10-04（本地）
> 关联提交：`f0bcaea0f7a89b118dd530a85846c10a9574eee9`（**versionName `0.4.4` / versionCode `9` —— 本轮已发版**）
> 本轮主题：**UI Redesign v2.0（字号 / 品牌色 / 滑块 / 间距 / 玻璃控件触控）+ 首轮编译错修复，发布 `v0.4.4`**

## 结论：✅ 两轮 main 后全绿发版 —— 首轮红（真实编译错）→ 修复后 main 绿 → 打 tag，5 job 全过（含 Publish），Release 已发布

| 阶段 | 运行 | ref / 提交 | 结论 |
|---|---|---|---|
| ① main 推 UI 重设计 + 抬版本号 | run **`37192930775`** | `2633e34`（main） | ❌ **failure**（`compileDebugKotlin` 2 错） |
| ② main 推编译修复 | run **`37193138300`** | `f0bcaea`（main） | ✅ **4 job 全绿**（`Publish` 非 tag 跳过） |
| ③ tag 触发发布 | run **`37193562293`** | `v0.4.4`（`f0bcaea`） | ✅ **5 job 全绿**（含 `Publish GitHub Release`） |

> 🚀 **Release 已发布**：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.4.4>
> 附件 `pixelcake-v0.4.4-release.apk`（**29.01 MB**），已供真机（一加15）下载验收。

## 任务（Job）总览 — run `37193562293`（tag run，最终绿）

| Job | 结论 | 说明 |
|---|---|---|
| Check signing secrets | ✅ success | 探测到 `KEYSTORE_BASE64` |
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest` 全过 |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Signed Release | ✅ success | 解 PKCS12 keystore → `assembleRelease`（R8）→ 签名 APK + mapping |
| Publish GitHub Release | ✅ success | `v*` tag 触发，上传 release APK 并创建 Release（`contents: write`） |

> 与 main run `37193138300` 的唯一差别：后者为非 tag push，`Publish` 按设计 **⏭️ skip**。
> 两步走的**闸门设计生效**：只有「main 已绿」的 commit 才被打了 tag。

## ❌ 本轮真实故障：首轮 `37192930775` main 红（`compileDebugKotlin`）

**报错（Build 与 Lint 两个 job 同一处，都是预编译步就红）**：

```
e: .../ui/components/GlassChipRow.kt:76:17  No parameter with name 'contentPadding' found.
e: .../ui/components/GlassChipRow.kt:81:53  Unresolved reference 'size'.
> Task :app:compileDebugKotlin FAILED
BUILD FAILED in 37s
```

**根因（两条，都在同一个文件）**：

1. **`FilterChip` 没有 `contentPadding` 参数** —— M3 的 `FilterChip` 只接受 `leadingIcon` / `trailingIcon` /
   `label` / `shape` / `colors` / `border` 等，**没有 `contentPadding`**（那是 `ElevatedFilterChip` 之外的
   低层重载 / 别的控件才有）。UI 重设计时想「把 chip 左右内边距拉回 `Spacing.m`」，直接写了这个实参 ⇒ 找不到命名参数。
2. **`Modifier.size` 未导入** —— 文件 import 了 `fillMaxWidth` / `height` / `width`，**独独漏了
   `androidx.compose.foundation.layout.size`**（色块圆点 `Box(Modifier.size(Spacing.s))` 用到）。

> **性质判定**：这不是 infra 抖动（不是 Gradle daemon 崩、不是 `Failed to find package 'tools'`），
> 是**代码级真错**，必须改代码。且 Build / Lint 是**同一个编译错**的两处回显（Lint 也先编 main）。

**修法（`GlassChipRow.kt` 单文件，保持原设计意图）**：

- 移除非法实参 `contentPadding = PaddingValues(horizontal = Spacing.m, vertical = 0.dp)`；
  改为在 `label` 槽内部的 `Row` 上加 `Modifier.padding(horizontal = Spacing.m)` —— 效果等价
  （撑开 chip 内容与边框的水平间距），**不改视觉纪律**（仍只走 `Radius.chip` + `Spacing.m`）。
- 补 `import androidx.compose.foundation.layout.size`。
- 顺带删掉随之不再使用的 `PaddingValues` 导入。

**修复提交** `f0bcaea` → 重推 main → run `37193138300` **一次全绿**。

## 本轮产出物（Artifacts）— run `37193562293` / `37193138300`

| Artifact | 保留 |
|---|---|
| `pixelcake-release-f0bcaea…` | 90 天 |
| `pixelcake-debug-f0bcaea…` | 90 天 |
| `pixelcake-mapping-f0bcaea…` | 90 天 |
| `lint-report-f0bcaea…` | 7 天 |

**Release 附件**：`pixelcake-v0.4.4-release.apk`（29.01 MB）。

## 本轮改动：UI Redesign v2.0（9 文件 + 新增 `docs/UI_REDESIGN.md`）

| 文件 | 变更 |
|---|---|
| `ui/theme/Type.kt` | 字号收敛为 **5 级阶梯**（displaySmall 28 / titleMedium 18 / bodyMedium 15 / labelMedium 13 / labelSmall 12） |
| `ui/theme/Color.kt` | 品牌 Seed 提亮 11 级（`0xFF7C5CFF` → `0xFF8B6FFF`），SeedOnDark 同步（`0xFF9C86F7` → `0xFFA894FF`），色相 252 一致；动态取色保持关闭 |
| `ui/components/ParamSlider.kt` | 拇指 18→**24dp**、阴影 3→4dp、形状统一 `Radius.pill`、移除有兼容风险的 `activeTrackStroke` |
| `ui/theme/Spacing.kt` | `xl` 24→20、`xxl` 32→28、`controlHeight` 44→**48dp** |
| `ui/editor/EditorScreen.kt` / `ParamPanel.kt` | 面板垂直间距 `Spacing.s` → `Spacing.m`，呼吸感提升 |
| `ui/shell/AppShell.kt` | 玻璃 TabBar 高度 56→**64dp** |
| `ui/components/GlassSegmentedBar.kt` | 选中项增加水平 padding |
| `ui/components/GlassChipRow.kt` | 内边距回到间距阶梯（**修复见上**） |
| `docs/UI_REDESIGN.md` | 新增：完整设计变更文档 + 真机触控热区验收待办 |

### 本轮提交

| 提交 | 说明 |
|---|---|
| `ca98a17` | `feat(ui)`：UI Redesign v2.0（10 文件，`+111 / −33`） |
| `2633e34` | `chore(release)`：抬版本 `0.4.3 → 0.4.4`（`versionCode 8 → 9`） |
| `f0bcaea` | `fix(ui)`：修正 `GlassChipRow` 编译错（1 文件，`+6 / −3`） |

## ⭐ 复盘（供后人少走弯路）

1. **M3 控件参数不能凭「应该有」写**：`FilterChip` 没有 `contentPadding`；想调 chip 内边距要落到 `label` 内容上。
   **本地无编译器时，新增控件实参前必须对源码签名核一遍**（本仓高频坑：`64.dp` 缺 `import ...unit.dp`、
   `togetherWith` 误写 `togetherTo` —— 现在再加一条：**`Modifier.size` 缺 `foundation.layout.size` 导入**）。
2. **一处编译错 = Build 与 Lint 两个 job 同时红**，且都在**预编译步**就失败（还没跑到真实 lint）。
   看到「Build + Lint 同时红、报同一个 `compileDebugKotlin`」先别怀疑环境，去看那行代码。
3. **「infra 抖动」与「真错」的判据**：抖动 = Build 绿而 Lint 挂、日志只有 `hs_err_pid*.log`、无 `e: ...kt`；
   真错 = 有 `e: file:///...kt:行:列` 的编译器报错。本轮属后者 ⇒ 改代码，重跑无用。
4. **`compileDebugKotlin` 挂 ⇒ `compileDebugUnitTestKotlin` 根本没跑**：修完 main 的错误后，
   测试侧可能还埋着第二轮错误 —— 本轮同一文件只有这两处，重推一次即全绿。

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
| **`37193562293`** | **`v0.4.4`（tag）** | ✅ **success** | **无（5 job 全绿含 Publish，Release 已发布）** |

## 后续步骤

1. **真机（一加15）验收 `v0.4.4`**，本批为纯 UI/视觉重设计，重点看：
   - 字号：整体层级是否清晰、有无过大/过小的跳变；
   - 品牌色：主色是否更「亮」而不刺眼（Seed `0xFF8B6FFF`）；
   - 滑块：拇指 24dp 是否更好按、拖拽是否顺手；
   - 触控热区：玻璃 TabBar 64dp、SegmentedBar / ChipRow 是否更舒适；
   - 间距：编辑器面板呼吸感（`Spacing.m`）是否合适，有无「太松」；
   - **两种主题（浅 / 深）各看一次** —— 历史教训：深浅相反才现形的 bug（`LocalContentColor`）必须两主题都验。
2. 仍待真机确认的历史项：第六轮面板文字（浅色主题）、第七轮拖参数「只有完整帧 + 每帧都在动」
   + `render done` 的 `ms` 报数、第四/五轮已发布项、批次 5 的 6 条（`docs/OBJECT_TONE_DESIGN.md` §12.2）、
   `TONE_ZONE_GAIN=0.35` 是否过猛、批次 1~4 预览↔导出一致性、锐化晚于磨皮、A7C2 直连、人脸锚点。
3. 下一版若要发：先抬 `versionName`/`versionCode`（当前 `0.4.4` / `9`）→ push main 跑绿 → 再打 tag，两步走。
4. 新修改按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
5. ⚠️ **已知待办**：第一版真实设置迁移落地时**必须同批补 `SettingsMigrationTest`**（当前 v0→v1 为空迁移）。
6. ⚠️ **环境类失败预警**：`Failed to find package 'tools'` = 上游 SDK 变动，改 workflow `packages`，别改代码。
7. ⚠️ **Material3 实验性 API**：用 `Slider` 自定义 `thumb` 等须加 `@OptIn(ExperimentalMaterial3Api::class)`。
8. ⚠️ **日志埋点传可空值**：`DebugLog` 的 kv 是 `Map<String, Any>`（值**非空**）—— 一律 `?: 兜底`。
9. ⚠️ **M3 控件实参要核签名**：`FilterChip` 无 `contentPadding`；`Modifier.size` 要 `import foundation.layout.size`。
   本地无编译器 ⇒ 新增控件实参前**对源码签名核一遍**。
10. ⚠️ **`Crossfade` / `AnimatedContent` 的内容必须是单一节点**；**`selectable` / `clickable` / `toggleable`
    必须显式传 `indication = null`**；**自定义主题换 `colorScheme` 时必须同时补 `LocalContentColor`**。
    三条都已写进 `docs/UI_DESIGN.md`。
11. ⚠️ **执行 git commit 时不要在 `-m` 里用反引号**：bash 会把它当命令替换执行，提交信息里被反引号包住的
    标识符会被**静默吃掉**。改用 `git commit -F <文件>`。
12. ℹ️ **网络环境**：本机 hosts 把 `github.com` 指向 `127.0.0.1`（黑洞），直连 22 端口失败；
    push 需走 `ssh.github.com:443`（`GIT_SSH_COMMAND="ssh -p 443 -o HostName=ssh.github.com"`）。
    详见会话侧备忘，**不改仓库/全局配置**，只在单条 push 命令上临时覆盖。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 main 首轮红（真实编译错）→ 修复 → main 一次跑绿（4 job）→ 打 `v0.4.4` tag → 5 job 全绿含 Publish → Release 已发布。*
