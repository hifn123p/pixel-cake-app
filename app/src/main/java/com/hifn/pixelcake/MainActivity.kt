package com.hifn.pixelcake

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import java.util.concurrent.atomic.AtomicBoolean
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
import com.hifn.pixelcake.core.edit.EditEngine
import com.hifn.pixelcake.core.edit.EditHistory
import com.hifn.pixelcake.core.edit.EditParams
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

/** 滑块拖动时的重渲节流窗口（FIX_LIST F08）。16ms ≈ 一帧，肉眼无感但能挡掉绝大多数中间值。 */
private const val RENDER_THROTTLE_MS = 16L

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
    val exportCancelled = remember { AtomicBoolean(false) }
    val renderMutex = remember { Mutex() }
    var renderStamp by remember { mutableStateOf(0) }

    // 参数或导入变化 -> 异步把参数栈重渲到代理图（复用目标位图，避免每帧重分配）。
    // 用 snapshotFlow + conflate + collectLatest 做节流与取消：
    // 拖动过程中只保留最新一帧待渲，旧帧直接丢弃（FIX_LIST F08）。
    LaunchedEffect(imported) {
        val src = imported ?: return@LaunchedEffect
        snapshotFlow { params }
            .conflate()
            .collectLatest { p ->
                delay(RENDER_THROTTLE_MS)
                // 取当前这次重渲对应的协程 job：新参数到来时 collectLatest 会取消它，
                // 渲染器据此在下一个分带边界退出（真正的协作取消，FIX_LIST F08 修复）。
                val renderJob = currentCoroutineContext().job
                val w = src.linear?.width ?: src.bitmap.width
                val h = src.linear?.height ?: src.bitmap.height
                val target = renderMutex.withLock {
                    val existing = rendered
                    val bmp = if (existing == null || existing.width != w || existing.height != h) {
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    } else existing
                    withContext(Dispatchers.Default) {
                        val linear = src.linear
                        if (linear != null) {
                            // 协作取消：renderJob 被 collectLatest 取消后，这里会在下一带边界退出
                            EditEngine.renderIntoLinear(bmp, linear, p) { !renderJob.isActive }
                        } else {
                            EditEngine.renderIntoSrgb(bmp, src.bitmap, p)
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
            val dec = runCatching { Decoder.decodeToProxy(context, uri, profile.proxyLongEdge) }.getOrNull()
            if (dec == null) {
                status = "无法解码该文件"
                DebugLog.w(DebugLog.TAG_DECODE, "decode failed", mapOf("uri" to uri.toString()))
            } else {
                imported = dec
                srcUri = uri
                history.reset()
                params = EditParams()
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
                    canUndo = history.canUndo,
                    canRedo = history.canRedo,
                    status = status,
                    exporting = exporting,
                    onParamChange = {
                        // F08：拖动过程中只更新参数，不进撤销栈
                        params = it
                    },
                    onParamCommit = { history.push(params) },
                    onUndo = { if (history.undo()) params = history.current },
                    onRedo = { if (history.redo()) params = history.current },
                    onExport = {
                        scope.launch {
                            val img = imported ?: return@launch
                            exporting = true
                            exportCancelled.set(false)
                            status = "正在生成导出…"
                            val result: Pair<Uri?, String> = withContext(Dispatchers.Default) {
                                val rawPath = img.rawCachePath
                                if (rawPath != null) {
                                    // 全分辨率 RAW：边解码边分带渲染，不把 196MB 线性图搬进堆
                                    val full = EditEngine.renderLinearFile(
                                        path = rawPath,
                                        maxLongSide = profile.fullResLongEdge,
                                        p = params
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
                                            Exporter.export(context, full, ExportFormat.JPEG, 92)
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
                                            val out = withContext(Dispatchers.IO) {
                                                Exporter.export(context, fullTarget, ExportFormat.JPEG, 92)
                                            }
                                            out to "全分辨率"
                                        } else {
                                            val proxy = rendered ?: img.bitmap
                                            val out = withContext(Dispatchers.IO) {
                                                Exporter.export(context, proxy, ExportFormat.JPEG, 92)
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
            onImportArw = { arwLauncher.launch(arrayOf("*/*")) }
        )
    }
}
