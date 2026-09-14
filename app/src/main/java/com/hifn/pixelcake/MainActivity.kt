package com.hifn.pixelcake

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hifn.pixelcake.arw.ArwFullDecoder
import com.hifn.pixelcake.core.decode.DecodedImage
import com.hifn.pixelcake.core.decode.Decoder
import com.hifn.pixelcake.core.decode.Exporter
import com.hifn.pixelcake.core.decode.ExportFormat
import com.hifn.pixelcake.core.edit.BeautyParams
import com.hifn.pixelcake.core.edit.EditEngine
import com.hifn.pixelcake.core.edit.EditHistory
import com.hifn.pixelcake.core.edit.EditParams
import com.hifn.pixelcake.core.edit.EditSnapshot
import com.hifn.pixelcake.core.edit.FullMask
import com.hifn.pixelcake.core.edit.InpaintStroke
import com.hifn.pixelcake.core.edit.NeutralGrayParams
import com.hifn.pixelcake.core.edit.RetouchMask
import com.hifn.pixelcake.core.edit.RetouchScale
import com.hifn.pixelcake.core.edit.RetouchState
import com.hifn.pixelcake.core.edit.preset.Preset
import com.hifn.pixelcake.core.edit.preset.Presets
import com.hifn.pixelcake.core.edit.retouch.RetouchLayer
import com.hifn.pixelcake.core.ml.MlFaceProvider
import com.hifn.pixelcake.core.ml.MlMaskProvider
import com.hifn.pixelcake.diag.DebugLog
import com.hifn.pixelcake.ui.editor.EditorScreen
import com.hifn.pixelcake.ui.editor.EditorStatus
import com.hifn.pixelcake.ui.editor.StatusKind
import com.hifn.pixelcake.ui.home.HomeScreen
import com.hifn.pixelcake.ui.home.probeCapabilities
import com.hifn.pixelcake.ui.home.resolutionProfile
import com.hifn.pixelcake.ui.settings.SettingsScreen
import com.hifn.pixelcake.ui.shell.AppShell
import com.hifn.pixelcake.ui.shell.PixelCakeTab
import com.hifn.pixelcake.ui.theme.LocalLowTransparency
import com.hifn.pixelcake.ui.theme.PixelCakeTheme
import com.hifn.pixelcake.ui.theme.PixelCakeWorkspaceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** 滑块拖动时的重渲节流窗口（FIX_LIST F08）。16ms ≈ 一帧，肉眼无感但能挡掉绝大多数中间值。 */
private const val RENDER_THROTTLE_MS = 16L

/** 皮肤画笔两描迹的最小归一化距离平方（避免一次拖动塞入成百上千条描迹，拖慢蒙版重建）。 */
private const val MIN_STROKE_DIST2 = 0.008f * 0.008f

/** 蒙版描迹条数上限（为 `RasterMask.fromStrokes` 的 O(n·r²) 重建耗时设上界）。 */
private const val MAX_BRUSH_STROKES = 3000

/**
 * 预设缩略图边长（像素）。
 *
 * 取 192 = 64dp @3x（面板里的显示尺寸是 64dp）。10 张合计约 1.5MB —— 与全幅 `IntArray`
 * 的 131MB 量级完全不是一回事，且**上界固定**（预设套数），所以不做回收，交给 GC。
 */
private const val PRESET_THUMB_PX = 192

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容绘制到系统栏之下，配合主题中的透明状态栏
        enableEdgeToEdge()
        setContent {
            PixelCakeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = remember { context.probeCapabilities().resolutionProfile() }

    var tab by remember { mutableStateOf(PixelCakeTab.Darkroom) }
    // 编辑器是否打开。**刻意不用第三个 Tab**：编辑是全屏工作台，进入后 TabBar 直接消失，
    // 把画面整块交给预览区（`docs/UI_DESIGN.md` §3.3）。
    var editorOpen by remember { mutableStateOf(false) }
    // 「降低透明度」：影响全 App 玻璃材质，因此由这里持有并经 CompositionLocal 下发。
    var lowTransparency by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<DecodedImage?>(null) }
    var srcUri by remember { mutableStateOf<Uri?>(null) }
    val history = remember { EditHistory() }
    var params by remember { mutableStateOf(EditParams()) }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    // 状态行：**类别 + 文案一起**存（审计 M2）。UI 侧按 kind 取色，不再拿文案做判断。
    var status by remember { mutableStateOf(EditorStatus()) }
    var exporting by remember { mutableStateOf(false) }
    // 导出格式（JPEG / PNG）：由编辑器里的格式选择驱动，导出两条路径（RAW / sRGB）共用同一取值。
    var exportFormat by remember { mutableStateOf(ExportFormat.JPEG) }
    var loading by remember { mutableStateOf(false) }
    val exportCancelled = remember { AtomicBoolean(false) }
    val renderMutex = remember { Mutex() }
    var renderStamp by remember { mutableStateOf(0) }

    // P1b-4：人像精修状态（tonal 的 EditParams 之外的附加层）
    var retouch by remember { mutableStateOf(RetouchState()) }
    var retouchTool by remember { mutableStateOf("none") }
    var brushRadius by remember { mutableStateOf(0.04f) }
    var brushStrokes by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    var inpaintRadius by remember { mutableStateOf(0.01f) }
    var inpaintStrokes by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    // 当前生效的预设 id（用于 UI 高亮；用户手动改动任一参数即清空）
    var activePresetId by remember { mutableStateOf("none") }
    // P1p-1b：自动蒙版（AI 皮肤识别）。默认开启；模型不可用/OOM 时自动降级回退画笔/整幅。
    var autoMaskEnabled by remember { mutableStateOf(true) }
    // 自动蒙版状态提示：成功时显示加速器（GPU/CPU），失败时提示已回退。供真机验收核对。
    var autoMaskNote by remember { mutableStateOf("") }
    // P1p-2c：液化锚点来源提示（人脸检测 / 蒙版质心）。供真机验收核对「锚点到底来自哪」。
    var liquifyNote by remember { mutableStateOf("") }
    // UI-4b（A 档）：预设缩略图（id → 位图）。按**原图**渲染，只在换图时算一次。
    var presetThumbs by remember { mutableStateOf(emptyMap<String, Bitmap>()) }

    // 预设缩略图：**从原图渲染**，与当前编辑无关 —— 缩略图回答的是「这个预设会把照片变成什么样」，
    // 不是「在现有编辑上叠加会怎样」。否则用户一调参全部缩略图跟着变，就失去了参照意义。
    // 放在 IO/Default 线程：10 张 192×192 的渲染约几十毫秒，放主线程会卡一次首帧。
    LaunchedEffect(imported) {
        val src = imported ?: return@LaunchedEffect
        presetThumbs = withContext(Dispatchers.Default) {
            buildPresetThumbs(src.bitmap, Presets.ALL)
        }
        DebugLog.i(
            DebugLog.TAG_EDIT, "preset thumbs ready",
            mapOf("count" to presetThumbs.size, "px" to PRESET_THUMB_PX)
        )
    }

    // 参数 / retouch / 蒙版 / 自动蒙版开关变化 -> 异步把参数栈 + retouch 重渲到代理图。
    // 用 snapshotFlow + conflate + collectLatest 做节流与取消（FIX_LIST F08）。
    LaunchedEffect(imported) {
        val src = imported ?: return@LaunchedEffect
        // 注意 `brushRadius` / `inpaintRadius` 必须**在监听列表里**：它们参与蒙版/祛瑕的
        // 像素半径换算（见 buildRenderRetouch），漏掉会导致「只拖半径滑块，预览不跟随」——
        // 蒙版仍按旧半径渲染，要等下一次别的参数变化才刷新（自愈，但严格说是所见非所得）。
        snapshotFlow {
            listOf(
                params, retouch, brushStrokes, inpaintStrokes, autoMaskEnabled,
                brushRadius, inpaintRadius
            )
        }
            .conflate()
            .collectLatest {
                delay(RENDER_THROTTLE_MS)
                // 取当前这次重渲对应的协程 job：新参数到来时 collectLatest 会取消它，
                // 渲染器据此在下一个分带边界退出（真正的协作取消，FIX_LIST F08 修复）。
                val renderJob = currentCoroutineContext().job
                // 在主线程捕获最新状态，避免在 Dispatchers.Default 内跨线程读快照状态
                val p = params
                val rt = retouch
                val strokes = brushStrokes
                val radius = brushRadius
                val inpStrokes = inpaintStrokes
                val inpRadius = inpaintRadius
                val autoOn = autoMaskEnabled
                val w = src.linear?.width ?: src.bitmap.width
                val h = src.linear?.height ?: src.bitmap.height
                // ML 蒙版缓存键：同一张图在预览与导出之间复用同一蒙版对象（P1p-1b）。
                val mlKey = mlCacheKey(srcUri, src.bitmap)
                var autoMaskNoteOut = ""
                var liquifyNoteOut = ""
                val target = renderMutex.withLock {
                    val existing = rendered
                    val bmp = if (existing == null || existing.width != w || existing.height != h) {
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    } else existing
                    withContext(Dispatchers.Default) {
                        val linear = src.linear
                        // 自动蒙版：首次真正需要时跑一次皮肤分割（失败返回 null → 自动回退画笔/整幅），
                        // 之后同一 key 命中 MlMaskProvider 缓存，不再重复推理。
                        val mlMask = if (autoOn) MlMaskProvider.skinMaskFor(context, src.bitmap, mlKey) else null
                        autoMaskNoteOut = when {
                            !autoOn -> ""
                            mlMask != null -> "自动蒙版已启用（${MlMaskProvider.accelerator ?: "?"}）"
                            else -> "自动蒙版不可用，已回退画笔/整幅"
                        }
                        val mask = buildSkinMask(w, h, strokes, radius, mlMask)
                        val renderRetouch = buildRenderRetouch(rt, w, h, inpStrokes, inpRadius)
                        // 液化锚点（P1p-2c）：**只在真的开了液化参数时**才跑检测 —— 没人碰美型滑块时
                        // 没必要多付一次推理。取最大的一张脸（`facesFor` 已按面积降序）。
                        val beautyOn = beautyActive(renderRetouch.beauty)
                        val faces = if (beautyOn) MlFaceProvider.facesFor(context, src.bitmap, mlKey) else null
                        val faceAnchor = faces?.firstOrNull()?.let {
                            RetouchLayer.FaceAnchor.fromDetection(it, src.bitmap.width, src.bitmap.height, w, h)
                        }
                        liquifyNoteOut = when {
                            !beautyOn -> ""
                            faces == null -> "液化锚点：蒙版质心（人脸检测不可用）"
                            faceAnchor == null -> "液化锚点：蒙版质心（未检测到人脸）"
                            else -> "液化锚点：人脸检测（${MlFaceProvider.accelerator ?: "?"}）· ${faces.size} 张脸"
                        }
                        if (linear != null) {
                            // 协作取消：renderJob 被 collectLatest 取消后，这里会在下一带边界退出。
                            // 注意：renderIntoLinear 内部已在物化目标 Bitmap 上跑过 retouch 整图 pass，
                            // 这里**不能**再调 RetouchLayer.apply，否则 RAW 预览会重复叠加（与导出不一致）。
                            EditEngine.renderIntoLinear(
                                bmp, linear, p, renderRetouch, mask, faceAnchor = faceAnchor
                            ) { !renderJob.isActive }
                        } else {
                            EditEngine.renderIntoSrgb(bmp, src.bitmap, p)
                            // 8-bit sRGB 路径的 renderIntoSrgb 不含 retouch，需在此补一趟整图 pass。
                            RetouchLayer.apply(bmp, renderRetouch, mask, faceAnchor)
                        }
                    }
                    bmp
                }
                // 渲染完成后再赋值并递增 stamp：renderInto* 是原位修改同一 Bitmap，
                // 不触发重组的话 Compose 会一直显示赋值时那一帧（预览空白）。
                rendered = target
                renderStamp++
                autoMaskNote = autoMaskNoteOut
                liquifyNote = liquifyNoteOut
                DebugLog.d(
                    DebugLog.TAG_EDIT, "render done",
                    mapOf("w" to target.width, "h" to target.height, "stamp" to renderStamp)
                )
            }
    }

    val openInEditor: (Uri) -> Unit = { uri ->
        DebugLog.i(DebugLog.TAG_IMPORT, "pick", mapOf("uri" to uri.toString()))
        scope.launch {
            loading = true
            status = EditorStatus(StatusKind.Info, "正在解码…")
            val dec = runCatching { Decoder.decodeToProxy(context, uri, profile.proxyLongEdge) }.getOrNull()
            loading = false
            if (dec == null) {
                status = EditorStatus(StatusKind.Error, "无法解码该文件")
                DebugLog.w(DebugLog.TAG_DECODE, "decode failed", mapOf("uri" to uri.toString()))
            } else {
                imported = dec
                srcUri = uri
                history.reset()
                // 换图：丢掉上一张的 ML 缓存（蒙版网格 + 人脸列表，均不含 Bitmap，代价极低）。
                MlMaskProvider.invalidate()
                MlFaceProvider.invalidate()
                params = EditParams()
                retouch = RetouchState()
                brushStrokes = emptyList()
                retouchTool = "none"
                inpaintStrokes = emptyList()
                inpaintRadius = 0.01f
                activePresetId = "none"
                autoMaskNote = ""
                liquifyNote = ""
                status = if (dec.linear != null) {
                    EditorStatus(StatusKind.Info, "RAW 已按 16-bit 线性管线载入")
                } else {
                    EditorStatus()
                }
                editorOpen = true
                DebugLog.i(
                    DebugLog.TAG_DECODE,
                    "decoded",
                    mapOf(
                        "w" to dec.width, "h" to dec.height, "raw" to dec.isRaw,
                        "mime" to dec.mime, "linear" to (dec.linear != null)
                    )
                )
            }
        }
    }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(openInEditor)
    }
    val arwLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(openInEditor)
    }

    if (editorOpen) {
        val src = imported
        // `val src = imported` 后必须再判一次 null：`imported` 是被多个 lambda 捕获并修改的
        // 局部 `var`，Kotlin 不允许对它做智能转换，只有拷进局部 val 才能安全解包。
        if (src != null) {
            // 编辑页**强制深色**：照片必须是页面上唯一的彩色主体（`docs/UI_DESIGN.md` §8 决策点 1）。
            PixelCakeWorkspaceTheme {
                EditorScreen(
                    original = src.bitmap,
                    rendered = rendered,
                    renderVersion = renderStamp,
                    params = params,
                    retouch = retouch,
                    retouchTool = retouchTool,
                    brushRadius = brushRadius,
                    inpaintRadius = inpaintRadius,
                    inpaintCount = inpaintStrokes.size,
                    autoMaskEnabled = autoMaskEnabled,
                    autoMaskNote = autoMaskNote,
                    liquifyNote = liquifyNote,
                    presets = Presets.ALL,
                    activePresetId = activePresetId,
                    presetThumbs = presetThumbs,
                    canUndo = history.canUndo,
                    canRedo = history.canRedo,
                    status = status,
                    exporting = exporting,
                    exportFormat = exportFormat,
                    onParamChange = {
                        // F08：拖动过程中只更新参数，不进撤销栈
                        params = it
                        if (activePresetId != "none") activePresetId = "none"
                    },
                    onParamCommit = { history.push(EditSnapshot(params, retouch)) },
                    onRetouchChange = {
                        retouch = it
                        if (activePresetId != "none") activePresetId = "none"
                    },
                    onRetouchCommit = { history.push(EditSnapshot(params, retouch)) },
                    onToolChange = { retouchTool = it },
                    onBrushStroke = { nx, ny ->
                        // 节流：与上一描迹太近则忽略；并设条数上限，防蒙版重建爆炸。
                        val last = brushStrokes.lastOrNull()
                        val far = last == null ||
                            (nx - last.first) * (nx - last.first) + (ny - last.second) * (ny - last.second) >= MIN_STROKE_DIST2
                        if (far && brushStrokes.size < MAX_BRUSH_STROKES) {
                            brushStrokes = brushStrokes + (nx to ny)
                        }
                    },
                    onBrushRadiusChange = { brushRadius = it },
                    onInpaintStroke = { nx, ny ->
                        if (inpaintStrokes.size < MAX_BRUSH_STROKES) inpaintStrokes = inpaintStrokes + (nx to ny)
                    },
                    onInpaintRadiusChange = { inpaintRadius = it },
                    onClearMask = { brushStrokes = emptyList() },
                    onClearInpaint = { inpaintStrokes = emptyList() },
                    onAutoMaskChange = { autoMaskEnabled = it },
                    onPreset = { p ->
                        params = p.params
                        retouch = p.retouch
                        activePresetId = p.id
                        history.push(EditSnapshot(params, retouch))
                    },
                    onReset = {
                        params = EditParams()
                        retouch = RetouchState()
                        brushStrokes = emptyList()
                        inpaintStrokes = emptyList()
                        activePresetId = "none"
                        history.push(EditSnapshot(params, retouch))
                    },
                    onUndo = {
                        if (history.undo()) {
                            params = history.current.params
                            retouch = history.current.retouch
                            activePresetId = "none"
                        }
                    },
                    onRedo = {
                        if (history.redo()) {
                            params = history.current.params
                            retouch = history.current.retouch
                            activePresetId = "none"
                        }
                    },
                    onExport = {
                        scope.launch {
                            val img = imported ?: return@launch
                            // 在主线程捕获 retouch/mask 状态，避免跨线程读快照状态
                            val rt = retouch
                            val strokes = brushStrokes
                            val radius = brushRadius
                            val inpStrokes = inpaintStrokes
                            val inpRadius = inpaintRadius
                            val autoOn = autoMaskEnabled
                            // 在主线程把格式/缓存键取出来：Dispatchers.Default 里不应读 Compose 快照状态
                            val fmt = exportFormat
                            val mlKey = mlCacheKey(srcUri, img.bitmap)
                            exporting = true
                            exportCancelled.set(false)
                            status = EditorStatus(StatusKind.Info, "正在生成导出…")
                            // A1 取证：导出起点（此刻蒙版/包围盒缓冲都还没分配）
                            DebugLog.i(DebugLog.TAG_EDIT, "export begin", memorySnapshot())
                            val result: Pair<Uri?, String> = withContext(Dispatchers.Default) {
                                val rawPath = img.rawCachePath
                                if (rawPath != null) {
                                    // 全分辨率 RAW：边解码边分带渲染，不把 196MB 线性图搬进堆
                                    val (ew, eh) = fitLongEdge(img.width, img.height, profile.fullResLongEdge)
                                    // 预览阶段的 ML 蒙版按 key 复用；resampleTo 只换尺寸、共享网格（无整幅分配）。
                                    val mlMask = if (autoOn) MlMaskProvider.skinMaskFor(context, img.bitmap, mlKey) else null
                                    val mask = buildSkinMask(ew, eh, strokes, radius, mlMask)
                                    val renderRetouch = buildRenderRetouch(rt, ew, eh, inpStrokes, inpRadius)
                                    // 液化锚点（P1p-2c）：预览阶段若已跑过检测，这里直接命中 MlFaceProvider 缓存
                                    // （同一 mlKey）⇒ 零成本；锚点按**导出分辨率**重新换算（与预览尺寸不同）。
                                    val faceAnchor = if (beautyActive(renderRetouch.beauty)) {
                                        MlFaceProvider.facesFor(context, img.bitmap, mlKey)?.firstOrNull()?.let {
                                            RetouchLayer.FaceAnchor.fromDetection(
                                                it, img.bitmap.width, img.bitmap.height, ew, eh
                                            )
                                        }
                                    } else null
                                    // A1 取证：耗峰前一刻（画笔栅格已建，液化条带与包围盒尚未分配）
                                    DebugLog.i(DebugLog.TAG_EDIT, "raw export pre-render", memorySnapshot())
                                    val full = EditEngine.renderLinearFile(
                                        path = rawPath,
                                        maxLongSide = profile.fullResLongEdge,
                                        p = params,
                                        retouch = renderRetouch,
                                        mask = mask,
                                        faceAnchor = faceAnchor
                                    ) { p ->
                                        if (p % 20 == 0 || p >= 100) {
                                            scope.launch(Dispatchers.Main) {
                                                status = EditorStatus(StatusKind.Info, "正在生成导出… $p%")
                                            }
                                        }
                                        !exportCancelled.get()
                                    }
                                    if (full == null) {
                                        null to "RAW 导出失败"
                                    } else {
                                        val out = withContext(Dispatchers.IO) {
                                            Exporter.export(context, full, fmt, 92)
                                        }
                                        full.recycle()
                                        out to "全分辨率 RAW"
                                    }
                                } else {
                                    var fullBase: DecodedImage? = null
                                    var fullTarget: Bitmap? = null
                                    try {
                                        fullBase = srcUri?.let {
                                            Decoder.decodeFullRes(context, it, profile.fullResLongEdge)
                                        }
                                        if (fullBase != null) {
                                            fullTarget = Bitmap.createBitmap(
                                                fullBase.bitmap.width, fullBase.bitmap.height,
                                                Bitmap.Config.ARGB_8888
                                            )
                                            EditEngine.renderIntoSrgb(fullTarget, fullBase.bitmap, params)
                                            // tonal 之后在已物化目标 Bitmap 上跑 retouch 整图 pass
                                            val fw = fullBase.bitmap.width
                                            val fh = fullBase.bitmap.height
                                            val fmlMask = if (autoOn) MlMaskProvider.skinMaskFor(context, img.bitmap, mlKey) else null
                                            val fmask = buildSkinMask(fw, fh, strokes, radius, fmlMask)
                                            val fretouch = buildRenderRetouch(rt, fw, fh, inpStrokes, inpRadius)
                                            // 液化锚点（P1p-2c）：同上，按导出分辨率换算
                                            val fAnchor = if (beautyActive(fretouch.beauty)) {
                                                MlFaceProvider.facesFor(context, img.bitmap, mlKey)?.firstOrNull()?.let {
                                                    RetouchLayer.FaceAnchor.fromDetection(
                                                        it, img.bitmap.width, img.bitmap.height, fw, fh
                                                    )
                                                }
                                            } else null
                                            // A1 取证：整幅 retouch 前一刻（目标 Bitmap + 画笔栅格都在堆上，
                                            // 紧接着 beautyPhase 还会再开两份整幅级缓冲 ⇒ 这里是最可能的爆点）
                                            DebugLog.i(DebugLog.TAG_EDIT, "srgb export pre-retouch", memorySnapshot())
                                            RetouchLayer.apply(fullTarget, fretouch, fmask, fAnchor)
                                            val out = withContext(Dispatchers.IO) {
                                                Exporter.export(context, fullTarget, fmt, 92)
                                            }
                                            out to "全分辨率"
                                        } else {
                                            val proxy = rendered ?: img.bitmap
                                            val out = withContext(Dispatchers.IO) {
                                                Exporter.export(context, proxy, fmt, 92)
                                            }
                                            out to "代理分辨率"
                                        }
                                    } finally {
                                        fullTarget?.recycle()
                                        fullBase?.bitmap?.recycle()
                                    }
                                }
                            }
                            val (exportedUri, label) = result
                            exporting = false
                            status = when {
                                exportCancelled.get() -> EditorStatus(StatusKind.Info, "已取消导出")
                                exportedUri != null ->
                                    EditorStatus(StatusKind.Success, "已导出（$label）：$exportedUri")
                                else -> EditorStatus(StatusKind.Error, "导出失败（$label）")
                            }
                            DebugLog.i(
                                DebugLog.TAG_EDIT, "export",
                                mapOf("ok" to (exportedUri != null), "tier" to label) + memorySnapshot()
                            )
                        }
                    },
                    onExportFormatChange = { exportFormat = it },
                    onCancelExport = {
                        exportCancelled.set(true)
                        status = EditorStatus(StatusKind.Info, "正在取消…")
                    },
                    onBack = {
                        rendered?.recycle()
                        rendered = null
                        ArwFullDecoder.releaseCache(src.rawCachePath)
                        // 退出编辑器：清掉 ML 缓存（下次打开重新推理，避免用错图）。
                        MlMaskProvider.invalidate()
                        MlFaceProvider.invalidate()
                        src.bitmap.recycle()
                        imported = null
                        srcUri = null
                        retouch = RetouchState()
                        brushStrokes = emptyList()
                        retouchTool = "none"
                        inpaintStrokes = emptyList()
                        inpaintRadius = 0.01f
                        activePresetId = "none"
                        autoMaskNote = ""
                        liquifyNote = ""
                        renderStamp = 0
                        editorOpen = false
                    }
                )
            }
        }
    } else {
        // 外壳：底部 2-Tab + 页面转场。编辑器**不套在这一层**（全屏工作台）。
        CompositionLocalProvider(LocalLowTransparency provides lowTransparency) {
            AppShell(current = tab, onSelect = { tab = it }) { currentTab ->
                when (currentTab) {
                    PixelCakeTab.Darkroom -> HomeScreen(
                        onImportPhoto = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onImportArw = { arwLauncher.launch(arrayOf("*/*")) },
                        loading = loading,
                        // 首屏状态是**纯文案**（无类别取色需求）→ 取 .text 即可。
                        message = status.text,
                        // P2：相机直连拉取的缓存文件（file:// 于本进程内可读，ARW 由后缀路由到线性管线）
                        onOpenLocalFile = { file -> openInEditor(Uri.fromFile(file)) }
                    )

                    PixelCakeTab.Settings -> SettingsScreen(
                        exportFormat = exportFormat,
                        onExportFormatChange = { exportFormat = it },
                        autoMaskEnabled = autoMaskEnabled,
                        onAutoMaskChange = { autoMaskEnabled = it },
                        lowTransparency = lowTransparency,
                        onLowTransparencyChange = { lowTransparency = it }
                    )
                }
            }
        }
    }
}

/** ML 蒙版缓存键：源图 uri + 预览位图尺寸。同一张图在预览与导出之间复用同一蒙版对象。 */
private fun mlCacheKey(uri: Uri?, bmp: Bitmap): String = "${uri ?: "-"}#${bmp.width}x${bmp.height}"

/**
 * 堆内存快照（审计 A1 的取证手段，零风险、先做）。
 *
 * 33MP 全分辨率导出的峰值是否真的逼近堆上限，**靠真机日志判断，不靠推算**。
 * 判读口径：
 * - 失败时 `heapPct` 已接近 100 ⇒ 真 OOM，需要按 A1 的方案削峰；
 * - 失败但 `heapPct` 明显偏低 ⇒ 另有原因（例如系统拒绝分配大 Bitmap、或 native 侧失败），
 *   此时去改 retouch 的内存纪律是白费功夫。
 *
 * `maxMB` 即堆上限（`largeHeap` 未开启时约等于 dalvik.vm.heapgrowthlimit）。
 */
private fun memorySnapshot(): Map<String, Any> {
    val r = Runtime.getRuntime()
    val max = (r.maxMemory() / 1_048_576).coerceAtLeast(1)
    val used = (r.totalMemory() - r.freeMemory()) / 1_048_576
    return mapOf(
        "usedMB" to used,
        "totalMB" to r.totalMemory() / 1_048_576,
        "maxMB" to max,
        "heapPct" to (used * 100 / max)
    )
}

/**
 * 编辑器侧的皮肤蒙版（P1p-1b）：委托 [RetouchScale.editorSkinMask]，与相机批处理共用**同一换算口径**。
 *
 * 合成口径（`docs/P1p_DESIGN.md` §7）：
 * - [autoMask] 为 `null`（自动蒙版关闭/不可用）：无描迹 ⇒ [com.hifn.pixelcake.core.edit.FullMask]
 *   （作用域 = 整幅，滑杆即有可见效果）；有描迹 ⇒ 画笔栅格；
 * - [autoMask] 非 `null`：无描迹 ⇒ ML 蒙版；有描迹 ⇒ `max(ML, 画笔)`。
 *
 * 返回 `null` 仅表示「尺寸非法」。皮肤类算子「不执行」由相机批量链路显式传 `mask = null` 表达，
 * 与这里的口径一致（都写在调用点）。
 */
private fun buildSkinMask(
    w: Int,
    h: Int,
    strokes: List<Pair<Float, Float>>,
    radiusNorm: Float,
    autoMask: RetouchMask? = null
): RetouchMask? = RetouchScale.editorSkinMask(w, h, strokes, radiusNorm, autoMask)

/** 把 UI 的 RetouchState 换算为渲染态：磨皮半径按短边比例 → 像素半径；UI 归一化祛瑕点 → 像素描迹。
 *  beauty / colorTransfer 已是分辨率无关参数，直接透传，保证预览/导出所见即所得。 */
private fun buildRenderRetouch(
    rt: RetouchState,
    w: Int,
    h: Int,
    inpaintStrokes: List<Pair<Float, Float>> = emptyList(),
    inpaintRadiusNorm: Float = 0.01f
): RetouchState {
    val minDim = min(w, h)
    val inpaint = inpaintStrokes.map { (nx, ny) ->
        val r = (inpaintRadiusNorm * minDim).toInt().coerceAtLeast(1)
        InpaintStroke((nx * w).toInt(), (ny * h).toInt(), r)
    }
    return rt.copy(
        neutralGray = NeutralGrayParams(
            strength = rt.neutralGray.strength,
            radiusPx = (rt.neutralGray.radiusNorm * minDim).toInt().coerceAtLeast(1),
            threshold = rt.neutralGray.threshold
        ),
        inpaint = inpaint
    )
}

/**
 * 预设缩略图（`docs/UI_DESIGN.md` §1.5 的 A 档）。
 *
 * ## 三个刻意的口径
 *
 * 1. **从原图渲染，与当前编辑无关** —— 缩略图回答「这个预设会把照片变成什么样」。
 *    若叠在当前编辑之上，用户每调一次参数全部缩略图都跟着变，参照系就没了。
 * 2. **只应用影调 + 磨皮 + 追色，丢掉液化与祛瑕**：几何形变需要人脸锚点，
 *    而缩略图阶段**不跑检测**（那是一次 GPU 推理，为 10 张 192px 小图付这个代价不值）。
 *    没有锚点时「蒙版质心猜」会把小图拧得很难看，反而失真 —— 那就不如不显示。
 * 3. **只在换图时算一次**（10 张 192×192，几十毫秒），不随参数变化重算。
 *
 * ## 尺寸与内存
 *
 * 中心方裁 + 缩放到 [PRESET_THUMB_PX]，**用 `Canvas` 画进全新位图**。
 * 不能用 `Bitmap.createBitmap(src, x, y, w, h)` 或 `createScaledBitmap` —— 当裁剪区域等于整幅 /
 * 目标尺寸等于原尺寸时，它们会**返回同一个实例**，后续 `setPixels` 会就地改掉用户的照片
 * （`ui/theme/Backdrop.kt` 踩过同一个坑，见那里的注释）。
 *
 * @return 预设 id → 缩略图；某一条渲染失败会被跳过（调用方按「缺缩略图」占位处理）。
 */
private fun buildPresetThumbs(base: Bitmap, presets: List<Preset>): Map<String, Bitmap> {
    if (base.width <= 0 || base.height <= 0) return emptyMap()
    val side = PRESET_THUMB_PX
    val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val crop = min(base.width, base.height)
    val left = (base.width - crop) / 2
    val top = (base.height - crop) / 2
    Canvas(square).drawBitmap(
        base,
        Rect(left, top, left + crop, top + crop),
        Rect(0, 0, side, side),
        Paint(Paint.FILTER_BITMAP_FLAG)
    )

    val out = LinkedHashMap<String, Bitmap>(presets.size)
    for (preset in presets) {
        val target = square.copy(Bitmap.Config.ARGB_8888, true) ?: continue
        // 影调：与预览/导出同一条 sRGB 管线。
        EditEngine.renderIntoSrgb(target, square, preset.params)
        // retouch：缩略图上作用域取**整幅**（FullMask）—— 预设的磨皮/追色必须可见，
        // 不能因为「没画蒙版」就整段跳过（那正是 mask = null 的语义）。
        val rt = buildRenderRetouch(
            preset.retouch.copy(beauty = BeautyParams(), inpaint = emptyList()),
            side, side
        )
        RetouchLayer.apply(target, rt, FullMask)
        out[preset.id] = target
    }
    square.recycle()
    return out
}

/** 按长边上限计算全分辨率目标尺寸（与 RawLinearSource 解码尺寸同口径）。 */
private fun fitLongEdge(srcW: Int, srcH: Int, longEdge: Int): Pair<Int, Int> {
    if (srcW <= 0 || srcH <= 0) return longEdge to longEdge
    return if (srcW >= srcH) {
        longEdge to (srcH * longEdge / srcW)
    } else {
        (srcW * longEdge / srcH) to longEdge
    }
}

/**
 * 液化参数是否**真的启用**（P1p-2c）。
 *
 * 用来决定「要不要为了液化锚点多跑一次人脸检测」：三个滑块都为 0 时
 * `RetouchLayer.beautyPhase` 会整段跳过，此时检测出来的锚点根本用不上，
 * 白花一次推理（Pixel 6 基准 GPU ≈71ms / CPU ≈218ms，大图更久）。
 */
private fun beautyActive(b: BeautyParams): Boolean =
    b.slimFace > 0f || b.slimJaw > 0f || b.eyeEnlarge > 0f
