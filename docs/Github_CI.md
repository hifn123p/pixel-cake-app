# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-10-04（本地）
> 关联提交：`b582da049ed982754d7cbb7e68833b3fce3f25d9`（**versionName `0.4.5` / versionCode `10` —— 本轮已发版**）
> 本轮主题：**细部位美容 + 预设体系扩容到 44 套（分类）+ 追色 mono 去色 + 曲线/直方图工具，发布 `v0.4.5`**

## 结论：✅ 四轮 main 后全绿发版 —— main 连续三轮红（均为真实编译错，逐轮收敛）→ 第四轮绿 → 打 tag，5 job 全过（含 Publish），Release 已发布

| 阶段 | 运行 | ref / 提交 | 结论 |
|---|---|---|---|
| ① main 推功能批次 + 抬版本号 | run **`37203167123`** | `60af310`（main） | ❌ **failure**（`compileDebugKotlin` 6 错：Presets ×3 + ParamPanel ×3） |
| ② main 推第 1 轮修复 | run **`37203592131`** | `54c2898`（main） | ❌ failure（2 错：`ParamPanel:75` 类型推断 + `CurveCanvas.cubicBezier` 不存在） |
| ③ main 推第 2 轮修复 | run **`37203885993`** | `3e2d1b6`（main） | ❌ failure（`CurveCanvas:123-127`：`Triple` 只给 2 参） |
| ④ main 推第 3 轮修复 | run **`37204072934`** | `b582da0`（main） | ✅ **4 job 全绿**（`Publish` 非 tag 跳过） |
| ⑤ tag 触发发布 | run **`37204595671`** | `v0.4.5`（`b582da0`） | ✅ **5 job 全绿**（含 `Publish GitHub Release`） |

> 🚀 **Release 已发布**：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.4.5>
> 附件 `pixelcake-v0.4.5-release.apk`（**29.03 MB**），已供真机（一加15）下载验收。

> ⚠️ **本轮是「main 连红三轮」的最坏情况**：新功能批次里有**从未编译过**的新文件（`curves/` 包 + `Histogram.kt`），
> 它们**首次进编译**，逐层暴露错误。共 4 个 run 才收敛。这是「新文件多 + 本地无编译器」的正常代价，不是流程故障。

## 任务（Job）总览 — run `37204595671`（tag run，最终绿）

| Job | 结论 | 说明 |
|---|---|---|
| Check signing secrets | ✅ success | 探测到 `KEYSTORE_BASE64` |
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest` 全过 |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Signed Release | ✅ success | 解 PKCS12 keystore → `assembleRelease`（R8）→ 签名 APK + mapping |
| Publish GitHub Release | ✅ success | `v*` tag 触发，上传 release APK 并创建 Release（`contents: write`） |

> 与 main run `37204072934` 的唯一差别：后者为非 tag push，`Publish` 按设计 **⏭️ skip**。
> 两步走的**闸门设计生效**：只有「main 已绿」的 commit 才被打了 tag。

## ❌ 本轮 4 个 run 的完整故障链（逐轮收敛）

### 第 1 轮红 — run `37203167123`（`60af310`，6 错）

```
e: .../core/edit/preset/Presets.kt:109:90   No parameter with name 'hue' found.
e: .../core/edit/preset/Presets.kt:303:130  No parameter with name 'blues' found.
e: .../core/edit/preset/Presets.kt:318:148  No parameter with name 'blues' found.
e: .../ui/editor/ParamPanel.kt:54:5   Initializer type mismatch:
                                      expected 'List<Pair<String, String>>', actual 'List<Serializable>'.
e: .../ui/editor/ParamPanel.kt:54:63  Variable 'COLOR_TRANSFER_LABELS' must be initialized.
e: .../ui/editor/ParamPanel.kt:779:9  Functions which invoke @Composable functions must be marked with the @Composable annotation
e: .../ui/editor/ParamPanel.kt:785:9  @Composable invocations can only happen from the context of a @Composable function
> Task :app:compileDebugKotlin FAILED
```

**根因（3 类）**：

1. **`EditParams` 没有 `hue` / `blues` 字段** —— 写预设时凭「应该有」注入了两个不存在的具名参数。
   `EditParams` 实测 **37 个字段**（`exposureEv`…`texture`），色相/蓝色调整的正确入口是 `hsl: HslMix`。
   修：`cyber` → `hsl = HslMix().withHue(6, 0.1f).withHue(5, 0.1f)`（品红 6 / 蓝 5）；
   `landscape_snow` → `hsl = HslMix().withSat(5, 0.15f).withLum(5, 0.1f)`；
   `landscape_coast` → `hsl = HslMix().withSat(5, 0.2f).withLum(5, 0.05f)`。并补 `import ...edit.HslMix`。
2. **Kotlin 顶层属性前向引用是编译错**（不是「能用」）—— `COLOR_TRANSFER_OPTIONS` 声明在
   `COLOR_TRANSFER_LABELS` **之前**，而它直接读后者 ⇒ `must be initialized`，
   且 `+` 的类型被推断成 `List<Serializable>`。修：把 `LABELS` 挪到 `OPTIONS` **之前**。
3. **`@Composable` 局部函数必须自己标 `@Composable`** —— `slider()` 与 7 个 `*Sliders()` 局部函数
   内部调用了 `ParamSlider`（`@Composable`），自身却未标注。共 **8 处**一并补上。

### 第 2 轮红 — run `37203592131`（`54c2898`，2 错）

```
e: .../ui/editor/ParamPanel.kt:75:5  Initializer type mismatch:
                                     expected 'List<Pair<String, String>>', actual 'List<Serializable>'.
e: .../ui/editor/curves/CurveCanvas.kt:106:14  Unresolved reference 'cubicBezier'.
e: .../ui/editor/curves/CurveCanvas.kt:111:14  Unresolved reference 'cubicBezier'.
e: .../ui/editor/curves/CurveCanvas.kt:119-121  Cannot infer type / No value passed for parameter 'third'.
```

**根因（2 类）**：

1. `ParamPanel:75` —— 声明顺序改了，但 `listOf("none" to "无") + REF_IDS.map {...}` 的 `+`
   仍把公共超类型推到 **`Serializable`**。修：显式写 `listOf<Pair<String, String>>(...)`，
   并把 elvis 结果加括号（`it to (LABELS[it] ?: it)`）防歧义。
2. **Compose `Path` 没有 `cubicBezier`** —— 三次贝塞尔真名是 `cubicTo(x1,y1,x2,y2,x3,y3)`（6 浮点）。
   原代码只给 5 个实参 ⇒ `No value passed for parameter 'third'` 并连带一串 `Cannot infer type`。
   修：两段改 `path.cubicTo(...)` 并补齐终点。

### 第 3 轮红 — run `37203885993`（`3e2d1b6`，仅 `CurveCanvas`）

```
e: .../ui/editor/curves/CurveCanvas.kt:123:9   Cannot infer type for type parameter 'T'. Specify it explicitly.
e: .../ui/editor/curves/CurveCanvas.kt:124:38  No value passed for parameter 'third'.
e: .../ui/editor/curves/CurveCanvas.kt:125:36  No value passed for parameter 'third'.
e: .../ui/editor/curves/CurveCanvas.kt:126:38  No value passed for parameter 'third'.
```

**根因**：锚点手柄写成 `Triple(AnchorType.Black, blackY)` —— **`Triple` 是三参构造**，只给 2 个实参
必然报 `No value passed for parameter 'third'`，并让 `listOf` 的 `T` / `C` 无法推断。
修：这是 **2 元组**，改用 **`Pair(AnchorType.Xxx, xxxY)`**，`forEach { (anchor, y) -> }` 解构不变。

### 第 4 轮绿 — run `37204072934`（`b582da0`）

`Build Debug APK` / `Lint` / `Check signing secrets` / `Signed Release` **4 job 全过**。
`compileDebugUnitTestKotlin` 随之首跑，**测试全过** ⇒ 断言与实现一致。

## 本轮产出物（Artifacts）— run `37204595671` / `37204072934`

| Artifact | 保留 |
|---|---|
| `pixelcake-release-b582da0…` | 90 天 |
| `pixelcake-debug-b582da0…` | 90 天 |
| `pixelcake-mapping-b582da0…` | 90 天 |
| `lint-report-b582da0…` | 7 天 |

**Release 附件**：`pixelcake-v0.4.5-release.apk`（29.03 MB）。

## 本轮改动：细部位美容 / 44 套分类预设 / 追色 mono / 曲线直方图工具

| 文件 | 变更 |
|---|---|
| `core/edit/RetouchState.kt` | 新增 `BodyPartBeautyParams`（13 字段）+ `RetouchSwitches`（7 开关，唇/身/腿/手默认关）；`BeautyParams` 加 `bodyParts`/`switches` 及 13 个新字段（共 18） |
| `core/edit/preset/Presets.kt` | 预设扩到 **44 套** + 新增 `PresetCategory`（风格 12 / 人像 9 / 风光 12 / 美食 5 / 黑白 6）；`Preset` 加**必填** `category` |
| `core/edit/retouch/ColorTransfer.kt` | 新增 `warm`/`cool`/`bw` 参考风格；`Ref` 加 `mono` 标志；`bw` 走**单通道亮度 z-score** 迁移 |
| `core/edit/Histogram.kt` | **新增**：纯函数直方图（零 Android 依赖），`compute`/`computeRgb`/`normalize`/`meanShift`/`clippedMedian` |
| `core/edit/ToneCurve.kt` | 补 `anchorX(Anchor)` + `Anchor` 枚举（核心层不依赖 UI 包） |
| `ui/components/ParamSlider.kt` | 新增 `enabled` 形参；禁用态显式降透明度（thumb `.alpha(0.38f)` + 三条 disabled 颜色） |
| `ui/editor/ParamPanel.kt` | 新增 `BeautyPartPanel`（部位分页 `BodyPartTab` / `BODY_PART_TABS` / `ALL_PARTS_ID`）；追色选项由 `ColorTransfer.REF_IDS` 动态生成 |
| `ui/editor/curves/*.kt` | **新增 3 文件（备用实现，暂未接线）**：`CurveCanvas`（可交互曲线画布）/ `CurvePanel` / `CurveDisplay` |
| `app/build.gradle.kts` | 抬版本 `0.4.4 → 0.4.5`（`versionCode 9 → 10`） |

> ℹ️ `ui/editor/curves/` 三个文件目前**无任何调用点（dead code）** —— 真正在用的曲线 UI 仍是
> `ParamPanel` 内的滑块版 `CurveTab`。它们**只要放在 `app/src` 下就必须能编译**，本轮 4 个错误里有 3 个出在这里。

### 本轮提交

| 提交 | 说明 |
|---|---|
| `f639183` | `feat(edit)`：细部位美容 + 44 套分类预设 + 追色 mono + 曲线/直方图工具（12 文件，`+1270 / −34`） |
| `60af310` | `chore(release)`：抬版本 `0.4.4 → 0.4.5`（`versionCode 9 → 10`） |
| `54c2898` | `fix(edit)`：修第 1 轮 6 处编译错（2 文件，`+32 / −14`） |
| `3e2d1b6` | `fix(edit)`：修第 2 轮 2 处编译错（2 文件，`+8 / −3`） |
| `b582da0` | `fix(edit)`：修第 3 轮 `Triple` 2 参调用（1 文件，`+7 / −3`） |

## ⭐ 复盘（供后人少走弯路）

1. **`Triple` 必 3 参、`Pair` 必 2 参 —— 别凭手感写元组**。`Triple(a, b)` 的报错是
   `No value passed for parameter 'third'`，还会**连带**把 `listOf(...)` 的类型推断成
   `Cannot infer type for type parameter 'T'`，看起来像两个无关错误，其实是同一处。
2. **Compose `Path` 的贝塞尔 API 是 `cubicTo(x1,y1,x2,y2,x3,y3)`（6 浮点）**，**没有** `cubicBezier`。
   少一个终点实参的报错同样会伪装成「类型推断失败」。
3. **顶层 `val` 前向引用是编译错**（`Variable 'X' must be initialized`），不是警告。
   且 `listOf(...) + listOther` 的公共超类型容易被推到 `Serializable` ⇒ **显式写 `listOf<T>(...)`**。
4. **`@Composable` 局部函数内调 `@Composable` ⇒ 自身必须标 `@Composable`**（本次 8 处）。
5. **「新文件首次进编译」是最贵的一轮**：`curves/` 包 + `Histogram.kt` 从未编译过，
   即使它们**是 dead code**，只要在 `app/src` 下就必须能编译 —— 别以为「没接线就能随便写」。
   本轮 4 个 run 中有 3 个错误来自这些文件。
6. **本地无编译器时的静态闸门**（本轮实测有效）：
   - **具名参数合法性扫描**：收集所有 `class X(...)` 的主构造字段名，再遍历所有 `X(...)` 调用点
     检查 `name =` 是否在字段表内。注意**必须先剥离注释/字符串**（否则 KDoc 里的示例会被算进去 ⇒ 假阳性）。
   - **元组实参个数扫描**：`Pair` 必 2、`Triple` 必 3。
   - **惯用类缺 import 扫描**：Compose/Android 常用类（`Modifier`/`Column`/`Path`/`Size`/`dp`…）
     出现在正文但不在 import 表 ⇒ 报警。
7. **一处编译错 = Build 与 Lint 两个 job 同时红**，且在**预编译步**就失败。
   判据：**真错** = 有 `e: file:///...kt:行:列`；**infra 抖动** = Build 绿而 Lint 挂、日志只有 `hs_err_pid*.log`。
8. **`compileDebugKotlin` 挂 ⇒ `compileDebugUnitTestKotlin` 根本没跑** —— main 编译错修完后，
   测试侧可能还埋着下一轮错误。本轮测试侧一次通过。

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
| `37193562293` | `v0.4.4`（tag） | ✅ success | 无（5 job 全绿含 Publish，Release 已发布） |
| `37203167123` | `60af310`（main） | ❌ failure | `Presets.kt:109/303/318` 非法具名参数 `hue`/`blues`；`ParamPanel.kt:54` 顶层属性前向引用；`ParamPanel.kt:779/785` 局部函数缺 `@Composable` |
| `37203592131` | `54c2898`（main） | ❌ failure | `ParamPanel.kt:75` `List<Serializable>` 类型推断；`CurveCanvas.kt:106/111` `Path.cubicBezier` 不存在 |
| `37203885993` | `3e2d1b6`（main） | ❌ failure | `CurveCanvas.kt:123-127` `Triple` 只给 2 参 ⇒ `No value passed for parameter 'third'` |
| `37204072934` | `b582da0`（main） | ✅ success | 无（修复后 4 job 绿） |
| **`37204595671`** | **`v0.4.5`（tag）** | ✅ **success** | **无（5 job 全绿含 Publish，Release 已发布）** |

## 后续步骤

1. **真机（一加15）验收 `v0.4.5`**，本批重点看：
   - **细部位美容**：头/脸/眼/唇/身/腿/手各部位分页切换是否顺、默认仅面部开启是否符合预期；
   - **预设**：44 套 × 5 分类的 chip 行能否正确筛选、`none` 是否仍在首位；
   - **追色**：新增 `暖调 / 冷调 / 黑白` 三格是否出现；**黑白**是否为真去色（R=G=B）；
   - **禁用态滑块**：`ParamSlider(enabled=false)` 的降透明度是否可见；
   - **两种主题（浅 / 深）各看一次** —— 历史教训：深浅相反才现形的 bug（`LocalContentColor`）必须两主题都验。
2. 仍待真机确认的历史项：第六轮面板文字（浅色主题）、第七轮拖参数「只有完整帧 + 每帧都在动」
   + `render done` 的 `ms` 报数、第四/五轮已发布项、批次 5 的 6 条（`docs/OBJECT_TONE_DESIGN.md` §12.2）、
   `TONE_ZONE_GAIN=0.35` 是否过猛、批次 1~4 预览↔导出一致性、锐化晚于磨皮、A7C2 直连、人脸锚点。
3. **`docs/CURVE_HISTOGRAM_DESIGN.md` 尚未创建** —— `Histogram.kt` 的 KDoc 已引用它。下次接线
   `curves/` 包时一并补上设计文档（或修正 KDoc 引用）。
4. 下一版若要发：先抬 `versionName`/`versionCode`（当前 `0.4.5` / `10`）→ push main 跑绿 → 再打 tag，两步走。
5. 新修改按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
6. ⚠️ **已知待办**：第一版真实设置迁移落地时**必须同批补 `SettingsMigrationTest`**（当前 v0→v1 为空迁移）。
7. ⚠️ **环境类失败预警**：`Failed to find package 'tools'` = 上游 SDK 变动，改 workflow `packages`，别改代码。
8. ⚠️ **Material3 实验性 API**：用 `Slider` 自定义 `thumb` 等须加 `@OptIn(ExperimentalMaterial3Api::class)`。
9. ⚠️ **日志埋点传可空值**：`DebugLog` 的 kv 是 `Map<String, Any>`（值**非空**）—— 一律 `?: 兜底`。
10. ⚠️ **M3 控件实参要核签名**：`FilterChip` 无 `contentPadding`；`Modifier.size` 要 `import foundation.layout.size`。
11. ⚠️ **`Crossfade` / `AnimatedContent` 的内容必须是单一节点**；**`selectable` / `clickable` / `toggleable`
    必须显式传 `indication = null`**；**自定义主题换 `colorScheme` 时必须同时补 `LocalContentColor`**。
12. ⚠️ **执行 git commit 时不要在 `-m` 里用反引号**：bash 会把它当命令替换执行。改用 `git commit -F <文件>`。
13. ℹ️ **网络环境**：本机 hosts 把 `github.com` 指向 `127.0.0.1`（黑洞），直连 22 端口失败；
    push 需走 `ssh.github.com:443`（`GIT_SSH_COMMAND="ssh -p 443 -o HostName=ssh.github.com"`）。
    **不改仓库/全局配置**，只在单条 push 命令上临时覆盖。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 main 连红三轮（`Triple` 2 参 / `Path.cubicBezier` / 非法具名参数与前向引用）→ 逐轮修复 → 第四轮 main 绿（4 job）→ 打 `v0.4.5` tag → 5 job 全绿含 Publish → Release 已发布。*
