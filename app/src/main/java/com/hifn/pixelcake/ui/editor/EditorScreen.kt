package com.hifn.pixelcake.ui.editor

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hifn.pixelcake.core.decode.ExportFormat
import com.hifn.pixelcake.core.edit.EditParams
import com.hifn.pixelcake.core.edit.Histogram
import com.hifn.pixelcake.core.edit.ObjectLayer
import com.hifn.pixelcake.core.edit.ObjectScope
import com.hifn.pixelcake.core.edit.RetouchState
import com.hifn.pixelcake.core.edit.hasScope
import com.hifn.pixelcake.core.edit.isAdjusted
import com.hifn.pixelcake.core.edit.upsert
import com.hifn.pixelcake.core.edit.without
import com.hifn.pixelcake.core.edit.preset.Preset
import com.hifn.pixelcake.ui.components.CapsuleNote
import com.hifn.pixelcake.ui.components.LocalHudReporter
import com.hifn.pixelcake.ui.theme.Motion
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing
import com.hifn.pixelcake.ui.theme.blurredBackdrop
import com.hifn.pixelcake.ui.theme.glassSurface
import com.hifn.pixelcake.ui.theme.pressScale
import com.hifn.pixelcake.ui.theme.rememberGlassTint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 顶栏高度：48dp = [Spacing.controlHeight]，顶栏与 Rail 同高，一上一下互相呼应。
 */
private val TOP_BAR_HEIGHT = Spacing.controlHeight

/** 胶囊提示自动淡出前的停留时长。够读完一句话，又不至于长期糊在照片上。 */
private const val NOTE_VISIBLE_MS = 3200L

/** 中央 HUD 松手后的停留时长（之后 300ms 淡出 —— 规范 §03 bighud 口径）。 */
private const val HUD_DWELL_MS = 800L

/**
 * 控制台（Deck）高度两档（UI 3.0 One-Surface）。
 *
 * S = 「chips + 滑块 + 说明」的标准档；L = 预设网格 / 曲线编辑器 / 人像这类内容多的分类。
 * 两档之外**没有第三种状态**——面板不再能展开遮住大半屏，照片在任何时刻都可见。
 * 切档用空间弹簧（随用户切分类变化，属用户主动行为，不违反「拖动期布局静止」）。
 *
 * ## ⚠️ 与规范 168/330 的偏差账（根因已修，数值是「算出来的」—— §9.4-2 / §9.5）
 *
 * 根因：旧 ParamSlider 是「标签行 22 + Slider 48」两行式 ≈70dp/条，3 滑块组要 292dp——
 * 规范 S=168 连它自己的 CSS（紧凑 44dp/条 ≈ 214dp）都装不下。已把滑块紧凑化到 ≈46dp/条
 * （标签+数值同行 + Slider 关触控增强收 26dp，见 `ParamSlider`）：chips 36 + 3×46 + Hint 30
 * + padding 28 ≈ **232dp**，S=248 有真实余量；4 滑块组（≈278）仍走内容区滚动兜底。
 *
 * ## ⚠️ L 档已对齐规范的「任何时刻最多 330dp」
 *
 * 规范原文（`docs/UI3.0.html` §04 / §06）：「一表面 + 固定高控制台（任何时刻最多 **330dp**，
 * **永远看得见照片**）」。原先这里是 344dp（比规范多 14dp，照片可见面积被吃掉一截）⇒ 已改为 330。
 *
 * ## ⚠️ S 档 248 是**有意偏离**，原因与「规范说永不滚动」冲突
 *
 * 规范同段还要求「每组的控件数被硬性约束在一屏放得下，**超了就拆 chip 组**——
 * 复杂度被结构吃掉，而不是被滚动条藏起来」。本项目「磨皮」「美型」两组各有 **3 个滑块**，
 * 而 S=168 是按「chips + **2 滑块** + 提示」算的 ⇒ 要落到 168dp，前提是把这两组**拆成
 * 子组**（例如「磨皮」拆成「磨皮 / 肤色 / 质感」），而不是把 Deck 拉高 + 让内容滚动。
 * 本轮未做拆组（涉及 chip 命名与交互语义，属产品决策），故 S 档取 248dp 并保留滚动兜底。
 * ⇒ **待办**：拆组后 S 档可降到 ≈174dp，再把提示行收到 16dp 即与规范的 168dp 对齐，
 * 届时可去掉滚动兜底。详见 `docs/ENGINEERING_NOTES.md` §9.6。
 */
private val DECK_HEIGHT_S = 248.dp
private val DECK_HEIGHT_L = 330.dp

/**
 * Rail（工具栏）高度：规范标注「悬浮胶囊，高 58dp」（UI3.0 §04）。
 *
 * ⚠️ 用 `heightIn(min = ...)` 而不是 `height(...)`：内容实高在 fontScale 1.0 时约 57dp
 * （图标 21 + 间距 3 + 文字行高 16 + 内边距 9 + Rail 自身 8），刚好塞进 58dp；
 * 但 **fontScale 1.3** 时文字行高变 20.8dp ⇒ 内容 61.8dp —— 若写死 `height(58.dp)`
 * 就会**裁掉标签的下半截**（UI3.0 §6.1 明令「fontScale 1.0–1.3 全量适配，布局不裁切」）。
 * ⇒ 固定值只当**下限**，超出就让它长高。
 */
// 58dp = 规范标注值；其 CSS 自算 64px 的「规范自相矛盾」与取舍过程见 Rail.kt 的 RailItem 注释（§9.4-1）。
private val RAIL_HEIGHT = 58.dp

/** 预览缩放上限。8x 已接近预览位图（长边 2048）的像素极限，再放大只会看到马赛克。 */
private const val MAX_PREVIEW_ZOOM = 8f

/** 双击放大档位（再次双击复位）。取 2.5x：人像看皮肤细节的常用档。 */
private const val DOUBLE_TAP_ZOOM = 2.5f

/** 双击的两次按下间隔上限（毫秒）。 */
private const val DOUBLE_TAP_MS = 280L

/**
 * 静态模糊底图的不透明度。刻意压得很低（0.26）：
 * 底图的作用是给玻璃层提供可透出的色彩信息，不是当壁纸。
 */
private const val BACKDROP_ALPHA = 0.26f

/**
 * 编辑工作台 —— UI 3.0「Aperture」One-Surface 架构。
 *
 * ## 一个表面：从进入到导出只有一屏
 *
 * ```
 * ┌────────────────────────────┐
 * │ 顶栏（玻璃胶囊，悬浮）        │ ← 返回 撤销 重做 重置 对比 导出
 * │ 直方图条（可点收起）          │
 * │                            │
 * │      照片（全屏画布）        │ ← 缩放 / 平移 / 长按对比 / 涂抹
 * │   [中央 HUD：巨大读数]       │ ← 拖滑块时浮现
 * ├────────────────────────────┤
 * │ Deck 控制台（固定高 S/L）    │ ← 作用域 chips + 分组 chips + 当前组内容
 * │ Rail 工具栏（六工具）        │ ← 图标 + 文字，琥珀选中
 * └────────────────────────────┘
 * ```
 *
 * 与 v2.1 分屏（预览 45% / 参数 55% + 分隔条）的本质区别：**面板不再与照片分蛋糕**。
 * 照片铺满整个屏幕（deck / rail 悬浮其上），「放大看细节」与「调参」不再互抢空间；
 * 分隔条退役，缩放的 fit 补偿逻辑保留（面板高度变化不改照片显示尺寸）。
 *
 * ## 拖动时隐藏非参数 UI（隐形式交互）
 *
 * 滑块拖动 → 顶栏 / 直方图条 / Rail 一起只降 alpha（绝不收高度）；Deck 是参数本身，
 * 保持可点。中央 HUD（[LocalHudReporter] 上报的参数名 + 等宽读数）随之浮现。
 *
 * ⚠️ `dragging` 不能卡在 true：`ParamSlider` 的 onValueChangeFinished 复位 + 组合销毁时
 * `DisposableEffect` 兜底（M3 Slider 在节点销毁时不回调 finished —— 见该文件）。
 *
 * ## 照片是唯一彩色主体
 *
 * 工作台底色由编辑页自画（外层 Surface 在 `PixelCakeWorkspaceTheme` 之外），
 * 底图是导入时一次性生成的静态模糊（拖动 / 滚动 / 切分类都不重算）。
 *
 * ## 层预算（对设计稿 §6.2「≤4」的诚实修正）
 *
 * 常驻**面**有 5 块（照片 / 顶栏 / 直方图条 / Deck / Rail），但「预算」真正约束的是
 * **同屏垂直叠加**：任一像素上最多 3 层（照片 + 一块 chrome + 浮层）——
 * 直方图条与顶栏不同位、不同时叠在照片同一区域，且与顶栏同组材质 / 同组 alpha（一起淡出）。
 * 设计稿写「≤4」时没把直方图条算进去（它是 §04-B 自己要求的）—— 两处已按本段口径对齐。
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
    presetThumbs: Map<String, Bitmap> = emptyMap(),
    objectScopesAvailable: Boolean = false,
    layers: List<ObjectLayer> = emptyList(),
    canUndo: Boolean,
    canRedo: Boolean,
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
    onLayersChange: (List<ObjectLayer>) -> Unit,
    onLayersCommit: () -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
    onExportFormatChange: (ExportFormat) -> Unit = {},
    onCancelExport: () -> Unit = {},
    jpegQuality: Int = 92,
    onBack: () -> Unit
) {
    var showOriginal by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf(EditorCategory.Portrait) }
    // 作用域（批次 5）：`null` = 整图。选择态不进快照；key=original 防跨图残留。
    var activeScope by remember(original) { mutableStateOf<ObjectScope?>(null) }
    val scope: ObjectScope? = activeScope?.takeIf { layers.hasScope(it) }
    val activeLayer: ObjectLayer? = scope?.let { sc -> layers.firstOrNull { it.scope == sc } }
    val shownParams: EditParams = activeLayer?.params ?: params
    val writeParams: (EditParams) -> Unit = { np ->
        val layer = activeLayer
        if (layer == null) onParamChange(np) else onLayersChange(layers.upsert(layer.copy(params = np)))
    }
    val selectScope: (ObjectScope?) -> Unit = { sc ->
        activeScope = sc
        if (sc != null && !layers.hasScope(sc)) {
            onLayersChange(layers.upsert(ObjectLayer(sc)))
            onLayersCommit()
        }
    }
    var selection by remember { mutableStateOf(PanelSelection()) }
    var showExport by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    // —— 预览缩放 / 平移（双指捏合 ≤8x、双击 2.5x、单指平移；换图整体复位）——
    var previewZoom by remember(original) { mutableStateOf(1f) }
    var previewPan by remember(original) { mutableStateOf(Offset.Zero) }
    val lastFitWidth = remember(original) { mutableStateOf(0f) }

    // 全屏画布实测尺寸：状态栏 / 导航栏 Insets 会让「屏幕高度」与可用高度差几十 dp，
    // fit / 缩放补偿都必须用实测值当分母。
    var screenW by remember { mutableStateOf(0) }
    var ownerHeightPx by remember { mutableStateOf(0) }

    // 对比基准：优先用上层给的零编辑渲染图（同管线同口径），缺省退回导入预览。
    val compareSource: Bitmap = compareBase ?: original

    // renderVersion 每次重渲自增；remember 钉住包装对象（裸调 asImageBitmap 每帧 new
    // 会让 BitmapPainter 失效重绘 —— 拖动期发闪的根因）。
    val display: ImageBitmap? = remember(renderVersion, showOriginal, compareSource, rendered) {
        if (showOriginal) compareSource.asImageBitmap() else rendered?.asImageBitmap()
    }

    // ———— 分割对比（`UI3.0.html` §09：「把『之前』钉在画面上」，分割线 = 逐列对比）————
    //
    // 规范写明「两条渲染位图都已存在，纯 UI 裁剪」—— 所以这里**不碰渲染管线**，
    // 只是把「之前 / 之后」同时画一遍：先画之后铺满，再把之前裁到分割线左侧。
    // 与 [showOriginal] 的区别：那个是整屏切换（看不出一半一半的差别），这个是并置。
    //
    // ⚠️ 两张位图都必须 `remember` 钉住：裸调 `asImageBitmap()` 每帧 new 会让
    // `BitmapPainter` 失效重绘 —— 拖动分割线时正好每帧都在重组，那就是发闪的根因
    // （与上面 [display] 同一条纪律）。
    var splitCompare by remember { mutableStateOf(false) }
    var splitPos by remember { mutableStateOf(0.5f) }
    val beforeBmp = remember(compareSource) { compareSource.asImageBitmap() }
    val afterBmp = remember(rendered) { rendered?.asImageBitmap() }

    // 隐形式：目标 0.32f 的「幽灵残影」（规范 §04-D），而不是完全消失 —— 残影保住空间感，
    // 且 `inertUnless` 仍保证残影不可点。回场 240ms（规范口径）比隐去 150ms 慢半拍：
    // 消失要果断、回来要温柔。⚠️ 绑定的是「滑块拖动」；规范还绑定了「照片上横拖调参」，
    // 那条手势通道属 P3（见 UI3.0.html §09），届时两条输入共用这一个状态。
    val chromeAlpha by animateFloatAsState(
        targetValue = if (dragging) 0.32f else 1f,
        animationSpec = if (dragging) {
            tween(Motion.durationFor(Motion.fast), easing = Motion.easingOut)
        } else {
            tween(Motion.durationFor(Motion.base), easing = Motion.easingOut)
        },
        label = "chromeAlpha"
    )

    val backdrop = remember(original) { blurredBackdrop(original) }

    // —— 中央 HUD（UI 3.0）：滑块拖动时上报「参数名 + 格式化值」——
    // hud = 最近一次读数（松手**不清空**，供停留期继续显示）；hudActive = 手指还在拖。
    // 松手 → 停 800ms → hudShown=false 触发 300ms 淡出（规范 §03 bighud 口径）。
    // 800ms 内再次拖动 ⇒ hudActive 翻回 true，hudShown 保持，不闪断。
    var hud by remember { mutableStateOf<Pair<String, String>?>(null) }
    var hudActive by remember { mutableStateOf(false) }
    var hudShown by remember { mutableStateOf(false) }
    LaunchedEffect(hudActive) {
        if (hudActive) {
            hudShown = true
        } else if (hudShown) {
            delay(HUD_DWELL_MS)
            hudShown = false
        }
    }

    // —— 直方图数据：随每次渲染完成在后台重算（采样步长按像素数分档），
    // 顶部直方图条消费；读取失败（位图竞态兜底）时条不占位。
    val histogram by produceState<Triple<FloatArray, FloatArray, FloatArray>?>(null, renderVersion, rendered) {
        val bmp = rendered ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                val w = bmp.width
                val h = bmp.height
                if (w <= 0 || h <= 0) return@runCatching null
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                val n = px.size
                val r = ByteArray(n)
                val g = ByteArray(n)
                val b = ByteArray(n)
                for (i in 0 until n) {
                    val p = px[i]
                    r[i] = ((p shr 16) and 0xff).toByte()
                    g[i] = ((p shr 8) and 0xff).toByte()
                    b[i] = (p and 0xff).toByte()
                }
                val step = if (n > 1_500_000) 8 else 4
                val (br, bg, bb) = Histogram.computeRgb(r, g, b, n, step)
                Triple(Histogram.normalize(br), Histogram.normalize(bg), Histogram.normalize(bb))
            }.getOrNull()
        }
    }
    var histExpanded by remember { mutableStateOf(true) }

    // Deck 高度两档（S/L），切分类时空间弹簧形变。
    val deckExpanded = category == EditorCategory.Portrait ||
        category == EditorCategory.Curve || category == EditorCategory.Preset
    // ⚠️ 规范 §06（`docs/UI3.0.html`）：「fontScale 1.0–1.3 全量适配（布局不裁切）；
    // **控制台 L 档高度随缩放放宽（330→356dp）**」⇒ 高度必须跟着系统字号缩放走。
    // 330→356 是 **+7.9%**，不是线性放大 ⇒ 系数取「每 1.0 字号增量放宽 26%」。
    // 写死 `height(deckH)` 的后果是：fontScale 1.3 时滑块行高被压住、底部内容被裁。
    val fontScale = LocalDensity.current.fontScale
    val deckScale = 1f + (fontScale - 1f) * 0.26f
    val deckH by animateDpAsState(
        targetValue = (if (deckExpanded) DECK_HEIGHT_L else DECK_HEIGHT_S) * deckScale,
        animationSpec = Motion.springSpatial(),
        label = "deckHeight"
    )

    val hudSetter: (Pair<String, String>?) -> Unit = { p ->
        if (p == null) hudActive = false else { hud = p; hudActive = true }
    }

    CompositionLocalProvider(LocalHudReporter provides hudSetter) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { ownerHeightPx = it.height; screenW = it.width }
                .background(MaterialTheme.colorScheme.background)
                .drawBehind {
                    backdrop?.let { img ->
                        drawImage(
                            image = img,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                            alpha = BACKDROP_ALPHA,
                            filterQuality = FilterQuality.Low
                        )
                    }
                }
        ) {
            // ———— 1. 照片层（全屏画布）————
            //
            // 手势挂在这一整层：deck / rail / 顶栏在其上（后声明的兄弟在 z 轴之上），
            // 各自消费自己的指针事件；它们之间的空隙（Spacer）事件自然落到本层 ——
            // 这是「空白处照样捏合缩放」的结构性保证。
            val density = LocalDensity.current
            val boxPx = IntSize(screenW, ownerHeightPx)
            val imagePx = display?.let { IntSize(it.width, it.height) } ?: IntSize.Zero
            val fit = fitContentRect(boxPx, imagePx)

            // 分隔条已退役，但「面板高度变化不牵动已放大的图片」这条不变量仍在：
            // 框尺寸变化时按新旧 fit 之比反向补偿 zoom，绝对显示尺寸恒定。
            if (previewZoom > 1f && lastFitWidth.value > 0f && fit.width > 0f &&
                abs(fit.width - lastFitWidth.value) > 0.5f
            ) {
                previewZoom = ((previewZoom * lastFitWidth.value) / fit.width).coerceIn(1f, MAX_PREVIEW_ZOOM)
            }
            if (fit.width > 0f) lastFitWidth.value = fit.width

            val dispW = fit.width * previewZoom
            val dispH = fit.height * previewZoom
            val clampedPan = clampPreviewPan(
                previewPan, boxPx.width.toFloat(), boxPx.height.toFloat(), dispW, dispH
            )
            if (clampedPan != previewPan) previewPan = clampedPan

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .previewGestures(
                        retouchTool = retouchTool,
                        boxPx = boxPx,
                        contentSize = imagePx,
                        readZoom = { previewZoom },
                        readPan = { previewPan },
                        onTransform = { zoomFactor, panDelta ->
                            previewZoom = (previewZoom * zoomFactor).coerceIn(1f, MAX_PREVIEW_ZOOM)
                            previewPan += panDelta
                        },
                        onDoubleTapToggle = { tap ->
                            val f = fitContentRect(boxPx, imagePx)
                            if (f.width > 0f && f.height > 0f) {
                                val newZoom = if (previewZoom > 1.05f) 1f else DOUBLE_TAP_ZOOM
                                previewPan = panAnchorAtPoint(
                                    tap, boxPx.width.toFloat(), boxPx.height.toFloat(),
                                    f.width, f.height, previewZoom, previewPan, newZoom
                                )
                                previewZoom = newZoom
                            }
                        },
                        onBrushStroke = onBrushStroke,
                        onInpaintStroke = onInpaintStroke,
                        // 分割对比期间禁用「按住看原图」：那个动作会把 display 整屏切成「之前」，
                        // 而分割线右侧本该是「之后」—— 两者叠加的结果是两侧一模一样，读起来像功能坏了。
                        onPressChanged = { pressed -> if (!splitCompare) showOriginal = pressed }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (display != null && fit.width > 0f && fit.height > 0f) {
                    // 相框（尺寸 / 平移 / 圆角 / 描边）四件事在两种形态下完全一致，
                    // 抽成同一个 Modifier 才不会让分割对比看起来像「另一个控件」。
                    val frame = Modifier
                        .size(
                            with(density) { dispW.toDp() },
                            with(density) { dispH.toDp() }
                        )
                        .offset { IntOffset(previewPan.x.roundToInt(), previewPan.y.roundToInt()) }
                        .clip(Radius.chip)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.chip)

                    if (splitCompare && afterBmp != null) {
                        // 规范 §09：分割线 = **逐列对比**。先铺「之后」，再把「之前」裁到线左侧 ——
                        // 两条位图同尺寸同缩放，所以这是一次纯 clipRect，不需要第二套坐标换算
                        // （这正是规范说「纯 UI 裁剪」的原因）。
                        val handleHalfPx = with(density) { 22.dp.toPx() }
                        Box(modifier = frame) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val dst = IntSize(size.width.toInt(), size.height.toInt())
                                drawImage(afterBmp, dstSize = dst)
                                val x = size.width * splitPos
                                clipRect(right = x) { drawImage(beforeBmp, dstSize = dst) }
                                // 分割线 + 中部手柄：白色实心，靠画面自身的高对比被看见，
                                // 不用强调色（琥珀在这里会与「选中」语义混淆）。
                                drawLine(
                                    color = Color.White.copy(alpha = 0.92f),
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = 1.5.dp.toPx()
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 9.dp.toPx(),
                                    center = Offset(x, size.height / 2f)
                                )
                                drawCircle(
                                    color = Color.Black.copy(alpha = 0.28f),
                                    radius = 2.5.dp.toPx(),
                                    center = Offset(x - 3.dp.toPx(), size.height / 2f)
                                )
                                drawCircle(
                                    color = Color.Black.copy(alpha = 0.28f),
                                    radius = 2.5.dp.toPx(),
                                    center = Offset(x + 3.dp.toPx(), size.height / 2f)
                                )
                            }
                            // 拖动手柄：**只在分割模式存在**的 44dp 宽热区。
                            //
                            // ⚠️ 这里刻意用 `detectHorizontalDragGestures` 而不是把它并进
                            // `previewGestures` 状态机：后者是全屏手势，混进一个「只在中线附近生效」
                            // 的分支会重新引入 v0.5.0 修掉的「捏合被当成单指拖动」那类冲突。
                            // 子级先拿到事件并消费 ⇒ 手柄区域不会触发父级的手势分支，两者天然隔离。
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .size(44.dp, with(density) { dispH.toDp() })
                                    .offset { IntOffset((dispW * splitPos - handleHalfPx).roundToInt(), 0) }
                                    // ⚠️ key 必须是 `dispW` 而不是 `Unit`：`pointerInput` 只在 key 变化时
                                    // 重建闭包，而 `dispW` 是**每帧重算的普通局部量**（不是 State）。
                                    // 写 `Unit` 会让闭包一直用首帧的 `dispW`（那时布局还没测量，通常是 0）
                                    // ⇒ `if (dispW > 0f)` 恒假 ⇒ 分割线**拖不动且不报错**，
                                    // 与 `snapshotFlow` 里先取局部 `val` 那类静默失效同源。
                                    .pointerInput(dispW) {
                                        detectHorizontalDragGestures { _, dx ->
                                            if (dispW > 0f) {
                                                splitPos = (splitPos + dx / dispW).coerceIn(0.02f, 0.98f)
                                            }
                                        }
                                    }
                            )
                        }
                    } else {
                        Image(
                            bitmap = display,
                            contentDescription = "编辑预览",
                            contentScale = ContentScale.Fit,
                            modifier = frame
                        )
                    }
                } else {
                    Text("渲染中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ———— 2. Chrome 层（顶栏 + 直方图条 + Deck + Rail）————
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏：只降 alpha、不收高度（收高度会改照片 fit，画面跳两下）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .inertUnless(enabled = !dragging)
                        .alpha(chromeAlpha)
                ) {
                    EditorTopBar(
                        showOriginal = showOriginal,
                        canUndo = canUndo,
                        canRedo = canRedo,
                        exporting = exporting,
                        onBack = onBack,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onReset = onReset,
                        onToggleCompare = { showOriginal = !showOriginal },
                        splitCompare = splitCompare,
                        // 进入分割对比时收起「看原图」：两者都改 display，同时开着会让
                        // 「之前」那张变成整屏（分割线左右看起来一样），用户会以为功能没生效。
                        onToggleSplit = {
                            splitCompare = !splitCompare
                            if (splitCompare) showOriginal = false
                        },
                        onExport = { showExport = true }
                    )
                }

                // 直方图条：随 chrome 淡出；点击收起 / 展开（用户主动行为，允许布局变化）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .inertUnless(enabled = !dragging)
                        .alpha(chromeAlpha)
                ) {
                    HistogramStrip(
                        data = histogram,
                        expanded = histExpanded,
                        onToggle = { histExpanded = !histExpanded }
                    )
                }

                // 照片区：weight 吃掉剩余高度（照片本体在上面第一层，全屏铺底）。
                Spacer(Modifier.weight(1f))

                // ———— Deck 控制台（固定高 S/L）————
                //
                // 实心容器（零 blur —— 满屏玻璃是掉帧第一来源，§6.2），顶部 20dp 圆角。
                // 作用域行 / 分组 chips 钉在滚动之外；内容区滚动兜底（4 滑块的组）。
                // ⚠️ 这里是参数的一部分（决定「改谁 / 改什么」），**不**随 chrome 淡出。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(deckH)
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                        )
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (category.scopeMode.showsScopeBar) {
                            ParamScopeBar(
                                current = scope,
                                available = objectScopesAvailable,
                                adjusted = { sc -> layers.isAdjusted(sc) },
                                onSelect = selectScope,
                                modifier = Modifier.padding(
                                    horizontal = Spacing.cardInner,
                                    vertical = Spacing.s
                                )
                            )
                        }
                        ParamSubBar(
                            category = category,
                            selection = selection,
                            onSelectionChange = { selection = it }
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = Spacing.cardInner)
                                .padding(bottom = Spacing.m)
                        ) {
                            Crossfade(
                                targetState = category,
                                animationSpec = tween(Motion.durationFor(Motion.base), easing = Motion.easingOut),
                                label = "paramCategory"
                            ) { cat ->
                                ParamPanel(
                                    category = cat,
                                    params = shownParams,
                                    retouch = retouch,
                                    retouchTool = retouchTool,
                                    brushRadius = brushRadius,
                                    inpaintRadius = inpaintRadius,
                                    inpaintCount = inpaintCount,
                                    autoMaskEnabled = autoMaskEnabled,
                                    presets = presets,
                                    activePresetId = activePresetId,
                                    selection = selection,
                                    activeScope = scope,
                                    activeLayer = activeLayer,
                                    presetThumbs = presetThumbs,
                                    onSelectionChange = { selection = it },
                                    onParamChange = writeParams,
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
                                    onLayerChange = { onLayersChange(layers.upsert(it)) },
                                    onLayerCommit = onLayersCommit,
                                    onRemoveLayer = {
                                        scope?.let { sc ->
                                            onLayersChange(layers.without(sc))
                                            activeScope = null
                                            onLayersCommit()
                                        }
                                    },
                                    onDraggingChange = { dragging = it }
                                )
                            }
                        }
                    }
                }

                // ———— Rail 工具栏（六工具，图标 + 文字）————
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .inertUnless(enabled = !dragging)
                        .alpha(chromeAlpha)
                        .navigationBarsPadding()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    EditorRail(
                        selected = category,
                        onSelect = { category = it },
                        modifier = Modifier.heightIn(min = RAIL_HEIGHT)
                    )
                }
            }

            // ———— 3. 浮层（放大复位胶囊 / 中央 HUD / 胶囊提示）————
            //
            // 必须在 chrome 之后声明（z 轴更高），否则会被 Deck 盖住。
            Box(modifier = Modifier.fillMaxSize()) {
                if (previewZoom > 1.01f) {
                    val tint = rememberGlassTint(opaque = true)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(top = 96.dp, end = 12.dp)
                            .clip(Radius.pill)
                            .clickable(
                                interactionSource = null,
                                indication = null
                            ) {
                                previewZoom = 1f
                                previewPan = Offset.Zero
                            }
                            .glassSurface(tint, Radius.pill)
                            .padding(horizontal = Spacing.m, vertical = Spacing.s),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "已放大 ${(previewZoom * 100).roundToInt()}% · 复位",
                            style = MaterialTheme.typography.labelSmall,
                            color = tint.content
                        )
                    }
                }

                // 中央 HUD：拖动时随读数更新浮现；松手**停 800ms 再 300ms 淡出**
                // （规范 `.bighud` 口径：读数要让人读完，不是闪一下就跑）。
                // 数据来自 LocalHudReporter；位置固定在 Deck 上方、照片下三分之一。
                AnimatedVisibility(
                    visible = hudShown,
                    enter = fadeIn(tween(Motion.durationFor(Motion.fast), easing = Motion.easingOut)),
                    exit = fadeOut(tween(300, easing = Motion.easingOut)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = deckH + RAIL_HEIGHT + 18.dp)
                ) {
                    hud?.let { (name, value) ->
                        Column(
                            modifier = Modifier
                                .clip(Radius.pill)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = value,
                                // 规范 `.bighud .pv{font-size:46px;font-weight:300;line-height:1.15}`
                                // ⇒ 46sp / **Light(300)**（原来 40sp + Normal，尺寸与字重都偏轻，
                                // 「巨大 HUD」不够巨大）。`tnum` 保持数值等宽（规范要求 tabular-nums）。
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Light,
                                    fontSize = 46.sp,
                                    lineHeight = 53.sp,
                                    fontFeatureSettings = "tnum"
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                PreviewNotes(
                    autoMaskNote = autoMaskNote,
                    liquifyNote = liquifyNote,
                    bottomPadding = deckH + RAIL_HEIGHT + 76.dp
                )
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
            onDismiss = { showExport = false },
            jpegQuality = jpegQuality
        )
    }
}

// ———————————————————————————————————————————————————————————————
// 顶栏
// ———————————————————————————————————————————————————————————————

/**
 * 顶栏：`← 编辑 … 撤销 重做 重置 对比 导出`（玻璃胶囊，悬浮于全屏画布上）。
 *
 * 图标 + 34dp 热区的次级动作（[IconAction]）承担撤销/重做/重置/对比——图标比文字
 * 少占一半宽度；「对比」是双态（长按照片也可以临时看原图）。
 * 「导出」是唯一强调色元素（[ExportPill]）。
 */
@Composable
private fun EditorTopBar(
    showOriginal: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    exporting: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onToggleCompare: () -> Unit,
    splitCompare: Boolean,
    onToggleSplit: () -> Unit,
    onExport: () -> Unit
) {
    val tint = rememberGlassTint()
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .glassSurface(tint, Radius.pill)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TOP_BAR_HEIGHT)
                    .padding(horizontal = Spacing.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(def = AppIcons.Back, label = "返回", onClick = onBack)
                Text("编辑", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconAction(def = AppIcons.Undo, label = "撤销", enabled = canUndo, onClick = onUndo)
                IconAction(def = AppIcons.Redo, label = "重做", enabled = canRedo, onClick = onRedo)
                IconAction(def = AppIcons.Reset, label = "重置", onClick = onReset)
                IconAction(
                    def = AppIcons.Eye,
                    label = "对比原图",
                    selected = showOriginal,
                    onClick = onToggleCompare
                )
                // 分割对比（UI 3.0 §09 顶栏入口之一）。`AppIcons.Split` 此前是**声明未使用**
                // 的悬空图标，正是为这个入口准备的 —— 接上之后它才有意义。
                IconAction(
                    def = AppIcons.Split,
                    label = "分割对比",
                    selected = splitCompare,
                    onClick = onToggleSplit
                )
                Spacer(Modifier.width(Spacing.s))
                ExportPill(enabled = !exporting, exporting = exporting, onClick = onExport)
            }
        }
    }
}

/**
 * 顶栏图标动作：34dp 热区 + 20dp 图标；不做按下缩放 ⇒ indication / interactionSource
 * 一并传 null（全 App 统一口径）。禁用态与选中态只动颜色。
 */
@Composable
private fun IconAction(
    def: IconDef,
    label: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val tint = when {
        selected -> MaterialTheme.colorScheme.primary
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(Radius.chip)
            .clickable(
                interactionSource = null,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        PathIcon(def = def, tint = tint, size = 20.dp)
    }
}

/**
 * 「导出」——顶栏的主操作：琥珀软底胶囊（primary 16% 底 + 45% 描边 + primary 文字）。
 * 全屏里唯一填充色块，主次一眼可辨。「导出中」变禁用 + 文案变化（进度在 Sheet 里）。
 */
@Composable
private fun ExportPill(
    enabled: Boolean,
    exporting: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .height(34.dp)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .alpha(if (enabled) 1f else 0.4f)
            .background(
                // 规范 `--amber-soft: rgba(255,180,84,.14)` ⇒ 14%
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                Radius.pill
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                Radius.pill
            )
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
// 直方图条
// ———————————————————————————————————————————————————————————————

/** 三通道的固定用色（与设计稿 §2.5 直方图组件同源）。 */
private val HIST_R = Color(0xFFFF6B6B)
private val HIST_G = Color(0xFF58C98B)
private val HIST_B = Color(0xFF6FA8FF)

/**
 * 顶部直方图条（UI 3.0）：三通道半透明叠加，偏色一眼可见。点击收起 / 展开。
 * 数据由 [EditorScreen] 的 produceState 随渲染完成在后台重算；null（首帧 / 失败）时不占位。
 */
@Composable
private fun HistogramStrip(
    data: Triple<FloatArray, FloatArray, FloatArray>?,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    if (!expanded || data == null) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(Radius.card)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .clickable(interactionSource = null, indication = null, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(26.dp)) {
            val channels = listOf(data.first to HIST_R, data.second to HIST_G, data.third to HIST_B)
            for ((arr, color) in channels) {
                val n = arr.size
                if (n < 2) continue
                val path = Path()
                path.moveTo(0f, size.height)
                for (i in 0 until n) {
                    path.lineTo(
                        i.toFloat() / (n - 1) * size.width,
                        size.height * (1f - arr[i].coerceIn(0f, 1f))
                    )
                }
                path.lineTo(size.width, size.height)
                path.close()
                drawPath(path, color.copy(alpha = 0.42f))
            }
        }
    }
}

// ———————————————————————————————————————————————————————————————
// 通用 Modifier
// ———————————————————————————————————————————————————————————————

/**
 * 「隐身即不可点」：`enabled` 为 `false` 时把整棵子树变成**哑的** —— 不再接收任何指针事件。
 *
 * alpha 只作用于绘制，命中测试照旧——一张 alpha=0 的「重置」照样点得中。
 * 在父节点 Initial 趟消费全部变更，子节点（含将来的任何形态）都收不到事件；
 * 且它必须排在 padding 之前，否则只盖得住内边距以内的部分。
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
// 预览区手势
// ———————————————————————————————————————————————————————————————

/**
 * 按 [ContentScale.Fit] 反算图片在给定空间内的**实际绘制矩形**（两个调用点共用）：
 * 1. 照片层定尺；2. 手势落点换算。internal 供 JVM 单测（FitContentRectTest）。
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
 * 预览区的指针交互（单协程状态机，UI 3.0 与 v2.1 同一实现）。
 *
 * | 手势 | 查看模式 | 画笔 | 祛瑕 |
 * |---|---|---|---|
 * | 双指捏合/拖动 | 缩放+平移 | 缩放+平移 | 缩放+平移 |
 * | 单指拖动（已放大） | 平移 | **涂抹**（挪图交给双指） | 平移 |
 * | 单指按住 | 看原图 | 涂抹 | — |
 * | 双击 | 2.5x ↔ 1x（点击处为锚） | — | 落祛瑕点 |
 *
 * ⚠️ pointerInput 的 key 带 boxPx/contentSize（首帧零尺寸），**绝不**带 zoom/pan
 * （平移中每帧变 ⇒ 协程重启 ⇒ 平移断掉）——它们经 readZoom/readPan 读活值。
 */
private fun Modifier.previewGestures(
    retouchTool: String,
    boxPx: IntSize,
    contentSize: IntSize,
    readZoom: () -> Float,
    readPan: () -> Offset,
    onTransform: (zoomFactor: Float, panDelta: Offset) -> Unit,
    onDoubleTapToggle: (Offset) -> Unit,
    onBrushStroke: (Float, Float) -> Unit,
    onInpaintStroke: (Float, Float) -> Unit,
    onPressChanged: (Boolean) -> Unit
): Modifier = pointerInput(retouchTool, boxPx, contentSize) {
    val slopPx = 8.dp.toPx()
    var lastTapUp = 0L
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        first.consume()
        var mode = 0
        var dragged = false
        // 查看模式：按下即看原图（保持既有语义，抬起/进入双指时还原）。
        if (retouchTool == "none") onPressChanged(true)
        try {
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    if (mode != 2 && retouchTool == "none") onPressChanged(false)
                    mode = 2
                    val zf = event.calculateZoom()
                    val pd = event.calculatePan()
                    if (zf != 1f || pd != Offset.Zero) {
                        onTransform(zf, pd)
                        event.changes.forEach { it.consume() }
                    }
                } else {
                    val c = pressed[0]
                    val delta = c.positionChange()
                    if (delta != Offset.Zero) {
                        if (mode == 2) {
                            // 双指抬到单指：继续平移（随时可再落回双指）
                            onTransform(1f, delta)
                            c.consume()
                        } else if (retouchTool == "skin") {
                            // clampToContent：手指划出画面不断笔（旧 detectDragGestures 的语义）
                            pointToContentNorm(
                                c.position, boxPx, contentSize, readZoom(), readPan(),
                                clampToContent = true
                            )?.let { onBrushStroke(it.first, it.second) }
                            dragged = true
                            c.consume()
                        } else if (readZoom() > 1f) {
                            onTransform(1f, delta)
                            dragged = true
                            c.consume()
                        } else {
                            // 未放大时的单指拖动：超过 slop 才算「拖过」（微抖不算，别误杀 tap）。
                            if ((c.position - first.position).getDistance() > slopPx) dragged = true
                        }
                    }
                    mode = if (mode == 2) 2 else 1
                }
            }
        } finally {
            if (retouchTool == "none") onPressChanged(false)
        }
        // —— 抬指判定：没拖过、没进过多指 ⇒ 是一次 tap ——
        if (!dragged) {
            if (retouchTool == "blemish") {
                pointToContentNorm(first.position, boxPx, contentSize, readZoom(), readPan())
                    ?.let { onInpaintStroke(it.first, it.second) }
            } else if (retouchTool == "none") {
                val now = System.currentTimeMillis()
                if (now - lastTapUp < DOUBLE_TAP_MS) {
                    onDoubleTapToggle(first.position)
                    lastTapUp = 0L
                } else {
                    lastTapUp = now
                }
            }
        } else {
            lastTapUp = 0L
        }
    }
}

/**
 * 平移钳位：图片不许脱出预览框（internal 供 JVM 单测 PreviewZoomTest）。
 */
internal fun clampPreviewPan(pan: Offset, boxW: Float, boxH: Float, dispW: Float, dispH: Float): Offset {
    val mx = maxOf(0f, (dispW - boxW) / 2f)
    val my = maxOf(0f, (dispH - boxH) / 2f)
    return Offset(pan.x.coerceIn(-mx, mx), pan.y.coerceIn(-my, my))
}

/**
 * 预览框内指针坐标 → 照片内容的 [0..1] 归一化坐标（缩放 / 平移感知）。
 * 祛瑕：越界返回 null（不落点）；画笔（clampToContent=true）：越界 clamp 不断笔。
 * internal 供 JVM 单测 PreviewZoomTest。
 */
internal fun pointToContentNorm(
    pos: Offset,
    boxPx: IntSize,
    contentSize: IntSize,
    zoom: Float,
    pan: Offset,
    clampToContent: Boolean = false
): Pair<Float, Float>? {
    val fit = fitContentRect(boxPx, contentSize)
    if (fit.width <= 0f || fit.height <= 0f) return null
    val dispW = fit.width * zoom
    val dispH = fit.height * zoom
    val left = boxPx.width / 2f - dispW / 2f + pan.x
    val top = boxPx.height / 2f - dispH / 2f + pan.y
    val nx = (pos.x - left) / dispW
    val ny = (pos.y - top) / dispH
    if (clampToContent) return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
    return nx to ny
}

/**
 * 双击切换缩放档时的平移换算：点住的照片内容在切换前后不动。
 * internal 供 JVM 单测 PreviewZoomTest。
 */
internal fun panAnchorAtPoint(
    tap: Offset,
    boxW: Float,
    boxH: Float,
    fitW: Float,
    fitH: Float,
    oldZoom: Float,
    oldPan: Offset,
    newZoom: Float
): Offset {
    val dispW = fitW * oldZoom
    val dispH = fitH * oldZoom
    val left = boxW / 2f - dispW / 2f + oldPan.x
    val top = boxH / 2f - dispH / 2f + oldPan.y
    val cx = if (dispW > 0f) (tap.x - left) / dispW else 0.5f
    val cy = if (dispH > 0f) (tap.y - top) / dispH else 0.5f
    val ndW = fitW * newZoom
    val ndH = fitH * newZoom
    val nLeft = tap.x - cx * ndW
    val nTop = tap.y - cy * ndH
    return Offset(nLeft - (boxW / 2f - ndW / 2f), nTop - (boxH / 2f - ndH / 2f))
}

// ———————————————————————————————————————————————————————————————
// 预览上的胶囊提示
// ———————————————————————————————————————————————————————————————

/**
 * 把系统状态说明浮在预览底部、数秒后淡出。内容变化时重新计时（key=两条 note）。
 * One-Surface 下固定在 Deck + Rail 之上（[bottomPadding] 由调用方按当前 Deck 档位传入）。
 */
@Composable
private fun BoxScope.PreviewNotes(autoMaskNote: String, liquifyNote: String, bottomPadding: Dp) {
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
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = bottomPadding)
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
