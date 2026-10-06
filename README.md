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

### 已实现
| 模块 | 说明 |
|---|---|
| 🖼 导入与解码 | Photo Picker / SAF；JPEG、HEIF；Sony ARW 内嵌预览与 LibRaw 全量解码 |
| 🎚 非破坏编辑 | 曝光、白平衡、曲线、HSL、分级、LUT、细节与效果；撤销/重做、前后对比 |
| 🧴 人像精修 | 皮肤画笔与 AI 肤色蒙版、磨皮、美型液化、祛瑕、追色及对象蒙版图层 |
| 🎨 预设 | 内置参数栈预设，按当前照片生成缩略图；编辑后可继续微调 |
| 🤖 端侧 ML | LiteRT 人脸框/关键点与 6 类人体分割；GPU→CPU 回退 |
| 📷 相机直连 | Sony USB PTP 探测、浏览与下载；支持单张导入及批量套预设导出 |
| 💾 导出 | JPEG / PNG；RAW 全分辨率分带渲染，非 RAW 按设备能力选择分辨率 |
| 🐞 调试日志 | 结构化端侧日志，可通过系统分享导出 |

### 规划中
- ☁️ NAS 后台引擎：Docker 化 Rust 服务与任务队列（P3）
- 🎨 用户预设库：保存/管理自定义参数栈，并在相机批处理中复用
- ⚡ GPU/AGSL 预览管线与更多格式/机型的性能验证
- 📷 相机真机验收与实时取景；当前 PTP 能力以传输照片为主，并非实时取景

---

## 🏗 技术栈

**客户端（主）**
- Kotlin 2.2.21 · Jetpack Compose + Material3 · AGP 8.13.2 · JDK 17
- AndroidX Core / Activity Compose / Lifecycle Runtime；Compose UI、Material3、Animation
- 端侧推理：LiteRT `CompiledModel`（GPU→CPU 回退），模型随包内置
- 预览渲染：CPU 分带像素管线；RAW 编辑使用 LibRaw NDK/JNI 的 16-bit 线性数据
- RAW 快速预览：纯 Kotlin TIFF/IFD 解析内嵌 JPEG

**体积与响应策略**：Release 已启用 R8/资源收缩、限定 `arm64-v8a` 并过滤语言资源；RAW/像素编辑采用代理预览、分带读写和双缓冲。肤色分割模型约 16.37 MB、用于端侧 mmap 因而不压缩，另有人脸检测模型约 0.68 MB。继续明显缩包需量化/蒸馏分割模型；在没有真机画质基准前不应以牺牲蒙版质量换体积。

**服务端（辅，P3）**
- Rust · `axum` · `ort`(ONNX: OpenVINO / Vulkan / CPU) · `rusqlite` · Docker（飞牛 FnOS）

---

## 🧩 架构

### 代码结构

```
com.hifn.pixelcake
├── core/
│   ├── decode/    JPEG/HEIF、RAW 解码与导出
│   ├── edit/      参数管线、对象图层、预设及人像算子
│   └── ml/        LiteRT 人脸检测与肤色/对象分割
├── arw/           ARW 容器解析、预览提取与全量解码
├── camera/        USB PTP、会话管理与批量处理
├── ui/            Compose 首页、编辑器、相机面板、设置与主题
└── diag/          DebugLog（结构化日志 + 导出分享）
```

### 复用 vs 自研

| 来源 | 复用内容（路径） |
|---|---|
| `pixel-cake-android` | `ui/home/DeviceCapabilities.kt`（设备探测）· `ui/home/HomeScreen.kt` · `arw/ArwContainer.kt` · `MainActivity.kt` · `ui/theme/` · `cpp/raw_bridge.cpp`（LibRaw JNI，已接入）· `.github/workflows/android.yml` |
| `pixel-cake`（Rust） | `engine/src/retouch/{neutral_gray,beauty,color_transfer,inpaint,enhance}.rs` · `engine/src/detect/{face,landmark,segment}.rs` · `engine/src/raw.rs` · `engine/src/color/lut.rs` · `crates/scheduler`（P3 任务队列） |

- **复用**：Compose 基建、设备探测、CI 骨架、LibRaw、Rust 修图算法（P3）
- **自研**：非破坏编辑栈、预览渲染（当前 CPU band）、ARW 内嵌预览解析、LUT 应用、预设系统、调试日志

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
| **P1b** | 人像修图、RAW 全量编辑与预设 | ✅（真机验收持续进行） |
| **P1+** | LiteRT 肤色/对象蒙版与人脸关键点 | ✅（真机效果仍需验收） |
| **P2** | Sony USB PTP 传输与批量预设处理 | ✅（真机兼容性/实时取景仍需验证） |
| **P3** | NAS Docker 化 + HTTP API，单张/批量后台修图 | 🔜 |

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

`.github/workflows/android.yml` 会执行：unit tests（`testDebugUnitTest`）→ Android Lint（`lintDebug` 拦门）→ `assembleRelease`（`KEYSTORE_BASE64` 签名）→ 产物与通知。ktlint/detekt 待本地验证后引入。

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

App 主体许可待定（TBD）；内置静态链接的 LibRaw（LGPL-2.1/CDDL-1.0）许可与「可重新链接」义务见仓库根 `NOTICE`。内置第三方 LUT / 模型将单独标注许可并保留 NOTICE。
