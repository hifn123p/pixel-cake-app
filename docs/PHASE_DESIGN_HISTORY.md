---
title: 像素蛋糕 App — 阶段设计存档（P1b / P1+ / P2 + 审查修复台账）
status: archived-design（内容已全部落地，仅作追溯与 KDoc 引用目标）
created: 2026-10-06
updated: 2026-10-06
project: D:\AI_Project
supersedes: [P1b_DESIGN.md, P1p_DESIGN.md, P2_DESIGN.md, FIX_LIST.md]
description: P1b 人像精修 / P1+ ML 自动蒙版 / P2 A7C2 直连 三阶段设计稿与审查修复台账（F/R/D 编号）的合并存档。原 5 份稿已在合并后从工作树移除（可从合并前的提交取回）。节号沿用原文，代码 KDoc 的「§15」「F08」「R10」等引用仍然有效。
---

# 阶段设计存档（P1b · P1+ · P2 · 审查台账）

> 本文由 4 份阶段性设计稿合并而成 —— 原 `docs/P1b_DESIGN.md`、`docs/P1p_DESIGN.md`、
> `docs/P2_DESIGN.md`、`docs/FIX_LIST.md`，外加并入 `docs/UI_DESIGN.md` §4.0.8 的 `docs/UI_REDESIGN.md`。
> 这 5 份原稿在合并后**已从工作树移除**；它们都是 HEAD 里有记录的正式提交内容，需要原文时用
> `git show <合并前的提交>:docs/P1b_DESIGN.md`（`<合并前的提交>` 取 `a8a93ce` 或更早）即可取回。
> 本文是仓库内唯一可追溯的那份。
>
> **这四条内容已全部落地。** 本文不再承担「计划」职责，只承担三件事：
> ① 保留「为什么这么设计」的论证与「踩过哪些坑」的实测记录；
> ② 保留 `F01~F24` / `R01~R10` / `D01~D10` 编号台账（**代码 KDoc 直接引用这些编号**）；
> ③ 作为「已写过、已修过，不要重复论证」的判决依据。
>
> **节号沿用原文**：各部分的 §N 就是原文档的 §N —— 因此代码里 `docs/PHASE_DESIGN_HISTORY.md`（P1+ 部分）§9
> 这类引用仍然指向同一处内容，不需要重新编号。
>
> 现行计划见 [`DEV_PLAN.md`](DEV_PLAN.md)；工程纪律与踩坑清单见 [`ENGINEERING_NOTES.md`](ENGINEERING_NOTES.md)；
> 参数体系见 [`TONING_DESIGN.md`](TONING_DESIGN.md)；对象作用域见 [`OBJECT_TONE_DESIGN.md`](OBJECT_TONE_DESIGN.md)；
> 界面见 [`UI_DESIGN.md`](UI_DESIGN.md)。

## 目录

| 部分 | 内容 | 原文档 | 落地状态 |
|---|---|---|---|
| [第一部分](#第一部分--p1b-人像精修) | P1b 人像精修（磨皮 / 液化 / 祛瑕 / 追色 + 预设） | `P1b_DESIGN.md` | ✅ 已落地（Phase 7 可选，后置） |
| [第二部分](#第二部分--p1-ml-自动蒙版) | P1+ ML 自动蒙版与人脸检测（LiteRT） | `P1p_DESIGN.md` | ✅ P1p-1 / P1p-2a / P1p-2b / P1p-2c 已落地 |
| [第三部分](#第三部分--p2-a7c2-usb-直连) | P2 A7C2 USB 直连（自研最小 PTP/MTP） | `P2_DESIGN.md` | ✅ PoC-1~5 已落地（PoC-6 后置） |
| [第四部分](#第四部分--审查修复台账) | 审查修复台账 F / R / D | `FIX_LIST.md` | ✅ 除明确标注「后置」者外均已修复 |

---

# 第一部分 · P1b 人像精修

> 状态：**已落地**（2026-09-11 起；Phase 1-6 全部完成并经 CI `testDebugUnitTest` + `lintDebug` 验证）。
> Phase 7（F03② 后台母版无缝切换）为**可选画质优化，后置** —— 感知延迟已由「打开即显进度 +
> 预览走 `halfSize` 代理」缓解。P1b-6 真机验收在用户侧的 A7C2 实拍上执行。
> §11 的 5 个待拍板项**已按推荐项执行**（Q1① / Q2 画笔作用域 / Q3 box 近似 / Q4 参数栈预设 / Q5 并入）。
>
> 依据（写此稿前已读真实代码）：
> - `EditParams` 是**扁平结构体**（曝光/对比/饱和/温色调/阴影高光/曲线/LUT），不是有序 op 列表；
> - `PixelProgram.finish()` 是**点态单 pass**（WB→曝光→sRGB→阴影高光→对比→饱和→曲线→LUT），逐像素零分配；
> - `EditEngine` 分带渲染（3 个入口：`renderIntoSrgb` / `renderIntoLinear` / `renderLinearFile`），
>   16-bit 线性母版留在 native，JVM 只持目标 8-bit Bitmap。
>
> **核心约束（决定架构）**：4 个人像算子都是**空间 / 蒙版 / 整图统计型**，塞不进现有点态 `finish()`。

## §0 范围与交付

| 项 | 本阶段做 | 本阶段不做 |
|---|---|---|
| 人像算子 | 中性灰磨皮 / 美型液化 / 祛瑕 / 追色（**CPU**） | GPU/AGSL 实时化（后置） |
| 蒙版 | **画笔蒙版**（手绘 skin 作用域） | ML 自动蒙版（→ 第二部分，LiteRT） |
| 预设 | 参数栈预设 + 可选 `.cube` LUT 打包 | DNG/XMP/不明许可源一律不用 |
| 渐进占位 | F03② 代理秒进 + 后台母版无缝切换 | 后置（见上） |
| 真机测试 | 统一测试口径 + DebugLog 关注点 | 用户真机侧执行 |

## §1 现状管线回顾（写此稿时的事实）

```
ARW 母版 (native, 16-bit 线性, ~196MB，留在 native)
   │  RawLinearSource.readRows(BAND_ROWS)  —— 每次只拉 32 行 ≈1.3MB
   ▼
[分带] PixelProgram.finish()  点态：WB×曝光增益 → sRGB 编码 → 阴影/高光 → 对比 → 饱和 → 亮度曲线 → 内置 LUT
   │  结果写入目标 Bitmap（8-bit ARGB_8888）
   ▼
目标 Bitmap（JVM，全分辨率 ~131MB / 代理 ~16MB）
```

- 预览（滑块实时）：`renderIntoLinear(代理或母版 LinearImage, 代理尺寸 target)`。
- 导出：`renderLinearFile` 全分辨率边解边渲，不把整幅母版搬进 JVM。

## §2 算子分层架构（本部分核心）

把管线拆成两层：**点态 tonal 层复用现有 `PixelProgram`，retouch 层作为「渲染后整图 pass」叠加在已物化的目标 Bitmap 上**。

```
母版 (native 16-bit)
   │  [分带] PixelProgram（WB/曝光/曲线/LUT，零改）  ← 复用
   ▼
目标 Bitmap（8-bit，已物化）
   │  [整图 pass] RetouchLayer（消费 Mask，按顺序执行空间算子）
   │     1. 中性灰磨皮   (mask = skin，邻域表面模糊 + 强度混合)
   │     2. 美型液化     (mask = 作用域，几何 remap 重采样)
   │     3. 祛瑕         (inpaint strokes，局部补洞)
   │     4. 追色         (无 mask，全局统计匹配)
   ▼
预览 / 导出产物
```

**为什么不在分带内做空间算子**：磨皮要邻域、液化要重映射、追色要整图统计；在目标 Bitmap 已物化后做，
复用已有的 131MB（代理仅 ~16MB），不再额外搬母版，符合内存纪律。
retouch 层对预览（代理）与导出（全分辨率）用**同一算法 + 同一 Mask（按比例重采样）**，仅分辨率不同
→ 保证「预览所见 = 导出所得」。

## §3 参数模型：保持扁平，新增 `RetouchState`

- **采用方案①**：`EditParams` 保持扁平（已验证的 `PixelProgram`/`EditHistory` 不动）；
  retouch 作为独立 `RetouchState` 附加层。

  ```kotlin
  data class RetouchState(
      val neutralGray: NeutralGrayParams = NeutralGrayParams(),  // strength, radius
      val beauty: BeautyParams = BeautyParams(),                 // slimFace, slimJaw, eyeEnlarge + 作用域
      val inpaint: List<InpaintStroke> = emptyList(),            // x,y,r,seed
      val colorTransfer: ColorTransferParams = ColorTransferParams() // refId, intensity
  )
  ```

  - 撤销/重做：`EditHistory` 管 tonal；`RetouchState` 并入同一快照（最终落地为 `EditSnapshot`）。
- **否决方案②**（改有序 op 列表）：侵入大，影响 `EditHistory`/`PixelProgram` 已验证逻辑，收益有限。
- 导出/预览共用 `EditParams + RetouchState + Mask` 三元组。

## §4 `RetouchMask` 抽象（P1 画笔蒙版，P1+ ML 复用同一接口）

```kotlin
interface RetouchMask {
    /** 返回 [0..1] 作用强度；px,py 为目标图坐标。 */
    fun sample(px: Int, py: Int): Float
    /** 按目标分辨率重采样（预览→导出 比例变换）。 */
    fun resampleTo(w: Int, h: Int): RetouchMask
}
```

- **P1 来源**：编辑器画笔（soft brush，半径 + 羽化）→ 累加到 `RasterMask`。
- **消费**：retouch 算子 `out = lerp(orig, op(orig), strength * mask(px,py))`。
- **⚠️ `mask == null` 语义（全仓统一，**禁止**各算子自行解释）**：`null` = **未圈定作用域 → 该算子不执行**，
  **不是**「全局生效」。适用于全部皮肤类算子（`NeutralGray` 磨皮、`Beauty` 液化）。
  - 由来：相机批量链路（`CameraBatch` 显式传 `mask = null`）借此避免把背景一起磨/形变。
  - 「要全局效果」的**正确做法**：调用方显式传**全幅蒙版**（`object FullMask`，`Mask.kt`），
    而不是依赖 `null` 的隐含含义（`null` 只有「不执行」一种解释）。
  - 两条入口的分工：
    - **编辑器**：无画笔描迹时 `RetouchScale.skinMask` 返回 `FullMask`（作用域 = 整幅），
      故「磨皮」滑杆在未涂抹前**依旧全局可见效果**，与 P1 旧行为一致；`MainActivity.buildSkinMask` 直接委托 `RetouchScale`。
    - **相机批量链路**：`CameraBatch` 显式传 `null` → **不执行磨皮/液化**，只做点态调色 + 祛瑕 + 追色。
  - 断言单测护栏：`NeutralGrayTest.noOpWhenMaskNull`、`BeautyTest.noOpWhenMaskNull`、
    `RetouchScaleTest.noStrokesYieldsFullMask`。
  - 这条约定是被**真机 bug 逼出来的**：曾一度 `NeutralGray` 把 `null` 当 `1f`（全局全强度）、
    `Beauty` 把 `null` 当 `return`，同一预设经两条入口得到两种结果（详见第四部分 R08）。
- **P1+ 扩展点**：`MlSkinMask` 实现同一接口（FaceDetector → skin 概率图），UI 无需改。

## §5 各算子设计

### 5.1 中性灰磨皮 `NeutralGray`
- **算法**：表面模糊（surface blur）压低中频皮肤纹理、保边缘；强度由 skin mask 调制。
- **参考**：Rust 引擎 `crates/engine/src/retouch/neutral_gray.rs`（跨仓库 `pixel-cake`）。
- **实现（CPU）**：分离式多趟 box blur 近似表面模糊（阈值内才混合，超阈值保边缘）；皮肤区 `lerp(orig, blurred, strength*mask)`。
- **内存纪律**（后续 R10 修正）：**分带**（`BAND_ROWS=256` + 保留上一带 `radius` 行**原始** halo）。
  必须留存原始 halo —— 核心行是原位写回的，上一带写过后顶部 halo 已非原始值，不能再当源用。
- **性能**：代理 2048 下多趟极快；全分辨率单次导出异步 + 进度，可接受。

### 5.2 美型液化 `Beauty`
- **算法**：瘦脸/瘦下颌/大眼几何形变（liquify remap），作用域由 mask 限定。
- **参考**：`crates/engine/src/retouch/beauty.rs`。
- **P1 简化**：**不依赖人脸检测**，提供滑块（瘦脸/瘦下颌/大眼）+ 画笔作用域；remap 用双线性重采样源像素。
  人脸关键点自动定位在 P1+ 补齐（→ 第二部分 §15.6）。
- **实现**：对目标 Bitmap 逐像素按形变场反查源坐标，mask 内强度插值。
- **⚠️ 方向（曾被写反，R05）**：`dx += k(x-cx)` 才是「收拢」。写作 `-=` 会让输出点采到更靠质心的源点
  ⇒ 实际是**放大**，与「瘦脸」语义相反。已有方向断言单测钉死。
- **⚠️ 不能分带**（R10）：液化是后向映射，`slimJaw` 把源点拉到带上方、`eyeEnlarge` 拉到质心下方，
  跨越多带 ⇒ 任何单趟带状原地处理都会读到已写值。改走**蒙版包围盒**：蒙版外 `mv==0` 恒等，
  故只在包围盒开缓冲是此算子唯一「既精确又有界」的口径。

### 5.3 祛瑕 `Inpaint`
- **算法**：用户画笔描 stroke（x,y,r）→ 对每 stroke 区域用外环邻域（均值/中值/Telea 近似）填充。
- **参考**：`crates/engine/src/retouch/inpaint.rs`。
- **实现**：stroke 列表 → 逐 stroke 用其外环邻域稳健填充；mask 由 stroke 生成。按笔画开**小图块**（`Inpaint.applyOne`，局部坐标）。

### 5.4 追色 `ColorTransfer`
- **算法**：将当前图颜色统计（分通道 mean/std）向参考帧匹配；全局、无 mask。
- **参考**：`crates/engine/src/retouch/color_transfer.rs`。
- **参考帧来源**：预设内置 ref（如 `portrait_film`）或用户从相册选图。
- **实现**：改**两遍只读统计 + 一遍写入**（`accumulateSum` / `accumulateVariance` / `applyWithStats`），
  保持原求和顺序 ⇒ 与整幅实现**位等价**。

## §6 预设（P1b-5~10）

- **形态 A 参数栈预设**（先做、已落地）：Portra 800 / Fuji / 复古褪色 / 莫兰迪 / 日系…
  —— 曲线 + 橙色明度微调（保肤色）+ `colorTransfer` ref。零授权风险，自研。
  最终落地为 **44 套分类预设**（`core/edit/preset/Presets.kt`）。
- **形态 B `.cube` 3D LUT**（可选）：仅取 **MIT/CC 可再分发**源
  （`shravankumar147/photo-edit-app` 的 `cinematic.cube`/`fuji_fp-100c_alt.cube`；`mv-lab/NILUT` CC4.0）。
  构建期转 3D LUT（33³/64³ half-float）打包 asset，运行时采样 + 三线性插值。
- **许可红线**：逐个核 LICENSE/NOTICE；**社区流传的 Lightroom DNG/XMP 预设不是 `.cube`，一律不用**。
- **存储**：预设 JSON 与编辑栈同格式；本轮落地为 assets + 代码内建（Room 规划中）。

## §7 F03② 两段式渐进占位（**后置，未落地**）

- **现状**：`ArwFullDecoder.open(halfSize=true)` 已能快速解（`half_size=1 + user_qual=0`，解码量 ~1/4）；
  编辑器启动即走代理。
- **原设计**：
  1. 打开 ARW → `openLinear(halfSize=true)` 得**代理线性** → 立即 `renderIntoLinear` 出可交互预览（秒开）。
  2. 同时 `LaunchedEffect` 后台 `openLinear(halfSize=false)` 开**全质量母版会话**；就绪后无缝切换（替换 base `LinearImage`，重渲一次）。
  3. 切换对用户无感（仅一次重渲）；母版未就绪时滑块拖动仍用代理（画质略低但跟手）。
- **同步**：`AtomicBoolean ` / State 标记「母版就绪」，避免重复 open；协作取消沿用既有机制。
- **为什么后置**：感知延迟已由「打开即显解码进度 + 预览本就走 `halfSize` 代理」缓解；
  这是一次架构改动（两条母版会话并存 ⇒ 内存翻倍），收益需真机画质基准支撑。

## §8 P1b-6 统一真机测试口径（用户真机侧执行）

- **真机**：一加15（骁龙 8 Elite / 16GB）。
- **重点验证**：
  - 协作取消在多 pass retouch 下仍跟手、无积压。
  - 33MP 导出峰值内存：retouch 整图 pass **不额外翻倍数**（复用 target，禁并行多份）。
  - 画笔蒙版交互；代理→母版切换（F03②）。
- **DebugLog 关注**：`DECODE`（走 LibRaw / halfSize）、`EDIT`、`RETOUCH`、`ERROR`。
- **验收**：导入 A7C II ARW → 调曝光/曲线/预设/磨皮/液化/祛瑕/追色 → 导出相册，无 OOM/卡死。

## §9 模块接口（实际落点）

| 文件 | 变更 | 说明 |
|---|---|---|
| `core/edit/EditModel.kt` | 保持 | tonal 参数不动 |
| `core/edit/RetouchState.kt` | **新增** | 4 算子参数 |
| `core/edit/Mask.kt` | **新增** | `RetouchMask` 接口 + `RasterMask` + `FullMask` |
| `core/edit/retouch/NeutralGray.kt` | **新增** | 表面模糊（box 近似，分带） |
| `core/edit/retouch/Beauty.kt` | **新增** | remap 液化（包围盒） |
| `core/edit/retouch/Inpaint.kt` | **新增** | stroke 补洞（小图块） |
| `core/edit/retouch/ColorTransfer.kt` | **新增** | 全局统计匹配（两遍） |
| `core/edit/retouch/RetouchLayer.kt` | **新增** | 整图 pass 编排（`PixelStore` 抽象，无全幅缓冲） |
| `core/edit/RetouchScale.kt` | **新增** | 归一化 retouch 参数 → 渲染态；蒙版唯一生产入口 |
| `core/edit/EditEngine.kt` | 改 | tonal 后追加 RetouchLayer pass |
| `ui/editor/ParamPanel.kt` | 改 | 画笔蒙版 UI、retouch 滑块、预设面板入口 |
| `core/edit/preset/Presets.kt` | **新增** | 预设参数栈 |

## §10 实施顺序（每 phase 独立提交 + CI）

1. **Phase 1**：`RetouchMask` + `RasterMask` + 画笔 UI 骨架。
2. **Phase 2**：中性灰磨皮（跑通 RetouchLayer 闭环 + 单测）。
3. **Phase 3**：美型液化。
4. **Phase 4**：祛瑕。
5. **Phase 5**：追色。
6. **Phase 6**：预设（参数栈预设）。
7. **Phase 7**：两段式渐进占位 → **后置**（§7）。
8. **穿插**：P1b-6 真机测试（用户侧），每块后下 APK 自测。

## §11 已拍板决策（原「待评审」，结论已执行）

| # | 决策 | 结论 |
|---|---|---|
| Q1 | 参数模型 | ① 扁平 `EditParams` + 新增 `RetouchState` |
| Q2 | 美型液化 | 先用画笔作用域 + 滑块，不等人脸检测（P1+ 再补锚点） |
| Q3 | 中性灰算法 | box 近似表面模糊（CPU 友好） |
| Q4 | 预设范围 | 先做参数栈预设；`.cube` 视需要 |
| Q5 | F03② 排期 | 并入本批；实际后置（§7） |

---

# 第二部分 · P1+ ML 自动蒙版

> 状态：**P1p-1a / 1b / 2a / 2b / 2c 全部已落地**（P1p-1c、P1p-2c 的真机验收在用户侧）。
> 目标：让「磨皮 / 液化」在**用户不涂画笔**时也能自动只作用在皮肤/人像上，而不是退化成「整幅生效」。
> P1b 的 `RetouchMask` 接口与 `FullMask` 语义已就位，本阶段**只是新增一个 `RetouchMask` 实现 + 接线**，UI 结构无需改动。

## §0 一句话结论

用 **LiteRT**（不是 TFLite + NNAPI）跑 **MediaPipe `selfie_multiclass_256x256`**（Apache-2.0，6 类含 `face-skin` / `body-skin`）
→ 输出 256×256 皮肤概率网格 → 包装成 `MlSkinMask : RetouchMask`
（**低分辨率网格 + 按需双线性采样，绝不物化整幅 `FloatArray`**）
→ 编辑器/批处理在「有 ML 蒙版」时优先用它，失败则按原逻辑回退（画笔 → `FullMask`）。

### §0.1 实施进度

| 批次 | 内容 | 状态 |
|---|---|---|
| **P1p-1a** | 依赖接入（`com.google.ai.edge.litert:litert:2.2.0`）+ 模型入库 + `core/ml/` 内核 + JVM 单测 + 许可声明 | ✅（CI 实证 `litert:2.2.0` 可解析 + native 打包，run `34699770791`） |
| **P1p-1b** | UI 接线：编辑器「自动蒙版」开关（默认开）；`RetouchScale.editorSkinMask` 合成「ML ∪ 画笔取 `max`」；预览 + 导出四条路径接入 | ✅ |
| **P1p-1c** | 真机验收（一加15）：核对日志「模型加载成功 / 是否走 GPU」、自动蒙版作用范围、无 OOM | ⬜ 用户侧 |
| **P1p-2a** | **检测内核（纯 Kotlin）**：`FaceAnchors` + `FaceDetectionPostProcess` + `Letterbox` + `FaceDetection` + 3 组 JVM 单测 | ✅（`2448e99`） |
| **P1p-2b** | **运行时**：模型入库 + `FaceDetector` + `LiteRtFaceDetector`（GPU→CPU 级联）+ `MlFaceProvider` + `NOTICE` | ✅（`88a0648`） |
| **P1p-2c** | **接线**：检测出的脸中心/眼心喂 `Beauty.centroid` / `eyeCentroid` + UI 回显锚点来源 | ✅（口径见 §15.6） |

> P1p-1a 的定位是**先验证风险最高的那一步**：`litert` 只在 Google Maven、且含 native 库，
> 能否在 CI 正常解析/打包是本批最大未知数；内核与单测先落地，接线再跟上。

**模型实际落地信息**（已写入根 `NOTICE`）：

| 项 | 值 |
|---|---|
| 随包路径 | `app/src/main/assets/models/selfie_multiclass_256x256.tflite` |
| 体积 | **16,371,837 字节（≈15.6 MiB）** |
| SHA-256 | `c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0` |
| 下载源 | `https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite` |
| 许可 | Apache-2.0（原样随包，未修改） |

## §1 现状：接口已就绪，只差实现

| 已有 | 位置 | 对本阶段的意义 |
|---|---|---|
| `RetouchMask` 接口 | `core/edit/Mask.kt` | ML 蒙版只需实现 `sample` / `resampleTo` |
| `FullMask`（整幅，O(1)） | `core/edit/Mask.kt` | **降级目标**：ML 不可用时退回，行为等同现状 |
| `RasterMask`（画笔栅格） | `core/edit/Mask.kt` | 与 ML 蒙版的**合并**对象（见 §7） |
| `RetouchScale.skinMask(...)` | `core/edit/RetouchScale.kt` | 唯一蒙版生产入口，编辑器与批处理共用 |
| `RetouchLayer.apply(bitmap, state, mask)` | `core/edit/retouch/RetouchLayer.kt` | 消费口，**不需要改**（已流式化，对蒙版只读） |
| `Beauty.centroid(mask, w, h)` | `core/edit/retouch/Beauty.kt` | 液化作用中心；P1p-2 起可由人脸关键点直接喂入 |
| `null` 语义 = 不执行 | `Mask.kt` KDoc + P1b §4 | ML 蒙版**不改变**这条约定 |

> ⚠️ 蒙版消费端已全部流式化，**ML 蒙版必须同样守内存纪律**：`resampleTo(7008, 4672)` 若照
> `RasterMask` 那样开 `FloatArray(w*h)`，就是 **131MB** 一次性分配 —— 本设计明确禁止（见 §8）。

## §2 技术选型

### §2.1 ❌ NNAPI：已废弃，不能再作为目标

原口径「TFLite + NNAPI delegate（NNAPI→GPU→CPU 降级）」**已过期**：

- Android **15 起 NNAPI 被官方废弃**，Google 的迁移指引是改用 LiteRT 的 accelerator；
- LiteRT 的 NNAPI delegate 页面已重定向到「NNAPI 迁移指南」，旧 Hexagon delegate 页面已下线；
- Google 的「Choosing a Delegate」现只列 GPU delegate 与 Core ML delegate（iOS），
  **其余厂商加速统一走 LiteRT v2 `CompiledModel` 的 NPU 通道**；
- TensorFlow Lite 本体进入维护模式（只收安全/稳定性修复），新特性只在 LiteRT。

### §2.2 ✅ 采用 LiteRT v2 `CompiledModel`

| 项 | 结论 | 依据 |
|---|---|---|
| 依赖 | `com.google.ai.edge.litert:litert`（`2.2.0` 起） | **仅在 Google Maven**（非 Maven Central）⇒ 需确认 `settings.gradle.kts` 有 `google()` |
| ABI | 只留 `arm64-v8a`（项目已如此） | 现有 `abiFilters` |
| API | `CompiledModel.create(...)` + `createInputBuffers()/writeFloat()/run()/readFloat()` | 官方 Kotlin 指南 |
| 加速级联 | `Accelerator.GPU` → 失败回落 `Accelerator.CPU` | v2 中 GPU 加速器**已并入主包**（`litert-gpu` 停在 1.4.2 的旧 Interpreter 时代） |
| NPU（后续可选） | `Accelerator.NPU` + `Environment.create(BuiltinNpuAcceleratorProvider(ctx))`；Snapdragon 需 `qnn-litert-delegate` + `qnn-runtime` | 一级骁龙 8 Elite；**先不做**，避免引入厂商闭源依赖 |
| 遗留 Interpreter | 2.x 包内仍含（可无缝迁移），但 **v2 Maven 上 GPU 只在 `CompiledModel` 可用** | 故直接用 `CompiledModel`，不要走 Interpreter |

### §2.3 为什么不用 MediaPipe Tasks / ML Kit

- **MediaPipe Tasks Vision**（`com.google.mediapipe:tasks-vision` + `.task` 包）：封装了预处理/输出蒙版，代码最少；
  但（a）引入更重的依赖层，（b）accelerator 由其内部 OpenGL delegate 管，不如 `CompiledModel` 可控，
  （c）本项目一贯自持底层（LibRaw/PTP/retouch 都是自写），且该模型前后处理**确实极简**（256×256 归一化 + 6 通道 argmax）。
- **ML Kit**：模型/推理在 Google Play Services 内，**运行时可能需下载模型**，与「全程本地、离线可用」的硬约束冲突；
  且模型权重不可审计。
- **自训/转换 ONNX**：需把 Rust 仓库 `detect/*.rs` 的权重转 TFLite，转换链在无本地环境的前提下风险高、
  且模型许可需重新审计。**后置**。

> 附带的协同收益：`selfie_multiclass_256x256.tflite` 是标准 tflite，**P3 的 NAS 侧可用 OpenVINO 直接读同一个模型**
> （OpenVINO 官方 notebook 正是用它做 Intel 核显推理），端侧与 NAS 侧可共用同一份权重与同一套后处理口径。

## §3 模型选型

MediaPipe Image Segmenter 提供 4 个候选（官方页数据）：

| 模型 | 输入 | 量化 | 输出类别 | Pixel 6 CPU/GPU 延迟 | 说明 |
|---|---|---|---|---|---|
| SelfieSegmenter (square) | 256×256 | float16 | 背景 / 人 | 33.5 / 35.2 ms | 最轻，但**只有人/背景**（衣服头发一起「是皮肤」） |
| SelfieSegmenter (landscape) | 144×256 | float16 | 背景 / 人 | 34.2 / 33.6 ms | 同上，横构图更省 |
| HairSegmenter | 512×512 | float32 | 背景 / 头发 | 57.9 / 52.1 ms | 只为头发换色，不合用 |
| **SelfieMulticlass (256×256)** ✅ | 256×256 | float32 | **0 背景 / 1 头发 / 2 body-skin / 3 face-skin / 4 衣服 / 5 其它** | 217.8 / 71.2 ms | **唯一能直接给出「皮肤」类**，正合磨皮需求 |
| DeepLab-V3 | 257×257 | float32 | 通用 21 类 | 123.9 / 103.3 ms | 通用分割，非人像皮肤 |

**选型：`selfie_multiclass_256x256`（float32）**

- 输入：`float32 [1,256,256,3]`，RGB，**除以 255** 归一到 `[0,1]`
- 输出：`float32 [1,256,256,6]`，逐像素 6 类概率（argmax 即标签）
- 体积约 **15.6MB**（无官方 fp16/int8 变体）；许可 **Apache-2.0**
- 延迟：Pixel 6 CPU 218ms / GPU 71ms；一加15（骁龙 8 Elite）预期远快于此，
  且蒙版**每张图只算一次**（不随滑杆重算），CPU 路径亦可接受

> 权衡：多类模型的 float32 体积与 CPU 延迟都不如二类小模型；换来的是**磨皮只作用于皮肤、不糊衣服**，
> 以及为「祛瑕/追色」提供可扩展的类别底图。若嫌重可退化为 SelfieSegmenter（~0.4MB），但磨皮精度显著下降。

## §4 分阶段实施

| 阶段 | 内容 | 交付物 | 状态 |
|---|---|---|---|
| **P1p-1** | 自动皮肤蒙版：LiteRT + multiclass 模型 → `MlSkinMask`；编辑器「自动蒙版」开关；失败降级 | 开开关后磨皮只作用皮肤，背景/衣服纹理保留 | ✅ |
| **P1p-2** | 人脸检测 + 关键点 → 自动给液化定 `centroid`（脸中心）与大眼中心；替代「按蒙版质心猜」 | 液化不用手画也能对准脸 | ✅ |
| **P1p-3**（可选） | 更细的人脸解析（唇/眼/眉）用于祛瑕与局部提亮；或引入 NPU 加速（QNN） | 按需 | ⬜ 后置 |

## §5 代码结构（`core/ml/`）

```
core/ml/
├── SkinMaskModel.kt        # 接口：val side / val acceleratorName / fun inferProbs(argb: IntArray): FloatArray? / fun close()
├── LiteRtSkinMaskModel.kt  # LiteRT/CompiledModel 实现（GPU→CPU 级联；assets 读模型）
├── SkinMaskPostProcess.kt  # 纯函数：6 通道概率 → 皮肤概率网格（可 JVM 单测）
├── FloatGrid.kt            # 低分辨率浮点网格 + 双线性采样（无 Android 依赖）
├── MlSkinMask.kt           # RetouchMask 实现：持 FloatGrid，sample 双线性、resampleTo 不分配整幅
├── MlMaskProvider.kt       # 单例：懒加载模型 + 每图一次缓存 + 失败降级 + 日志
│
│   # —— P1p-2 人脸检测 ——
├── FaceAnchors.kt          # 纯函数：SSD anchor 生成（FULL_RANGE / SHORT_RANGE 预置）
├── FaceDetectionPostProcess.kt  # 纯函数：16 维 raw → 框 + 6 关键点 + 加权 NMS
├── Letterbox.kt            # 纯函数：等比缩放 + 居中 padding 的几何换算（含投回源图）
├── FaceDetection.kt        # 数据类：NormFace（张量归一化坐标）/ FaceDetection（源图像素坐标）
├── FaceDetector.kt / LiteRtFaceDetector.kt / MlFaceProvider.kt
├── ObjectMasks.kt          # 批次 5：一次分割推理的全部作用域蒙版（见 OBJECT_TONE_DESIGN.md）
└── (P1p-3) FaceLandmarker.kt（478 点网格）
```

**可测性设计**：`SkinMaskModel` / `FaceDetector` 是接口 → JVM 单测注入 fake，**测试完全不碰 LiteRT 原生库**。
**零 Android 依赖**：`FloatGrid` / `SkinMaskPostProcess` / `MlSkinMask` / `FaceAnchors` / `Letterbox` 均为纯 Kotlin。

## §6 数据流

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

**每图一次缓存**：以 `(源图标识, 尺寸, 会话代次)` 为 key，预览与导出**共用同一蒙版对象**
（`sample` 按目标坐标自适应），因此「预览所见 = 导出所得」天然成立，且不重复推理。
⚠️ 缓存键的构造有一条硬约束（**不能用 `Bitmap` 的实例身份**），见 `ENGINEERING_NOTES.md`。

## §7 接线口径（与画笔蒙版的关系）

`RetouchScale.skinMask(...)` 扩展为可接收 ML 蒙版，优先级：

```
ML 蒙版（开启且成功） ──┬─ 有画笔描迹 → 合并（取 max，见下）
                        └─ 无画笔描迹 → 直接用 ML 蒙版
         ↓ 不可用
画笔描迹栅格
         ↓ 无描迹
FullMask（整幅生效）
```

- **合并口径**：`sample = max(ml, brush)`。理由：两者都是「作用强度」，画笔是用户**显式补正**
  （比如 ML 漏了脖子），取 max 才符合直觉；若取 min 会出现「涂了反而没效果」。
- 编辑器有「自动蒙版（AI）」开关，默认**开**（不可用时置灰并提示）。
  批处理（`CameraBatch`）保持传 `null` 不变（不执行皮肤类算子）——若要给批量也开自动蒙版，
  是**独立的产品决策**，未做。
- `null` 语义**不变**：ML 不可用 ≠ `null`，仍回退到 `FullMask`（编辑器）语义。

## §8 内存纪律（硬约束）

| 项 | 要求 |
|---|---|
| 推理输入 | 仅 `256×256` Bitmap（256KB 级）；**不得**把全分辨率图喂进去 |
| 蒙版存储 | `FloatGrid` = `FloatArray(256*256)` ≈ **256KB**；**绝不** `FloatArray(w*h)`（33MP = 131MB） |
| `resampleTo(w,h)` | **直接返回 this**（靠 `sample` 的双线性自适应），零分配 |
| 概率中间量 | 6×256×256 的 `FloatArray` ≈ 1.5MB，用完即弃；可复用缓冲避免反复分配 |
| 缓存 | 只缓存最终 `FloatGrid`，不缓存 Bitmap 副本 |
| 模型 | mmap 自 assets；`noCompress` 保证可映射 |

## §9 降级链与可观测性

失败**永不崩、永不静默**：

| 失败点 | 行为 | 日志 |
|---|---|---|
| assets 缺模型 / 打开失败 | GPU→CPU 都失败 → 返回 `null` → 蒙版按 §7 回退 | `ML` WARN，含原因 |
| `CompiledModel.create(GPU)` 抛异常 | 回落 CPU 重试一次 | `ML` INFO（加速器降级）+ 实测耗时 |
| 推理抛异常 / 返回尺寸异常 | 返回 `null`，回退 | `ML` ERROR + 堆栈摘要 |
| 内存不足（OOM） | 捕获 → 回退；并**关闭本会话自动蒙版**避免反复触发 | `ML` ERROR |

DebugLog 复用 tag `ML`。启动快照里 dump「LiteRT 版本 / 实际选中的 accelerator / 模型 sha256 前 8 位」，
便于真机回传核对。

## §10 预算（实测口径）

- **包体**：分割模型 16.37MB + 人脸模型 0.68MB + LiteRT 运行库 arm64 增量 ~2–4MB
  （未压缩 assets 会进 APK，注意 `noCompress`）。继续明显缩包需量化/蒸馏分割模型；
  **在没有真机画质基准前不应以牺牲蒙版质量换体积**。
- **首帧延迟**：模型一次性加载（冷启动首次推理含加载，预计 200–600ms）。
- **内存**：蒙版 ≤1MB，推理输入/中间量 ≤2MB —— 相对 retouch pass 的工作集，**可忽略**。

## §11 单测口径（JVM，跑在 CI 上）

| 用例 | 断言 |
|---|---|
| `SkinMaskPostProcess` 6 通道 → 皮肤概率 | 构造已知概率图，逐位比对期望（纯函数） |
| 阈值/羽化映射 | lo/hi 边界、单点、单调性 |
| `FloatGrid.sample` 双线性 | 与手写朴素双线性逐位一致（含边界 clamp） |
| `MlSkinMask.resampleTo(w,h)` | `assertSame`（不分配）+ 任意坐标采样与低分辨率双线性一致 |
| 合并口径 `max(ml, brush)` | 两分支（ML 强 / 画笔强），见 `MaskCombineTest` |
| 降级分支 | fake model 返回 null / 抛异常 → `skinMask` 回退到画笔或 `FullMask` |

> 推理正确性（真实 LiteRT 调用）**不进 JVM 单测**（原生库不可用），靠真机验收 + 日志；
> 这与既有「LibRaw 走真机验收」的策略一致。

## §12 已拍板决策（原「待拍板」）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 模型变体 | `selfie_multiclass`（15.6MB，能区分皮肤/衣服） |
| 2 | 模型入库方式 | **提交进仓库** `app/src/main/assets/models/`（可复现、CI 不依赖外链） |
| 3 | 加速器 | 一次到位 `GPU→CPU` 级联 |
| 4 | 默认开关 | 编辑器「自动蒙版」默认**开** |

## §13 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| 新增 Maven 依赖（Google Maven）在 CI 解析失败 | 中 | 已确认 `settings.gradle.kts` 有 `google()`；`litert:2.2.0` 已 CI 实证 |
| LiteRT `CompiledModel` API 与文档示例有出入（无法本地编译验证） | 中 | 严格照官方 Kotlin 指南写；CI 编译兜底；跑通后固化为注释 |
| 模型 float32 15.6MB 让 APK 明显变大 | 低 | 可接受；后续可换量化模型 |
| 推理在低端机过慢 | 低 | 只跑一次 + GPU 级联 + 可关开关 |
| 模型许可（Apache-2.0）合规 | 低 | `NOTICE` 增补模型出处 + 模型卡链接；随包携带 |
| 蒙版边缘生硬（256×256 上采样） | 中 | 概率场本身是软的 + 羽化参数；必要时对网格做一次 3×3 平滑 |

## §14 参考

- LiteRT Android Kotlin API（CompiledModel）：<https://developers.google.cn/edge/litert/next/android_kotlin>
- TFLite → LiteRT 迁移（含 Maven 坐标与 v1/v2 路径）：<https://www.tensorflow.org/lite/guide/roadmap>
- MediaPipe Image Segmenter（模型清单/规格/延迟）：<https://developers.google.cn/edge/mediapipe/solutions/vision/image_segmenter>
- NNAPI 废弃（Android 15）：Android Developers「NNAPI 迁移指南」
- OpenVINO selfie segmentation notebook（同一模型 + Intel 核显，P3 参考）：<https://docs.openvino.ai/2024/notebooks/tflite-selfie-segmentation-with-output.html>

## §15 P1p-2 人脸检测：实测 I/O 与实现口径

> **本节所有数字均来自真实文件的离线解析**（隔离 venv + `pip install --no-deps tflite flatbuffers`，
> 直接读 flatbuffer 打印张量规格），**不是照抄文档** —— 模型卡/文档实测有两处过时。
> 下载源：`https://storage.googleapis.com/mediapipe-assets/<name>.tflite`；解码参数出自同目录
> `mediapipe/modules/face_detection/face_detection_*.pbtxt`（**不在 `.tflite` 里**）。

### §15.1 候选实测（三选一，均 Apache-2.0；`custom_ops = 0` ⇒ LiteRT GPU/CPU 直跑，无需厂商 delegate）

| 模型 | 体积 | 输入 | 输出 | anchor 规格 |
|---|---|---|---|---|
| `face_detection_short_range` | 229,032 B | `input` `[1,128,128,3]` f32 | `regressors[1,896,16]` + `classificators[1,896,1]` | SSD：4 层 strides 8/16/16/16、896 anchors、scale 128、阈值 0.5 |
| `face_detection_full_range` | 1,083,786 B | `input` `[1,192,192,3]` f32 | `reshaped_regressor_face_4[1,2304,16]` + `...[1,2304,1]` | CenterNet 式：1 层 stride 4（48×48）、2304 anchors、scale 192、阈值 0.6 |
| **`face_detection_full_range_sparse`** ✅ | **676,746 B** | `input_1` `[1,192,192,3]` f32 | `Identity[1,2304,16]` + `Identity_1[1,2304,1]` | 同上；**MediaPipe 自家 full-range 管线用的就是它** |

- **两处对文档的更正**：① 实测输入是 **FLOAT32**（非 float16）；② full/sparse 张量是 **192×192**
  （模型卡写 sparse「160×192」是旧值）。
- **选型**：`face_detection_full_range_sparse.tflite` —— 后摄向（A7C2 旅行照）、体积最小、
  解码配置与 dense 版**完全一致**，故 dense/sparse 可互换。
- **输入归一化**：`[-1.0, 1.0]`（即 `pixel/127.5 − 1`），由 pbtxt 的 `output_tensor_float_range` 明示。

### §15.2 解码链路（与 MediaPipe 一致，逐项对照 C++ 源码）

1. **预处理**：等比缩小 + **居中** padding 到 192×192（`keep_aspect_ratio: true` + `border_mode: BORDER_ZERO`）
   → 归一化 `[-1,1]`。⚠️ **不能直接拉伸**成方图（改变人脸比例，召回明显下降）。
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

### §15.3 代码映射

| 文件 | 职责 | 批次 |
|---|---|---|
| `core/ml/FaceAnchors.kt` | anchor 生成（`FULL_RANGE` / `SHORT_RANGE` 预置规格） | P1p-2a ✅ |
| `core/ml/FaceDetectionPostProcess.kt` | 解码 + 加权 NMS + `largest()` | P1p-2a ✅ |
| `core/ml/Letterbox.kt` | letterbox 几何 + 投回源图 | P1p-2a ✅ |
| `core/ml/FaceDetection.kt` | `NormFace` / `FaceDetection`（含 `centerX/Y`、`eyeCenter`、`eyeRoll`） | P1p-2a ✅ |
| `core/ml/FaceDetector.kt` + `LiteRtFaceDetector.kt` | 接口 + LiteRT 实现（GPU→CPU 级联，永不抛；按张量元素个数认领 boxes/scores） | P1p-2b ✅ |
| `core/ml/MlFaceProvider.kt` | 懒加载 + letterbox 预处理 + 每图一次缓存 + 降级 + 日志 | P1p-2b ✅ |

**可测性**：`FaceAnchors` / `FaceDetectionPostProcess` / `Letterbox` 均为**纯 Kotlin 纯函数**，
单测覆盖「锚点数量与顺序」「`reverse_output_order` 的取值」「加权合并的加权平均与分数保持」「letterbox 正反互逆」。

### §15.4 待真机核实的两个假设

1. **letterbox 边框颜色**：按 `BORDER_ZERO` 补 **黑（归一化 −1）**。若真机发现框系统性偏移/漏检，先复核此项。
2. **letterbox 对齐**：按 MediaPipe `PadRoi` 的**居中**放置（四舍五入取整）。框中心有偏移时复核。

### §15.5 模型实际落地信息（已写入根 `NOTICE`）

| 项 | 值 |
|---|---|
| 随包路径 | `app/src/main/assets/models/face_detection_full_range_sparse.tflite` |
| 体积 | **676,746 字节** |
| SHA-256 | `2c3728e6da56f21e21a320433396fb06d40d9088f2247c05e5635a688d45dfe1` |
| 下载源 | `https://storage.googleapis.com/mediapipe-assets/face_detection_full_range_sparse.tflite` |
| 许可 | Apache-2.0（原样随包，未修改）；`.tflite` 已由 `noCompress` 排除压缩 |

### §15.6 P1p-2c 接线口径（人脸锚点 → 液化）

| 项 | 落地 |
|---|---|
| 锚点类型 | `RetouchLayer.FaceAnchor(faceX, faceY, eyeX, eyeY)`，单位是**渲染分辨率下的绝对像素** |
| 换算 | `FaceAnchor.fromDetection(face, srcW, srcH, w, h)` —— 经**归一化坐标**中转；越界 clamp，非法尺寸返回 `null` |
| 消费者 | `Beauty.apply(..., centroid, eyeCentroid)`：`slimFace` / `slimJaw` 锚在脸框中心，`eyeEnlarge` 锚在双眼连线中点 |
| 降级 | 模型不可用 / 图里没人脸 → **整个 `FaceAnchor` 传 `null`** ⇒ 退回 `Beauty.centroid`（**P1 口径逐位相同**，单测钉死） |
| 触发时机 | 仅当 `beautyActive()`（三个美型滑块任一 > 0）才跑检测；导出复用预览的缓存（同一 `mlKey`）⇒ 零成本 |
| 取哪张脸 | `MlFaceProvider.facesFor` 已按面积降序 ⇒ `firstOrNull()` = **最大脸** |
| UI 回显 | `EditorScreen.liquifyNote`：「液化锚点：人脸检测（GPU）· N 张脸」/「液化锚点：蒙版质心（未检测到人脸）」/「液化锚点：蒙版质心（人脸检测不可用）」 |
| 涉及文件 | `core/edit/retouch/RetouchLayer.kt`、`Beauty.kt`、`core/edit/EditEngine.kt`、`MainActivity.kt`、`ui/editor/EditorScreen.kt` |

**两条容易踩的坑（已修，均有单测钉死）**：

1. **眼心必须参与液化条带的源行上下界**。`eyeEnlarge` 把源点拉向眼心，而眼心通常在脸框中心
   **上方** ⇒ 源行可以取到 `eyeY < cy`。旧式只取 `min(蒙版顶, cy)` 会裁掉那几行，大眼的双线性取样
   落到条带外被 clamp ⇒ **画质悄悄变差且不报错**。
   `RetouchLayerTest.faceAnchorWithEyeAboveMaskTopMatchesFullFrame` 用「眼心高出蒙版顶」的输入钉死。
   （`faceAnchor == null` 时 `eyeY == cy`，两式退化为原式 ⇒ 逐位不变。）
2. **锚点必须按归一化坐标换算**。ARW 预览的「源图」是内嵌 JPEG 预览（3504×2336），而 retouch 跑在
   16-bit 线性代理（2048×1366）/ 导出全分辨率上。直接拿检测像素当渲染像素用，锚点会随分辨率整体偏移
   —— 预览看着还行、导出就跑偏。
   `FaceAnchorTest.sameFaceLandsAtSameNormalizedSpotAcrossResolutions` 钉死此不变式。

**其它**：换图 / 退出编辑器时 `MlFaceProvider.invalidate()` 与 `MlMaskProvider.invalidate()` 一并清缓存。

---

# 第三部分 · P2 A7C2 USB 直连

> 状态：**PoC-1/2/3/4/5 已全部落地**（USB 枚举 → 授权 → PTP 会话 → 存储/对象枚举 → 流式拉图 → 批量套预设导出）。
> P1（含 P1b 人像精修）已完成。P2 目标：Sony **A7C2（ILCE-7CM2）** 通过 USB 直连手机 →
> **拉图 → 套预设 → 看/导出**，并评估「边拍边看」可行性。
>
> 依据（写此稿时已读真实代码）：
> - 导入后处理链已成熟且**与来源解耦**：`Decoder`（ARW 走 LibRaw）/ `EditEngine` /
>   `RetouchLayer` / `Presets.ALL` 全部可原样复用于「相机传来的 ARW」，无需改动。
> - 约束：minSdk 36、仅 arm64-v8a、CI-only 构建（本地零环境）、真机一加15 自测。
>
> **核心约束**：P2 是**强真机依赖**阶段 —— USB 枚举/PTP 会话/传输都必须插上 A7C2 才能验证，
> 纯编译期（CI）无法覆盖。因此按 PoC 分步推进，每步真机验收后再进下一步。
> 能进 CI 的部分（协议容器编解码、数据集解析、报告格式化）**全部下沉为纯 Kotlin + JVM 单测**，
> 使真机上只剩「USB 端点与相机实际行为」这一小块不确定性。

## §1 范围与目标

| 项 | P2 做 | P2 不做 |
|---|---|---|
| 连接 | USB（宿主模式）直连 A7C2 | 云端/NAS（P3） |
| 拉图 | 枚举相机照片、下载 **ARW/JPEG** 到 App 缓存 | 删除/改写相机内文件 |
| 处理 | 拉到的 ARW 走**现有 P1 管线**套预设 | 新的解码/调色算法 |
| 遥控 | **评估**「边拍边看」（可能受限于官方 SDK） | 承诺 liveview 一定可用 |
| 批处理 | 单张 + 批量队列（进度 / 文件边界取消） | 后台服务/常驻 |

**验收目标（P2 完成口径）**：USB 连上 A7C2 → App 列出相机照片 → 选一张 ARW 拉到本地 →
自动套用一个预设 → 在编辑器看到「相机直出」成片 → 可导出到相册。

## §2 A7C2 的 USB 能力（事实与待验证）

Sony ILCE-7CM2 的机身「USB 连接」菜单一般提供：

| 模式 | 协议 | 对 App 的意义 |
|---|---|---|
| **Mass Storage** | USB MSC | 相机当 U 盘，但 Android 需挂载其卷（常见做法是走 MTP 而非 MSC） |
| **MTP** | PTP + MTP 扩展 | **可枚举/拉图**（文件名/大小/格式 + 下载），是拉图主路径 |
| **PC Remote** | PTP（Sony 扩展） | 遥控/连拍/取景；需官方 SDK 才能安全驱动 |

> ⚠️ 待真机确认：A7C2 各模式下的 **VID/PID 与接口类**。检测面板会打印
> `0x054C:xxxx` 与接口类（6=StillImage / 8=MSC / 0xFF=Vendor），并据此判定当前模式。

## §3 技术方案对比（拉图）

| 方案 | 说明 | 优点 | 代价/风险 | 结论 |
|---|---|---|---|---|
| **A. 纯 Kotlin 最小 PTP/MTP 客户端** | 直接用 `UsbDeviceConnection` 的 bulk 端点跑 PTP 容器（Open/GetDeviceInfo/GetStorageIDs/GetObjectHandles/GetObjectInfo/GetObject） | 无第三方、无 NDK、无许可；与现有纯 Kotlin 风格一致；**协议层可 JVM 单测** | 协议实现量中等（~900 行）；USB 端点行为必须真机调试 | **采用（已落地 PoC-1/2/3/4/5）** |
| B. libmtp（NDK 静态链接） | 复用成熟 MTP 库 | 功能全（含事件） | 引入 native 依赖、构建复杂、许可核对；CI 构建变重 | 备选（若 A 的协议坑太多） |
| C. Sony Camera Remote SDK | 官方 CRSDK（prebuilt `.so` + EULA） | 官方支持遥控/liveview | 需申请与许可、闭源 prebuilt、与 arm64/NDK 版本匹配；分发受限 | 仅当「边拍边看」必须时评估 |
| D. Wi-Fi ScalarWebAPI | 相机 Wi-Fi 上的 HTTP API | 无 USB 依赖 | A7C2 支持面有限、需切换相机 Wi-Fi、Android 本地网络权限（API 37 起为运行时权限） | 后置/备选 |

## §4 与 P1 的复用（关键）

拉图得到的 ARW 直接复用 P1 全链路，**零算法改动**：

```
相机 (USB) ──PTP/MTP GetObject──▶ App cache 里的 x.ARW
        │
        ├─ 打开/预览：ArwPreviewDecoder（内嵌 JPEG，秒开）        ← 复用
        └─ 修图/导出：Decoder/ArwFullDecoder → EditEngine
                        → RetouchLayer → Presets.ALL            ← 复用
```

即：P2 只新增「**取文件**」这一步；只要把文件落到 `cacheDir` 得到路径，
就等价于「用户从相册导入了一个 ARW」。

## §5 分阶段 PoC 计划

| 阶段 | 内容 | 依赖 | 状态 |
|---|---|---|---|
| **PoC-1** | USB 主机检测：枚举设备、识别 Sony VID/PID 与接口类、打印模式；UI 面板 + 日志 | 仅 USB 枚举（免权限） | ✅ |
| **PoC-2** | 授权 + 打开设备 + 声明 PTP 接口 + `OpenSession` + `GetDeviceInfo` | 真机（授权弹窗） | ✅ |
| **PoC-3** | 枚举存储与对象：`GetStorageIDs` → `GetStorageInfo` → `GetObjectHandles` → `GetObjectInfo` | PoC-2 | ✅ |
| **PoC-4** | `GetObject` 流式下载单张 ARW 到 cache → 复用 P1 管线套预设 → 「相机直出」第一张 | PoC-3 | ✅ |
| **PoC-5** | 批量拉图 + 预设批处理队列（可取消、进度） | PoC-4 | ✅ |
| PoC-6（可选） | 边拍边看 / 遥控（评估 CRSDK 或 ScalarWebAPI） | 决策 | ⬜ 后置 |

## §6 PTP 实现要点

### §6.1 容器格式（为什么字节序/长度必须严格）

PTP over USB 一律 **little-endian**，容器 = 12 字节头 + 负载：

```
length(u32) | type(u16) | code(u16) | transactionId(u32) | payload[]
```

一次事务 **Command →（可选 Data）→ Response**。两个最容易踩的坑：

1. **数据阶段是否存在，必须由操作码决定**（`OpenSession`/`CloseSession` 没有 Data）。
   猜错会把 Response 当成 Data 读，然后卡在等待一个永远不来的负载上。
2. **设备可能不发 Data 只回 Response**（例如不支持该操作时直接 `OperationNotSupported`）。
   读到一个 `type=Response` 的头时，必须立刻当作响应处理，而不是继续读负载。

### §6.2 文件清单

| 文件 | 层次 | 作用 |
|---|---|---|
| `camera/PtpProtocol.kt` | 纯 Kotlin | 容器编解码、操作码/响应码/格式码常量、小端读写、可读名 |
| `camera/PtpData.kt` | 纯 Kotlin | `PtpReader`（越界不抛异常）+ DeviceInfo/StorageInfo/ObjectInfo 等解析 |
| `camera/PtpTransport.kt` | Android | 选 PTP 接口与 Bulk 端点、`claimInterface`、三阶段事务、`GetObject` 流式下载、读满/空包重试 |
| `camera/UsbPermission.kt` | Android | 授权：`FLAG_MUTABLE` PendingIntent + 广播/系统 action 双注册 + 300ms 轮询兜底，25s 超时 |
| `camera/CameraSession.kt` | Android | **长生命周期会话**：授权→打开→OpenSession→设备/存储信息；`listPhotos` / `download` / `closeGracefully` |
| `camera/CameraPhoto.kt` | 纯 Kotlin | `CameraPhoto` / `CameraPhotoList`（含截断诊断）/ 照片过滤器 |
| `camera/CameraBatch.kt` | Android | 批量流水线：逐张 `download` → 套预设渲染 → 导出 → **导完即删**；取消只在文件边界 |
| `camera/CameraConnection.kt` | Android | 体检：对**已有会话**枚举各存储对象数 + 采样末尾对象 → `CameraPtpReport`；只读 |
| `camera/CameraPtpReport.kt` | 纯 Kotlin | 报告数据类 + 摘要行（用户直接回传的那份文本） |
| `core/edit/RetouchScale.kt` | 纯 Kotlin | 归一化 retouch 参数 → 渲染态（编辑器与批处理**共用口径**） |
| `ui/home/CameraPanel.kt` | UI | 检测 → 选择设备 → 连接 → 列图 → 单张导入 / 批量套预设 → 断开 |

### §6.3 授权：为什么是「广播 + 轮询」双保险

`UsbManager.requestPermission` 只能靠 PendingIntent 收回结果，而：

- PendingIntent **必须 `FLAG_MUTABLE`** —— 系统要往里填 `EXTRA_DEVICE` / `EXTRA_PERMISSION_GRANTED`；
- 授权广播的 action 在不同 ROM 上不完全一致（自定义 action 与系统 action 都注册一遍）；
- 极端情况下广播可能收不到。

因此同时启动一个 300ms 间隔的 `hasPermission()` 轮询，**谁先到算谁**，25s 超时。
少了这条兜底，一次「广播没送到」就会让整轮真机验证白跑。

### §6.4 流式下载与批量

**长生命周期会话**。一次握手后 `CameraSession` 被 UI 持有，列图 / 拉单张 / 跑批量复用同一会话，
直到点「断开连接」或面板被销毁（`DisposableEffect` 兜底 `close()`）。
每次操作重开会话意味着「授权 + 打开设备 + `claimInterface` + `OpenSession`」全部重来，
既慢又容易在相机端留下半开状态。所有 USB I/O 串行化在会话内部 `Mutex` 上
（`PtpTransport.transactionId` 是可变状态，并发发命令会错位；UI 即使误并发也只会排队）。

**流式下载，不整段进堆**。`PtpTransport.downloadObject(handle, sink, onProgress)`
按 256KB 分块写 `OutputStream`，RAW 35–57MB 全程不加载进堆。长度未知（`0xFFFFFFFF`）或为 0 直接放弃。

**取消只在文件边界**。中途停止 `GetObject` 的数据阶段会让数据流与响应错位、会话必须废弃，
因此**不做「半张拉取就中断」**：批量的取消标志在**每张开始前**判断一次，当前张一定跑完。

**批量导出与清理**。每张「拉取 → 套预设 → 导出到相册」后**立即删除临时文件**，
无论拉多少张，峰值磁盘占用只有一张的量级；另有 `pruneOldFiles` 兜底清理 >1h 的残留
（单张拉进编辑器的那一份会留到编辑结束，别误删近期文件）。

**目标文件名必须带后缀**。下游 `Decoder` 判定 RAW **只看后缀**（`.arw`）；
相机偶尔不给文件名（只有句柄命中），此时按相机自报类型补 `.ARW` / `.JPG`，
否则 ARW 会被当成 JPEG 走 `BitmapFactory` 而解码失败。

### §6.5 真机验证步骤

USB 连接 A7C2 → 机身「USB 连接」设 **MTP**（或 PC Remote）→ 首页「检测 USB 设备」→
选中 `★ Sony ...` → 「连接并握手」→ 首次弹授权框点「允许」→ 面板列出卡内照片（新 → 旧）。

- **单张**：某行点「导入」→ 拉取完成后自动打开编辑器（ARW 走 16-bit 线性管线）。
- **批量**：勾选若干张 → 选一个预设 chip → 「批量导入并套预设」→ 观察进度条；
  可点「取消（当前文件处理完即停）」。
- **断开**：点「断开连接」结束会话（或离开面板自动释放）。

若任一步失败，导出调试日志，`CAMERA` 日志里会有每条事务、每个文件、失败点。

## §7 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| **接口被系统 MTP 服务占用**（`claimInterface` 失败）：Android 检测到 MTP 设备后可能由 MediaProvider 先打开 | **高** | `claimInterface` 结果单独打日志；失败时报告直接给结论。备选：机身切 PC Remote（厂商接口类 0xFF，系统通常不接管） |
| A7C2 某些模式不暴露可用的 MTP 接口 | 中 | 先探明各模式接口类；必要时引导用户切到 MTP 模式 |
| `GetObjectHandles(0xFFFFFFFF)` 不被支持 | 中 | 已实现回退到 `associationHandle=0`（仅根层），并把「用了哪条路」写进报告 |
| 自研 PTP 协议细节多、真机调试耗时 | 中 | 协议层/数据集解析下沉为 JVM 单测；真机只剩 USB 行为 |
| 传输大文件（ARW 35–57MB）耗时/OOM | 中 | 已落地：`GetObject` 256KB 分块流式写文件，不整段进堆；进度回调 + 文件边界取消 |
| 批量处理占满磁盘 / 中断留垃圾 | 低 | 已落地：导完即删（峰值 ≈ 一张）+ `pruneOldFiles` 清理 >1h 残留 |
| 相机不给文件名 → 后缀缺失被误判为 JPEG | 中 | 已落地：按相机自报类型补 `.ARW`/`.JPG`（`CameraBatch.targetName`） |
| 相机端「PC Remote」占用导致 MTP 不可用 | 中 | UI 明确提示模式切换 |
| 边拍边看依赖官方 SDK | 高 | 不承诺；先交付「拍完拉图」，liveview 单列评估 |
| targetSdk 37 后 `ACCESS_LOCAL_NETWORK` 运行时权限（Wi-Fi 方案） | 中 | 优先 USB；Wi-Fi 方案后置 |

## §8 决策记录

- **D1 拉图方案**：**A 纯 Kotlin 最小 PTP/MTP**（已落地）。
- **D2 相机模式**：默认引导 **MTP**；若 `claimInterface` 被系统占用，再试 **PC Remote**。
- **D3 批处理范围**：单张（PoC-4）与批量队列（PoC-5）均已落地；批量取消只在文件边界生效。
- **D4 边拍边看**：本阶段只评估不实现。
- **D5 UI 归属**：首页独立「相机直连」入口（已落地）。
- **D6 失败姿态**：任何一步失败都返回**带原因的报告**并写日志，不重试、不静默 ——
  真机每轮验证成本高，必须一次拿到足够信息。
- **D7 会话模型**：**长生命周期会话**（`CameraSession`）由 UI 持有，列图/拉图/批量复用；
  I/O 串行化在会话内 `Mutex`，面板销毁兜底释放 USB。
- **D8 下载落盘**：只落 `cacheDir/camera/`，导完即删；单张拉进编辑器的那份留到编辑结束（或 >1h 清理）。

---

# 第四部分 · 审查修复台账

> 本文合入的是三轮审查的**全部条目与处置结论**。编号是**仓库级 ID**，
> 代码 KDoc 直接引用 `F01`~`F24` / `R01`~`R10` / `D01`~`D10`。
> 除非本条明确标注「后置 / 未做」，**均已在代码中修复并经 CI 验证**。

## F 系列 —— 中期审查（基线 `c8ea422`）

### 复审纠正（上一轮结论有误，先记下）

**曾判断「DEV_PLAN §3.2 说 ARW 内嵌预览是 7008×4672 属事实错误」—— 这个判断是错的，计划是对的，错的是代码。**

用 Python 解析本地样本 `example/DSC04926.ARW`（68MB）得到 IFD 链实况：

| 位置 | 0x0201/0x0202 指向 | 尺寸 | 体积 |
|---|---|---|---|
| IFD0 | 192674 / 196313 | **1616×1080** | 192 KB |
| IFD0.next = IFD@43670 | 43988 / 7694 | 160×120 | 8 KB |
| IFD@43670.next = **IFD@138222** | 389120 / 2008341 | **7008×4672** | **1.9 MB** |
| SubIFD@138530 | — | 7040×4688 RAW (compression=1) | 传感器数据 |

结论：**A7C II 的 ARW 里确实有全分辨率内嵌 JPEG，只有 1.9MB**，但它在 IFD 链的第 3 个 IFD 上。
`ArwContainer.previewJpegRange()` 只查 IFD0 与 SubIFD（`0x014A`），**从不沿 IFD 链的 next 指针走**，
且首个命中即返回 → 永远只拿到 1616×1080 那张。

这条纠正把 F04 从「文档笔误」升级为「实现缺陷」，也是当时性价比最高的一个修复。

### F01 —— P0 · LibRaw 输出被降级成 8-bit 烘焙图

- **原因**：DEV_PLAN §3.2 论证「ARW 修图必须走全量解码」的唯一理由是「内嵌预览白平衡与色调曲线已固化、没有编辑宽容度」。
  而当时实现把相机白平衡、sRGB 曲线、自动亮度**全部烘焙进 8-bit 输出**，宽容度同样为零 ——
  自己的实现推翻了自己的立论。`no_auto_bright=0` 还让曝光基准随图内容漂移，同一组参数在不同照片上不可复现。
- **位置**：`app/src/main/cpp/raw_bridge.cpp`（`use_camera_wb=1` / `output_color=1` / `output_bps=8` / `no_auto_bright=0` / `user_qual=3`）
- **方案**：`output_bps=16` + `no_auto_bright=1` + 线性 gamma（`gamm={1,1}`）+
  保留 `use_camera_wb` 作为**初始 WB 参数回传 Kotlin**（而非烘焙进像素）；
  Kotlin 侧管线按 16-bit 线性输入重写，白平衡/曝光在线性域可逆。
- **验证**：同一张 ARW，曝光 -2EV → +2EV 往返后高光不应被截断；日志打印 `output_bps` 与 WB 乘子。
- **状态**：✅ 已修

### F02 —— P0 · `dcraw_make_mem_image()` 返回值未释放

- **原因**：返回的 `libraw_processed_image_t*` 必须用 `LibRaw::dcraw_clear_mem()` 释放。
  原代码用的是 `free_image()`，它只释放 LibRaw 内部的 `imgdata.image`，**不释放返回的那块 malloc**。
  7008×4672×3 ≈ 98MB / 次，反复导出必然 native OOM。
- **位置**：`raw_bridge.cpp` 五处 `free_image()` 调用全部需改判。
- **方案**：所有退出路径改为 `LibRaw::dcraw_clear_mem(image)`；内部缓冲交由 `rawProcessor` 析构处理。
- **验证**：连续导出同一张 ARW 10 次，`Debug.getNativeHeapAllocatedSize()` 不应单调增长。
- **状态**：✅ 已修（并同步更正了计划与看板里「`free_image()` 替代 `dcraw_free()`」的错误结论 → D01/D09）

### F03 —— P0 · 编辑预览与导出走两条不同底图，WYSIWYG 断裂

- **原因**：`decodeToProxy` 对 ARW 恒走内嵌预览（相机烘焙 JPEG），`decodeFullRes` 走 LibRaw（AHD + 自动亮度）。
  同一套 `EditParams` 作用在两张色彩基准不同的底图上不等价 → 用户在 A 上调、导出得到 B。
- **方案**：代理图与导出同源，都走 LibRaw；代理档用 `half_size=1` 或 `user_qual=0` 换速度。
  全分辨率内嵌 JPEG（F04）只用于「秒开占位」，RAW 解完后替换并重放参数（渐进式），且占位期间禁用滑块或明确标注。
- **验证**：ARW 导出图与预览截图做直方图比对，均值差 < 1%。
- **状态**：✅ ① 已修（代理走 `openLinear(halfSize=true)`）；② 渐进占位后置（见第一部分 §7）

### F04 —— P0 · ARW 内嵌预览取错 IFD

- **原因**：见上方「复审纠正」。`previewJpegRange()` 不走 IFD 链、且首个命中即返回，
  导致「ARW 内嵌**全分辨率** JPEG 预览解码」实际只解出 1616×1080。1.9MB 就能拿到 7008×4672。
- **方案**：遍历 IFD0 → next → next…（设置最大跳数防环）+ SubIFD，收集所有 `0x0201/0x0202` 候选，
  按 `0x0202` 长度（或解析 SOF0 宽高）择大者返回。
- **连带**：`ArwPreviewDecoder` 注释需改；单测需**新增链式用例**（IFD 链里存在更大预览时应择大）。
- **验证**：真机打开样本 ARW，日志 `arw preview decoded` 应输出 7008×4672。
- **状态**：✅ 已修（`previewChain_picksLargest` 通过）。⚠️ 回归过一次：主 IFD 链曾因
  SubIFD 与 next 指向同一偏移而提前退出 —— 现在按「择最大」而非「首个命中」。

### F 系列 —— P1 · 高风险

| ID | 问题与原因 | 方案 | 状态 |
|---|---|---|---|
| F05 | **全分辨率导出内存峰值 500MB+**。同时在世：LibRaw processed 98MB(native) + `jbyteArray` 131MB + `GetByteArrayElements` 可能再复制 131MB + Bitmap 131MB + `renderInto` 内 `IntArray` 131MB + `fullTarget` 131MB | native 侧改 `AndroidBitmap_lockPixels` 直写 Bitmap；`EditEngine` 改按行带处理、复用单份 `IntArray`。**最终形态**：16-bit 线性母版（~196MB）始终留 native，Kotlin 只拉 `BAND_ROWS=32` 行（≈1.3MB）上色后写入目标 Bitmap ⇒ Kotlin 峰值 ≈ 单张 8-bit 目标 Bitmap | ✅ 已修 |
| F06 | **逐像素装箱**。`processPixel` 返回 `Triple<Int,Int,Int>`，内部 4 个子函数各再返回 `Triple` ⇒ 每像素约 5 个对象 + Integer 装箱，33MP 导出 ≈1.6 亿次分配 | 白平衡×曝光×linear→sRGB 本质是逐通道单调映射，塌缩成 3 张查表；其余改成写入 `IntArray` 的无返回值函数（`PixelProgram`） | ✅ 已修 |
| F07 | **暗部会出色带**。`LINEAR_TO_SRGB_LUT` 建在**线性域**均匀 256 格上；`toIdx` 还先 `coerceIn(0,1)` 把曝光后的高光直接砍死 | 整段改为 16-bit 线性域运算（随 F01）；过渡期先把表加密到 4096 项并去掉提前 clamp | ✅ 已修 |
| F08 | **滑块无节流 + 每跳都进撤销栈**。`LaunchedEffect(params)` 每跳触发一次全代理图重渲，`renderInto` 是不可取消的阻塞循环，靠 Mutex 串行排队 → 拖动积压；一次拖动能往 `EditHistory` 塞几十条 | `snapshotFlow{params}.conflate()`；渲染内加协作取消（在分带边界 `return false`）；history 只在 `onValueChangeFinished` 时 push | ✅ 已修（⚠️ 协作取消的返回值必须被消费 → R10/第七轮） |
| F09 | **`.gitignore` 未覆盖实际使用的凭证文件名**。忽略的是 `.ci_token.txt` / `.ci_*.json`，而 CI 日志抓取流程实际写的是 `.ci_ghtoken` ⇒ **PAT 入库风险** | 改为 `.ci_*`；并建议轮换现有 PAT | ✅ 已修 |
| F10 | **LibRaw 静态链接的许可合规未评估**。LibRaw 为 LGPL-2.1 / CDDL-1.0 双许可，静态链接进闭源 APK 触发 LGPL 的「可重新链接」义务 | 选 CDDL 分支或改动态链接 `.so`；补 LICENSE/NOTICE；风险表补条目 | ✅ 已修（仓库根 `NOTICE` 已附许可与子模块 pin；**分发前需复核 relink 可行性并提供对应 native 构建脚本**） |

### F 系列 —— P2 · 中（CI 闸门 / 可复现性 / 交付回路）

| ID | 问题与原因 | 方案 | 状态 |
|---|---|---|---|
| F11 | **CI 不是质量闸门**。无 ktlint、无 detekt、`test` 任务**从未被调用**，`lint` job 还挂着 `continue-on-error: true` 永不拦门 | build job 加 `./gradlew testDebugUnitTest`；lint 去掉 `continue-on-error` 或改 `lintRelease` 拦门 | ✅ 已修（`testDebugUnitTest` + `lintDebug` 已拦门；ktlint/detekt 仍未引入，理由见 DEV_PLAN §7） |
| F12 | **NDK 版本不可复现**。CI 用 `sdkmanager --list \| sort -V \| tail -1` 动态取最新 NDK | `build.gradle.kts` 显式 `ndkVersion = "30.0.16248370"`，CI 装同一版本 | ✅ 已修 |
| F13 | **「Action 之后通知我」这条项目硬要求未实现** | 加 `$GITHUB_STEP_SUMMARY` 输出（commit、变体、APK 名、大小、测试结果）；Firebase 分发按条件化 | ✅ 已修 |
| F14 | **CI 三个 job 各自重复 20 行 SDK/NDK 安装块**，各下载一次 NDK（~1-2GB），维护面 ×3 | 抽成 composite action 或 reusable workflow | ✅ 已修 |
| F15 | **release job 不依赖 lint/test**，测试挂了照样能产出签名包 | `needs` 补 lint/test job | ✅ 已修 |

### F 系列 —— P3 · 低（清理与用户可见文案）

| ID | 问题 | 状态 |
|---|---|---|
| F16 | HomeScreen 路线图卡片还是旧 8 步版（fp16+EGL P3 / 16bit TIFF / MediaPipe），与 v3.0 冲突，且「NDK + LibRaw」仍标未完成 —— **真机上用户直接看得到的错误信息** | ✅ 已修 |
| F17 | ARW 相关文案全部过期：仍在说「P1b 开放修图」，而 `useLibRaw` 早已为 true | ✅ 已修 |
| F18 | `bitmapConfig="RGBA_F16"` 是死字段（全代码硬编码 `ARGB_8888`）；Manifest 请求 `colorMode="hdr"` 但管线是 8-bit sRGB | ✅ 已修（Manifest 已去掉 `colorMode`） |
| F19 | `READ_MEDIA_IMAGES` / `READ_MEDIA_VISUAL_USER_SELECTED` 在 Photo Picker + SAF 方案下不需要 | ✅ 已修 |
| F20 | minSdk=36 下 `Build.VERSION.SDK_INT >= Q` 判断全是死代码 | ✅ 已修 |
| F21 | `copyToTemp` 在 `openInputStream` 返回 null 时仍返回 0 字节文件（`?.use` 的返回值被丢弃）→ 绕一圈才回退；异常路径下半截临时文件不会删 | ✅ 已修 |
| F22 | 本地工作树两个 LibRaw 子模块处于 **deleted** 状态（`git status` 显示 ` D`）。CI 用 `submodules: recursive` 不受影响，但本地一旦 `git commit -a` 会把子模块从仓库删掉 | ✅ 已处理（`git submodule update --init --recursive`）—— 本地卫生项，遇到就补 |
| F23 | 子模块 pin 在 master 快照（LibRaw `dde798dd` / LibRaw-cmake `eb98e432`），已因此吃过 `dcraw_free` 被移除的 API 变更 | ✅ 已知并接受（pin 记录在 DEV_PLAN §5.1） |
| F24 | `ENABLE_OPENMP=OFF` + `user_qual=3`(AHD) 单线程解 33MP，导出预计 5-15s，UI 只有一行「正在生成导出…」，无进度无取消 | ✅ 已修（导出走分带渲染 + 进度反馈 + 可取消） |

### D 系列 —— 文档与看板对齐（不改代码，但必须同步）

| ID | 目标 | 内容 | 状态 |
|---|---|---|---|
| D01 | `DEV_PLAN §0` 备注 | 「`free_image()` 替代 `dcraw_free()`」结论错误 → 更正为 `dcraw_clear_mem()` | ✅ |
| D02 | `DEV_PLAN §2.1` | 「代理图 GPU 实时预览 RenderEffect/AGSL」标 P1a ✅ → 实为 CPU 多线程逐像素，全仓库无 shader 代码 → 改为未实现（后置） | ✅ |
| D03 | `DEV_PLAN §6.1` | target/compileSdk 仍写 37 → 与 `libs.versions.toml` 及 §0 对齐为 36 | ✅ |
| D04 | `DEV_PLAN §5.1/§5.2` | 路径清单大面积失效 → **按现状重写**，而不是反过来逼代码去凑 | ✅ |
| D05 | `DEV_PLAN §6.2` | 内存估算未含 LibRaw 中间缓冲与 JNI 拷贝 → 按 F05 重算 | ✅ |
| D06 | `DEV_PLAN §8` | 补 LibRaw LGPL 静态链接合规风险（F10） | ✅ |
| D07 | `DEV_PLAN §3.2` | 内嵌预览表格补「全分辨率 7008×4672 JPEG 位于 IFD 链第 3 个 IFD，1.9MB」实测事实 | ✅ |
| D08 | wb-issues 卡 | 标题「targetSdk 37 / compileSdk 37」→ 改 36 | ✅ |
| D09 | wb-issues 卡 | 描述里 `free_image` 结论错误 → 更正 | ✅ |
| D10 | wb-issues 新增卡 | 「P1b-3.5 渲染管线定型」承接 F01/F03/F05/F06/F07，排在 P1b-4 人像算子之前 | ✅ |

## R 系列 —— 第一轮收尾复审（「矩阵标 ✅ 但实际不可达」的断链）

> 触发：「把 P1 和 P2 一口气全部开发完」。本轮**不加新功能**，只审计「计划说做了、代码却到不了」的断链。
> 结论：P1/P2 主体完整（全仓无 `TODO` / 无 stub / 无未接线回调），但存在 4 处**声明与可达性不一致**，已全部修复。

| ID | 级别 | 问题 | 修复 |
|---|---|---|---|
| R01 | 高 | `DEV_PLAN §2.1`「曝光 / 曲线 / LUT」标 P1a ✅，但**编辑器没有任何曲线入口**：`EditParams.lumaPoints` 只被 `PixelProgram` 读，全仓无写入点 | 新增 `core/edit/ToneCurve.kt`（黑场/中间调/白场三点锚点 ↔ 控制点，含钳位与缺锚回退）+ `ToneCurveTest`；`EditorScreen` 增 `CurveRow`，走既有 `onParamChange/onParamCommit` 入撤销栈 |
| R02 | 中 | `DEV_PLAN §2.1`「导出到相册（JPEG / PNG）」标 ✅，但 `Exporter` 的 PNG 分支**没有任何调用点** ⇒ PNG 不可达 | `EditorScreen` 增导出格式 chips；`MainActivity` 持 `exportFormat`，并在进入 `Dispatchers.Default` **之前**于主线程取值 |
| R03 | 低（文档） | `DEV_PLAN §2.1` 把「边拍边预览（liveview）」在 P2 列标 ✅，与 P2 §8 D4「本期只评估不实现」直接矛盾 | 矩阵改为「后置」 |
| R04 | 低（死代码） | `CameraPanel` 改成长会话模型后，`CameraConnection` / `CameraPtpReport` 失去唯一主代码调用点 ⇒ 体检报告不可达 | `CameraConnection.connect()` → `inspect(session, steps)`：对**已有会话**体检，连接成功后自动产出报告 |

> 有意不动（非遗漏）：`NeutralGrayParams.threshold` 无 UI —— 它是算子内部调参（细节保护阈值），
> 不在计划列出的用户可见控制项内。

## R 系列 —— 第三方审计报告核对与修复

> 逐条回到源码核验。结论：报告**方法严谨、绝大多数条目属实**（3 严重 + 12 中 + 12 轻微，仅 2 处需修正）。

### 报告需更正的两处（核验增量，非代码问题）

| # | 报告原文 | 实际 | 处置 |
|---|---|---|---|
| C1 | 「预设 `creamy` 开箱即触发，用户点预设脸会变宽」 | 方向 bug 属实，但**需先有画笔蒙版**（`Beauty.apply` 需非空蒙版才生效；无描迹时返回 null）⇒ 实际需用户先画一笔 | 严重性下修为「需先有画笔蒙版」 |
| C2 | 「`Beauty`/`NeutralGray` 在 `mask==null` 时提前 return ⇒ 磨皮/液化在批量静默 no-op」 | 只有 `Beauty`（液化）会 return；`NeutralGray` 当时是 `if (mask == null) 1f` ⇒ **磨皮反而「全局无蒙版生效」**（连背景一起磨） | 事实更正 → 引出 R08 的语义统一 |

### 已修复（P0）

| ID | 级别 | 问题 | 修复 |
|---|---|---|---|
| R05 | **高** | `Beauty` 瘦脸/收颌的**后向映射方向写反** —— `dx -= k(x-cx)` 使输出点采到更靠质心的源点 ⇒ 实际是**放大**，与 KDoc 相反 | 改 `dx += …` / `dy += …`（输出点采更靠外的源点 → 外侧内容被拉进来 = 收拢）；并**补方向断言单测** `slimFaceMovesContentTowardCentroid` / `slimJawMovesContentTowardCentroidVertically`（旧 `shiftsPixelsWithMask` 只断言 `diff>0`，正是它放过了本 bug） |
| R06 | **高** | `CameraBatch` 单张处理**只有 `finally` 没有 `catch`** ⇒ `processOne` 内的 OOM 等异常穿透 `run()`；调用方 `scope.launch` 无兜底 ⇒ `busy` 永不复位、界面永久停在「批量处理中…」，已完成项全丢 | 单张 `try/catch(Throwable)/finally`：失败转 `ItemResult(ok=false, …)` 记入 `items`；`run()` 整体再加一层兜底，保证 `Summary` 一定返回；`CameraPanel` 批量协程加 `try/catch/finally` 确保 `busy/phase` 必复位。三处均**原样重抛 `CancellationException`**（不吞取消信号） |
| R07 | **高** | 下载路径把**空包（ZLP）当失败** —— `read <= 0` 即中断，与同文件 `readFully` 的「零长度包可重试」策略矛盾；大文件一次空读即整张失败，且报错文案误导为「超时或设备已断开」 | 拆开 `read < 0`（超时/断开）与 `read == 0`（ZLP，重试 `MAX_EMPTY_READS` 次）；成功续读后清零计数；两类错误文案分开 |

> 未做（受「无本地构建环境 / 最小改动」约束）：`CameraBatch.run()` 三语义（取消/导完即删/失败续跑）与
> `PtpTransport` 的**单测**需要先抽 `PtpTransport` 接口注入 fake，属结构性重构，当时未夹带。

## R 系列 —— 第二轮复审：N1 语义统一 + N2 文档过期

| ID | 级别 | 问题 | 修复 |
|---|---|---|---|
| R08 | **中** | N1：`mask == null` 在两个皮肤类算子间**语义相反**且设计稿未定义 ⇒ 同一预设经两条入口得到两种结果。批量链路套「奶油肌」时**磨皮对整张照片（含背景）全强度生效、瘦脸静默失效** | **统一为「null = 未圈定作用域 → 不执行」**：`NeutralGray` 改 `val skin = mask ?: return`；`RetouchMask` KDoc 与 P1b §4 写明约定；`NeutralGrayTest` 两用例改传全幅蒙版并**新增 `noOpWhenMaskNull`**；`CameraPanel` 批量区明示「磨皮/瘦脸不生效」 |
| R09 | 低（文档） | N2：前文结论「本轮改动未提交（不代 push）」已过期 | 更正为「已提交 `280dbfb`，CI run `34612770955` 全绿」 |
| R10 | **高** | retouch 全幅缓冲：全分辨率导出在 retouch pass 内同时持有 目标 Bitmap(131MB) + `RetouchLayer.px`(131MB) + `NeutralGray` 的 tmp/out(各 131MB) + `Beauty.out`(131MB) → 峰值 ≈524MB（JVM 堆），若计入仍打开的 196MB native 线性母版 ≈720MB。**撤销了 F05 的分带内存纪律** | **已实施**：① `NeutralGray` 改**分带**（`BAND_ROWS=256` + 保留上一带 `radius` 行原始 halo），3×131MB → **≈30MB**；② `Beauty` **不能分带**（后向映射跨带），改走**蒙版包围盒**，典型画笔蒙版下 131MB → 数 MB；③ **`RetouchLayer.px` 流式化**：不再持全幅 `px`，改在 `PixelStore` 抽象（生产 `BitmapStore` / 测试 `MemStore`）上分段读写 —— 磨皮分带、液化**源行条带**、祛瑕**小图块**、追色**两遍只读统计 + 一遍写**（保持原求和顺序 ⇒ 位等价）。**该 pass 现只剩目标 Bitmap(131MB) 的固有工作集** |

> **R10 的归属更正**：第二轮报告把它列在「上轮未动项」，但前两轮报告**都未列过这条** ——
> 它是 P1b-4 retouch 层引入的**新增**严重项，不是旧账。
>
> **为什么必须留存原始 halo**：核心行是原位写回的，上一带写过之后顶部 halo 已非原始值，不能再当源用。
>
> **等价性由「朴素整幅参照实现」钉死**（不复用被测代码）：`NeutralGrayTest.bandedMatchesFullFrame`、
> `BeautyTest.bboxBufferMatchesFullFrame`、`RetouchLayerTest.*`（`MemStore` vs 朴素整幅参照，6 用例）。
> ⚠️ 这几条是**关键回归防线** —— 后续再动这些算子/编排层必须让它们保持绿。
>
> **抽 `PixelStore` 的附带收益**：JVM 单测里 `Bitmap` 是不可用桩，抽接口后编排层才能跑等价性测试。

---

*本文为合并存档；原 5 份稿已在合并后从工作树移除，需要原文用 `git show <合并前的提交>:docs/<原名>` 取回。新增阶段设计请另开新文档，不要往本文追加。*
