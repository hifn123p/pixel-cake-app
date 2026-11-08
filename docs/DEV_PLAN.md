---
title: 像素蛋糕 App — 开发计划（唯一有效文档 v3.1）
status: active
created: 2026-09-05
updated: 2026-11-07
project: D:\AI_Project
replaces: [PLAN.md, FEASIBILITY_REPORT.md, P1_MVP_DESIGN.md, PLAN_REVIEW.md]
description: 像素蛋糕（AI 人像精修）安卓应用的唯一开发计划：项目介绍、功能矩阵、核心原理、技术路线、代码架构（当前真实模块树）、设备要求、交付调试与风险、文档索引。历史设计（阶段稿 / UI2.0 备选稿 / 外部审计快照）已移至本地 docs/archive/（不入库），原件可 git show 取回。
---

# 像素蛋糕 App — 开发计划（v3.1）

> **本文是本项目唯一有效的开发计划**，已合并此前的 `PLAN.md` / `FEASIBILITY_REPORT.md` / `P1_MVP_DESIGN.md` / `PLAN_REVIEW.md`。
> **文档政策（2026-11-07）**：`docs/` 只保留**当前技术路线与设计**；被取代 / 阶段性 / 快照性质的
> 文档一律移入本地 `docs/archive/`（`.gitignore` 屏蔽、不入库）——含阶段设计存档
> `PHASE_DESIGN_HISTORY.md`（KDoc 编号 `F01~F24` / `R01~R10` / `D01~D10` 的溯源处，取回命令见
> `docs/archive/README.md`）、`UI2.0.html` 备选稿、两份外部审计报告（整改结论已沉淀进
> `ENGINEERING_NOTES.md` §13）。
> v3.1 相对 v3.0 的关键变更：
> 1. **进度更新到 v0.6.4**：UI 3.0「Aperture」全量改版 + 外部审计整改批次（EXIF 层 / 导出取消 / 系留健壮化 / 空作用域禁用 / T1 参数栈 JSON）。
> 2. **§5.2 代码架构树改为当前真实模块**（v3.0 的「规划未建」占位已全部兑付或删除）。
> 3. **§9 文档索引只列现行文档**；历史文档的溯源方式集中在 `docs/archive/README.md`。
>
> v3.0 保留的关键决策（仍然生效）：
> 1. **ARW 修图必须走全量 RAW**（LibRaw 真解马赛克），内嵌预览**只用于打开/快速预览**，不作为修图数据源。
> 2. **SDK：minSdk 36；compileSdk / targetSdk 暂对齐 36**（CI runner 镜像尚未发布 `platforms;android-37`，待官方发布后升回 37）。
> 3. A7C II 规格（33MP / 7008×4672 / 14bit）；**导出最高 8/10-bit**（内部 16-bit 保精度，不承诺 16-bit 文件导出）。

---

## 0. 当前进度（2026-11-07）

> **已发布到 `v0.7.0`**（`versionCode = 22`；`v0.1.0` → `v0.7.0` tag 齐全，5 个 CI job 含 `Publish GitHub Release`；
> 最新 run 全绿报告见 `docs/Github_CI.md`）。逐版本明细见根 `README.md` 的「版本历史」表。
>
> | 阶段 | 状态 | 说明 |
> |---|---|---|
> | M0a / M0b / P1a / P1b / P1+ / P2 | ✅ | 全部落地（P1a 最小链路 → P1b LibRaw 全量 + 人像算子 → P1+ LiteRT 双模型 → P2 USB PTP 直连与批量）。真机验收持续进行，清单见 §10 |
> | 调色批次 1~5 | ✅ | 参数体系 **81 项** + 对象作用域图层（8 作用域）。设计见 `TONING_DESIGN.md` / `OBJECT_TONE_DESIGN.md` |
> | **UI 3.0「Aperture」** | ✅（v0.6.2） | **One-Surface** 全屏画布 / 双通道调节 / 点哪修哪 / 强度环 / 自动增强 / 历史丝带 / 分割对比 / 导出回执 / 曲线本体 / 系留出片 v2 / 暖石墨琥珀主题 / 自适应图标。设计见 `UI3.0.html`（唯一真值） |
> | **导航层 + 模型中心 + 预设分享** | ✅（v0.7.0） | T2 完整形态（sealed AppScreen + NavStack 可测栈；编辑器内再导图 replaceTop 防栈叠层）；**模型中心页**（UI3.0 §N 只读诊断：分割/人脸模型状态、加速器、OOM、缓存，设置→高级进入）；**预设导入/导出**（SAF 导入 + FileProvider 分享，id 冲突自动重生成）|
| **用户预设库** | ✅（v0.6.6） | 另存当前调法为自定义预设（整图参数 + 人像精修；瑕疵描迹按语义不入预设）；编辑器「预设 → 我的」应用/强度环/管理删除；**相机批量与系留默认参数复用**；JSON 持久化（原子写 + 坏文件留证 + 8 断言单测）。T1 序列化解锁的首个功能 |
| **审计剩余项清账** | ✅（v0.6.5） | hy4 阶段 2/3 全量落定：E1 属性码/datatype、A4-A7、D1-D5、B1/B2/B4、M-3/M-4、U2/U3/U6、T2 BackHandler+生命周期可测化、**T3 横屏最小可用**、**T4 无障碍语义**、TetherProcessor 可测化（K1/K2/K3 回归测试）、CE 静态闸门；Q1/Q2/Q3/Q6 决策落地。台账见 `ENGINEERING_NOTES.md` §14 |
| **外部审计整改** | ✅（v0.6.3~v0.6.4） | 双模型审计（qwen3.8 + hy4）→ 3 个 P0（系留 ARW 必失败 / EXIF 层缺失 / 陈旧直方图）+ P1 批次（导出可取消 / 直方图进锁按带 / 系留基线播种 / 空作用域禁用 / 滤镜强度空转 / 悬空缓存路径 / Hint 矛盾）+ **T1 参数栈 JSON 序列化**。台账见 `ENGINEERING_NOTES.md` §13；判级以 hy4 报告为权威（已归档） |
> | P3 | 🔜 | NAS Docker 化 Rust 引擎 —— 未启动 |
> | 端侧模型 P6/P7 | 🔜 | 见 `ONDEVICE_MODEL_PLAN.md`（M1 交互分割 / M2 关键点 / M4 超分 / M5 深度…） |

> LibRaw master API 注意（仍然生效）：`dcraw_make_mem_image()` 的返回产物必须用 `LibRaw::dcraw_clear_mem()` 释放，`free_image()` 只释放内部 `imgdata.image`、二者不可混用；Kotlin `val version` 与 native `getVersion()` JVM 签名冲突，已改名 `librawVersion`。

## 1. 项目介绍

### 1.1 目标
做一款**安卓端 AI 人像精修 App**（对标商业软件"像素蛋糕"，定位类似 Snapseed 但专攻人像），并逐步扩展到：相机直连即拍即修 → 云端 NAS 批量修图。

### 1.2 分阶段产品形态
| 阶段 | 形态 | 说明 |
|---|---|---|
| **P1** | 纯手机端单机 App | 导入相册照片（JPEG / HEIF / ARW）→ 修图 → 导出。全端侧、无后端 |
| **P2** | 相机直连 | 连接 Sony A7C II，拍完直传手机 → 套预设模板 → 边拍边看成片 |
| **P3** | 云端 NAS | 手机控制 NAS 目录，单张/批量提交后台修图（Docker 化 Rust 引擎） |

### 1.3 硬约束（必须遵守）
- **本地零开发环境**：开发机只写代码、不装任何构建环境；编译/检查/测试/打包全在 **GitHub Actions**。
- **全部代码经 git 推送 GitHub**；Action 结束后通知，自行下载到真机调试。
- **调试机：一加15**（Android 16+，骁龙 8 Elite，NPU 充足）。
- **App 必须内置调试日志模块**（可导出分享）——这是"无本地构建"下唯一的联调回路。
- 项目管理走 wb-issues 计划面板。

### 1.4 现有资产
| 仓库 | 内容 | 成熟度 |
|---|---|---|
| `hifn123p/pixel-cake-android` | Kotlin + Compose 安卓**脚手架** | 仅骨架（设备探测 + 首页 + RAW 元数据），无编辑功能 |
| `hifn123p/pixel-cake` | Rust **修图引擎**（Tauri 桌面应用） | **算法完整**，M1–M6 ✅，含 ONNX 推理与 16-bit 管线 |

---

## 2. 功能范围

### 2.1 功能矩阵
| 功能 | P1a | P1b | P2 | P3 |
|---|---|---|---|---|
| 相册导入（Photo Picker） | ✅ | | | |
| JPEG / HEIF 解码 | ✅ | | | |
| ARW **打开/快速预览**（内嵌 JPEG 预览，零 NDK） | ✅ | | | |
| ARW **修图**（LibRaw 全量真解马赛克，16-bit） | | ✅ | | |
| 曝光 / 曲线 / LUT | ✅ | | | |
| 非破坏编辑栈 + 撤销重做 + 原图对比 | ✅ | | | |
| 代理图实时预览（CPU 多线程逐像素 band 渲染，分带拉取 16-bit 线性；GPU/RenderEffect/AGSL 后置） | ✅ | | | |
| 导出到相册（JPEG / PNG 可选；HEIF 需探测） | ✅ | | | |
| 自适应导出分辨率（按设备档位） | ✅ | | | |
| 调试日志模块（落盘 + 导出分享） | ✅ | | | |
| 中性灰磨皮 / 美型液化 / 祛瑕 / 追色 | | ✅ | | |
| 局部调整 + 手动画笔蒙版（CPU `RasterMask` 描迹蒙版；AGSL 未采用） | | ✅ | | |
| 内置人像预设（**44 套**：参数栈，按分类组织） | | ✅ | | |
| A7C2 直连（USB PTP 拉图 + 批量套预设导出） | | | ✅ | |
| 边拍边预览（liveview，需 CRSDK / ScalarWebAPI PoC） | | | 后置 | |
| NAS 目录控制 / 单张·批量后台修图 | | | | ✅ |
| ML 自动皮肤蒙版（P1p-1：LiteRT + MediaPipe 皮肤分割；编辑器「自动蒙版」开关，默认开） | | ✅(P1+) | | |
| ML 人脸检测 / 关键点（P1p-2：喂液化 `centroid`） | | 后置 | | |
| **预览缩放与平移**（双指缩放 ≤8×、双击 2.5×↔1×、按住看原图；UI v2.1） | | ✅ | | |
| **RGB 三通道直方图**（明暗页叠加显示；UI v2.1） | | ✅ | | |

### 2.2 明确不做
- P1 阶段不涉及任何后端/网络（**照片在手机上，全程本地**）。
- 不做 16-bit 文件导出（TIFF/DNG 编码不在范围内）。
- 不做视频、不做社交/云同步。

---

## 3. 核心原理

### 3.1 双轨分辨率：代理图交互 + 全分辨率导出
编辑**只作用在参数栈**（JSON，~1KB）上，像素只在两处物化：
- **预览**：在**代理图**（长边按设备档 1024–2048）上跑 **CPU 多线程逐像素 band 渲染**（分带拉取 16-bit 线性 + PixelProgram 查表上色），保证滑块节流跟手（GPU/RenderEffect/AGSL 规划中，见 §2.1）；
- **导出**：把同一参数栈重放到**全分辨率**位图后编码写盘。
> 改一个滑块 = 改一个 op 参数 + 重编译预览链，**绝不重新解 RAW**。

### 3.2 ARW 双路径（v3.0 关键修正）
| 用途 | 数据源 | 实现 | 精度 |
|---|---|---|---|
| **打开 / 快速预览 / 相册缩略图** | ARW **内嵌全分辨率 JPEG 预览**（7008×4672） | 纯 Kotlin 解析 TIFF/IFD（tag `0x0201` PreviewImageStart / `0x0202` PreviewImageLength）取字节 → 系统 `ImageDecoder`。**零 NDK、零 LibRaw** | 8-bit，相机已烘焙（白平衡/锐化/曲线已应用） |
| **修图** | **全量 RAW 传感器数据** | **LibRaw NDK 真解马赛克** → 16-bit 线性 → 白平衡/色调映射。`RawNative.kt` + `raw_bridge.cpp` + `app/src/main/cpp/third_party/LibRaw` 子模块 | 16-bit 线性，保留完整宽容度 |

> **A7C II 内嵌预览的 IFD 位置（F04/D07 实测事实）**：A7C II 的 ARW 含 3 张内嵌 JPEG 预览，最大一张（**7008×4672，约 1.9MB**）位于 **IFD 链的「第 3 个 IFD」**——需沿 TIFF `next` 指针遍历整条 IFD 链、比较各预览尺寸后择取。`ArwContainer.kt` 的 IFD 遍历即按此逻辑取最大预览；若只看首个 IFD 会拿到较小的缩略图而非全分辨率预览。

> **为什么修图必须全量**：内嵌预览是相机处理过的 JPEG，白平衡与色调曲线已固化，无法重新设定白平衡、无法大范围拉回曝光/高光——**没有修图所需的编辑宽容度**。因此"全量解码"是 ARW 修图的必需项，只是不阻塞首个 APK。

### 3.3 非破坏编辑栈
有序操作列表，序列化为 JSON（P1/P2/P3 与预设模板共用同一格式）：
```json
{
  "version": 1,
  "source": { "uri": "content://...", "type": "jpeg|heif|arw", "w": 0, "h": 0 },
  "operations": [
    { "op": "exposure",       "params": { "ev": 0.3 } },
    { "op": "curves",         "params": { "luma": [[0,0],[128,140],[255,255]] } },
    { "op": "lut",            "params": { "lutId": "portrait_warm", "intensity": 0.8 } },
    { "op": "agsl",           "params": { "shader": "skin_soften", "amount": 0.5, "mask": "skin" } },
    { "op": "neutral_gray",   "params": { "strength": 0.4, "mask": "skin" } },
    { "op": "beauty",         "params": { "warp": "slim_face", "amount": 0.2 } },
    { "op": "color_transfer", "params": { "ref": "portrait_film" } },
    { "op": "inpaint",        "params": { "strokes": [{ "x":0,"y":0,"r":8 }] } }
  ],
  "preset": { "id": "wedding_soft" }
}
```

### 3.4 预设 = 参数栈 + LUT（两种形态）
- **(A) 参数栈预设**：自研、零授权风险，用于小红书/Ins 热门风（Portra 800 / Fuji / 复古褪色 / 莫兰迪 / 日系…）。实现要点：曲线 + 橙色明度/饱和微调（Orange Luminance +12~18 保肤色）+ `color_transfer` 取参考帧。
- **(B) `.cube` 3D LUT**：仅取 **MIT / CC 可再分发**源（如 `shravankumar147/photo-edit-app` 的 `cinematic.cube`、`fuji_fp-100c_alt.cube`；`mv-lab/NILUT` CC4.0）。构建期转 3D LUT（33³/64³ half-float）打包为 asset，运行时 GPU 采样 + 三线性插值。
- ⚠️ 打包前逐个核许可并保留 LICENSE/NOTICE；**社区流传的 Lightroom DNG/XMP 预设不是 `.cube`**，不可直接内置（且多为付费/来源不明）→ 一律不用。

### 3.5 轻量 ML（P1+；已落地，模块见 `core/ml/`）
> **⚠️ 口径更正（2026-09-12）**：原定「TFLite + NNAPI delegate（NNAPI→GPU→CPU 降级）」**已过期** ——
> **NNAPI 自 Android 15 起被官方废弃**（LiteRT 的 NNAPI delegate 页已重定向到迁移指南），TFLite 本体进入维护模式（只收安全/稳定性修复）。
> 现行栈是 **LiteRT**：`com.google.ai.edge.litert:litert:2.x`，用 **`CompiledModel`** API，accelerator 走 **`GPU → CPU` 级联**（NPU 需 `BuiltinNpuAcceleratorProvider` + 厂商 delegate，列为后续可选）。

**模型选型**：直接用 MediaPipe 官方 **`selfie_multiclass_256x256`**（Apache-2.0；输入 `[1,256,256,3]` RGB÷255；输出 6 类：背景/头发/`body-skin`/`face-skin`/衣服/其它）产出**皮肤蒙版**。
**不再依赖**把 Rust 仓库 `detect/*.rs` 的 ONNX 权重转 TFLite（该转换链降级为可选后置项，规避了原「ONNX→TFLite 转换」风险）。

`RetouchMask` 接口已就绪，接入 **UI 零改动**。分阶段：**P1p-1** 自动皮肤蒙版（`MlSkinMask : RetouchMask`）→ **P1p-2** 人脸检测/关键点（喂液化的 `centroid`）→ **P1p-3** 可选精修 / NPU。

### 3.6 混合算力（P3 及以后）
日常编辑全端侧；仅"GPEN 增强 / 批量 / 老设备"才唤醒 NAS 上的 Rust 引擎。网络模型：**RAW 一次落盘 + 代理图 + 参数栈回传**，绝不做"每步回传整张 RAW"（A7C II ARW ~35–57MB，远程回传 ~10–12s/张，不可交互）。

---

## 4. 技术路线

```
M0a  SDK 升 minSdk36 / target36 / compile36 + CI 出首个可装 APK
      目标：先跑通「编码 → push → Action → 拿 APK → 真机装 → 日志回传」闭环
M0b  ARW 内嵌预览解码（纯 Kotlin TIFF/IFD，零 NDK）  ← 只服务「打开/预览」
──────────────────────────────────────────────────────
P1a  最小可用编辑链路 → 首个真机可测 APK
      导入 / 解码(JPEG·HEIF) + 曝光·曲线·LUT + 导出 + 调试日志 + 自适应分辨率
      （ARW 此时仅「可打开预览」，修图暂不支持）
P1b  完整人像修图 + ARW 全量修图
      LibRaw NDK 全量解码 → 16-bit 管线
      中性灰磨皮 / 液化 / 祛瑕 / 追色 / 局部 + 内置预设 44 套
P1+  ML 自动蒙版（LiteRT + MediaPipe 现成 .tflite，UI 零改动）
──────────────────────────────────────────────────────
P2   A7C2 直连（USB PTP 拉图，先 PoC）+ 预设套用 + 边拍边看
P3   NAS Docker 化 + axum API（注意 API 37 本地网络权限）
```

**验收口径**
- M0a：一加15 能装、能开、调试日志可导出回传。
- P1a：导入 JPEG/HEIF → 调曝光/曲线/LUT → 导出到相册；ARW 能打开预览。
- P1b：ARW 能**全量修图并导出**；预设可用；磨皮/液化/祛瑕/追色可用。

---

## 5. 代码架构

### 5.1 复用清单（现有仓库 · 具体路径）

#### A. `pixel-cake-android`（安卓脚手架，直接在此基础上开发）
| 路径 | 内容 | 复用方式 |
|---|---|---|
| `app/src/main/java/com/hifn/pixelcake/MainActivity.kt` | 入口 Activity | 保留，接入 Navigation |
| `app/src/main/java/com/hifn/pixelcake/PixelCakeApp.kt` | Application | 保留，接入 Hilt |
| `app/src/main/java/com/hifn/pixelcake/ui/home/DeviceCapabilities.kt` | **设备能力探测**（广色域/HDR/内存预算） | **保留并扩展**——自适应分辨率与 NPU 分级都依赖它 |
| `app/src/main/java/com/hifn/pixelcake/ui/home/HomeScreen.kt` | 首页（能力面板+权限+路线图） | 保留，改路由入口 |
| `app/src/main/java/com/hifn/pixelcake/arw/ArwContainer.kt` · `ArwFullDecoder.kt` · `ArwPreviewDecoder.kt` · `ArwPreviewExtractor.kt` | ARW 容器 / 全量解码 / 内嵌预览解析（纯 Kotlin IFD 遍历 + LibRaw 封装） | 现网真实模块（无独立 `raw/RawInfo.kt`） |
| `app/src/main/java/com/hifn/pixelcake/ui/theme/{Color,Theme,Type}.kt` | Compose 主题 | 保留 |
| `app/src/main/cpp/raw_bridge.cpp` | LibRaw JNI 实现（`openLinear` / `readLinearRows` / `closeLinear` 分带会话） | **P1b 已接入并启用**（externalNativeBuild 编译） |
| `app/src/main/cpp/CMakeLists.txt` | native 构建脚本（静态链接 LibRaw + LibRaw-cmake） | P1b 已启用（见 F10 / 根 `NOTICE`） |
| `app/src/main/cpp/third_party/LibRaw`（master `dde798dd`）· `LibRaw-cmake`（`eb98e432`） | LibRaw 子模块 | **已接入并 pin**（子模块为空是本地工作树未 `submodule update`，CI 递归拉取正常） |
| `.github/workflows/android.yml` | CI 骨架 | 强化（ktlint/detekt/单测/签名/通知条件化） |
| `app/build.gradle.kts`、`gradle/libs.versions.toml` | 构建与版本目录 | compileSdk/targetSdk **36**、minSdk 36（§0/§6.1，待 API 37 平台发布升回）；NDK 已 pin `30.0.16248370`（F12） |

#### B. `pixel-cake`（Rust 修图引擎，P3 复用；算法可作端侧实现参考）
| 路径 | 内容 | 复用方式 |
|---|---|---|
| `crates/engine/src/retouch/neutral_gray.rs` | 中性灰磨皮 | 算法参考 + P3 直接复用 |
| `crates/engine/src/retouch/beauty.rs` | 美型液化 | 同上 |
| `crates/engine/src/retouch/color_transfer.rs` | 追色 | 同上 |
| `crates/engine/src/retouch/inpaint.rs` | 祛瑕 | 同上 |
| `crates/engine/src/retouch/enhance.rs` | GPEN 增强 | P3 重 AI 通道 |
| `crates/engine/src/detect/{face,landmark,segment}.rs` | SCRFD / 2DFAN4 / BiSeNet | 权重转 TFLite 后供端侧；P3 直接用 |
| `crates/engine/src/raw.rs` | LibRaw 解 RAW | P3 服务端解码 |
| `crates/engine/src/color/lut.rs` | LUT 应用 | 参考其插值实现 |
| `crates/engine/src/{pipeline,export,image,infer}.rs` | 管线 / 导出 / 图像 / 推理 | P3 复用 |
| `crates/scheduler/src/` | 异步任务调度 | P3 批量任务队列 |
| `crates/{bus,storage}/src/` | 消息总线 / 存储(SQLite) | P3 复用 |

> P3 需"去 Tauri 化"：新增 `axum` HTTP 层替换 Tauri IPC，ONNX EP 从 CUDA/DirectML 改为 **OpenVINO / Vulkan / CPU**。

### 5.2 代码架构（当前真实模块树，2026-11-07）

```
com.hifn.pixelcake
├── MainActivity.kt · PixelCakeApp.kt      # 入口 / Application / 编辑页宿主（含渲染协程与导出编排）
├── arw/                                    # ARW 解析（纯 Kotlin + LibRaw）
│   ├── ArwContainer.kt                     #   IFD 链遍历，择最大内嵌 JPEG 预览
│   ├── ArwFullDecoder.kt                   #   全量解码编排 + 缓存（落盘 → LibRaw / releaseCache）
│   ├── ArwPreviewDecoder.kt                #   内嵌预览解码（秒开占位图）
│   └── ArwPreviewExtractor.kt              #   预览字节提取（JVM 单测覆盖）
├── camera/                                 # Sony USB PTP 直连（11 文件）
│   ├── PtpProtocol / PtpData / PtpTransport#   协议层（事务 / 数据集 / USB 传输）
│   ├── CameraSession / CameraConnection    #   长会话（列图/拉图/批量复用，I/O 串行化）+ 体检
│   ├── CameraBatch / CameraPhoto(List|Filter) / CameraPtpReport
│   └── CameraProbe / UsbCameraScanner / UsbPermission
├── core/
│   ├── decode/                             # 解码与导出
│   │   ├── Decoder.kt                      #   路由 JPEG/HEIF/ARW + EXIF 方向烘焙（applyExifOrientation）
│   │   ├── Exporter.kt                     #   相册导出（取消双检查点 + 最小 EXIF 回写）
│   │   ├── LinearImage.kt / RawLinearSource.kt / RawNative.kt   # 16-bit 线性母版（分带）
│   ├── edit/                               # 编辑管线（15 文件）
│   │   ├── EditModel.kt                    #   EditParams(81 项)/HslMix/ColorGrading（@Serializable）
│   │   ├── EditEngine.kt                   #   三条渲染入口（sRGB/Linear/LinearFile，分带 + 取消）
│   │   ├── EditSnapshotJson.kt             #   T1：EditDocument(version+params+retouch+layers) ⇄ JSON
│   │   ├── PixelProgram / ColorMath        #   预编译逐像素程序 + 查表数学
│   │   ├── DetailPass / DetailApply        #   细节（锐化/降噪/清晰度/纹理）邻域 pass
│   │   ├── Histogram / ToneCurve           #   直方图 + 曲线锚点
│   │   ├── Mask / ObjectLayers / RetouchScale / RetouchState / EditLerp / AutoEnhance
│   │   ├── preset/Presets.kt               #   44 套分类预设（参数栈）
│   │   └── retouch/                        #   磨皮/液化/祛瑕/追色（NeutralGray/Beauty/Inpaint/ColorTransfer/RetouchLayer）
│   └── ml/                                 # LiteRT 端侧推理（13 文件）
│       ├── MlMaskProvider / MlSkinMask / SkinMaskModel / LiteRtSkinMaskModel / SkinMaskPostProcess
│       ├── ObjectMasks.kt                  #   6 类分割 → 8 作用域蒙版 + scopeAt 命中 + coveredScopes 判空
│       └── FaceDetection* / FaceAnchors / MlFaceProvider / Letterbox / FloatGrid
├── diag/DebugLog.kt                        # 结构化日志落盘 + 导出分享
└── ui/                                     # （30 文件）
    ├── shell/AppShell.kt                   #   2-Tab 工作台外壳
    ├── home/                               #   HomeScreen（Hero 卡）/ CameraPanel（直连+系留）/ DeviceCapabilities / ResolutionProfile
    ├── editor/                             #   EditorScreen（One-Surface + 双通道手势 + 拾取）/ ParamPanel / Rail（PathParser 图标引擎）/ ParamAdjust / EditorToolbar / ExportSheet
    ├── components/                         #   ParamSlider（HUD）/ CurveEditor / GlassCard / GlassChipRow / ActionTile / PresetThumbRow / GlassCircleButton
    ├── settings/                           #   SettingsScreen / AppSettings（版本化迁移）/ AboutSheet
    └── theme/                              #   Color / Type / Spacing / Radius / Glass / Motion / Theme / Backdrop（暖石墨+琥珀）
```

**依赖现状**（2026-11-07）：`androidx.core-ktx` / `activity-compose` / `lifecycle-runtime-ktx` /
Compose BOM(Material3 + animation) / **`com.google.ai.edge.litert:litert` 2.2.0** /
**`androidx.exifinterface` 1.3.7（P0-2 EXIF 层）** / **`kotlinx-serialization-json` 1.7.3 +
`kotlin.plugin.serialization` 编译器插件（T1）** / JUnit(测试)。
Hilt · Room · Coil · Navigation · ktlint · detekt · ONNX-RT 均**未引入**（规划中，引入前需本地验证）。
NDK pin `30.0.16248370`；LibRaw 子模块静态链接。

### 5.3 复用 vs 自研 决策表
| 能力 | 决策 | 理由 |
|---|---|---|
| Compose 基建 / 主题 / 设备探测 | **复用** | 脚手架已有 |
| CI 骨架 | **复用并强化** | `android.yml` 已能出包 |
| ARW 快速预览 | **自研（纯 Kotlin）** | 零 NDK、零 LibRaw，秒开 |
| ARW 全量解码 | **复用 LibRaw**（修复孤儿 native） | 成熟解码器，不重造 |
| 编辑算法（磨皮/液化/追色/祛瑕） | **P1 自研（GPU/AGSL），P3 复用 Rust 引擎** | 端侧要实时；服务端复用省事 |
| LUT 应用 | **自研**（参考 `color/lut.rs`） | 需 GPU 实时 |
| 检测/分割 | **自研端侧（TFLite）+ 复用 Rust 权重** | 权重转换后置 |
| 批量/任务队列 | **P3 复用 `scheduler`** | 已实现 |

---

## 6. 设备要求

### 6.1 平台
| 项 | 值 |
|---|---|
| minSdk | **36（Android 16）** |
| targetSdk / compileSdk | **36（Android 16；CI runner 镜像尚未发布 `platforms;android-37`，待官方发布后升 37，代码已预留非致命安装尝试）** |
| Kotlin / AGP | 2.2.21 / 8.13.2（随 API 36 对齐） |
| 构建 | JDK 17，AGSL 需 31+（已满足） |
| 分发注意 | minSdk 36 是硬门槛（仅 Android 16+ 可装）。**Q6 决策**：12 个月内不对公开渠道发布，保留 36 为自用档（调试机一加15 = Android 16+）；将来扩用户面可下调到 33/34（无 API>31 强依赖），下调成本低 |

### 6.2 真机与硬件档位
- **主力调试机**：一加15（Android 16+，骁龙 8 Elite，NPU 充足，RAM ≥12GB）。
- **相机**：Sony **A7C II / ILCE-7CM2** —— **33.0MP（有效）/ 34.1MP（总），7008×4672，14bit**；ARW 压缩 ~35–45MB / 无损 ~50–60MB / 未压缩 ~57MB。

**自适应分辨率档位**（全分辨率 RGBA_8888 ≈ **131MB**，RGBA_F16 ≈ **262MB**）：
| 设备档 | 策略 |
|---|---|
| 旗舰 ≥12GB（一加15） | 全分辨率 + RGBA_F16（~262MB，安全） |
| 中高 8–12GB | 全分辨率 RGBA_8888（~131MB），避免 F16 翻倍 |
| 中端 6–8GB | 全分辨率 RGBA_8888 + 单 Bitmap 复用、禁并行多份、导出即 recycle |
| 低 <6GB | cap long-edge 4096 或走代理图，UI 提示 |
| 代理图长边 | 旗舰 2048 / 中端 1536 / 低 1024 |

> **F05 内存重算（按 P1b 分带管线）**：全分辨率 **16-bit 线性**结果（7008×4672×3×2B ≈ **196MB**）始终留在 **native 侧**（`raw_bridge.cpp` 会话内），Kotlin 每次只 `readLinearRows` 拉 **BAND_ROWS=32** 行（≈32×7008×3×2B ≈ **1.3MB**）上色后写入目标 Bitmap。因此 Kotlin 侧峰值 ≈ 单张 8-bit 目标 Bitmap（**131MB**）+ 极小分带缓冲，**不再**同时持有 196MB+131MB+131MB 多份中间缓冲，33MP 导出峰值从 ~500MB+ 降到 ~130MB 量级，未开 `largeHeap` 也可在 ≥8GB 机型安全导出（F05）。
>
### 6.3 升级到 targetSdk 37 时需注意的变更（前瞻）
1. ≥600dp 大屏**强制自由方向** → 勿硬锁 `orientation`。
2. **`ACCESS_LOCAL_NETWORK` 变运行时权限** → **直接影响 P3 手机连 NAS / 局域网发现**。
3. `System.load()` 加载的 native 库**必须只读** → 影响 LibRaw / ONNX-RT 动态库加载。
4. 静态 final 不可反射修改、MessageQueue 锁无关实现 → 老三方库/反射有风险。

---

## 7. 交付与调试

- **CI**：强化 `android.yml` —— **unit tests 必过**（`testDebugUnitTest`）+ **Android Lint 拦门**（`lintDebug`，去掉 `continue-on-error`）；`assembleRelease` 签名（`KEYSTORE_BASE64` secret）；compileSdk/targetSdk 36。ktlint/detekt 待本地验证后引入（当前未启用，避免无本地构建下盲开导致 CI 误红）。
- **本批次（2026-09-10 复审）已落地**：① **F04** 回归修复——`ArwContainer.previewJpegRange` 主 IFD 链不再因 SubIFD 与 next 指向同一偏移而提前退出，能取到挂在链尾的全分辨率预览（7008×4672）；单测 `previewChain_picksLargest` 通过。② **F03 ①** 代理预览走 LibRaw `half_size=1 + user_qual=0` 快速解（`openLinear(halfSize=true)`），解码量约 1/4；导出母版仍全质量。③ **F08** 协作取消修复——`collectLatest` 内取 `currentCoroutineContext().job`，渲染器在分带边界真正退出（此前 `{!isActive}` 恒 false）。④ **4.3** 导出取消标志改 `AtomicBoolean`（原 `mutableStateOf` 跨线程读无语义保证）。⑤ **4.4.1** 删 `HomeScreen` 未使用导入（`AnimatedVisibility`/`ContextCompat`）。*未做*：F03 ② 两段式渐进占位交付（架构改动，留待单独批次）。
- **通知（条件化）**：签名 APK 一律作 workflow artifact（保留 90 天）+ 构建摘要通知；**仅当配置了 `FIREBASE_APP_ID` 等 secret 才额外走 Firebase App Distribution**（自动邮件 + 一键安装）。首包不依赖外部账号配置。
- **调试日志模块**（`diag/DebugLog.kt`，必须与首包同时就位）：
  - 落盘 `files/debug/log_<session>.txt`（环形，单文件 ~2MB 滚动）；
  - 格式 `yyyy-MM-dd HH:mm:ss.SSS [LEVEL] TAG: msg {kv}`，级别 DEBUG/INFO/WARN/ERROR；
  - Tags：`LIFECYCLE` `IMPORT` `DECODE`（含走内嵌预览 or LibRaw）`EDIT` `ML` `CAMERA` `API` `ERROR`；
  - 启动快照 dump `DeviceCapabilities` + 版本 + **分辨率选档结果**（便于排查 OOM）；
  - "导出调试日志" → `Downloads/` + `FileProvider` 系统分享；
  - Release 下通过隐藏手势/设置项开启。
- **密钥**：全 GitHub Secrets；本地零 `.env`；`.gitignore` 锁 keystore / `.cxx`。

---

## 8. 风险清单

| 风险 | 阶段 | 等级 | 缓解 |
|---|---|---|---|
| LibRaw 接入（NDK + 子模块 + ILCE-7CM2 机型配置） | P1b | **高** | 先在 P1a 只做 JPEG/HEIF 修图、ARW 仅预览；P1b 再接入；CI 编译耗时需评估 |
| `app/src/main/cpp/third_party/LibRaw` 子模块为空 | P1b | 中 | `git submodule add` 官方 LibRaw，确认版本含 A7C II |
| 相机 API / liveview 可用性 | P2 | 中 | 先 USB PTP 拉图 PoC；liveview（ScalarWebAPI）待验证，不行则退回"拍完拉图" |
| ONNX → TFLite 模型转换 | P1+ | **低** | **已规避**：改用 MediaPipe 现成 `.tflite` 模型（见 §3.5；模型与降级链实现见 `core/ml/`），转换链降级为可选后置项 |
| 端侧 GPEN 设备分化 | P1+ | 中 | 设备分级；弱机转 P3 |
| NAS 算力（i5-8600T + UHD630） | P3 | 中 | INT8 量化 + 低分辨率 + 批量串行 |
| targetSdk 37 破坏性变更 | 全 | 中 | 见 §6.3 逐项验证 |
| minSdk 36 分发面窄 | 全 | 低 | 自用阶段无碍；将来下调成本低 |
| LUT 许可 | P1b | 低 | 只用 MIT/CC 可再分发源，保留 LICENSE/NOTICE；DNG/XMP 不用 |
| LibRaw 静态链接合规（LGPL-2.1/CDDL-1.0） | P1b | 中 | 闭源 APK 静态链接触发 LGPL「可重新链接」义务；仓库根 `NOTICE` 已附许可与子模块 pin（`dde798dd`/`eb98e432`），分发前需复核 relink 可行性并提供对应 native 构建脚本（审查台账 F10） |

---

## 9. 文档索引（只列现行文档）

> 三层体系：`docs/*_DESIGN.md`（**设计决策**，随仓库走）→ `docs/ENGINEERING_NOTES.md`（**工程纪律**）
> → 代码 KDoc（**实现细节**）。
> **文档政策（2026-11-07）**：`docs/` 只保留当前技术路线与设计。历史设计（阶段稿 / UI2.0 备选 /
> 审计快照）已移入本地 `docs/archive/`（`.gitignore` 屏蔽）；入库过的原件可
> `git show v0.6.4:docs/<文件名>` 取回，溯源表见 `docs/archive/README.md`。
> ⚠️ 本地工作记忆（`.workbuddy/memory/`）与 `docs/archive/` 都不入库 ——
> 任何需要长期留存的结论都必须落进下表里的入库文档。

| 文档 | 作用 | 何时读 |
|---|---|---|
| [`DEV_PLAN.md`](DEV_PLAN.md) | **本文**：唯一有效计划 | 想了解「要做什么、做到哪了」 |
| [`ENGINEERING_NOTES.md`](ENGINEERING_NOTES.md) | **工程纪律**：仓库与协作约定（CI 是唯一编译器）、Compose 布局陷阱、渲染/并发纪律、构建与发版、§7~§13 历史审查整改台账 | **动代码之前必读** |
| [`UI3.0.html`](UI3.0.html) | **UI 3.0 规范（当前唯一真值来源）**：One-Surface 骨架、token 真值（`:root` 与 CSS）、Rail/Deck/顶栏规格、动效与字号红线、launcher 图标规范 | **改 UI 前先看这份** |
| [`UI_DESIGN.md`](UI_DESIGN.md) | UI 规范与**编号锚点**：§1 竞品基线 / §2 设计语言五支柱 / §5 动效 / §6 无障碍 / §8 设计决策点 / §11 UI3.0 落地对照；§4.0.x 修正表的**编号**被代码 KDoc 引用（数值已由 UI3.0 接管） | 查一个被 KDoc 引用的编号、或改动效/无障碍时 |
| [`TONING_DESIGN.md`](TONING_DESIGN.md) | 调色参数体系：参数全表 / 管线顺序 / 批次 1~4 落地记录 | 改 tonal 参数或管线顺序时 |
| [`OBJECT_TONE_DESIGN.md`](OBJECT_TONE_DESIGN.md) | 对象作用域调色（批次 5）：作用域全表 / 数据模型 / 单趟逐层 lerp / 哪些参数不能进层 / 验收方式 | 改对象图层或蒙版作用域时 |
| [`ONDEVICE_MODEL_PLAN.md`](ONDEVICE_MODEL_PLAN.md) | 端侧模型规划：P6/P7 候选模型表 + 体积延迟预算 + 接入架构 | 接入新端侧模型时 |
| [`Github_CI.md`](Github_CI.md) | 最近一次 CI 运行的完整报告（**每次 CI 后覆盖重写**，工具性文档） | 想知道上一次 CI 红/绿与原因 |
| `docs/archive/` | **本地历史存档（不入库）**：`PLAN.md` 等最早规划 + `PHASE_DESIGN_HISTORY.md`（KDoc 编号 `F01~F24`/`R01~R10`/`D01~D10` 溯源处）+ `UI2.0.html` + 两份外部审计报告 | 追历史决策或审计证据链时（取回命令见其 README） |

## 10. 当前版本与待验收

- **当前版本**：`v0.7.0`（`versionCode = 22`）。**发版四者必须同时改**：`build.gradle.kts` 的
  `versionCode`/`versionName` → git tag → `AboutSheet` 显示 → CI 报告记录。
  ⚠️ 任何「记忆里的版本号 / 尺寸」用前先 `git grep` 复核（`ENGINEERING_NOTES.md` §5.5）。
- **待真机验收（至今未闭环）**：A2 涂抹落点 / A1 导出内存日志 / 自动蒙版（P1p-1c）/ 人脸锚点（P1p-2c）/
  A7C2 直连（P2）/ 批次 3 预览↔导出一致性 / 批次 4 锐化必须晚于磨皮 /
  批次 5 的 6 条 / 预览缩放手势 / UI3.0 逐屏（`UI_DESIGN.md` §11 已知偏离）；
  **v0.6.4 新增（hy4 整改判据，`ENGINEERING_NOTES.md` §13.1）**：
  `Orientation=6` 竖拍片进 App 是正的、导出也是正的（同名 ARW/JPEG 方向一致）/
  导出中点取消后相册**没有**该文件 / 卡上有旧片时打开系留**不产生任何下载** /
  混有目录对象时轮询仍能推进 / 拖滑块时直方图与画面同代、GC 抖动消失 /
  图里没有的对象作用域 chip 呈禁用态。
- **未启动**：P3（NAS Docker 化 Rust 引擎）；端侧模型 P6/P7（`ONDEVICE_MODEL_PLAN.md`）。
