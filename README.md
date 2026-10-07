<div align="center">

# 🍰 像素蛋糕 PixelCake

**安卓端 AI 人像精修 App** — 对标像素蛋糕，定位类似 Snapseed 但专攻人像

[![Platform](https://img.shields.io/badge/Platform-Android%2016%2B-3DDC84?logo=android)](https://developer.android.com)
[![minSdk](https://img.shields.io/badge/minSdk-36-blue)](https://developer.android.com)
[![targetSdk](https://img.shields.io/badge/targetSdk-36-blue)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.21-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3%204285F4?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Version](https://img.shields.io/badge/version-v0.4.6-success)](https://github.com/hifn123p/pixel-cake-app/releases)
[![Build](https://img.shields.io/badge/Build-GitHub%20Actions-2088FF?logo=githubactions)](https://github.com/features/actions)
[![License](https://img.shields.io/badge/License-TBD-lightgrey)](#许可)

</div>

---

## 📖 项目简介

在手机上做**人像精修**：导入相册照片（JPEG / HEIF / Sony ARW）→ 非破坏式调色与美化 → 导出成片。

后续会扩展到：连接 Sony A7C II **边拍边看成片**，以及把修图任务交给家里 NAS **批量后台处理**。

- **参考对象**：像素蛋糕（商业人像精修软件）、Snapseed（交互形态）
- **算力策略**：日常编辑**全在手机端**（CPU 分带管线 + LiteRT 端侧推理），只有「重 AI 增强 / 批量 / 老设备」才唤醒 NAS 上的 Rust 引擎
- **开发方式**：本机**不装任何开发环境**，代码全部推送 GitHub，由 **GitHub Actions** 完成编译/检查/测试/打包

> ⚠️ **接手者请先读这一条**：本仓库**没有**本地构建环境 —— **CI 是唯一的编译器**。
> 本地能做的静态检查只有「模板感知的括号配平」+「跨文件 import / 调用点核对」两道闸门，
> 它们只能证明语法树闭合，**证明不了名字解析**。
> 相关纪律与历史踩坑全部整理在 [`docs/ENGINEERING_NOTES.md`](docs/ENGINEERING_NOTES.md)，**改代码前请先读它**。

> 完整开发计划见 [`docs/DEV_PLAN.md`](docs/DEV_PLAN.md)（项目唯一有效计划文档）；
> 全部文档索引见本文末尾 [📄 文档](#-文档)。

---

## ✨ 特性

### 已实现

| 模块 | 说明 |
|---|---|
| 🖼 导入与解码 | Photo Picker / SAF；JPEG、HEIF；Sony ARW 内嵌全分辨率预览（纯 Kotlin TIFF/IFD 链遍历）与 LibRaw 全量解码（16-bit 线性） |
| 🎚 非破坏编辑 | 白平衡、影调（曝光/对比/高光/阴影/白点/黑点/高光恢复）、偏好（自然饱和/饱和/去雾）、曲线、HSL 八通道、彩色分级、LUT、细节与效果（锐化/降噪/清晰度/纹理/暗角/颗粒）；撤销/重做、长按对比 |
| 🎨 调色预设 | **44 套**分类预设（参数栈），按当前照片生成真实缩略图；套用后可继续微调 |
| 🧴 人像精修 | 皮肤画笔与 AI 肤色蒙版、磨皮、美型液化（人脸锚点）、祛瑕、追色 |
| 🪄 对象作用域调色 | **批次 5**：同一套调色参数可作用在「整图」或识别到的对象（8 个作用域 = 模型原生 6 类 + 2 个派生），图层栈可叠加 |
| 🤖 端侧 ML | LiteRT `CompiledModel`（GPU→CPU 级联、永不崩的降级链）：6 类人体分割染色蒙版 + 人脸框/关键点，模型随包内置 |
| 📷 相机直连 | Sony USB PTP 探测、浏览、下载（256KB 流式分块）；单张导入 + 批量套预设导出，导完即删 |
| 💾 导出 | JPEG / PNG；RAW 全分辨率分带渲染，非 RAW 按设备档位选分辨率；导出成功后按钮切换形态 |
| 📊 工具 | 曲线编辑器（黑场/中间调/白场锚点）、直方图 |
| 🐞 调试日志 | 结构化端侧日志（`files/debug/log_<session>.txt`），可通过系统分享导出 |

### 规划中

- ☁️ **P3**：NAS 后台引擎 —— Docker 化 Rust 服务与任务队列（手机控 NAS、单张/批量后台修图）
- 🎨 用户预设库：保存/管理自定义参数栈，并在相机批处理中复用
- ⚡ GPU/AGSL 预览管线（当前预览是 CPU 多线程分带管线）
- 📷 相机「边拍边看」（liveview）评估 —— 需 Sony CRSDK / ScalarWebAPI，当前 PTP 能力以传输照片为主

---

## 🏗 技术栈

**客户端（主）**

- Kotlin 2.2.21 · Jetpack Compose + Material3（Compose BOM `2025.11.01`）· AGP 8.13.2 · JDK 17
- AndroidX Core / Activity Compose / Lifecycle Runtime；Compose UI、Material3、Animation
- 端侧推理：**LiteRT** `com.google.ai.edge.litert:litert`（`CompiledModel`，GPU→CPU 回退），模型随包内置
- 预览渲染：CPU 多线程分带像素管线（`BAND_ROWS = 32`，双缓冲提交）；RAW 编辑使用 LibRaw NDK/JNI 的 16-bit 线性数据
- RAW 快速预览：纯 Kotlin TIFF/IFD 解析内嵌 JPEG
- NDK **pin 在** `30.0.16248370`（CI 装同一版本，保证可复现）

**体积与响应策略**：Release 已启用 R8 / 资源收缩、限定 `arm64-v8a` 并过滤语言资源；RAW/像素编辑采用代理预览、分带读写与双缓冲。分割模型 **16.37 MB** + 人脸检测模型 **0.68 MB**，用于端侧 mmap 因而在 `noCompress` 中排除压缩。**继续明显缩包需量化 / 蒸馏分割模型 —— 在没有真机画质基准前，不应以牺牲蒙版质量换体积。**

**服务端（辅，P3）**

- Rust · `axum` · `ort`（ONNX：OpenVINO / Vulkan / CPU）· `rusqlite` · Docker（飞牛 FnOS）

---

## 🧩 架构

### 代码结构

```
com.hifn.pixelcake
├── arw/           ARW 容器解析（IFD 链择最大预览）、内嵌预览提取、全量解码编排
├── core/
│   ├── decode/    JPEG/HEIF、RAW 线性解码（RawLinearSource 分带）与导出
│   ├── edit/      参数管线（PixelProgram/ColorMath）、对象图层、预设、人像算子（retouch/）
│   └── ml/        LiteRT 皮肤/对象分割与人脸检测；FloatGrid / 掩码 / Provider 与降级链
├── camera/        USB PTP（PtpProtocol/PtpData/PtpTransport）、长会话、批量处理
├── ui/            shell（2-Tab 外壳）· home（调色台/相机面板）· editor（五段式编辑器）
│                  · components（玻璃控件）· settings · theme（Token/玻璃/动效/静态模糊底图）
└── diag/          DebugLog（结构化日志 + 导出分享）
```

### 复用 vs 自研

| 来源 | 复用内容（路径） |
|---|---|
| `pixel-cake-android` | `ui/home/DeviceCapabilities.kt`（设备探测）· `ui/home/HomeScreen.kt` · `arw/ArwContainer.kt` · `MainActivity.kt` · `ui/theme/` · `cpp/raw_bridge.cpp`（LibRaw JNI，已接入）· `.github/workflows/android.yml` |
| `pixel-cake`（Rust） | `engine/src/retouch/{neutral_gray,beauty,color_transfer,inpaint,enhance}.rs` · `engine/src/detect/{face,landmark,segment}.rs` · `engine/src/raw.rs` · `engine/src/color/lut.rs` · `crates/scheduler`（P3 任务队列） |

- **复用**：Compose 基建、设备探测、CI 骨架、LibRaw、Rust 修图算法（P3）
- **自研**：非破坏编辑栈、预览渲染（CPU 分带 + 双缓冲）、ARW 内嵌预览解析、PTP/MTP 客户端、LUT 应用、预设系统、对象作用域图层、调试日志

### 核心原理

1. **双轨分辨率**：编辑只作用于**参数栈**（~1KB）；预览跑代理图（长边 1024–2048），导出才物化全分辨率。改滑块 = 改参数 + 重跑预览链，**绝不重解 RAW**。
2. **渲染的双缓冲与协作取消**：预览分带写、每 32 行提交一次；正在显示的位图**只读**，写入目标与显示目标两块互相轮换。「这一批画完了没有」由渲染结果**显式**带出（`completed`），与「用户还在不在看这张图」（`imageEpoch`）是**正交**的两个判据。
3. **ARW 双路径**：
   - *打开/预览* → 内嵌全分辨率 JPEG 预览（7008×4672 位于 **IFD 链第 3 个 IFD**，约 1.9MB；纯 Kotlin 沿 `next` 指针遍历后择最大，零 NDK）
   - *修图* → **LibRaw 全量真解马赛克 → 16-bit 线性**（内嵌预览已烘焙白平衡/曲线，没有修图宽容度）。16-bit 母版留 native，Kotlin 每次只拉 32 行上色
4. **对象作用域 = 单趟逐层 lerp**：每个作用域至多一层，参数按「叠加量」语义合并；暗角（画面几何）与颗粒（整幅确定性噪声）**不允许**进层。
5. **预设 = 参数栈 + LUT**：热门胶片风用自研参数栈；精确胶片模拟用 MIT/CC 可再分发 `.cube`。

---

## 🗺 路线图

| 阶段 | 目标 | 状态 |
|---|---|---|
| **M0a** | SDK 升 minSdk36 + CI 出首个可装 APK + DebugLog | ✅ |
| **M0b** | ARW 内嵌预览解码（纯 Kotlin，零 NDK） | ✅ |
| **P1a** | 最小可用编辑链路 → **首个真机可测 APK**（导入/曝光·曲线·LUT/导出/日志） | ✅ |
| **P1b** | 人像修图（磨皮/液化/祛瑕/追色）、RAW 全量 16-bit 编辑与预设 | ✅（真机验收持续进行） |
| **P1+** | LiteRT 自动皮肤蒙版与人脸关键点 | ✅（真机效果仍需验收） |
| **批次 1~5** | 调色参数体系（81 项）+ 对象作用域图层 | ✅ |
| **UI v2.0** | 玻璃化的工作台外壳与五段式编辑器 | ✅ |
| **P2** | Sony USB PTP 传输与批量预设处理 | ✅（真机兼容性 / liveview 仍需验证） |
| **P3** | NAS Docker 化 + HTTP API，单张/批量后台修图 | 🔜 |

### 版本历史

| 版本 | 内容 |
|---|---|
| `v0.4.6` | ML 缓存键改为**会话代次**（修「同 URI 更新后误用旧人脸/分割」）+ 渲染提交判据补 `completed`；chip/预设改按宽度换行；控件尺寸回调（一级分类 48dp、滑块拇指 20dp） |
| `v0.4.5` | 细部位美容 + 44 套分类预设 + 追色 mono 去色 + 曲线/直方图工具 |
| `v0.4.4` | UI Redesign v2.0 |
| `v0.4.0` / `v0.4.1` | 调色批次 1~4（81 项）/ 批次 5 对象作用域 |

（`v0.1.0` → `v0.4.6` tag 齐全，Release 页可下载签名 APK。）

---

## 📱 设备要求

- **Android 16+（API 36）**，targetSdk / compileSdk **36**（暂对齐；CI runner 未发布 `platforms;android-37`，待官方发布后升 37）
- 推荐 **RAM ≥ 8GB**；全分辨率 33MP 解码：RGBA_8888 ≈ 131MB、RGBA_F16 ≈ 262MB
- 自适应档位：≥12GB 全分辨率+F16 ｜ 8–12GB 全分辨率 RGBA_8888 ｜ 6–8GB 全分辨率+单 Bitmap 复用 ｜ <6GB 降至长边 4096
- 调试真机：**一加15**（骁龙 8 Elite）；相机：**Sony A7C II**（33MP / 7008×4672 / 14bit）

---

## 🔨 构建与分发

> 本机**无需**任何 Android 开发环境。

```bash
git push origin main        # 推送后 GitHub Actions 自动接管
```

`.github/workflows/android.yml` 会依次执行：

1. `testDebugUnitTest`（单元测试 **拦门**）
2. `lintDebug`（Android Lint **拦门**，无 `continue-on-error`）
3. `assembleRelease`（`KEYSTORE_BASE64` secret 签名）
4. 上传 artifact（保留 90 天）+ 写 `$GITHUB_STEP_SUMMARY`
5. **仅在打 tag 时**触发 `Publish GitHub Release`

ktlint / detekt **未引入** —— 无本地构建环境时盲开容易让 CI 误红，待本地验证后再加。

- **子模块**：LibRaw 经 git 子模块引入，CI 用 `actions/checkout` 递归拉取；本机首次构建前执行 `git submodule update --init --recursive`（走 SSH 可避开代理证书问题）。
- **通知策略**：签名 APK 一律作 workflow artifact；配置 `FIREBASE_APP_ID` 等 secret 后才额外走 Firebase App Distribution（邮件 + 一键安装）。
- **密钥**：全部存放 GitHub Secrets，仓库内零 `.env`。debug keystore 入库（PKCS12 / `android`），release 走 Secrets。

### 发版一致性

`app/build.gradle.kts` 的 `versionCode` / `versionName`、git tag、`AboutSheet` 显示、CI 报告记录
**四者必须同时改**，不一致就是发版事故。

---

## 🐞 调试

App 内置调试日志（`diag/DebugLog.kt`）：

- 落盘 `files/debug/log_<session>.txt`，单文件 ~2MB 滚动
- 格式：`yyyy-MM-dd HH:mm:ss.SSS [LEVEL] TAG: msg {kv}`
- TAG：`LIFECYCLE` `IMPORT` `DECODE` `EDIT` `ML` `CAMERA` `API` `ERROR`
- 启动即 dump 设备能力 + 版本 + 分辨率选档结果；ML 段额外 dump「LiteRT 版本 / 实际选中的 accelerator / 模型 sha256 前 8 位」
- 编辑页可「导出调试日志」→ 系统分享

真机复现问题后把日志回传，即可定位。

---

## 📄 文档

| 文档 | 作用 |
|---|---|
| [`docs/DEV_PLAN.md`](docs/DEV_PLAN.md) | **开发计划（唯一有效）**：项目介绍 / 功能矩阵 / 核心原理 / 技术路线 / 代码架构 / 设备要求 / 风险 |
| [`docs/ENGINEERING_NOTES.md`](docs/ENGINEERING_NOTES.md) | **工程笔记（改代码前必读）**：仓库与协作约定、Compose 布局陷阱、渲染/并发纪律、构建与发版。**每一条都是被真机 bug 逼出来的** |
| [`docs/UI_DESIGN.md`](docs/UI_DESIGN.md) | UI 设计规范 + Token + **每轮真机反馈修正表（§4.0.x）** |
| [`docs/TONING_DESIGN.md`](docs/TONING_DESIGN.md) | 调色参数体系：参数全表、管线顺序、分批实施与落地记录（批次 1~4） |
| [`docs/OBJECT_TONE_DESIGN.md`](docs/OBJECT_TONE_DESIGN.md) | 对象作用域调色（批次 5）：作用域全表、数据模型、单趟逐层 lerp、验收方式 |
| [`docs/PHASE_DESIGN_HISTORY.md`](docs/PHASE_DESIGN_HISTORY.md) | **阶段设计存档**：P1b / P1+ / P2 三阶段设计 + 审查修复台账（`F01~F24` / `R01~R10` / `D01~D10` 编号溯源）。代码 KDoc 直接引用这些编号 |
| [`docs/Github_CI.md`](docs/Github_CI.md) | 最近一次 CI 运行的完整报告（**每次 CI 后覆盖重写**，工具性文档） |
| [`docs/ui_preview.html`](docs/ui_preview.html) | 四个界面的静态预览 + token 表（无法本地构建时的版式评审手段） |
| `docs/archive/` | 本机历史存档：P1 之前的 4 份规划稿。**被 `.gitignore` 屏蔽 ⇒ 不上传 GitHub**（仓库内还有一份同名目录的说明写在它自己的 README 里，克隆者看不到） |

> 三层文档体系：`docs/*_DESIGN.md`（设计决策，入库）→ `docs/ENGINEERING_NOTES.md`（工程纪律）→ 代码 KDoc（实现细节）。
> 本地工作记忆（`.workbuddy/memory/`）不入库，需要长期留存的内容一律提炼进上面的入库文档。

---

## 🙏 致谢

- 现成的 Rust 修图引擎 `pixel-cake`（算法与模型复用）
- 端侧模型：[MediaPipe](https://developers.google.cn/edge/mediapipe/solutions/vision/image_segmenter) `selfie_multiclass_256x256` 与 `face_detection_full_range_sparse`（Apache-2.0）
- LUT 来源（仅使用 MIT / CC 可再分发部分，许可见各资源 LICENSE）：`shravankumar147/photo-edit-app`、`mv-lab/NILUT`
- RAW 解码：[LibRaw](https://www.libraw.org/)

## 许可

App 主体许可待定（TBD）；内置静态链接的 LibRaw（LGPL-2.1/CDDL-1.0）许可与「可重新链接」义务见仓库根 `NOTICE`。内置第三方 LUT / 模型单独标注许可并保留 NOTICE。
