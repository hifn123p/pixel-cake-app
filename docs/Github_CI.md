# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-22（本地）
> 关联提交：`4ecf169f1990e3239af05db7cefd8fce8b8768d9`（versionName `0.3.0` / versionCode `3`）
> 发布 tag：`v0.3.0`

## 结论：✅ 全绿 —— main 推送 4 job 成功；`v0.3.0` tag 推送 **5 job 全绿（含 Publish GitHub Release）**，Release 已发布

本轮把**真机反馈第二轮 + 第三轮**的改动发版：先抬版本号到 `0.3.0` 全量推送并在 main 上跑绿，
再在该已验证 commit 上打**附注标签** `v0.3.0` 推送，触发 `publish` 把签名 APK 挂到 GitHub Releases。
（本批改动量大：10 文件、`+1487 / −315`，含新文件 `ui/settings/AppSettings.kt`，且首次引入 `BoxWithConstraints`／自定义 `pointerInput`／`draggable(enabled=…)` —— 一次通过。）

| 阶段 | 运行 | ref | 结论 |
|---|---|---|---|
| ① 全量推送 main | run **`35691497560`** | `main` | ✅ 4 job 绿（`Publish` 非 tag 故跳过） |
| ② 打 tag `v0.3.0` | run **`35692247567`** | `v0.3.0` | ✅ **5 job 全绿（含 Publish）** |

## 任务（Job）总览 — run `35692247567`（tag）

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest` 全过 |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Check signing secrets | ✅ success | 探测到 `KEYSTORE_BASE64` |
| **Signed Release** | ✅ success | 解 PKCS12 keystore → `assembleRelease`（启用 R8）→ 签名 APK + R8 mapping |
| **Publish GitHub Release** | ✅ success | `gh release create v0.3.0`（`--verify-tag --generate-notes`），附件 `pixelcake-v0.3.0-release.apk` |

> run `35691497560`（main）同批 4 job 全绿，`Publish` 因 `if: startsWith(github.ref, 'refs/tags/v')` 跳过，属预期。

## 本轮产出物（Artifacts）— run `35692247567`

| Artifact | 大小 | 保留 |
|---|---|---|
| `pixelcake-release-4ecf169…` | 20.76 MB | 90 天 |
| `pixelcake-debug-4ecf169…` | 31.03 MB | 90 天 |
| `pixelcake-mapping-4ecf169…` | 2.14 MB | 90 天 |
| `lint-report-4ecf169…` | 0.03 MB | 7 天 |

> R8 mapping 刻意只作 Actions artifact、**不进 Release 附件**，避免公开内部符号名。

## 发布的 Release

| 项 | 值 |
|---|---|
| Tag / 标题 | `v0.3.0` |
| 状态 | 已发布（`draft=false`, `prerelease=false`） |
| 附件 | `pixelcake-v0.3.0-release.apk`（28.94 MB） |
| 页面 | <https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.3.0> |
| Notes | 自动生成：`compare/v0.2.0...v0.3.0` |

## 本轮提交

| 提交 | 说明 |
|---|---|
| `4ecf169` | `feat(ui)`：真机反馈第二轮 + 第三轮修复（见下）+ `release`：`versionName 0.2.0 → 0.3.0`、`versionCode 2 → 3` |

改动要点（10 文件，`+1487 / −315`）：

1. **首页** `HomeScreen.kt` — `StartHero` 补 `.fillMaxWidth()`（修「一条竖窄玻璃条 + 一大片空白」）；内容改为 72dp 玻璃圆「＋」+ 动作标签 + 范围说明；`HomeTopBar` 去掉与首屏重复的「＋」，签名收成 `()`。
2. **预览去黑边** `EditorScreen.kt` — 预览改 `BoxWithConstraints`，用 `fitContentRect` **反算矩形给内层 `Image` 定尺**（黑边从根上消失，且「预览矩形 == 手势内容矩形」）；去掉黑底/圆角/描边。
3. **导出按钮降权** `ExportSheet.kt` — 成功后主按钮改**描边** + 文案「再导一次」+ 一行「不必重复导出」；`Success` 时不再显示「导出到相册（JPEG）」。
4. **底栏五段式 + 可拖分隔条** `EditorScreen.kt` — 预览区与参数区改**权重对** `weight(previewFraction)`/`weight(1-previewFraction)`（去掉写死 `heightIn(max=320.dp)`）；新增 28dp 热区 / 4dp 视觉线的拖拽分隔条；顶栏动作改手写紧凑 ghost 控件（M3 `TextButton` 有 58dp `minWidth`，5 个必溢出 360dp 屏）。
5. **设置持久化** `AppSettings.kt`（新增）· `SettingsScreen.kt` · `MainActivity.kt` · `AboutSheet.kt` — `ThemeMode`(跟随系统/浅色/深色) + `JpegQuality`(95/92/80) + `SharedPreferences` + `mutableStateOf` 可观察；设置页重排为**外观 / 导出 / AI 与性能 / 存储 / 关于 / 高级**六组；`appVersionLabel()` 抽公用。
6. **对比基准同口径** `MainActivity.kt` — 首次渲染完成后留一份零编辑副本作 `compareBase`；此前拿**解码预览图**跟**渲染图**比 ⇒ 参数全中性也必然跳（曝光曲线 + 长宽口径不同）。
7. **P0 崩溃修复** `MainActivity.kt` — `onBack` 与在途渲染的**位图回收竞态**：新增 `imageEpoch`(AtomicInteger) 代次作废在途批次、回收纳入 `renderMutex` 串行化、`src.bitmap` 读者不唯一改交 GC；`exporting` 提前同步置位并补全提前返回的复位。
8. **文档** `docs/UI_DESIGN.md` · `docs/ui_preview.html` — 五段式布局、2026-09-22 修订、真机反馈修正清单；预览 HTML 同步（版本号升 0.2.0→0.3.0，div 配平 250/250）。

## 发布流程（本次实际执行，供复用）

```bash
# 1) 先在 main 上验证（tag 必须打在已验证的 commit 上）
git add -A && git commit -m "feat(ui): ... ; release: 版本号 x.y.z"
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
| **`35691497560`** | **`4ecf169`（main）** | ✅ **success** | **无（本批 10 文件大改，4 job 绿）** |
| **`35692247567`** | **`v0.3.0`（tag）** | ✅ **success** | **无（5 job 全绿，Release 已发布）** |

## 后续步骤

1. 真机（一加15）安装 **release 签名包** `pixelcake-v0.3.0-release.apk` 回归本轮重点：
   - **拖滑块时画面是否还会跳**（应是「完全不跳」）；
   - **拖动结束后顶栏 / 工具条是否误触**（应「看着隐身就点不到」）；
   - **拖完滑块立刻点返回是否还崩**（P0 竞态修复）；
   - **升级安装后设置是否保持**（主题模式 / JPEG 质量 / 降低透明度）；
   - 首页是否还有空白、预览是否还有黑边、导出成功按钮是否降权、底栏五段式 + 分隔条手感。
2. 其余真机验收点不变：A2 涂抹落点 / A1 导出内存日志 / P1p-1c 自动蒙版 / A7C2 直连 / P1p-2c 人脸锚点。
3. 新修改按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
4. ⚠️ **已知待办（未修，需拍板）**：第一版真实设置迁移落地时**必须同批补 `SettingsMigrationTest`**（当前 v0→v1 为空迁移，无可测内容）。
5. ⚠️ **环境类失败预警**：`Failed to find package 'tools'` / `sdkmanager` 失败 = 上游 SDK 变动，改 workflow `packages`，别改代码；
   lint/编译的 `Gradle build daemon disappeared` 仍按老办法 `ci_rerun.py` 重跑。
6. ⚠️ **Material3 实验性 API**：用 `Slider` 自定义 `thumb` 等须加 `@OptIn(ExperimentalMaterial3Api::class)`，否则 ERROR 级编译失败。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 main 4 job 绿 + tag 5 job 全绿（含 Publish），Release `v0.3.0` 已发布。*
