package com.hifn.pixelcake.core.edit

/**
 * 对象作用域调色（批次 5）的数据模型与层构建，见 `docs/OBJECT_TONE_DESIGN.md`。
 *
 * 本文件**零 Android 依赖**：`ObjectScope` / `ObjectLayer` / `LayerStack` / [blendStack8]
 * 全部是纯 Kotlin ⇒ 可以在 JVM 上直接单测（`EditEngine` 那条路要 `Bitmap`，测不了）。
 * 这是本批最重要的可测接缝：**作用域的组成关系与叠加代数**都能在没有设备的情况下钉死。
 *
 * 依赖方向（刻意，勿破）：`core/ml` 实现 `core/edit.RetouchMask` ⇒ 只能 `core/ml → core/edit`。
 * 所以「哪些语义槽位组成哪个作用域」放在这里，而**模型类的数字编号只允许出现在 `core/ml` 的一处映射里**。
 */

/**
 * 分割模型的语义槽位（6 个，与 `selfie_multiclass_256x256` 的 6 个输出通道一一对应）。
 *
 * ⚠️ **刻意不带数字**。写成 `enum class ScopePart(val channel: Int)` 会让「模型换了类别顺序」
 * 这件事散落在 `core/edit` 里，而那是 `core/ml` 的知识。数字映射只有一处：
 * `SkinMaskPostProcess.classIndexOf(part)`。
 */
enum class ScopePart {
    Background,
    Hair,
    BodySkin,
    FaceSkin,
    Clothes,

    /**
     * 模型第 5 类，MediaPipe 的原名是 **others（其他）**：眼镜、帽子、饰品、手持物、宠物、食物…
     * 一切不属于前五类的前景物体都归在这里。
     *
     * UI 上叫「配饰」而不是「其他」（`ObjectScope.Accessories` 的 KDoc 有完整理由）。
     */
    Others
}

/**
 * 8 个可选的对象作用域 = 模型原生 6 类 + 2 个派生（`docs/OBJECT_TONE_DESIGN.md` §3）。
 *
 * ## 顺序即**使用频次**（与一级/二级工具条同一条约定）
 *
 * 人物（最常做）→ 皮肤 → 面部 → 身体 → 头发 → 衣服 → 配饰 → 背景。
 * 从「整体的人」一路收到「具体部位」，最后是背景 —— 这条顺序本身就是用户的思考路径。
 *
 * ## 派生作用域**不是**「子蒙版取 max」
 *
 * `人物` 与 `皮肤` 必须**先求和、再阈值羽化、最后平滑**（见 §3.1）。`thresholdAndFeather` 不是线性的
 * （死区 + 线性 + 饱和），拿已羽化的子蒙版取 `max` 会在「概率刚好落在羽化区间」的像素上给出不同结果，
 * 表现为**脖子/脸颊边缘一条不属于任何一侧的缝**。实现落在 `ObjectMasks` 里，
 * 由 [parts] 的**并集语义**表达 —— 这里只描述「由哪些槽位组成」，不描述怎么算。
 *
 * ## 刻意**不**做「反向选择」
 *
 * 8 个作用域里已有一对天然互补（[Person] ↔ [Background]），再加反转开关会让 chip 行出现
 * 两个语义重叠的入口，用户得先推理「我点的是反转后的人还是背景」。将来若要「除了头发以外的一切」，
 * 正确做法是**再加一个派生作用域**，而不是给每个作用域挂开关（8 个入口 × 2 = 16 个语义状态）。
 */
enum class ObjectScope(val label: String, val parts: Set<ScopePart>) {

    /** 派生：除背景以外的一切。 */
    Person("人物", setOf(ScopePart.Hair, ScopePart.BodySkin, ScopePart.FaceSkin, ScopePart.Clothes, ScopePart.Others)),

    /** 派生：身体皮肤 ∪ 面部皮肤。 */
    Skin("皮肤", setOf(ScopePart.BodySkin, ScopePart.FaceSkin)),

    /** 原生类：面部皮肤。脸与身体常需分开处理（脸要保通透，手背往往要先统一肤色）。 */
    FaceSkin("面部", setOf(ScopePart.FaceSkin)),

    /** 原生类：身体皮肤。 */
    BodySkin("身体", setOf(ScopePart.BodySkin)),

    Hair("头发", setOf(ScopePart.Hair)),

    Clothes("衣服", setOf(ScopePart.Clothes)),

    /**
     * 原生类，模型原名 **others（其他）**。
     *
     * ## 为什么 chip 上写「配饰」而不是「其他」
     *
     * 「其他」在 UI 上是一个**无法被理解的分类名** —— 用户不知道里面有什么，也就永远不敢点。
     * 而这一类实际上装的是眼镜/帽子/饰品/手持物这类**附着在人身上的小物件**，
     * 把它读作「配饰」是一个**可用性上的翻译**。
     *
     * ⚠️ 但这是一次**有意的近似**，不是精确命名（宠物、食物也会落进这一类），
     * 所以参数面板里必须在它下面挂一句 Hint 说明实际覆盖范围 —— 否则就是在骗用户。
     */
    Accessories("配饰", setOf(ScopePart.Others)),

    Background("背景", setOf(ScopePart.Background))
}

/**
 * 一个对象图层（`docs/OBJECT_TONE_DESIGN.md` §4）。
 *
 * ## 为什么没有 `enabled` 开关
 *
 * [strength] 拉成 0 就等价于「临时停用」，而且**不会丢参数** —— 这正是保留强度、砍掉开关的理由：
 * 两个控件做同一件事时，用户会不知道该用哪个（开关 + 强度 0 的界面语义还要额外解释）。
 * 真要清干净用「移除本层」（面板里就有）。
 *
 * 一个作用域**至多一层**，唯一性由 [upsert] / [without] 保证（UI 是唯一写者）。
 *
 * @param params 该层的调色参数。只允许**逐像素**项；效果与细节两段会在
 *   [buildLayerStack] 里被剥掉（`docs/OBJECT_TONE_DESIGN.md` §7）。
 * @param strength 该层整体强度 0..1。逐像素只多一次乘法。
 */
data class ObjectLayer(
    val scope: ObjectScope,
    val params: EditParams = EditParams(),
    val strength: Float = 1f
)

/**
 * 以 [scope] 为准写入一层：已存在同作用域的层则**整层替换**，否则追加。
 *
 * 用列表而不是 `Map<ObjectScope, ObjectLayer>`：`EditSnapshot` 需要一个**有序、可比较**的容器，
 * 而 `Map` 的迭代顺序会让撤销栈的比较语义变得依赖实现细节。
 */
fun List<ObjectLayer>.upsert(layer: ObjectLayer): List<ObjectLayer> {
    val i = indexOfFirst { it.scope == layer.scope }
    return if (i < 0) this + layer else mapIndexed { idx, old -> if (idx == i) layer else old }
}

/** 移除 [scope] 对应的层；不存在时原样返回（**不分配新列表**，便于在热路径上调）。 */
fun List<ObjectLayer>.without(scope: ObjectScope): List<ObjectLayer> =
    if (none { it.scope == scope }) this else filterNot { it.scope == scope }

/** 该作用域是否已有层。 */
fun List<ObjectLayer>.hasScope(scope: ObjectScope): Boolean = any { it.scope == scope }

/** 该作用域是否已**有效**（有层且该层的参数不是中性的）—— 供 chip 上的「已调整」标记。 */
fun List<ObjectLayer>.isAdjusted(scope: ObjectScope): Boolean =
    firstOrNull { it.scope == scope }?.params?.isNeutral()?.not() ?: false

// ———————————————————————————————————————————————————————————————
// 渲染态
// ———————————————————————————————————————————————————————————————

/**
 * 一层在**渲染期**的形态：已按目标尺寸建好的程序 + 已对齐的蒙版 + 权重。
 *
 * [mask] 一定是**网格化**蒙版（`MlSkinMask` / `ObjectMask` 这类共享 `FloatGrid` 的实现）：
 * `resampleTo(w, h)` 对它们只换目标尺寸、零大分配。**绝不要**把画笔栅格（`RasterMask`）接进来 ——
 * 它的 `resampleTo` 会真的分配 `FloatArray(w*h)`，33MP 下是 131MB
 * （`docs/OBJECT_TONE_DESIGN.md` §5.5）。
 */
class AppliedLayer(
    val scope: ObjectScope,
    val program: PixelProgram,
    val mask: RetouchMask,
    val weight: Float
)

/**
 * 已按目标尺寸建好的图层栈。
 *
 * [EMPTY] 是三条渲染入口的默认实参：**没有对象层的用户走的就是它**，
 * 逐像素路径上只多一次 `list.isEmpty()` 判断（分支条件对整帧恒定，预测得极好）。
 */
class LayerStack internal constructor(val layers: List<AppliedLayer>) {

    val isActive: Boolean get() = layers.isNotEmpty()
    val size: Int get() = layers.size

    companion object {
        val EMPTY = LayerStack(emptyList())
    }
}

/**
 * `List<ObjectLayer>`（参数态）→ [LayerStack]（渲染态）。
 *
 * ## 四条过滤，每一条都对应一个「本来会白算」的真实场景
 *
 * | 条件 | 处置 | 场景 |
 * |---|---|---|
 * | `strength <= 0` | 跳过 | 用户把层强度拉到 0（= 临时停用） |
 * | 剥掉效果/细节后**全中性** | 跳过 | 用户点了一下 chip 建了层、但还没调任何参数 |
 * | `maskOf(scope)` 为 `null` | 跳过 | 模型不可用；该作用域在本图里没有任何像素也会返回合法全 0 蒙版（那条不跳过） |
 * | 其余 | 建 [PixelProgram] + `resampleTo(w, h)` | —— |
 *
 * 由此得到一个**必须保住**的好性质：**「选中一个对象作用域」这个动作本身不改变任何像素**。
 * 用户可以先选后调，误触 chip 不会改照片。
 *
 * ## 不负责去重
 *
 * 同一作用域出现两次时会**两层都生效**（顺序取决于列表顺序）。唯一性由 [upsert] / [without]
 * 在 UI 层保证，那才是被单测钉住的接缝 —— 在这里再补一层去重只会引入「先者胜还是后者胜」的新语义，
 * 而且它对每一帧都要跑。
 *
 * @param maskOf 作用域 → 蒙版。返回 `null` 表示该作用域当前无法提供蒙版。
 */
fun buildLayerStack(
    layers: List<ObjectLayer>,
    maskOf: (ObjectScope) -> RetouchMask?,
    w: Int,
    h: Int
): LayerStack {
    if (layers.isEmpty() || w <= 0 || h <= 0) return LayerStack.EMPTY
    val out = ArrayList<AppliedLayer>(layers.size)
    for (l in layers) {
        val weight = l.strength.coerceIn(0f, 1f)
        if (weight <= 0f) continue
        val p = l.params.withoutWholeImageStages()
        if (p.isNeutral()) continue
        val mask = maskOf(l.scope)?.resampleTo(w, h) ?: continue
        out.add(AppliedLayer(l.scope, PixelProgram(p, frameW = w, frameH = h), mask, weight))
    }
    return if (out.isEmpty()) LayerStack.EMPTY else LayerStack(out)
}

// ———————————————————————————————————————————————————————————————
// 逐层插值（渲染热路径）
// ———————————————————————————————————————————————————————————————

/**
 * 单像素逐层插值：把 [base]（整图调色后的 0xAARRGGBB）**依次**朝每一层的目标值插值。
 *
 * ## 语义：层是「叠加量」（`docs/OBJECT_TONE_DESIGN.md` §5.2）
 *
 * `base` 是**整图管线跑完的结果**，而不是原始像素 —— 层的曝光是「在整图之上再加一点」，
 * 与 Lightroom 的局部调整一致，也正是用户选「图层栈（**可叠加**）」时那个词的含义。
 *
 * 代价必须写在旁边：`base` 已经量化成 8-bit，所以「整图推爆高光、再用层拉回来」救不回已 clip 的电平；
 * 从很暗的整图结果里大幅提亮某一层会看到色阶。这两条都写进了面板 Hint，而不是留给用户去撞。
 *
 * ## 只在最后量化一次
 *
 * 累加器全程是 `Float`（初值 = `base` 的三个字节），**只在返回前打包一次**。
 * 每层自身的 `applySrgb8` 出口仍会量化一次（那是 `PixelProgram` 的既有出口），
 * 误差上界是每层 ≤0.5 LSB、`N` 层累计 ≤`N/2` LSB —— 通常 1~3 层，肉眼不可辨。
 * 不用「浮点出口」换来「零量化」的原因是：那需要给 `runBand` 加按分片分配 scratch 的参数，
 * 而 `PixelProgram` 实例是多核共享的、不能挂共享可变缓冲。**用一次热路径签名变更换 1.5 LSB 不划算。**
 *
 * ## 为什么写成 `inline` + 两个内联 lambda
 *
 * 零分配：`targetAt` / `weightAt` 捕获的是调用方的局部量（`x` / `py` / `u` / `v` / 三个字节），
 * 内联后不产生任何对象。这是 `PixelProgram` 类文档里那条「逐像素路径零分配是不可退让的约束」
 * 在本批的落点。
 *
 * 顺带给了单测一个干净的接缝：测试里传普通 lambda 就能验证叠加代数，不需要 Android 的 `Bitmap`。
 *
 * @param base 整图管线结果（0xAARRGGBB，alpha 恒为 0xff）
 * @param count 生效层数；`<= 0` 时**逐位返回 [base]**（零成本的恒等路径）
 * @param targetAt `k → 第 k 层的目标颜色`（0xAARRGGBB）。**只有 `weightAt(k) > 0` 时才会被调用**，
 *   所以调用方不必在它内部再做一次权重判断（也就不会白算一轮 `applySrgb8`）。
 * @param weightAt `k → 第 k 层的权重` = `蒙版强度 × 层强度`；`> 1` 会被钳到 1
 */
internal inline fun blendStack8(
    base: Int,
    count: Int,
    targetAt: (Int) -> Int,
    weightAt: (Int) -> Float
): Int {
    if (count <= 0) return base
    var r = ((base shr 16) and 0xff).toFloat()
    var g = ((base shr 8) and 0xff).toFloat()
    var b = (base and 0xff).toFloat()
    for (k in 0 until count) {
        val raw = weightAt(k)
        if (raw <= 0f) continue
        val w = if (raw > 1f) 1f else raw
        val t = targetAt(k)
        r += (((t shr 16) and 0xff) - r) * w
        g += (((t shr 8) and 0xff) - g) * w
        b += ((t and 0xff) - b) * w
    }
    return (0xff shl 24) or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)
}

/** 浮点 → 8-bit（四舍五入 + 钳位）。与 `PixelProgram.finish` 的收尾口径一致。 */
internal fun clamp8(v: Float): Int = when {
    v <= 0f -> 0
    v >= 255f -> 255
    else -> (v + 0.5f).toInt()
}

// ———————————————————————————————————————————————————————————————
// 层参数的清洗
// ———————————————————————————————————————————————————————————————

/**
 * 剥掉**整幅阶段**的两段参数（效果 / 细节），得到「只含逐像素项」的参数。
 *
 * `docs/OBJECT_TONE_DESIGN.md` §7：暗角是画面几何、颗粒是整幅确定性噪声，
 * 「面部的暗角」讲不通；细节是邻域算子，结构上就进不了 `PixelProgram`（用户已拍板只在整图层）。
 *
 * 剥离是**双保险**的后半段：前半段是 UI 在这两个分类下对对象作用域显示空态
 * （用户根本调不到）。只有 UI 那半会漏掉「代码直接构造 `ObjectLayer`」的路径 ——
 * 而只有这里会制造「调了没反应」的假象，所以两个都要。
 */
internal fun EditParams.withoutWholeImageStages(): EditParams = copy(
    // 效果（批次 3）
    vignetteAmount = 0f, vignetteMidpoint = 0.5f, vignetteFeather = 0.5f, vignetteRoundness = 0f,
    grainAmount = 0f, grainSize = 0.35f, grainRoughness = 0.5f,
    // 细节（批次 4）
    sharpenAmount = 0f, sharpenRadius = 1f, sharpenDetail = 0.5f, sharpenMasking = 0f,
    nrLuminance = 0f, nrLuminanceDetail = 0.5f, nrColor = 0f, nrColorDetail = 0.5f,
    clarity = 0f, texture = 0f
)

/**
 * 中性参数（= 逐像素路径上一个字节都不会变）。
 *
 * ⚠️ **不要**手写「37 个字段逐个 `!= 0f`」的判据：那是一个每加一个参数就要同步一次、
 * 漏一个就静默失效（chip 的「已调整」标记不亮 / 空层被当成有效层参与渲染）的清单。
 * 这里用 `data class` 的 `equals` 与一份常量默认实例比较 ⇒ **新增字段自动纳入**，不可能漏。
 */
internal fun EditParams.isNeutral(): Boolean {
    if (this == NEUTRAL_PARAMS) return true
    // 唯一的例外：`lutId == "none"` 时 `lutIntensity` 不参与任何计算
    // （`PixelProgram` 的判据是 `lutIntensity > 0 && lutId != "none"`）。
    // 若不认这种情况为中性，「在 LUT 页拖一下强度但没选滤镜」就会凭空造出一个有效空层。
    return lutId == "none" && copy(lutIntensity = NEUTRAL_PARAMS.lutIntensity) == NEUTRAL_PARAMS
}

/** [isNeutral] 的比较基准。文件级 `val` ⇒ 全进程只建一次。 */
private val NEUTRAL_PARAMS = EditParams()
