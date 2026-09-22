package com.hifn.pixelcake.ui.editor

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.core.decode.ExportFormat
import com.hifn.pixelcake.core.edit.EditParams
import com.hifn.pixelcake.core.edit.RetouchState
import com.hifn.pixelcake.core.edit.preset.Preset
import com.hifn.pixelcake.ui.components.CapsuleNote
import com.hifn.pixelcake.ui.components.GlassCard
import com.hifn.pixelcake.ui.theme.Motion
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing
import com.hifn.pixelcake.ui.theme.blurredBackdrop
import com.hifn.pixelcake.ui.theme.glassSurface
import com.hifn.pixelcake.ui.theme.pressScale
import com.hifn.pixelcake.ui.theme.rememberGlassTint
import kotlinx.coroutines.delay

/**
 * 顶栏高度。
 *
 * ## 为什么是 44dp，而不是 Material 标准的 56dp（真机反馈修正）
 *
 * 56dp 是「工具栏」的高度，它服务的是**有标题栏语义的页面**（`TopAppBar` 要用 56dp 装下
 * 标题 + 副标题 + 若干图标）。而这里只有一行文字动作，56dp 会让动作上下各空出 18dp ——
 * 真机反馈的原话是「topbar 过于明显，因为上下距离过大」：在深色工作台上它读起来像一条
 * **压住照片的带子**，而不是一层可以忽略的控制面。
 *
 * 取 44dp = [Spacing.controlHeight]（全 App 可点控件的最小高度），于是顶栏与一级工具条
 * **同高**，一上一下互相呼应；动作按钮 36dp 在里面上下各留 4dp，仍然按得住。
 *
 * ⚠️ 这里**不写死 44.dp，而是绑定 `Spacing.controlHeight`** —— 否则「顶栏与工具条同高」
 * 只是这一行注释里的一句愿望，任何人改了 `Spacing.controlHeight` 都会让它悄悄失真。
 */
private val TOP_BAR_HEIGHT = Spacing.controlHeight

/** 胶囊提示自动淡出前的停留时长。够读完一句话，又不至于长期糊在照片上。 */
private const val NOTE_VISIBLE_MS = 3200L

/**
 * 预览 / 参数区高度比的可拖拽范围。
 *
 * 下限保证参数面板至少装得下「一个分组标题 + 两三个滑块」，上限保证照片不被挤成一条缝 ——
 * 两端都不是「随便留点余量」。
 *
 * ⚠️ 下限从 0.28 放宽到 0.22（真机反馈修正）：0.28 时参数区在 6.8" 机型上只有约 260dp，
 * 连一个分组（标题 + 3 个滑块 ≈ 260dp）都装不满，用户表达成「无法使用」。
 * 放宽下限的代价只是「照片变小」，而那是用户主动拖出来的一次性选择 ——
 * 不像默认值那样必须照顾「第一眼观感」，所以可以放心让到底。
 */
private const val SPLIT_MIN = 0.22f
private const val SPLIT_MAX = 0.78f

/**
 * 预览区默认占比。
 *
 * ## 为什么从「面板固定 320dp」改成按比例
 *
 * 旧版给参数面板写死 `heightIn(max = 320.dp)`。在矮屏（或状态栏 / 导航栏吃掉较多高度的机型）上，
 * 固定 320dp + 工具条 44dp + 顶栏 44dp 会把预览挤到只剩一条缝。
 * **固定高度永远会在某块屏幕上错**，因为屏幕高度不是我们能控制的常量。
 *
 * ## 为什么默认值又从 0.62 降到 0.45（真机反馈：「参数全部挤在一起，无法使用」）
 *
 * 因为 **0.62 分给预览的高度里有大半是空气**。关键在 [fitContentRect]：照片是**按宽度贴合**的，
 * 3:2 横构图在 6.8" 机型上只需约 285dp 高，而 0.62 给了约 489dp —— **204dp 是空的**；
 * 与此同时参数区只有约 260dp，而一个分类的内容有 500dp（调色）/ 900dp（人像），**要滚三屏**。
 *
 * 也就是说 §4 那条「预览主体占比 ≥55%」从一开始就定错了口径：它假设预览区的每一 dp 都花在
 * 照片上，而贴合之后并不是。**预览区的合理高度是「装得下照片 + 一点余量」**，不是固定占比。
 * 取 0.45 后照片尺寸**完全不变**（横构图仍被宽度限制，355dp > 285dp），
 * 而参数区可视高度多出约 135dp（+50%）。
 *
 * ⚠️ 别把它当成「照片变小了」调回去：**先看照片实际尺寸有没有变**，那是唯一判据。
 * 竖构图（3:2 竖）确实会比 0.62 时小一些 —— 那类照片想铺满屏幕高度就必须牺牲参数区，
 * 交给分隔条（用户可拖）而不是默认值来承担。
 */
private const val SPLIT_DEFAULT = 0.45f

/**
 * 分隔条热区高度。
 *
 * 视觉上只是一根 4dp 的细线，但**拖拽热区必须远大于它** ——
 * 4dp 的热区在手指下几乎点不中（Material 的最小可点尺寸是 48dp，触屏上还有手指遮挡）。
 */
private val SPLIT_HANDLE_HEIGHT = 28.dp

/**
 * 静态模糊底图的不透明度。
 *
 * 刻意压得很低（0.26）：底图的作用是**给玻璃层提供可透出的色彩信息**，不是当壁纸。
 * 调高会让界面自身变彩色，与「照片是唯一彩色主体」冲突。
 */
private const val BACKDROP_ALPHA = 0.26f

/**
 * 编辑工作台（`docs/UI_DESIGN.md` §4）。
 *
 * ## 五段式布局
 *
 * ```
 * ┌─ 顶栏 44dp ── 返回 / 撤销 重做 重置 / 导出          ← 一次性动作
 * ├─ 预览区 weight(previewFraction) ── 照片 + 手势 + 胶囊提示
 * ├─ 分隔条 28dp ── 上下拖动即改「预览 : 工具区」比例     ← 用户可调（真机反馈新增）
 * ├─ 一级工具条 44dp ── 人像 调色 曲线 LUT 预设          ← 分类导航
 * └─ 参数区 weight(1-previewFraction) ── 当前分类的 3~7 个滑块 ← 高频动作
 * ```
 *
 * 与旧版（一条 485 行的长滚动 Column，把 30 个滑块和导出按钮平铺在一起）的区别：
 * **按使用频率分层**。一次性动作（导出）收进 Sheet，高频动作（滑块）留在手边，
 * 中频的「换一类参数」交给一级工具条。旧版把三者混在一起，用户每次都要在长列表里找位置。
 *
 * ## ⚠️ 高度分配必须按**比例**，不能写死 dp（真机反馈修正）
 *
 * 预览区与参数区是**一对**：谁多一分谁就少一分，而屏幕高度不是我们能控制的常量。
 * 曾经给面板写死 `heightIn(max = 320.dp)`，结果在矮屏/高状态栏机型上
 * 顶栏 56 + 工具条 44 + 面板 320 就把预览挤成一条缝（用户原话：「底部所有菜单栏
 * 都挤到了一起，完全无法使用」）。现在两者是**同一块空间的权重对**
 * （`weight(previewFraction)` / `weight(1 - previewFraction)`），比例由用户拖分隔条决定，
 * 任何屏幕高度下都不会出现「一方被挤没」。
 *
 * 权重对还有第二个好处：切分类时参数个数变化（人像 7 个滑块 vs 预设 1 行缩略图）
 * **不再让面板整体跳动** —— 面板高度是分配出来的，不是内容撑出来的。
 *
 * ## 拖动时隐藏非参数 UI（隐形式交互，§5）
 *
 * 任何滑块开始拖动 → 顶栏、分隔条、一级工具条一起淡出，只留照片和正在动的那个滑块。
 * 这是 Snapseed 的核心体验：**调参时画面不该被任何 UI 分心**。松手立刻恢复。
 *
 * ⚠️ **三处一律只降 `alpha`，绝不收高度**（真机反馈修正）。
 *
 * 曾经让顶栏走 [AnimatedVisibility]，理由是「顶栏在预览上方，把它收起来正好把高度让给预览」。
 * 这个推理漏了一件事：预览区的高度是**按权重分配**出来的（`weight(previewFraction)`），
 * 而分母是「扣掉顶栏与工具条之后剩下的那块空间」。顶栏一收 56dp，分母就大 56dp，
 * 照片随之放大一次；松手又缩回去 —— 于是**每一次拖滑块，画面都会跳两下**。
 * 它换来的收益是「拖动期间多 56dp 预览」，而那一刻根本没人在看构图，这笔买卖显然是亏的。
 *
 * 工具条从始至终只能做透明度（它若收高度，面板会整体下移 44dp，用户手指按着的滑块会在
 * 拖动中突然跑掉）。现在顶栏也与它对齐，这条理由就不再是「工具条的特例」，
 * 而是本页的统一口径：**拖动期间布局完全静止，只有透明度在变**。
 *
 * ⚠️ 但「淡出」不等于「不可点」：`alpha = 0` 只影响**绘制**，命中测试照旧 ——
 * 一张全透明的「重置」按钮仍然点得中。所以凡是靠 `alpha` 藏起来的子树，
 * 都必须挂上 [inertUnless]（见该函数）。
 *
 * ## 动效一律「按属性分派」（`Motion` 类文档）
 *
 * 本页所有淡入淡出都是**透明度**，所以走 `tween` + [Motion.durationFor]
 * （时长确定、且能被系统「移除动画」坍缩）。这一页曾经 5 处漏写 `durationFor`，
 * 系统关了动画 App 还在动 —— 已随本次改造修掉。
 *
 * ## 预览区手势（由 [retouchTool] 决定，语义与旧版完全一致）
 * - `"none"`   ：按住看原图（前后对比）；
 * - `"skin"`   ：拖动涂抹皮肤作用区，坐标归一化到 [0..1] 后经 [onBrushStroke] 上报；
 * - `"blemish"`：点击脏点，经 [onInpaintStroke] 上报。
 *
 * 坐标一律**归一化**再上报：预览尺寸与源图/导出分辨率都不是一回事（硬约定），
 * 任何一处直接传像素坐标都会在导出时错位。
 *
 * ⚠️ 归一化的基准是**图片内容矩形**（见 [fitContentRect]），而预览区现在正是按这个矩形定尺的 ——
 * 两者等价是**先定尺、再复用同一函数**的结果，不是巧合。曾经预览是「填满 + 居中留白」，
 * 而落点却按整块空间归一化，于是「涂不准 + 导出后位置也不对」（P0 级功能缺陷）。
 *
 * @param original 导入时解出的**预览位图**。它只用来给工作台铺一层模糊底图，**不参与对比** ——
 *   理由见 [compareBase]。
 * @param compareBase 「按住看原图」的**对比基准**（零编辑渲染图，由上层在首次渲染时留副本）。
 *
 *   ⚠️ 曾经直接拿 [original] 当基准，于是**一个参数都没改**时按住预览画面也会跳一下
 *   （用户原话：「还未完成 ARW 图片的任何修改，按住预览窗口，发现图片已有变动，
 *   主要是曝光和拉伸的问题」）。原因是两条路径根本不是同一个东西：
 *   `original` 是**解码器给的预览图**（sRGB、尺寸按解码器口径），而 `rendered` 是
 *   **走内部管线渲出来的**（RAW 走 16-bit 线性 → sRGB 编码，尺寸按线性源口径）——
 *   曝光曲线与长宽口径都可能不同，于是「原图」与「当前图」天然对不上。
 *   对比必须发生在**同一条管线的同一次渲染口径**下：零编辑渲染 vs 当前渲染，才有意义。
 *
 *   为 `null` 时退回 [original]（只为不崩，不代表语义正确）。
 * @param autoMaskNote 自动蒙版状态（模型/加速器）。以胶囊提示浮在预览上、数秒后淡出，
 *   **不再占参数面板空间** —— 它是系统状态说明，不是参数。真机验收时用它核对是否真走 GPU。
 * @param liquifyNote 液化锚点来源（人脸检测 / 蒙版质心）。同上，供真机验收核对。
 */
@Composable
fun EditorScreen(
    original: Bitmap,
    compareBase: Bitmap? = null,
    rendered: Bitmap?,
    renderVersion: Int,
    params: EditParams,
    retouch: RetouchState,
    retouchTool: String,
    brushRadius: Float,
    inpaintRadius: Float,
    inpaintCount: Int,
    autoMaskEnabled: Boolean,
    autoMaskNote: String,
    liquifyNote: String,
    presets: List<Preset>,
    activePresetId: String,
    // 预设 id → 缩略图（按**原图**渲染，见 `MainActivity.buildPresetThumbs`）。缺省即无缩略图。
    presetThumbs: Map<String, Bitmap> = emptyMap(),
    canUndo: Boolean,
    canRedo: Boolean,
    // 状态行：类别 + 文案一起传（见 [EditorStatus]）。**不要改回 String** ——
    // 下游需要按类别取色，用文案前缀判断会在改文案时静默改变逻辑（审计 M2）。
    status: EditorStatus,
    exporting: Boolean = false,
    exportFormat: ExportFormat = ExportFormat.JPEG,
    onParamChange: (EditParams) -> Unit,
    onParamCommit: () -> Unit = {},
    onRetouchChange: (RetouchState) -> Unit,
    onRetouchCommit: () -> Unit = {},
    onToolChange: (String) -> Unit,
    onBrushStroke: (Float, Float) -> Unit,
    onBrushRadiusChange: (Float) -> Unit,
    onInpaintStroke: (Float, Float) -> Unit,
    onInpaintRadiusChange: (Float) -> Unit,
    onClearMask: () -> Unit,
    onClearInpaint: () -> Unit,
    onAutoMaskChange: (Boolean) -> Unit,
    onPreset: (Preset) -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
    onExportFormatChange: (ExportFormat) -> Unit = {},
    onCancelExport: () -> Unit = {},
    onBack: () -> Unit
) {
    var showOriginal by remember { mutableStateOf(false) }
    // 一级工具条当前分类。**默认落在「人像」**：这是本 App 的主场景。
    var category by remember { mutableStateOf(EditorCategory.Portrait) }
    var showExport by remember { mutableStateOf(false) }
    // 是否有滑块正在被拖动（隐形式交互的开关）
    var dragging by remember { mutableStateOf(false) }

    // 预览区占「预览 + 参数区」这块空间的比例。SPLIT_DEFAULT 的取值理由见其 KDoc。
    var previewFraction by remember { mutableStateOf(SPLIT_DEFAULT) }
    // 外层 Column 的实测高度，用来把拖拽位移（px）换算成比例。
    // 实测而非常量：状态栏 / 导航栏 Insets 会让「屏幕高度」与实际可用高度相差几十 dp，
    // 用常量当分母会让拖拽手感在不同机型上不一致。
    var ownerHeightPx by remember { mutableStateOf(0) }
    val splitDragState = rememberDraggableState { delta ->
        if (ownerHeightPx > 0) {
            // delta > 0 = 手指向下 ⇒ 分隔条下移 ⇒ 上方预览变高。
            previewFraction = (previewFraction + delta / ownerHeightPx).coerceIn(SPLIT_MIN, SPLIT_MAX)
        }
    }

    // 对比基准：优先用上层给的**零编辑渲染图**（同一条管线、同一次口径），
    // 缺省才退回导入预览（语义上不严谨，但好过空指针）。
    val compareSource: Bitmap = compareBase ?: original

    // renderVersion 每次重渲自增，确保本可组合项重组并重绘当前(已被原位修改的)Bitmap。
    //
    // ⚠️ 必须用 `remember(...)` 钉住包装对象，不能裸调 `asImageBitmap()`：
    // `asImageBitmap()` 每次都 new 一个 `AndroidImageBitmap`，而本页在拖动期间每帧都会重组
    // （`chromeAlpha` 透明度动画 + 参数变化），裸调就等于**每帧给 `Image` 换一个新位图实例**，
    // `BitmapPainter` 随之每帧失效并重绘 —— 画面据此发闪/掉帧。
    // 包装对象只是「指向同一张 Bitmap 的壳」，不需要复制像素，所以钉住它是零成本的。
    // （`PresetThumbRow` 早就是这个口径，这里是把它对齐回来。）
    val display: ImageBitmap? = remember(renderVersion, showOriginal, compareSource, rendered) {
        if (showOriginal) compareSource.asImageBitmap() else rendered?.asImageBitmap()
    }

    // 顶栏收起 / 工具条淡出（两者做法不同，原因见类文档）
    val chromeAlpha by animateFloatAsState(
        targetValue = if (dragging) 0f else 1f,
        animationSpec = tween(Motion.durationFor(Motion.fast), easing = Motion.easingOut),
        label = "chromeAlpha"
    )

    // UI-5：静态模糊底图。用 `remember(original)` 钉住 —— 换图才重算一次，
    // 拖动滑块 / 滚动面板 / 切分类都**不重算**（这是「不做实时 backdrop 模糊」的落地方式）。
    val backdrop = remember(original) { blurredBackdrop(original) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 量一次整页高度：分隔条拖拽要把 px 位移换算成比例，分母只能是**实测**高度
            // （状态栏 / 导航栏 Insets 会让它与屏幕高度差几十 dp）。
            .onSizeChanged { ownerHeightPx = it.height }
            // 工作台底色必须由编辑页自己画：外层 `Surface` 位于 `PixelCakeWorkspaceTheme` **之外**，
            // 用的是普通主题的 surface（浅色模式下是浅灰）。不自己铺底就会出现
            // 「照片周围一圈浅色」——照片不再是唯一的彩色主体，这正是 §8 决策点 1 要避免的。
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                backdrop?.let { img ->
                    // dstSize 铺满整屏（非等比拉伸）。模糊底图是低频渐变，拉伸形变肉眼不可辨，
                    // 却省掉了 crop 的取整与偏移计算。
                    drawImage(
                        image = img,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        alpha = BACKDROP_ALPHA,
                        filterQuality = FilterQuality.Low
                    )
                }
            }
    ) {
        // ———— 1. 顶栏 ————
        //
        // ⚠️ 只降 alpha、**不收高度**。理由见类文档「拖动时隐藏非参数 UI」：
        // 顶栏高度参与预览区的权重分母，把它收掉会让照片在拖动中放大一次、松手再缩回去。
        // 代价是全透明时仍占 56dp —— 这 56dp 买的是「拖动期间布局完全静止」，值得。
        //
        // `inertUnless` 补上 `alpha` 管不到的那半：淡出后按钮仍在命中测试里，
        // 手指从预览区往上划过去就可能误触「重置」这类不可逆动作。
        //
        // ⚠️ 这里有个必须成立的不变量：**`dragging` 不能卡在 `true`**。
        // 真卡住的话，顶栏会「隐身且不可点」，而顶栏上的「←」是退出编辑器的**唯一出口**
        // —— 用户会被关在编辑器里。它在本设计下不可达，理由是一条链：
        // `dragging` 只由参数面板的滑块置位（`ParamSlider` 在 `onValueChangeFinished` 里必复位）；
        // 而拖动期间顶栏与工具条都不可点 ⇒ 分类切不了 ⇒ 面板内容不会中途被 `Crossfade` 换掉
        // ⇒ 不存在「滑块被移出组合、`onValueChangeFinished` 再也不会回来」的路径。
        // 改动这里的任何一处（例如让工具条在拖动期间仍可点）都会让这条链断掉，务必重新论证。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(chromeAlpha)
                .inertUnless(enabled = !dragging)
        ) {
            EditorTopBar(
                canUndo = canUndo,
                canRedo = canRedo,
                exporting = exporting,
                onBack = onBack,
                onUndo = onUndo,
                onRedo = onRedo,
                onReset = onReset,
                onExport = { showExport = true }
            )
        }

        // ———— 2. 预览区（主体）————
        //
        // ⚠️ 这里**不再给预览垫一块黑底**（真机反馈：「渲染窗口大小和图片大小不一致，有黑边」）。
        // 旧做法是「外层 Box 填满 weight + 照片 `ContentScale.Fit`」，两者的长宽比几乎不可能一致，
        // `Fit` 就必然在上下（或左右）留出黑边 —— 黑边不属于照片，用户读到的是「框和画没对齐」。
        // 现在改成：先用 [fitContentRect] 反算出照片真正要占的矩形（**与手势落点用的是同一个函数**），
        // 再让内层 Box **正好等于那个矩形** —— 预览框与照片尺寸一致，黑边从根上消失；
        // 顺带让「预览矩形 == 手势内容矩形」，两套坐标自然合一，不必各算一次、各错一次。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(previewFraction)
                .padding(horizontal = Spacing.m),
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current
            // `maxWidth` / `maxHeight` 是本组件拿到的**约束**，即外层分给预览的可用空间。
            val boxPx = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
            val imagePx = display?.let { IntSize(it.width, it.height) } ?: IntSize.Zero
            val fit = fitContentRect(boxPx, imagePx)

            if (display != null && fit.width > 0f && fit.height > 0f) {
                Image(
                    bitmap = display,
                    contentDescription = "编辑预览",
                    // contentScale 必须**显式**写出：手势归一化要靠它反算内容矩形（见 previewGestures）。
                    // 依赖默认值的话，一旦 Compose 改了默认值就会静默改变落点换算。
                    // 此处内层已按 Fit 的结果定尺，Fit 实际是恒等映射 —— 显式写出只为钉住语义。
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(
                            with(density) { fit.width.toDp() },
                            with(density) { fit.height.toDp() }
                        )
                        // 圆角改由**照片自己**承担：预览容器已经没有背景了，
                        // 再让容器去 clip 就等于「裁一块没有内容的圆角」。
                        //
                        // ⚠️ 取最小的 14dp（`Radius.chip`），**不能**沿用容器的 40dp（`Radius.shell`）。
                        // 真机反馈：「预览窗口圆角过大，丢失边缘细节」。
                        // 这是一条很容易搞反的规则 —— 圆角阶梯是给**容器**定的（大容器配大圆角），
                        // 而照片是**内容**：40dp 在 428dp 宽的照片上会啃掉四角一大块，
                        // 而照片四角常常正好是有用信息（天空、地面、人物肩线）。**内容一律取最小档。**
                        .clip(Radius.chip)
                        // 1px 极淡描边：照片现在是直接落在模糊底图上的，需要一个边界兜底 ——
                        // 否则浅色照片的边缘会与底图糊在一起，读起来像「没装进框里」。
                        // 取 `outlineVariant`（工作台主题里是 #33313A）：只兜底、不装饰，
                        // 与 §2.2「不画粗边框」是同一条规矩。
                        // ⚠️ 形状必须与上面的 `clip` 一致，否则描边会画在照片**已经被裁掉**的角上。
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.chip)
                        .previewGestures(
                            retouchTool = retouchTool,
                            // 预览矩形 == 内容矩形 ⇒ 直接把 Fit 的结果当基准传下去，
                            // 归一化于是退化为「除以照片尺寸」，与语义完全一致。
                            previewSize = IntSize(fit.width.toInt(), fit.height.toInt()),
                            contentSize = imagePx,
                            onBrushStroke = onBrushStroke,
                            onInpaintStroke = onInpaintStroke,
                            onPressChanged = { showOriginal = it }
                        )
                )
            } else {
                Text("渲染中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 系统状态说明浮在画面上、数秒后淡出（不占参数面板）
            PreviewNotes(autoMaskNote = autoMaskNote, liquifyNote = liquifyNote)
        }

        // ———— 3. 分隔条（用户可自由控制「预览 : 工具区」高度比）————
        //
        // 视觉上只有 4dp 的细线，热区却是 SPLIT_HANDLE_HEIGHT —— 理由见该常量。
        // 用 `draggable(orientation = Vertical)` 而不是 `pointerInput + detectDragGestures`：
        // 前者自带手势判定与多点处理，也不会和兄弟节点的点击/滚动互相抢事件。
        //
        // 它与参数滑块共用 `chromeAlpha`：拖动滑杆时整条工具区一起淡出，分隔条跟着淡出不会突兀，
        // 也避免「手指压着分隔条的同时它在半透明地闪」。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SPLIT_HANDLE_HEIGHT)
                .alpha(chromeAlpha)
                // 淡出时一并禁用拖拽：`alpha` 挡不住指针事件（见 [inertUnless]），
                // 而这是一条 28dp 高、横跨整屏的热区 —— 手指从预览区划到工具区的路上
                // 最容易误抓的就是它，抓到就会在调参途中把画面比例改掉。
                .draggable(
                    state = splitDragState,
                    orientation = Orientation.Vertical,
                    enabled = !dragging
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(Radius.pill)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
            )
        }

        // ———— 4. 一级工具条（与顶栏同口径：只淡出，不收高度 —— 收高度会让面板整体下移、
        // 把用户手指按着的那个滑块挪走）————
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // ⚠️ `inertUnless` 必须排在最前面：`pointerInput` 的命中区域是「它在这一串
                // modifier 中占的那块尺寸」，排在 `padding` 之后就只盖得住内边距以内的部分，
                // 四周留出一圈「看着已经隐身、其实还能点」的边框。
                .inertUnless(enabled = !dragging)
                .padding(horizontal = Spacing.page, vertical = Spacing.s)
                .alpha(chromeAlpha)
        ) {
            EditorToolbar(current = category, onSelect = { category = it })
        }

        // ———— 5. 参数区 ————
        // 面板**常驻**（不收起）：它是高频操作区，收起来等于每次调参都多一次点击。
        //
        // ## 卡片在 Crossfade **外面**
        //
        // `Crossfade` 在过渡期会**同时组合新旧两份内容**。若把 `GlassCard`（不透明容器）包在里面，
        // 两张卡各以 ~50% alpha 叠加，合成覆盖率只有 0.75
        // （0.75·`ContainerDark` + 0.25·`WorkspaceBg` ≈ 23.8 < 27）—— 深色工作台会透出来，
        // 面板先变暗再回亮，观感就是「闪一下」；同时两层文字互为鬼影。
        // 表面只该有一份、只让**内容**淡换，所以卡片提到 `Crossfade` 之外。
        //
        // ## 高度由**权重**分配，滚动条留在卡片**内部**
        //
        // 与预览区是一对权重（理由见类文档）。卡片本身是**固定尺寸的停靠面板**，动的是它里面的内容 ——
        // 这样「面板边界」不随内容多少漂移，切分类时也不会整块上下跳。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f - previewFraction)
                .padding(horizontal = Spacing.page, vertical = Spacing.s)
        ) {
            // contentPadding 传 0 并把内边距交给滚动层：卡片负责「边界与材质」，
            // 滚动层负责「内容与 Insets」，两者各管一件事。
            GlassCard(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.cardInner, vertical = Spacing.m)
                        // ⚠️ `navigationBarsPadding()` 必须落在 **verticalScroll 之内**（即放在末尾）：
                        // 编辑器不套 `AppShell`，拿不到它那层底部兜底；全屏 + edge-to-edge 下，
                        // 最后一个滑块会被系统导航条 / 手势条压住。放在滚动内容内 → 背景仍沉浸到屏幕底，
                        // 而内容能被滚到导航条之上，两者兼得。
                        .navigationBarsPadding()
                ) {
                    Crossfade(
                        targetState = category,
                        animationSpec = tween(Motion.durationFor(Motion.base), easing = Motion.easingOut),
                        label = "paramCategory"
                    ) { cat ->
                        ParamPanel(
                            category = cat,
                            params = params,
                            retouch = retouch,
                            retouchTool = retouchTool,
                            brushRadius = brushRadius,
                            inpaintRadius = inpaintRadius,
                            inpaintCount = inpaintCount,
                            autoMaskEnabled = autoMaskEnabled,
                            presets = presets,
                            activePresetId = activePresetId,
                            presetThumbs = presetThumbs,
                            onParamChange = onParamChange,
                            onParamCommit = onParamCommit,
                            onRetouchChange = onRetouchChange,
                            onRetouchCommit = onRetouchCommit,
                            onToolChange = onToolChange,
                            onBrushRadiusChange = onBrushRadiusChange,
                            onInpaintRadiusChange = onInpaintRadiusChange,
                            onClearMask = onClearMask,
                            onClearInpaint = onClearInpaint,
                            onAutoMaskChange = onAutoMaskChange,
                            onPreset = onPreset,
                            onDraggingChange = { dragging = it }
                        )
                    }
                }
            }
        }
    }

    if (showExport) {
        ExportSheet(
            exportFormat = exportFormat,
            onExportFormatChange = onExportFormatChange,
            exporting = exporting,
            status = status,
            onExport = onExport,
            onCancelExport = onCancelExport,
            onDismiss = { showExport = false }
        )
    }
}

// ———————————————————————————————————————————————————————————————
// 通用 Modifier
// ———————————————————————————————————————————————————————————————

/**
 * 「隐身即不可点」：`enabled` 为 `false` 时把整棵子树变成**哑的** —— 不再接收任何指针事件。
 *
 * ## 为什么需要它（这不是洁癖，是误触）
 *
 * 本页靠 `alpha` 淡出来隐藏顶栏 / 工具条（理由见 [EditorScreen] 的类文档）。但 `alpha`
 * 只作用于**绘制**阶段，与命中测试完全无关 —— 一张 `alpha = 0` 的按钮照样能被点中，
 * 而且用户**看不见自己在点什么**。顶栏上恰好放着「重置」（清空全部编辑）与「←」（退出编辑器）
 * 这类不可逆动作：手指从预览区往上划出去、在看不见的按钮位置抬手，就会中招。
 *
 * ## 为什么是「在 Initial 阶段消费」而不是「把 clickable 的 enabled 传 false」
 *
 * 逐个组件传 `enabled` 要改 [EditorTopBar] / [EditorToolbar] / [ExportPill] 三个签名，
 * 而且以后每加一个可点元素都得记得再传一次 —— 漏一个就又不隐身可点了。
 * 在**父节点**统一拦截则是「一次性、且对子树内容不可知」的：子树将来长成什么样都盖得住。
 *
 * Compose 的指针事件按 `Initial → Main → Final` 三趟分发，`Initial` 趟是**父节点先于子节点**
 * 拿到事件的。在 `Initial` 趟把变更 `consume()` 掉，子节点就再也收不到它 —— 这正是
 * 「整棵子树不吃事件」的语义。
 *
 * ## 边界：它只作用于自己那棵子树
 *
 * 指针事件的命中路径在**按下那一刻**就确定了，不会随着手指移动重新命中。所以调参时
 * 「手指按在参数面板的滑块上、然后滑过工具条」不会被打断 —— 工具条根本不在那条命中路径里。
 * （这也是本函数敢直接用「消费全部事件」这种粗暴做法的前提。）
 *
 * @param enabled `true` 表示正常放行（本函数什么都不做）；`false` 表示吞掉子树的所有指针事件。
 */
private fun Modifier.inertUnless(enabled: Boolean): Modifier = pointerInput(enabled) {
    if (enabled) return@pointerInput
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

// ———————————————————————————————————————————————————————————————
// 顶栏
// ———————————————————————————————————————————————————————————————

/**
 * 顶栏：`← 编辑 | 撤销 重做 重置 | 导出`。
 *
 * ## 为什么全是文字，没有图标
 *
 * 本项目没有依赖 `material-icons-extended`，为了 6 个图标把整个图标库打进包并不划算；
 * 而「撤销 / 重做 / 重置」写中文比图标更不容易认错（图标在不同 App 里语义漂移很大）。
 *
 * ## 为什么不能直接用 5 个 `TextButton`（真实约束，不是审美）
 *
 * M3 的 `TextButton` 自带 `defaultMinSize(minWidth = 58.dp)`，**收紧 `contentPadding` 也压不下去**。
 * 5 个按钮 = 290dp，加标题与边距在 360dp 宽的机型上必然溢出。所以：
 * - 「撤销 / 重做 / 重置」用手写的紧凑 ghost 按钮（[TopBarAction]，宽度按文字走，无 58dp 下限）；
 * - 只有「导出」承担视觉重量，做成**玻璃胶囊**（[ExportPill]）。
 *
 * 结果：一屏只有一个填充色块，主次一眼可辨，窄屏也不溢出。
 */
@Composable
private fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    exporting: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(TOP_BAR_HEIGHT)
            .padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TopBarAction(label = "←", onClick = onBack)
        Text("编辑", style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.weight(1f))

        TopBarAction(label = "撤销", enabled = canUndo, onClick = onUndo)
        TopBarAction(label = "重做", enabled = canRedo, onClick = onRedo)
        TopBarAction(label = "重置", onClick = onReset)

        Spacer(Modifier.width(Spacing.s))

        ExportPill(enabled = !exporting, exporting = exporting, onClick = onExport)
    }
}

/**
 * 顶栏的**次级**动作（撤销 / 重做 / 重置 / 返回）。
 *
 * ## 为什么不用 `TextButton`
 *
 * `TextButton` 的 `defaultMinSize(minWidth = 58.dp)` 会让 5 个动作在 360dp 宽的机型上溢出
 * （详见 [EditorTopBar]）。这里手写一个「只按文字宽度生长」的 ghost 按钮：
 * 无背景、无涟漪（与全 App 的 `pressScale` 同口径），高度固定 36dp 保证可点。
 *
 * 用 `clickable` 的 `enabled` 而不是手动改 alpha：禁用态由 Compose 一并处理语义
 * （无障碍读屏会正确读出 disabled），颜色只是它的视觉映射。
 */
@Composable
private fun TopBarAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(Radius.chip)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = Spacing.s),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)
        )
    }
}

/**
 * 「导出」——顶栏的**主操作**，做成玻璃胶囊。
 *
 * ## 为什么它曾经「很奇怪」（真机反馈修正）
 *
 * 它本来是 M3 的实心 `Button`：底色取 `primary`（过饱和）、圆角走 M3 默认 **4dp**、
 * 高度按默认。放在这条全是玻璃胶囊、圆角走 14/22/28/40 阶梯的顶栏里，它就是一块**外来物** ——
 * 用户的原话是「颜色、大小、位置、背景色、背景大小很奇怪，不协调」。
 *
 * 现在改为与 TabBar / 一级工具条 / 圆按钮**同一套玻璃材质 + 同一档胶囊圆角**，
 * 只用 `colorScheme.primary` 染**文字**表达主次。这样既保住主操作的地位，
 * 又不会让界面出现第二个彩色主体（§8 决策点 1：照片是唯一的彩色主体）。
 *
 * 「导出中」时按钮变灰而非消失：进度反馈由 Sheet 承担，顶栏这个按钮只需要
 * **不让人重复点**，所以禁用 + 文案变化就够了。
 */
@Composable
private fun ExportPill(
    enabled: Boolean,
    exporting: Boolean,
    onClick: () -> Unit
) {
    val tint = rememberGlassTint()
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            // 36dp：与次级动作（[TopBarAction]）同高，在 44dp 的顶栏里上下各留 4dp。
            // 顶栏降到 44dp 后它不能再是 40dp（只剩 2dp 余量，胶囊会显得「顶到栏边」）。
            // 主次之分靠**材质**（玻璃胶囊 vs 无背景 ghost）而不是靠高度差 ——
            // 一个栏里两种高度的控件，比两种材质更容易读成「没对齐」。
            .height(36.dp)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .alpha(if (enabled) 1f else 0.4f)
            .glassSurface(tint, Radius.pill)
            .padding(horizontal = Spacing.l),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (exporting) "导出中" else "导出",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ———————————————————————————————————————————————————————————————
// 预览区手势
// ———————————————————————————————————————————————————————————————

/**
 * 按 [ContentScale.Fit] 反算图片在给定空间内的**实际绘制矩形**。
 *
 * ## 这个函数现在有两个调用点，语义是同一个
 *
 * 1. **预览区定尺**：内层 `Box` 直接取这个矩形的宽高 ⇒ 预览框与照片尺寸完全一致，**黑边不存在**
 *    （真机反馈：「渲染窗口大小和图片大小不一致，有黑边」）；
 * 2. **手势落点换算**：指针坐标先减去矩形左上角、再除以矩形尺寸，得到相对照片的 [0..1] 坐标。
 *
 * 两处共用同一个函数是刻意的：一旦「预览尺寸」与「落点基准」各算各的，就迟早会出现
 * 「框对了但涂不准」或反过来的系统性偏移 —— 这种偏移目视几乎发现不了
 * （涂抹位置就在手指附近，看起来「差不多」），但**预览与导出共用同一套归一化坐标**，
 * 两条路径会一起错。
 *
 * ## 为什么必须减掉留白再归一化
 *
 * 照片与可用空间的长宽比几乎不可能一致（3:2 横构图 vs 竖屏），`Fit` 会等比缩放后**居中**放置。
 * 「直接除以整块空间尺寸」只在「照片恰好铺满」时成立 —— 横构图照片在竖屏上会把留白算进照片高度，
 * 落点系统性偏移（留白越高偏得越多）。
 *
 * @return 内容矩形；任一尺寸非法时返回 [Rect.Zero]（调用方据此跳过本次手势 / 显示占位）。
 *
 * 可见性为 `internal`（而非 `private`）**是为了让 JVM 单测能直接覆盖它** —— 落点偏移这种
 * 「看着对、其实系统性偏了」的缺陷靠目视验不出来，必须用数字钉死（`FitContentRectTest`）。
 */
internal fun fitContentRect(box: IntSize, image: IntSize): Rect {
    if (box.width <= 0 || box.height <= 0 || image.width <= 0 || image.height <= 0) return Rect.Zero
    val scale = minOf(
        box.width.toFloat() / image.width,
        box.height.toFloat() / image.height
    )
    val w = image.width * scale
    val h = image.height * scale
    val left = (box.width - w) / 2f
    val top = (box.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/**
 * 预览区的指针交互。抽成 [Modifier] 扩展是为了让 [EditorScreen] 的布局部分读起来是布局，
 * 而不是被三段 `pointerInput` 代码块淹没。
 *
 * 坐标一律先换算到**内容矩形**内的 [0..1] 再上报（换算依据见 [fitContentRect]）。
 *
 * ⚠️ `pointerInput` 的 key 必须带上 [previewSize] / [contentSize]：这两个参数是**普通值**
 * （不是 state 读取），首帧拿到的是 `IntSize.Zero`；不把它们作为 key 重启手势协程，
 * 归一化就会一直用首帧的零尺寸，涂抹落点会永远贴着角落。
 */
private fun Modifier.previewGestures(
    retouchTool: String,
    previewSize: IntSize,
    contentSize: IntSize,
    onBrushStroke: (Float, Float) -> Unit,
    onInpaintStroke: (Float, Float) -> Unit,
    onPressChanged: (Boolean) -> Unit
): Modifier = when (retouchTool) {
    "skin" -> pointerInput(previewSize, contentSize) {
        detectDragGestures { change, _ ->
            change.consume()
            val rect = fitContentRect(previewSize, contentSize)
            if (rect.width <= 0f || rect.height <= 0f) return@detectDragGestures
            // 拖动越界用 clamp（不能让手指划出画面就断笔），但基准是内容矩形而不是 Box。
            onBrushStroke(
                ((change.position.x - rect.left) / rect.width).coerceIn(0f, 1f),
                ((change.position.y - rect.top) / rect.height).coerceIn(0f, 1f)
            )
        }
    }

    "blemish" -> pointerInput(previewSize, contentSize) {
        detectTapGestures { offset ->
            val rect = fitContentRect(previewSize, contentSize)
            if (rect.width <= 0f || rect.height <= 0f) return@detectTapGestures
            // 点在黑边上时**不落点**：那里没有像素，硬 clamp 会在照片边缘凭空多一个祛斑点。
            if (offset.x < rect.left || offset.x > rect.right ||
                offset.y < rect.top || offset.y > rect.bottom
            ) {
                return@detectTapGestures
            }
            onInpaintStroke(
                ((offset.x - rect.left) / rect.width).coerceIn(0f, 1f),
                ((offset.y - rect.top) / rect.height).coerceIn(0f, 1f)
            )
        }
    }

    else -> pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                onPressChanged(true)
                awaitRelease()
                onPressChanged(false)
            }
        )
    }
}

// ———————————————————————————————————————————————————————————————
// 预览上的胶囊提示
// ———————————————————————————————————————————————————————————————

/**
 * 把系统状态说明浮在预览底部、数秒后淡出。
 *
 * 内容变化（重新推理完 / 重新检测完）时**重新计时**，所以 key 用两条 note 而不是 `Unit`：
 * 用户改一次参数就会产出新 note，应该再给他看一次，而不是「只显示第一次」。
 */
@Composable
private fun BoxScope.PreviewNotes(autoMaskNote: String, liquifyNote: String) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(autoMaskNote, liquifyNote) {
        if (autoMaskNote.isEmpty() && liquifyNote.isEmpty()) return@LaunchedEffect
        visible = true
        delay(NOTE_VISIBLE_MS)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.durationFor(Motion.base), easing = Motion.easingOut)),
        exit = fadeOut(tween(Motion.durationFor(Motion.slow), easing = Motion.easingIn)),
        modifier = Modifier.align(Alignment.BottomCenter).padding(Spacing.m)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            if (autoMaskNote.isNotEmpty()) CapsuleNote(autoMaskNote)
            if (liquifyNote.isNotEmpty()) CapsuleNote(liquifyNote)
        }
    }
}
