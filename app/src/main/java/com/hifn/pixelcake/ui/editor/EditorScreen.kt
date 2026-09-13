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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.core.decode.ExportFormat
import com.hifn.pixelcake.core.edit.EditParams
import com.hifn.pixelcake.core.edit.RetouchState
import com.hifn.pixelcake.core.edit.preset.Preset
import com.hifn.pixelcake.ui.components.CapsuleNote
import com.hifn.pixelcake.ui.theme.Motion
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing
import com.hifn.pixelcake.ui.theme.blurredBackdrop
import kotlinx.coroutines.delay

/** 顶栏高度。56dp 是 Material 的工具栏标准高度，也是「一眼认出这是标题栏」的最小代价。 */
private val TOP_BAR_HEIGHT = 56.dp

/** 胶囊提示自动淡出前的停留时长。够读完一句话，又不至于长期糊在照片上。 */
private const val NOTE_VISIBLE_MS = 3200L

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
 * ## 四段式布局
 *
 * ```
 * ┌─ 顶栏 56dp ── 返回 / 撤销 重做 重置 / 导出          ← 一次性动作
 * ├─ 预览区 weight(1f) ── 照片 + 手势 + 胶囊提示        ← 主体，占比 ≥55%
 * ├─ 一级工具条 44dp ── 人像 调色 曲线 LUT 预设          ← 分类导航
 * └─ 参数面板 ── 当前分类的 3~7 个滑块                  ← 高频动作
 * ```
 *
 * 与旧版（一条 485 行的长滚动 Column，把 30 个滑块和导出按钮平铺在一起）的区别：
 * **按使用频率分层**。一次性动作（导出）收进 Sheet，高频动作（滑块）留在手边，
 * 中频的「换一类参数」交给一级工具条。旧版把三者混在一起，用户每次都要在长列表里找位置。
 *
 * ## 拖动时隐藏非参数 UI（隐形式交互，§5）
 *
 * 任何滑块开始拖动 → 顶栏与工具条淡出，只留照片和正在动的那个滑块。这是 Snapseed 的
 * 核心体验：**调参时画面不该被任何 UI 分心**。松手立刻恢复。
 *
 * 两处的做法**刻意不同**，原因是「会不会让手指下的滑块位移」：
 * - 顶栏在预览**上方** → 用 [AnimatedVisibility] 收起，把高度让给预览（布局收缩是安全的）；
 * - 工具条在参数面板**上方** → 只能做**透明度**淡出。若把高度收掉，面板会整体下移 44dp，
 *   用户手指按着的滑块会在拖动中突然跑掉 —— 这类「动一下就跳」是触屏调参最忌的体验。
 *
 * ## 预览区手势（由 [retouchTool] 决定，语义与旧版完全一致）
 * - `"none"`   ：按住看原图（前后对比）；
 * - `"skin"`   ：拖动涂抹皮肤作用区，坐标归一化到 [0..1] 后经 [onBrushStroke] 上报；
 * - `"blemish"`：点击脏点，经 [onInpaintStroke] 上报。
 *
 * 坐标一律**归一化**再上报：预览尺寸与源图/导出分辨率都不是一回事（硬约定），
 * 任何一处直接传像素坐标都会在导出时错位。
 *
 * @param autoMaskNote 自动蒙版状态（模型/加速器）。以胶囊提示浮在预览上、数秒后淡出，
 *   **不再占参数面板空间** —— 它是系统状态说明，不是参数。真机验收时用它核对是否真走 GPU。
 * @param liquifyNote 液化锚点来源（人脸检测 / 蒙版质心）。同上，供真机验收核对。
 */
@Composable
fun EditorScreen(
    original: Bitmap,
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
    canUndo: Boolean,
    canRedo: Boolean,
    status: String,
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
    // 预览图实测尺寸，用于把指针坐标归一化（与显示缩放/letterbox 解耦）。
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    // 一级工具条当前分类。**默认落在「人像」**：这是本 App 的主场景。
    var category by remember { mutableStateOf(EditorCategory.Portrait) }
    var showExport by remember { mutableStateOf(false) }
    // 是否有滑块正在被拖动（隐形式交互的开关）
    var dragging by remember { mutableStateOf(false) }

    // renderVersion 每次重渲自增，确保本可组合项重组并重绘当前(已被原位修改的)Bitmap。
    val display: ImageBitmap? = run {
        val _v = renderVersion
        if (showOriginal) original.asImageBitmap() else rendered?.asImageBitmap()
    }

    // 顶栏收起 / 工具条淡出（两者做法不同，原因见类文档）
    val chromeAlpha by animateFloatAsState(
        targetValue = if (dragging) 0f else 1f,
        animationSpec = tween(Motion.fast, easing = Motion.easingOut),
        label = "chromeAlpha"
    )

    // UI-5：静态模糊底图。用 `remember(original)` 钉住 —— 换图才重算一次，
    // 拖动滑块 / 滚动面板 / 切分类都**不重算**（这是「不做实时 backdrop 模糊」的落地方式）。
    val backdrop = remember(original) { blurredBackdrop(original) }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
        AnimatedVisibility(
            visible = !dragging,
            enter = fadeIn(tween(Motion.fast, easing = Motion.easingOut)),
            exit = fadeOut(tween(Motion.fast, easing = Motion.easingIn))
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = Spacing.m)
                // 先 clip 再 background：预览是圆角容器，若只给背景上圆角，
                // 照片的方形四角会露在圆角之外（`Image` 默认 ContentScale.Fit，仍按矩形绘制）。
                .clip(Radius.shell)
                .background(Color.Black, Radius.shell)
                .onSizeChanged { previewSize = it },
            contentAlignment = Alignment.Center
        ) {
            if (display != null) {
                Image(
                    bitmap = display,
                    contentDescription = "编辑预览",
                    modifier = Modifier.fillMaxSize().previewGestures(
                        retouchTool = retouchTool,
                        previewSize = previewSize,
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

        // ———— 3. 一级工具条（拖动时只淡出，不收起 —— 否则面板会下移、手指下的滑块会跑掉）————
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.page, vertical = Spacing.s)
                .alpha(chromeAlpha)
        ) {
            EditorToolbar(current = category, onSelect = { category = it })
        }

        // ———— 4. 参数面板 ————
        // 面板**常驻**（不收起）：它是高频操作区，收起来等于每次调参都多一次点击。
        // 切分类时用 Crossfade 淡换内容 —— 直接硬切会让面板「闪一下」，而且用户会以为点错了。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page, vertical = Spacing.s)
        ) {
            Crossfade(
                targetState = category,
                animationSpec = tween(Motion.base, easing = Motion.easingOut),
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
// 顶栏
// ———————————————————————————————————————————————————————————————

/**
 * 顶栏：`← 编辑 | 撤销 重做 重置 | 导出`。
 *
 * 用文字按钮而不是图标，是为了**不引入新的依赖**：本项目当前没有依赖
 * `material-icons-extended`，为了 6 个图标把整个图标库打进包并不划算；
 * 而「撤销 / 重做 / 重置」写中文比图标更不容易认错（图标在不同 App 里语义漂移很大）。
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
        TextButton(onClick = onBack) { Text("←") }
        Text("编辑", style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.weight(1f))

        TextButton(onClick = onUndo, enabled = canUndo) { Text("撤销") }
        TextButton(onClick = onRedo, enabled = canRedo) { Text("重做") }
        TextButton(onClick = onReset) { Text("重置") }

        Spacer(Modifier.width(Spacing.xs))

        Button(onClick = onExport, enabled = !exporting) {
            Text(if (exporting) "导出中" else "导出")
        }
    }
}

// ———————————————————————————————————————————————————————————————
// 预览区手势
// ———————————————————————————————————————————————————————————————

/**
 * 预览区的指针交互。抽成 [Modifier] 扩展是为了让 [EditorScreen] 的布局部分读起来是布局，
 * 而不是被三段 `pointerInput` 代码块淹没。
 *
 * ⚠️ 坐标必须先除以**实测预览尺寸**再 `coerceIn(0f, 1f)`：预览是 letterbox 居中的，
 * 但上报的是归一化坐标，所以这里用整块预览 `Box` 的尺寸即可（与显示缩放无关）。
 */
private fun Modifier.previewGestures(
    retouchTool: String,
    previewSize: IntSize,
    onBrushStroke: (Float, Float) -> Unit,
    onInpaintStroke: (Float, Float) -> Unit,
    onPressChanged: (Boolean) -> Unit
): Modifier = when (retouchTool) {
    "skin" -> pointerInput(Unit) {
        detectDragGestures { change, _ ->
            change.consume()
            val sx = previewSize.width.toFloat().coerceAtLeast(1f)
            val sy = previewSize.height.toFloat().coerceAtLeast(1f)
            onBrushStroke(
                (change.position.x / sx).coerceIn(0f, 1f),
                (change.position.y / sy).coerceIn(0f, 1f)
            )
        }
    }

    "blemish" -> pointerInput(Unit) {
        detectTapGestures { offset ->
            val sx = previewSize.width.toFloat().coerceAtLeast(1f)
            val sy = previewSize.height.toFloat().coerceAtLeast(1f)
            onInpaintStroke(
                (offset.x / sx).coerceIn(0f, 1f),
                (offset.y / sy).coerceIn(0f, 1f)
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
        enter = fadeIn(tween(Motion.base, easing = Motion.easingOut)),
        exit = fadeOut(tween(Motion.slow, easing = Motion.easingIn)),
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
