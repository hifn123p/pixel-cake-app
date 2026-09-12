---
title: 像素蛋糕 App — P1+ ML 自动蒙版 设计稿
status: draft（v0.1，待确认）
created: 2026-09-12
project: D:\AI_Project
related: [DEV_PLAN.md, P1b_DESIGN.md, P2_DESIGN.md]
description: P1+ 阶段（ML 自动蒙版）的技术选型、模型选型、分阶段实施、代码结构、降级链、内存与包体预算、单测口径与待拍板决策。关键更正：计划稿原写的 NNAPI 已于 Android 15 废弃，改用 LiteRT。
---

# P1+ ML 自动蒙版 — 设计稿 v0.1

> 目标：让「磨皮 / 液化」在**用户不涂画笔**时也能自动只作用在皮肤/人像上，而不是退化成「整幅生效」。
> 这是让 App 从"能用"到"好用"的关键一步：P1b 的 `RetouchMask` 接口与 `FullMask` 语义已就位，本阶段**只是新增一个 `RetouchMask` 实现 + 接线**，UI 结构无需改动。

---

## 0. 一句话结论

用 **LiteRT**（不是 TFLite+NNAPI）跑 **MediaPipe `selfie_multiclass_256x256`**（Apache-2.0，6 类含 `face-skin`/`body-skin`）→ 输出 256×256 皮肤概率网格 → 包装成 `MlSkinMask : RetouchMask`（**低分辨率网格 + 按需双线性采样，绝不物化整幅 `FloatArray`**）→ 编辑器/批处理在「有 ML 蒙版」时优先用它，失败则按原逻辑回退（画笔 → `FullMask`）。

### 0.1 实施进度（2026-09-12）

| 批次 | 内容 | 状态 |
|---|---|---|
| **P1p-1a** | 依赖接入（`com.google.ai.edge.litert:litert:2.2.0`）+ 模型入库 + `core/ml/` 内核 + JVM 单测 + 许可声明 | ✅ 已完成（**CI 实证 `litert:2.2.0` 可解析 + native 打包**，run `34699770791`） |
| **P1p-1b** | UI 接线：编辑器「自动蒙版」开关（默认开）；`RetouchScale.editorSkinMask` 合成「ML ∪ 画笔取 `max`」（关自动蒙版时等价 P1 旧口径）；预览 + 导出四条路径接入 | ✅ 已完成并 push（CI 全绿） |
| **P1p-1c** | 真机验收（一加15）：核对日志「模型加载成功 / 是否走 GPU」、自动蒙版对皮肤的作用范围、无 OOM | ⬜ 待做 |
| **P1p-2a** | **检测内核（纯 Kotlin）**：`FaceAnchors`（SSD anchor 生成，移植 `SsdAnchorsCalculator`）+ `FaceDetectionPostProcess`（解码 + 加权 NMS）+ `Letterbox` + `FaceDetection` 数据类 + 3 组 JVM 单测 | ✅ 已完成（本轮） |
| **P1p-2b** | **运行时**：模型入库（`face_detection_full_range_sparse.tflite`）+ `FaceDetector` 接口 + `LiteRtFaceDetector`（LiteRT GPU→CPU 级联）+ `MlFaceProvider`（懒加载 / 每图一次缓存 / 降级 / 日志）+ `NOTICE` | ✅ 已完成（本轮） |
| **P1p-2c** | **接线**：检测出的脸中心/眼心喂 `Beauty` 的 `centroid`（替代「蒙版质心猜」）+ UI 回显 | ⬜ 待做 |

> P1p-1a 的定位是**先验证风险最高的那一步**：`litert` 只在 Google Maven、且含 native 库，
> 能否在 CI 正常解析/打包是本批最大未知数；内核与单测先落地，接线再跟上。

**模型实际落地信息**（已写入根 `NOTICE`）：

- 随包路径：`app/src/main/assets/models/selfie_multiclass_256x256.tflite`
- 体积：**16,371,837 字节（≈15.6 MiB）**
- SHA-256：`c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0`
- 下载源：`https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite`
- 许可：Apache-2.0（原样随包，未修改）

---

## 1. 现状：接口已就绪，只差实现

| 已有 | 位置 | 对本阶段的意义 |
|---|---|---|
| `RetouchMask` 接口 | `core/edit/Mask.kt` | ML 蒙版只需实现 `sample` / `resampleTo` |
| `FullMask`（作用域=整幅，O(1)） | `core/edit/Mask.kt` | **降级目标**：ML 不可用时退回它，行为等同现状 |
| `RasterMask`（画笔栅格） | `core/edit/Mask.kt` | 与 ML 蒙版的**合并**对象（见 §7） |
| `RetouchScale.skinMask(...)` | `core/edit/RetouchScale.kt` | 唯一蒙版生产入口，编辑器与批处理共用，本阶段在此扩展 |
| `RetouchLayer.apply(bitmap, state, mask)` | `core/edit/retouch/RetouchLayer.kt` | 消费口，**不需要改**（已流式化，对蒙版只读） |
| `Beauty.centroid(mask, w, h)` | `core/edit/retouch/Beauty.kt` | 液化的作用中心；P1p-2 可由人脸关键点直接喂入 |
| `null` 语义 = 不执行 | `Mask.kt` KDoc + `P1b_DESIGN.md §4` | ML 蒙版**不改变**这条约定 |

> ⚠️ 蒙版消费端已全部流式化（R10），**ML 蒙版必须同样守内存纪律**：`resampleTo(7008, 4672)` 若照 `RasterMask` 那样开 `FloatArray(w*h)`，就是 **131MB** 一次性分配 —— 本设计明确禁止（见 §8）。

---

## 2. 技术选型（含对计划稿的关键更正）

### 2.1 ❌ NNAPI：已废弃，不能再作为目标

`DEV_PLAN.md` 现写「TFLite + NNAPI delegate（NNAPI→GPU→CPU 降级）」，**此口径已过期**：

- Android **15 起 NNAPI 被官方废弃**，Google 的迁移指引是改用 LiteRT 的 accelerator；
- LiteRT 的 NNAPI delegate 页面已重定向到「NNAPI 迁移指南」，旧 Hexagon delegate 页面已下线；
- Google 的「Choosing a Delegate」现只列 GPU delegate（Android/iOS）与 Core ML delegate（iOS），**其余厂商加速统一走 LiteRT v2 `CompiledModel` 的 NPU 通道**；
- TensorFlow Lite 本体进入维护模式（只收安全/稳定性修复），新特性只在 LiteRT。

### 2.2 ✅ 采用 LiteRT v2 `CompiledModel`

| 项 | 结论 | 依据 |
|---|---|---|
| 依赖 | `com.google.ai.edge.litert:litert:2.2.0` | 官方迁移页；**仅在 Google Maven**（非 Maven Central）→ 需确认 `settings.gradle.kts` 有 `google()` |
| ABI | 只留 `arm64-v8a`（项目已如此） | 现有 `abiFilters` |
| API | `CompiledModel.create(...)` + `createInputBuffers()/writeFloat()/run()/readFloat()` | 官方 Kotlin 指南 |
| 加速级联 | `Accelerator.GPU` → 失败回落 `Accelerator.CPU` | v2 中 GPU 加速器**已并入主包**（`litert-gpu` 停在 1.4.2 的旧 Interpreter 时代） |
| NPU（后续可选） | `Accelerator.NPU` + `Environment.create(BuiltinNpuAcceleratorProvider(ctx))`；Snapdragon 需 `qnn-litert-delegate` + `qnn-runtime` | 一级骁龙 8 Elite；**先不做**，避免引入厂商闭源依赖 |
| 遗留 Interpreter | 2.x 包内仍含（可无缝迁移），但 **v2 Maven 上 GPU 只在 CompiledModel 可用** | 故直接用 CompiledModel，不要走 Interpreter |

### 2.3 为什么不用 MediaPipe Tasks / ML Kit

- **MediaPipe Tasks Vision**（`com.google.mediapipe:tasks-vision` + `.task` 包）：封装了预处理/输出蒙版，代码最少；但（a）引入更重的依赖层，（b）accelerator 由其内部 OpenGL delegate 管，不如 `CompiledModel` 可控，（c）本项目一贯自持底层（LibRaw/PTP/retouch 都是自写），且该模型前后处理**确实极简**（256×256 归一化 + 6 通道 argmax），自写成本低。
- **ML Kit**：模型/推理在 Google Play Services 内，**运行时可能需下载模型**，与「P1/P2 全程本地、离线可用」的硬约束冲突；且模型权重不可审计。
- **自训/转换 ONNX**：需把 Rust 仓库 `detect/*.rs` 的权重转 TFLite，转换链在无本地环境的前提下风险高、且模型许可需重新审计。**后置**。

> 附带的协同收益：`selfie_multiclass_256x256.tflite` 是标准 tflite，**P3 的 NAS 侧可用 OpenVINO 直接读同一个模型**（OpenVINO 官方 notebook 正是用它做 Intel 核显推理），端侧与 NAS 侧可共用同一份权重与同一套后处理口径。

---

## 3. 模型选型

MediaPipe Image Segmenter 提供 4 个候选（官方页数据）：

| 模型 | 输入 | 量化 | 输出类别 | Pixel 6 CPU/GPU 延迟 | 说明 |
|---|---|---|---|---|---|
| SelfieSegmenter (square) | 256×256 | **float16** | 背景 / 人 | 33.5 / 35.2 ms | 最轻，但**只有人/背景**（衣服头发一起"是皮肤"） |
| SelfieSegmenter (landscape) | 144×256 | float16 | 背景 / 人 | 34.2 / 33.6 ms | 同上，横构图更省 |
| HairSegmenter | 512×512 | float32 | 背景 / 头发 | 57.9 / 52.1 ms | 只为头发换色，不合用 |
| **SelfieMulticlass (256×256)** ✅ | 256×256 | float32 | **0 背景 / 1 头发 / 2 body-skin / 3 face-skin / 4 衣服 / 5 其它** | 217.8 / 71.2 ms | **唯一能直接给出"皮肤"类**，正合磨皮需求 |
| DeepLab-V3 | 257×257 | float32 | 通用 21 类 | 123.9 / 103.3 ms | 通用分割，非人像皮肤 |

**选型：`selfie_multiclass_256x256`（float32）**

- URL（pin `latest`，接线时记录实际 sha256）：`https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite`
- 体积：约 **15.6MB**（float32，无官方 fp16/int8 变体）
- 输入：`float32 [1,256,256,3]`，RGB，**除以 255** 归一到 `[0,1]`
- 输出：`float32 [1,256,256,6]`，逐像素 6 类概率（官方 notebook 实测，argmax 即标签）
- 许可：**Apache-2.0**（MediaPipe 模型，须随包保留 NOTICE/模型卡出处）
- 延迟：Pixel 6 CPU 218ms / GPU 71ms；**一加15（骁龙 8 Elite）预期远快于此**，且蒙版**每张图只算一次**（不随滑杆重算），CPU 路径亦可接受

> 权衡：多类模型的 float32 体积（15.6MB）与 CPU 延迟都不如二类小模型；换来的是**磨皮只作用于皮肤、不糊衣服**，以及为"祛瑕/追色"提供可扩展的类别底图。若嫌重，可退化为 SelfieSegmenter（人/背景，~0.4MB），但磨皮精度显著下降 —— 见 §12 决策点。

---

## 4. 分阶段实施

| 阶段 | 内容 | 交付物 |
|---|---|---|
| **P1p-1**（本批） | **自动皮肤蒙版**：接入 LiteRT + multiclass 模型 → `MlSkinMask`；编辑器加「自动蒙版」开关；失败降级 | 可测：开开关后磨皮只作用皮肤，背景/衣服纹理保留 |
| P1p-2 | **人脸检测 + 关键点**（`blaze_face_short_range.tflite` + `face_landmarker`）→ 自动给液化定 `centroid`（脸中心）与大眼中心；替代现在"按蒙版质心猜" | 液化不用手画也能对准脸 |
| P1p-3（可选） | 更细的人脸解析（唇/眼/眉），用于祛瑕与局部提亮；或引入 NPU 加速（QNN） | 按需 |

> 本批**只做 P1p-1**，把端到端链路（依赖接入 → 推理 → 蒙版 → 渲染 → 降级 → 日志）跑通并真机验证后，再评估 P1p-2。

---

## 5. 代码结构（新增 `core/ml/`）

```
core/ml/
├── SkinMaskModel.kt        # 接口：val side / val acceleratorName / fun inferProbs(argb: IntArray): FloatArray? / fun close()
├── LiteRtSkinMaskModel.kt  # LiteRT/CompiledModel 实现（GPU→CPU 级联；assets 读模型）
├── SkinMaskPostProcess.kt  # 纯函数：6 通道概率 → 皮肤概率网格（可 JVM 单测）
├── FloatGrid.kt            # 低分辨率浮点网格 + 双线性采样（无 Android 依赖）
├── MlSkinMask.kt           # RetouchMask 实现：持 FloatGrid，sample 双线性、resampleTo 不分配整幅
├── MlMaskProvider.kt       # 单例：懒加载模型 + 每图一次缓存 + 失败降级 + 日志
│
│   # —— P1p-2 人脸检测（以下为 P1p-2a 已落地，其余标注批次）——
├── FaceAnchors.kt          # 纯函数：SSD anchor 生成（移植 SsdAnchorsCalculator；FULL_RANGE/SHORT_RANGE 预置）
├── FaceDetectionPostProcess.kt  # 纯函数：16 维 raw → 框+6 关键点（含 sigmoid / reverse_output_order）+ 加权 NMS
├── Letterbox.kt            # 纯函数：等比缩放 + 居中 padding 的几何换算（含投回源图）
├── FaceDetection.kt        # 数据类：NormFace（张量归一化坐标）/ FaceDetection（源图像素坐标）
├── (P1p-2b) FaceDetector.kt / LiteRtFaceDetector.kt / MlFaceProvider.kt
└── (P1p-3) FaceLandmarker.kt（478 点网格）
```

**可测性设计**：`SkinMaskModel` 是接口 → JVM 单测注入 fake（返回构造好的概率网格），**测试完全不碰 LiteRT 原生库**。
**零 Android 依赖**：`FloatGrid` / `SkinMaskPostProcess` / `MlSkinMask` 均为纯 Kotlin。

---

## 6. 数据流

```
源图 Bitmap（预览代理或全分辨率）
      │  ① 缩到 256×256（Bitmap.createScaledBitmap，ARGB_8888）
      ▼
SkinMaskModel.infer(256×256 ARGB)        ② GPU→CPU 级联，~几十~200ms
      │
      ▼
FloatGrid(256×256)  皮肤概率 = f(P2, P3)  ③ 后处理：软合并 + 阈值/羽化
      │
      ▼
MlSkinMask : RetouchMask                 ④ sample() 双线性上采样，O(1) 分配
      │
      ▼
RetouchLayer.apply(bitmap, state, mask)  ⑤ 已流式化，不需改动
```

**每图一次缓存**：以 `(源图标识, 尺寸)` 为 key，预览与导出**共用同一蒙版对象**（`sample` 按目标坐标自适应），因此"预览所见 = 导出所得"天然成立，且不重复推理。

---

## 7. 接线口径（与画笔蒙版的关系）

`RetouchScale.skinMask(...)` 扩展为可接收 ML 蒙版，优先级：

```
ML 蒙版（开启且成功） ──┬─ 有画笔描迹 → 合并（取 max，见下）
                        └─ 无画笔描迹 → 直接用 ML 蒙版
         ↓ 不可用
画笔描迹栅格（现状）
         ↓ 无描迹
FullMask（现状，整幅生效）
```

- **合并口径**：`sample = max(ml, brush)`。理由：两者都是"作用强度"，画笔是用户**显式补正**（比如 ML 漏了脖子），取 max 才符合直觉；若取 min 会出现"涂了反而没效果"。
- 编辑器新增一个「自动蒙版（AI）」开关，默认**开**（不可用时置灰并提示）。批处理（`CameraBatch`）保持传 `null` 不变（不执行皮肤类算子）——若要给批量也开自动蒙版，是**独立的产品决策**，本批不动。
- `null` 语义**不变**：ML 不可用 ≠ `null`，仍回退到 `FullMask`（编辑器）语义。

---

## 8. 内存纪律（硬约束，与 R10 一脉相承）

| 项 | 要求 |
|---|---|
| 推理输入 | 仅 `256×256` Bitmap（256KB 级）；**不得**把全分辨率图喂进去 |
| 蒙版存储 | `FloatGrid` = `FloatArray(256*256)` ≈ **256KB**；**绝不** `FloatArray(w*h)`（33MP = 131MB） |
| `resampleTo(w,h)` | **直接返回 this**（靠 `sample` 的双线性自适应），零分配 |
| 概率中间量 | 6×256×256 的 `FloatArray` ≈ 1.5MB，用完即弃；可复用缓冲避免反复分配 |
| 缓存 | 只缓存最终 `FloatGrid`，不缓存 Bitmap 副本 |
| 模型 | mmap 自 assets；`aaptOptions/noCompress` 保证可映射 |

---

## 9. 降级链与可观测性

失败**永不崩、永不静默**：

| 失败点 | 行为 | 日志 |
|---|---|---|
| assets 缺模型 / 打开失败 | GPU→CPU 都失败 → 返回 `null` → 蒙版按 §7 回退 | `ML` WARN，含原因 |
| `CompiledModel.create(GPU)` 抛异常 | 回落 CPU 重试一次 | `ML` INFO（加速器降级）+ 实测耗时 |
| 推理抛异常 / 返回尺寸异常 | 返回 `null`，回退 | `ML` ERROR + 堆栈摘要 |
| 内存不足（OOM） | 捕获 → 回退；并**关闭本会话自动蒙版**避免反复触发 | `ML` ERROR |

DebugLog 新增/复用 tag：`ML`。启动快照里 dump「LiteRT 版本 / 实际选中的 accelerator / 模型 sha256 前 8 位」，便于真机回传核对。

---

## 10. 预算（估算，接线后以实测修正）

- **包体**：模型 ~15.6MB + LiteRT 运行库 arm64 增量 ~2–4MB（未压缩 assets 会进 APK，注意 `noCompress`）→ 相对当前 APK 增量偏大但可接受；若需瘦身可后续换 fp16/量化模型。
- **首帧延迟**：模型一次性加载（冷启动首次推理含加载，预计 200–600ms）；推理本身按上表。
- **内存**：蒙版 ≤1MB，推理输入/中间量 ≤2MB —— 相对 R10 后 ~30MB 的 retouch 峰值，**可忽略**。

---

## 11. 单测口径（JVM，跑在 CI 上）

| 用例 | 断言 |
|---|---|
| `SkinMaskPostProcess` 6 通道 → 皮肤概率 | 构造已知概率图，逐位比对期望（纯函数） |
| 阈值/羽化映射 | lo/hi 边界、单点、单调性 |
| `FloatGrid.sample` 双线性 | 与手写朴素双线性逐位一致（含边界 clamp） |
| `MlSkinMask.resampleTo(w,h)` | `assertSame`（不分配）+ 任意坐标采样与低分辨率双线性一致 |
| 合并口径 `max(ml, brush)` | 两分支（ML 强 / 画笔强） |
| 降级分支 | fake model 返回 null / 抛异常 → `skinMask` 回退到画笔或 `FullMask` |

> 推理正确性（真实的 LiteRT 调用）**不进 JVM 单测**（原生库不可用），靠真机验收 + 日志；这与既有"LibRaw 走真机验收"的策略一致。

---

## 12. 待你拍板的决策点

1. **模型变体**：`selfie_multiclass`（15.6MB，能区分皮肤/衣服，推荐）还是 `selfie_segmenter`（~0.4MB，只有人/背景，磨皮会糊衣服）？
2. **模型入库方式**：（a）**提交进仓库** `app/src/main/assets/models/`（可复现、CI 不依赖外链，代价是仓库 +15.6MB）；（b）**CI 构建时下载 + sha256 校验**再塞进 assets（仓库干净，但依赖外链可用性）。倾向 (a)。
3. **加速器**：先只上 CPU（最简单、零风险），还是一次到位 `GPU→CPU` 级联（推荐，代码略多但更合适一加15）？
4. **默认开关**：编辑器「自动蒙版」默认开（推荐，否则用户感知不到新功能）还是默认关？

---

## 13. 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| 新增 Maven 依赖（Google Maven）在 CI 解析失败 | 中 | 先确认 `settings.gradle.kts` 的 `google()`；接线后立刻推 CI 验证 |
| LiteRT `CompiledModel` API 与文档示例有出入（无法本地编译验证） | 中 | 严格照官方 Kotlin 指南写；CI 编译兜底；首次跑通后固化为注释 |
| 模型 float32 15.6MB 让 APK 明显变大 | 低 | 可接受；后续可换量化模型 |
| 推理在低端机过慢 | 低 | 只跑一次 + GPU 级联 + 可关开关 |
| 模型许可（Apache-2.0）合规 | 低 | NOTICE 增补模型出处 + 模型卡链接；随包携带 |
| 蒙版边缘生硬（256×256 上采样） | 中 | 概率场本身是软的 + 羽化参数；必要时对网格做一次 3×3 平滑 |

---

## 14. 参考

- LiteRT Android Kotlin API（CompiledModel）：<https://developers.google.cn/edge/litert/next/android_kotlin>
- TFLite → LiteRT 迁移（含 Maven 坐标与 v1/v2 路径）：<https://www.tensorflow.org/lite/guide/roadmap>
- MediaPipe Image Segmenter（模型清单/规格/延迟）：<https://developers.google.cn/edge/mediapipe/solutions/vision/image_segmenter>
- NNAPI 废弃（Android 15）：Android Developers「NNAPI 迁移指南」
- OpenVINO selfie segmentation notebook（同一模型 + Intel 核显，P3 参考）：<https://docs.openvino.ai/2024/notebooks/tflite-selfie-segmentation-with-output.html>

---

## 15. P1p-2 人脸检测：实测 I/O 与实现口径

> **本节所有数字均来自真实文件的离线解析**（隔离 venv + `pip install --no-deps tflite flatbuffers`，
> 直接读 flatbuffer 打印张量规格），**不是照抄文档** —— 模型卡/文档实测有两处过时。
> 下载源：`https://storage.googleapis.com/mediapipe-assets/<name>.tflite`；解码参数出自同目录
> `mediapipe/modules/face_detection/face_detection_*.pbtxt`（**不在 `.tflite` 里**）。

### 15.1 候选实测（三选一，均 Apache-2.0；`custom_ops = 0` ⇒ LiteRT GPU/CPU 直跑，无需厂商 delegate）

| 模型 | 体积 | 输入 | 输出 | anchor 规格 |
|---|---|---|---|---|
| `face_detection_short_range` | 229,032 B | `input` `[1,128,128,3]` f32 | `regressors[1,896,16]` + `classificators[1,896,1]` | SSD：4 层 strides 8/16/16/16、896 anchors、scale 128、阈值 0.5 |
| `face_detection_full_range` | 1,083,786 B | `input` `[1,192,192,3]` f32 | `reshaped_regressor_face_4[1,2304,16]` + `...[1,2304,1]` | CenterNet 式：1 层 stride 4（48×48）、2304 anchors、scale 192、阈值 0.6 |
| **`face_detection_full_range_sparse`** ✅ | **676,746 B** | `input_1` `[1,192,192,3]` f32 | `Identity[1,2304,16]` + `Identity_1[1,2304,1]` | 同上；**MediaPipe 自家 full-range 管线用的就是它** |

- **两处对文档的更正**：① 实测输入是 **FLOAT32**（非 float16）；② full/sparse 张量是 **192×192**（模型卡写 sparse「160×192」是旧值）。
- **选型**：`face_detection_full_range_sparse.tflite` —— 后摄向（A7C2 旅行照）、体积最小、解码配置与 dense 版**完全一致**，故 dense/sparse 可互换。
- **输入归一化**：`[-1.0, 1.0]`（即 `pixel/127.5 − 1`），由 pbtxt 的 `output_tensor_float_range` 明示。

### 15.2 解码链路（MediaPipe 一致，逐项对照 C++ 源码）

1. **预处理**：等比缩小 + **居中** padding 到 192×192（`keep_aspect_ratio: true` + `border_mode: BORDER_ZERO`）→ 归一化 `[-1,1]`。
   ⚠️ **不能直接拉伸**成方图（改变人脸比例，召回明显下降）。
2. **anchor 生成**（`SsdAnchorsCalculator`）：`fixed_anchor_size = true` ⇒ `w = h = 1`、中心 `(i+0.5)/48`；
   连续**同 stride 的层会合并**成同一格多锚（full-range 只 1 层，不涉及）。
3. **解码**（`TfLiteTensorsToDetectionsCalculator`）：`reverse_output_order = true` ⇒ 16 维前 4 个是
   **`[x_center, y_center, w, h]`**（写反会让框转 90°）；分数 `sigmoid(clamp(raw, ±100))`，阈值 0.6；
   框心 `raw / 192 * anchor.w + anchor.center`；`apply_exponential_on_box_size` 默认 **false**（直接相除）；
   宽/高为负的框丢弃。**16 = 4 框 + 6 关键点 × 2**。
4. **抑制**（`NonMaxSuppressionCalculator`）：`INTERSECTION_OVER_UNION`、`min_suppression_threshold = 0.3`、
   `algorithm = WEIGHTED` ⇒ 被抑制的框**按分数加权并入胜者**（框与关键点都平均），
   **胜者分数保持不变**（不累加）。
5. **投回源图**：张量归一化坐标 → 源像素，用 `Letterbox` 的逆变换。

### 15.3 代码映射

| 文件 | 职责 | 批次 |
|---|---|---|
| `core/ml/FaceAnchors.kt` | anchor 生成（`FULL_RANGE` / `SHORT_RANGE` 预置规格） | P1p-2a ✅ |
| `core/ml/FaceDetectionPostProcess.kt` | 解码 + 加权 NMS + `largest()` | P1p-2a ✅ |
| `core/ml/Letterbox.kt` | letterbox 几何 + 投回源图 | P1p-2a ✅ |
| `core/ml/FaceDetection.kt` | `NormFace`（张量坐标）/ `FaceDetection`（源图坐标，含 `centerX/Y`、`eyeCenter`、`eyeRoll`） | P1p-2a ✅ |
| `core/ml/FaceDetector.kt` + `LiteRtFaceDetector.kt` | 接口 + LiteRT 实现（GPU→CPU 级联，永不抛；按张量元素个数认领 boxes/scores） | P1p-2b ✅ |
| `core/ml/MlFaceProvider.kt` | 懒加载 + letterbox 预处理 + 每图一次缓存 + 降级 + 日志 | P1p-2b ✅ |

**可测性**：`FaceAnchors` / `FaceDetectionPostProcess` / `Letterbox` 均为**纯 Kotlin 纯函数**，
单测覆盖「锚点数量与顺序」「reverse_output_order 的取值」「加权合并的加权平均与分数保持」「letterbox 正反互逆」。
推理正确性（真实 LiteRT 调用）不进 JVM 单测，靠真机验收（与 LibRaw 同策略）。

### 15.4 待真机核实的两个假设

1. **letterbox 边框颜色**：这里按 `BORDER_ZERO` 补 **黑（归一化 −1）**。若真机发现框系统性偏移/漏检，先复核此项。
2. **letterbox 对齐**：这里按 MediaPipe `PadRoi` 的**居中**放置（四舍五入取整）。框中心有偏移时复核。

### 15.5 模型实际落地信息（已写入根 `NOTICE` 第 4 条）

- 随包路径：`app/src/main/assets/models/face_detection_full_range_sparse.tflite`
- 体积：**676,746 字节**
- SHA-256：`2c3728e6da56f21e21a320433396fb06d40d9088f2247c05e5635a688d45dfe1`
- 下载源：`https://storage.googleapis.com/mediapipe-assets/face_detection_full_range_sparse.tflite`
- 许可：Apache-2.0（原样随包，未修改）；`.tflite` 已由 `noCompress` 排除压缩（与 P1p-1a 同一处配置）。
