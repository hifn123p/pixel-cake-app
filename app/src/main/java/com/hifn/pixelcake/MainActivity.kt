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
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.hifn.pixelcake.core.edit.BeautyParams
import com.hifn.pixelcake.core.edit.EditEngine
import com.hifn.pixelcake.core.edit.EditHistory
import com.hifn.pixelcake.core.edit.EditParams
import com.hifn.pixelcake.core.edit.EditSnapshot
import com.hifn.pixelcake.core.edit.FullMask
import com.hifn.pixelcake.core.edit.InpaintStroke
import com.hifn.pixelcake.core.edit.NeutralGrayParams
import com.hifn.pixelcake.core.edit.ObjectLayer
import com.hifn.pixelcake.core.edit.RetouchMask
import com.hifn.pixelcake.core.edit.RetouchScale
import com.hifn.pixelcake.core.edit.RetouchState
import com.hifn.pixelcake.core.edit.buildLayerStack
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
import com.hifn.pixelcake.ui.settings.AppSettings
import com.hifn.pixelcake.ui.settings.SettingsScreen
import com.hifn.pixelcake.ui.settings.rememberAppSettings
import com.hifn.pixelcake.ui.shell.AppShell
import com.hifn.pixelcake.ui.shell.PixelCakeTab
import com.hifn.pixelcake.ui.theme.LocalLowTransparency
import com.hifn.pixelcake.ui.theme.PixelCakeTheme
import com.hifn.pixelcake.ui.theme.PixelCakeWorkspaceTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** 滑块拖动时的重渲节流窗口（PHASE_DESIGN_HISTORY.md（审查台账） F08）。16ms ≈ 一帧，肉眼无感但能挡掉绝大多数中间值。 */
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
            // ⚠️ 设置对象必须建在**主题包装之外**：主题模式本身是一个设置项，
            // 主题要读它 ⇒ 它不能是主题的后代（否则「改主题要重组主题」形成循环依赖的写法）。
            val settings = rememberAppSettings()
            PixelCakeTheme(darkTheme = settings.themeMode.isDark(isSystemInDarkTheme())) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(settings = settings)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = remember { context.probeCapabilities().resolutionProfile() }

    var tab by remember { mutableStateOf(PixelCakeTab.Darkroom) }
    // 编辑器是否打开。**刻意不用第三个 Tab**：编辑是全屏工作台，进入后 TabBar 直接消失，
    // 把画面整块交给预览区（`docs/UI_DESIGN.md` §3.3）。
    var editorOpen by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<DecodedImage?>(null) }
    var srcUri by remember { mutableStateOf<Uri?>(null) }
    val history = remember { EditHistory() }
    var params by remember { mutableStateOf(EditParams()) }
    // 对象作用域图层（批次 5，`docs/OBJECT_TONE_DESIGN.md` §4）：每作用域至多一层，
    // 唯一性由 `upsert` / `without` 保证。与 `params` / `retouch` **并列** ——
    // 它是编辑的一部分（进 `EditSnapshot`，撤销会一起回退），不是工具选择态。
    var layers by remember { mutableStateOf(emptyList<ObjectLayer>()) }
    // 对象识别是否可用（模型是否加载成功）。作用域 chip 行据此**逐个禁用** 8 个对象作用域 ——
    // 见 `ParamScopeBar` 的 KDoc：让用户点进一个注定不生效的作用域，是本批最不能犯的错。
    var objectScopesAvailable by remember { mutableStateOf(false) }

    // 「图片代次」：每次导入新图 / 退出编辑器都 +1。
    //
    // ## 它解决的是什么
    //
    // 渲染协程是**异步**的（几百毫秒），而「退出编辑器」「换图」是主线程上的一次性动作。
    // 两者重叠时，已经算完但还没写回的那一批会把**上一张照片**的结果写进 `rendered` ——
    // 这比崩溃更隐蔽：用户退出后再进来，先看到的是刚关掉的那张图。
    //
    // ⚠️ 别指望「协程取消」能兜住这里：取消只在**挂起点**生效，而
    // `rendered = target` 这类赋值恰好不是挂起点 —— 协程被取消后仍会把它执行完。
    // 所以必须有一个显式的、由主线程推进的标记来作废在途批次。
    // （第七轮起渲染协程改用 `collect` 收集快照，能取消它的只剩「换图 / 退出编辑器」这一条路；
    //  那条路的取消同样**不保证**在赋值前生效 ⇒ 本标记一步都不能省。见渲染协程的注释。）
    //
    // 用 `AtomicInteger` 而不是 `var by mutableStateOf`：它是纯控制位，不需要驱动任何重组；
    // 而放进 Compose 快照会让「在 Default 线程上读它」变成一件需要留神的事。
    //
    // ⚠️ **声明必须在本函数的靠前位置**：下面的对象作用域预热 `LaunchedEffect` 会读它。
    // 局部变量按声明顺序可见，放在后面会得到 `Unresolved reference 'imageEpoch'`。
    val imageEpoch = remember { AtomicInteger(0) }

    // 对象作用域的可用性**预热**（批次 5）。
    //
    // ## 为什么要在用户点开之前就探测
    //
    // 作用域 chip 行必须先知道「能不能用对象」才能决定禁用与否；若等到用户点了某个作用域再探测，
    // 就必然出现「点进去 → 转一下 → 弹出不可用」这种把失败当流程的交互。
    //
    // ## 为什么这次预热的代价可以忽略
    //
    // `MlMaskProvider` 按 key 缓存的是**原始 6 类概率**，皮肤蒙版与对象蒙版都从它构造
    // ⇒ 这次预热的产物会被之后的预览/导出渲染**直接复用**，不会重复推理。
    //
    // ## 为什么放在**独立**的 `LaunchedEffect` 里，而不是塞进渲染协程
    //
    // 渲染协程的首帧是用户真正在等的东西（几百毫秒），不该被这次探测（GPU ≈70ms / CPU ≈218ms）
    // 推后。两者并发进入时，`MlMaskProvider` 的 `@Synchronized` 会把它们串行化，
    // 后到的那个命中缓存 —— 无论谁先到，结果都只有一次推理。
    LaunchedEffect(imported) {
        val src = imported ?: return@LaunchedEffect
        objectScopesAvailable = false
        val key = mlCacheKey(srcUri, src.bitmap.width, src.bitmap.height, imageEpoch.get())
        val ok = withContext(Dispatchers.Default) {
            MlMaskProvider.objectMasksFor(context, src.bitmap, key) != null
        }
        objectScopesAvailable = ok
        DebugLog.i(DebugLog.TAG_ML, "object scopes probed", mapOf("available" to ok))
    }

    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    // 双缓冲的第二块（长注释见渲染协程里选写入目标的那一段）：
    // `rendered` = 「正在被显示的那一块」，对渲染协程**只读**；`renderSpare` = 「下一批渲染的
    // 写入目标」。两者**永远不是同一个对象** —— 这条不变量就是「拖动时预览不会被写花」的全部依据。
    // 稳态下两块互相轮换 ⇒ 零额外分配；为了省内存去复用同一块的旧做法，代价正是屏幕上那根撕裂的横线。
    //
    // ⚠️ 一个仍然存在的边界（今天不打算在本次修复里动它）：导出时那条「拿不到全分辨率 ⇒
    // 直接把 `rendered` 交给编码器」的降级分支仍会读到前台缓冲。双缓冲只把它被改写的时间
    // 推后了一批（不再是「每批都写」），并没有让那条路径变安全 —— 但它**不会比改之前更差**。
    var renderSpare by remember { mutableStateOf<Bitmap?>(null) }
    // 状态行：**类别 + 文案一起**存（审计 M2）。UI 侧按 kind 取色，不再拿文案做判断。
    var status by remember { mutableStateOf(EditorStatus()) }
    var exporting by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val exportCancelled = remember { AtomicBoolean(false) }
    val renderMutex = remember { Mutex() }
    var renderStamp by remember { mutableStateOf(0) }

    // 「按住看原图」的对比基准 = **零编辑渲染图**（不是解码预览图）。
    // 为什么必须是它、而不是 `src.bitmap`，见 `EditorScreen` 的 compareBase 文档。
    var compareBase by remember { mutableStateOf<Bitmap?>(null) }
    // 本次导入是否还没留基线。抓一次就够，抓完置 false（避免每帧都复制一张代理图）。
    var baselinePending by remember { mutableStateOf(false) }

    // ⚠️ 设置项**不要**先取成局部 `val` 再用：
    // `snapshotFlow { ... }` 靠「读快照状态」来订阅变化，读一个普通局部变量是**不可观察**的，
    // 会让「在设置页改自动蒙版 → 编辑器不重渲」这类问题静默出现。所以这里一律直接读 `settings.*`。
    // （`params` / `retouch` 这些局部 `var by mutableStateOf` 不受影响：读它们走的是委托的 getter，
    // 仍然是一次快照读。）

    // P1b-4：人像精修状态（tonal 的 EditParams 之外的附加层）
    var retouch by remember { mutableStateOf(RetouchState()) }
    var retouchTool by remember { mutableStateOf("none") }
    var brushRadius by remember { mutableStateOf(0.04f) }
    var brushStrokes by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    var inpaintRadius by remember { mutableStateOf(0.01f) }
    var inpaintStrokes by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    // 当前生效的预设 id（用于 UI 高亮；用户手动改动任一参数即清空）
    var activePresetId by remember { mutableStateOf("none") }
    // P1p-1b：自动蒙版（AI 皮肤识别）的开关已上移到 `AppSettings.autoMask`（设置页与编辑页共用
    // 同一个状态，并持久化）。这里不再有局部副本 —— 两份状态必然漂移。
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
        // ⚠️ 与主渲染协程**共用同一把 `renderMutex`**：两者都在读 `src.bitmap` 的像素，
        // 而退出编辑器时会有一方在同一把锁内回收 `rendered`/`compareBase`（`src.bitmap` 本身
        // 已改为交 GC，见 `onBack`）。把「所有位图像素读写的持有者」都收进一把锁，
        // 是为了让「回收时没有读者」这件事**可以证明**，而不是靠「窗口很小」来赌。
        //
        // 代价是首屏「预设缩略图」与「首帧预览」不再重叠（缩略图约几十毫秒），
        // 换来的是一条能在代码里被验证的性质 —— 这个交换在崩溃风险面前是划算的。
        presetThumbs = renderMutex.withLock {
            withContext(Dispatchers.Default) {
                buildPresetThumbs(src.bitmap, Presets.ALL)
            }
        }
        DebugLog.i(
            DebugLog.TAG_EDIT, "preset thumbs ready",
            mapOf("count" to presetThumbs.size, "px" to PRESET_THUMB_PX)
        )
    }

    // 参数 / retouch / 蒙版 / 自动蒙版开关变化 -> 异步把参数栈 + retouch 重渲到代理图。
    // 用 snapshotFlow + conflate 做节流（PHASE_DESIGN_HISTORY.md（审查台账） F08）。
    //
    // ⚠️ **刻意用 `collect` 而不是 `collectLatest`**（2026-10-01 第七轮真机反馈修）。
    //
    // `collectLatest` 会在**每一次**新快照到来时取消正在跑的那一帧，而拖滑块时 UI 以
    // 60~120Hz 连发快照 ⇒ 一帧 100~300ms 的重渲**几乎永远跑不到一半就被砍掉**。
    // 它带来两个后果，正好对应两条真机症状：
    //   1. 被砍掉的帧在目标位图上留下的是**写了一半**的分带结果（只写到第 k 带）；
    //      这样一个半成品只要被提交上屏，就是「上下一半颜色不一致的横线」，
    //      而每帧 k 都不同 ⇒ 拖动时持续**闪动**、看着像有**扫描条纹**穿过画面；
    //   2. 算力全花在「启动 → 被砍」的循环上，真正完整的帧一张都出不来 ⇒ **越拖越卡**。
    //
    // 换成 `collect` 后：`conflate()` 只保留**最新**一次快照（不排队、不积压），
    // 而**已经在跑的那一帧一定跑完** ⇒ 每张上屏的都是完整帧。
    // 重渲吞吐也从「~60 次/秒、全部作废」变成「~1/renderTime、次次有效」。
    //
    // 取消因此只剩一个正当用途：**整张图被换掉**（新导入 / 退出编辑器）—— 见下面的 `renderJob`。
    LaunchedEffect(imported) {
        val src = imported ?: return@LaunchedEffect
        // 注意 `brushRadius` / `inpaintRadius` 必须**在监听列表里**：它们参与蒙版/祛瑕的
        // 像素半径换算（见 buildRenderRetouch），漏掉会导致「只拖半径滑块，预览不跟随」——
        // 蒙版仍按旧半径渲染，要等下一次别的参数变化才刷新（自愈，但严格说是所见非所得）。
        snapshotFlow {
            listOf(
                // ⚠️ `settings.autoMask` 必须在这里**直接读**（属性读 = 快照读）。
                // 先取成局部 val 再用的话，snapshotFlow 看不到变化，设置页改了开关编辑器不会重渲。
                //
                // ⚠️ `layers`（批次 5）同理必须在列表里：它是**编辑的一部分**，
                // 漏掉的后果不是「不重渲」，而是「拖对象层的滑块画面完全不动」——
                // 滑块自己会动、数值也在变，只有照片不变。那比崩溃更难排查。
                params, retouch, layers, brushStrokes, inpaintStrokes, settings.autoMask,
                brushRadius, inpaintRadius
            )
        }
            .conflate()
            .collect {
                // `delay` 在这里**不是**节流阀（节流由上面的 `conflate` 负责）：它是「让 UI 先落定」。
                // 下面读的所有状态都在 `delay` **之后**，所以每次开跑用的**一定是最新快照**，
                // 不会拿着一个已经过期的值去跑几百毫秒的整帧。
                delay(RENDER_THROTTLE_MS)
                // 取本协程的 job：它只在**整张图被换掉**时失效（`imported` 变了 ⇒ `LaunchedEffect`
                // 重启；或退出编辑器）。渲染器据此在下一个分带边界退出（协作取消，PHASE_DESIGN_HISTORY.md（审查台账） F08）。
                // ⚠️ 参数变化**不会**让它失效 —— 这正是上面改用 `collect` 的目的。
                val renderJob = currentCoroutineContext().job
                // 在主线程捕获最新状态，避免在 Dispatchers.Default 内跨线程读快照状态
                val p = params
                val rt = retouch
                val layerList = layers
                val strokes = brushStrokes
                val radius = brushRadius
                val inpStrokes = inpaintStrokes
                val inpRadius = inpaintRadius
                val autoOn = settings.autoMask
                // 取一次代次留到出锁后比对。位置刻意放在「已读完源图状态、尚未进入临界区」：
                // 只要期间有人推进了代次（退出编辑器 / 换图），这一批就该被整批丢弃。
                val epochAtStart = imageEpoch.get()
                val w = src.linear?.width ?: src.bitmap.width
                val h = src.linear?.height ?: src.bitmap.height
                // ML 蒙版缓存键：同一张图在预览与导出之间复用同一蒙版对象（P1p-1b）。
                // ⚠️ 尺寸必须用 `src.bitmap` 的，**不能**用上面的 `w`/`h` —— 那两个在 RAW 路径下
                // 是**线性代理尺寸**（`src.linear` 的），与导入探测 / 导出路径传的键不同
                // ⇒ 会把「同一张图」拆成两个键，缓存复用失效（每次导出重跑一遍推理）。
                // ⚠️ 代次用 `epochAtStart` 而不是 `imageEpoch.get()`：后者在换图期间已被推进，
                // 会把「上一张图的这一帧」算到新会话的键上。
                val mlKey = mlCacheKey(srcUri, src.bitmap.width, src.bitmap.height, epochAtStart)
                var autoMaskNoteOut = ""
                var liquifyNoteOut = ""
                val batch = try {
                    renderMutex.withLock {
                        // ⚠️ 写入目标**绝不能**是正在被显示的那一块（`rendered`）。
                        //
                        // 这条约束比「省内存」硬得多。写入的是**分带**结果：`EditEngine` 每写完一带就
                        // `Bitmap.setPixels` 一次，而 `setPixels` 会推进位图的 generation id ——
                        // 正在显示的位图每被推进一次，合成器就会**重新上传一次纹理**。
                        // 于是屏幕上出现的是一张**写了一半**的图：当前带的边界就是那根
                        // 「上下颜色不一致的横线」，而每一批渲染它都从顶部重新扫下来，
                        // 拖动时于是持续闪动（真机反馈：「预览窗口有一根横线，上下颜色不一致，还会闪动」）。
                        //
                        // 双缓冲让这件事**结构性地不可能发生**：`rendered` 只读、`renderSpare` 只写，
                        // 算完再一次性换过来。稳态下**零额外分配**（两块互相轮换），
                        // 所以「为了省 11MB/批 的 GC 去复用同一块」这个旧做法没有任何必要 ——
                        // 它换来的正是上面那根横线。
                        //
                        // ⚠️ 被换下来的那块要等到**下一批**渲染才会被写，而那时它已经离开画面至少一帧，
                        // 合成器对它的最后一次读取早已结束 ⇒ 连「刚换下来就被写」的窗口也不存在。
                        val displayed = rendered
                        val spare = renderSpare
                        val bmp = if (spare != null && spare.width == w && spare.height == h && spare !== displayed) {
                            spare
                        } else {
                            // 尺寸不符（换图）或还没有备用缓冲 ⇒ 新建一块。
                            // 旧的那块**不手动回收**：它可能刚离开画面、合成器仍持有它，
                            // 而这个分支一张图最多走一次（约 11MB）—— 丢掉交 GC，
                            // 与 `src.bitmap` 同一个口径（见 `onBack` 的说明）。
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        }
                        // 单帧渲染耗时（诊断用，零风险）：真机上「滑参数卡不卡」需要的是一条**数字**，
                        // 不是感觉。它跟着这一批走（见 `RenderBatch.ms`），出锁后连同 stamp 一起打点。
                        val renderT0 = System.nanoTime()
                        // ⚠️ 必须接住返回值：`false` = **这张位图没画完**，绝不能当成品提交。
                        val completed = withContext(Dispatchers.Default) {
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
                            // 对象作用域图层（批次 5）：**只在真有层时才去取蒙版** ——
                            // 没有层的用户（绝大多数）在这里零额外开销，一个作用域网格都不会被构建。
                            // `objectMasksFor` 与上面的 `skinMaskFor` 共用同一份概率缓存 ⇒ 只推理一次。
                            val objMasks = if (layerList.isEmpty()) {
                                null
                            } else {
                                MlMaskProvider.objectMasksFor(context, src.bitmap, mlKey)
                            }
                            // `buildLayerStack` 会剔掉「强度 0 / 参数全中性 / 拿不到蒙版」的层，
                            // 并且按**目标尺寸**建好每层的程序与蒙版；全被剔掉时返回 EMPTY
                            // ⇒ 逐像素路径退回「一次 applySrgb8」，与批次 4 完全一致。
                            val layerStack = buildLayerStack(layerList, { sc -> objMasks?.maskFor(sc) }, w, h)
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
                                // 协作取消：`renderJob` 失效（换图 / 退出编辑器）时，这里会在下一带边界退出。
                                // ⚠️ 它的返回值**必须**往上传 —— `false` 意味着这张位图只写了前 k 带，
                                // 是**半成品**，绝不能当成品提交（见下面 `RenderBatch.completed`）。
                                // 注意：renderIntoLinear 内部已在物化目标 Bitmap 上跑过 retouch 整图 pass，
                                // 这里**不能**再调 RetouchLayer.apply，否则 RAW 预览会重复叠加（与导出不一致）。
                                EditEngine.renderIntoLinear(
                                    bmp, linear, p, renderRetouch, mask,
                                    faceAnchor = faceAnchor, layers = layerStack
                                ) { !renderJob.isActive }
                            } else {
                                // ⚠️ 人像精修已经**收进** renderIntoSrgb：阶段顺序必须是
                                // `调色 → 精修 → 细节`，与两条 RAW 入口同序。
                                // 以前这里是「调色 → 细节」再由调用方补一趟精修，等于
                                // 「先锐化再磨皮」—— 磨皮把刚锐出的边缘糊掉，锐化量程被吃掉一半。
                                // 返回值必须接住：尺寸不匹配 = 这一帧不是成品。
                                EditEngine.renderIntoSrgb(
                                    bmp, src.bitmap, p, renderRetouch, mask, faceAnchor, layerStack
                                )
                            }
                        }
                        // `reused` 用引用相等判定，不重算一遍尺寸条件：回收决策完全依赖
                        // 「这张位图是不是借来的」，重算等于多一处可能与上面那次判断不一致的地方。
                        // 借来的那一块是双缓冲的备用块（`renderSpare`），**归属权不在本协程**。
                        RenderBatch(
                            bmp = bmp,
                            reused = bmp === spare,
                            epoch = epochAtStart,
                            completed = completed,
                            ms = (System.nanoTime() - renderT0) / 1_000_000
                        )
                        }
                } catch (c: CancellationException) {
                    // 协程被取消（换图 / 退出编辑器）不是错误，必须原样抛出。
                    throw c
                } catch (t: Throwable) {
                    // ⚠️ 这一段里 `Bitmap.createBitmap`（11MB 级）、ML 推理、`EditEngine.renderInto*`、
                    // `RetouchLayer.apply`、`target.copy` 全都可能抛 —— 33MP 分配失败是 `OutOfMemoryError`
                    // （一个 `Error`，不是 `Exception`）。以前这里**没有兜底**，异常会取消整个
                    // `LaunchedEffect(imported)` ⇒ 这张图之后再也不重渲，界面停在旧帧上、状态栏无任何提示，
                    // 只能退回再进来。而 `CameraBatch` 早有 `catch Throwable`，两边口径不一致。
                    DebugLog.e(
                        DebugLog.TAG_EDIT, "render batch failed",
                        mapOf("err" to (t.message ?: t.javaClass.simpleName), "epoch" to epochAtStart)
                    )
                    status = EditorStatus(
                        StatusKind.Error,
                        "渲染失败（${t.javaClass.simpleName}），可退回后重试或换一张照片"
                    )
                    return@collect
                }
                // ⚠️ 出锁后的第一件事是**确认这一批还算不算一帧成品**。两个条件缺一不可。
                //
                // 1. `batch.epoch != imageEpoch.get()` —— 「没人要了」：用户完全可能在渲染的这几百
                //    毫秒里退出了编辑器或换了图，那样写回去就等于让用户先看到上一张照片
                //    （这比崩溃更隐蔽，因为它看起来只是「慢了半拍」）。
                // 2. `!batch.completed` —— 「压根没画完」（2026-10-01 第七轮真机反馈修）。
                //    这是**两个不同的维度**，过去只判了第 1 个，于是把半成品当了成品：
                //    `renderIntoLinear` 被取消时是**正常返回 `false`**（不是抛异常），
                //    那一帧只写了前 k 带、下半截还是上一张照片的像素。它一上屏就是
                //    「上下颜色不一致的横线」，而每帧 k 不同 ⇒ 拖动时闪动、像有扫描条纹。
                //    **判据必须取渲染器自报的完成位**，不能拿「这一批有没有被取消」反推 ——
                //    `imageEpoch` 跟踪的是**图片**代次，拖参数它一次都不会变。
                if (!batch.completed || batch.epoch != imageEpoch.get()) {
                    // 丢弃这一批。⚠️ 回收规则是**不对称**的，别写反：
                    // - `reused == false`：这张位图是本次新建、只有本协程持有 ⇒ 必须自己回收，
                    //   否则「拖完滑块立刻点返回」每来一次就漏一张代理图（约 11MB）；
                    // - `reused == true`：它是借来的 `renderSpare`，归属权不在本协程 ⇒
                    //   **绝不回收** —— 它还要当下一次渲染的写入目标，回收它等于把双缓冲拆成单缓冲。
                    //   （它里面留着半张图也无所谓：它此刻**不在画面上**，而 `renderSpare` 的唯一
                    //   读者就是这个渲染协程；只要下一批渲染画完，它就被整幅重写 —— 而「画完」
                    //   本身正是上面 `completed` 把关的那件事，所以「上屏的图一定是完整帧」这条
                    //   不变量不依赖它此刻的内容。）
                    if (!batch.reused) batch.bmp.recycle()
                    return@collect
                }
                val target = batch.bmp
                // 渲染完成后再赋值并递增 stamp：位图是**原位**被写满的，
                // 不触发重组的话 Compose 会一直显示赋值时那一帧（预览空白）。
                //
                // 双缓冲交换就在这一行：刚算好的那块换到前台，旧的**前台**块降级为下一批的写入目标。
                //
                // ⚠️ 这里读 `rendered` 是安全的：能走到这一行说明 `imageEpoch` 没变，而唯一会在
                // 别处改它的 `onBack` 一定会先推进代次 ⇒ 从进锁前到现在没有别人动过它。
                // 另外本段全程没有挂起点（`rendered =` / `renderSpare =` 都是快照写），
                // 所以「读旧值 → 换前台 → 定备用」这三步之间不可能插进另一批渲染。
                //
                // 尺寸与本次不符（换图）时干脆置空：那种场合它早已在 `onBack` 里退役并回收，
                // 这里的置空只是兜底 —— 宁可下一批多分配一次，也不能把一块尺寸不对的缓冲留成写入目标。
                val previous = rendered
                rendered = target
                renderSpare = if (
                    previous != null && previous !== target &&
                    previous.width == w && previous.height == h
                ) {
                    previous
                } else {
                    null
                }
                if (baselinePending) {
                    // 首次渲染用的参数就是 `EditParams()`（导入那一刻刚重置过），所以**这一帧本身就是零编辑图**，
                    // 直接留一份副本当对比基准即可 —— 不必为了对比再单独跑一趟渲染（代理图也要几百毫秒）。
                    //
                    // ⚠️ 刻意在**当前线程同步复制**，不切 `Dispatchers.Default`：
                    // 切线程会引入挂起点，而本协程仍可能在换图时被取消 ——
                    // 一旦在复制期间被取消，`compareBase` 永远是 null，「按住看原图」会静默退化成
                    // 拿解码预览图对比（正是这次要修的 bug）。复制量约 11MB、一次性、且紧跟在
                    // 一次几百毫秒的渲染之后，同步复制的代价可以忽略。
                    //
                    // 这里回收旧的 `compareBase` **不需要**进 `renderMutex`：它的读者只有主线程
                    // （编辑器预览绘制），没有任何后台协程读写它 —— 没有第二个线程就没有竞态可防。
                    // `rendered` 不同：它有后台写者，所以它的回收必须进锁（见 `onBack`）。
                    baselinePending = false
                    compareBase?.recycle()
                    compareBase = target.copy(Bitmap.Config.ARGB_8888, false)
                }
                renderStamp++
                autoMaskNote = autoMaskNoteOut
                liquifyNote = liquifyNoteOut
                // `ms` = 单帧渲染耗时（**完整帧**才算）。真机反馈「滑参数卡不卡」要看的就是它：
                // 拖动时预览每秒能上几帧，直接由这个数决定（`conflate` 只留最新快照，不排队）。
                DebugLog.d(
                    DebugLog.TAG_EDIT, "render done",
                    mapOf(
                        "w" to target.width, "h" to target.height,
                        "stamp" to renderStamp, "ms" to batch.ms, "layers" to layerList.size
                    )
                )
            }
    }

    val openInEditor: (Uri) -> Unit = { uri ->
        DebugLog.i(DebugLog.TAG_IMPORT, "pick", mapOf("uri" to uri.toString()))
        scope.launch {
            loading = true
            status = EditorStatus(StatusKind.Info, "正在解码…")
            // ⚠️ 换图时必须释放**上一张**的 ARW 临时缓存（65MB/张）：`copyToCache` 每次都新建
            // `rawbridge_<nanoTime>.arw`，而 `releaseCache` 原先只有 `onBack` 一个调用点
            // ⇒ 连续打开 10 张 ARW 就在 cacheDir 里堆约 650MB，而且这些文件 mtime 很新、
            // 不容易被系统 trim 掉。放在解码**之前**取旧值：此时 `imported` 还是上一张。
            val staleRaw = imported?.rawCachePath
            val dec = runCatching { Decoder.decodeToProxy(context, uri, profile.proxyLongEdge) }.getOrNull()
            if (dec == null) {
                loading = false
                status = EditorStatus(StatusKind.Error, "无法解码该文件")
                DebugLog.w(DebugLog.TAG_DECODE, "decode failed", mapOf("uri" to uri.toString()))
            } else {
                // 换图：作废在途渲染批次，并登记「等第一次渲染完成后留一份新基线」。
                // `baselinePending` 必须在 `imported = dec` **之前**设好 —— 渲染副作用以
                // `imported` 为 key，赋值后马上就会重启，可能抢在下一行之前跑完。
                imageEpoch.incrementAndGet()
                val staleBase = compareBase
                compareBase = null
                baselinePending = true
                if (staleBase != null) {
                    // 回收进 `renderMutex`：它可能与上一批在途渲染并发（读者是主线程的预览绘制，
                    // 但写者仍是渲染协程，所以口径与 `onBack` 一致）。
                    // 正常路径上 `compareBase` 早已被 `onBack` 清成 null，这里是兜底。
                    scope.launch { renderMutex.withLock { staleBase.recycle() } }
                }
                withContext(Dispatchers.Default) {
                    MlMaskProvider.invalidate()
                    MlFaceProvider.invalidate()
                }
                loading = false
                imported = dec
                srcUri = uri
                // 异步释放上一张的 ARW 缓存（不阻塞解码收尾；IO 磁盘操作用 IO 调度器）
                if (staleRaw != null && staleRaw != dec.rawCachePath) {
                    scope.launch(Dispatchers.IO) { ArwFullDecoder.releaseCache(staleRaw) }
                }
                history.reset()
                params = EditParams()
                retouch = RetouchState()
                // 换图必须清空对象层：它们是**上一张照片**的分割结果上建出来的作用域，
                // 留在这里会让新图凭空多出几层（而且用的还是旧图的参数口径）。
                // `objectScopesAvailable` 不需要在这里复位：上面那个探测 effect 自己会先置 false
                // 再按新图的结果置位。
                layers = emptyList()
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
            // ⚠️ 撤销/重做必须**三层一起回填**。曾经只回填 params + retouch，于是：
            // ① 拖完对象层滑块 → 提交 → 撤销，画面上那层纹丝不动（而 `history.current.layers`
            //    已经变了）；② 之后再提交一次，`push` 记录的是错的「当前」；
            // ③ 连续撤销到栈底后 `history.current.layers` 与界面 `layers` 永久漂移。
            // 这与 `EditModel.kt` 里警告的「撤销一次，全部对象层消失」是同一类**接线漏项** ——
            // 抽成这一个局部 lambda，就是为了让「漏一层」这件事只有一处可发生。
            val applySnapshot: (EditSnapshot) -> Unit = { s ->
                params = s.params
                retouch = s.retouch
                layers = s.layers
                activePresetId = "none"
            }
            // 编辑页**强制深色**：照片必须是页面上唯一的彩色主体（`docs/UI_DESIGN.md` §8 决策点 1）。
            PixelCakeWorkspaceTheme {
                EditorScreen(
                    original = src.bitmap,
                    // 「按住看原图」的基准：零编辑渲染图（首次渲染后留下的副本）。
                    compareBase = compareBase,
                    rendered = rendered,
                    renderVersion = renderStamp,
                    params = params,
                    retouch = retouch,
                    retouchTool = retouchTool,
                    brushRadius = brushRadius,
                    inpaintRadius = inpaintRadius,
                    inpaintCount = inpaintStrokes.size,
                    autoMaskEnabled = settings.autoMask,
                    autoMaskNote = autoMaskNote,
                    liquifyNote = liquifyNote,
                    presets = Presets.ALL,
                    activePresetId = activePresetId,
                    presetThumbs = presetThumbs,
                    // 对象作用域（批次 5）：可用性来自上面的预热探测；层列表是编辑状态的一部分。
                    objectScopesAvailable = objectScopesAvailable,
                    layers = layers,
                    canUndo = history.canUndo,
                    canRedo = history.canRedo,
                    status = status,
                    exporting = exporting,
                    exportFormat = settings.exportFormat,
                    onParamChange = {
                        // F08：拖动过程中只更新参数，不进撤销栈
                        //
                        // ⚠️ 这里收到的**只是整图**参数：对象层的写入走下面的 `onLayersChange`
                        // （`EditorScreen` 的 `writeParams` 已按当前作用域分好路）。
                        // 若哪天有人在 `EditorScreen` 里把两者接错，表现是「拖对象层的滑块，
                        // 整图的参数在变」—— 画面会动，但动的是错的地方。
                        params = it
                        if (activePresetId != "none") activePresetId = "none"
                    },
                    onParamCommit = { history.push(EditSnapshot(params, retouch, layers)) },
                    onRetouchChange = {
                        retouch = it
                        if (activePresetId != "none") activePresetId = "none"
                    },
                    onRetouchCommit = { history.push(EditSnapshot(params, retouch, layers)) },
                    // 图层变更（拖动中）只改状态；离散动作由 `onLayersCommit` 单独入栈
                    //（两者分开的理由与 `onParamChange` / `onParamCommit` 完全一样：
                    // 拖动过程中每帧都入栈会把撤销栈撑爆）。
                    //
                    // 刻意**不**清 `activePresetId`：预设只描述「整图参数 + 人像精修」，
                    // 对象层是并列的结构 ⇒ 改层不算「偏离预设」（见 `PresetParams` 的说明）。
                    onLayersChange = { layers = it },
                    onLayersCommit = { history.push(EditSnapshot(params, retouch, layers)) },
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
                    onAutoMaskChange = { settings.autoMask = it },
                    onPreset = { p ->
                        params = p.params
                        retouch = p.retouch
                        activePresetId = p.id
                        // ⚠️ **刻意不动 `layers`**：预设给的是一套整图参数 + 人像精修，
                        // 而对象层是用户按**这张图的具体内容**搭出来的结构（「背景压暗」「面部提亮」）。
                        // 换个调色风格不该把这张图的分区结构拆掉 —— 那与「选一个作为起点」的语义相反。
                        // 但 `layers` 仍要进快照，否则这一步的撤销会把已有的层抹掉。
                        history.push(EditSnapshot(params, retouch, layers))
                    },
                    onReset = {
                        params = EditParams()
                        retouch = RetouchState()
                        // 「重置」= 回到刚导入的状态 ⇒ 对象层也一起清（它同样是编辑的一部分）。
                        layers = emptyList()
                        brushStrokes = emptyList()
                        inpaintStrokes = emptyList()
                        activePresetId = "none"
                        history.push(EditSnapshot(params, retouch, layers))
                    },
                    onUndo = {
                        if (history.undo()) applySnapshot(history.current)
                    },
                    onRedo = {
                        if (history.redo()) applySnapshot(history.current)
                    },
                    onExport = {
                        // ⚠️ 这三行必须在 `scope.launch` **之前同步执行**，不能留在协程体里。
                        // `onBack` 靠 `exporting` 判断「导出的降级路径可能正在读 `rendered`，
                        // 此刻不能手动回收它」。若 `exporting = true` 落在协程里，
                        // 从「点导出」到「协程真正开跑」之间就存在一个窗口 ——
                        // 此刻点返回会立刻回收 `rendered`，而导出协程随后才去读它，
                        // 那是一次 native 层的 use-after-free。
                        exporting = true
                        exportCancelled.set(false)
                        status = EditorStatus(StatusKind.Info, "正在生成导出…")
                        scope.launch {
                            // 这里再判一次 null 是防御性的（编辑器只在 `imported != null` 时才组合出来）。
                            // ⚠️ 但这个分支必须**把 `exporting` 收回去**：上面那三行已经把 `exporting`
                            // 置成了 true，若在这里直接 return，按钮会永久停在「导出中」——
                            // 而且退出编辑器并不会清它，用户下次进来看到的还是「导出中」。
                            val decoded = imported
                            if (decoded == null) {
                                exporting = false
                                status = EditorStatus(StatusKind.Error, "没有可导出的图片")
                                return@launch
                            }
                            // 绑定成非空局部量：下面 `img.` 有十几处调用点，
                            // 全部依赖智能转换会让这段代码对「中间插一句赋值」极其敏感。
                            val img = decoded
                            // 在主线程捕获 retouch/mask 状态，避免跨线程读快照状态
                            val rt = retouch
                            // 对象作用域图层（批次 5）：导出必须与预览用**同一套层参数**
                            // —— 这是「预览所见 = 导出所得」在对象层上的前提。
                            val layerList = layers
                            val strokes = brushStrokes
                            val radius = brushRadius
                            val inpStrokes = inpaintStrokes
                            val inpRadius = inpaintRadius
                            val autoOn = settings.autoMask
                            // 在主线程把格式/质量/缓存键取出来：Dispatchers.Default 里不应读 Compose 快照状态
                            val fmt = settings.exportFormat
                            val quality = settings.jpegQuality.value
                            val mlKey = mlCacheKey(srcUri, img.bitmap.width, img.bitmap.height, imageEpoch.get())
                            // 降级导出（拿不到全分辨率时用当前预览）的兜底位图**必须在主线程、
                            // 进入 withContext 之前**取好，两个理由：
                            // ① 跨线程读 Compose 快照状态本身就是错的；
                            // ② 更要命的是 `rendered ?: img.bitmap` —— 用户在导出期间点返回时，
                            // `onBack` 已把 `rendered` 置为 null，于是 `?:` 会落到**完全没有编辑的
                            // 代理图**上，而状态行照样显示「已导出（代理分辨率）」。
                            // 用户拿到的是错的图且毫不知情 —— 这比直接报错危险得多。
                            val proxyFallback = rendered
                            // A1 取证：导出起点（此刻蒙版/包围盒缓冲都还没分配）
                            DebugLog.i(DebugLog.TAG_EDIT, "export begin", memorySnapshot())
                            var failure: String? = null
                            val result: Pair<Uri?, String> = try {
                                withContext(Dispatchers.Default) {
                                    val rawPath = img.rawCachePath
                                    if (rawPath != null) {
                                        // 全分辨率 RAW：边解码边分带渲染，不把 196MB 线性图搬进堆
                                        val (ew, eh) = fitLongEdge(img.width, img.height, profile.fullResLongEdge)
                                        // 预览阶段的 ML 蒙版按 key 复用；resampleTo 只换尺寸、共享网格（无整幅分配）。
                                        val mlMask = if (autoOn) MlMaskProvider.skinMaskFor(context, img.bitmap, mlKey) else null
                                        val mask = buildSkinMask(ew, eh, strokes, radius, mlMask)
                                        val renderRetouch = buildRenderRetouch(rt, ew, eh, inpStrokes, inpRadius)
                                        // 对象作用域图层（批次 5）：按**导出分辨率**重建。
                                        // 代价可以忽略：`ObjectMasks.maskFor` 的网格与目标尺寸无关，
                                        // `resampleTo(ew, eh)` 只换一个分母（零大分配），
                                        // 而每层只多一个 `PixelProgram`（几 KB 的 LUT）——
                                        // 换来的是暗角几何等依赖画面尺寸的量在导出尺寸下同样正确。
                                        val objMasks = if (layerList.isEmpty()) {
                                            null
                                        } else {
                                            MlMaskProvider.objectMasksFor(context, img.bitmap, mlKey)
                                        }
                                        val layerStack =
                                            buildLayerStack(layerList, { sc -> objMasks?.maskFor(sc) }, ew, eh)
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
                                            faceAnchor = faceAnchor,
                                            layers = layerStack
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
                                                                            // ⚠️ `full` 是 33MP ≈ 132MB 的位图，`recycle()` 必须进 finally：
                                                                            // 下面 `Exporter.export`（compress）是 OOM 高发点，抛出去就泄漏一整块。
                                                                            try {
                                                                                val out = withContext(Dispatchers.IO) {
                                                                                    Exporter.export(context, full, fmt, quality)
                                                                                }
                                                                                out to "全分辨率 RAW"
                                                                            } finally {
                                                                                full.recycle()
                                                                            }
                                                                        }
                                    } else {
                                        var fullBase: DecodedImage? = null
                                        var fullTarget: Bitmap? = null
                                        try {
                                            fullBase = srcUri?.let {
                                                Decoder.decodeFullRes(context, it, profile.fullResLongEdge)
                                            }
                                            if (fullBase != null) {
                                                // 尺寸先取出来：对象层要按**导出分辨率**建（见 RAW 分支的说明），
                                                // 所以「建层」必须排在 `renderIntoSrgb` 之前。
                                                val fw = fullBase.bitmap.width
                                                val fh = fullBase.bitmap.height
                                                fullTarget = Bitmap.createBitmap(
                                                    fw, fh, Bitmap.Config.ARGB_8888
                                                )
                                                val fobjMasks = if (layerList.isEmpty()) {
                                                    null
                                                } else {
                                                    MlMaskProvider.objectMasksFor(context, img.bitmap, mlKey)
                                                }
                                                val flayerStack =
                                                    buildLayerStack(layerList, { sc -> fobjMasks?.maskFor(sc) }, fw, fh)
                                                // ⚠️ retouch 的三个参数必须**在** renderIntoSrgb **之前**算好：
                                                // 人像精修已经收进该函数内部（阶段顺序
                                                // `调色 → 精修 → 细节`，与两条 RAW 入口同序）。
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
                                                // 局部名刻意**不叫** rendered：外层已有一个 Compose 状态 `rendered`，
                                                // 同名会被遮蔽，将来读代码的人极易误判自己在读哪个。
                                                val okFull = EditEngine.renderIntoSrgb(
                                                    fullTarget, fullBase.bitmap, params,
                                                    fretouch, fmask, fAnchor, flayerStack
                                                )
                                                if (!okFull) null to "全分辨率渲染尺寸不匹配"
                                                else {
                                                    val out = withContext(Dispatchers.IO) {
                                                        Exporter.export(context, fullTarget, fmt, quality)
                                                    }
                                                    out to "全分辨率"
                                                }
                                            } else if (proxyFallback != null) {
                                                // 降级导出：用**当前预览**那张，而不是「没有编辑的源图」。
                                                // `proxyFallback` 在主线程取好，这里只读局部 val，
                                                // 所以「导出期间用户点返回」不可能把它换成未编辑的图。
                                                val out = withContext(Dispatchers.IO) {
                                                    Exporter.export(context, proxyFallback, fmt, quality)
                                                }
                                                out to "代理分辨率（按当前预览）"
                                            } else {
                                                // 兜底也没了（预览已被释放）：必须**明确报错**，
                                                // 绝不能悄悄导出 `img.bitmap` —— 那是用户没编辑过的原图。
                                                null to "预览已释放，无法降级导出"
                                            }
                                        } finally {
                                            fullTarget?.recycle()
                                            fullBase?.bitmap?.recycle()
                                        }
                                    }
                                    }
                            } catch (c: CancellationException) {
                                // 取消（退出编辑器 / scope 被取消）不是失败，原样抛出。
                                throw c
                            } catch (t: Throwable) {
                                // ⚠️ 33MP 路径上的 OOM、Decoder/EditEngine 的 IllegalArgumentException…
                                // 以前这里**完全没有兜底**：异常会逃出协程，`exporting` 永不复位
                                // ⇒ 按钮永久停在「导出中」，而且 `onBack` 因为看到 `exporting == true`
                                // 会跳过位图回收，132MB 一起泄漏。OOM 是 `Error` 不是 `Exception`，
                                // 所以必须是 `Throwable`。
                                DebugLog.e(
                                    DebugLog.TAG_EDIT, "export threw",
                                    mapOf("err" to (t.message ?: t.javaClass.simpleName))
                                )
                                failure = t.javaClass.simpleName
                                null to "异常"
                            } finally {
                                // 无论成功、失败还是抛异常，`exporting` 都必须复位 ——
                                // 它同时是「导出期间不回收位图」的保护条件，漏复位等于把渲染判死。
                                exporting = false
                                DebugLog.i(DebugLog.TAG_EDIT, "export end", memorySnapshot())
                            }
                            val (exportedUri, label) = result
                            status = when {
                                exportCancelled.get() -> EditorStatus(StatusKind.Info, "已取消导出")
                                exportedUri != null ->
                                    EditorStatus(StatusKind.Success, "已导出（$label）：$exportedUri")
                                else -> EditorStatus(
                                    StatusKind.Error,
                                    "导出失败（$label）" + (failure?.let { "：$it" } ?: "")
                                )
                            }
                            DebugLog.i(
                                DebugLog.TAG_EDIT, "export",
                                mapOf("ok" to (exportedUri != null), "tier" to label) + memorySnapshot()
                            )
                        }
                    },
                    onExportFormatChange = {
                        settings.exportFormat = it
                        // 换格式 = 上一次导出结果不再代表「当前设置」，把成功态清掉。
                        // 否则导出过 PNG 后再切到 JPEG，Sheet 还写着「已导出 / 再导一次」——
                        // 状态与设置脱节，正是让用户误判的那类不一致。
                        if (status.kind == StatusKind.Success) status = EditorStatus()
                    },
                    onCancelExport = {
                        exportCancelled.set(true)
                        status = EditorStatus(StatusKind.Info, "正在取消…")
                    },
                    onBack = {
                        // ⚠️ 退出编辑器是这个文件里最危险的一段回收，请按下面的顺序读。
                        //
                        // 触发场景非常常见：拖完滑块**立刻**点返回。此刻渲染协程正在
                        // `Dispatchers.Default` 上往 `rendered` 里写像素，而 `recycle()` 释放的
                        // 正是那块 native 像素内存 —— 两者并发就是 use-after-free：
                        // 表现为 native 崩溃，Java 层的 try/catch 兜不住，也留不下可读的堆栈。
                        //
                        // 三道保护，缺一不可：
                        // ① 推进代次 —— 在途渲染即使算完也不会写回（否则用户退出后再进来，
                        //    先看到的会是上一张照片）；
                        // ② 先摘引用、后回收 —— 主线程同步置 null，UI 立刻不再绘制它们；
                        //    回收延后到锁被授予时，返回键不会被在途渲染堵住几百毫秒；
                        // ③ 回收动作进 `renderMutex` —— 与所有像素写入串行化。
                        //    这是唯一能**证明**「回收那一刻没有写者」的办法，而「窗口很小」
                        //    不是理由：这个回收每次退出都会跑。
                        imageEpoch.incrementAndGet()
                        // 导出期间 `rendered` 还可能被编码路径读（拿不到全分辨率时的降级分支
                        // 会直接拿它去编码）—— 那段时间它的归属权是**共享的**，手动回收必须等导出结束。
                        // 用「导出中就不手动回收」而不是「给导出加锁」：导出要跑几秒，
                        // 让预览渲染去等它会把编辑器卡成幻灯片；这几张图交给 GC 是更划算的交换。
                        // ⚠️ `renderSpare` 必须与 `rendered` **同口径**一起退役：它同样是
                        // 「渲染协程写、生命周期由这一把锁管」的缓冲（见双缓冲那一段）。
                        // 漏掉它的后果有两层：每退出一次编辑器漏一块 11MB；而且那块缓冲里
                        // 还留着上一张照片的像素，下一次导入会把它当作写入目标，画面先脏一拍。
                        val retired: List<Bitmap> =
                            if (exporting) emptyList()
                            else listOfNotNull(rendered, compareBase, renderSpare)
                        rendered = null
                        compareBase = null
                        renderSpare = null
                        baselinePending = false
                        if (retired.isNotEmpty()) {
                            scope.launch {
                                renderMutex.withLock { retired.forEach { it.recycle() } }
                            }
                        }
                        ArwFullDecoder.releaseCache(src.rawCachePath)
                        // 退出编辑器：清掉 ML 缓存（下次打开重新推理，避免用错图）。
                        scope.launch(Dispatchers.Default) {
                            MlMaskProvider.invalidate()
                            MlFaceProvider.invalidate()
                        }
                        // ⚠️ 刻意**不**回收 `src.bitmap`（导入代理图）。它与上面两张的关键区别是
                        // **读者不唯一**：预览渲染、预设缩略图、导出、ML 推理都要读它。
                        // 手动回收就得穷举全部读者并逐个串行化，漏掉任何一个都换来一次 native 崩溃；
                        // 而它只有约 11MB（2048×1366 ARGB_8888），与 33MP 全幅的 131MB
                        // 根本不是一个量级。交给 GC 是正确性明显更高的选择 —— GC 只在这张图
                        // 确实无人引用时才释放，不会误伤在途的读者。
                        //
                        // 上面的 `rendered` / `compareBase` 之所以仍走手动回收，正是因为它们的
                        // 读写者**只有渲染协程自己**：生命周期明确，一把锁就能管住。
                        imported = null
                        srcUri = null
                        retouch = RetouchState()
                        // 对象层与 retouch 同口径一起清（下一次导入本来也会清，这里是为了不让
                        // 「退出编辑器后仍留着一批上一张图的层」这种状态存在哪怕一帧的机会）。
                        layers = emptyList()
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
        CompositionLocalProvider(LocalLowTransparency provides settings.lowTransparency) {
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

                    // 设置页直接持有 `AppSettings`：读写都走同一份状态，写即持久化。
                    // 逐项展开成参数的话，每加一个设置项都要改三处（AppRoot / 签名 / 调用点），
                    // 而它们之间没有任何语义差别 —— 属于会被遗忘的机械改动。
                    PixelCakeTab.Settings -> SettingsScreen(settings = settings)
                }
            }
        }
    }
}

/**
 * ML 缓存键：源图 uri + 尺寸 + **会话代次** `epoch`（= `imageEpoch`）。
 *
 * ## 为什么不能拿 `Bitmap` 的实例身份（`identityHashCode` / `generationId`）当键
 *
 * 两条都是硬伤，任一条单独成立就足以否掉这个方案：
 *
 * 1. **`identityHashCode` 会碰撞，而且碰撞模式恰好命中本项目。**
 *    它是 32 位、由对象地址派生，**不是唯一 ID**。而本项目的位图生命周期正好是
 *    「导入 → 编辑 → 退出 → 交 GC（`src.bitmap` 明确不手动回收，见 `onBack`）→ 再导入」——
 *    前一块被回收后，新的 `Bitmap` 极可能落在**同一地址** ⇒ `identityHashCode` 相同。
 *    若两次的 `uri` 与尺寸也相同（同一张照片重开、或连拍都是同尺寸），键就**完全相等** ⇒
 *    命中缓存 ⇒ 用上一张的旧人脸/分割结果。**这正是本次要修的症状，那样写修不掉它。**
 * 2. **`generationId` 的语义不是「这是哪张图」。** 它跟踪的是「像素**内容**被改动过」
 *    （`setPixels` / `eraseColor` 推进它）。用它当键，等于把「图换了」与「图被改了一笔」
 *    混成一件事；而 `EditEngine` 渲染**每 32 行**就 `setPixels` 一次（`BAND_ROWS = 32`）——
 *    一旦哪天有调用点传进来的是渲染目标而非源图，键就会每帧变化。
 * 3. **最现实的反面作用：缓存会永不命中。** `uri` + 尺寸相同时，每次 `decodeToProxy`
 *    都产生**新实例** ⇒ 实例身份必不同。而本缓存的核心价值就是「预览与导出之间复用同一蒙版，
 *    只推理一次」（见 `MlMaskProvider` 类 KDoc 第 3 条）—— 预览传 `src.bitmap`、
 *    导出传 `img.bitmap`（**不同实例**，见调用点）⇒ 复用**全部失效**，
 *    每次导出都白跑一遍人脸检测与皮肤分割（几百 ms ~ 数秒）。
 *
 * ## `epoch` 为什么恰好是对的那个量
 *
 * `imageEpoch` 的语义就是「**换图 / 退出编辑器**时 +1」，因此它在**一次编辑会话内恒定**：
 *  - 同一张图的预览、导出、对象作用域探测 ⇒ **键相同 ⇒ 复用生效**（性能前提保住）；
 *  - 换成另一张图（或同一 URI 内容被替换后重开）⇒ `epoch` 已 +1 ⇒ **必然失效**；
 *  - 不碰撞、不依赖地址复用、不依赖 GC 时机。
 *
 * ⚠️ 调用点必须在 `imageEpoch.incrementAndGet()` **之后**取值（导入路径即如此），
 * 否则取到的是上一张图的代次。
 */
private fun mlCacheKey(uri: Uri?, w: Int, h: Int, epoch: Int): String =
    "${uri ?: "-"}#${w}x$h#$epoch"

/**
 * 一次预览渲染的产物（连同它的「身份」、「归属」与「完整性」）。
 *
 * 把这几样东西包在一起返回，是因为**出锁之后才需要判断该不该写回**：
 * 判断依据是 [epoch]（这一批还算不算数）与 [completed]（这一批画完了没有），
 * 而丢弃时该不该回收取决于 [reused]（这张图归谁）。
 * 若只返回一个 `Bitmap`，这些信息在锁外就都丢了 —— 而它们各自对应一个真实缺陷：
 * 「退出编辑器瞬间 native 崩溃」、「换图后每批漏一张代理图」，
 * 以及「拖动时预览上下一半颜色不一致的横线 + 闪动」（第七轮真机反馈）。
 *
 * @param bmp 渲染结果。`renderInto*` 是**原位修改**，所以它就是位图本身、不是副本。
 * @param reused `true` 表示这张位图是从 `rendered` **借来复用**的（尺寸刚好一致）；
 *   `false` 表示是本次新建。**只有新建的那些才归本批所有、才允许在丢弃时回收。**
 * @param epoch 进入临界区之前读到的图片代次，用来在写回前核对这一批还有没有人要。
 *   注意它跟踪的是**图片**（换图 / 退出编辑器），拖参数它不会变。
 * @param completed 渲染器是否**画完了整幅**。`false` = 只写了前 k 带，是半成品，
 *   一律不得提交上屏。⚠️ 这一位**必须**取渲染器自报的返回值，不能拿 [epoch] 反推：[epoch]
 *   管「还有没有人要」，本字段管「画完了没有」，两者是互相独立的两个维度。
 * @param ms 单帧渲染耗时（诊断用，配合 `EditEngine.BAND_ROWS` 与 `layers` 一起读日志）。
 */
private data class RenderBatch(
    val bmp: Bitmap,
    val reused: Boolean,
    val epoch: Int,
    val completed: Boolean,
    val ms: Long
)

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
 * 合成口径（`docs/PHASE_DESIGN_HISTORY.md`（P1+ 部分） §7）：
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
        // retouch：缩略图上作用域取**整幅**（FullMask）—— 预设的磨皮/追色必须可见，
        // 不能因为「没画蒙版」就整段跳过（那正是 mask = null 的语义）。
        // ⚠️ 它必须在渲染**之前**算好并作为参数传入：人像精修已收进 renderIntoSrgb 内部，
        // 以保证 `调色 → 精修 → 细节` 的顺序与预览/导出完全一致。
        val rt = buildRenderRetouch(
            preset.retouch.copy(beauty = BeautyParams(), inpaint = emptyList()),
            side, side
        )
        // 影调 + 精修 + 细节：与预览/导出同一条 sRGB 管线（retouch 在内部按 FullMask 生效）。
        EditEngine.renderIntoSrgb(target, square, preset.params, rt, FullMask)
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
/**
 * 是否要跑人脸检测（液化锚点）。
 *
 * ⚠️ 必须与 [Beauty.apply] / `RetouchLayer` 的判据**逐字一致**（现在都走
 * [BeautyParams.hasWiredLiquify]）—— 否则会出现「白跑一次几十毫秒~数秒的检测」，
 * 或者更糟：「液化生效但没喂锚点」而退回蒙版质心。
 */
private fun beautyActive(b: BeautyParams): Boolean = b.hasWiredLiquify
