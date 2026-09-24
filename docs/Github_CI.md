# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-24（本地）
> 关联提交：`917dd65145907c05c1d573c58a4edcebfad34d20`（**versionName `0.4.1` / versionCode `6` —— 本轮已发版**）
> 本轮主题：**批次 5「对象作用域调色」正式发布 `v0.4.1`**

## 结论：✅ 全绿发版 —— main 跑绿后打 tag，5 job 全过（含 Publish），Release 已发布

| 阶段 | 运行 | ref / 提交 | 结论 |
|---|---|---|---|
| ① main 抬版本号 | run **`35998171813`** | `917dd65`（main） | ✅ **4 job 全绿**（`Publish` 非 tag 跳过） |
| ② tag 触发发布 | run **`36003780194`** | `v0.4.1`（`917dd65`） | ✅ **5 job 全绿**（含 `Publish GitHub Release`） |

> 🚀 **Release 已发布**：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.4.1>
> 附件 `pixelcake-v0.4.1-release.apk`（**29.01 MB**），已供真机（一加15）下载验收。

## 任务（Job）总览 — run `36003780194`（tag run，最终绿）

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest`（**33 测试文件 / 244 个 `@Test` 全过**） |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Check signing secrets | ✅ success | 探测到 `KEYSTORE_BASE64` |
| Signed Release | ✅ success | 解 PKCS12 keystore → `assembleRelease`（R8）→ 签名 APK + mapping |
| Publish GitHub Release | ✅ success | `v*` tag 触发，上传 release APK 并创建 Release（`contents: write`） |

> 与 main run `35998171813` 的唯一差别：后者为非 tag push，`Publish` 按设计 **⏭️ skip**。
> 两步走的**闸门设计生效**：只有「main 已绿」的 commit 才被打了 tag。

## 本轮产出物（Artifacts）— run `36003780194` / `35998171813`

| Artifact | 大小 | 保留 |
|---|---|---|
| `pixelcake-release-917dd65…` | 20.79 MB | 90 天 |
| `pixelcake-debug-917dd65…` | 31.12 MB | 90 天 |
| `pixelcake-mapping-917dd65…` | 2.17 MB | 90 天 |
| `lint-report-917dd65…` | 0.03 MB | 7 天 |

**Release 附件**：`pixelcake-v0.4.1-release.apk`（29.01 MB）。

## 本轮提交

| 提交 | 说明 |
|---|---|
| `917dd65` | `chore(release)`：抬版本 `0.4.0 → 0.4.1`（`versionCode 5 → 6`） |
| ↳ 承载内容 | 批次 5 的功能提交 `6915249` + 修复 `4fd16f1`（本 tag 指向的代码即这两笔） |

### 本 tag 实际发布的内容 = 批次 5「对象作用域调色」（Lightroom 式局部调整）

| 维度 | 内容 |
|---|---|
| 作用域 | **8 个**：人物 / 皮肤 / 面部 / 身体 / 头发 / 衣服 / 配饰 / 背景（+「整图」= 图层栈底） |
| 模型 | **不需要新模型** —— 复用已随包的 `selfie_multiclass_256x256.tflite`（MediaPipe, Apache-2.0）。此前只用其中 2 类合成皮肤蒙版、另 4 类**算完即弃** ⇒ 本次本质是「把已算出的概率接出来」，**推理成本零增长** |
| 层语义 | **层 = 叠加量**（Lightroom 口径）：层的目标值 = 在**整图结果**之上再算一遍 `PixelProgram`；三条渲染入口写**同一个表达式** ⇒「预览所见 = 导出所得」是**结构性事实** |
| 性能 | `blendStack8` **单趟逐层 lerp**：一个像素循环内算 `1 + N` 遍，零额外整幅缓冲、零额外循环，只在最后量化一次 |
| 蒙版 | 必须**网格化**（`ObjectMasks` / `ObjectMask` 共享 `FloatGrid`）；**整幅蒙版 0 条**（33MP 下一条就是 131MB） |
| 派生作用域 | 走**完整链路**（求和 → 阈值羽化 → 平滑），**不是**子蒙版取 `max` |
| 顺序 | ①逐像素调色（整图 + N 层）→ ②人像精修 → ③细节 pass ⇒ **「人脸的暗角」这类错层不存在** |
| 剥离 | 效果 / 细节两段从对象层剥离（双保险：UI 空态 + `withoutWholeImageStages()` 构建期归零） |

配套：新增 `core/edit/ObjectLayers.kt`、`core/ml/ObjectMasks.kt` 与对应 2 个测试文件；新增 `docs/OBJECT_TONE_DESIGN.md`（13 节规格）。

## ⭐ 上一轮失败复盘（保留，供后人少走弯路）

### ❌ run `35993853528`（`6915249`）—— `mapOf` 里的**可空值**污染了整张 map 的类型

- **现象**：`compileDebugKotlin` 红（`Lint` 被 lint→compile 依赖级联拖红），报错 2 条：
  ```
  MlMaskProvider.kt:63:13  Argument type mismatch: actual type is
    'Map<String, Comparable<*>? & Serializable?>', but 'Map<String, Any>?' was expected.
  MlMaskProvider.kt:86:13  （同上）
  ```
- **根因**：`DebugLog.i/e/w/d(tag, msg, kv: Map<String, Any>?)` 的 **kv 值类型是非空 `Any`**。
  调用处写的是 `mapOf("key" to key, "grid" to mask.gridSide, "accel" to accelerator)`，
  而 `accelerator` 的声明是 **`val accelerator: String?`**（`model?.acceleratorName`，模型未加载时为 `null`）。
  ⇒ 三个 value（`String` / `Int` / `String?`）的公共超类型是**可空的** `Comparable<*>? & Serializable?`；
  `Map` 在 value 上虽然是 `out V` 协变，但**可空类型不是 `Any` 的子类型** ⇒ 不匹配。
- **定位方式（无本地编译器）**：读 CI 报错行 → 读 `DebugLog.kt` 确认形参为 `Map<String, Any>?` →
  全仓扫描所有 `DebugLog.*(… mapOf(…))` 调用，确认**只有这 2 处**有可空 value。
- **修法**（`4fd16f1`）：
  ```kotlin
  mapOf<String, Any>("key" to key, "grid" to mask.gridSide, "accel" to (accelerator ?: "none"))
  ```
  显式 `mapOf<String, Any>` 的作用是：**以后再往这张 map 里塞可空值，会在调用处直接报错**。
- **顺带排除的 3 个「疑似风险」**（推前静态核对，均已确认无问题）：
  1. ⚠️ **`compileDebugKotlin` 挂 ⇒ `compileDebugUnitTestKotlin` 根本没跑** —— 两个新测试文件从未被编译过，
     故推前逐项核对了测试侧风险；
  2. **`assertEquals(1f, actual, 1e-6f)`** —— **假警报**：仓库里已绿的旧测试大量同写法
     （`EditHistoryTest` 9 处、`MaskCombineTest` 5 处…）；
  3. **`enum.entries` / `internal inline` 跨源集** —— `ObjectScope`/`ScopePart` 均为 `enum class`（Kotlin 2.2.21）；
     `src/test` 是 `src/main` 的 **friend module**，可访问 `internal`（`FitContentRectTest` 已是先例）。

> **规律（值得记住）**：**日志/埋点里的 `mapOf` 最容易踩这个坑** —— value 常来自「可能为 null 的展示字段」。
> 两条护栏：① 一律 `?: 兜底值`；② 显式写 `mapOf<String, Any>(...)` 让编译器在调用点就拦下来。

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
| **`36003780194`** | **`v0.4.1`（tag）** | ✅ **success** | **无（5 job 全绿含 Publish，Release 已发布）** |

## 后续步骤

1. **真机（一加15）验收 `v0.4.1`**（`docs/OBJECT_TONE_DESIGN.md` §8 及其「待真机验收」），优先：
   - ① **8 个作用域的蒙版位置对不对**（最高优先 —— 人/肤/面/身/发/衣/饰/背景 是否各盖各的）；
   - ② 多层合成顺序与「层 = 叠加量」的手感；
   - ③ **预览 vs 导出**一致；
   - ④ 逐像素代价（3 层时的帧率 / `slow preview render` 日志频率）；
   - ⑤ 降级：模型不可用时 chip 是否禁用并给出提示句。
2. 仍待真机确认的 `v0.4.0`（批次 1~4）项：预览==导出 / 暗角中心不动 / 拖滑块噪点不抖 / 锐化晚于磨皮 / 高光力度。
3. 下一版若要发：先抬 `versionName`/`versionCode`（当前 `0.4.1` / `6`）→ push main 跑绿 → 再打 tag，两步走。
4. 新修改按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
5. ⚠️ **已知待办**：第一版真实设置迁移落地时**必须同批补 `SettingsMigrationTest`**（当前 v0→v1 为空迁移）。
6. ⚠️ **环境类失败预警**：`Failed to find package 'tools'` = 上游 SDK 变动，改 workflow `packages`，别改代码。
7. ⚠️ **Material3 实验性 API**：用 `Slider` 自定义 `thumb` 等须加 `@OptIn(ExperimentalMaterial3Api::class)`。
8. ⚠️ **日志埋点传可空值**：`DebugLog` 的 kv 是 `Map<String, Any>`（值**非空**）。往 `mapOf` 里塞
   `String?` 这类可空值会让整张 map 被推断成可空交集类型而编译失败 —— 一律 `?: 兜底`，并建议显式写
   `mapOf<String, Any>(...)`。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 main 跑绿（4 job）→ 打 `v0.4.1` tag → 5 job 全绿含 Publish → Release 已发布。*
