# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-22（本地）
> 关联提交：`4a1a8bc58761495c11f7c89ef42ea547bc375e19`（versionName `0.3.1` / versionCode `4`）
> 发布 tag：`v0.3.1`

## 结论：✅ 全绿 —— main 推送 4 job 成功；`v0.3.1` tag 推送 **5 job 全绿（含 Publish GitHub Release）**，Release 已发布

本轮是**真机反馈第三轮**（纯排版/观感三修）：先抬版本号到 `0.3.1` 全量推送并在 main 上跑绿，
再在该已验证 commit 上打**附注标签** `v0.3.1` 推送，触发 `publish` 把签名 APK 挂到 GitHub Releases。

| 阶段 | 运行 | ref | 结论 |
|---|---|---|---|
| ① 全量推送 main | run **`35694905892`** | `main` | ✅ 4 job 绿（`Publish` 非 tag 故跳过） |
| ② 打 tag `v0.3.1` | run **`35695500853`** | `v0.3.1` | ✅ **5 job 全绿（含 Publish）** |

> 前置：上一轮 docs-only 提交 `382c2aa` 的 main run `35692631309` 也是 ✅（4 job，`Publish` 跳过）。

## 任务（Job）总览 — run `35695500853`（tag）

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest` 全过 |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Check signing secrets | ✅ success | 探测到 `KEYSTORE_BASE64` |
| **Signed Release** | ✅ success | 解 PKCS12 keystore → `assembleRelease`（启用 R8）→ 签名 APK + R8 mapping |
| **Publish GitHub Release** | ✅ success | `gh release create v0.3.1`（`--verify-tag --generate-notes`），附件 `pixelcake-v0.3.1-release.apk` |

> run `35694905892`（main）同批 4 job 全绿，`Publish` 因 `if: startsWith(github.ref, 'refs/tags/v')` 跳过，属预期。

## 本轮产出物（Artifacts）— run `35695500853`

| Artifact | 大小 | 保留 |
|---|---|---|
| `pixelcake-release-4a1a8bc…` | 20.76 MB | 90 天 |
| `pixelcake-debug-4a1a8bc…` | 31.03 MB | 90 天 |
| `pixelcake-mapping-4a1a8bc…` | 2.14 MB | 90 天 |
| `lint-report-4a1a8bc…` | 0.03 MB | 7 天 |

> R8 mapping 刻意只作 Actions artifact、**不进 Release 附件**，避免公开内部符号名。

## 发布的 Release

| 项 | 值 |
|---|---|
| Tag / 标题 | `v0.3.1` |
| 状态 | 已发布（`draft=false`, `prerelease=false`） |
| 附件 | `pixelcake-v0.3.1-release.apk`（28.94 MB） |
| 页面 | <https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.3.1> |
| Notes | 自动生成：`compare/v0.3.0...v0.3.1` |

## 本轮提交

| 提交 | 说明 |
|---|---|
| `4a1a8bc` | `fix(ui)`：真机反馈第三轮三修（见下）+ `release`：`versionName 0.3.0 → 0.3.1`、`versionCode 3 → 4` |

改动要点（6 文件，`+122 / −39`）：

1. **顶栏过高** `EditorScreen.kt` — `TOP_BAR_HEIGHT` 56dp → **44dp**（绑定 `Spacing.controlHeight`，不再写死数值）；`ExportPill` 40 → **36dp** 与 ghost 动作齐平。
2. **参数菜单挤在一起** `EditorScreen.kt` · `ParamPanel.kt` — ① 预览默认占比 `SPLIT_DEFAULT` 0.62 → **0.45**、`SPLIT_MIN` 0.28 → **0.22**（照片按**宽度**定尺，3:2 横片只需 ~285dp，0.62 时约 204dp 是纯空气，代价却是从参数区抢走）；② `GroupLabel` 由 `labelMedium`+`onSurfaceVariant` → **`titleMedium`+`onSurface`**，恢复面板内的层级表达（原先与滑块右侧数值完全同款）。
3. **预览圆角过大丢边缘细节** `EditorScreen.kt` · `Radius.kt` — 预览 `clip`/`border` 由容器级 `Radius.shell`(40dp) → 内容级 **`Radius.chip`(14dp)**（40dp 是给大面板/整表的，套在照片上会啃掉四角有效信息）。
4. **文档同步** `docs/UI_DESIGN.md` · `docs/ui_preview.html` — 新增 §4.0.3 第三轮修正 + 两条规律；圆角梯级订正为 14/22/28/40（补 UI-6 起的文档滞后）；顶栏 56→44px；预览圆角改 `chip`。

> 两条被坐实的规律：**① 圆角梯级 = 内容小、容器大**（照片/缩略图走 `chip` 14，卡片 `card` 22，面板/整表 `shell` 40 —— 给内容套容器半径 = 用装饰吃掉信息）；**② `weight` 的分母是「剩余」高度**，子内容不随权重缩放时（照片按宽度定尺），多给某区的高度只是空气，代价从兄弟区抢走。

## 发布流程（本次实际执行，供复用）

```bash
# 1) 先在 main 上验证（tag 必须打在已验证的 commit 上）
git add -A && git commit -m "fix(ui): ... ; release: 版本号 x.y.z"
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
- ⚠️ **已推送的 tag 不要删除/移动**（`git push --force --tags` 属历史改写）。发错版本的正确做法是**发下一个版本**并在 notes 里说明。

## 历史回归记录

| Run | 提交 / ref | 结论 | 失败点 |
|---|---|---|---|
| `34866770056` | `f28ccc5`（main） | ❌ failure | `EditorScreen.kt:149 Unresolved reference 'EditorStatus'`（与并行会话抢跑，定义文件未提交） |
| `34868197276` | `d29179e`（main） | ✅ success | 无（补齐定义后全绿） |
| `34870704645` | `e4b2c6e`（main） | ✅ success | 无（docs-only） |
| `35312379991` | `71fc0ba`（main） | ❌ failure | `Set up Android SDK`：`Failed to find package 'tools'`（action 默认 packages 含已下架包） |
| `35312573435` | `1bc9f9e`（main） | ❌ failure | `ParamSlider.kt:126` 实验性 Material3 API 未 opt-in |
| `35313027938` | `b3d609d`（main） | ✅ success | 无（环境 + opt-in 修复后全绿） |
| `35313769515` | `d5eb37d`（main） | ✅ success | 无（docs-only） |
| `35615284665` | `746f196`（main） | ✅ success | 无（v0.2.0 版本号推送） |
| `35616709639` | `v0.2.0`（tag） | ✅ success | 无（5 job 全绿，Release 已发布） |
| `35617373327` | `7877fda`（main） | ✅ success | 无（docs-only） |
| `35691497560` | `4ecf169`（main） | ✅ success | 无（v0.3.0 十文件大改，4 job 绿） |
| `35692247567` | `v0.3.0`（tag） | ✅ success | 无（5 job 全绿，Release 已发布） |
| `35692631309` | `382c2aa`（main） | ✅ success | 无（docs-only） |
| **`35694905892`** | **`4a1a8bc`（main）** | ✅ **success** | **无（4 job 绿）** |
| **`35695500853`** | **`v0.3.1`（tag）** | ✅ **success** | **无（5 job 全绿，Release 已发布）** |

## 后续步骤

1. 真机（一加15）安装 **release 签名包** `pixelcake-v0.3.1-release.apk` 回归本轮三修：
   - **顶栏**是否不再像「压住照片的一条带子」（56→44dp）；
   - **参数区可视高度**是否足够、**分组标题是否与数值区分得开**（默认比 0.62 时多约 135dp）；
   - **照片四角细节**是否回来（圆角 40→14dp）。
   - ⚠️ **判别信号**：横构图照片尺寸在 0.62→0.45 后**应完全不变**；若变了说明 `fitContentRect` 的宽度约束没生效。
2. 上一轮（v0.3.0）待回归项仍未验收：拖滑块不跳 / 拖动后不误触 / 拖完立刻返回不崩 / 升级后设置保持。
3. 其余真机验收点不变：A2 涂抹落点 / A1 导出内存日志 / P1p-1c 自动蒙版 / A7C2 直连 / P1p-2c 人脸锚点。
4. 新修改按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
5. ⚠️ **已知待办（未修，需拍板）**：第一版真实设置迁移落地时**必须同批补 `SettingsMigrationTest`**（当前 v0→v1 为空迁移，无可测内容）。
6. ⚠️ **环境类失败预警**：`Failed to find package 'tools'` / `sdkmanager` 失败 = 上游 SDK 变动，改 workflow `packages`，别改代码；
   lint/编译的 `Gradle build daemon disappeared` 仍按老办法 `ci_rerun.py` 重跑。
7. ⚠️ **Material3 实验性 API**：用 `Slider` 自定义 `thumb` 等须加 `@OptIn(ExperimentalMaterial3Api::class)`，否则 ERROR 级编译失败。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 main 4 job 绿 + tag 5 job 全绿（含 Publish），Release `v0.3.1` 已发布。*
