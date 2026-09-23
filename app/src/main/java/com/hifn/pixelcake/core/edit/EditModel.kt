package com.hifn.pixelcake.core.edit

/**
 * HSL 混色的 8 个色相通道（`docs/TONING_DESIGN.md` §2.3.1）。
 *
 * 通道中心角与 Lightroom 的色相环对位，且**刻意不等宽**：红/橙更窄（肤色集中在橙，
 * 窄一点才修得动），绿更宽（自然景观里绿色的跨度本来就大）。等分会把「肤色」摊到
 * 红与黄两个通道上，导致「只想修肤色」时背景跟着动。
 */
object HslBands {

    /** UI 文案，顺序即通道顺序。 */
    val LABELS: List<String> = listOf("红", "橙", "黄", "绿", "青", "蓝", "紫", "品红")

    /** 通道中心角（度）。与 [LABELS] 一一对应。 */
    val CENTERS: FloatArray = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 275f, 315f)

    const val COUNT = 8

    /** 单通道满量程时的色相旋转角：±30°。**这是 UX 上限，不是数学上限**，理由见 [HslMix]。 */
    const val HUE_SWING_DEG = 30f
}

/**
 * HSL 混色：8 个色相通道 × (色相 / 饱和度 / 明度) = 24 项。
 *
 * ## 为什么色相旋转要封在 ±30°
 *
 * 色相一路转到 ±180° 在数学上完全成立，但「橙 → 黄绿」会让**肤色直接变绿**，
 * 用户不会认为「我把它转过去了」，只会认为 App 坏了。Lightroom 的单通道色相量程同样很窄。
 * 上限是 UX 决定：宁可给不到，也不要给出一个能一键毁掉照片的滑块。
 *
 * ## 三个列表的长度不一定等于 [HslBands.COUNT]
 *
 * 允许外部（预设 / 将来的 JSON 存档）只给出部分通道，所以读取一律走 [hueOf] / [satOf] / [lumOf]
 * —— 越界返回中性值 0，绝不抛下标异常。写回走 [withHue] / [withSat] / [withLum]，会自动补齐长度。
 */
data class HslMix(
    val hue: List<Float> = List(HslBands.COUNT) { 0f },
    val sat: List<Float> = List(HslBands.COUNT) { 0f },
    val lum: List<Float> = List(HslBands.COUNT) { 0f }
) {
    fun hueOf(i: Int): Float = hue.getOrElse(i) { 0f }
    fun satOf(i: Int): Float = sat.getOrElse(i) { 0f }
    fun lumOf(i: Int): Float = lum.getOrElse(i) { 0f }

    fun withHue(i: Int, v: Float): HslMix = copy(hue = hue.patched(i, v))
    fun withSat(i: Int, v: Float): HslMix = copy(sat = sat.patched(i, v))
    fun withLum(i: Int, v: Float): HslMix = copy(lum = lum.patched(i, v))

    /** 全部通道中性 ⇒ 渲染时整段跳过（这是「默认不付新参数的性能代价」的关键）。 */
    val isIdentity: Boolean
        get() = hue.all { it == 0f } && sat.all { it == 0f } && lum.all { it == 0f }
}

/** 把下标 [i] 处替换为 [v]；长度不足时补齐到 `max(原长, i+1, COUNT)`，缺位填 0。 */
private fun List<Float>.patched(i: Int, v: Float): List<Float> {
    val size = maxOf(this.size, i + 1, HslBands.COUNT)
    return List(size) { idx -> if (idx == i) v else getOrElse(idx) { 0f } }
}

/** 彩色分级里的一个分区（阴影 / 中间调 / 高光 / 全局）。 */
data class GradingBand(
    /** 色相，0..1 映射到 0..360°，与 HSV 同口径。 */
    val hue: Float = 0f,
    /** 着色强度 0..1。为 0 时该分区**完全不参与**（[isNeutral] 也只看它与 [lum]）。 */
    val sat: Float = 0f,
    /** 该分区的明度偏移 −1..1。 */
    val lum: Float = 0f
) {
    /** 是否中性：色相不参与判断 —— 着色强度为 0 时改色相不该有任何画面变化。 */
    val isNeutral: Boolean get() = sat == 0f && lum == 0f
}

/**
 * 彩色分级（= 美图秀秀的「色调分离」）：4 个亮度分区各自染一个色 + 调明度。
 *
 * 这是「电影感」的主要来源（阴影压青、高光偏橙），也是成本最低的一档
 * —— 参数在渲染前塌缩成 3 条 256 项 LUT，逐像素只有 3 次查表 + 3 次加法。
 */
data class ColorGrading(
    val shadows: GradingBand = GradingBand(),
    val midtones: GradingBand = GradingBand(),
    val highlights: GradingBand = GradingBand(),
    val global: GradingBand = GradingBand(),
    /** 分区之间的过渡锐度 0..1：越大越「硬」（分区界限越清楚）。 */
    val blending: Float = 0.5f,
    /** 分区界线整体平移 −1..1：正 = 界线往亮部挪（阴影区变大）。 */
    val balance: Float = 0f
) {
    val isNeutral: Boolean
        get() = shadows.isNeutral && midtones.isNeutral && highlights.isNeutral && global.isNeutral

    /** 按分区序号取（0 阴影 / 1 中间调 / 2 高光 / 3 全局）。越界回落到全局。 */
    fun band(zone: Int): GradingBand = when (zone) {
        0 -> shadows
        1 -> midtones
        2 -> highlights
        else -> global
    }

    fun withBand(zone: Int, b: GradingBand): ColorGrading = when (zone) {
        0 -> copy(shadows = b)
        1 -> copy(midtones = b)
        2 -> copy(highlights = b)
        else -> copy(global = b)
    }

    companion object {
        /** UI 分区文案，顺序与 [band] / [withBand] 的序号一致。 */
        val ZONE_LABELS: List<String> = listOf("阴影", "中间调", "高光", "全局")

        const val ZONE_COUNT = 4
    }
}

/**
 * 非破坏编辑参数栈（与 DEV_PLAN §3.3 的 operations 对应）。
 * 所有调整只是改这里的字段，像素仅在渲染时物化。
 *
 * ## 关于新增字段的兼容性（`docs/TONING_DESIGN.md` §6.1）
 *
 * 批次 1 / 2 / 3 / 4 新增的全部字段**默认中性** ⇒ 旧预设（`Preset.params` 用具名参数构造）行为完全不变，
 * 也无须任何迁移；撤销栈（`EditSnapshot` 持整体）与重渲订阅（`snapshotFlow` 观察整个对象）自动覆盖。
 *
 * ⚠️ 这四批里有**两处**「字段虽然默认中性、但改动远不止加字段」的例子，而且**性质完全不同**，
 * 别把它们的风险混为一谈：
 *
 * - **批次 3 的暗角 / 颗粒：改了签名。** 字段中性（`amount = 0`）时整级跳过，但它们的存在把
 *   `PixelProgram` 的入口从「只看当前像素」改成了「当前像素 + 归一化坐标 `(u, v)`」——见本类末尾
 *   「效果」那一段。签名变了 ⇒ 所有调用点必须一起改。这不是兼容性问题（没有旧调用方），
 *   而是一次**必须原子完成**的改动：漏一个调用点就是编译错误，CI 会拦。
 * - **批次 4 的细节：完全没碰签名。** 降噪 / 清晰度 / 纹理 / 锐化是**邻域**算子（一个输出像素要看
 *   一圈邻居），无法写成 `(r, g, b, u, v) → (r, g, b)`。它们被实现成「在已物化的目标位图上**再跑
 *   一遍**」的独立第二遍 pass（`DetailPass` + `DetailApply`）⇒ `PixelProgram` 零改动、现有调用点
 *   零改动，回归面 < 批次 3。代价是它**不是** `(u, v)` 的纯函数 ⇒ 只能说「预览与导出观感一致」，
 *   **不能**说逐位相同（见本类末尾「细节」段的说明）。
 *
 * ## ⚠️ `shadows` / `highlights` 的符号约定已改为 Lightroom 口径：**正值 = 往亮推**
 *
 * 旧实现是 `r -= highlights * lum * 0.5` ⇒ 正值**压暗**高光（「拉回」语义），与 Lightroom 相反。
 * 批次 1 统一为「四区滑块正方向一致 = 提亮」，理由是心智模型：四个滑块方向一致时用户不需要
 * 每次判断「这个滑块的正方向是往哪边」。已逐个核对 10 个内置预设，**没有一个用到这两个字段**
 * ⇒ 翻转是安全的；一旦 JSON 参数栈落地（DEV_PLAN §3.3）就再也不能翻了。
 */
data class EditParams(
    // ———— 影调 ————
    val exposureEv: Float = 0f,
    val contrast: Float = 0f,
    /** 黑场：只动暗部末端，**加法**才能抬起真黑（乘法抬不动 0）。正 = 提亮。 */
    val blacks: Float = 0f,
    val shadows: Float = 0f,
    /** 高光。正 = 提亮（见类 KDoc 的符号约定说明）。 */
    val highlights: Float = 0f,
    /** 白场：只动亮部末端。正 = 提亮。 */
    val whites: Float = 0f,
    /**
     * 高光找回 0..1：**线性域**软压肩的强度（`docs/TONING_DESIGN.md` §5）。
     * 为 0 时仍保留一条极窄的默认压肩（保住三通道比例，修 M1-b 的高光偏色）；
     * 拉高则把压肩起点一路下移，真正把已经「推爆」的高光拉回来。
     */
    val highlightRecovery: Float = 0f,

    // ———— 白平衡 ————
    val temperature: Float = 0f,
    val tint: Float = 0f,

    // ———— 偏好 ————
    /** 自然饱和度：按当前饱和度**反比**加权，已经很艳的地方几乎不动 ⇒ 护肤色。 */
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    /** 去朦胧（近似版：抬黑点 + 轻度对比）。正 = 去雾。 */
    val dehaze: Float = 0f,

    // ———— 曲线（4 条：亮度 + 分通道）————
    val lumaPoints: List<Pair<Int, Int>> = ToneCurve.IDENTITY,
    val redPoints: List<Pair<Int, Int>> = ToneCurve.IDENTITY,
    val greenPoints: List<Pair<Int, Int>> = ToneCurve.IDENTITY,
    val bluePoints: List<Pair<Int, Int>> = ToneCurve.IDENTITY,

    // ———— 颜色（批次 2）————
    val hsl: HslMix = HslMix(),
    val grading: ColorGrading = ColorGrading(),

    // ———— LUT ————
    val lutId: String = "none",
    val lutIntensity: Float = 0.8f,

    // ———— 效果（批次 3：暗角 / 颗粒）————
    //
    // ## ⚠️ 这是全项目**第一批需要像素坐标**的参数
    //
    // 前 20 个字段都是「只看当前像素自己」的纯逐像素算子：给定 (r, g, b) 就能算出输出。
    // 暗角与颗粒不是 —— 它们的效果取决于「这个像素在画面里的哪儿」，于是
    // `PixelProgram.applyLinear` / `applySrgb8` 从批次 3 起多收一对**归一化坐标** `(u, v)`。
    //
    // 坐标口径只有一条、没有例外：
    //
    //     u = (x + 0.5f) / max(w, h)        v = (y + 0.5f) / max(w, h)
    //
    // **按长边归一化**（两支分母相同），不是「按各自的宽/高归一化」，更不是像素坐标。
    // 理由是本项目最硬的那条不变量 ——「预览所见 = 导出所得」：同一次编辑会在三种尺寸上渲染
    // （预览 2048 / 代理 3504 / 导出 7008），只有按长边归一化才能让同一个几何量在三处
    // **占画面的比例相同**，暗角的范围和颗粒的粗细看起来才一致。
    // 反过来若用像素坐标：`grainSize` 在导出里会比预览粗 3.4 倍，用户会认为「导出的颗粒坏了」。
    //
    // `+ 0.5f` 是取**像素中心**（不是左上角）：否则整幅几何相对画面中心偏半像素，
    // 暗角的四个角会不对称。
    //
    // ## 全部默认中性 ⇒ 零成本
    //
    // `vignetteAmount = 0f`、`grainAmount = 0f` 时 [PixelProgram] **整段跳过**这两级，
    // 逐像素路径连一次乘加都不付。所以「默认没开暗角/颗粒」的用户，性能与批次 2 完全一致。

    /**
     * 暗角强度 −1..1。**正 = 提亮四角，负 = 压暗四角**（Lightroom「暗角量」同口径，
     * 也与本项目「正 = 往亮推」的统一约定一致）。
     *
     * 常用的那个动作（压暗四角）落在负半区 —— 这是刻意的：把最常用的方向放在负值一侧，
     * 换来的是「四个影调滑块 + 暗角」方向语义完全统一，用户不必记两套。
     * 满量程时四角保留 20% 亮度（不是纯黑）—— 「一键把照片毁掉」的滑块是设计事故。
     */
    val vignetteAmount: Float = 0f,

    /**
     * 暗角作用起点 0..1：0 = 从画面中心附近就开始压，1 = 只有最外的一圈。
     * 映射到半径后**四角恒定落在「完全生效」处**（见 [ColorMath.buildVignetteLut]），
     * 所以它调的是「影响范围有多大」，不是「四角有多暗」。
     */
    val vignetteMidpoint: Float = 0.5f,

    /** 暗角过渡宽度 0..1：0 = 硬边（会看到一圈界线），1 = 从起点一路平滑铺到四角。 */
    val vignetteFeather: Float = 0.5f,

    /**
     * 暗角轮廓圆度 −1..1：**正 = 圆（在像素里是正圆），负 = 贴住画幅（方）**。
     *
     * 为什么需要它：3:2 的底片上，「到中心等距」的轮廓是**椭圆**时才贴合画幅，
     * 是**正圆**时会在长边两侧留下一片没被压到、也没被压到的区域。两种都是合法审美，
     * 所以做成可调的，而不是替用户选死。
     */
    val vignetteRoundness: Float = 0f,

    /** 颗粒强度 0..1。0 = 完全关闭（**整级跳过**，不是乘 0）。 */
    val grainAmount: Float = 0f,

    /**
     * 颗粒尺寸 0..1：0 = 细（长边上约 1200 个颗粒），1 = 粗（约 300 个），对数插值。
     *
     * 它是「长边上铺多少个颗粒」，**不是像素数** —— 这是 §上面那段的归一化纪律在颗粒上
     * 唯一的落点，也是最容易写错的一个（写成像素数就会让预览与导出的颗粒粗细差 3.4 倍）。
     */
    val grainSize: Float = 0.35f,

    /**
     * 颗粒对比 0..1：对噪声做一次**带符号幂整形**，0.5 恰好是恒等（指数 = 1）。
     * 0 → 更软（指数 1.6，接近高斯，散得开），1 → 更硬（指数 0.4，更多「椒盐」味的小黑点）。
     *
     * 只在 [grainAmount] > 0 时有意义（`amount = 0` 时整个颗粒级被跳过）。
     */
    val grainRoughness: Float = 0.5f,

    // ———— 细节（批次 4：降噪 / 清晰度 / 纹理 / 锐化）————
    //
    // ## ⚠️ 这是全项目**唯一**的非逐像素参数：输出依赖「当前像素周围长什么样」
    //
    // 前 27 个字段（批次 1 / 2 / 3）都能写成 (r, g, b, u, v) → (r, g, b) 的纯函数。
    // 这 10 个不行 —— 它们要一张**低频参考图**（对邻域做模糊）才谈得上计算，因此不能放进
    // `PixelProgram`（那个程序没有放邻域的位置），而是由 `DetailPass` 在目标位图上单独跑一遍。
    //
    // ## 由此带来一条**口径差别**，必须写在参数旁边
    //
    // 批次 3 承诺「预览与导出逐位相同」（它是 (u, v) 的纯函数）。**本批不能承诺**：
    // 邻域算子的输出依赖核半径与邻域内容，跨分辨率时半径按长边的固定比例等比缩放，
    // 得到的是**同一个观感**而不是同一个数字。所以这里的承诺只有一条：
    // **核半径随长边等比 ⇒ 观感一致**。别把它当成回归指标去写逐位断言（那是假护栏）。
    //
    // 10 个字段全部默认中性 ⇒ 整段跳过 ⇒ 不开细节的用户性能与批次 3 完全一致。

    /** 锐化强度 0..1。0 = 关闭（**整段跳过**，不是乘 0）。 */
    val sharpenAmount: Float = 0f,

    /**
     * 锐化核半径倍率 0.5..3：`rFine = 长边 / 1000 × 本值`（即长边的 0.5‰..3‰），**不是像素数**。
     *
     * 做成倍率而不是绝对像素，是因为绝对像素在预览（长边 2048）与导出（7008）上会给出
     * 不同的观感 —— 那正是批次 3 已经踩过的「导出的颗粒比预览粗 3.4 倍」同一个坑。
     */
    val sharpenRadius: Float = 1f,

    /** 锐化的高频/低频分配 0..1：0 = 只锐高频（几乎无光晕），1 = 连中频一起锐（更「利」但会有光晕）。 */
    val sharpenDetail: Float = 0.5f,

    /**
     * 边缘蒙版 0..1：0 = 全图锐化，1 = 只锐边缘。
     *
     * ⚠️ 与旁边的 `*Detail` 量纲相反：这里的 0 是「不设限」，1 是「限制最严」。
     * 这是 LR 的同名滑块口径，沿用它是为了让从 LR 过来的人不必重新学。
     */
    val sharpenMasking: Float = 0f,

    /** 亮度降噪强度 0..1。朝低频参考图拉，三通道等比缩放 ⇒ 色相与饱和度不动。 */
    val nrLuminance: Float = 0f,

    /**
     * 亮度降噪的细节保留 0..1：**1 时该级完全不改像素**（保护阈值归零），0 时最激进。
     *
     * 它实现的是「多大以内的亮度差算噪声」。LR 的「细节」滑块方向一致：越高越保细节。
     */
    val nrLuminanceDetail: Float = 0.5f,

    /** 色度降噪强度 0..1。只拉 (R−Y, B−Y) 两个色度差，亮度保持不变 ⇒ 去彩噪而不动明暗结构。 */
    val nrColor: Float = 0f,

    /** 色度降噪的细节保留 0..1，语义同 [nrLuminanceDetail]。色度噪声是大块的，其阈值上限取亮度的 3.5 倍。 */
    val nrColorDetail: Float = 0.5f,

    /** 清晰度 −1..1：大尺度局部对比（长边 1/120 的低频带），提「通透感」。正 = 增强。 */
    val clarity: Float = 0f,

    /** 纹理 −1..1：中频增强，带**反**边缘门 ⇒ 提质感而不提边缘。正 = 增强。 */
    val texture: Float = 0f
)

/**
 * 一次可撤销的**完整编辑快照**：tonal 参数 [EditParams] + 人像精修 [RetouchState]。
 *
 * P1b 之前撤销栈只存 [EditParams]，导致「磨皮/液化/追色」等 retouch 改动不可撤销（P1 要求
 * 非破坏编辑栈可撤销/重做）。改为以快照为粒度后，一次 undo/redo 会同时回退调色与人像精修，
 * 语义与「一步操作」一致。
 *
 * 注：画笔蒙版/瑕疵描迹属「工具选择态」，由 UI 层单独持有，不入此快照（撤销不回退画笔轨迹）。
 */
data class EditSnapshot(
    val params: EditParams = EditParams(),
    val retouch: RetouchState = RetouchState()
)

/** 撤销/重做历史：持有当前 [EditSnapshot]，push 新状态即记录旧状态。 */
class EditHistory(initial: EditSnapshot = EditSnapshot()) {
    private val undoStack = mutableListOf<EditSnapshot>()
    private val redoStack = mutableListOf<EditSnapshot>()
    var current: EditSnapshot = initial
        private set

    fun push(next: EditSnapshot) {
        undoStack.add(current)
        current = next
        redoStack.clear()
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.add(current)
        current = undoStack.removeAt(undoStack.lastIndex)
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.add(current)
        current = redoStack.removeAt(redoStack.lastIndex)
        return true
    }

    fun reset(s: EditSnapshot = EditSnapshot()) {
        undoStack.clear()
        redoStack.clear()
        current = s
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
}
