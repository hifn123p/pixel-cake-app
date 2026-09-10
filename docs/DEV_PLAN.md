---
title: 像素蛋糕 App — 开发计划（唯一有效文档 v3.0）
status: active
created: 2026-09-05
updated: 2026-09-11
project: D:\AI_Project
replaces: [PLAN.md, FEASIBILITY_REPORT.md, P1_MVP_DESIGN.md, PLAN_REVIEW.md]
description: 像素蛋糕（AI 人像精修）安卓应用的唯一开发计划：项目介绍、功能、核心原理、技术路线、代码架构（复用清单含具体路径 / 新开发模块）、设备要求、交付调试与风险。旧规划文档已归档至 docs/archive/。
---

# 像素蛋糕 App — 开发计划（v3.0）

> **本文是本项目唯一有效的开发计划**，已合并此前的 `PLAN.md` / `FEASIBILITY_REPORT.md` / `P1_MVP_DESIGN.md` / `PLAN_REVIEW.md`（归档在 `docs/archive/`，仅供追溯，不再维护）。
> v3.0 相对之前的关键变更：
> 1. **ARW 修图必须走全量 RAW**（LibRaw 真解马赛克），内嵌预览**只用于打开/快速预览**，不作为修图数据源。
> 2. **P1 拆为 P1a / P1b**（先拿首个真机 APK，再堆完整人像功能）。
> 3. **SDK：minSdk 36；compileSdk / targetSdk 暂对齐 36**（CI runner 的 SDK 仓库截至 2026-08 镜像尚未发布 `platforms;android-37`，`sdkmanager` 在 stable 与 canary 均 `Failed to find package`，故暂降到 36；待官方发布 API 37 平台后改回 37，代码已预留非致命的 37 安装尝试）。
> 4. 修正 A7C II 规格（33MP / 7008×4672 / 14bit）与 ARW 体积，重算内存与自适应档位。
> 5. 删除"16-bit 导出"过度承诺：内部 16-bit 保精度，**导出最高 8/10-bit**。

---

## 0. 当前进度（2026-09-09）

| 阶段 | 状态 | 说明 |
|---|---|---|
| M0a | ✅ | SDK 升 36 + CI 出首个可装 APK + DebugLog 模块 |
| M0b | ✅ | ARW 内嵌 JPEG 预览解码（纯 Kotlin TIFF/IFD，零 NDK） |
| P1a | ✅ | 最小编辑链路 → 首个真机可测 APK（导入/曝光·曲线·LUT/导出/日志） |
| P1b | ✅ | **LibRaw 全量解码**（子模块 `third_party/LibRaw`[master `dde798dd`] + LibRaw-cmake[`eb98e432`]，静态链接；`raw_bridge.cpp` 全量解马赛克→RGBA；`useLibRaw=true`，失败回退预览）。**人像算子全量落地**：NeutralGray / Beauty / Inpaint / ColorTransfer 均落 `core/edit/retouch/`，由 `RetouchLayer` 单趟 getPixels 按「磨皮→液化→祛瑕→追色」编排；retouch 整图 pass 已接到 RAW 与 JPEG/HEIF 的预览 + 全分辨率导出四条路径（复用目标 Bitmap，符合 F05）。编辑器：工具选择（皮肤/祛瑕）、美型三滑块、追色风格+强度、**10 套参数栈预设**（`core/edit/preset/Presets.kt`）。**2026-09-11 批次**：撤销/重做升级为 `EditSnapshot`（tonal + retouch 同步回退）、画笔描迹节流+条数上限、打开大图加载进度反馈、「重置全部」+ 预设选中态。单测：NeutralGray / Beauty / Inpaint / ColorTransfer / RasterMask / Presets / EditHistory。**剩**：P1b-6 真机统一测试（用户侧 A7C2 实拍验收）。 |
| P1+ / F03② | ⬜ | **P1+** ML 自动蒙版（SCRFD / 2DFAN4 / BiSeNet → TFLite + NNAPI）待启动（`RetouchMask` 接口已预留，接入 UI 零改动）。**F03②**「代理秒进」的感知延迟已用「打开即显解码进度 + 预览本就走 `halfSize` 代理」缓解；「后台母版无缝切换」为可选画质优化，后置。 |
| P2 / P3 | 🔧 / 🔜 | **P2**（进行中）：A7C2 USB 直连 —— 设计稿 `docs/P2_DESIGN.md`；**PoC-1 USB 检测已落地**（`camera/CameraProbe` + `camera/UsbCameraScanner` + 首页 `CameraPanel`：免权限枚举设备 + Sony VID/接口类→USB 模式识别 + `CAMERA` 日志 + 纯 JVM 单测）。后续 PoC-2→4：权限 → PTP 会话 → 枚举/拉图 → **复用 P1 管线套预设**（`Presets.ALL` 已就绪）。**P3**：NAS Docker 化 Rust 引擎。 |

> LibRaw master API 注意：已移除 `dcraw_free()`；`dcraw_make_mem_image()` 的返回产物必须用 `LibRaw::dcraw_clear_mem()` 释放，`free_image()` 只释放内部 `imgdata.image`、二者不可混用（见 F02 / D09）；Kotlin `val version` 与 native `getVersion()` JVM 签名冲突，已改名 `librawVersion`（详见 §8 风险表与每日日志 2026-09-09）。

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
| 代理图实时预览（CPU 多线程逐像素 band 渲染；GPU/RenderEffect/AGSL 规划中） | ⬜ | | | |
| 导出到相册（JPEG / PNG；HEIF 需探测） | ✅ | | | |
| 自适应导出分辨率（按设备档位） | ✅ | | | |
| 调试日志模块（落盘 + 导出分享） | ✅ | | | |
| 中性灰磨皮 / 美型液化 / 祛瑕 / 追色 | | ✅ | | |
| AGSL 局部调整 + 手动画笔蒙版 | | ✅ | | |
| 内置人像预设（~10 套：参数栈 + `.cube` LUT） | | ✅ | | |
| A7C2 直连（USB PTP 拉图）+ 预设套用 | | | ✅ | |
| 边拍边预览（liveview，需 PoC） | | | ✅ | |
| NAS 目录控制 / 单张·批量后台修图 | | | | ✅ |
| ML 自动蒙版（人脸/关键点/分割） | | 后置 | | |

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

### 3.5 轻量 ML（后置增强）
SCRFD(人脸) / 2DFAN4(关键点) / BiSeNet(分割) 经 **TFLite + NNAPI delegate**（NNAPI→GPU→CPU 降级）产出 skin/portrait 蒙版。**P1 首版后置**，先用手动画笔蒙版跑通链路，避开"ONNX→TFLite 模型转换"风险。

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
      中性灰磨皮 / 液化 / 祛瑕 / 追色 / 局部 + 内置预设 ~10 套
P1+  ML 自动蒙版（需 ONNX→TFLite）
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

### 5.2 新开发模块（代码架构）

```
com.hifn.pixelcake
├── MainActivity.kt · PixelCakeApp.kt        # 入口与 Application（已实现）
├── arw/                                      # 【已建】ARW 解析
│   ├── ArwContainer.kt                       #   IFD 链遍历，择最大内嵌 JPEG 预览（F04）
│   ├── ArwFullDecoder.kt                     #   全量解码编排 + 缓存清理（F21）
│   ├── ArwPreviewDecoder.kt                  #   内嵌预览解码（纯 Kotlin，零 NDK）
│   └── ArwPreviewExtractor.kt                #   预览字节提取（JVM 单测覆盖）
├── core/
│   ├── decode/                               # 【已建】解码入口
│   │   ├── Decoder.kt                        #   按扩展名路由 JPEG/HEIF/ARW；ARW 走 LibRaw 线性
│   │   ├── RawNative.kt                      #   LibRaw JNI 封装（openLinear/readLinearRows/closeLinear）
│   │   ├── LinearImage.kt                    #   【新·P1b】16-bit 线性图数据类
│   │   ├── RawLinearSource.kt                #   【新·P1b】分带读取封装
│   │   └── Exporter.kt                       #   导出（死代码已清，F20）
│   ├── edit/                                 # 【已建】编辑管线
│   │   ├── EditModel.kt                      #   编辑参数模型
│   │   ├── ColorMath.kt                      #   线性↔sRGB 查表（processPixel 已移除，F06）
│   │   ├── PixelProgram.kt                   #   【新·P1b】预编译 WB×曝光标量增益 + sRGB LUT
│   │   └── EditEngine.kt                     #   分带渲染（renderIntoSrgb/renderIntoLinear/renderLinearFile）
│   ├── ml/                                   # 【规划未建·P1+】FaceDetector/Landmarker/Segmenter
│   ├── render/                               # 【规划未建】PreviewPipeline（GPU/RenderEffect/AGSL）
│   └── model/                                # 【部分】EditParams 等；Photo/Preset/Project 规划中
├── data/                                     # 【规划未建】Room 历史/预设缓存；preset/ .cube LUT
├── ui/
│   ├── home/                                 # 【已建】HomeScreen · DeviceCapabilities · ResolutionProfile
│   ├── theme/                                # 【已建】Color/Theme/Type
│   ├── editor/EditorScreen.kt                # 【已建】画布 + 滑块节流（F08）
│   ├── gallery/                              # 【规划未建】相册 + ARW 选择
│   └── export/                               # 【规划未建】导出对话框
├── diag/DebugLog.kt                          # 【已建·必须随首包】结构化日志落盘 + 导出分享
└── util/                                     # 【规划未建】
```
> 注：**已建** = 当前仓库真实存在并可编译的模块；**规划未建** = v3.0 路线图中尚未落地的部分（编辑栈 EditStack/ops、ML 蒙版、GPU 预览、Room、相册/导出 UI、Hilt 依赖注入等）。Hilt / Room / Coil / Navigation / ktlint / detekt 截至本版**均未引入**——避免「逼代码去凑计划」，故按现状登记（D04）。

**依赖现状**：截至本版仅引入 `androidx.core-ktx` / `activity-compose` / `lifecycle-runtime-ktx` / Compose BOM(Material3) / JUnit(测试)。Hilt · Room · Coil · Navigation · TFLite · ktlint · detekt · ONNX-RT 均**未引入**（规划中，引入前需本地验证）。

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
| 分发注意 | minSdk 36 是硬门槛（仅 Android 16+ 可装）；将来扩用户面可下调（无 API>31 强依赖） |

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
| ONNX → TFLite 模型转换 | P1+ | 中 | 后置，先用画笔蒙版 |
| 端侧 GPEN 设备分化 | P1+ | 中 | 设备分级；弱机转 P3 |
| NAS 算力（i5-8600T + UHD630） | P3 | 中 | INT8 量化 + 低分辨率 + 批量串行 |
| targetSdk 37 破坏性变更 | 全 | 中 | 见 §6.3 逐项验证 |
| minSdk 36 分发面窄 | 全 | 低 | 自用阶段无碍；将来下调成本低 |
| LUT 许可 | P1b | 低 | 只用 MIT/CC 可再分发源，保留 LICENSE/NOTICE；DNG/XMP 不用 |
| LibRaw 静态链接合规（LGPL-2.1/CDDL-1.0） | P1b | 中 | 闭源 APK 静态链接触发 LGPL「可重新链接」义务；仓库根 `NOTICE` 已附许可与子模块 pin（`dde798dd`/`eb98e432`），分发前需复核 relink 可行性并提供对应 native 构建脚本（F10） |
