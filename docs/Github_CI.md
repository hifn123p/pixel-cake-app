# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-24（本地）
> 关联提交：`4fd16f11fdf952e1dc5b821b271a1a3eb4771864`（versionName 仍 `0.4.0` / code `5` —— **本轮未发版**）
> 本轮主题：**批次 5 —— 对象作用域调色（Lightroom 式局部调整）**首次过 CI

## 结论：✅ 最终全绿 —— 首跑红（main 编译 2 处），修一处类型推断后第二次 push 通过

| 阶段 | 运行 | 提交 | 结论 |
|---|---|---|---|
| ① 批次 5 全量推送 | run **`35993853528`** | `6915249` | ❌ failure —— `compileDebugKotlin` **2 处**类型不匹配（`MlMaskProvider.kt:63/86`） |
| ② 修 `DebugLog` kv 可空值 | run **`35994832403`** | `4fd16f1` | ✅ **4 job 全绿**（`Publish` 非 tag 跳过） |

> 本轮为 `main` push，**未打 tag、未发新 Release**。线上最新 Release 仍是 `v0.4.0`。

## 任务（Job）总览 — run `35994832403`（最终绿）

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest`（**33 测试文件 / 244 个 `@Test` 全过**） |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Check signing secrets | ✅ success | 探测到 `KEYSTORE_BASE64` |
| Signed Release | ✅ success | 解 PKCS12 keystore → `assembleRelease`（R8）→ 签名 APK + mapping |
| Publish GitHub Release | ⏭️ skip | 仅 `v*` tag 触发；本次为 `main` push，属预期 |

> 对比首跑：`Check signing secrets` ✅、`Build Debug APK` ❌、`Lint` ❌（lint 依赖 compile，被级联拖红）、
> `Signed Release`/`Publish` ⏭️ skip。

## 本轮产出物（Artifacts）— run `35994832403`

| Artifact | 大小 | 保留 |
|---|---|---|
| `pixelcake-release-4fd16f1…` | 20.79 MB | 90 天 |
| `pixelcake-debug-4fd16f1…` | 31.12 MB | 90 天 |
| `pixelcake-mapping-4fd16f1…` | 2.17 MB | 90 天 |
| `lint-report-4fd16f1…` | 0.03 MB | 7 天 |

## 本轮提交

| 提交 | 说明 |
|---|---|
| `6915249` | `feat(edit)`：**批次 5 —— 对象作用域调色**（18 文件，`+2634 / −82`，含 5 新增） |
| `4fd16f1` | `fix(ml)`：`MlMaskProvider` 两处 `DebugLog` kv 传了**可空** `accelerator`（1 文件，`+2 / −2`） |

### 批次 5（对象作用域调色）

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

## ⭐ 本轮失败复盘（重要，供后人少走弯路）

### ❌ run `35993853528`（`6915249`）—— `mapOf` 里的**可空值**污染了整张 map 的类型

- **现象**：`compileDebugKotlin` 红（`Lint` 被 lint→compile 依赖级联拖红），报错 2 条：
  ```
  MlMaskProvider.kt:63:13  Argument type mismatch: actual type is
    'Map<String, Comparable<*>? & Serializable?>', but 'Map<String, Any>?' was expected.
  MlMaskProvider.kt:86:13  （同上）
  ```
- **根因**：`DebugLog.i/e/w/d(tag, msg, kv: Map<String, Any>?)` 的 **kv 值类型是非空 `Any`**。
  调用处写的是
  ```kotlin
  mapOf("key" to key, "grid" to mask.gridSide, "accel" to accelerator)
  ```
  而 `accelerator` 的声明是 **`val accelerator: String?`**（`model?.acceleratorName`，模型未加载时为 `null`）。
  ⇒ 三个 value（`String` / `Int` / `String?`）的公共超类型是 **可空的** `Comparable<*>? & Serializable?`；
  `Map` 在 value 上虽然是 `out V` 协变，但**可空类型不是 `Any` 的子类型** ⇒ 不匹配。
  ⚠️ 关键教训：Kotlin 会把**可空值**带进泛型实参推断，而 `Map` 的协变**救不了可空性**。
- **定位方式（无本地编译器）**：读 CI 报错行 → 打开 `DebugLog.kt` 确认形参是 `Map<String, Any>?` →
  全仓扫描所有 `DebugLog.*(… mapOf(…))` 调用，确认**只有这 2 处**有可空 value
  （其余调用都遵循本仓约定用 `?:` 兜底，如 `(t.message ?: t.javaClass.simpleName)`）。
- **修法**：按既有约定兜底 + 显式标注类型参数：
  ```kotlin
  mapOf<String, Any>("key" to key, "grid" to mask.gridSide, "accel" to (accelerator ?: "none"))
  ```
  显式 `mapOf<String, Any>` 的作用是：**以后再往这张 map 里塞可空值，会在调用处直接报错**，而不是悄悄推断成可空交集类型。
- **顺带排除的 3 个「疑似风险」**（推前静态核对，均已确认无问题）：
  1. ⚠️ **`compileDebugKotlin` 挂 ⇒ `compileDebugUnitTestKotlin` 根本没跑** —— 两个新测试文件从未被编译过。
     故推前逐项核对了测试侧风险；
  2. **`assertEquals(1f, actual, 1e-6f)`（JUnit 没有 float 重载？）** —— **假警报**：
     仓库里**已绿的旧测试**大量使用同一写法（`EditHistoryTest` 9 处、`MaskCombineTest` 5 处、
     `RetouchScaleTest`、`MlSkinMaskTest`、`SkinMaskPostProcessTest`…），而它们在 run `35949465017` 里全过 ⇒ 解析没问题；
  3. **`enum.entries` / `internal inline` 跨源集** —— `.entries` 要求 `enum class`
     （`ObjectScope`、`ScopePart` 均已确认是 `enum class`，Kotlin **2.2.21** 支持）；
     `src/test` 是 `src/main` 的 **friend module**，可访问 `internal`（`internal inline fun blendStack8`、
     `internal fun clamp8`、`EditParams.withoutWholeImageStages/isNeutral` 全部在位），本仓 `FitContentRectTest` 已是先例。

> **规律（值得记住）**：**日志/埋点里的 `mapOf` 最容易踩这个坑** —— 因为日志的 value 常常来自
> 「可能为 null 的展示字段」（加速器名、错误消息、可选尺寸…）。
> 两条护栏：① 一律用 `?: 兜底值`；② 显式写 `mapOf<String, Any>(...)` 让编译器在调用点就拦下来。
> 另外，**`kv: Map<String, Any>?` 这种「值非空」的签名本身就比 `Map<String, Any?>` 更难用** ——
> 若将来同类问题反复出现，可考虑把 `DebugLog` 的 kv 放宽为 `Map<String, Any?>`（本文档仅记录，未改动）。

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
| **`35994832403`** | **`4fd16f1`（main）** | ✅ **success** | **无（33 文件 / 244 测试全过，4 job 绿）** |

## 后续步骤

1. **本批尚未发版**：线上最新仍为 `v0.4.0`。若要发，按规范两步走（先 push main 跑绿 → 再打 tag）；
   注意打 tag 前要**同步抬 `versionName`/`versionCode`**（当前 `0.4.0` / `5`）。
2. 真机（一加15）验收 **批次 5**（`docs/OBJECT_TONE_DESIGN.md` §8 及其「待真机验收」），优先：
   - ① **8 个作用域的蒙版位置对不对**（最高优先 —— 人/肤/面/身/发/衣/饰/背景 是否各盖各的）；
   - ② 多层合成顺序与「层 = 叠加量」的手感；
   - ③ **预览 vs 导出**一致；
   - ④ 逐像素代价（3 层时的帧率 / `slow preview render` 日志频率）；
   - ⑤ 降级：模型不可用时 chip 是否禁用并给出提示句。
3. 仍待真机确认的上一批项（`v0.4.0`，批次 1~4）：预览==导出 / 暗角中心不动 / 拖滑块噪点不抖 / 锐化晚于磨皮 / 高光力度。
4. 新修改按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
5. ⚠️ **已知待办**：第一版真实设置迁移落地时**必须同批补 `SettingsMigrationTest`**（当前 v0→v1 为空迁移）。
6. ⚠️ **环境类失败预警**：`Failed to find package 'tools'` = 上游 SDK 变动，改 workflow `packages`，别改代码。
7. ⚠️ **Material3 实验性 API**：用 `Slider` 自定义 `thumb` 等须加 `@OptIn(ExperimentalMaterial3Api::class)`。
8. ⚠️ **日志埋点传可空值**：`DebugLog` 的 kv 是 `Map<String, Any>`（值**非空**）。往 `mapOf` 里塞
   `String?` 这类可空值会让整张 map 被推断成可空交集类型而编译失败 —— 一律 `?: 兜底`，并建议显式写
   `mapOf<String, Any>(...)`。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮首跑红（`MlMaskProvider` 两处 mapOf 可空值）→ 修后复跑全绿（4 job，33 文件 / 244 测试全过）。*
