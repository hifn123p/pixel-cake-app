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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    val renderMutex = remember { Mutex() }

    // 参数或导入变化 -> 异步把参数栈重渲到代理图（复用目标位图，避免每帧重分配）
    LaunchedEffect(params, imported) {
        val src = imported ?: return@LaunchedEffect
        val target = rendered ?: run {
            val b = Bitmap.createBitmap(src.bitmap.width, src.bitmap.height, Bitmap.Config.ARGB_8888)
            rendered = b
            b
        }
        renderMutex.withLock {
            withContext(Dispatchers.Default) { EditEngine.renderInto(target, src.bitmap, params) }
        }
        DebugLog.d(DebugLog.TAG_EDIT, "render done", mapOf("w" to target.width, "h" to target.height))
    }

    val openInEditor: (Uri) -> Unit = { uri ->
        DebugLog.i(DebugLog.TAG_IMPORT, "pick", mapOf("uri" to uri.toString()))
        scope.launch {
            val dec = Decoder.decodeToProxy(context, uri, profile.proxyLongEdge)
            if (dec == null) {
                status = "无法解码该文件"
                DebugLog.w(DebugLog.TAG_DECODE, "decode failed", mapOf("uri" to uri.toString()))
            } else {
                imported = dec
                srcUri = uri
                history.reset()
                params = EditParams()
                status = if (dec.isRaw) "ARW 当前仅预览（全量修图待 P1b 开放）" else ""
                screen = "editor"
                DebugLog.i(
                    DebugLog.TAG_DECODE,
                    "decoded",
                    mapOf("w" to dec.width, "h" to dec.height, "raw" to dec.isRaw, "mime" to dec.mime)
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
                    params = params,
                    canUndo = history.canUndo,
                    canRedo = history.canRedo,
                    status = status,
                    onParamChange = {
                        params = it
                        history.push(it)
                    },
                    onUndo = { if (history.undo()) params = history.current },
                    onRedo = { if (history.redo()) params = history.current },
                    onExport = {
                        scope.launch {
                            val img = imported ?: return@launch
                            val uri = srcUri
                            status = "正在生成导出…"
                            val result = withContext(Dispatchers.Default) {
                                var fullBase: DecodedImage? = null
                                var fullTarget: Bitmap? = null
                                try {
                                    if (uri != null) fullBase = Decoder.decodeFullRes(context, uri, profile.fullResLongEdge)
                                    if (fullBase != null) {
                                        fullTarget = Bitmap.createBitmap(
                                            fullBase.bitmap.width, fullBase.bitmap.height, Bitmap.Config.ARGB_8888
                                        )
                                        EditEngine.renderInto(fullTarget, fullBase.bitmap, params)
                                        val out = withContext(Dispatchers.IO) {
                                            Exporter.export(context, fullTarget, ExportFormat.JPEG, 92)
                                        }
                                        val label = if (fullBase.isRaw) "ARW 预览分辨率" else "全分辨率"
                                        out to label
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
                            val (exportedUri, label) = result
                            status = if (exportedUri != null) "已导出（$label）：$exportedUri" else "导出失败"
                            DebugLog.i(DebugLog.TAG_EDIT, "export", mapOf("ok" to (exportedUri != null), "tier" to label))
                        }
                    },
                    onBack = {
                        rendered?.recycle()
                        rendered = null
                        src.bitmap.recycle()
                        imported = null
                        srcUri = null
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
