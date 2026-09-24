# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-24（本地）
> 关联提交：`04e211dde1ae1a08069324ad1383e561266f482d`（versionName 仍 `0.3.1` —— **本轮未发版**）
> 本轮主题：**调色参数体系批次 1~4（参数 10 → 81 项）**首次过 CI

## 结论：✅ 最终全绿 —— 首跑红（2 条单测），修掉一个真实缺陷后第二次 push 通过

| 阶段 | 运行 | 提交 | 结论 |
|---|---|---|---|
| ① 批次 1~4 全量推送 | run **`35891490851`** | `fce0163` | ❌ failure —— `Build Debug APK` 的 `testDebugUnitTest` **2 条失败**（编译、lint 全过） |
| ② 修 halo 缺项 | run **`35949465017`** | `04e211d` | ✅ **4 job 全绿**（`Publish` 非 tag 跳过） |

> 本轮为 `main` push，**未打 tag、未发新 Release**（用户只要求 push）。线上最新 Release 仍是 `v0.3.1`。

## 任务（Job）总览 — run `35949465017`（最终绿）

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest`（**197 个测试全过**） |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Check signing secrets | ✅ success | 探测到 `KEYSTORE_BASE64` |
| Signed Release | ✅ success | 解 PKCS12 keystore → `assembleRelease`（R8）→ 签名 APK + mapping |
| Publish GitHub Release | ⏭️ skip | 仅 `v*` tag 触发；本次为 `main` push，属预期 |

> 对比首跑：`Check signing secrets` ✅、`Lint` ✅、`Build Debug APK` ❌、`Signed Release`/`Publish` ⏭️ skip
> （`release` 依赖 `build`，单测没过就不出签名包 —— 这条闸门按设计生效）。

## 本轮产出物（Artifacts）— run `35949465017`

| Artifact | 大小 | 保留 |
|---|---|---|
| `pixelcake-release-04e211d…` | 20.78 MB | 90 天 |
| `pixelcake-debug-04e211d…` | 31.09 MB | 90 天 |
| `pixelcake-mapping-04e211d…` | 2.16 MB | 90 天 |
| `lint-report-04e211d…` | 0.03 MB | 7 天 |

## 本轮提交

| 提交 | 说明 |
|---|---|
| `fce0163` | `feat(edit)`：调色参数体系**批次 1~4**（19 文件，`+4493 / −290`）——见下 |
| `04e211d` | `fix(edit)`：`DetailPass` 分带 **halo 少了 `rFine`**（4 文件，`+103 / −34`） |

### 批次 1~4（参数 10 → 81 项）

| 批次 | 成本类型 | 内容 |
|---|---|---|
| 1 + 2 | 纯逐像素 | 影调四区（黑场/阴影/高光/白场）+ 自然饱和度 + 去朦胧近似 + **修 `linear16ToSrgb` 的 `v ≥ 65535` 硬截断**（线性域软压肩，审计项 M1）+ 高光符号统一为 Lightroom 口径（正 = 提亮）+ HSL 混色 24 项 + 彩色分级 14 项 + UI 二级分类（调色/颜色/曲线） |
| 3 | **需坐标** | 暗角（1024 项乘性 LUT、索引 `d²`、四角恒 `d = 1`）+ 颗粒（512² 确定性瓦片 + 长边归一化采样，**不用 `Random`**）。破坏性改动：`PixelProgram` 入口加**必填** `(u, v)` 与 `frameW/frameH`，`runBand` 改按行分片 |
| 4 | **需邻域** | 细节四算子（锐化/降噪/质感/清晰度）作**独立第二遍 pass**（`DetailPass.kt`，零 Android 依赖）；顺序 逐像素 → 精修 → 细节 → 导出 |

配套：`EditEngine` 慢渲染 / 导出耗时日志；新增 5 个测试文件（含测试内**独立**写的朴素全幅参照）；`docs/TONING_DESIGN.md` 规格全文。

## ⭐ 本轮失败复盘（重要，供后人少走弯路）

### ❌ run `35891490851`（`fce0163`）—— 分带路径「halo 少一项」

- **现象**：编译与 lint **全过**，197 个单测里**只有 2 条失败**：
  `DetailPassTest.everySizeMatchesNaiveReference` / `DetailPassTest.wideImageMatchesNaiveReference`
  （都是「与朴素参照逐位比对」的用例）。
- **难点**：Gradle 控制台默认是 **SHORT** 异常格式，只打异常类名 + 行号，
  **看不到** `assertArrayEquals` 的「第几个元素、期望多少、实际多少」；而失败运行没上传测试报告
  ⇒ CI 日志拿不到差值。**本地又没有编译器**，无法复跑。
- **定位方式**：用 Python **逐行复刻** `DetailPass` 与测试内的朴素参照（含 `java.util.Random`
  精确复刻、整数滑窗模糊、逐像素浮点），在本地复现出分歧点：
  - 分歧**只**出现在**分带路径**；
  - 差异行**精确落在每两条带的接缝两侧各一行**（h=133 → 127/128；h=300 → 127/128 与 255/256）；
  - 差值仅 **±1~2 级** —— 典型「画面看起来完全正常、真机绝看不出」的缺陷。
- **根因**：粗层 `b2 = blur(blur(raw, rFine), rCoarse)` 是**复合**核，纵向支撑半径 = `rFine + rCoarse`；
  但窗口只向外扩了 `rMax`（= `rCoarse`）行，**少了 `rFine` 行** ⇒ 接缝那两行的 `b2` 从窗口边界
  clamp 到「被复制出来的邻居行」，而整幅路径那里有真实数据。
- **修法**（`04e211d`）：halo 改为 `rFine + rCoarse`，同步三处 ——
  ① `haloRows()` 的口径；② 分流阈值 `h <= band + 2·halo`；③ `applyBanded()` 的
  `top`/`bot`/`carry` 行数与 `rows >= halo` 判据。
  窗口从 `128 + 2×58` 变为 `128 + 2×65 = 258` 行（33MP 下单个缓冲 ≈ 7.2MB，5 个在世 ≈ 36MB）。
- **顺带改进**：`build.gradle.kts` 加 `tasks.withType<Test> { testLogging { exceptionFormat = FULL } }`
  ⇒ 以后测试失败的**断言差值直接进 CI 日志**，不再需要离线复刻。

> **规律（值得记住）**：凡是「复合核 / 二次采样 / 多次映射」的算子，halo 必须是**各阶段支撑之和**，
> 而不是其中最大的那一个。单看「一次模糊」的算子（如 `retouch/NeutralGray`，halo = radius）
> 会觉得这条天经地义 —— 正是这个直觉在复合情形下失效了。

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
| **`35949465017`** | **`04e211d`（main）** | ✅ **success** | **无（197 测试全过，4 job 绿）** |

## 后续步骤

1. **本批尚未发版**：线上最新仍为 `v0.3.1`。若要发，按规范两步走（先 push main 跑绿 → 再打 tag）；
   注意打 tag 前要**同步抬 `versionName`/`versionCode`**（当前 `0.3.1` / `4`）。
2. 真机（一加15）验收批次 1~4（`docs/TONING_DESIGN.md` §8，第 1~13 条），优先：
   - **批次 3 第 5 条**：预览与导出目视一致（本批唯一新增的不变量）；
   - **批次 3 第 6 条**：暗角中心纹丝不动、四角上下左右对称；
   - **批次 3 第 7 条**：拖任何滑块噪点不抖（颗粒用确定性瓦片，不该「沸腾」）；
   - **批次 4 第 9 条**：锐化晚于磨皮（错了画面仍正常、只是效果变弱，离线测不出）；
   - 批次 1/2：四区不串扰 / 高光找回 / HSL 单通道 / 分级不偏中性灰 / **高光力度是否过猛**。
3. 仍待真机确认的上一批项：拖滑块不跳 / 拖动后不误触 / 拖完立刻返回不崩 / 升级后设置保持。
4. 新修改按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
5. ⚠️ **已知待办**：第一版真实设置迁移落地时**必须同批补 `SettingsMigrationTest`**（当前 v0→v1 为空迁移）。
6. ⚠️ **环境类失败预警**：`Failed to find package 'tools'` = 上游 SDK 变动，改 workflow `packages`，别改代码。
7. ⚠️ **Material3 实验性 API**：用 `Slider` 自定义 `thumb` 等须加 `@OptIn(ExperimentalMaterial3Api::class)`。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮首跑红 → 修 `DetailPass` halo 缺项 → 复跑全绿（4 job，197 测试全过）。*
