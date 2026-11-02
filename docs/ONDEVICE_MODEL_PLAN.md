# 端侧模型规划（P6 / P7 · 设计稿，未开发）

> 状态：**设计先行**（2026-11-02）。本文回答「端侧有哪些好用的模型、手机跑不跑得动、怎么接」。
> 现状基线：两个模型已随包（人像分割 16.4MB + 人脸检测 0.68MB，LiteRT `CompiledModel`，
> GPU→CPU 降级链已验证）；运行端 = 一加15（骁龙 8 Elite，≈LPDDR5X 12GB）。
> 原则不变：**全端侧、零联网推理、可关闭、失败降级不崩**（`ENGINEERING_NOTES` §4.2）。

## 1. 选型总表（按接入阶段排序）

| # | 能力 | 候选模型 | 体积（量化后） | 延迟（8 Elite 实测量级） | 接入方式 | 阶段 |
|---|---|---|---|---|---|---|
| M1 | **点击式精确分割**（点哪修哪 v2） | MediaPipe **Interactive Segmenter**（MagicTouch，image 512） | ~8–12 MB | 30–80ms | MediaPipe Tasks（自带 runtime） | P6 |
| M2 | **人脸 478 关键点**（精细美型锚点：唇形/眼距/眉骨） | MediaPipe **Face Landmarker**（face_landmarker.task） | ~3–6 MB | 10–20ms | MediaPipe Tasks | P6 |
| M3 | **照片自动增强**（曝光/白平衡/对比度建议） | ① 启发式（直方图分位 + 灰世界，零模型）② 小型回归 CNN（<2MB int8，自训） | 0 / <2 MB | ① <5ms ② ~10ms | LiteRT | P6 |
| M4 | **图像超分 / 细节增强**（导出 2x） | **Real-ESRGAN-x4v3-tiny** 或 ESRGAN-mobile 变体（int8，输入 ≤512 tile） | ~8–20 MB | 0.5–2s（分 tile） | LiteRT + 分块推理 | P6 |
| M5 | **人像背景虚化**（景深估计） | **MiDaS-small / Depth-Anything-mobile**（int8） | ~20–50 MB | 50–150ms | LiteRT | P7 |
| M6 | **场景理解 / 智能推荐预设** | **MobileCLIP-S0**（图像塔 int8 + 文本塔按需） | ~40 MB | 30–60ms | LiteRT | P7 |
| M7 | **自然语言调参**（「把天空提亮一点，人像再暖一点」→ EditParams JSON） | **Qwen2.5-0.5B-instruct int4**（~400MB）或 **Gemma-3-1B-it int4**（~600MB），LiteRT LLM / MediaPipe GenAI | 0.4–0.8 GB | 首字 0.3–1s，8–20 tok/s | LiteRT Generative API | P7 |

> 体积/延迟为公开基准 + 同档机型的经验区间，**接入前必须以真机 A1 日志口径实测**（`DebugLog` 已有「模型 sha256 / 加速器 / 耗时」字段的先例）。

## 2. 机型可行性判断（8 Elite 档）

- **跑得动的**：M1/M2/M3/M6 无压力（现有分割 256×256 已跑 GPU 11ms）；M4 需分 tile + int8，2 秒级可接受（导出是一次性动作）；M5 需 fp16→int8 验证精度。
- **勉强 / 有条件的**：M7 —— 0.5B int4 在 8 Elite 上可用（内存峰值 ~1GB，LiteRT mmap 不占堆），但 6–7GB 档机型必须**默认不下载**；模型中心（UI3.0 §N 已画）做内存预算开关（≤2GB 预算、仅 Wi-Fi 下载、可删除）。
- **明确不做的**：GAN 人脸修复（GFPGAN/CodeFormer，百 MB 级 + 时延秒级 + 观感争议）；Stable-Diffusion 级生成（>1.5GB，越过了「精确修图」的产品线）。

## 3. 接入架构（复用既有骨架）

```
core/ml/
├── SkinMaskModel / FaceDetector        # 已有（LiteRT CompiledModel + GPU→CPU 级联）
├── InteractiveSegmenter（M1）           # MediaPipe Tasks 封装：image + 点击点 → mask
├── FaceLandmarker（M2）                 # 输出喂 Beauty 的锚点（替代质心猜）
├── AutoEnhance（M3）                    # 直方图 → EditParams 建议（纯函数，可 JVM 单测）
├── SuperRes（M4）                       # 分块 tile 推理 + 接缝羽化（复用 DetailPass 分带纪律）
└── ModelRegistry                        # 模型中心（§N）的数据源：状态/体积/sha/延迟/开关
```

- **统一降级契约**：每个封装器实现 `createOrNull() → null` 收口 + OOM 计数 + 会话禁用（照抄 `MlMaskProvider` 的既有模式，不发明新范式）。
- **模型分发**：≤20MB 的随包 assets（沿用 noCompress+mmap）；>20MB（M7/M5）走「首次使用时下载到 filesDir + sha256 校验 + 仅 Wi-Fi」，模型中心可见进度。
- **UI 落点**：模型中心（UI3.0 §N 已画：体积/延迟/加速器/降级记录）即 [ModelRegistry] 的渲染；每个模型独立开关（关闭 = 该功能入口隐藏，不是灰按钮）。

## 4. 功能联动（对应 UI3.0 已画的界面）

| 模型 | 解锁的 UI | 已画位置 |
|---|---|---|
| M1 | 点哪修哪 v2：单击 → **像素级**轮廓（替代六类 argmax 的粗命中，拾取骨架本轮已接） | §D |
| M2 | 美型滑块锚点升级（瘦脸/大眼沿 478 点插值）+「笑眼/露齿」检测 | §E |
| M3 | 顶栏「自动」按钮：一键增强（建议值走既有撤销栈，可重置） | §09 顶栏 wand |
| M4 | 导出 Sheet「超分 2x」开关 + 回执标注 | §H |
| M5 | 效果组新增「虚化」滑块（景深图 × 分割蒙版） | §细节 |
| M6 | 预设页「推荐」置顶（CLIP 相似度） | §F |
| M7 | 明暗页顶栏命令条：自然语言 → 参数 diff 预览 → 应用（走撤销栈） | §C 命令条（待画） |

## 5. 明确不做 / 风险

- **不做**：云端推理、账号体系、生成式内容（与「全本地、可复现」定位冲突，§1.3）。
- **风险**：① MediaPipe Tasks 与 LiteRT CompiledModel 双 runtime 并存 ⇒ 进程内存要并入模型中心预算；② M4 tile 接缝必须复用「分带 + halo」纪律，逐位一致性测试先行；③ M7 输出必须过「参数范围钳位 + 撤销栈」，模型幻觉不能直接改照片。
