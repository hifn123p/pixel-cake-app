---
title: 像素蛋糕 App — 修改清单（代码审查 2026-09-09）
status: pending-review
created: 2026-09-09
base_commit: c8ea422
scope: docs/DEV_PLAN.md v3.0 + 全部 52 个入库文件 + wb-issues 16 张卡
---

# 修改清单（P1b 中期审查）

> 本清单只记录「为什么改」「改哪里」「怎么验」，**不含实施**。
> 状态列：`待确认` = 已核对定位、等待批准动手；`已核对` = 结论与行号已用证据坐实。

## 0. 复审纠正（上一轮结论有误，必须先记下）

**上一轮我判断「DEV_PLAN §3.2 说 ARW 内嵌预览是 7008×4672 属事实错误」——这个判断是错的，计划是对的，错的是代码。**

用 Python 解析本地样本 `example/DSC04926.ARW`（68MB）得到 IFD 链实况：

| 位置 | 0x0201/0x0202 指向 | 尺寸 | 体积 |
|---|---|---|---|
| IFD0 | 192674 / 196313 | **1616×1080** | 192 KB |
| IFD0.next = IFD@43670 | 43988 / 7694 | 160×120 | 8 KB |
| IFD@43670.next = **IFD@138222** | 389120 / 2008341 | **7008×4672** | **1.9 MB** |
| SubIFD@138530 | — | 7040×4688 RAW (compression=1) | 传感器数据 |

结论：**A7C II 的 ARW 里确实有全分辨率内嵌 JPEG，只有 1.9MB**，但它在 IFD 链的第 3 个 IFD 上。
`ArwContainer.previewJpegRange()` 只查 IFD0 与 SubIFD（0x014A），**从不沿 IFD 链的 next 指针走**，且首个命中即返回 → 永远只拿到 1616×1080 那张。

这条纠正把 F04 从「文档笔误」升级为「实现缺陷」，也是本轮性价比最高的一个修复。

## 1. P0 — 阻断级（不改则 P1b 的立论不成立 / 必然泄漏）

### F01 LibRaw 输出被降级成 8-bit 烘焙图，"16-bit 线性"未兑现
- **原因**：DEV_PLAN §3.2 论证「ARW 修图必须走全量解码」的唯一理由是「内嵌预览白平衡与色调曲线已固化、没有编辑宽容度」。而当前实现把相机白平衡、sRGB 曲线、自动亮度**全部烘焙进 8-bit 输出**，宽容度同样为零——自己的实现推翻了自己的立论。`no_auto_bright=0` 还让曝光基准随图内容漂移，同一组参数在不同照片上不可复现。
- **位置**：`app/src/main/cpp/raw_bridge.cpp:35-39`（`use_camera_wb=1` / `output_color=1` / `output_bps=8` / `no_auto_bright=0` / `user_qual=3`）
- **连带**：`core/decode/RawImage.kt`（需承载 16-bit）、`arw/ArwFullDecoder.kt:78-95`（toBitmap 目前假定 8-bit RGBA）、`core/edit/ColorMath.kt` 整条 8-bit 管线
- **方案**：`output_bps=16` + `no_auto_bright=1` + 线性 gamma（`gamm={1,1}`）+ 保留 `use_camera_wb` 作为**初始 WB 参数回传 Kotlin**（而非烘焙进像素）；Kotlin 侧管线按 16-bit 线性输入重写，白平衡/曝光在线性域可逆
- **验证**：同一张 ARW，曝光 -2EV → +2EV 往返后高光不应被截断；日志打印 `output_bps` 与 WB 乘子
- **状态**：已核对

### F02 `dcraw_make_mem_image()` 返回值未释放，每解一张 ARW 泄漏 ~98MB native 内存
- **原因**：`dcraw_make_mem_image()` 返回的 `libraw_processed_image_t*` 必须用 `LibRaw::dcraw_clear_mem()` 释放。代码用的是 `free_image()`，它只释放 LibRaw 内部的 `imgdata.image`，**不释放返回的那块 malloc**。7008×4672×3 ≈ 98MB / 次，反复导出必然 native OOM。
- **证据**：已核对 LibRaw master `libraw/libraw.h`，存在 `static void dcraw_clear_mem(libraw_processed_image_t *);`，与 `void free_image();` 是两个不同职责的函数
- **位置**：`app/src/main/cpp/raw_bridge.cpp:60, 69, 90, 95, 144`（五处 `free_image()` 调用全部需要改判）
- **方案**：所有退出路径改为 `LibRaw::dcraw_clear_mem(image)` 释放处理结果；内部缓冲交由 `rawProcessor` 析构（`recycle()`）处理，不需手工 `free_image()`
- **连带文档**：`docs/DEV_PLAN.md §0` 备注与 wb-issues `rNJffz` 描述里都写着「free_image() 替代 dcraw_free()」，**这条结论是错的，必须一起更正**（见 D01/D08）
- **验证**：连续导出同一张 ARW 10 次，`Debug.getNativeHeapAllocatedSize()` 不应单调增长
- **状态**：已核对

### F03 编辑预览与导出走两条不同底图，WYSIWYG 断裂
- **原因**：`decodeToProxy` 对 ARW 恒走内嵌预览（相机烘焙 JPEG），`decodeFullRes` 走 LibRaw（AHD + 自动亮度）。同一套 `EditParams` 作用在两张色彩基准不同的底图上不等价 → 用户在 A 上调、导出得到 B，颜色与亮度必然不一致。修图 App 里这是硬伤。
- **位置**：`core/decode/Decoder.kt:49-54`（proxy 分支恒用 `ArwPreviewDecoder`）、`arw/ArwFullDecoder.kt:28-30`、`MainActivity.kt:98`（proxy）与 `:152`（export）
- **方案**：代理图与导出同源，都走 LibRaw；代理档用 `half_size=1` 或 `user_qual=0` 换速度。全分辨率内嵌 JPEG（F04）只用于「秒开占位」，RAW 解完后替换并重放参数（渐进式，Lightroom 同款做法），且占位期间禁用滑块或明确标注
- **验证**：ARW 导出图与预览截图做直方图比对，均值差 < 1%
- **状态**：已核对

### F04 ARW 内嵌预览取错 IFD，全分辨率 7008×4672 预览从未被使用
- **原因**：见 §0 纠正。`previewJpegRange()` 不走 IFD 链、且首个命中即返回，导致 M0b「ARW 内嵌**全分辨率** JPEG 预览解码」实际只解出 1616×1080。1.9MB 就能拿到 7008×4672，白白浪费。
- **位置**：`arw/ArwContainer.kt:29-34`（只查 IFD0 + SubIFD，缺 next 链遍历）、`:59-61`（首个命中即返回，未择优）
- **方案**：遍历 IFD0 → next → next…（设置最大跳数防环）+ SubIFD，收集所有 `0x0201/0x0202` 候选，按 `0x0202` 长度（或解析 SOF0 宽高）择大者返回
- **连带**：`ArwPreviewDecoder.kt:57` 注释「预览本身仅 1616px」需改；`app/src/test/.../ArwPreviewExtractorTest.kt` 现有 3 个用例断言的是「首个命中即返回」，需**新增链式用例**（IFD 链里存在更大预览时应择大）
- **验证**：真机打开样本 ARW，日志 `arw preview decoded` 应输出 7008×4672
- **状态**：已核对

## 2. P1 — 高风险（会 OOM / 画质缺陷 / 交互失效 / 安全）

| ID | 问题与原因 | 位置 | 方案 | 状态 |
|---|---|---|---|---|
| F05 | **全分辨率导出内存峰值 500MB+**。同时在世：LibRaw processed 98MB(native) + `jbyteArray` 131MB + `GetByteArrayElements` 可能再复制 131MB + Bitmap 131MB + `renderInto` 内 `IntArray` 131MB + `fullTarget` 131MB。计划 §6.2 只算了 131MB，未含任何中间缓冲，且未开 `largeHeap`、未分块 → 33MP 导出很可能 OOM | `raw_bridge.cpp:86-143`、`ArwFullDecoder.kt:78-95`、`EditEngine.kt:26-60`、`MainActivity.kt:148-174`、`AndroidManifest.xml:10-21` | native 侧改 `AndroidBitmap_lockPixels` 直写 Bitmap（同时消掉 Kotlin 侧 3200 万次逐行 `setPixels`）；`EditEngine` 改按行带处理、复用单份 `IntArray`；必要时补 `largeHeap` | 已核对 |
| F06 | **逐像素装箱**。`processPixel` 返回 `Triple<Int,Int,Int>`，内部 4 个子函数各再返回一个 `Triple` → 每像素约 5 个对象 + Integer 装箱，33MP 导出 ≈1.6 亿次分配，与注释「亚秒级」自相矛盾 | `ColorMath.kt:44-143`（`applyWhiteBalance`/`applySaturation`/`applyLumaCurve`/`applyBuiltinLut`/`processPixel` 五处 Triple）、`EditEngine.kt:52` | 白平衡×曝光×linear→sRGB 本质是逐通道单调映射，塌缩成 3 张查表；其余改成写入 `IntArray` 的无返回值函数 | 已核对 |
| F07 | **暗部会出色带**。`LINEAR_TO_SRGB_LUT` 建在**线性域**均匀 256 格上，线性 0~1/255 对应 sRGB 0~0.08，暗部全塌进极少数台阶；`toIdx` 还先 `coerceIn(0,1)` 把曝光后的高光直接砍死，高光恢复不可能 | `ColorMath.kt:16`（LUT 构建）、`:120-122`（查表）、`:146`（`toIdx` clamp） | 若 F01 落地则整段改为 16-bit 线性域运算；过渡期至少把表加密到 4096 项并去掉提前 clamp | 已核对 |
| F08 | **滑块无节流 + 每跳都进撤销栈**。`LaunchedEffect(params)` 每跳触发一次全代理图重渲，`renderInto` 是不可取消的阻塞循环（`jobs.forEach{it.get()}`），靠 Mutex 串行排队 → 拖动积压；一次拖动能往 `EditHistory` 塞几十条，撤销等于失效 | `MainActivity.kt:75-93`（重渲）、`:137-140`（push）、`EditorScreen.kt:144-148`（Slider 缺 `onValueChangeFinished`） | `snapshotFlow{params}.conflate()` 或 debounce(16ms)；`renderInto` 分片内加 `isActive` 协作取消；history 只在 `onValueChangeFinished` 时 push | 已核对 |
| F09 | **`.gitignore` 未覆盖实际使用的凭证文件名**。忽略的是 `.ci_token.txt` / `.ci_*.json`，而 CI 日志抓取流程实际写的是 `.ci_ghtoken` → **不在忽略范围内，存在 PAT 入库风险** | `.gitignore:41-42` | 改为 `.ci_*`；同时建议轮换现有 PAT（已在项目说明中明文出现） | 已核对 |
| F10 | **LibRaw 静态链接的许可合规未评估**。LibRaw 为 LGPL-2.1 / CDDL-1.0 双许可，静态链接进闭源 APK 触发 LGPL 的「可重新链接」义务。风险表 §8 只写了 LUT 许可，漏了这条。自用无碍，一旦分发即硬问题 | `app/src/main/cpp/CMakeLists.txt:24`、`docs/DEV_PLAN.md §8` | 选 CDDL 分支或改动态链接 `.so`；补 LICENSE/NOTICE；风险表补条目 | 已核对 |

## 3. P2 — 中（CI 闸门 / 可复现性 / 交付回路）

| ID | 问题与原因 | 位置 | 方案 | 状态 |
|---|---|---|---|---|
| F11 | **CI 不是质量闸门**。DEV_PLAN §7 承诺「ktlint/detekt 必过、unit tests 必过」，实际：无 ktlint、无 detekt、`test` 任务**从未被调用**（仓库里明明有 `ColorMathTest` / `ArwPreviewExtractorTest` 两个 JVM 测试），`lint` job 还挂着 `continue-on-error: true` 永不拦门 | `.github/workflows/android.yml:60-61`（只有 assembleDebug）、`:75`（continue-on-error）、`:114-115` | build job 加 `./gradlew testDebugUnitTest`；接 ktlint/detekt 插件；lint 去掉 continue-on-error 或改 `lintRelease` 拦门 | 已核对 |
| F12 | **NDK 版本不可复现**。CI 用 `sdkmanager --list \| sort -V \| tail -1` 动态取最新 NDK，随镜像漂移；`app/build.gradle.kts` 未 pin `ndkVersion`，AGP 默认版本与 CI 装的可能不一致 | `android.yml:48-52 / 102-106 / 170-174`、`app/build.gradle.kts:9-33` | build.gradle.kts 显式 `ndkVersion = "<固定版本>"`，CI 装同一版本 | 已核对 |
| F13 | **「Action 之后通知我」这条项目硬要求未实现**。只有 artifact 上传，没有构建摘要或任何通知步骤（wb-issues `rO1ZRw` 仍在待开始） | `android.yml:63-68` 之后缺 summary/notify step | 加 `$GITHUB_STEP_SUMMARY` 输出（commit、变体、APK 名、大小、测试结果）；Firebase 分发按 §7 条件化 | 已核对 |
| F14 | **CI 三个 job 各自重复 20 行 SDK/NDK 安装块**，各下载一次 NDK（~1-2GB），维护面 ×3 且已经出现「改一处漏两处」的隐患 | `android.yml:38-52 / 92-106 / 160-174` | 抽成 composite action 或 reusable workflow | 已核对 |
| F15 | **release job 不依赖 lint/test**，测试挂了照样能产出签名包 | `android.yml:142`（`needs: [build, check-signing]`） | needs 补 lint/test job | 已核对 |

## 4. P3 — 低（清理与用户可见文案）

| ID | 问题与原因 | 位置 | 状态 |
|---|---|---|---|
| F16 | HomeScreen 路线图卡片还是旧 8 步版（fp16+EGL P3 / 16bit TIFF / MediaPipe），与 v3.0（已删 16-bit 导出、改 TFLite）冲突，且「NDK + LibRaw」仍标未完成 —— **真机上用户直接看得到的错误信息** | `ui/home/HomeScreen.kt:184-191` | 已核对 |
| F17 | ARW 相关文案全部过期：仍在说「P1b 开放修图」，而 `useLibRaw` 早已为 true | `MainActivity.kt:107`、`HomeScreen.kt:161`、`Decoder.kt:37`、`ArwFullDecoder.kt:18-21` | 已核对 |
| F18 | `bitmapConfig="RGBA_F16"` 是死字段（全代码硬编码 `ARGB_8888`）；Manifest 请求 `colorMode="hdr"` 但管线是 8-bit sRGB，广色域名不副实 | `ui/home/ResolutionProfile.kt:23`、`AndroidManifest.xml:37` | 已核对 |
| F19 | `READ_MEDIA_IMAGES` / `READ_MEDIA_VISUAL_USER_SELECTED` 在 Photo Picker + SAF 方案下不需要，HomeScreen 还专门弹了一次授权 UI | `AndroidManifest.xml:6-8`、`HomeScreen.kt:52-58, 131-145` | 已核对 |
| F20 | minSdk=36 下 `Build.VERSION.SDK_INT >= Q` 判断全是死代码 | `core/decode/Exporter.kt:34-37, 48-52` | 已核对 |
| F21 | `copyToTemp` 在 `openInputStream` 返回 null 时仍返回 0 字节文件（`?.use` 的返回值被丢弃）→ 绕一圈才回退；异常路径下半截临时文件不会删 | `arw/ArwFullDecoder.kt:68-75` | 已核对 |
| F22 | 本地工作树两个 LibRaw 子模块处于 **deleted** 状态（`git status` 显示 ` D`）。CI 用 `submodules: recursive` 不受影响，但本地一旦 `git commit -a` 会把子模块从仓库删掉 | 工作树，需 `git submodule update --init --recursive` | 已核对 |
| F23 | 子模块 pin 在 master 快照（LibRaw `dde798dd` / LibRaw-cmake `eb98e432`），已因此吃过 `dcraw_free` 被移除的 API 变更 | `.gitmodules` + gitlink | 已核对 |
| F24 | `ENABLE_OPENMP=OFF` + `user_qual=3`(AHD) 单线程解 33MP，导出预计 5-15s，UI 只有一行「正在生成导出…」，无进度无取消 | `CMakeLists.txt:14`、`raw_bridge.cpp:39`、`MainActivity.kt:147` | 已核对 |

## 5. 文档与看板对齐（不改代码，但必须同步，否则下一轮又会照错的计划做）

| ID | 目标 | 内容 |
|---|---|---|
| D01 | `DEV_PLAN §0` 备注 | 「free_image() 替代 dcraw_free()」结论错误 → 更正为 `dcraw_clear_mem()`（见 F02） |
| D02 | `DEV_PLAN §2.1` | 「代理图 GPU 实时预览 RenderEffect/AGSL」标记为 P1a ✅ → 实为 CPU 多线程逐像素，全仓库无任何 shader 代码，改为未实现 |
| D03 | `DEV_PLAN §6.1` | target/compileSdk 仍写 37 → 与 `libs.versions.toml` 及 §0 对齐为 36 |
| D04 | `DEV_PLAN §5.1/§5.2` | 路径清单大面积失效：`RawInfo.kt`、`cpp/libraw/CMakeLists.txt`、`third_party/libraw`（小写）都不存在；`EditStack/ops/mask/render/PreviewPipeline/ExportResolver/data/ui.gallery/ui.export/util` 一个未建；Hilt/Room/Coil/Navigation 全未引入。建议**按现状重写**，而不是反过来逼代码去凑 |
| D05 | `DEV_PLAN §6.2` | 内存估算未含 LibRaw 中间缓冲与 JNI 拷贝 → 按 F05 重算 |
| D06 | `DEV_PLAN §8` | 补 LibRaw LGPL 静态链接合规风险（F10） |
| D07 | `DEV_PLAN §3.2` | 内嵌预览表格补充「全分辨率 7008×4672 JPEG 位于 IFD 链第 3 个 IFD，1.9MB」这一实测事实 |
| D08 | wb-issues `rq3yMT` | 标题仍写「targetSdk 37 / compileSdk 37」→ 改 36 |
| D09 | wb-issues `rNJffz` | 描述里 free_image 结论错误 → 更正 |
| D10 | wb-issues 新增卡 | 「P1b-3.5 渲染管线定型」，承接 F01/F03/F05/F06/F07，**排在 P1b-4 人像算子之前** |

## 6. 建议实施顺序

1. **第 1 批（可独立验证，不动管线）**：F02、F04、F09、F22 + D01/D08/D09
   —— 泄漏与取错 IFD 都是点状修复，改完就能出一包真机验证；F09/F22 是安全与工作树卫生。
2. **第 2 批（渲染管线定型，一次做完）**：F01 + F03 + F05 + F06 + F07
   —— 这五条互相咬合，必须一起改。**在 P1b-4 人像算子之前完成**，否则 10 个算子会摊在一条 8-bit、装箱、预览与导出不同源的管线上，返工成本翻倍。
3. **第 3 批（CI 闸门与交付回路）**：F11、F12、F13、F14、F15
   —— 在无本地构建环境下，CI 是唯一质量闸门，越早补越省。
4. **第 4 批（清理与文案）**：F08、F10、F16~F21、F23、F24 + D02~D07、D10

> P1b-6「整阶段完成后统一真机测试」建议改为「每完成一批出一包」：无本地构建环境下唯一反馈通路是 APK + 日志，越晚测越贵。

## 7. P1 收尾复审（2026-09-11）：「矩阵标 ✅ 但实际不可达」的断链

> 触发：要求「把 P1 和 P2 一口气全部开发完」。本轮**不加新功能**，只审计「计划说做了、代码却到不了」的断链。
> 结论：P1/P2 主体完整（全仓无 `TODO` / 无 stub / 无未接线回调），但存在 4 处**声明与可达性不一致**，已全部修复。

| ID | 级别 | 问题 | 证据 | 修复 |
|---|---|---|---|---|
| R01 | **高** | `DEV_PLAN §2.1`「曝光 / 曲线 / LUT」标 P1a ✅，但**编辑器没有任何曲线入口**：`EditParams.lumaPoints` 只被 `PixelProgram` 读，全仓无写入点 → 用户永远只能得到恒等曲线 | `Grep lumaPoints` 仅命中 `EditModel.kt:15`（默认值）与 `PixelProgram.kt:18`（读取） | 新增 `core/edit/ToneCurve.kt`（黑场/中间调/白场三点锚点 ↔ 控制点，含钳位与缺锚回退）+ `ToneCurveTest`；`EditorScreen` 增 `CurveRow`（三滑块，非恒等时才显示「重置曲线」），走既有 `onParamChange/onParamCommit` 入撤销栈 |
| R02 | 中 | `DEV_PLAN §2.1`「导出到相册（JPEG / PNG）」标 ✅，但 `Exporter` 的 PNG 分支**没有任何调用点**：3 处 `Exporter.export(...)` 全硬编码 `ExportFormat.JPEG` → PNG 不可达 | `Grep "ExportFormat.JPEG, 92"` → 3 处全为硬编码 | `EditorScreen` 增导出格式 chips（JPEG/PNG，导出按钮文案随格式变化）；`MainActivity` 持 `exportFormat`，并在进入 `Dispatchers.Default` **之前**于主线程取值（不在后台线程读 Compose 快照）。`CameraBatch` 维持 JPEG（批量体积考量，属产品决策） |
| R03 | 低（文档） | `DEV_PLAN §2.1` 把「边拍边预览（liveview）」在 P2 列标 ✅，与 `P2_DESIGN §8 D4`「本期只评估不实现」直接矛盾 | 两文档对照 | 矩阵改为「后置」，与 P2_DESIGN 对齐 |
| R04 | 低（死代码） | `CameraPanel` 改成长会话模型后，`CameraConnection` / `CameraPtpReport` 失去唯一主代码调用点（只剩自带单测）→ PoC-2/3 体检报告实际上不可达 | `Grep CameraConnection` 仅命中自身定义与 KDoc | `CameraConnection.connect()` → `inspect(session, steps)`：改为对**已有会话**体检，连接成功后自动产出报告（`CameraPanel:206` 已接线）。既消除死代码，又省掉一次多余握手 |

> 有意不动（非遗漏）：`NeutralGrayParams.threshold` 无 UI —— 它是算子内部调参（细节保护阈值），不在计划列出的用户可见控制项内。

## 8. 第三方审计报告核对与修复（2026-09-11）

> 触发：要求「核对 `PixelCake_全面审计报告_2026-09-11.md` 是否正确」→ 逐条回到源码核验 → 用户确认「修改」。
> 核验结论：报告**方法严谨、绝大多数条目属实**（3 严重 + 12 中 + 12 轻微，仅 2 处需修正，见下）。
> 本轮按**最小改动**修其 P0 三项（S1/S2/S3）。

### 8.1 报告需修正的两处（核验增量，非代码问题）

| # | 报告原文 | 实际 | 处置 |
|---|---|---|---|
| C1 | S1「预设 `creamy` 开箱即触发，用户点预设脸会变宽」 | 方向 bug 属实，但 **`Beauty.apply` 需非空蒙版才生效**（`Beauty.kt:22` `val m = mask ?: return`）；蒙版来自画笔描迹，`buildSkinMask` 无描迹时返回 null，且 `RetouchScale.skinMask` 零调用（无自动蒙版）→ 实际需用户先画一笔 | 严重性下修为「需先有画笔蒙版」 |
| C2 | M5「`Beauty`/`NeutralGray` 在 `mask==null` 时提前 return → 磨皮/液化在批量静默 no-op」 | 只有 **`Beauty`（液化）会 return**；`NeutralGray.kt:27` 是 `if (mask == null) 1f` → **磨皮在批量链路反而「全局无蒙版生效」**（连背景一起磨），不是 no-op | 事实更正；是否给批量接入自动蒙版属产品决策，本轮未动 |

### 8.2 已修复（P0）

| ID | 级别 | 问题 | 证据 | 修复 |
|---|---|---|---|---|
| R05 | **高** | S1：`Beauty` 瘦脸/收颌的**后向映射方向写反**——`dx -= k(x-cx)` 使输出点采到更靠质心的源点 ⇒ 实际是**放大**，与 KDoc「拉向质心（瘦脸/收颊）」相反 | `Beauty.kt:43-44` | 改 `dx += …` / `dy += …`（输出点采更靠外的源点 → 外侧内容被拉进来 = 收拢）；并**补方向断言单测** `slimFaceMovesContentTowardCentroid` / `slimJawMovesContentTowardCentroidVertically`（旧 `shiftsPixelsWithMask` 只断言 `diff>0`，正是它放过了本 bug；L9 同因） |
| R06 | **高** | S2：`CameraBatch` 单张处理**只有 `finally` 没有 `catch`** → `processOne` 内的 OOM 等异常穿透 `run()`；调用方 `scope.launch` 无兜底 → `busy` 永不复位、界面永久停在「批量处理中…」，已完成项全丢 | `CameraBatch.kt:145-150`、`CameraPanel.kt:430-442` | 单张 `try/catch(Throwable)/finally`：失败转 `ItemResult(ok=false, "处理失败：…")` 记入 `items`；`run()` 整体再加一层兜底，保证 `Summary` 一定返回；`CameraPanel` 批量协程加 `try/catch/finally` 确保 `busy/phase` 必复位。三处均**原样重抛 `CancellationException`**（不吞取消信号） |
| R07 | **高** | S3：下载路径把**空包（ZLP）当失败**——`read <= 0` 即中断，与同文件 `readFully` 的「零长度包可重试」策略矛盾；大文件（35–57MB）一次空读即整张失败，且报错文案误导为「超时或设备已断开」 | `PtpTransport.kt:214`（`readFully` 对照 `:293-309`） | 拆开 `read < 0`（超时/断开）与 `read == 0`（ZLP，重试 `MAX_EMPTY_READS` 次）；成功续读后清零计数（下载读次数远多于 `readFully`，避免零星空包累积触顶）；两类错误文案分开 |

> 未做（受「无本地构建环境 / 最小改动」约束，报告 M10/L12 已登记为后续）：`CameraBatch.run()` 三语义（取消/导完即删/失败续跑）与 `PtpTransport` 的**单测**需要先抽 `PtpTransport` 接口注入 fake，属结构性重构，本轮不夹带。
> 状态：§8.2 的改动**已提交 `280dbfb`**，CI run `34612770955` 全绿（详见 `docs/Github_CI.md`）。

## 9. 第二轮复审（2026-09-11）：N1 语义统一 + N2 文档过期

> 触发：第二轮复审报告（基线 `95a868a` / 代码 `280dbfb` / run `34612770955`）—— 确认 §8 的 S1/S2/S3 修复正确、
> C1/C2 更正成立，并提出 N1（`mask == null` 语义相反）与 N2（本文档 §8 结论过期）。本轮修这两项。

| ID | 级别 | 问题 | 证据 | 修复 |
|---|---|---|---|---|
| R08 | **中** | N1：`mask == null` 在两个皮肤类算子间**语义相反**且设计稿未定义 → 同一预设经两条入口得到两种结果。批量链路（`mask = null`）套「奶油肌」时**磨皮对整张照片（含背景）全强度生效、瘦脸静默失效** | `NeutralGray.kt:27`（null → `1f` 全局）vs `Beauty.kt:22`（null → `return`）；`CameraBatch.kt:205` 传 `null`；`P1b_DESIGN.md:98/106/108/112` 只写 `strength*mask`，未定义 `null` | **统一为「null = 未圈定作用域 → 不执行」**（方案②）：`NeutralGray` 改 `val skin = mask ?: return`；`RetouchMask` KDoc 与 `P1b_DESIGN.md §4` 写明约定；`NeutralGrayTest` 两用例改传全幅蒙版并**新增 `noOpWhenMaskNull`** 断言；`CameraPanel` 批量区明示「磨皮/瘦脸不生效」 |
| R09 | 低（文档） | N2：§8 结论「本轮改动未提交（不代 push）」已过期 | 本文档 §8 末行 | 更正为「已提交 `280dbfb`，CI run `34612770955` 全绿」 |
| R10 | **高（本轮新提，已按方案 A 落地）** | retouch 全幅缓冲：全分辨率导出在 retouch pass 内同时持有 目标 Bitmap(131MB) + `RetouchLayer.px`(131MB) + `NeutralGray.boxBlur` 的 tmp/out(各 131MB) + `Beauty.out`(131MB) → 峰值 ≈524MB（JVM 堆），若计入 retouch 期间仍打开的 196MB native 线性母版 ≈720MB。**撤销了 F05 的分带内存纪律** → 全分辨率 + retouch 导出存在 OOM 风险 | `RetouchLayer.kt:31`、`NeutralGray.kt:53-59`（tmp/out 各一全幅 `IntArray`）、`Beauty.kt:33` | **已实施**：① `NeutralGray` 改**分带**（`BAND_ROWS=256` + 保留上一带 `radius` 行原始 halo），3×131MB → **≈30MB**；② `Beauty` 消除整幅 `out`，改为**只在蒙版包围盒**开缓冲（蒙版外恒等 ⇒ 原地保留），典型画笔蒙版下 131MB → 数 MB。两者各配「朴素整幅参照实现」等价性单测。**已续做（`RetouchLayer.px` 流式化）**：`RetouchLayer` 不再持有全幅 `px`，改为在 `PixelStore` 上分带/条带/小图块处理——磨皮分带 `BAND_ROWS=256`（携上一带原始 halo）、液化只处理蒙版包围盒的**源行条带**（`Beauty.apply(rowOffset,centroid)`）、祛瑕按笔画开**小图块**（`Inpaint.applyOne`，局部坐标）、追色改**两遍只读统计 + 一遍写入**（`accumulateSum/accumulateVariance/applyWithStats`，保持原求和顺序 ⇒ 位等价）。**该 pass 现只剩目标 Bitmap(131MB) 的固有工作集，整幅 `px` 已消除**；等价性由 `RetouchLayerTest`（`MemStore` vs 朴素整幅参照，6 用例）钉死 |

> **R10 的归属更正**：第二轮报告把它列在「上轮未动项」，但上两轮报告（FIX_LIST 复审 + 全面审计）**都未列过这条** ——
> 它是 P1b-4 retouch 层引入的**新增**严重项，不是旧账。
>
> **R10 实施口径（方案 A）**：
> - `NeutralGray` 走**分带**：每带源缓冲 = 上一带留存的 `radius` 行原始像素（顶部 halo） + 本带核心行及其下 `radius` 行；
>   boxBlur 只作用在子图上，核心行的窗口恒落在子图内 ⇒ 与整幅版 clamp 结果一致。**为什么必须留存原始 halo**：
>   核心行是原位写回的，上一带写过之后顶部 halo 已非原始值，不能再当源用。
> - `Beauty` **不能分带**，改走**包围盒**：液化是后向映射且位移随「到质心距离」线性增长 ——
>   `slimJaw` 把源点拉到带上方、`eyeEnlarge` 把源点拉到质心下方，跨越多带；任何单趟带状原地处理都会读到
>  已写值而失真，要精确就得保留无界 halo（等于没省）。而蒙版外 `mv==0` 恒等，故只在包围盒开缓冲是
>  此算子唯一「既精确又有界」的口径。
> - 等价性由**朴素整幅参照实现**钉死（不复用被测代码）：`NeutralGrayTest.bandedMatchesFullFrame`、
>   `BeautyTest.bboxBufferMatchesFullFrame`。⚠️ 这两条是本轮新增的关键回归防线 —— 后续再动这两个算子必须让它们保持绿。
>
> **R08 的连带行为（已定案，非「行为变化」）**：编辑器改为**无描迹时显式传 `FullMask`**（作用域 = 整幅），
> 因此「磨皮」滑杆在未涂抹画笔蒙版前**仍全局可见效果**，与 P1 旧行为一致；`null` 唯一含义 = 「不执行」，
> 只由 `CameraBatch` 显式传入。落点：`Mask.kt` 新增 `object FullMask`；`RetouchScale.skinMask` 无描迹返回 `FullMask`；
> `MainActivity.buildSkinMask` 改为委托 `RetouchScale`（顺带消除 M4 死代码）。护栏 `RetouchScaleTest.noStrokesYieldsFullMask`。

