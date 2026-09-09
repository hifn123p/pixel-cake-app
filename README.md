<div align="center">

# 🍰 像素蛋糕 PixelCake

**安卓端 AI 人像精修 App** — 对标像素蛋糕，定位类似 Snapseed 但专攻人像

[![Platform](https://img.shields.io/badge/Platform-Android%2016%2B-3DDC84?logo=android)](https://developer.android.com)
[![minSdk](https://img.shields.io/badge/minSdk-36-blue)](https://developer.android.com)
[![targetSdk](https://img.shields.io/badge/targetSdk-36-blue)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.21-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Build](https://img.shields.io/badge/Build-GitHub%20Actions-2088FF?logo=githubactions)](https://github.com/features/actions)
[![License](https://img.shields.io/badge/License-TBD-lightgrey)](#许可)

</div>

---

## 📖 项目简介

在手机上做**人像精修**：导入相册照片（JPEG / HEIF / Sony ARW）→ 非破坏式调色与美化 → 导出成片。

后续会扩展到：连接 Sony A7C II **边拍边看成片**，以及把修图任务交给家里 NAS **批量后台处理**。

- **参考对象**：像素蛋糕（商业人像精修软件）、Snapseed（交互形态）
- **算力策略**：日常编辑**全在手机端**（GPU + NPU），只有"重 AI 增强 / 批量 / 老设备"才唤醒 NAS 上的 Rust 引擎
- **开发方式**：本机**不装任何开发环境**，代码全部推送 GitHub，由 **GitHub Actions** 完成编译/检查/测试/打包

> 完整开发计划见 [`docs/DEV_PLAN.md`](docs/DEV_PLAN.md)（项目唯一有效计划文档）。

---

## ✨ 特性

### 已完成规划 / 开发中
| 模块 | 说明 |
|---|---|
| 🖼 导入 | 相册 Photo Picker；JPEG / HEIF 原生解码；**ARW 支持** |
| ⚡ ARW 快速预览 | 解析 ARW 内嵌的全分辨率 JPEG 预览（7008×4672），**纯 Kotlin 零 NDK**，秒开 |
| 🎚 调色 | 曝光 / 曲线 / LUT，非破坏编辑栈，可撤销重做、原图对比 |
| 🚀 实时预览 | 代理图 + GPU 链（RenderEffect / AGSL），滑块拖动 <16ms/帧 |
| 💾 导出 | 写回相册（JPEG / PNG；HEIF 视设备编码器），按设备档位自适应分辨率 |
| 🐞 调试日志 | 内置结构化日志，可导出分享——无本地构建环境下的唯一联调回路 |
| 📷 ARW 全量修图 | LibRaw NDK 真解马赛克 → 16-bit 线性（P1b-1/2/3 已接入并启用；人像算子 / 预设见 P1b-4/5） |

### 规划中
- 🧴 人像精修：中性灰磨皮 / 美型液化 / 祛瑕 / 追色 / AGSL 局部
- 🎨 内置人像预设 ~10 套（参数栈 + `.cube` 电影/胶片 LUT）
- 🤖 ML 自动蒙版：人脸检测 / 关键点 / 分割（TFLite + NNAPI）
- 📡 A7C2 相机直连：USB PTP 拉图 + 套预设 + 边拍边看
- ☁️ 云端 NAS：Docker 化 Rust 引擎 + HTTP API，单张/批量后台修图

---

## 🏗 技术栈

**客户端（主）**
- Kotlin 2.2.21 · Jetpack Compose + Material3 · AGP 8.13.2 · JDK 17
- Hilt · Room · Coil · Navigation · Coroutines
- GPU 实时预览：RenderEffect / **AGSL**
- 端侧推理（后置）：TensorFlow Lite + **NNAPI** delegate（NNAPI → GPU → CPU 降级）
- RAW：LibRaw（NDK / JNI）+ 纯 Kotlin TIFF/IFD 解析

**服务端（辅，P3）**
- Rust · `axum` · `ort`(ONNX: OpenVINO / Vulkan / CPU) · `rusqlite` · Docker（飞牛 FnOS）

---

## 🧩 架构

### 代码结构

```
com.hifn.pixelcake
├── core/
│   ├── decode/    Decoder · ArwPreviewDecoder · RawNative(LibRaw) · ExportResolver
│   ├── edit/      EditStack + ops/(曝光·曲线·LUT·AGSL·中性灰·液化·祛瑕·追色) + mask/
│   ├── ml/        FaceDetector · Landmarker · Segmenter   (后置)
│   ├── render/    PreviewPipeline（EditStack → GPU 节点图）
│   └── model/     Photo · EditOperation · Preset · Project
├── data/          Room · preset/(参数栈 json + .cube LUT)
├── ui/            home · gallery · editor · export
└── diag/          DebugLog（结构化日志 + 导出分享）
```

### 复用 vs 自研

| 来源 | 复用内容（路径） |
|---|---|
| `pixel-cake-android` | `ui/home/DeviceCapabilities.kt`（设备探测）· `ui/home/HomeScreen.kt` · `raw/RawInfo.kt` · `MainActivity.kt` · `ui/theme/` · `cpp/raw_bridge.cpp`（LibRaw JNI，已接入）· `.github/workflows/android.yml` |
| `pixel-cake`（Rust） | `engine/src/retouch/{neutral_gray,beauty,color_transfer,inpaint,enhance}.rs` · `engine/src/detect/{face,landmark,segment}.rs` · `engine/src/raw.rs` · `engine/src/color/lut.rs` · `crates/scheduler`（P3 任务队列） |

- **复用**：Compose 基建、设备探测、CI 骨架、LibRaw、Rust 修图算法（P3）
- **自研**：非破坏编辑栈、GPU 预览链、ARW 内嵌预览解析、LUT 应用、预设系统、调试日志

### 核心原理

1. **双轨分辨率**：编辑只作用于**参数栈**（JSON ~1KB）；预览跑代理图（长边 1024–2048），导出才物化全分辨率。改滑块=改参数+重编译预览链，**绝不重解 RAW**。
2. **ARW 双路径**：
   - *打开/预览* → 内嵌全分辨率 JPEG 预览（纯 Kotlin 解析 TIFF/IFD tag `0x0201`/`0x0202`，零 NDK）
   - *修图* → **LibRaw 全量真解马赛克 → 16-bit 线性**（内嵌预览已烘焙白平衡/曲线，没有修图宽容度）
3. **预设 = 参数栈 + LUT**：热门胶片风用自研参数栈；精确胶片模拟用 MIT/CC 可再分发 `.cube`。

---

## 🗺 路线图

| 阶段 | 目标 | 状态 |
|---|---|---|
| **M0a** | SDK 升 minSdk36 + CI 出首个可装 APK + DebugLog | ✅ |
| **M0b** | ARW 内嵌预览解码（纯 Kotlin，零 NDK） | ✅ |
| **P1a** | 最小可用编辑链路 → **首个真机可测 APK**（导入/曝光·曲线·LUT/导出/日志） | ✅ |
| **P1b** | 完整人像修图 + ARW 全量修图 + 内置预设 ~10 套 | 🔧 进行中（LibRaw 全量解码已接入启用；人像算子/预设待做） |
| **P1+** | ML 自动蒙版（ONNX → TFLite） | ⬜ |
| **P2** | A7C2 相机直连（USB PTP PoC）+ 边拍边看 | ⬜ |
| **P3** | NAS Docker 化 + HTTP API，单张/批量后台修图 | ⬜ |

---

## 📱 设备要求

- **Android 16+（API 36）**，targetSdk / compileSdk **36（暂对齐；CI runner 未发布 `platforms;android-37`，待官方发布后升 37）**
- 推荐 **RAM ≥ 8GB**；全分辨率 33MP 解码：RGBA_8888 ≈ 131MB、RGBA_F16 ≈ 262MB
- 自适应档位：≥12GB 全分辨率+F16 ｜ 8–12GB 全分辨率 RGBA_8888 ｜ 6–8GB 全分辨率+单 Bitmap 复用 ｜ <6GB 降至长边 4096
- 调试真机：**一加15**（骁龙 8 Elite）；相机：**Sony A7C II**（33MP / 7008×4672 / 14bit）

---

## 🔨 构建与分发

> 本机**无需**任何 Android 开发环境。

```bash
git push origin main        # 推送后 GitHub Actions 自动接管
```

`.github/workflows/android.yml` 会执行：ktlint/detekt → unit tests → `assembleRelease`（`KEYSTORE_BASE64` 签名）→ 产物与通知。

- **子模块**：LibRaw 经 git 子模块引入，CI 用 `actions/checkout` 递归拉取；本机首次构建前执行 `git submodule update --init --recursive`（走 SSH 可避开代理证书问题）。
- **通知策略**：签名 APK 一律作 workflow artifact（保留 90 天）；配置 `FIREBASE_APP_ID` 等 secret 后才额外走 Firebase App Distribution（邮件 + 一键安装）。
- **密钥**：全部存放 GitHub Secrets，仓库内零 `.env`。

---

## 🐞 调试

App 内置调试日志（`diag/DebugLog.kt`）：

- 落盘 `files/debug/log_<session>.txt`，单文件 ~2MB 滚动
- 格式：`yyyy-MM-dd HH:mm:ss.SSS [LEVEL] TAG: msg {kv}`
- TAG：`LIFECYCLE` `IMPORT` `DECODE` `EDIT` `ML` `CAMERA` `API` `ERROR`
- 启动即 dump 设备能力 + 版本 + 分辨率选档结果
- 编辑页可"导出调试日志" → 系统分享

真机复现问题后把日志回传，即可定位。

---

## 📄 文档

- [`docs/DEV_PLAN.md`](docs/DEV_PLAN.md) — **开发计划（唯一有效）**：项目介绍 / 功能 / 原理 / 技术路线 / 代码架构 / 设备要求
- `docs/archive/` — 历史规划文档（已归档，不再维护）

---

## 🙏 致谢

- 现成的 Rust 修图引擎 `pixel-cake`（算法与模型复用）
- LUT 来源（仅使用 MIT / CC 可再分发部分，许可见各资源 LICENSE）：`shravankumar147/photo-edit-app`、`mv-lab/NILUT`
- RAW 解码：[LibRaw](https://www.libraw.org/)

## 许可

尚未确定（TBD）。内置第三方 LUT / 模型将单独标注许可并保留 NOTICE。
