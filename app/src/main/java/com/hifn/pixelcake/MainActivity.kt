package com.hifn.pixelcake

import android.graphics.Bitmap
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
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
import com.hifn.pixelcake.core.edit.BrushStroke
import com.hifn.pixelcake.core.edit.EditEngine
import com.hifn.pixelcake.core.edit.EditHistory
import com.hifn.pixelcake.core.edit.EditParams
import com.hifn.pixelcake.core.edit.EditSnapshot
import com.hifn.pixelcake.core.edit.InpaintStroke
import com.hifn.pixelcake.core.edit.NeutralGrayParams
import com.hifn.pixelcake.core.edit.RasterMask
import com.hifn.pixelcake.core.edit.RetouchState
import com.hifn.pixelcake.core.edit.preset.Preset
import com.hifn.pixelcake.core.edit.preset.Presets
import com.hifn.pixelcake.core.edit.retouch.RetouchLayer
import com.hifn.pixelcake.diag.DebugLog
import com.hifn.pixelcake.ui.editor.EditorScreen
import com.hifn.pixelcake.ui.home.HomeScreen
import com.hifn.pixelcake.ui.home.probeCapabilities
import com.hifn.pixelcake.ui.home.resolutionProfile
import com.hifn.pixelcake.ui.theme.PixelCakeTheme
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

    var screen by remember { mutableStateOf("home") }
    var imported by remember { mutableStateOf<DecodedImage?>(null) }
    var srcUri by remember { mutableStateOf<Uri?>(null) }
    val history = remember { EditHistory() }
    var params by remember { mutableStateOf(EditParams()) }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("") }
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

    // 参数 / retouch / 蒙版变化 -> 异步把参数栈 + retouch 重渲到代理图。
    // 用 snapshotFlow + conflate + collectLatest 做节流与取消（FIX_LIST F08）。
    LaunchedEffect(imported) {
        val src = imported ?: return@LaunchedEffect
        snapshotFlow { listOf(params, retouch, brushStrokes, inpaintStrokes) }
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
                val w = src.linear?.width ?: src.bitmap.width
                val h = src.linear?.height ?: src.bitmap.height
                val target = renderMutex.withLock {
                    val existing = rendered
                    val bmp = if (existing == null || existing.width != w || existing.height != h) {
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    } else existing
                    withContext(Dispatchers.Default) {
                        val linear = src.linear
                        val mask = buildSkinMask(w, h, strokes, radius)
                        val renderRetouch = buildRenderRetouch(rt, w, h, inpStrokes, inpRadius)
                        if (linear != null) {
                            // 协作取消：renderJob 被 collectLatest 取消后，这里会在下一带边界退出。
                            // 注意：renderIntoLinear 内部已在物化目标 Bitmap 上跑过 retouch 整图 pass，
                            // 这里**不能**再调 RetouchLayer.apply，否则 RAW 预览会重复叠加（与导出不一致）。
                            EditEngine.renderIntoLinear(bmp, linear, p, renderRetouch, mask) { !renderJob.isActive }
                        } else {
                            EditEngine.renderIntoSrgb(bmp, src.bitmap, p)
                            // 8-bit sRGB 路径的 renderIntoSrgb 不含 retouch，需在此补一趟整图 pass。
                            RetouchLayer.apply(bmp, renderRetouch, mask)
                        }
                    }
                    bmp
                }
                // 渲染完成后再赋值并递增 stamp：renderInto* 是原位修改同一 Bitmap，
                // 不触发重组的话 Compose 会一直显示赋值时那一帧（预览空白）。
                rendered = target
                renderStamp++
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
            status = "正在解码…"
            val dec = runCatching { Decoder.decodeToProxy(context, uri, profile.proxyLongEdge) }.getOrNull()
            loading = false
            if (dec == null) {
                status = "无法解码该文件"
                DebugLog.w(DebugLog.TAG_DECODE, "decode failed", mapOf("uri" to uri.toString()))
            } else {
                imported = dec
                srcUri = uri
                history.reset()
                params = EditParams()
                retouch = RetouchState()
                brushStrokes = emptyList()
                retouchTool = "none"
                inpaintStrokes = emptyList()
                inpaintRadius = 0.01f
                activePresetId = "none"
                status = if (dec.linear != null) "RAW 已按 16-bit 线性管线载入" else ""
                screen = "editor"
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

    when (screen) {
        "editor" -> {
            val src = imported
            if (src != null) {
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
                    presets = Presets.ALL,
                    activePresetId = activePresetId,
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
                            // 在主线程把格式取出来：Dispatchers.Default 里不应读 Compose 快照状态
                            val fmt = exportFormat
                            exporting = true
                            exportCancelled.set(false)
                            status = "正在生成导出…"
                            val result: Pair<Uri?, String> = withContext(Dispatchers.Default) {
                                val rawPath = img.rawCachePath
                                if (rawPath != null) {
                                    // 全分辨率 RAW：边解码边分带渲染，不把 196MB 线性图搬进堆
                                    val (ew, eh) = fitLongEdge(img.width, img.height, profile.fullResLongEdge)
                                    val mask = buildSkinMask(ew, eh, strokes, radius)
                                    val renderRetouch = buildRenderRetouch(rt, ew, eh, inpStrokes, inpRadius)
                                    val full = EditEngine.renderLinearFile(
                                        path = rawPath,
                                        maxLongSide = profile.fullResLongEdge,
                                        p = params,
                                        retouch = renderRetouch,
                                        mask = mask
                                    ) { p ->
                                        if (p % 20 == 0 || p >= 100) {
                                            scope.launch(Dispatchers.Main) { status = "正在生成导出… $p%" }
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
                                            val fmask = buildSkinMask(fw, fh, strokes, radius)
                                            val fretouch = buildRenderRetouch(rt, fw, fh, inpStrokes, inpRadius)
                                            RetouchLayer.apply(fullTarget, fretouch, fmask)
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
                                exportCancelled.get() -> "已取消导出"
                                exportedUri != null -> "已导出（$label）：$exportedUri"
                                else -> "导出失败（$label）"
                            }
                            DebugLog.i(
                                DebugLog.TAG_EDIT, "export",
                                mapOf("ok" to (exportedUri != null), "tier" to label)
                            )
                        }
                    },
                    onExportFormatChange = { exportFormat = it },
                    onCancelExport = {
                        exportCancelled.set(true)
                        status = "正在取消…"
                    },
                    onBack = {
                        rendered?.recycle()
                        rendered = null
                        ArwFullDecoder.releaseCache(src.rawCachePath)
                        src.bitmap.recycle()
                        imported = null
                        srcUri = null
                        retouch = RetouchState()
                        brushStrokes = emptyList()
                        retouchTool = "none"
                        inpaintStrokes = emptyList()
                        inpaintRadius = 0.01f
                        activePresetId = "none"
                        renderStamp = 0
                        screen = "home"
                    }
                )
            } else {
                screen = "home"
            }
        }
        else -> HomeScreen(
            onImportPhoto = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onImportArw = { arwLauncher.launch(arrayOf("*/*")) },
            loading = loading,
            message = status,
            // P2：相机直连拉取的缓存文件（file:// 于本进程内可读，ARW 由后缀路由到线性管线）
            onOpenLocalFile = { file -> openInEditor(Uri.fromFile(file)) }
        )
    }
}

/** 由画笔描迹（归一化坐标 + 归一化半径）构建皮肤蒙版，尺寸对齐目标图 (w,h)。 */
private fun buildSkinMask(w: Int, h: Int, strokes: List<Pair<Float, Float>>, radiusNorm: Float): RasterMask? {
    if (strokes.isEmpty() || w <= 0 || h <= 0) return null
    val minDim = min(w, h)
    val r = (radiusNorm * minDim).toInt().coerceAtLeast(1)
    val bs = strokes.map { (nx, ny) -> BrushStroke((nx * w).toInt(), (ny * h).toInt(), r) }
    return RasterMask.fromStrokes(w, h, bs)
}

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

/** 按长边上限计算全分辨率目标尺寸（与 RawLinearSource 解码尺寸同口径）。 */
private fun fitLongEdge(srcW: Int, srcH: Int, longEdge: Int): Pair<Int, Int> {
    if (srcW <= 0 || srcH <= 0) return longEdge to longEdge
    return if (srcW >= srcH) longEdge to (srcH * longEdge / srcW)
    else (srcW * longEdge / srcH) to longEdge
}
