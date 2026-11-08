<div align="center">

# 🍰 像素蛋糕 PixelCake

**安卓端 AI 人像精修 App** — 对标像素蛋糕，定位类似 Snapseed 但专攻人像

[![Platform](https://img.shields.io/badge/Platform-Android%2016%2B-3DDC84?logo=android)](https://developer.android.com)
[![minSdk](https://img.shields.io/badge/minSdk-36-blue)](https://developer.android.com)
[![targetSdk](https://img.shields.io/badge/targetSdk-36-blue)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.21-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3%204285F4?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Version](https://img.shields.io/badge/version-v0.6.6-success)](https://github.com/hifn123p/pixel-cake-app/releases)
[![Build](https://img.shields.io/badge/Build-GitHub%20Actions-2088FF?logo=githubactions)](https://github.com/features/actions)
[![License](https://img.shields.io/badge/License-TBD-lightgrey)](#许可)

</div>

---

## 📖 项目简介

在手机上做**人像精修**：导入相册照片（JPEG / HEIF / Sony ARW）→ 非破坏式调色与美化 → 导出成片。

> **产品定位（Q1）**：**专业向手工精修 + 真 RAW + 全本地** —— 影调/色彩/曲线逐项手调、16-bit RAW 管线、零上传零账号；不做「一键美颜流水线」定位。

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

## 📱 截图

> 真机截图待补充。设计稿参见 [`docs/UI3.0.html`](docs/UI3.0.html)（14 屏 + 2 横屏 + 动效演示）。

| 调色台 | 编辑器 | 系留仪表盘 |
|---|---|---|
| *待补充* | *待补充* | *待补充* |

---

## 🚀 快速开始

```bash
# 克隆（含 LibRaw 子模块）
git clone --recurse-submodules https://github.com/hifn123p/pixel-cake-app.git

# Android Studio 打开项目根目录，Sync + Run（真机 Android 16+）
# 或命令行构建：
./gradlew assembleDebug
```

> ⚠️ 本仓库 CI 是唯一编译器，本地不装 Android 开发环境。
> 推送到 main 即自动触发 CI（单测 → Lint → 签名 APK → Release）。

---

## ✨ 特性

### 已实现

| 模块 | 说明 |
|---|---|
| 🖼 导入与解码 | Photo Picker / SAF；JPEG、HEIF；**EXIF 方向烘焙**（Orientation 矩阵逐像素正立，翻转类方向含镜像分量）；Sony ARW 内嵌全分辨率预览（纯 Kotlin TIFF/IFD 链遍历）与 LibRaw 全量解码（16-bit 线性） |
| 🎚 非破坏编辑 | 白平衡、明暗（曝光/对比/高光/阴影/白点/黑点/高光恢复）、颜色（自然饱和/饱和/去雾/HSL 八通道/彩色分级）、曲线（亮度 + R/G/B 分通道）、细节与效果（锐化/降噪/清晰度/纹理/暗角/颗粒）、LUT；撤销/重做、长按对比 |
| 🪄 **UI 3.0「Aperture」** | **One-Surface** 全屏画布（照片不被遮挡）；**双通道调节**（照片横拖调参 + deck 滑块同步，HUD 46sp 巨大读数）；**点哪修哪**（单击命中分割蒙版选作用域）；**强度环**（预设参数栈整体向中性回退）；**自动增强**（直方图启发式，零模型）；**历史丝带**（步骤跳转）；**分割对比**（逐列修前修后）；**导出回执**（尺寸/格式/预设名）；曲线本体（deck 内嵌可拖曲线+直方图底）；暖石墨+琥珀主题；自适应图标三层 |
| 🔍 预览缩放 | 双指缩放（≤8×）与平移、双击 2.5×↔1×（以点击处为锚）、画笔模式下单指恒为涂抹、未放大时按住看原图；已放大时显示「已放大 N% · 复位」胶囊 |
| 🎨 调色预设 | **44 套**分类预设 + **用户预设库**（把当前调法存为自定义预设，编辑器应用/强度环、相机批量、系留默认参数三处复用；JSON 持久化），按当前照片生成真实缩略图 |
| 🧴 人像精修 | 皮肤画笔与 AI 肤色蒙版、磨皮、美型液化（人脸锚点）、祛瑕、追色 |
| 🪄 对象作用域调色 | **批次 5**：同一套调色参数可作用在「整图」或识别到的对象（8 个作用域 = 模型原生 6 类 + 2 个派生），图层栈可叠加 |
| 🤖 端侧 ML | LiteRT `CompiledModel`（GPU→CPU 级联、永不崩的降级链）：6 类人体分割染色蒙版 + 人脸框/关键点，模型随包内置 |
| 📸 **系留出片 v2** | 连接 Sony A7C II → 仪表盘（电量/存储/曝光）→ **拍摄到达流**（轮询新对象→自动下载→套默认修图参数→导出相册，3s 轮询）→ 会话批量 |
| 📷 相机直连 | Sony USB PTP 探测、浏览、下载（256KB 流式分块）；单张导入 + 批量套预设导出，导完即删 |
| 💾 导出 | JPEG / PNG（可选画质）；**可取消**（逐带检查点，取消不留幽灵文件）；**最小 EXIF 回写**（机身/拍摄参数 + 方向恒正）；RAW 全分辨率分带渲染，非 RAW 按设备档位选分辨率；导出成功后按钮切换形态 |
| 📊 工具 | 曲线编辑器（黑场/中间调/白场锚点）+ **RGB 三通道直方图**（明暗页，叠加显示） |
| 🐞 调试日志 | 结构化端侧日志（`files/debug/log_<session>.txt`），可通过系统分享导出 |

### 规划中

- ☁️ **P3**：NAS 后台引擎 —— Docker 化 Rust 服务与任务队列（手机控 NAS、单张/批量后台修图）
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
| **UI v2.1** | 预览缩放/平移/双击 + 调色菜单重排（7→6）+ RGB 直方图 + 尺寸回调 | ✅ |
| **UI 3.0** | **「Aperture」全量改版**（One-Surface / 双通道 / 点哪修哪 / 强度环 / 自动增强 / 历史丝带 / 分割对比 / 导出回执 / 曲线本体 / 系留出片 v2） | ✅ |
| **P2** | Sony USB PTP 传输与批量预设处理 | ✅（真机兼容性 / liveview 仍需验证） |
| **P3** | NAS Docker 化 + HTTP API，单张/批量后台修图 | 🔜 |

### 版本历史

| 版本 | 内容 |
|---|---|
| `v0.6.6` | **用户预设库**（T1 解锁的首个功能）：编辑器「预设 → 我的」把当前调法存为自定义预设（JSON 原子写 + 坏文件留证 + 8 断言单测），应用/强度环与内置同型；**相机批量与系留默认参数均可选自定义预设**；瑕疵描迹按语义不入预设 |
| `v0.6.5` | **审计剩余项全量清账**（hy4 阶段 2/3 + Q 决策）：**协议** E1 属性码撞码修正（0x5007/0x5008/0x5009 按 PTP 标准）+ datatype 校验 + 数据阶段事务号校验对称 + E2 SessionID 存档 + E3(b) 诊断上屏 + E5 注释修正；**并发** A4 帧栅栏回收 / A5 进度回调切主线程 / A6 缩略图去锁不堵首帧；**无响应** D1 后台线程释放 USB / D2 busy 进 finally×4 / D3 日志攒批刷盘 / D4 缓存清理进 IO / D5 渲染分片等待加超时；**内存** B1 导出即回收底图+6-8GB 档收敛 / B2 笔刷蒙版网格化（131MB→≤4MB）/ B4 openLinear 内存预算；**质量** M-3 Bayer 有序抖动（16-bit→8-bit）/ M-4 thermal headroom 进日志；**诚实界面** U2 强度 0 禁用修饰量 / U3 分级色相 chip / U6 重置接线 + 磨皮阈值滑块 / 横滑 chips 截断渐隐；**架构** T2 BackHandler（系统返回=安全退出编辑器）+ 生命周期决策可测化 + TetherProcessor 抽取（K1/K2/K3 回归测试）/ **T3 横屏最小可用**（Deck 右侧栏）/ **T4 无障碍语义清单**；**护栏** `catch(Throwable)` CE 闸门进 kotlin_gates.py；Q1/Q2/Q3/Q6 决策落地；死代码清理。+5 组新测试 |
| `v0.6.4` | **外部审计整改批次**（qwen3.8 + hy4 双审）：P0-2 **EXIF/方向层落地**（解码烘焙 Orientation 矩阵、导出回写最小 EXIF 集）；P1-3 **导出可取消**（sRGB 逐带取消 + 幽灵文件删除 + CE 重抛）；P1-2 **直方图按带累加**（19MB/帧垃圾清零、进 renderMutex、isActive 检查点）；P1-5 **系留健壮化**（开系留播种基线、坏对象记账、位图回收进 finally、renderIntoSrgb 返回值消费）；U4 **空作用域禁用**（`coveredScopes` 判空 + chip 禁用 + 说明）；U1 滤镜强度空转修复；P1-15 悬空 rawCachePath / NotApplicable Hint 矛盾修正；**T1 参数栈 JSON 序列化**（`EditDocument` + 版本迁移骨架，解锁另存预设/项目/P3 契约）+ 3 组新单测 |
| `v0.6.3` | P0 三项修复（系留 ARW 走 LibRaw 管线、陈旧直方图清位、exifinterface 依赖）+ 两份审计报告入库 |
| `v0.6.2` | **UI 3.0「Aperture」全量改版**：One-Surface 编辑器 + 双通道调节 + 点哪修哪 + 强度环 + 自动增强 + 历史丝带 + 分割对比 + 导出回执 + 曲线本体 + 系留出片 v2 + 暖石墨琥珀主题 + 自适应图标 |
| `v0.6.1` | PTP GetDevicePropValue 扩展（电量/ISO/光圈） |
| `v0.6.0` | UI 3.0 架构 + 版本抬升 |
| `v0.5.1` | PTP GetDevicePropValue 预备 + 版本号对齐 |
| `v0.5.0` | **第二轮全量审查**（3 个 P1：`RawLinearSource` 的 use-after-free 互斥、`raw_bridge` 缓冲校验的 32 位截断、ARW 预览候选超限回退；另 12 个 P2 含两个 `Histogram` 真 bug）+ **删除 `ui/editor/curves/` 死代码**（3 文件）+ 3 个新测试；**UI v2.1**：预览缩放（双指 ≤8×/ 双击 2.5× / 按住看原图，单协程手势状态机）、调色菜单 7→6 重排、RGB 三通道直方图、尺寸回调（字阶 28→24 等） |
| `v0.4.7` | **全量代码审查修复**：21 个 P0（撤销丢对象层、绿/蓝曲线静默跳过、颗粒映射反向、JPEG 与 RAW 阶段顺序不一致、导出 0 字节图、native 句柄竞态、USB close 竞态、PTP 失步、19 个美容滑块无效果等）+ 性能批次（逐像素 64 位除法、900MB 分带垃圾、131MB 无谓重采样）+ UI 规范修复 + 新增 13 条回归断言 |
| `v0.4.6` | ML 缓存键改为**会话代次**（修「同 URI 更新后误用旧人脸/分割」）+ 渲染提交判据补 `completed`；chip/预设改按宽度换行；控件尺寸回调（一级分类 48dp、滑块拇指 20dp） |
| `v0.4.5` | 细部位美容 + 44 套分类预设 + 追色 mono 去色 + 曲线/直方图工具 |
| `v0.4.4` | UI Redesign v2.0 |
| `v0.4.0` / `v0.4.1` | 调色批次 1~4（81 项）/ 批次 5 对象作用域 |

（`v0.1.0` → `v0.6.6` tag 齐全，Release 页可下载签名 APK。）

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

> **文档政策（2026-11-07）**：`docs/` 只保留**当前技术路线与设计**——被取代 / 阶段性 / 快照
> 性质的文档一律移入本地 `docs/archive/`（见下）。

| 文档 | 作用 |
|---|---|
| [`docs/DEV_PLAN.md`](docs/DEV_PLAN.md) | **开发计划（唯一有效）**：项目介绍 / 功能矩阵 / 核心原理 / 技术路线 / 代码架构 / 设备要求 / 风险 / 文档索引 |
| [`docs/ENGINEERING_NOTES.md`](docs/ENGINEERING_NOTES.md) | **工程笔记（改代码前必读）**：仓库与协作约定、Compose 布局陷阱、渲染/并发纪律、构建与发版、历史审查整改台账（§7~§13）。**每一条都是被真机 bug 逼出来的** |
| [`docs/UI3.0.html`](docs/UI3.0.html) | **UI 3.0「Aperture」设计稿（当前唯一真值）**：14 屏 + 2 横屏 + 动效系统 + 性能字号审计 + token 真值 |
| [`docs/UI_DESIGN.md`](docs/UI_DESIGN.md) | UI 规范与编号锚点：设计语言五支柱 / Token / §8 设计决策点 / §4.0.x 真机反馈修正表（编号被代码 KDoc 引用，数值已由 UI3.0 接管） |
| [`docs/TONING_DESIGN.md`](docs/TONING_DESIGN.md) | 调色参数体系：参数全表、管线顺序、分批实施与落地记录（批次 1~4） |
| [`docs/OBJECT_TONE_DESIGN.md`](docs/OBJECT_TONE_DESIGN.md) | 对象作用域调色（批次 5）：作用域全表、数据模型、单趟逐层 lerp、验收方式 |
| [`docs/ONDEVICE_MODEL_PLAN.md`](docs/ONDEVICE_MODEL_PLAN.md) | **端侧模型规划**：P6/P7 候选模型表 + 体积延迟预算 + 接入架构 |
| [`docs/Github_CI.md`](docs/Github_CI.md) | 最近一次 CI 运行的完整报告（**每次 CI 后覆盖重写**，工具性文档） |
| [`docs/app_icon.svg`](docs/app_icon.svg) | App 图标资产（自适应三层：背景/前景/单色） |
| `docs/archive/` | **本地历史存档（不入库）**：早期规划稿、UI2.0 备选稿、阶段设计存档（`PHASE_DESIGN_HISTORY.md`，KDoc 编号引用的溯源处）、两份外部审计报告。**被 `.gitignore` 屏蔽 ⇒ 克隆仓库看不到**；入库过的原件可 `git show v0.6.4:docs/<文件名>` 取回 |

> 三层文档体系：`docs/*_DESIGN.md`（设计决策，入库）→ `docs/ENGINEERING_NOTES.md`（工程纪律）→ 代码 KDoc（实现细节）。
> 本地工作记忆（`.workbuddy/memory/`）与 `docs/archive/` 不入库，需要长期留存的结论一律提炼进上面的入库文档。

---

## 🙏 致谢

- 现成的 Rust 修图引擎 `pixel-cake`（算法与模型复用）
- 端侧模型：[MediaPipe](https://developers.google.cn/edge/mediapipe/solutions/vision/image_segmenter) `selfie_multiclass_256x256` 与 `face_detection_full_range_sparse`（Apache-2.0）
- LUT 来源（仅使用 MIT / CC 可再分发部分，许可见各资源 LICENSE）：`shravankumar147/photo-edit-app`、`mv-lab/NILUT`
- RAW 解码：[LibRaw](https://www.libraw.org/)

## 许可

App 主体许可待定（TBD）；内置静态链接的 LibRaw（LGPL-2.1/CDDL-1.0）许可与「可重新链接」义务见仓库根 `NOTICE`。内置第三方 LUT / 模型单独标注许可并保留 NOTICE。
