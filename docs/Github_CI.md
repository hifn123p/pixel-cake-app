# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-15（本地）
> 关联提交：`d29179e35eaef4b9cb8053ec5ef427d720458703`
> 运行链接：<https://github.com/hifn123p/pixel-cake-app/actions/runs/34868197276>

## 结论：✅ 成功（success）—— 4 个 job 全绿（含签名 Release）

本轮把「v0.1.0 发布流水线」之后的整改批次收口后 push，**一次通过，无失败 job**。
新引入的单测 `FitContentRectTest` / `LinearPipelineTest` 随 `testDebugUnitTest` 全过。

## 任务（Job）总览

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest`（含新增两个测试类）全过 → 上传 debug APK |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Check signing secrets | ✅ success | 探测到 `KEYSTORE_BASE64`（4 个 Secrets 已配） |
| **Signed Release** | ✅ success | 解出 PKCS12 keystore → `assembleRelease`（启用 R8）→ 签名 APK + R8 mapping |
| Publish GitHub Release | ⏭️ skip | **仅 `v*` tag 触发**；本次为 `main` push，跳过属预期 |

## 本轮产出物（Artifacts）

| Artifact | 大小 | 保留 |
|---|---|---|
| `pixelcake-release-d29179e…` | 20.75 MB | 90 天 |
| `pixelcake-debug-d29179e…` | 31.00 MB | 90 天 |
| `pixelcake-mapping-d29179e…` | 2.12 MB | 90 天 |
| `lint-report-d29179e…` | 0.03 MB | 7 天 |

下载：Actions → 本轮 run `34868197276` → 页面底部 Artifacts；或 API `…/actions/runs/34868197276/artifacts`。

## 本批提交

| 提交 | 说明 |
|---|---|
| `7eb348f` | `fix(retouch)`：Beauty 双线性采样改用私有 `inline lerp1`，消除逐像素 lambda 分配（33MP 液化 ≈3300 万次），逐位等价 |
| `1e8eda8` | `fix(editor)`：预览手势按 `ContentScale.Fit` **内容矩形**归一化（修 letterbox 黑边导致的落点系统性偏移 P0）；`pointerInput` 补 `previewSize/contentSize` key；`fitContentRect` 提升为 `internal` 供单测 |
| `f28ccc5` | `ci(release)`：`publish` 附件改用带版本号文件名 `pixelcake-<tag>-release.apk` |
| `d29179e` | `fix(editor)`：状态行结构化收口（审计 M2）—— `ExportSheet` 定义 `EditorStatus`/`StatusKind`、`MainActivity` 全量状态点改结构化、`HomeScreen` 取 `.text`；补 `FitContentRectTest`(A2) / `LinearPipelineTest`(M5)；`build.gradle.kts` 去死配置(M8)；`SkinMaskPostProcess` KDoc 更正 |

改动明细：

1. **状态行结构化（审计 M2）** — 新增 `enum StatusKind { Info, Success, Error }` 与 `data class EditorStatus(kind, text)`，
   由 `ExportSheet` 按**类别**取色，取代此前 `status.startsWith("已导出")` 的「按文案前缀猜」，避免改文案静默改逻辑；
   顺带修掉「失败/取消与普通提示同色」的体验问题（失败现用 `colorScheme.error`）。
2. **预览落点归一化（P0）** — 新增 `fitContentRect(box, image)`，按 `ContentScale.Fit` 反算图片实际绘制矩形，
   手势坐标换算到**内容矩形**内的 `[0..1]`；`Image` 显式写 `contentScale = ContentScale.Fit`。
   修的是「按整块预览 Box 归一化」在横构图照片 + 竖屏预览下的系统性偏移（预览与导出共用坐标，两条路径一起错）。
3. **Beauty 逐像素零分配** — `sampleBilinear` 内的局部 `lerp` lambda 会捕获 `tx`、每像素一次对象分配；
   抽成私有 `inline fun lerp1` 消除分配，数值与旧实现逐位等价。
4. **`build.gradle.kts`（审计 M8）** — 移除无对应源集/依赖的 `testInstrumentationRunner` 死配置；护栏逻辑一律走 JVM 单测（如 `FitContentRectTest`）。
5. **`SkinMaskPostProcess` KDoc 更正** — `smooth3x3` 作用在**上采样之前**的低分辨率网格上，原注释「上采样后」与调用顺序相反。

## 发布流水线（上一轮，保持有效）

- 已发布 **Release `v0.1.0`**（非草稿）：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.1.0>，
  tag 触发 run `34865057998` 5 job 全绿（含 `Publish GitHub Release`），附件 `app-release.apk`。
- 本轮起 `publish` job 的附件命名为 `pixelcake-<tag>-release.apk`（下次打 tag 生效，便于区分版本）。
- 发布方式：`git tag -a vX.Y.Z -m "..." && git push origin vX.Y.Z`（`on: tags: ['v*']` 触发）。

## 🔐 安全边界（重要）

- 入库的 `app/debug.keystore` 是 Android 标准调试密钥，口令（`android`/`androiddebugkey`）为**公开约定、非机密**；
  `.gitignore` 用 `!debug.keystore` 有意反忽略，只为让 debug 签名跨构建稳定。
- ⚠️ **release keystore 绝不能入库**：仅存于 GitHub Secrets，CI 解到 `$RUNNER_TEMP` 临时落盘、job 结束即删。
- 若将来**误提交**真实密钥/口令，仅删文件**不够** —— 必须用 `git filter-repo`（或 BFG）清理**历史**并立刻轮换。

## 历史回归记录

| Run | 提交 | 结论 | 失败点 |
|---|---|---|---|
| `34744787789` | `ca5410b` | ❌ failure | ActionTile 缺 `dp` 导入 + AppShell `togetherTo` 笔误 |
| `34745071202` | `fbc5e49` | ✅ success | 无 |
| `34816082925` | `c3841cd` | ❌ failure | AppShell `transitionSpec` 中调用 `@Composable` 的 `Motion.durationFor` |
| `34816492199` | `ca4ac11` | ✅ success | 无 |
| `34859073004` | `e633ec6` | ✅ success | 无（**首次 Signing Release 成功**） |
| `34865057998` | `18054b8`（tag `v0.1.0`） | ✅ success | 无（5 job，**首次发布 GitHub Release**） |
| `34866770056` | `f28ccc5` | ❌ failure | `EditorScreen.kt:149 Unresolved reference 'EditorStatus'` —— 与并行整改批次**抢跑**：`EditorScreen` 已引用 `EditorStatus`，但其定义所在的 `ExportSheet.kt` 当时尚未提交（同包符号，缺定义即报错）。已在 `d29179e` 补齐定义并收口 |
| `34868197276` | `d29179e` | ✅ success | 无（补齐后全绿，含签名 Release） |

## 后续步骤

1. 真机（一加15）下载 **release 签名 APK** 与 **debug APK** 验证：
   - **release 包**：R8 后 LiteRT/MediaPipe 推理、相机 PTP、ARW 解码是否正常（重点看运行期）；
   - **debug 包**：确认与上一版可**覆盖安装**（不再报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）；
   - 功能面：预览手势落点（横构图照片在竖屏上的涂抹/祛斑是否对准）、ML 自动蒙版、retouch、Beauty、ToneCurve、相机批量。
2. `LinearPipelineTest` 中两条 **M1 刻画测试**（白点裁顶不可恢复 / 逐通道裁顶致高光偏色）**当前断言的是现状**；
   将来修 M1（线性域软压肩）时它们应当变红，届时连实现一起更新。
3. 正式发版：打 `v*` tag → 自动触发 `publish`（附件 `pixelcake-<tag>-release.apk`）。
4. 如有新修改，按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
5. 如遇 lint job 报 `Gradle build daemon disappeared`，先 **rerun-failed-jobs** 重试（`ci_rerun.py <run_id>`）。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 4 job 全绿（含签名 Release），Publish 仅 tag 触发故跳过。*
