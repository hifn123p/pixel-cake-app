package com.hifn.pixelcake.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.hifn.pixelcake.core.edit.ColorGrading
import com.hifn.pixelcake.core.edit.ColorMath
import com.hifn.pixelcake.core.edit.EditParams
import com.hifn.pixelcake.core.edit.GradingBand
import com.hifn.pixelcake.core.edit.HslBands
import com.hifn.pixelcake.core.edit.ObjectLayer
import com.hifn.pixelcake.core.edit.ObjectScope
import com.hifn.pixelcake.core.edit.RetouchState
import com.hifn.pixelcake.core.edit.ToneCurve
import com.hifn.pixelcake.core.edit.preset.Preset
import com.hifn.pixelcake.ui.components.GlassChipRow
import com.hifn.pixelcake.ui.components.ParamSlider
import com.hifn.pixelcake.ui.components.PresetThumbRow
import com.hifn.pixelcake.ui.theme.Spacing
import kotlin.math.abs

/** 皮肤画笔 / 祛瑕 的子模式 id。与上层 `retouchTool` 的字符串约定一致。 */
private const val TOOL_NONE = "none"
private const val TOOL_SKIN = "skin"
private const val TOOL_BLEMISH = "blemish"

/** 追色风格可选项。 */
private val COLOR_TRANSFER_OPTIONS = listOf(
    "none" to "无",
    "portra" to "波特拉",
    "fuji" to "富士",
    "retro" to "复古",
    "morandi" to "莫兰迪",
    "jp" to "日系"
)

/** LUT 可选项。 */
private val LUT_OPTIONS = listOf(
    "none" to "无",
    "warm" to "暖调",
    "cool" to "冷调",
    "bw" to "黑白",
    "film" to "胶片"
)

// ———————————————————————————————————————————————————————————————
// 面板内部的选择态
// ———————————————————————————————————————————————————————————————

/**
 * 「调色」分类的二级分组。
 *
 * 顺序即**使用频次**（与一级分类同一条约定，见 [EditorCategory]）：曝光/对比度是最高频的两个
 * 动作，所以「影调」排第一并作为默认落点；白平衡在暗房工作流里更靠前，但它一天动不了几次，
 * 让它默认占据面板等于每次调曝光都要多点一次。
 */
enum class ToneTab(val label: String) {
    Tone("影调"),
    WhiteBalance("白平衡"),
    Preference("偏好")
}

/** 「颜色」分类的二级分组。LUT 由一级降到这里（见 [EditorCategory] 的说明）。 */
enum class ColorTab(val label: String) {
    Mix("混色"),
    Grading("分级"),
    Lut("LUT")
}

/** 「曲线」分类的二级分组：一条亮度曲线 + 三条分通道曲线。 */
enum class CurveTab(val label: String) {
    Luma("亮度"),
    Red("红"),
    Green("绿"),
    Blue("蓝")
}

/**
 * 「效果」分类的二级分组（批次 3：暗角 / 颗粒）。
 *
 * ⚠️ **两组是二级 chip 行的下限**，不是随便定的：只有一组时，这行 chip 就恒等于一个
 * 永远选中的按钮 —— 纯噪音。这正是「人像」分类不渲染子模式 chip 行的同一个判据
 * （见 [ParamSubBar]）：它的「画布工具」是一行**组内**控件，不是一个导航层级。
 *
 * 顺序即使用频次：暗角是几乎每张照片都会碰的收尾动作，颗粒是风格化的选择。
 */
enum class EffectTab(val label: String) {
    Vignette("暗角"),
    Grain("颗粒")
}

/**
 * 「细节」分类的二级分组（批次 4：锐化 / 降噪 / 质感）。
 *
 * ## 10 个参数为什么切成这三组
 *
 * 按「管哪一段频带、以及是往里**加**还是往外**抹**」归并（`docs/TONING_DESIGN.md` §12）：
 * 「降噪」是唯一往外**抹**东西的一组；「锐化」加的是最高频的**边缘**；
 * 「质感」加的是中间频带的**体积感**。清晰度与纹理是同一个中频算子的两个旋钮
 * （半径与阈值不同），拆成两个二级分组只会让用户在两个几乎一样的页面之间来回跳。
 *
 * ## 顺序即**使用频次**（与 [ToneTab] 同一条约定，不是工作流顺序）
 *
 * 锐化几乎每张照片都会给一点；降噪只在脏图 / 高 ISO 才动；质感是风格化选择。
 * 所以默认落点是锐化而不是降噪 —— 与「影调排第一」的理由完全一样：让最高频的动作用户
 * 一点开就在手边，而不是每次都要先多点一次 chip。
 *
 * ⚠️ 三组是二级 chip 行的**下限之上**（下限是 2，见 [EffectTab]），所以这里不必论证必要性，
 * 只须说明为什么不是四组。
 */
enum class DetailTab(val label: String) {
    Sharpen("锐化"),
    Noise("降噪"),
    Presence("质感")
}

/**
 * 参数面板**内部**的选择态：二级分组 + 组内被选中的那一格（色相通道 / 分级分区）。
 *
 * ## ⚠️ 这份状态必须由 `EditorScreen` 持有，不能由 [ParamPanel] 自己 `remember`
 *
 * 面板内容挂在 `EditorScreen` 的 `Crossfade` 里，切换一级分类时旧内容会被**销毁**，
 * `remember` 随之清零 —— 用户看到的是「切到曲线看一眼再切回来，混色又跳回红色通道」。
 * 状态挂高一层是唯一可靠的解法（`rememberSaveable` 依赖 `Crossfade` 是否提供
 * `SaveableStateProvider`，这是框架实现细节，不该把正确性押在上面）。
 *
 * 做成一个不可变 data class 而不是散在 `EditorScreen` 里的几个 `var`：回调只有一个
 * `(PanelSelection) -> Unit`，面板里任何一处改动都走 `copy`，不会出现「某一格忘了传 setter」。
 */
data class PanelSelection(
    val toneTab: ToneTab = ToneTab.Tone,
    val colorTab: ColorTab = ColorTab.Mix,
    val curveTab: CurveTab = CurveTab.Luma,
    val detailTab: DetailTab = DetailTab.Sharpen,
    val effectTab: EffectTab = EffectTab.Vignette,
    /** HSL 混色当前编辑的色相通道下标（见 [HslBands.LABELS]）。 */
    val hslBand: Int = 0,
    /** 彩色分级当前编辑的分区下标（见 [ColorGrading.ZONE_LABELS]）。 */
    val gradeZone: Int = 0
)

/**
 * 面板顶部的二级分组 chip 行。
 *
 * ## 为什么它必须**钉在滚动之外**
 *
 * 面板内容放在 `EditorScreen` 的 `verticalScroll` 里（参数多，必须能滚）。若这行 chip 跟着
 * 内容一起滚，「影调」分组的 7 个滑块一滚，切换分组的入口就滚出屏幕 —— 用户想换一组只能
 * 先滚回顶部，这是 71 项参数下面板最容易变成「迷宫」的地方。所以它由 `EditorScreen` 渲染在
 * 滚动容器**之上**，高度恒定、永远在原位。
 *
 * ⚠️ 代价是这条 chip 行不在 `Crossfade` 内：切一级分类时它**立刻**换成新分类的分组，
 * 而下面的内容还在淡出。这是有意的取舍 —— chip 行是导航（应当立刻响应点击），
 * 内容才是需要过渡的东西。
 *
 * 人像 / 预设没有二级分组：**不渲染任何东西**，连内边距都不加，否则卡片顶部会多出一条
 * 24dp 的空白带（「什么都没有，但版面被占了」）。
 */
@Composable
fun ParamSubBar(
    category: EditorCategory,
    selection: PanelSelection,
    onSelectionChange: (PanelSelection) -> Unit,
    modifier: Modifier = Modifier
) {
    val bar = modifier.padding(horizontal = Spacing.cardInner, vertical = Spacing.m)
    when (category) {
        EditorCategory.Tone -> GlassChipRow(
            items = ToneTab.entries,
            selected = selection.toneTab,
            label = { it.label },
            onSelect = { onSelectionChange(selection.copy(toneTab = it)) },
            modifier = bar
        )

        EditorCategory.Color -> GlassChipRow(
            items = ColorTab.entries,
            selected = selection.colorTab,
            label = { it.label },
            onSelect = { onSelectionChange(selection.copy(colorTab = it)) },
            modifier = bar
        )

        EditorCategory.Curve -> GlassChipRow(
            items = CurveTab.entries,
            selected = selection.curveTab,
            label = { it.label },
            onSelect = { onSelectionChange(selection.copy(curveTab = it)) },
            modifier = bar
        )

        EditorCategory.Detail -> GlassChipRow(
            items = DetailTab.entries,
            selected = selection.detailTab,
            label = { it.label },
            onSelect = { onSelectionChange(selection.copy(detailTab = it)) },
            modifier = bar
        )

        EditorCategory.Effect -> GlassChipRow(
            items = EffectTab.entries,
            selected = selection.effectTab,
            label = { it.label },
            onSelect = { onSelectionChange(selection.copy(effectTab = it)) },
            modifier = bar
        )

        EditorCategory.Portrait, EditorCategory.Preset -> Unit
    }
}

/**
 * 参数面板（`docs/UI_DESIGN.md` §4.3 三级工具条的第 3 级）。
 *
 * ## 设计决定：只显示当前分类 + 当前二级分组的参数
 *
 * 旧版把**全部**参数塞进一条长滚动条（约 30 个滑块），用户想调「色温」要先滚过 20 个
 * 磨皮/液化的滑块 —— 这是「仪表盘式」界面最典型的失败：把复杂度原样丢给用户排序。
 * 批次 1 / 2 / 3 把可调项扩到 71 项后，只靠一级分类也不够了（「调色」一类就有 12 项），
 * 所以补一层二级分组（见 [ParamSubBar]）。任意时刻屏幕上只有 2~7 个滑块，
 * 且位置固定（同一个滑块永远出现在面板的同一个高度），肌肉记忆才能建立。
 *
 * ## 曲线 / LUT 不做成 Sheet
 *
 * 它们**本身就是分类的内容**，再弹一层 Sheet 等于同一个概念套两层壳。
 * 小到面板能装下就放在面板里 —— 判据是「内容量」而不是「概念上够不够独立」。
 *
 * ## 卡片底由**调用方**提供（本控件不自带 `GlassCard`）
 *
 * 上层用 `Crossfade` 淡换分类内容，而 `Crossfade` 在过渡期会**同时组合新旧两份**。
 * 若卡片包在这里，两张不透明容器卡会互相叠加（合成覆盖率仅 0.75），面板会发闪且文字互为鬼影 ——
 * 所以「表面」必须留在 `Crossfade` **之外**，本控件只负责内容。详见 `EditorScreen` 第 4 段的说明。
 *
 * ## 每个「组」都留一个「重置」
 *
 * 参数从 10 项扩到 71 项后，用户很容易调到一半找不到回头的路。凡是成组出现的参数
 * （一条曲线、一个色相通道、一个分级分区）都在分组标题右侧挂一个「重置」；
 * 它**只在偏离默认值时才出现**，所以默认状态下不会给面板添噪音。
 *
 * @param onDraggingChange 任一滑块开始/结束拖动。上层据此隐藏非参数 UI（隐形式交互）。
 */
@Composable
fun ParamPanel(
    category: EditorCategory,
    params: EditParams,
    retouch: RetouchState,
    retouchTool: String,
    brushRadius: Float,
    inpaintRadius: Float,
    inpaintCount: Int,
    autoMaskEnabled: Boolean,
    presets: List<Preset>,
    activePresetId: String,
    selection: PanelSelection,
    // 当前作用域（批次 5）：`null` = 整图。**`params` 已经由 `EditorScreen` 按作用域挑好了**
    // —— 于是本控件里没有任何一处需要判断「我在改谁」，滑块、量程、重置、Hint 全部自动跟着切。
    activeScope: ObjectScope?,
    // 当前对象层（`activeScope != null` 时非 null）。只用来读「层强度」并写回。
    activeLayer: ObjectLayer?,
    // 预设 id → 缩略图（由上层按**原图**渲染一次，见 `MainActivity.buildPresetThumbs`）。缺失即占位。
    presetThumbs: Map<String, Bitmap> = emptyMap(),
    onSelectionChange: (PanelSelection) -> Unit,
    onParamChange: (EditParams) -> Unit,
    onParamCommit: () -> Unit,
    onRetouchChange: (RetouchState) -> Unit,
    onRetouchCommit: () -> Unit,
    onToolChange: (String) -> Unit,
    onBrushRadiusChange: (Float) -> Unit,
    onInpaintRadiusChange: (Float) -> Unit,
    onClearMask: () -> Unit,
    onClearInpaint: () -> Unit,
    onAutoMaskChange: (Boolean) -> Unit,
    onPreset: (Preset) -> Unit,
    onLayerChange: (ObjectLayer) -> Unit,
    onLayerCommit: () -> Unit,
    onRemoveLayer: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    // ⚠️ 层管理区排在**分类内容之前**，而且**空态分支也必须经过它** —— 否则用户在「细节」分类下
    // 会被困住：他既调不了参数，也看不到「移除本层」，只能先切回别的分类再回来。
    activeLayer?.let { layer ->
        LayerHeader(
            layer = layer,
            onChange = onLayerChange,
            onCommit = onLayerCommit,
            onRemove = onRemoveLayer,
            onDraggingChange = onDraggingChange
        )
    }

    // 对象作用域下的「细节 / 效果」：它们是**整幅阶段**，按对象施加讲不通
    // （`docs/OBJECT_TONE_DESIGN.md` §7）。显示说明而不是**灰掉的滑块** ——
    // 灰控件会让人以为是「暂时不可用」，于是反复找开关；一句话 + 明确的出口才诚实。
    if (activeScope != null && category.scopeMode == ScopeMode.WholeImageOnly) {
        WholeImageStageNotice(activeScope)
        return
    }

    // 人像 / 预设与作用域无关（画笔/美型有自己的蒙版，预设是一整套参数）。这里只留一句说明，
    // 不让用户以为自己在一个「不生效的作用域」里调参数。
    if (activeScope != null && category.scopeMode == ScopeMode.NotApplicable) {
        Hint("「${category.label}」是整幅操作，不受当前作用域影响。")
    }

    when (category) {
        EditorCategory.Portrait -> PortraitParams(
            retouch, retouchTool, brushRadius, inpaintRadius, inpaintCount,
            autoMaskEnabled, onRetouchChange, onRetouchCommit, onToolChange,
            onBrushRadiusChange, onInpaintRadiusChange, onClearMask, onClearInpaint,
            onAutoMaskChange, onDraggingChange
        )

        EditorCategory.Tone -> ToneParams(
            params, selection.toneTab, onParamChange, onParamCommit, onDraggingChange
        )

        EditorCategory.Color -> ColorParams(
            params, selection, onSelectionChange, onParamChange, onParamCommit, onDraggingChange
        )

        EditorCategory.Curve -> CurveParams(
            params, selection.curveTab, onParamChange, onParamCommit, onDraggingChange
        )

        EditorCategory.Detail -> DetailParams(
            params, selection.detailTab, onParamChange, onParamCommit, onDraggingChange
        )

        EditorCategory.Effect -> EffectParams(
            params, selection.effectTab, onParamChange, onParamCommit, onDraggingChange
        )

        EditorCategory.Preset -> PresetParams(presets, activePresetId, presetThumbs, onPreset)
    }
}

/** 作用域行里「整图」那一格的文案。**与 `EditorScreen` 无关**：整图不是一个 `ObjectScope`，只是栈底。 */
private const val WHOLE_IMAGE_LABEL = "整图"

/** 作用域行的全部选项：`整图` + 8 个对象作用域。文件级 `val` ⇒ 全进程只建一次。 */
private val SCOPE_ITEMS: List<ObjectScope?> = listOf(null) + ObjectScope.entries

/**
 * 作用域 chip 行（批次 5，`docs/OBJECT_TONE_DESIGN.md` §9.1）。
 *
 * ## 为什么它必须**钉在滚动之外**（不是排版偏好，是安全约束）
 *
 * 这一行决定「下面那些滑块改的是谁」。若它跟着内容滚出屏幕，用户可能以为自己在调整图、
 * 实际在改背景 —— 这类错误**在画面上是可见的**（只有背景变了），但用户已经调了好几下才发现，
 * 而且第一个念头是「App 是不是坏了」。让「当前在编辑哪个作用域」永久留在视野里，
 * 是本批唯一能防住它的办法。代价是钉住高度约 48dp，用「只在 5 个逐像素分类下显示」压到最小
 * （见 `ScopeMode.showsScopeBar`）。
 *
 * ## 为什么没有「作用域」这三个字的标签
 *
 * 再加一行标题要多付约 20dp 的常驻高度，而 `整图 | 人物 | 皮肤 | 面部 | 身体 | 头发 | 衣服 | 配饰 | 背景`
 * 本身已经把语义说完了。取而代之的是：面板内容顶部会重复一次「作用域：面部」
 * （见 [LayerHeader]），进入对象作用域后不存在「不知道现在在哪个模式」的状态。
 *
 * ## 「已调整」用一个尾随圆点表示
 *
 * 而不是复用 [GlassChipRow] 的 `swatch` 色块槽：色块槽在**每个** chip 上都会占 12dp
 * （8dp 圆点 + 4dp 间距），没调整的那些会留出一圈看不懂的空白，而且色块槽的语义是
 * 「选项的颜色」（HSL 通道用），用来表达「已修改」是错用。文字后缀零 API 改动、也不会误读。
 *
 * @param current 当前作用域；`null` = 整图
 * @param available 对象识别是否可用（模型是否成功加载）。不可用时 8 个对象 chip **逐个禁用**，
 *   而「整图」保持可用 —— 用整行的 `enabled` 会把「整图」也灰掉，那等于告诉用户「功能坏了」。
 *   禁用是必须的：让用户点进一个注定不生效的作用域，他调半天的滑块一个像素都不会变。
 * @param adjusted 该作用域是否已有非中性参数（决定尾随圆点）
 */
@Composable
fun ParamScopeBar(
    current: ObjectScope?,
    available: Boolean,
    adjusted: (ObjectScope) -> Boolean,
    onSelect: (ObjectScope?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        GlassChipRow(
            items = SCOPE_ITEMS,
            selected = current,
            label = { sc ->
                val base = sc?.label ?: WHOLE_IMAGE_LABEL
                if (sc != null && adjusted(sc)) "$base •" else base
            },
            onSelect = onSelect,
            itemEnabled = { sc -> sc == null || available }
        )
        if (!available) {
            Text(
                "对象识别不可用，仅支持整图。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs)
            )
        }
    }
}

/**
 * 对象作用域的层管理区（面板内容的第一块）。
 *
 * ## 为什么滑块位置会整体下移，而这是有意的
 *
 * 选中对象作用域后，分类内容上方多出约 90dp（标题 + 强度 + 说明）。这会**破坏**
 * `ParamPanel` 类文档里那条「同一个滑块永远出现在面板的同一个高度」的肌肉记忆。
 * 换取的是「用户时刻知道自己在改谁」+「一个把整层效果收一点的总旋钮」，
 * 而作用域本身就是一个**模式**，模式切换理应重新排版。面板头部只有 2 个控件，代价可接受。
 *
 * ## 为什么「移除本层」放在标题右侧而不是一个整行大按钮
 *
 * 与「重置」同一处位置、同一副样子（见 [GroupHeader]）—— 面板里的次要动作只有这一种形态。
 * 做成整行按钮的话，它就在滑块正下方，误触一次会丢掉整层的参数（层可以重建，参数不会回来）。
 */
@Composable
private fun LayerHeader(
    layer: ObjectLayer,
    onChange: (ObjectLayer) -> Unit,
    onCommit: () -> Unit,
    onRemove: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    GroupHeader(
        text = "作用域：${layer.scope.label}",
        showReset = true,
        actionLabel = "移除本层",
        onReset = onRemove
    )
    ParamSlider(
        label = "层强度",
        value = layer.strength,
        valueRange = 0f..1f,
        step = 0.05f,
        format = PERCENT_U,
        onValueChange = { onChange(layer.copy(strength = it)) },
        onValueChangeFinished = onCommit,
        onDraggingChange = onDraggingChange
    )
    Hint("层强度 0% = 临时停用（参数保留，不会丢）。层是**叠加量**：作用在整图调色之后，整图 +0.5EV 加本层 +1EV ⇒ 这块共 +1.5EV。")
}

/**
 * 「细节 / 效果」在对象作用域下的空态说明（`docs/OBJECT_TONE_DESIGN.md` §7）。
 *
 * 说实话比给控件更重要：这两组的实现前提就是整幅的 —— 邻域算子要一张低频参考图，
 * 暗角是画面几何、颗粒是整幅确定性噪声。**「人脸的暗角」不是「暂未实现」，而是没有定义。**
 */
@Composable
private fun WholeImageStageNotice(scope: ObjectScope) {
    GroupLabel("整幅阶段")
    Hint("「细节」（锐化/降噪/清晰度/纹理）与「效果」（暗角/颗粒）作用在整幅画面上 —— 按「${scope.label}」施加讲不通。把上面的作用域切回「整图」即可调整这两组参数。")
}

// ———————————————————————————————————————————————————————————————
// 各分类内容
// ———————————————————————————————————————————————————————————————

@Composable
private fun PortraitParams(
    retouch: RetouchState,
    tool: String,
    brushRadius: Float,
    inpaintRadius: Float,
    inpaintCount: Int,
    autoMaskEnabled: Boolean,
    onRetouchChange: (RetouchState) -> Unit,
    onRetouchCommit: () -> Unit,
    onToolChange: (String) -> Unit,
    onBrushRadiusChange: (Float) -> Unit,
    onInpaintRadiusChange: (Float) -> Unit,
    onClearMask: () -> Unit,
    onClearInpaint: () -> Unit,
    onAutoMaskChange: (Boolean) -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    // 作用域开关放最前：它决定「下面这些滑块作用在哪」，是前置语义而不是某个参数
    GroupLabel("作用域")
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("自动蒙版（AI 皮肤识别）", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (autoMaskEnabled) {
                    "磨皮/美型只作用于识别到的皮肤区，画笔涂抹可补正"
                } else {
                    "已关闭：作用于整幅或画笔涂抹区"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = autoMaskEnabled, onCheckedChange = onAutoMaskChange)
    }

    // 画布工具：改变「点图片会发生什么」，因此是一级参数之前的选择
    Spacer(Modifier.height(Spacing.m))
    GroupLabel("画布工具")
    GlassChipRow(
        items = listOf(TOOL_NONE, TOOL_SKIN, TOOL_BLEMISH),
        selected = tool,
        label = { id -> when (id) { TOOL_SKIN -> "皮肤画笔"; TOOL_BLEMISH -> "祛瑕"; else -> "关闭" } },
        onSelect = onToolChange
    )

    when (tool) {
        TOOL_SKIN -> {
            GroupLabel("磨皮")
            ParamSlider(
                label = "磨皮强度", value = retouch.neutralGray.strength,
                valueRange = 0f..1f, step = 0.05f,
                onValueChange = {
                    onRetouchChange(retouch.copy(neutralGray = retouch.neutralGray.copy(strength = it)))
                },
                onValueChangeFinished = onRetouchCommit,
                onDraggingChange = onDraggingChange
            )
            ParamSlider(
                label = "磨皮半径", value = retouch.neutralGray.radiusNorm,
                valueRange = 0.002f..0.05f, step = 0.002f, format = { "%.3f".format(it) },
                onValueChange = {
                    onRetouchChange(retouch.copy(neutralGray = retouch.neutralGray.copy(radiusNorm = it)))
                },
                onValueChangeFinished = onRetouchCommit,
                onDraggingChange = onDraggingChange
            )
            ParamSlider(
                label = "笔刷大小", value = brushRadius,
                valueRange = 0.005f..0.15f, step = 0.005f, format = { "%.3f".format(it) },
                onValueChange = onBrushRadiusChange,
                onValueChangeFinished = {},
                onDraggingChange = onDraggingChange
            )
            ClearButton("清除皮肤蒙版", onClearMask)
        }

        TOOL_BLEMISH -> {
            GroupLabel("祛瑕")
            ParamSlider(
                label = "瑕疵点半径", value = inpaintRadius,
                valueRange = 0.002f..0.04f, step = 0.002f, format = { "%.3f".format(it) },
                onValueChange = onInpaintRadiusChange,
                onValueChangeFinished = {},
                onDraggingChange = onDraggingChange
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已标记瑕疵点", style = MaterialTheme.typography.bodyMedium)
                Text(
                    inpaintCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ClearButton("清除瑕疵点", onClearInpaint)
        }
    }

    GroupLabel("美型")
    ParamSlider(
        label = "瘦脸", value = retouch.beauty.slimFace, valueRange = 0f..1f, step = 0.05f,
        onValueChange = { onRetouchChange(retouch.copy(beauty = retouch.beauty.copy(slimFace = it))) },
        onValueChangeFinished = onRetouchCommit, onDraggingChange = onDraggingChange
    )
    ParamSlider(
        label = "收下颌", value = retouch.beauty.slimJaw, valueRange = 0f..1f, step = 0.05f,
        onValueChange = { onRetouchChange(retouch.copy(beauty = retouch.beauty.copy(slimJaw = it))) },
        onValueChangeFinished = onRetouchCommit, onDraggingChange = onDraggingChange
    )
    ParamSlider(
        label = "大眼", value = retouch.beauty.eyeEnlarge, valueRange = 0f..1f, step = 0.05f,
        onValueChange = { onRetouchChange(retouch.copy(beauty = retouch.beauty.copy(eyeEnlarge = it))) },
        onValueChangeFinished = onRetouchCommit, onDraggingChange = onDraggingChange
    )

    GroupLabel("追色")
    GlassChipRow(
        items = COLOR_TRANSFER_OPTIONS,
        selected = COLOR_TRANSFER_OPTIONS.firstOrNull { it.first == retouch.colorTransfer.refId },
        label = { it.second },
        onSelect = { (id, _) ->
            onRetouchChange(retouch.copy(colorTransfer = retouch.colorTransfer.copy(refId = id)))
            onRetouchCommit()
        }
    )
    if (retouch.colorTransfer.refId != "none") {
        ParamSlider(
            label = "追色强度", value = retouch.colorTransfer.intensity,
            valueRange = 0f..1f, step = 0.05f,
            onValueChange = {
                onRetouchChange(retouch.copy(colorTransfer = retouch.colorTransfer.copy(intensity = it)))
            },
            onValueChangeFinished = onRetouchCommit, onDraggingChange = onDraggingChange
        )
    }
}

/**
 * 「调色」分类：白平衡 / 影调 / 偏好（`docs/TONING_DESIGN.md` §2.1 / §2.2）。
 *
 * ## 分组内的顺序是有讲究的
 *
 * 影调组按 Lightroom 的顺序排：曝光 → 对比度 → 高光 → 阴影 → 白色色阶 → 黑色色阶。
 * 这**不是**从亮到暗的数学顺序，而是「先定整体、再分别管两端」的暗房习惯：
 * 先高光后阴影，因为压高光是比提阴影更常见的动作（天空总是先爆）。
 * 随机排序会让用户在滑块之间来回找，这是调色界面最容易踩的坑。
 *
 * ⚠️ 四个滑块的正方向**统一为「往亮推」**（与 Lightroom 一致，见 [EditParams] 的类文档）。
 */
@Composable
private fun ToneParams(
    params: EditParams,
    tab: ToneTab,
    onParamChange: (EditParams) -> Unit,
    onCommit: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    when (tab) {
        ToneTab.WhiteBalance -> {
            GroupLabel("白平衡")
            EditSlider("色温", params.temperature, -1f, 1f, 0.05f, { p, v -> p.copy(temperature = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
            EditSlider("色调", params.tint, -1f, 1f, 0.05f, { p, v -> p.copy(tint = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
            Hint("色温正 = 偏暖（增红减蓝）；色调正 = 偏品红。RAW 上这两项改的是线性增益，不会像 JPEG 那样一拉就出色阶断层。")
        }

        ToneTab.Tone -> {
            GroupLabel("影调")
            EditSlider("曝光", params.exposureEv, -2f, 2f, 0.1f, { p, v -> p.copy(exposureEv = v) }, params, onParamChange, onCommit, onDraggingChange, format = EV)
            EditSlider("对比度", params.contrast, -1f, 1f, 0.05f, { p, v -> p.copy(contrast = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
            EditSlider("高光", params.highlights, -1f, 1f, 0.05f, { p, v -> p.copy(highlights = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
            EditSlider("阴影", params.shadows, -1f, 1f, 0.05f, { p, v -> p.copy(shadows = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
            EditSlider("白色色阶", params.whites, -1f, 1f, 0.05f, { p, v -> p.copy(whites = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
            EditSlider("黑色色阶", params.blacks, -1f, 1f, 0.05f, { p, v -> p.copy(blacks = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)

            GroupLabel("高光找回")
            EditSlider("强度", params.highlightRecovery, 0f, 1f, 0.05f, { p, v -> p.copy(highlightRecovery = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
            Hint("四个影调滑块的正方向一律是「往亮推」（Lightroom 口径）。高光找回把线性域的压肩起点往下移：被曝光推到白点以上的云层只有它救得回来，其余滑块都只能压「已经存在」的亮度。")
        }

        ToneTab.Preference -> {
            GroupLabel("偏好")
            EditSlider("自然饱和度", params.vibrance, -1f, 1f, 0.05f, { p, v -> p.copy(vibrance = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
            EditSlider("饱和度", params.saturation, -1f, 1f, 0.05f, { p, v -> p.copy(saturation = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
            EditSlider("去朦胧", params.dehaze, -1f, 1f, 0.05f, { p, v -> p.copy(dehaze = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
            Hint("自然饱和度按「这块颜色已经有多艳」反比加权：已经很艳的地方几乎不动，发灰的地方先亮起来 —— 护肤色靠的就是它。去朦胧是黑点 + 拉伸的一阶近似（完整版需要邻域估计）。")
        }
    }
}

/** 「颜色」分类：混色 / 分级 / LUT。 */
@Composable
private fun ColorParams(
    params: EditParams,
    selection: PanelSelection,
    onSelectionChange: (PanelSelection) -> Unit,
    onParamChange: (EditParams) -> Unit,
    onCommit: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    when (selection.colorTab) {
        ColorTab.Mix -> HslGroup(
            params, selection.hslBand,
            { onSelectionChange(selection.copy(hslBand = it)) },
            onParamChange, onCommit, onDraggingChange
        )

        ColorTab.Grading -> GradingGroup(
            params, selection.gradeZone,
            { onSelectionChange(selection.copy(gradeZone = it)) },
            onParamChange, onCommit, onDraggingChange
        )

        ColorTab.Lut -> LutGroup(params, onParamChange, onCommit, onDraggingChange)
    }
}

/**
 * HSL 混色：8 个色相通道 × (色相 / 饱和度 / 明度)。
 *
 * 「先选通道、再调三项」而不是把 24 个滑块平铺 —— 后者正是这个面板要避免的形态。
 * 通道用**色块**当选项：让用户在「红 / 橙 / 黄」三个字里挑，远不如直接把颜色摆出来。
 */
@Composable
private fun HslGroup(
    params: EditParams,
    bandIndex: Int,
    onBandChange: (Int) -> Unit,
    onParamChange: (EditParams) -> Unit,
    onCommit: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    val i = bandIndex.coerceIn(0, HslBands.COUNT - 1)
    val hsl = params.hsl

    GroupHeader(
        text = "混色通道",
        showReset = hsl.hueOf(i) != 0f || hsl.satOf(i) != 0f || hsl.lumOf(i) != 0f,
        onReset = {
            onParamChange(params.copy(hsl = hsl.withHue(i, 0f).withSat(i, 0f).withLum(i, 0f)))
            onCommit()
        }
    )
    GlassChipRow(
        items = (0 until HslBands.COUNT).toList(),
        selected = i,
        label = { HslBands.LABELS[it] },
        swatch = { HUE_SWATCHES[it] },
        onSelect = onBandChange
    )
    EditSlider("色相偏移", hsl.hueOf(i), -1f, 1f, 0.05f, { p, v -> p.copy(hsl = hsl.withHue(i, v)) }, params, onParamChange, onCommit, onDraggingChange, format = HUE_DEG)
    EditSlider("饱和度", hsl.satOf(i), -1f, 1f, 0.05f, { p, v -> p.copy(hsl = hsl.withSat(i, v)) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
    EditSlider("明度", hsl.lumOf(i), -1f, 1f, 0.05f, { p, v -> p.copy(hsl = hsl.withLum(i, v)) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
    Hint("八个通道各自覆盖一段色相，相邻通道之间平滑过渡（不是硬切），所以渐变天空和肤色边缘都不会出块。色相偏移满量程只有 ±30° —— 再大就会把橙色肤色转成绿色，那是「看起来像坏了」而不是「效果很猛」。")
}

/**
 * 彩色分级（= 美图秀秀的「色调分离」）：4 个亮度分区各自染一个色 + 抬/压明度。
 *
 * 分区、色相各一行 chip，然后固定 4 个滑块 —— 切分区时**滑块的位置不变**，
 * 只有数值在换，这是「同一组控件反复用」而不是「每个分区一套控件」。
 */
@Composable
private fun GradingGroup(
    params: EditParams,
    zoneIndex: Int,
    onZoneChange: (Int) -> Unit,
    onParamChange: (EditParams) -> Unit,
    onCommit: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    val z = zoneIndex.coerceIn(0, ColorGrading.ZONE_COUNT - 1)
    val g = params.grading
    val band = g.band(z)

    GroupHeader(
        text = "分区",
        showReset = !band.isNeutral,
        onReset = {
            onParamChange(params.copy(grading = g.withBand(z, GradingBand())))
            onCommit()
        }
    )
    GlassChipRow(
        items = (0 until ColorGrading.ZONE_COUNT).toList(),
        selected = z,
        label = { ColorGrading.ZONE_LABELS[it] },
        onSelect = onZoneChange
    )
    GlassChipRow(
        items = (0 until HslBands.COUNT).toList(),
        // 色相永远显示「最接近的那一格」：参数里存的是任意角度的 hue，chip 行只有 8 个离散入口，
        // 显示最近格是唯一诚实的做法（选一格就会把 hue 吸附到该格的中心角）。
        selected = nearestHueIndex(band.hue),
        label = { HslBands.LABELS[it] },
        swatch = { HUE_SWATCHES[it] },
        onSelect = {
            onParamChange(params.copy(grading = g.withBand(z, band.copy(hue = HslBands.CENTERS[it] / 360f))))
            onCommit()
        }
    )
    EditSlider("着色强度", band.sat, 0f, 1f, 0.05f, { p, v -> p.copy(grading = g.withBand(z, band.copy(sat = v))) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
    EditSlider("明度", band.lum, -1f, 1f, 0.05f, { p, v -> p.copy(grading = g.withBand(z, band.copy(lum = v))) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)

    GroupHeader(
        text = "分区过渡",
        showReset = g.blending != 0.5f || g.balance != 0f,
        onReset = {
            onParamChange(params.copy(grading = g.copy(blending = 0.5f, balance = 0f)))
            onCommit()
        }
    )
    EditSlider("过渡锐度", g.blending, 0f, 1f, 0.05f, { p, v -> p.copy(grading = g.copy(blending = v)) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
    EditSlider("平衡", g.balance, -1f, 1f, 0.05f, { p, v -> p.copy(grading = g.copy(balance = v)) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
    Hint("一个分区 = 一档亮度范围，给它染一个颜色并抬/压明度。着色向量已减去自身亮度，所以中性灰不会被连带提亮 —— 这正是「电影感」的常见来源：阴影压青、高光偏橙。")
}

/** 内置 LUT（由一级分类降为「颜色」的二级，见 [EditorCategory]）。 */
@Composable
private fun LutGroup(
    params: EditParams,
    onParamChange: (EditParams) -> Unit,
    onCommit: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    GroupLabel("滤镜")
    GlassChipRow(
        items = LUT_OPTIONS,
        selected = LUT_OPTIONS.firstOrNull { it.first == params.lutId },
        label = { it.second },
        onSelect = { (id, _) ->
            onParamChange(params.copy(lutId = id))
            onCommit()
        }
    )
    EditSlider("滤镜强度", params.lutIntensity, 0f, 1f, 0.05f, { p, v -> p.copy(lutIntensity = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
    Hint("内置滤镜全部是 MIT / CC 授权，可随包分发。强度 0% 等同关闭，方便对照效果。")
}

/**
 * 曲线分类：亮度曲线 + 红/绿/蓝三条分通道曲线，形态完全一致，只有作用对象不同。
 *
 * 三条分通道曲线**复用**同一套三锚点模型与同一个 [ColorMath.buildLumaLut]：
 * 「亮度曲线按比值整体缩放（保色相）」与「分通道曲线直接改该通道响应」是两件事，
 * 但它们的**参数形态**一模一样，所以控件也一模一样 —— 零新控件，见 `docs/TONING_DESIGN.md` §2.3.3。
 */
@Composable
private fun CurveParams(
    params: EditParams,
    tab: CurveTab,
    onParamChange: (EditParams) -> Unit,
    onCommit: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    val points = when (tab) {
        CurveTab.Luma -> params.lumaPoints
        CurveTab.Red -> params.redPoints
        CurveTab.Green -> params.greenPoints
        CurveTab.Blue -> params.bluePoints
    }
    val title = when (tab) {
        CurveTab.Luma -> "亮度曲线"
        CurveTab.Red -> "红通道曲线"
        CurveTab.Green -> "绿通道曲线"
        CurveTab.Blue -> "蓝通道曲线"
    }
    val setPoints: (List<Pair<Int, Int>>) -> EditParams = when (tab) {
        CurveTab.Luma -> { pts -> params.copy(lumaPoints = pts) }
        CurveTab.Red -> { pts -> params.copy(redPoints = pts) }
        CurveTab.Green -> { pts -> params.copy(greenPoints = pts) }
        CurveTab.Blue -> { pts -> params.copy(bluePoints = pts) }
    }
    val black = ToneCurve.black(points)
    val mid = ToneCurve.mid(points)
    val white = ToneCurve.white(points)

    GroupHeader(
        text = title,
        showReset = !ToneCurve.isIdentity(points),
        onReset = {
            onParamChange(setPoints(ToneCurve.IDENTITY))
            onCommit()
        }
    )
    EditSlider("黑场", black.toFloat(), ToneCurve.BLACK_RANGE.start, ToneCurve.BLACK_RANGE.endInclusive, 1f, { _, v -> setPoints(ToneCurve.points(v.toInt(), mid, white)) }, params, onParamChange, onCommit, onDraggingChange, format = BYTE)
    EditSlider("中间调", mid.toFloat(), ToneCurve.MID_RANGE.start, ToneCurve.MID_RANGE.endInclusive, 1f, { _, v -> setPoints(ToneCurve.points(black, v.toInt(), white)) }, params, onParamChange, onCommit, onDraggingChange, format = BYTE)
    EditSlider("白场", white.toFloat(), ToneCurve.WHITE_RANGE.start, ToneCurve.WHITE_RANGE.endInclusive, 1f, { _, v -> setPoints(ToneCurve.points(black, mid, v.toInt())) }, params, onParamChange, onCommit, onDraggingChange, format = BYTE)
    Hint(
        if (tab == CurveTab.Luma) {
            "亮度曲线按「曲线前后亮度之比」整体缩放，所以只改明暗、不动色相。三个锚点与预设、以及将来的 JSON 参数栈一一对应。"
        } else {
            "分通道曲线直接改该通道的响应：抬红通道的黑场 = 给暗部加红（去青），压蓝通道的白场 = 给高光去蓝（加黄）。它是查色偏最直接的一把尺子。"
        }
    )
}

/**
 * 效果分类（批次 3）：暗角 / 颗粒。
 *
 * ## 为什么这两个是同一个一级分类
 *
 * 它们共享的是**实现前提**而不是观感：都需要像素坐标（`docs/TONING_DESIGN.md` §2.4），
 * 也都是「调色做完之后往画面上加质感」的收尾动作。合成一类，用户想「给照片加点味道」时
 * 只需要看一个地方。
 *
 * ## 两组都做成了「一组滑块 + 一个重置」
 *
 * 而不是「每个参数一个分组」：暗角那 4 项是**同一个几何**的四个旋钮（形状、范围、软硬、强度），
 * 拆成四段只会让面板变长、让用户以为它们是四件独立的事。这与 HSL 混色 / 彩色分级的做法一致
 * （那里是「先选通道、再调三项」，这里是「一个对象、四个旋钮」）。
 *
 * ## ⚠️ 重置用的「默认值」是与 [EditParams] 的字面量对齐的
 *
 * 与 [GradingGroup] 里 `blending = 0.5f` 的做法相同。改模型里的默认值时，
 * **这两个地方必须一起改** —— 否则「重置」会把参数恢复到一个既不是旧默认、也不是新默认的值。
 */
@Composable
private fun EffectParams(
    params: EditParams,
    tab: EffectTab,
    onParamChange: (EditParams) -> Unit,
    onCommit: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    when (tab) {
        EffectTab.Vignette -> {
            GroupHeader(
                text = "暗角",
                showReset = params.vignetteAmount != 0f ||
                    params.vignetteMidpoint != 0.5f ||
                    params.vignetteFeather != 0.5f ||
                    params.vignetteRoundness != 0f,
                onReset = {
                    onParamChange(
                        params.copy(
                            vignetteAmount = 0f, vignetteMidpoint = 0.5f,
                            vignetteFeather = 0.5f, vignetteRoundness = 0f
                        )
                    )
                    onCommit()
                }
            )
            EditSlider("强度", params.vignetteAmount, -1f, 1f, 0.05f, { p, v -> p.copy(vignetteAmount = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
            EditSlider("起点", params.vignetteMidpoint, 0f, 1f, 0.05f, { p, v -> p.copy(vignetteMidpoint = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
            EditSlider("过渡", params.vignetteFeather, 0f, 1f, 0.05f, { p, v -> p.copy(vignetteFeather = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
            EditSlider("圆度", params.vignetteRoundness, -1f, 1f, 0.05f, { p, v -> p.copy(vignetteRoundness = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
            Hint("正 = 提亮四角（负 = 压暗），与四个影调滑块同一条符号约定。几何量按长边归一化 ⇒ 预览与导出的暗角范围完全一致；画面中心永远不动。")
        }

        EffectTab.Grain -> {
            GroupHeader(
                text = "颗粒",
                showReset = params.grainAmount != 0f ||
                    params.grainSize != 0.35f ||
                    params.grainRoughness != 0.5f,
                onReset = {
                    onParamChange(
                        params.copy(grainAmount = 0f, grainSize = 0.35f, grainRoughness = 0.5f)
                    )
                    onCommit()
                }
            )
            EditSlider("强度", params.grainAmount, 0f, 1f, 0.05f, { p, v -> p.copy(grainAmount = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
            EditSlider("尺寸", params.grainSize, 0f, 1f, 0.05f, { p, v -> p.copy(grainSize = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
            EditSlider("对比", params.grainRoughness, 0f, 1f, 0.05f, { p, v -> p.copy(grainRoughness = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
            Hint("亮度噪声（三通道同加），所以不会出现高 ISO 那种彩噪。尺寸按长边归一化 ⇒ 预览与导出的颗粒粗细一致；噪声是确定性哈希，拖动滑块时画面不会「沸腾」。")
        }
    }
}

/**
 * 细节分类（批次 4）：锐化 / 降噪 / 质感。
 *
 * ## 10 个参数为什么值一个独立的一级分类
 *
 * 它们是**邻域**算子（`DetailPass`）：输出的像素要问「你周围一圈长什么样」。
 * 这在界面上表现为**响应性质不同** —— 逐像素参数拖到哪儿就是哪儿，而邻域参数的实际量感
 * 还取决于画面自身的纹理密度（同一档锐化，拍羽毛和拍天空的效果完全不同）。
 * 所以这组参数必须离预览最近，且一次只显示一组。
 *
 * ## 三个二级分组各自守一个承诺
 *
 * - **锐化**：只加「细于 rFine」的高频，且有边缘蒙版 ⇒ 想锐哪儿锐哪儿，不把高 ISO 噪声一起提亮；
 * - **降噪**：唯一**往外抹**的一组，且**亮度 / 色度两条通路独立** —— 只拉色度就能压掉彩噪
 *   而几乎不糊细节，这是「要不要降噪」这个问题最实用的答案；
 * - **质感**：清晰度与纹理都作用在**中频**（`docs/TONING_DESIGN.md` §12），
 *   一个是让画面更立体、一个是让画面更耐看。
 *
 * ## ⚠️ 半径 / 细节 / 蒙版 / 保护都是**修饰量**，注意「强度」永远排第一位
 *
 * 在对应强度为 0 时，改这些滑块**不会改变任何一个像素**（见 `DetailPass.isNeutral`）。
 * 这不是缺陷而是唯一合理的语义：不知道「锐多少」时谈「锐多细」没有意义。所以每一组都把
 * 强度放第一位、修饰量放后面 —— 顺序本身在告诉用户「先动哪个」。
 *
 * ## 重置用的默认值必须与 [EditParams] 的字面量对齐
 *
 * 与 [GradingGroup]（`blending = 0.5f`）、[EffectParams] 同一约定：改模型默认值时这几个 `copy`
 * 要一起改，否则「重置」会回到一个既不是旧默认、也不是新默认的值。
 */
@Composable
private fun DetailParams(
    params: EditParams,
    tab: DetailTab,
    onParamChange: (EditParams) -> Unit,
    onCommit: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    when (tab) {
        DetailTab.Sharpen -> {
            GroupHeader(
                text = "锐化",
                showReset = params.sharpenAmount != 0f ||
                    params.sharpenRadius != 1f ||
                    params.sharpenDetail != 0.5f ||
                    params.sharpenMasking != 0f,
                onReset = {
                    onParamChange(
                        params.copy(
                            sharpenAmount = 0f, sharpenRadius = 1f,
                            sharpenDetail = 0.5f, sharpenMasking = 0f
                        )
                    )
                    onCommit()
                }
            )
            EditSlider("强度", params.sharpenAmount, 0f, 1f, 0.05f, { p, v -> p.copy(sharpenAmount = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
            EditSlider("半径", params.sharpenRadius, 0.5f, 3f, 0.1f, { p, v -> p.copy(sharpenRadius = v) }, params, onParamChange, onCommit, onDraggingChange)
            EditSlider("细节", params.sharpenDetail, 0f, 1f, 0.05f, { p, v -> p.copy(sharpenDetail = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
            EditSlider("蒙版", params.sharpenMasking, 0f, 1f, 0.05f, { p, v -> p.copy(sharpenMasking = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
            Hint("半径按长边等比 ⇒ 预览与导出的锐度一致。蒙版拉高后只在有边缘处锐化（保住皮肤和天空）；「细节」把中频一并纳入，适合建筑与织物。")
        }

        DetailTab.Noise -> {
            GroupHeader(
                text = "降噪",
                showReset = params.nrLuminance != 0f || params.nrLuminanceDetail != 0.5f ||
                    params.nrColor != 0f || params.nrColorDetail != 0.5f,
                onReset = {
                    onParamChange(
                        params.copy(
                            nrLuminance = 0f, nrLuminanceDetail = 0.5f,
                            nrColor = 0f, nrColorDetail = 0.5f
                        )
                    )
                    onCommit()
                }
            )
            EditSlider("亮度", params.nrLuminance, 0f, 1f, 0.05f, { p, v -> p.copy(nrLuminance = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
            EditSlider("亮度细节", params.nrLuminanceDetail, 0f, 1f, 0.05f, { p, v -> p.copy(nrLuminanceDetail = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
            EditSlider("色度", params.nrColor, 0f, 1f, 0.05f, { p, v -> p.copy(nrColor = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
            EditSlider("色度细节", params.nrColorDetail, 0f, 1f, 0.05f, { p, v -> p.copy(nrColorDetail = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT_U)
            Hint("亮度降噪会把细节一起抹平，所以把「细节」拉到 100% 时它完全不生效 —— 想保住细节就别动亮度这一路，只提色度：色度噪声是大块彩斑，单压它几乎不伤画面。")
        }

        DetailTab.Presence -> {
            GroupHeader(
                text = "质感",
                showReset = params.clarity != 0f || params.texture != 0f,
                onReset = {
                    onParamChange(params.copy(clarity = 0f, texture = 0f))
                    onCommit()
                }
            )
            EditSlider("清晰度", params.clarity, -1f, 1f, 0.05f, { p, v -> p.copy(clarity = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
            EditSlider("纹理", params.texture, -1f, 1f, 0.05f, { p, v -> p.copy(texture = v) }, params, onParamChange, onCommit, onDraggingChange, format = PERCENT)
            Hint("清晰度动的是大范围明暗过渡（让主体更立体），纹理动的是细密的重复结构。两者都避开了边缘 ⇒ 不会像锐化过度那样出白边；负值可以做出柔焦感。")
        }
    }
}

/**
 * 预设（`docs/UI_DESIGN.md` §1.5 的 **A 档**：卡片带**真实缩略图**，不再是纯文字 chip）。
 *
 * 缩略图由上层按**原图**渲染（每个预设一张，只在换图时算一次）—— 所以这里的职责只是「怎么摆」，
 * 不含任何渲染逻辑。`presetThumbs` 缺失时 [PresetThumbRow] 会显示占位底色，不会崩也不会空白。
 */
@Composable
private fun PresetParams(
    presets: List<Preset>,
    activePresetId: String,
    presetThumbs: Map<String, Bitmap>,
    onPreset: (Preset) -> Unit
) {
    GroupLabel("预设（参数栈）")
    PresetThumbRow(
        items = presets,
        selected = presets.firstOrNull { it.id == activePresetId },
        label = { it.name },
        thumb = { presetThumbs[it.id] },
        onSelect = onPreset
    )
    Text(
        if (activePresetId == "none") {
            "缩略图按「当前照片」渲染，所以每个预设的效果是所见即所得。" +
                "选一个作为起点，再在其它分类里微调；手动改动任一参数后高亮会取消。"
        } else {
            "已套用预设；手动改动任一参数即视为「已偏离预设」，高亮取消。"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.s)
    )
}

// ———————————————————————————————————————————————————————————————
// 小工具
// ———————————————————————————————————————————————————————————————

/** 默认数值格式：两位小数。与 [ParamSlider] 的默认值一致。 */
private val DEFAULT_FORMAT: (Float) -> String = { "%.2f".format(it) }

/** −1..1 的滑块按**带符号**百分比显示（自然饱和度 +50% / 明度 −20%）。 */
private val PERCENT: (Float) -> String = { "%+.0f%%".format(it * 100f) }

/** 0..1 的滑块按无符号百分比显示（强度 80%）。带符号的「+80%」在这种量程上是噪音。 */
private val PERCENT_U: (Float) -> String = { "%.0f%%".format(it * 100f) }

/** 曝光：EV 是摄影里唯一有实际单位的量，写出来比「0.50」有用得多。 */
private val EV: (Float) -> String = { "%+.2f EV".format(it) }

/**
 * HSL 色相：显示成**真实旋转角**。
 *
 * 这是把「满量程 = ±30°」这个人为上限**变得可见**的唯一办法 ——
 * 否则用户只会看到滑块走到底，不知道到底转了多少度。
 */
private val HUE_DEG: (Float) -> String = { "%+.0f°".format(it * HslBands.HUE_SWING_DEG) }

/** 曲线锚点：整数 0..255。曲线是查表，小数位没有意义。 */
private val BYTE: (Float) -> String = { it.toInt().toString() }

/**
 * 色相环上的 8 个色块，与 [HslBands.CENTERS] 一一对应。
 *
 * 用 [ColorMath.hueToRgbUnit]（本项目自己、可 JVM 单测）而不是 Compose 的 `Color.hsv`：
 * 色块必须与引擎渲染出来的颜色**同源**，否则用户点了橙色却调出偏黄的效果，
 * 会以为是「点错了」。它是**文件级 `val`** ⇒ 全进程只算一次，之后每次重组只是读列表。
 */
private val HUE_SWATCHES: List<Color> = HslBands.CENTERS.map { deg ->
    val v = FloatArray(3)
    ColorMath.hueToRgbUnit(deg / 360f, v)
    Color(v[0], v[1], v[2])
}

/**
 * 色相（0..1）→ 色相环上**最接近**的 [HslBands] 通道下标。
 *
 * 环形距离：`|a − b|` 与 `360 − |a − b|` 取小，否则 350° 会被判成离 240°（蓝）最远而不是离 0°（红）。
 */
private fun nearestHueIndex(hue: Float): Int {
    val deg = (((hue % 1f) + 1f) % 1f) * 360f
    var best = 0
    var bestD = Float.MAX_VALUE
    for (i in HslBands.CENTERS.indices) {
        val raw = abs(deg - HslBands.CENTERS[i])
        val d = if (raw > 180f) 360f - raw else raw
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    return best
}

/**
 * 参数面板的滑块。把「(EditParams) -> EditParams」的写法收敛到一处，
 * 避免几十个滑块各写一遍 `params.copy(...)` 而在某一行写错字段。
 *
 * [apply] 收 `(EditParams, Float) -> EditParams`：`EditParams` 是**不可变** data class，
 * 只能靠 `copy(...)` 产出新值 —— 写成 `{ params.exposureEv = it }` 是编译不过的。
 *
 * @param format 数值显示格式。默认两位小数；区间有实际单位时（EV / 百分比 / 角度）显式传。
 */
@Composable
private fun EditSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    apply: (EditParams, Float) -> EditParams,
    params: EditParams,
    onParamChange: (EditParams) -> Unit,
    onCommit: () -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    format: (Float) -> String = DEFAULT_FORMAT
) {
    ParamSlider(
        label = label,
        value = value,
        valueRange = min..max,
        step = step,
        format = format,
        onValueChange = { onParamChange(apply(params, it)) },
        onValueChangeFinished = onCommit,
        onDraggingChange = onDraggingChange
    )
}

/**
 * 分组标题 + 右侧动作。动作按钮**只在 [showReset] 为真时出现** ——
 * 常显会给面板添一圈永远点不动的灰按钮，那正是「控件很多但都很吵」的观感来源。
 *
 * [actionLabel] 默认「重置」（绝大多数分组如此）。批次 5 新增它，是因为作用域层管理区需要
 * 「移除本层」—— 那个动作与「重置」是**同一处位置、同一副样子**（面板里的次要动作只有这一种形态），
 * 但文案必须不同：把「移除本层」写成「重置」会让人以为只是清参数，实际整层都没了。
 */
@Composable
private fun GroupHeader(
    text: String,
    showReset: Boolean,
    onReset: () -> Unit,
    actionLabel: String = "重置"
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GroupLabel(text)
        if (showReset) {
            TextButton(onClick = onReset) { Text(actionLabel) }
        }
    }
}

/**
 * 面板内的分组小标题（「影调」「色彩」「美型」…）。
 *
 * ## ⚠️ 2026-09-22 真机反馈修正：它原先与「数值标签」长得一模一样
 *
 * 原先用 `labelMedium` + `onSurfaceVariant` —— 而滑块右侧的**数值**用的也是
 * `labelMedium` + `onSurfaceVariant`。也就是说，面板里**唯一**表达层级的东西
 * 与最不重要的东西同规格，于是「影调 / 色彩」这些标题完全读不出来，
 * 整块面板被看成一坨平铺的控件。真机反馈的原话是「参数全部挤在一起，无法使用」。
 *
 * 现在改用 [SectionHeader]（`ui/components/GlassCard.kt`）的**同一套规格**：
 * `titleMedium` + `onSurface`。理由不是「大一点好看」，而是**同一个概念在全 App 只能有一套样式** ——
 * 设置页的分组标题走 `SectionHeader`，参数面板的分组标题没有理由另立一套。
 *
 * 代价是每个分组标题高 8dp（16sp/24 行高 vs 12sp/16），一个分类 2~5 个分组 ⇒ 最多 +40dp。
 * 这次面板同时多拿到约 135dp（见 `EditorScreen.SPLIT_DEFAULT`），净赚。
 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs)
    )
}

/**
 * 分组末尾的一句说明。**篇幅上限就是一句话** ——
 * 参数面板里的解释文字一旦超过两行，用户就不再读了，只会觉得「被挡了」。
 */
@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.s)
    )
}

/** 面板内的次要动作用文字按钮，不用实心按钮 —— 实心按钮会把视线从预览图抢走。 */
@Composable
private fun ClearButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}
