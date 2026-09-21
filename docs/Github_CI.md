# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-21（本地）
> 关联提交：`746f196bd4b14d3a192b698b6392a64d8f96abff`（versionName `0.2.0` / versionCode `2`）
> 发布 tag：`v0.2.0`

## 结论：✅ 全绿 —— main 推送 4 job 成功；`v0.2.0` tag 推送 **5 job 全绿（含 Publish GitHub Release）**，Release 已发布

本轮为 **UI-6「克制液态玻璃」里程碑发版**：先把版本号抬到 `0.2.0`（`746f196`）全量推送并在 main 上跑绿，
再在该已验证 commit 上打**附注标签** `v0.2.0` 推送，触发 `publish` 把签名 APK 挂到 GitHub Releases。

| 阶段 | 运行 | ref | 结论 |
|---|---|---|---|
| ① 版本号推送 main | run **`35615284665`** | `main` | ✅ 4 job 绿（`Publish` 非 tag 故跳过） |
| ② 打 tag `v0.2.0` | run **`35616709639`** | `v0.2.0` | ✅ **5 job 全绿（含 Publish）** |

## 任务（Job）总览 — run `35616709639`（tag）

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest` 全过 |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Check signing secrets | ✅ success | 探测到 `KEYSTORE_BASE64` |
| **Signed Release** | ✅ success | 解 PKCS12 keystore → `assembleRelease`（启用 R8）→ 签名 APK + R8 mapping |
| **Publish GitHub Release** | ✅ success | `gh release create v0.2.0`（`--verify-tag --generate-notes`），附件 `pixelcake-v0.2.0-release.apk` |

> run `35615284665`（main）同批 4 job 全绿，`Publish` 因 `if: startsWith(github.ref, 'refs/tags/v')` 跳过，属预期。

## 本轮产出物（Artifacts）— run `35616709639`

| Artifact | 大小 | 保留 |
|---|---|---|
| `pixelcake-release-746f196…` | 20.75 MB | 90 天 |
| `pixelcake-debug-746f196…` | 31.01 MB | 90 天 |
| `pixelcake-mapping-746f196…` | 2.13 MB | 90 天 |
| `lint-report-746f196…` | 0.03 MB | 7 天 |

> R8 mapping 刻意只作 Actions artifact、**不进 Release 附件**，避免公开内部符号名。

## 发布的 Release

| 项 | 值 |
|---|---|
| Tag / 标题 | `v0.2.0` |
| 状态 | 已发布（`draft=false`, `prerelease=false`） |
| 附件 | `pixelcake-v0.2.0-release.apk`（28.94 MB） |
| 页面 | <https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.2.0> |
| Notes | 自动生成：`compare/v0.1.0...v0.2.0` |

## 本轮提交

| 提交 | 说明 |
|---|---|
| `746f196` | `release`：`versionName 0.1.0 → 0.2.0`、`versionCode 1 → 2`（与 tag 对齐，`AboutSheet` 会显示 `v0.2.0 (2)`） |

自上个 tag `v0.1.0` 起（本次发版包含）的主要改动：
`71fc0ba`(UI-6 液态玻璃) → `1bc9f9e`(修 setup-android `tools` 包) → `b3d609d`(ParamSlider `@OptIn`) → `d5eb37d`(CI 报告) → `746f196`(版本号)。

## 发布流程（本次实际执行，供复用）

```bash
# 1) 先在 main 上验证（tag 必须打在已验证的 commit 上）
git add -A && git commit -m "release: 版本号 x.y.z"
git push origin main                    # 等 main 这轮 CI 绿

# 2) 打附注标签并推送（触发 publish job）
git tag -a vX.Y.Z -m "PixelCake vX.Y.Z"
git push origin vX.Y.Z
```

## 🔐 安全边界（重要）

- 入库的 `app/debug.keystore` 是 Android 标准调试密钥，口令（`android`/`androiddebugkey`）为**公开约定、非机密**；
  `.gitignore` 用 `!debug.keystore` 有意反忽略，只为让 debug 签名跨构建稳定。
- ⚠️ **release keystore 绝不能入库**：仅存于 GitHub Secrets，CI 解到 `$RUNNER_TEMP` 临时落盘、job 结束即删。
- 若将来**误提交**真实密钥/口令，仅删文件**不够** —— 必须用 `git filter-repo`（或 BFG）清理**历史**并立刻轮换。
- ⚠️ **已推送的 tag 不要删除/移动**（`git push --force --tags` 属历史改写，会破坏别人已克隆的引用）。
  发错版本的正确做法是**发下一个版本**（如 `v0.2.1`）并在 notes 里说明。

## 历史回归记录

| Run | 提交 / ref | 结论 | 失败点 |
|---|---|---|---|
| `34866770056` | `f28ccc5`（main） | ❌ failure | `EditorScreen.kt:149 Unresolved reference 'EditorStatus'`（与并行会话抢跑，定义文件未提交） |
| `34868197276` | `d29179e`（main） | ✅ success | 无（补齐定义后全绿） |
| `34870704645` | `e4b2c6e`（main） | ✅ success | 无（docs-only） |
| `34859073004` | `e633ec6`（main） | ✅ success | 无（首次 Signing Release 成功） |
| `34865057998` | `v0.1.0`（tag） | ✅ success | 无（首次发布 GitHub Release，5 job） |
| `35312379991` | `71fc0ba`（main） | ❌ failure | `Set up Android SDK`：`Failed to find package 'tools'`（action 默认 packages 含已下架包） |
| `35312573435` | `1bc9f9e`（main） | ❌ failure | `ParamSlider.kt:126` 实验性 Material3 API 未 opt-in |
| `35313027938` | `b3d609d`（main） | ✅ success | 无（环境 + opt-in 修复后全绿） |
| `35313769515` | `d5eb37d`（main） | ✅ success | 无（docs-only） |
| `35615284665` | `746f196`（main） | ✅ success | 无（版本号推送） |
| **`35616709639`** | **`v0.2.0`（tag）** | ✅ **success** | **无（5 job 全绿，Release 已发布）** |

## 后续步骤

1. 真机（一加15）安装 **release 签名包** `pixelcake-v0.2.0-release.apk` 验证 UI-6：
   - **液态玻璃观感**：TabBar / 工具条选中块（白渐变 + 顶部亮线）、浮层外投影、`ParamSlider` 白色拇指；
   - **圆角阶梯** 14/22/28/40 是否观感统一；
   - **系统栏图标**：系统浅色模式下进编辑页（强制深色），时钟/电量应可见（浅色图标）；
   - **参数面板**：切换分类不应「闪一下」（卡片已在 `Crossfade` 外）；最后一个滑块不被手势条压住；
   - 其余回归：预览手势落点、ML 自动蒙版、retouch、Beauty、ToneCurve、相机批量。
2. 新修改按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
3. ⚠️ **环境类失败预警**：若再遇 `Failed to find package 'tools'` / `sdkmanager` 失败，是**上游 SDK 仓库变动**，
   改 workflow 的 `packages` 即可，**别改代码**。lint/编译 job 的 `Gradle build daemon disappeared` 仍按老办法 `ci_rerun.py` 重跑。
4. ⚠️ **Material3 实验性 API**：用到 `Slider` 自定义 `thumb` 等实验特性须加 `@OptIn(ExperimentalMaterial3Api::class)`，
   否则是 ERROR 级编译失败（文案含 "This material API is experimental"）。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 main 4 job 绿 + tag 5 job 全绿（含 Publish），Release `v0.2.0` 已发布。*
