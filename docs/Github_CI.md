# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-18（本地）
> 关联提交：`b3d609d4e9155aaf68b9a8a1c607b1ae2d62922b`
> 运行链接：<https://github.com/hifn123p/pixel-cake-app/actions/runs/35313027938>

## 结论：✅ 成功（success）—— 4 个 job 全绿（含签名 Release）

本轮是 **UI-6「克制液态玻璃」改版**。前两次 push 分别因 **CI 环境坏** 与 **一条实验性 API 未 opt-in** 失败，
修复后第三次 push 一次通过。最终 4 job 全绿。

## 任务（Job）总览

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest` 全过 |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Check signing secrets | ✅ success | 探测到 `KEYSTORE_BASE64` |
| **Signed Release** | ✅ success | 解 PKCS12 keystore → `assembleRelease`（启用 R8）→ 签名 APK + R8 mapping |
| Publish GitHub Release | ⏭️ skip | **仅 `v*` tag 触发**；本次为 `main` push，跳过属预期 |

## 本轮产出物（Artifacts）

| Artifact | 大小 | 保留 |
|---|---|---|
| `pixelcake-release-b3d609d…` | 20.75 MB | 90 天 |
| `pixelcake-debug-b3d609d…` | 31.01 MB | 90 天 |
| `pixelcake-mapping-b3d609d…` | 2.13 MB | 90 天 |
| `lint-report-b3d609d…` | 0.03 MB | 7 天 |

下载：Actions → 本轮 run `35313027938` → 页面底部 Artifacts；或 API `…/actions/runs/35313027938/artifacts`。

## 本批提交

| 提交 | 说明 |
|---|---|
| `71fc0ba` | `feat(ui)`：**UI-6「克制液态玻璃」** —— `GlassTint` 由平色升级为渐变 `Brush` 底 / 渐变描边 + 外投影；新增共享 `segmentIndicator`（收敛审计 L3 的两套近似实现）；`ParamSlider` 改白色实心拇指；圆角阶梯 12/20/28/36 → **14/22/28/40**；系统栏图标跟随**生效主题**；参数面板卡片移出 `Crossfade` + 补 `navigationBarsPadding` |
| `1bc9f9e` | `ci`：修复 `android-actions/setup-android@v3` 默认 `packages` 含已下架的 `tools` 包导致三 job 直接红 |
| `b3d609d` | `fix(ui)`：`ParamSlider` 对 Material3 实验性 `Slider`（自定义 `thumb`）加 `@OptIn(ExperimentalMaterial3Api::class)` |

改动明细（UI-6 摘要）：

1. **液态玻璃** — `GlassTint` 的 `surface`/`border` 从 `Color` 改为 `Brush`（顶亮→中透→底回光 + 顶亮底暗描边），
   并新增 `shadow`/`elevation`（浮层外投影）。明暗两套主题方向一致，调用点无需翻转。
   `Brush` 在 `object Glass` 初始化时建一次并缓存（避免每帧新建渐变对象）。
2. **选中块统一** — `GlassSegmentedBar` 与 `AppShell.GlassTabBar` 原先各写一遍「强调色平色块」，
   现统一走 `Modifier.segmentIndicator()`：深色下白渐变+顶部亮线，浅色下退回强调色淡染（白底白块不可见）。
3. **参数面板闪动修复** — `Crossfade` 过渡期会同时组合新旧两份内容，不透明卡片叠加会使面板先变暗再回亮；
   卡片提到 `Crossfade` **之外**，只让内容淡换。
4. **系统栏图标** — `MainActivity` 的 `enableEdgeToEdge()` 按系统 `uiMode` 决定图标明暗，
   但编辑页强制深色不改 `uiMode` ⇒ 系统浅色时会拿到「深色图标压深色工作台」。新增 `SystemBarIcons()` 按**生效主题**纠正。
5. **圆角阶梯** — 14 / 22 / 28 / 40 dp（`sheet` 保持 28 与屏幕圆角对齐），零调用点改动。

## 本轮两次失败与根因（重要，供后人少走弯路）

### ❌ run `35312379991`（`71fc0ba`）—— **CI 环境坏，非代码问题**
- 现象：`Build` / `Lint` 双红，但失败点是 `Set up Android SDK`，**根本没跑到编译**。
- 根因：`android-actions/setup-android@v3` 的 `packages` 输入**默认值是 `'tools platform-tools'`**，
  而 Google 已下架遗留的 **`tools`** 包 ⇒ `sdkmanager tools` 报
  `Warning: Failed to find package 'tools'` + `Error: The process '...sdkmanager' failed with exit code 1`。
  （9/15 还是绿的；9/18 runner 镜像升到 **cmdline-tools 16.0** 后才复现。）
- 修复 `1bc9f9e`：三处 `Set up Android SDK` 均显式传 `with: packages: 'platform-tools'`（去掉 `tools`）。
  真正需要的 SDK 平台 / NDK / CMake 由 `./.github/actions/setup-android-toolchain` 负责。

### ❌ run `35312573435`（`1bc9f9e`）—— **真实代码问题：实验性 API 未 opt-in**
- 现象：`e: .../ui/components/ParamSlider.kt:126:9 This material API is experimental and is likely to change or to be removed in the future.` → `:app:compileDebugKotlin FAILED`。
- 根因：`Slider` 的**自定义 `thumb` 重载**在 Material3 里标了 `@ExperimentalMaterial3Api`（ERROR 级），未 opt-in 即编译失败；
  原代码不传 `thumb`，走非实验性重载，所以以前不报。
- 修复 `b3d609d`：给 `ParamSlider` 加 `@OptIn(ExperimentalMaterial3Api::class)` + 对应 import；**保留**自定义白色拇指 + 投影的设计。

> 排查提示：这类「`e:` 报在某行、但报错文案是 experimental」的失败，**先看文案判断是哪个注解**——
> "This material API is experimental…" 即 `@ExperimentalMaterial3Api`；若含 "Expressive" 则是 `@ExperimentalMaterial3ExpressiveApi`。

## 发布流水线（保持有效）

- 已发布 **Release `v0.1.0`**（非草稿）：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.1.0>。
- 打 `v*` tag 自动触发 `publish`，附件名 `pixelcake-<tag>-release.apk`
  （`git tag -a vX.Y.Z -m "..." && git push origin vX.Y.Z`）。

## 🔐 安全边界（重要）

- 入库的 `app/debug.keystore` 是 Android 标准调试密钥，口令（`android`/`androiddebugkey`）为**公开约定、非机密**；
  `.gitignore` 用 `!debug.keystore` 有意反忽略，只为让 debug 签名跨构建稳定。
- ⚠️ **release keystore 绝不能入库**：仅存于 GitHub Secrets，CI 解到 `$RUNNER_TEMP` 临时落盘、job 结束即删。
- 若将来**误提交**真实密钥/口令，仅删文件**不够** —— 必须用 `git filter-repo`（或 BFG）清理**历史**并立刻轮换。

## 历史回归记录

| Run | 提交 | 结论 | 失败点 |
|---|---|---|---|
| `34866770056` | `f28ccc5` | ❌ failure | `EditorScreen.kt:149 Unresolved reference 'EditorStatus'`（与并行会话抢跑，定义文件未提交） |
| `34868197276` | `d29179e` | ✅ success | 无（补齐定义后全绿） |
| `34870704645` | `e4b2c6e` | ✅ success | 无（docs-only） |
| `35312379991` | `71fc0ba` | ❌ failure | `Set up Android SDK`：`Failed to find package 'tools'`（action 默认 packages 含已下架包） |
| `35312573435` | `1bc9f9e` | ❌ failure | `ParamSlider.kt:126` 实验性 Material3 API 未 opt-in |
| `35313027938` | `b3d609d` | ✅ success | 无（环境 + opt-in 修复后全绿） |
| `34859073004` | `e633ec6` | ✅ success | 无（首次 Signing Release 成功） |
| `34865057998` | `18054b8`（tag `v0.1.0`） | ✅ success | 无（首次发布 GitHub Release，5 job） |

## 后续步骤

1. 真机（一加15）下载 **release 签名 APK** 与 **debug APK** 验证 UI-6：
   - **液态玻璃观感**：TabBar / 工具条选中块（白渐变 + 顶部亮线）、浮层外投影、`ParamSlider` 白色拇指；
   - **圆角阶梯** 14/22/28/40 是否观感统一；
   - **系统栏图标**：系统浅色模式下进编辑页（强制深色），时钟/电量应可见（浅色图标）；
   - **参数面板**：切换分类不应「闪一下」（卡片已在 `Crossfade` 外）；最后一个滑块不被手势条压住；
   - 其余回归：预览手势落点、ML 自动蒙版、retouch、Beauty、ToneCurve、相机批量。
2. 正式发版：打 `v*` tag → 自动 `publish`。
3. 新修改按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
4. ⚠️ **环境类失败预警**：若再遇 `Failed to find package 'tools'` / `sdkmanager` 失败，是**上游 SDK 仓库变动**，
   改 workflow 的 `packages` 即可，**别改代码**。lint/编译 job 的 `Gradle build daemon disappeared` 仍按老办法 `ci_rerun.py` 重跑。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮最终 4 job 全绿（含签名 Release），Publish 仅 tag 触发故跳过。*
