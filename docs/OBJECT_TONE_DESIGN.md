# PixelCake 对象调色（作用域化调色）设计

> 参照 Lightroom 的「蒙版 / 局部调整」：**同一套调色参数，作用域可以是整图，也可以是识别到的某个对象。**
> 本文是这一批（下称「批次 5」）的**唯一设计依据**。姊妹文档：`docs/TONING_DESIGN.md`（参数体系）、
> `docs/UI_DESIGN.md`（界面）、`docs/P1p_DESIGN.md`（端侧推理栈）。三者冲突时以本文为对象作用域部分的准。

---

## 1. 现状盘点：**模型早就在包里，只用了其中一路**

这一批**不需要新模型、不需要新依赖**。事实核查（读源码得出，非推测）：

| 事实 | 位置 |
|---|---|
| 6 类分割模型已随包 | `app/src/main/assets/models/selfie_multiclass_256x256.tflite`（MediaPipe，Apache-2.0） |
| 6 类常量**已全部定义** | `SkinMaskPostProcess.CLASS_BACKGROUND=0 / HAIR=1 / BODY_SKIN=2 / FACE_SKIN=3 / CLOTHES=4 / OTHERS=5` |
| 输出为 channel-last `[1,256,256,6]` 概率 | `LiteRtSkinMaskModel.inferProbs` → `outputs[0].readFloat()` |
| 低分辨率网格 + 双线性上采样已就绪 | `FloatGrid` / `MlSkinMask`（256KB 网格，**绝不物化整幅** `FloatArray(w*h)`） |
| 阈值 + 羽化 + 平滑已是纯函数 | `SkinMaskPostProcess.thresholdAndFeather` / `smooth3x3` |
| 每图一次推理 + key 缓存 + OOM 熔断 + GPU→CPU 级联 | `MlMaskProvider` / `LiteRtSkinMaskModel` |

**唯一被丢掉的**：`SkinMaskPostProcess.skinProbability` 只把 `body-skin + face-skin` 加了起来，
**其余 4 类概率算完即弃**。也就是说「背景 / 头发 / 衣服 / 其他」这四路信息每次推理都算出来了，
只是没人用。本批的全部工作就是**把这些已经算出来的东西接出来，并且让它们在渲染管线里真正生效**。

---

## 2. 三项已拍板（2026-09-24，用户选择，不再讨论）

| # | 问题 | 结论 | 理由（用户选择时给出的口径） |
|---|---|---|---|
| **q-0** | 多条作用域同时存在时怎么合成？ | **图层栈（可叠加）** | 整图 + 每个对象各一层，可多层同时生效（人提亮的同时背景压暗）。 |
| **q-1** | 作用域有哪些？ | **6 类 + 2 个派生 = 8 个** | 模型原生 6 类已经够用，另外两个派生（人 = 除背景、人物皮肤 = 身体+面部）是使用频次最高、且**不需要任何新推理**的组合。 |
| **q-2** | 邻域算子（锐化/降噪/清晰度/纹理）能否按对象作用？ | **只在整图层** | 邻域算子要一张低频参考图，按对象施加在语义与实现上都站不住脚（见 §7）。 |

---

## 3. 作用域全表（8 个）

`P` = 模型某一类的概率。所有作用域都是**若干类概率之和**（softmax ⇒ 六类之和 = 1，
所以「除背景」既等于 `1 − P(背景)`、也等于其余五类之和；实现取**求和**，
因为它对「输出未归一化的模型」也成立，且只有一处 `coerceIn(0,1)`）。

| # | 作用域 | 中文标签 | 组成 | 性质 | 典型用法 |
|---|---|---|---|---|---|
| 1 | `Person` | 人物 | `Hair + BodySkin + FaceSkin + Clothes + Others` | **派生**（= 除背景） | 人物提亮、加饱和，背景不动 |
| 2 | `Skin` | 皮肤 | `BodySkin + FaceSkin` | **派生** | 肤色统一、去红、提亮面部 |
| 3 | `FaceSkin` | 面部 | `FaceSkin` | 原生类 | 面部单独提亮 / 压高光 |
| 4 | `BodySkin` | 身体 | `BodySkin` | 原生类 | 身体皮肤与面部**分开**处理（脸和手臂常需不同） |
| 5 | `Hair` | 头发 | `Hair` | 原生类 | 加对比、压反光 |
| 6 | `Clothes` | 衣服 | `Clothes` | 原生类 | 提饱和、去色偏 |
| 7 | `Accessories` | 配饰 | `Others` | 原生类（**模型名为「其他」**） | 见下方 ⚠️ |
| 8 | `Background` | 背景 | `Background` | 原生类 | 压曝光、降饱和、去色 |

⚠️ **第 7 项的标签是「配饰」，但模型的类名是「其他」**。MediaPipe 的 `selfie_multiclass` 第 5 类
把「眼镜 / 帽子 / 饰品 / 手持物 / 宠物 / 食物」等**所有不属于前五类的前景物体**归在一起。
把它叫作「其他」在 UI 上是一个**无法被理解的分类名**（用户不知道里面有什么），
所以 chip 上写 `配饰`，并在该组下方用一句 Hint 说明它实际覆盖什么。
**这是一次「可读性优先」的有意命名，不是把「其他」误读成了「配饰」** —— 参数面板里必须带那句 Hint。

### 3.1 派生作用域不是「蒙版取 max」

`人` 与 `人物皮肤` 必须是**先求和、再阈值羽化、最后平滑**，不能拿已羽化好的子蒙版去 `max`：

```
正确：thresholdFeather(P(2) + P(3))         ← 本批做法
错误：max(thresholdFeather(P(2)), thresholdFeather(P(3)))
```

`thresholdAndFeather` 不是线性的（它有一段死区 + 一段线性 + 一段饱和），
而 `max` 只对线性算子可交换。在「肤色概率刚好落在羽化区间」的像素上，两者结果不同 ——
表现为**脖子/脸颊边缘出现一条不属于任何一侧的缝**。所以派生作用域各自走完整条链路，
代价是多算一遍 256×256 的浮点（可忽略，见 §8）。

### 3.2 刻意**不**做「反向选择」

Lightroom 有「反转蒙版」。此处不做，判据是**语义重叠**：8 个作用域里已经有一对天然互补
（`人物` ↔ `背景`），再叠一层「反转」会让 chip 行出现两个含义重叠的入口，
用户需要先想清楚「我点的是反转后的人还是背景」——而这正是参数面板最该避免的**需要推理才能点**。
将来若真出现「除了头发以外的一切」这类需求，正确做法是**再加一个派生作用域**，
而不是给每个作用域都挂一个反转开关（那会把 8 个入口变成 16 个语义状态）。

---

## 4. 数据模型

```kotlin
// core/edit/ObjectLayers.kt —— 全部纯 Kotlin、零 Android 依赖 ⇒ 可 JVM 单测

/** 模型语义槽位。**刻意不带数字**：数字只允许出现在 core/ml 的一处映射里。 */
enum class ScopePart { Background, Hair, BodySkin, FaceSkin, Clothes, Others }

enum class ObjectScope(val label: String, val parts: Set<ScopePart>) {
    Person("人物", setOf(Hair, BodySkin, FaceSkin, Clothes, Others)),
    Skin("皮肤", setOf(BodySkin, FaceSkin)),
    FaceSkin("面部", setOf(FaceSkin)),
    BodySkin("身体", setOf(BodySkin)),
    Hair("头发", setOf(Hair)),
    Clothes("衣服", setOf(Clothes)),
    Accessories("配饰", setOf(Others)),
    Background("背景", setOf(Background))
}

data class ObjectLayer(
    val scope: ObjectScope,
    /** 该层的调色参数。**只允许逐像素项**，见 §7 的剥离规则。 */
    val params: EditParams = EditParams(),
    /** 该层整体强度 0..1。**0 = 临时停用且不丢参数** —— 这就是本批不设 enabled 开关的理由。 */
    val strength: Float = 1f
)
```

### 4.1 为什么 `ScopePart` 与 `ObjectScope` 放在 `core/edit`，而数字映射放在 `core/ml`

`core/ml/MlSkinMask` 已经在实现 `core/edit/RetouchMask` ⇒ 依赖方向是 **`core/ml` → `core/edit`**。
若把 `ObjectScope` 放进 `core/ml`，`core/edit` 的 `ObjectLayer` 引用它就会形成**包级双向依赖**。
所以：**语义（哪些槽位组成哪个作用域）留在 `core/edit`，模型类的编号只出现在 `core/ml` 的唯一一处
`classIndexOf(part)` 里**。这样「模型换了类别顺序」这件事只有一个落点，
而且那处 `when` 是穷尽的 —— 漏一个槽位就编译不过。

### 4.2 「一个作用域至多一层」

`List<ObjectLayer>` 在 UI 层由两个纯函数保证唯一性（也是单测对象）：

```kotlin
fun List<ObjectLayer>.upsert(layer: ObjectLayer): List<ObjectLayer>  // 有则替换、无则追加
fun List<ObjectLayer>.without(scope: ObjectScope): List<ObjectLayer>  // 移除（不存在则原样返回）
```

选它们而不是 `Map<ObjectScope, ObjectLayer>`：`EditSnapshot` 里需要一个**有序、可比较**的容器，
而 `Map` 的相等性与迭代顺序会让撤销栈的比较变得微妙。列表 + 唯一性不变量更直白。

### 4.3 `EditSnapshot` 增加第三个字段

```kotlin
data class EditSnapshot(
    val params: EditParams = EditParams(),
    val retouch: RetouchState = RetouchState(),
    val layers: List<ObjectLayer> = emptyList()   // ← 新增，默认空
)
```

默认空 ⇒ **旧预设、旧调用点、撤销栈的既有语义零变化**。
但 ⚠️ 这带来一个**必须主动排查的静默风险**：`EditSnapshot(params, retouch)` 这种**位置参数**调用
在新增第三个字段后依然编译通过，却会把 `layers` 悄悄丢成空 ⇒ **撤销一次就抹掉全部对象层**。
因此本批必须 `Grep "EditSnapshot("` 扫全部调用点并逐一补上 `layers`，
并在 `ObjectLayersTest` 里钉一条「三参数快照保留 layers」的断言。
（这是 `kotlin-offline-syntax-gate` 技能第 8 条「给枚举/数据类加成员必须扫消费点」的同一类问题，
只不过这次消费点从 `when` 变成了**位置参数调用**。）

---

## 5. 渲染管线：**单趟逐层 lerp**

### 5.1 核心洞察

`N` 层**不需要 N 份整幅缓冲、不需要 N 遍循环**。它们可以在**同一个像素循环内**各自算完再插值：

```
out ← 整图管线(原始像素)                       ← 已有的一行，不变
for k in 生效的层:
    w ← layer[k].mask(x, y) × layer[k].strength
    if w <= 0: continue
    t ← layer[k].program(base)                 ← 该层参数作用在**整图结果**上
    out ← out + (t − out) × w                  ← 三通道各自插值
```

逐像素代价从「1 次 `applySrgb8`」变成「1 + N 次」，**仅此而已**：没有额外缓冲、
没有额外循环、没有额外同步点。`N` 层同时生效时就是 `N` 倍逐像素算力 —— 这是本设计**唯一**的性能代价，
且它**只在用户真的建了层时才付**（见 §5.4）。

### 5.2 为什么层作用在「整图结果」上，而不是「原始像素」上（**关键语义决定**）

两种候选语义：

- **(A) 叠加量**：`t = layer.params(整图调色后的像素)` ⇒ 层的曝光是**在整图之上再加一点**；
- **(B) 绝对值**：`t = layer.params(原始像素)` ⇒ 层的曝光是**这块区域的总曝光**。

**选 (A)**，三条理由：

1. **与 Lightroom 一致**：LR 的局部调整是叠加量（整图 +0.5EV、人物蒙版 +1EV ⇒ 人物共 +1.5EV）。
   本项目从批次 1 起就在「符号约定 / 分区口径 / 蒙版语义」上刻意对齐 LR（见 `TONING_DESIGN` §6.3），
   没有理由在这里换一套。
2. **与用户的原话一致**：q-0 选的是「图层栈（**可叠加**）」。叠加这个词的含金量就在这里 ——
   若取 (B)，层与整图就是**两个互斥的候选结果**，那不是叠加而是覆盖。
3. **实现上三条渲染入口完全同构**：`renderIntoSrgb` / `renderIntoLinear` / `renderLinearFile` 里
   层的目标值都写作 `layerProgram.applySrgb8(baseR, baseG, baseB, u, v)` ——
   **同一行代码、同一个出口**。而 (B) 需要线性路径用 `applyLinear(原始 r16/g16/b16)`、
   sRGB 路径用 `applySrgb8(原始 r8/g8/b8)`，两条入口各写一遍 ⇒
   「预览所见 = 导出所得」立刻变成一句需要逐条维护的承诺。

**代价（必须诚实写下）**：整图结果已经量化成 8-bit，所以 (A) 让层从「已调色、已量化的图」上再加工。
极端场景是「整图 +2EV 把高光推爆，再想用人物层 −1EV 把人拉回来」——**已经 clip 的电平回不来**。
这与 Lightroom 的行为一致，不是本实现的缺陷；正确做法是用整图曝光去处理这类需求。
另外「从很暗的整图结果里大幅提亮某一层」会看到 8-bit 色阶 —— 面板 Hint 里明确写出这条，
而不是让用户自己撞上去。

### 5.3 只在最后量化一次

累加器 `r/g/b` 全程是 `Float`（初值 = 整图结果的三个字节），**只在返回前打包一次**：

```kotlin
internal inline fun blendStack8(base: Int, count: Int,
                                targetAt: (Int) -> Int, weightAt: (Int) -> Float): Int
```

写成 `inline` + 两个内联 lambda 是**为了零分配**：`targetAt` / `weightAt` 捕获的是
`x, py, u, v, r8/g8/b8` 这些局部量，内联后没有任何对象产生。
（`PixelProgram` 的类文档里那条「逐像素路径零分配是不可退让的约束」在这里同样成立。）

`inline` 还顺带给了单测一个干净的接缝：测试里传普通的 lambda 就能验证叠加代数，
不需要 Android 的 `Bitmap`（见 §12）。

**为什么不用「浮点出口」**：让 `PixelProgram` 新增一个把结果写进调用方 `FloatArray(3)` 的出口，
需要**每个线程一份 scratch**（`EditEngine.runBand` 把一个 `PixelProgram` 实例交给多核并发调用，
共享可变缓冲是数据竞争），那就要给 `runBand` 加一个「按分片分配临时缓冲」的参数、
改它的签名与 4 个调用点。换来的收益是「层的目标值少一次量化」，
而它的误差上界是 **每个层 ≤0.5 LSB**、`N` 层累计 ≤`N/2` LSB（`N` 通常 1~3 ⇒ ≤1.5 LSB）。
**用一次热路径签名变更去换 1.5 LSB 是不可接受的交换**（批次 4 已经论证过同一件事：
回归面越小越好）。这里明确记下这个取舍，将来真机若反馈出色带再回头。

### 5.4 零成本默认

`LayerStack.EMPTY` 时，`EditEngine` 的三条入口走的还是那一行 `program.applySrgb8(...)`，
**一次多余的乘加都不付**。构建期也会把「中性层 / 强度 0 的层 / 蒙版为 null 的层」全部剔除：

```kotlin
fun buildLayerStack(layers: List<ObjectLayer>, maskOf: (ObjectScope) -> RetouchMask?,
                    w: Int, h: Int): LayerStack
```

`build` 的过滤顺序（每一步都是一个「本来会白算」的场景）：

| 条件 | 处置 | 对应场景 |
|---|---|---|
| `strength <= 0` | 跳过 | 用户把层强度拉到 0（临时停用） |
| `params` 全中性 | 跳过 | 用户点了一下 chip 建了层、但还没调任何参数 |
| `maskOf(scope)` 返回 `null` | 跳过 | 模型不可用 / 该作用域在本图里为空 |
| 其余 | 建 `PixelProgram`，蒙版 `resampleTo(w, h)` | —— |

由此得到一个好性质：**「选中一个对象作用域」这个动作本身不改变任何像素**，
用户可以先选后调，不会因为误触 chip 就把照片改了。

### 5.5 蒙版必须**网格化**（33MP 的硬约束）

`AppliedLayer.mask` 一律来自 ML 网格（`FloatGrid` 256×256 ≈ 256KB）。
原因是 `RetouchMask.resampleTo` 对它的实现是**只换目标尺寸、共享网格**，零大分配；
而画笔栅格（`RasterMask`）的 `resampleTo` 会**真的分配 `FloatArray(w*h)`** ——
在 7008×4672 上那是 **131MB**，正是 R10 之后全仓要避免的东西。

**所以对象层不接受画笔蒙版**（画笔是「人像」分类里给磨皮用的补正手段，与本批是两件事）。
这条必须写下来，否则将来有人为了「让用户能手动修蒙版」把 `brushMask` 接进来，
就会在导出时静默吃掉 131MB。

---

## 6. 管线顺序

```
① 逐像素调色（整图 + N 个对象层，同一个循环）
② 人像精修（RetouchLayer：磨皮 / 液化 / 祛瑕 / 追色）
③ 细节（DetailPass：锐化 / 降噪 / 清晰度 / 纹理）
```

与批次 4 完全相同，**本批没有插入任何新阶段**：对象层是① 的一部分，
而①②③ 的相对顺序一个字都没动（②③ 的顺序在批次 4 已被讨论并钉死：锐化必须晚于磨皮）。

由此推出一条必须写进文档的局限：

> **「人脸的暗角」不存在。** 暗角/颗粒（效果）与锐化/降噪（细节）都是整幅阶段。
> 本批能做的是「让**调色**按对象作用」，不是「让**所有算子**都按对象作用」。

这与用户 q-2 的选择一致，也是唯一能保住「预览所见 = 导出所得」的分界线：
① 是 `(u,v)` 的纯函数（可按对象作用），② 有跨分辨率口径问题（只承诺观感），
③ 是邻域算子（跨分辨率只承诺观感）。**能在①里做的作用域，就只在①里做。**

---

## 7. 哪些参数**不能**进层

`ObjectLayer.params` 用的是一个完整的 `EditParams`，但其中两段在构建层程序时被**归零剥离**：

| 段 | 字段 | 为什么剥离 |
|---|---|---|
| **效果** | `vignetteAmount/Midpoint/Feather/Roundness`、`grainAmount/Size/Roughness` | 暗角是**画面几何**、颗粒是**整幅确定性噪声**。「面部的暗角」在语义上讲不通（那个人脸不在画面中心，暗角凭什么绕它一圈）；「只有人身上有颗粒」则是把胶片噪声当成了贴纸。两者都是整幅的收尾动作（`TONING_DESIGN` §2.4）。 |
| **细节** | `sharpen*`、`nr*`、`clarity`、`texture` | 邻域算子，**结构上**就进不了 `PixelProgram`（详见 `DetailPass` 类文档）。用户已拍板「只在整图层」。 |

**双保险**：① UI 在这两个分类下对对象作用域显示空态说明（用户根本调不到）；
② `buildLayerStack` 构建层程序时把这两段字段归零（防预设/代码绕过 UI 塞进来）。
只有 ① 会在有人用代码直接构造 `ObjectLayer` 时漏掉，只有 ② 会让用户「调了没反应」——所以两个都要。

被剥离的字段在**整图层**照旧完全生效，行为与批次 4 一致。

---

## 8. 内存与性能账（33MP = 7008×4672）

| 项 | 量级 | 说明 |
|---|---|---|
| 模型原始概率（**常驻缓存**） | `256×256×6×4B = 1.5MB` | `MlMaskProvider` 缓存 probs；皮肤蒙版与各作用域蒙版都从这一份构造 |
| 单个作用域网格 | `256×256×4B = 256KB` | 懒构建、按作用域缓存 |
| 8 个作用域全用 | `≈2MB` | 上界；实际用几层建几个 |
| **整幅蒙版** | **0** | 一条都不物化（对比：`FloatArray(7008×4672)` = **131MB**） |
| 逐像素额外代价 | 每层 +1 次 `applySrgb8` + 1 次 `mask.sample` | 层的 program 只含逐像素项 ⇒ 无额外 LUT 内存压力 |
| `PixelProgram`/层 | 每层 ~几 KB（LUT） | 层数为 0 时全部为 0 |

**单次推理而非两次**：皮肤蒙版（磨皮用）与对象蒙版本来都需要那 6 类概率，
`MlMaskProvider` 因此重构成「**缓存 probs，皮肤/对象两份产物都从它构造**」。
若不重构，同时开自动蒙版与对象作用域会**对同一张图跑两遍模型**（Pixel 6 基准 GPU ≈71ms / CPU ≈218ms）。
重构后按 key 命中缓存，第二遍是纯内存操作。该重构**不改变** `skinMaskFor` 的对外语义与降级行为。

---

## 9. UI 结构

### 9.1 作用域 chip 行：**钉在滚动之外**

在参数面板的二级 chip 行（`ParamSubBar`）**上方**再加一行：

```
┌─ 一级工具条：人像 调色 颜色 曲线 细节 效果 预设 ─────────────┐
├─ 【作用域】整图 人物 皮肤 面部 身体 头发 衣服 配饰 背景   ← 本批新增，横向可滚
├─ 【二级分组】影调 白平衡 偏好                              ← 已有
└─ 滚动内容：当前作用域 + 当前分组的滑块                       ← 已有
```

**为什么钉住而不是跟着滚**：这是一条**安全**约束，不是排版偏好。
作用域决定「下面这些滑块改的是谁」；若它滚出屏幕，用户可能以为自己在调整图，
实际在改背景 —— 这类错误**在画面上是可见的（只有背景变了）**，但用户已经调了好几下才发现，
且他会先怀疑「App 坏了」。把「当前在编辑哪个作用域」永久摆在视野里，是这一批唯一能防住它的办法。

代价是钉住高度增加约 48dp（`FilterChip` 32dp + 上下各 8dp）。用两条判断把代价压到最小：

- 只在 **调色 / 颜色 / 曲线 / 细节 / 效果** 这 5 个分类下显示（它们才是逐像素参数所在）；
  人像（画笔/美型）与预设（无参数）不显示；
- `FilterChip` 的高度与圆角都是 M3 默认，**与 `GlassChipRow` 既有口径一致**（不新造控件）。

### 9.2 参数面板：**同一套滑块，换一个写入目标**

这是本批 UI 侧最省事、也最不容易错的一点：**面板本身一个字都不改**。
`ParamPanel` 收到的 `params` 就是「当前作用域的参数」，`onParamChange` 写回「当前作用域的参数」：

```kotlin
val layer = activeScope?.let { sc -> layers.firstOrNull { it.scope == sc } }
val shown = layer?.params ?: params                                  // activeScope == null ⇒ 整图
val write = if (layer == null) onParamChange
            else { np -> onLayersChange(layers.map { if (it.scope == layer.scope) it.copy(params = np) else it }) }
```

于是**分组标题右侧的「重置」、每个滑块的量程与格式、Hint 文案，全部自动跟着作用域切换**——
因为它们本来就只是「读写一个 `EditParams`」。零新控件、零分支。

### 9.3 对象作用域激活时的面板头部

选中某个对象作用域后，滚动内容的最前面多出一块**层管理区**：

- 标题：`作用域：面部`（明确当前模式）
- 滑块：**强度 0~100%**（`ObjectLayer.strength`）
- 文字按钮：**移除本层**（移除后自动切回「整图」）

⚠️ 这会让滑块的纵向位置整体下移，破坏「同一个滑块永远在同一个高度」的肌肉记忆。
这是**有意的**：作用域是一个模式，模式切换理应重新排版；
而且面板头部只有 2 个控件（约 90dp），比「用户不知道自己改的是谁」的代价小得多。

### 9.4 细节 / 效果分类下的对象作用域：**宁可不给，不给假的**

对象作用域下切到「细节」或「效果」，内容区显示空态说明：

> 「细节」与「效果」只作用于整图（它们是整幅的邻域/几何阶段）。切回「整图」即可调整。

**不显示灰掉的滑块**：灰滑块会让人以为是「暂时不可用」而反复找开关。
一句说明 + 明确的出口（切回整图）比一排灰控件诚实。

### 9.5 没有新控件

- 作用域 chip 行 → 复用 `GlassChipRow`（只加一个 `itemEnabled` 参数，见 §10）；
- 强度滑块 → 复用 `ParamSlider`；
- 移除按钮 → 复用既有的 `TextButton` 口径（面板内次要动作统一用文字按钮，不用实心按钮）。
- **不动 `GlassSegmentedBar`**：一级分类仍是 7 项。

---

## 10. 降级链

| 环节 | 失败时 | 用户看到 |
|---|---|---|
| 模型加载 / 推理失败 | `MlMaskProvider` 返回 `null`（**不抛异常**，沿用既有契约） | 作用域行里「整图」可选，8 个对象 chip **禁用**；面板顶部一句「对象识别不可用，仅支持整图」 |
| 推理 OOM | 本会话直接关闭模型（既有逻辑） | 同上 |
| 单个作用域在本图为空 | `ObjectMasks` 仍返回合法蒙版（全 0） | 层的效果为 0 —— 与「不存在该对象」的物理事实一致，不做特殊提示 |
| 层存在但模型后来不可用 | `buildLayerStack` 的 `maskOf` 返回 `null` ⇒ 该层被跳过 | 层仍留在状态里（不悄悄删用户的东西），但**不生效**；chip 禁用 + 提示句说明了原因 |

⚠️ 「层还在但没生效」是这批**唯一**可能出现「状态与画面不一致」的地方，
所以它必须有那句提示句兜住 —— 否则用户会以为参数丢了。

---

## 11. 兼容性与影响面

| 项 | 影响 |
|---|---|
| 旧预设（`Preset`） | `Preset` 不带 layers ⇒ 选预设 = 整图调色，**行为完全不变** |
| `EditSnapshot` 位置参数调用 | ⚠️ 必须全量扫一遍（见 §4.3） |
| `EditEngine` 三条入口 | 各加一个 `layers: LayerStack = LayerStack.EMPTY`（**带默认值、放在尾随 lambda 之前**），现有调用点零改动 |
| `PixelProgram` | **零改动**（本批不新增任何出口，见 §5.3） |
| `DetailPass` / `DetailApply` | **零改动**（细节仍是整幅第二遍 pass） |
| `RetouchMask` / `FullMask` / `MaxMask` | **零改动**（复用既有蒙版抽象；`null` = 不执行的约定继续成立） |
| `MlMaskProvider` | 内部重构（probs 缓存 + 两个产物），对外新增 `objectMasksFor`，`skinMaskFor` 语义不变 |
| `GlassChipRow` | 新增可选参数 `itemEnabled`，既有调用点零改动 |

---

## 12. 验收方式

### 12.1 离线（CI 是唯一的编译器）

1. **JVM 单测**（三个文件，全部零 Android 依赖 —— 三条渲染入口要 `Bitmap`，在 JVM 上测不了，
   所以本批所有可持续验证的性质都压在这里）
   - `ObjectLayersTest`（新增）：作用域组成（`Person` = 5 槽且 = 除背景、`Skin` = 身体 ∪ 面部、
     6 个原生作用域与 6 个槽位**一一对应**、label 无重复）；`upsert` 的唯一性与**原地替换不改位置**、
     `without` 在不存在时**不分配新列表**；`EditSnapshot` **三参位置形式保留 layers** +
     `EditHistory` 的 undo/redo 往返；`buildLayerStack` 的**四条过滤**（强度 0 / 中性 / 蒙版 `null` /
     尺寸退化，且**合法全 0 蒙版不跳过**）+ 强度钳位 + `resampleTo(目标尺寸)`；
     **剥离规则的行为级验证**（不只是「字段被改了」，而是「产出的程序对坐标逐点忽略效果/细节」）；
     `isNeutral` 覆盖全部段落（含 `lutId == "none"` 例外）；`blendStack8` 的**叠加代数**
     （0 / 负层数 = **逐位**恒等；权重 0 连目标都不算；权重 1 取目标、>1 钳到 1；
     两层**顺序敏感**；**累加器直到最后一刻才量化** ⇒ 两层各 50% 得 `0xBF` 而不是 `0xC0`）
     与 `clamp8` 的**四舍五入 + 饱和**。
   - `ObjectMasksTest`（新增）：`maskFor` 的**并集语义**（每个作用域只看自己的槽位；`Person` 与
     `Background` 在此消彼长下互补；**本图没有该对象时是合法的全 0 蒙版而非 `null`**）；
     **`Skin` 与 `MlSkinMask.fromProbs` 逐点逐位相同**（0 容差）+ `LO` / `HI` 常量钉死；
     **懒构建 + 按 `ordinal` 缓存**（上界 8 个网格）；`resampleTo` **共享同一份 `Grids`**、
     与直接构造逐点一致、越界采样为 0、`ObjectMask.scope` 在 `resampleTo` 后保留。
   - `SkinMaskPostProcessTest`（扩展）：`classIndexOf` **双射到 6 个模型通道**；
     `scopeProbability` = 成员类概率之和、上界钳位、**与 `Set` 迭代顺序无关（0 容差）**、
     **`skinProbability` ≡ `scopeProbability({BODY, FACE})` ≡ `scopeProbability(Skin.parts)`**
     （0 容差，钉住这次重构不改变旧行为）；空集 → 全 0；越界 / `side <= 0` 抛
     `IllegalArgumentException`。
2. **语法闸门**：**栈式**括号配平（逐类型计数法对 `([)]` 交叉错配会假绿；剥离器还必须**模板感知**，
   否则 `${ml.accelerator ?: "?"}` 这类嵌套引号模板会提前结束字符串、把 `}` 当代码推栈而错位级联）；
   跨文件调用点实参核对（`EditSnapshot(...)` 的三参、`EditEngine.*` 的 `layers`、
   `MlMaskProvider.objectMasksFor`、`buildLayerStack` / `blendStack8` / `scopeProbability` 的签名）。
3. **文档同步**：`TONING_DESIGN`（§13 指向本文）、`UI_DESIGN`（§4.4 作用域行）、`ui_preview.html`。

### 12.2 真机（离线证明不了的事）

| # | 验的是什么 | 判据 |
|---|---|---|
| 1 | 8 个作用域的蒙版**位置对不对**（最要紧） | 逐个选作用域、把曝光推到 ±2EV，看**变化的区域**是不是那个对象 |
| 2 | 多层的**合成顺序** | 背景 −1EV + 人物 +1EV 同时开，画面要同时呈现两种变化 |
| 3 | **预览 vs 导出** | 同一套层参数，代理预览与全分辨率导出的作用区域与幅度一致 |
| 4 | 「层是叠加量」（§5.2） | 整图 +0.5EV、人物层 +1EV ⇒ 人物应比只开整图时**再亮一档**，而不是回到 +1EV |
| 5 | 逐像素代价 | 建 3 层后拖滑块的帧率；日志里 `slow preview render` 是否明显变多 |
| 6 | 降级 | 关掉模型（或换一台不支持的机型）后 chip 禁用 + 提示句是否出现 |

---

## 13. 落地记录（批次 5，2026-09-24）

### 13.1 代码落点

| 文件 | 变更 |
|---|---|
| `core/edit/ObjectLayers.kt` | **新增**（零 Android 依赖）：`ScopePart`(6) · `ObjectScope`(8) · `ObjectLayer` · `upsert` / `without` / `hasScope` / `isAdjusted` · `AppliedLayer` · `LayerStack`(+`EMPTY`) · `buildLayerStack` · `blendStack8` · `clamp8` · `withoutWholeImageStages` · `isNeutral` |
| `core/ml/SkinMaskPostProcess.kt` | 新增 `classIndexOf(ScopePart)`（**全仓唯一的数字映射点**，穷尽 `when`）与 `scopeProbability(probs, side, parts)`（累加顺序钉死在 `ScopePart.entries`）；`skinProbability` 退化为它的一个特例（**逐位等价**，有断言） |
| `core/ml/ObjectMasks.kt` | **新增**：`ObjectMasks`（持原始 probs + 按 `scope.ordinal` 懒建的 8 槽网格缓存）+ `ObjectMask : RetouchMask`（网格实现，`resampleTo` 只换目标尺寸）；`LO = 0.35` / `HI = 0.65` 与 `MlSkinMask` 的默认值一致 |
| `core/ml/MlMaskProvider.kt` | 重构为「**缓存原始 6 类 probs**，皮肤 / 对象两条产物都从它派生」⇒ 自动蒙版与对象作用域同时开**只推理一次**；新增 `objectMasksFor(context, src, key)`；`ensureProbs` **先成功、后写缓存**；`invalidate()` 清五个字段 |
| `core/edit/EditModel.kt` | `EditSnapshot` 新增第三字段 `layers: List<ObjectLayer> = emptyList()`，KDoc 写明**位置参数会静默丢掉 layers** |
| `core/edit/EditEngine.kt` | 三条入口各加 `layers: LayerStack = LayerStack.EMPTY`；三处像素循环改为「先算整图、再 `applyObjectLayers(...)`」；新增 `private inline fun applyObjectLayers`；`slow render` 与导出日志加 `"layers" to ls.size` |
| `ui/editor/EditorToolbar.kt` | 新增 `enum class ScopeMode`（三态）；`EditorCategory` 的 7 项各自带上 `scopeMode` |
| `ui/editor/EditorScreen.kt` | 新增选择态 `activeScope`、`shownParams` / `writeParams`（决定参数读写指向整图还是某一层）、`selectScope`；面板区在二级 chip 行**之上**插入 `ParamScopeBar`；签名加 4 个新参数（后两个**刻意不给默认值**） |
| `ui/editor/ParamPanel.kt` | `ScopeMode` 分支（`WholeImageOnly` → 空态并 `return`；`NotApplicable` → Hint 后继续）；`ParamScopeBar` / `LayerHeader` / `WholeImageStageNotice`；`GroupHeader` 加 `actionLabel` |
| `ui/components/GlassChipRow.kt` | 新增可选 `itemEnabled: ((T) -> Boolean)?`（用整行 `enabled` 会把「整图」也一起灰掉） |
| `MainActivity.kt` | `layers` 状态 + 独立 `LaunchedEffect(imported)` 预热探测 + `snapshotFlow` 观察 `layers` + 三条渲染入口传 `layerStack` + 6 处 `EditSnapshot(...)` 补第三实参 |
| 测试 | `ObjectLayersTest`（新增）· `ObjectMasksTest`（新增）· `SkinMaskPostProcessTest`（扩展） |
| 文档 | 本文 · `TONING_DESIGN.md` §13 · `UI_DESIGN.md` §4.4 · `ui_preview.html` |

### 13.2 实施中新增 / 收紧的决定（规格里没写、但代码里做死了）

1. **层 = 叠加量**。[blendStack8] 的 `base` 是**整图管线跑完的结果**，层的目标值是「在整图结果之上
   再算一遍 `PixelProgram`」。三条渲染入口**写同一个表达式** ⇒「预览所见 = 导出所得」是**结构性事实**，
   而不是一条需要逐处维护的承诺。代价已写进面板 Hint：整图已量化 8-bit ⇒ **已 clip 的高光救不回来**。
2. **只在最后量化一次**（累加器全程 `Float`）。没有为「零量化」去给 `PixelProgram` 开浮点出口：
   那需要给多核共享的 `PixelProgram` 配按分片 scratch ⇒ 改 `runBand` 签名与 4 个调用点，
   换 ≤ N/2 LSB 不划算。这条被 `accumulatorStaysFloatUntilTheSingleFinalQuantisation` 钉住了。
3. **`ScopePart` 刻意不带数字**。写成 `enum class ScopePart(val channel: Int)` 会让「模型换了类别顺序」
   这件事散落进 `core/edit`，而那属于 `core/ml` 的知识。数字映射只有 `classIndexOf` 一处且是穷尽 `when`
   ⇒ 换模型 / 加槽位会**编译报错**，而不是静默指错通道（错通道的画面「依然好看」，只是改了不该改的）。
4. **`scopeProbability` 的累加顺序钉死在 `ScopePart.entries`**。浮点加法不满足结合律，
   顺着调用方给的 `Set` 迭代会让「同一组槽位」得到 1ulp 之差 ⇒ 预览与导出在极端像素上分叉。
5. **派生作用域走完整链路**（求和 → 阈值羽化 → 平滑），**不是**子蒙版取 `max`：
   `thresholdAndFeather` 非线性（死区 + 线性 + 饱和），取 `max` 会在「概率刚好落在羽化区间」的像素上
   给出不同结果，表现为脖子 / 脸颊边缘一条不属于任何一侧的缝。
6. **对象层只接受网格化蒙版**。`RasterMask.resampleTo` 会真的分配 `FloatArray(w*h)`（33MP = 131MB）
   ⇒ 画笔蒙版**不接进**对象层（`maskOf` 只会给出 `ObjectMask` 这类共享 `FloatGrid` 的实现）。
7. **剥离规则做双保险**：UI 在 `细节` / `效果` 下给空态（用户根本调不到）+ 构建期
   `withoutWholeImageStages()` 归零（挡住「代码直接构造 `ObjectLayer`」的路径）。
   只有前者会漏掉后者 —— 而那种漏法的表现恰好是「调了没反应」。
8. **`buildLayerStack` 不去重**：同一作用域出现两次会两层都生效。唯一性由 `upsert` / `without`
   在 UI 层保证（那才是被单测钉住的接缝）。在这里再补一层去重只会引入「先者胜还是后者胜」的
   新语义，而且它对每一帧都要跑。
9. **UI 的作用域行钉在滚动之外**是**安全约束**而非排版偏好（见 §9.1 与 `UI_DESIGN.md` §4.4）——
   它决定「下面这些滑杆改的是谁」，滚出去之后用户会在不知道作用对象的情况下拖滑杆。
10. **`isNeutral` 用 `== EditParams()` 比较**（`data class` 的 `equals` ⇒ 新增字段自动纳入），
    而不是手写 37 个字段的清单（那是一个每加一个参数就要同步一次、漏一个就静默失效的清单）。
    唯一例外：`lutId == "none"` 时 `lutIntensity` 不参与任何计算 ⇒ 那种情况仍算中性
    （否则「在 LUT 页拖一下强度但没选滤镜」会凭空造出一个有效空层）。
11. **`EditSnapshot` 的位置参数风险**已全量扫过调用点并加了单测断言（见 §4.3 / §13.3）。

### 13.3 离线闸门读数（2026-09-24）

- **栈式括号配平**：`scanned = 114 files, unbalanced = 0`。剥离器是**模板感知**的
  （模式栈 `code` / `str` / `raw` / `tpl`），并用三例**反向自测**证明它不是假绿：
  接受含嵌套引号模板的好文件、报出缺 `}`、报出 `([)]` 交叉错配。
- **跨文件消费点核对**：`EditSnapshot(` 共 6 处（全在 `MainActivity`）均已补第三实参；
  `EditHistoryTest` 用的是**具名参数** ⇒ 不受新字段影响（无需改动）；`EditModel` 内 3 处
  `EditSnapshot()` 是默认值构造。`buildLayerStack` / `blendStack8` / `clamp8` / `isNeutral` /
  `withoutWholeImageStages` / `ObjectMasks.maskFor` / `MlMaskProvider.objectMasksFor` /
  `GlassChipRow(itemEnabled)` / `GroupHeader(actionLabel)` 的定义点与**全部**使用点已逐个核对实参个数。
- ⚠️ **闸门证明不了的**：名字解析（`import`、拼写、可见性）、`internal inline` 能否从 `src/test`
  调用、`when` 是否穷尽。前两条见 §13.4。配平只证明**语法树闭合**，证明不了名字解析 ——
  本项目曾漏过 `import ...unit.dp` 与 `togetherWith` → `togetherTo` 这类错。

### 13.4 仍待 CI 判定（本地无编译器）

1. 全部新代码能否编译 —— 每批的固定项。
2. **`internal inline fun blendStack8` 从单测调用**：依赖 AGP 给单测编译配置的 friend-paths。
   本仓已有先例 —— `FitContentRectTest` 直接调用 `ui/editor/EditorScreen.kt` 里
   `internal fun fitContentRect`，且 CI 是绿的 ⇒ 有据可依（但仍属「编译器说了算」那一类）。
3. `ScopePart.entries` / `ObjectScope.entries`（Kotlin `enum entries`）、`assertEquals(Float, Float, Float)`
   重载、以及用 `assertEquals(Object, Object)` 比较 `Pair` / `List` / `EditParams`。
4. `EditorCategory` 新增 `scopeMode` 之后，所有 `when (category)` 消费点是否仍穷尽 ——
   穷尽 `when` 加枚举成员会**从编译通过变成编译不过**，那正是我们要的那道闸门。

### 13.5 仍待真机验收

§12.2 的 6 条。其中**第 1 条（蒙版位置对不对）优先级最高** ——
它是唯一「错了画面依然好看、只是改了不该改的东西」的缺陷，离线完全测不出来。
