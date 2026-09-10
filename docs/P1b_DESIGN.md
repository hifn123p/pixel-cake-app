# P1b 设计稿（草稿 v0.1 — 待评审）

> 状态：**已落地（P1b 完成 · 2026-09-11）**。本文为 `DEV_PLAN.md` §0「P1b」的细化设计，
> 覆盖 P1b-4 人像算子、P1b-5~10 预设、F03 ② 两段式渐进占位、P1b-6 真机测试口径。
> 实施情况：Phase 1-6 已全部落地并经 CI（`testDebugUnitTest` + `lintDebug`）验证；
> Phase 7（F03② 后台母版无缝切换）为可选画质优化、暂缓（感知延迟已由「打开即显进度 +
> 预览走 halfSize 代理」缓解）；P1b-6 真机验收由用户在 A7C2 实拍侧执行。
> §11「待拍板决策」已按推荐默认执行（Q1① / Q2 画笔作用域 / Q3 box 近似 / Q4 参数栈预设 / Q5 并入）。
>
> 依据（已读真实代码）：
> - `EditParams` 为**扁平结构体**（曝光/对比/饱和/温色调/阴影高光/曲线/LUT），非有序 op 列表；
> - `PixelProgram.finish()` 为**点态单 pass**（WB→曝光→sRGB→阴影高光→对比→饱和→曲线→LUT），逐像素零分配；
> - `EditEngine` 分带渲染（3 入口：`renderIntoSrgb`/`renderIntoLinear`/`renderLinearFile`），16-bit 线性母版留在 native，JVM 仅持目标 8-bit Bitmap（全分辨率 ~131MB，见 F05）。
>
> 核心约束（决定架构）：**4 个人像算子均为空间 / 蒙版 / 整图统计型，无法塞进现有点态 `finish()`。**

---

## 0. 范围与交付

| 项 | 本期做 | 本期不做 |
|---|---|---|
| 人像算子 | 中性灰磨皮 / 美型液化 / 祛瑕 / 追色（**CPU 实现**） | GPU/AGSL 实时化（§2.1 规划中，后置） |
| 蒙版 | **画笔蒙版**（手绘 skin 作用域） | ML 自动蒙版（SCRFD/2DFAN4/BiSeNet，P1+，TFLite） |
| 预设 | 参数栈预设 + **可选** `.cube` LUT 打包 | DNG/XMP/不明许可源一律不用 |
| 渐进占位 | F03 ② 代理秒进 + 后台母版无缝切换 | — |
| 真机测试 | P1b-6 统一测试口径 + DebugLog 关注点 | 测试动作在你真机侧执行 |

---

## 1. 现状管线回顾（已读代码事实）

```
ARW 母版 (native, 16-bit 线性, ~196MB，F05 留在 native)
   │  RawLinearSource.readRows(BAND_ROWS)  —— 每次只拉 32 行 ≈1.3MB
   ▼
[分带] PixelProgram.finish()  点态：WB×曝光增益 → sRGB 编码 → 阴影/高光 → 对比 → 饱和 → 亮度曲线 → 内置 LUT
   │  结果写入目标 Bitmap（8-bit ARGB_8888）
   ▼
目标 Bitmap（JVM，全分辨率 ~131MB / 代理 ~16MB）
```

- 预览（滑块实时）：`renderIntoLinear(代理或母版 LinearImage, 代理尺寸 target)`。
- 导出：`renderLinearFile` 全分辨率边解边渲，不搬整幅母版进 JVM。

---

## 2. 算子分层架构（本稿核心）

把管线拆成两层，**点态 tonal 层复用现有 `PixelProgram`，retouch 层作为「渲染后整图 pass」叠加在已物化的目标 Bitmap 上**：

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

**为什么不在分带内做空间算子**：磨皮要邻域、液化要重映射、追色要整图统计；在目标 Bitmap 已物化后做，复用已有的 131MB（代理仅 ~16MB），不再额外搬母版，符合 F05 内存纪律。retouch 层对预览（代理）与导出（全分辨率）用**同一算法 + 同一 Mask（按比例重采样）**，仅分辨率不同 → 保证「预览所见即导出所得」。

---

## 3. 参数模型：保持扁平，新增 RetouchState（推荐方案①）

- **方案①（推荐）**：`EditParams` 保持扁平（已验证的 `PixelProgram`/`EditHistory` 不动）；retouch 作为独立 `RetouchState` 附加层。
  ```kotlin
  data class RetouchState(
      val neutralGray: NeutralGrayParams = NeutralGrayParams(),  // strength, radius
      val beauty: BeautyParams = BeautyParams(),                 // slimFace, slimJaw, eyeEnlarge + 作用域
      val inpaint: List<InpaintStroke> = emptyList(),            // x,y,r,seed
      val colorTransfer: ColorTransferParams = ColorTransferParams() // refId, intensity
  )
  ```
  - 撤销/重做：`EditHistory` 管 tonal；`RetouchState` 单独持有历史段（或合并进同一快照）。
- **方案②（不推荐）**：改有序 op 列表（贴合 DEV_PLAN §3.3 JSON）。侵入大，影响 `EditHistory`/`PixelProgram` 已验证逻辑，收益有限。
- **结论**：采用①。导出/预览共用 `EditParams + RetouchState + Mask` 三元组，序列化格式对齐 §3.3。

---

## 4. Mask 抽象（P1 画笔蒙版，P1+ ML 复用同一接口）

```kotlin
interface RetouchMask {
    /** 返回 [0..1] 作用强度；px,py 为目标图坐标。 */
    fun sample(px: Int, py: Int): Float
    /** 按目标分辨率重采样（预览→导出 比例变换）。 */
    fun resampleTo(w: Int, h: Int): RetouchMask
}
```
- **P1 来源**：编辑器画笔（soft brush，半径 + 羽化）→ 累加到 `RasterMask`（与图同尺寸 8-bit/浮点栅格，存内存）。
- **消费**：retouch 算子 `out = lerp(orig, op(orig), strength * mask(px,py))`。
- **P1+ 扩展点**：`MlSkinMask` 实现同一接口（FaceDetector → skin 概率图），UI 无需改。

---

## 5. 各算子设计

### 5.1 中性灰磨皮 `NeutralGray`
- **算法**：表面模糊（surface blur）压低中频皮肤纹理、保边缘；强度由 skin mask 调制。
- **参考**：Rust 引擎 `crates/engine/src/retouch/neutral_gray.rs`（跨仓库 `pixel-cake`，实施前精读）。
- **实现（CPU）**：分离式多趟 box blur 近似表面模糊（阈值内才混合，超阈值保边缘）；皮肤区 `lerp(orig, blurred, strength*mask)`。
- **性能**：代理 2048 下多趟极快；全分辨率单次导出异步 + 进度，可接受。

### 5.2 美型液化 `Beauty`
- **算法**：瘦脸/瘦下颌/大眼几何形变（liquify remap），作用域由 mask 限定。
- **参考**：`crates/engine/src/retouch/beauty.rs`。
- **P1 简化（推荐）**：**不依赖人脸检测**，提供滑块（瘦脸/瘦下颌/大眼）+ 画笔作用域；remap 用双线性重采样源像素。人脸关键点自动定位留 P1+。
- **实现**：对目标 Bitmap 逐像素按形变场反查源坐标，mask 内强度插值。

### 5.3 祛瑕 `Inpaint`
- **算法**：用户画笔描 stroke（x,y,r）→ 对每 stroke 区域用外环邻域（均值/中值/Telea 近似）填充。
- **参考**：`crates/engine/src/retouch/inpaint.rs`。
- **实现**：stroke 列表 → 逐 stroke 用其外环邻域稳健填充；mask 由 stroke 生成。简单、可撤销、无外部依赖。

### 5.4 追色 `ColorTransfer`
- **算法**：将当前图颜色统计（分通道 mean/std）向参考帧匹配；全局、无 mask。
- **参考**：`crates/engine/src/retouch/color_transfer.rs`。
- **参考帧来源**：预设内置 ref（如 `portrait_film`）或用户从相册选图。
- **实现**：统计目标 Bitmap 分通道 mean/std → 按参考线性匹配 → 可加强度。整图单遍。

---

## 6. P1b-5~10 预设

- **形态 A 参数栈预设**（推荐先做）：Portra 800 / Fuji / 复古褪色 / 莫兰迪 / 日系… —— 曲线 + 橙色明度微调（保肤色）+ `colorTransfer` ref。零授权风险，自研。
- **形态 B `.cube` 3D LUT**（可选，下期）：仅取 **MIT/CC 可再分发**源（`shravankumar147/photo-edit-app` 的 `cinematic.cube`/`fuji_fp-100c_alt.cube`；`mv-lab/NILUT` CC4.0）。构建期转 3D LUT（33³/64³ half-float）打包 asset，运行时采样 + 三线性插值。
- **许可红线**：逐个核 LICENSE/NOTICE；**社区流传的 Lightroom DNG/XMP 预设不是 `.cube`，一律不用**。
- **存储**：预设 JSON 与编辑栈同格式（§3.3）；`data/preset/` 加载/存储（Room 规划中，本期可用 assets + 文件）。

---

## 7. F03 ② 两段式渐进占位

- **现状**：`ArwFullDecoder.open(halfSize=true)` 已能快速解（`half_size=1 + user_qual=0`，解码量 ~1/4）；需确认编辑器启动即走代理。
- **设计**：
  1. 打开 ARW → `openLinear(halfSize=true)` 得**代理线性** → 立即 `renderIntoLinear` 出可交互预览（秒开）。
  2. 同时 `LaunchedEffect` 后台 `openLinear(halfSize=false)` 开**全质量母版会话**；就绪后无缝切换（替换 base `LinearImage`，重渲一次）。
  3. 切换对用户无感（仅一次重渲）；母版未就绪时滑块拖动仍用代理（画质略低但跟手）。
- **同步**：`AtomicBoolean`/State 标记「母版就绪」，避免重复 open；协作取消沿用 F08 机制。

---

## 8. P1b-6 统一真机测试口径（你真机侧执行，我出关注点）

- **真机**：一加15（骁龙 8 Elite / 16GB）。
- **重点验证**：
  - 协作取消（F08）在 retouch 多 pass 下仍跟手、无积压。
  - 33MP 导出峰值内存（F05）：retouch 整图 pass **不额外翻倍数**（复用 target，禁并行多份）。
  - 画笔蒙版交互；代理→母版无缝切换（F03 ②）。
- **DebugLog 关注**：`DECODE`（走 LibRaw / halfSize）、`EDIT`、`RETOUCH`（新增 tag）、`ERROR`。
- **验收**：导入 A7C II ARW → 调曝光/曲线/预设/磨皮/液化/祛瑕/追色 → 导出相册，无 OOM/卡死。

---

## 9. 模块接口草图（新增 / 变更）

| 文件 | 变更 | 说明 |
|---|---|---|
| `core/edit/EditParams.kt` | 保持 | tonal 参数不动 |
| `core/edit/RetouchState.kt` | **新增** | 4 算子参数 + 序列化 |
| `core/edit/Mask.kt` | **新增** | `RetouchMask` 接口 + `RasterMask` |
| `core/edit/retouch/NeutralGray.kt` | **新增** | 表面模糊（box 近似） |
| `core/edit/retouch/Beauty.kt` | **新增** | remap 液化 |
| `core/edit/retouch/Inpaint.kt` | **新增** | stroke 补洞 |
| `core/edit/retouch/ColorTransfer.kt` | **新增** | 全局统计匹配 |
| `core/edit/EditEngine.kt` | 改 | `renderIntoLinear`/`renderLinearFile` 在 tonal 后追加 RetouchLayer 整图 pass |
| `ui/editor/EditorScreen.kt` | 改 | 画笔蒙版 UI、retouch 滑块、预设面板入口 |
| `data/preset/` | **新增** | 预设 JSON 加载/存储 |
| `core/decode/ArwFullDecoder.kt` + `ui` | 改 | F03 ② 代理/母版切换 |

---

## 10. 实施顺序（每 phase 独立提交 + CI）

1. **Phase 1**：`RetouchMask` + `RasterMask` + 画笔 UI 骨架（仅能画 mask 并落 `RETOUCH` 日志，无算子）。
2. **Phase 2**：中性灰磨皮（试点，跑通 RetouchLayer 整图 pass 闭环 + 单测）。
3. **Phase 3**：美型液化。
4. **Phase 4**：祛瑕。
5. **Phase 5**：追色。
6. **Phase 6**：P1b-5~10 预设（先做参数栈预设；`.cube` 视决策）。
7. **Phase 7**：F03 ② 两段式渐进占位（独立速赢，可提前到 Phase 1 后）。
8. **穿插**：P1b-6 真机测试（你侧），每块后下 APK 自测。

---

## 11. 待拍板决策（请评审时确认）

- **Q1 参数模型**：① 扁平 `EditParams` + 新增 `RetouchState`（推荐） / ② 改有序 op 列表。
- **Q2 美型液化**：P1 先不依赖人脸检测、用画笔作用域 + 滑块（推荐） / 等 P1+ 人脸检测再做人脸感知液化。
- **Q3 中性灰算法**：box 近似表面模糊（CPU 友好，推荐） / 导向滤波（更保边缘但更重）。
- **Q4 预设范围**：本期只做参数栈预设（推荐） / 参数栈 + `.cube` LUT 打包一并做。
- **Q5 F03 ② 排期**：并入本批（推荐，独立速赢，可先于算子） / 单独批次。

---

*本稿为设计草稿，未落地代码；落地以你评审后的决策为准，逐块提交并走 CI 闸门。*
