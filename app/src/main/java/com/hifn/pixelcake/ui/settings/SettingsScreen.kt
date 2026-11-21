package com.hifn.pixelcake.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import java.io.File
import androidx.compose.ui.platform.LocalContext
import com.hifn.pixelcake.core.decode.ExportFormat
import com.hifn.pixelcake.core.edit.preset.CustomPresets
import com.hifn.pixelcake.core.edit.preset.CustomPreset
import com.hifn.pixelcake.core.ml.MlMaskProvider
import com.hifn.pixelcake.diag.DebugLog
import com.hifn.pixelcake.ui.components.GlassCard
import com.hifn.pixelcake.ui.components.amberChipColors
import com.hifn.pixelcake.ui.components.SectionHeader
import com.hifn.pixelcake.ui.theme.LocalDarkTheme
import com.hifn.pixelcake.ui.theme.Ok
import com.hifn.pixelcake.ui.theme.WsOk
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（`docs/UI_DESIGN.md` §3.2.1）。
 *
 * ## 收录判据：**每次编辑都要用的不进设置；一年才改一次的不占编辑页**
 *
 * 所以这一页只有「外观、导出默认值、AI 与性能、存储、关于、高级」六组，
 * **绝不放**任何调色参数、预设内容、LUT 管理 —— 那些在编辑器里随手就能改，
 * 放到设置里等于让用户在两处之间来回跳。
 *
 * ## 为什么这一页比首版「厚」了（真机反馈修正）
 *
 * 首版刻意做得很薄，理由是「极简」。但用户反馈「按照一般 app 的情况，丰富设置项」——
 * 这条反馈是对的：**极简 ≠ 缺项**。用户在设置页里最常做的事其实是「确认」与「找回」：
 * 「主题是跟随系统的吗」「导出到底用的什么格式」「缓存占了多少」「我这是哪个版本」。
 * 这些信息每一台手机上的主流 App 都有，缺了会让人怀疑「是不是没做完」。
 *
 * 新增的每一项都遵守同一条规矩：**只放「用户选的、要活过重启的」值 + 只读的系统事实**，
 * 不引入任何需要用户理解的运行机制（例如「推理后端选 GPU 还是 CPU」——
 * 那是实现细节，不该让用户选）。
 *
 * ## 分组用 [GlassCard]（默认实心容器材质）
 *
 * 承载型容器不用玻璃（§1.6）：卡片底下本来就是纯色页面底，透出来没有信息量，
 * 还会多叠一层高光描边。卡片之间靠留白分隔 —— 不画粗边框、不画分割线。
 *
 * ## 设置项的读写全部走 [AppSettings]
 *
 * 页内**不持有任何设置的局部 state**。局部副本是这类页面最经典的 bug 源：
 * 改完看着生效了，一退出页面就丢（因为它只改了副本）。
 * [AppSettings] 的属性本身就是可观察状态 + 自动持久化，读的地方读属性、
 * 写的地方写属性 —— 只有一条路径，不存在「忘记存」。
 */
/** 导出调试日志的三态（审计 C-F13）：失败也要可见，不能与「还没试过」同貌。 */
private const val LOG_IDLE = 0
private const val LOG_EXPORTED = 1
private const val LOG_EXPORT_FAILED = 2

@Composable
fun SettingsScreen(
    settings: AppSettings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // D4：缓存清理等页面级磁盘 IO 的协程域（主线程派发，IO 动作显式切线程）
    val cacheScope = rememberCoroutineScope()
    var showAbout by remember { mutableStateOf(false) }
    var showModelCenter by remember { mutableStateOf(false) }
    // 「端侧模型」状态的刷新计数：`MlMaskProvider` 没有可观察性，改它做 key 即可重读快照。
    var modelRefresh by remember { mutableStateOf(0) }
    var logExportState by remember { mutableStateOf(LOG_IDLE) }
    var cacheMiB by remember { mutableStateOf<Long?>(null) }
    // v0.7.2（§M 相机直连组）：默认修图参数的候选 = 内置 3 + 用户预设库（与相机面板同一份）
    var cameraCustomPresets by remember { mutableStateOf(emptyList<CustomPreset>()) }
    LaunchedEffect(Unit) {
        cameraCustomPresets = withContext(Dispatchers.IO) {
            CustomPresets.Store(File(context.filesDir, "custom_presets.json")).load()
        }
    }
    val cameraPresetOptions = remember(cameraCustomPresets) {
        listOf("portra" to "波特拉", "jp" to "日系", "none" to "不套用") +
            cameraCustomPresets.map { it.id to it.name }
    }
    // 版本号只需读一次（同一个进程内不会变）。
    val version = remember(context) { appVersionLabel(context) }

    // 目录遍历放 IO 线程：缓存目录可能有上千个条目，绝不能在主线程 walk。
    LaunchedEffect(Unit) {
        cacheMiB = withContext(Dispatchers.IO) { context.cacheDir.dirSizeBytes() / (1024L * 1024L) }
    }

    LazyColumn(
        // ⚠️ `statusBarsPadding()` 不可省：`MainActivity` 开了 `enableEdgeToEdge()`，页面从 y=0 起画。
        // 只靠 `contentPadding.top` 是**固定 32dp**，不随真实状态栏高度变化 —— 挖孔屏/高状态栏机型
        // （ColorOS 常见 36~44dp）上标题会被状态栏压住。首屏顶栏用的就是同一个 Insets 修饰符。
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(
            start = Spacing.page,
            end = Spacing.page,
            top = Spacing.xxl,
            bottom = Spacing.xxxl
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)
    ) {
        item {
            Column(modifier = Modifier.padding(bottom = Spacing.m)) {
                // v0.7.3（审计 A4）：页头 28sp → titleMedium（规范 §M 页头 17px/700）
                Text("设置", style = MaterialTheme.typography.titleMedium)
                Text(
                    "默认值与系统级开关。调色参数都在编辑器里，不在这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }

        // ———— 外观 ————
        item {
            GlassCard {
                SectionHeader(
                    title = "外观",
                    subtitle = "编辑页始终使用深色工作台，不受这里影响"
                )
                Spacer(Modifier.height(Spacing.m))

                SettingChips(
                    title = "主题模式",
                    subtitle = "首页与设置页的明暗；编辑页恒为深色"
                ) {
                    ChipSelector(
                        options = ThemeMode.entries,
                        selected = settings.themeMode,
                        label = { it.label },
                        onSelect = { settings.themeMode = it }
                    )
                }

                Spacer(Modifier.height(Spacing.m))

                // v0.7.2（审计 C-F10）：开关行整行 `toggleable(role = Role.Switch)` 合并语义 ——
                // TalkBack 读得出「降低透明度，开关」，点行任意处都能拨；Switch 本身
                // `onCheckedChange = null` 避免双焦点。
                SettingSwitchRow(
                    title = "降低透明度",
                    subtitle = "毛玻璃换成实心底，提升文字可读性",
                    checked = settings.lowTransparency,
                    onChange = { settings.lowTransparency = it }
                )

                Spacer(Modifier.height(Spacing.xs))
                Text(
                    // Android 没有 iOS 那种「降低透明度」系统开关（详见 docs/UI_DESIGN.md §6 的更正），
                    // 所以这里必须是应用内开关，不能指望跟随系统。
                    "注：Android 没有系统级的「降低透明度」开关，因此这是应用内设置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(Spacing.m))

                // 「放大文本」（UI3.0 §6.1 明文红线：fontScale ≥1.3 需提供独立开关，
                // 审计 A-P3-14）：生效字号 = max(系统字号, 1.3×)，包装在 MainActivity 的
                // LocalDensity 里；Deck/Rail 的 heightIn(min) 已经按「只能长高不许裁」设计。
                SettingSwitchRow(
                    title = "放大文本",
                    subtitle = "在系统字号基础上再放大到 1.3×，布局让位不裁切",
                    checked = settings.largeText,
                    onChange = { settings.largeText = it }
                )
            }
        }

        // ———— 导出 ————
        item {
            GlassCard {
                SectionHeader(
                    title = "导出",
                    subtitle = "决定编辑器里「导出」的初始选择"
                )
                Spacer(Modifier.height(Spacing.m))

                SettingChips(
                    title = "默认格式",
                    subtitle = if (settings.exportFormat == ExportFormat.PNG) {
                        "PNG 无损、体积大；需要二次后期时选它"
                    } else {
                        "JPEG 体积小，适合直接发出去"
                    }
                ) {
                    ChipSelector(
                        options = ExportFormat.entries,
                        selected = settings.exportFormat,
                        label = { it.name },
                        onSelect = { settings.exportFormat = it }
                    )
                }

                Spacer(Modifier.height(Spacing.m))

                SettingChips(
                    title = "JPEG 质量",
                    subtitle = settings.jpegQuality.note
                ) {
                    ChipSelector(
                        options = JpegQuality.entries,
                        selected = settings.jpegQuality,
                        label = { it.label },
                        onSelect = { settings.jpegQuality = it }
                    )
                }

                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "质量只对 JPEG 生效；PNG 无损，不参与压缩。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ———— AI 与性能 ————
        item {
            GlassCard {
                SectionHeader(
                    title = "AI 与性能",
                    subtitle = "端侧推理，照片不出本机"
                )
                Spacer(Modifier.height(Spacing.m))

                SettingSwitchRow(
                    title = "默认开启自动蒙版",
                    subtitle = "磨皮与液化只作用于 AI 识别出的皮肤区；画笔涂抹可补正",
                    checked = settings.autoMask,
                    onChange = { settings.autoMask = it }
                )

                Spacer(Modifier.height(Spacing.s))
                Text(
                    "推理优先走 GPU，失败自动降级到 CPU。加速器状态在编辑器里实时显示。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(Spacing.m))

                // ———— 端侧模型状态（UI 3.0 §N「端侧模型中心」的降级形态）————
                //
                // 规范给的是独立页面。这里先内联在「AI 与性能」里，因为独立页要新增一层导航，
                // 而这块信息本身只有两行 —— 为两行开一页不划算。等相机系留那批（J/K/L）
                // 一起做时再抽成正式页面。
                //
                // 数据是**快照**：`MlMaskProvider` 是普通 object、没有可观察性；
                // 给它套 State 会侵入核心推理路径（那里每帧都在跑），不值得。
                // 用「刷新」按钮主动重读即可 —— 这个页面是给人排查问题用的，不是仪表盘。
                ModelStatusRows(refreshKey = modelRefresh, onRefresh = { modelRefresh++ })
            }
        }

        // ———— 存储 ————
        item {
            GlassCard {
                SectionHeader(
                    title = "存储",
                    subtitle = "缓存用于 RAW 预览与导出中转"
                )
                Spacer(Modifier.height(Spacing.s))

                SettingRow(
                    title = "缓存占用",
                    subtitle = "导出成品固定写入相册 Pictures/PixelCake，不在缓存里"
                ) {
                    Text(
                        cacheMiB?.let { "$it MiB" } ?: "统计中…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(Modifier.height(Spacing.xs))
                // v0.7.4（用户审计 P3）：清理缓存弹确认 —— RAW 中转/导出临时文件
                // 被误清会拖慢下次 RAW 导入，破坏性动作加一道闸。
                var confirmCacheClear by remember { mutableStateOf(false) }
                if (confirmCacheClear) {
                    AlertDialog(
                        onDismissRequest = { confirmCacheClear = false },
                        title = { Text("清理缓存？") },
                        text = { Text("将删除 RAW 预览缓存与导出中转文件；相册成片不受影响。") },
                        confirmButton = {
                            TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = {
                                confirmCacheClear = false
                                cacheScope.launch(Dispatchers.IO) {
                                    context.cacheDir.clearChildren()
                                    cacheMiB = context.cacheDir.dirSizeBytes() / (1024L * 1024L)
                                }
                                cacheMiB = null
                            }) { Text("清理") }
                        },
                        dismissButton = {
                            TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = { confirmCacheClear = false }) { Text("取消") }
                        }
                    )
                }
                TextButton(modifier = Modifier.heightIn(min = 44.dp),
                    onClick = { confirmCacheClear = true }
                ) { Text("清理缓存") }
            }
        }

        // ———— 相机直连（UI3.0 §M 的三条例内设置，v0.7.2 落地）————
        // 三项分别驱动：HomeScreen 的 USB 自动弹出、相机批量/系留的预设选择、
        // 系留到达流的自动导出闸门 —— AppSettings 是唯一状态源，相机面板只消费。
        item {
            GlassCard {
                SectionHeader(
                    title = "相机直连",
                    subtitle = "USB 相机批量与系留到达流"
                )
                Spacer(Modifier.height(Spacing.m))

                SettingSwitchRow(
                    title = "USB 相机接入后自动打开面板",
                    subtitle = "插入相机回到首页时，面板自动弹出（默认关闭）",
                    checked = settings.cameraAutoOpen,
                    onChange = { settings.cameraAutoOpen = it }
                )

                Spacer(Modifier.height(Spacing.m))

                SettingChips(
                    title = "默认修图参数",
                    subtitle = "相机批量与系留套用的预设；与相机面板里的选择是同一个状态"
                ) {
                    // ChipSelector 的 T = 选项 id（String）：label 经 id→名称表解析
                    // （Pair 选项与 String selected 会让 T 无法统一，编译期即炸）
                    ChipSelector(
                        options = cameraPresetOptions.map { it.first },
                        selected = settings.cameraDefaultPresetId,
                        label = { id -> cameraPresetOptions.firstOrNull { it.first == id }?.second ?: id },
                        onSelect = { settings.cameraDefaultPresetId = it }
                    )
                }

                Spacer(Modifier.height(Spacing.m))

                SettingSwitchRow(
                    title = "拍摄完成后自动导出相册",
                    subtitle = "关闭 = 只下载到面板缓存，由你手动批量导出",
                    checked = settings.tetherAutoExport,
                    onChange = { settings.tetherAutoExport = it }
                )

                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "需要 USB 相机（Sony 等 PTP 设备）；功能入口在首页「相机直连」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ———— 关于 ————
        item {
            GlassCard {
                SectionHeader(
                    title = "关于",
                    subtitle = "版本、隐私声明与开源许可"
                )
                Spacer(Modifier.height(Spacing.s))

                SettingRow(
                    title = "像素蛋糕 PixelCake",
                    // 版本号是排查真机问题时第一个要问的东西，直接显示在这里，
                    // 不用再点进「关于」里找（口径与关于页一致，走同一个函数）。
                    subtitle = version
                ) {
                    TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = { showAbout = true }) { Text("查看详情") }
                }
            }
        }

        // ———— 高级 ————
        item {
            GlassCard {
                SectionHeader(
                    title = "高级",
                    subtitle = "不常动，出问题时用"
                )
                Spacer(Modifier.height(Spacing.s))

                SettingRow(
                    title = "恢复默认设置",
                    subtitle = "只重置本页选项；不动缓存，也不会碰你的照片"
                ) {
                    // v0.7.2（审计 C-F12）：一键毁掉主题/透明度/放大文本/导出格式/画质/自动蒙版
                    // 属破坏性操作，弹确认窗；误触点「取消」即可无损返回。
                    var confirmReset by remember { mutableStateOf(false) }
                    TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = { confirmReset = true }) { Text("恢复") }
                    if (confirmReset) {
                        AlertDialog(
                            onDismissRequest = { confirmReset = false },
                            title = { Text("恢复全部默认设置？") },
                            text = { Text("主题、透明度、放大文本、导出格式与画质、自动蒙版都会回到默认值。") },
                            confirmButton = {
                                TextButton(modifier = Modifier.heightIn(min = 44.dp), 
                                    onClick = {
                                        settings.reset()
                                        confirmReset = false
                                    }
                                ) { Text("恢复") }
                            },
                            dismissButton = {
                                TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = { confirmReset = false }) { Text("取消") }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.s))

                Spacer(Modifier.height(Spacing.s))

                SettingRow(
                    title = "模型中心",
                    subtitle = "分割 / 人脸模型的加载状态、加速器与缓存（只读诊断）"
                ) {
                    TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = { showModelCenter = true }) { Text("查看") }
                }

                Spacer(Modifier.height(Spacing.s))

                SettingRow(
                    title = "导出调试日志",
                    subtitle = when (logExportState) {
                        LOG_EXPORTED -> "已触发系统分享"
                        LOG_EXPORT_FAILED -> "导出失败（没有可分享的日志文件）"
                        else -> "排查真机问题时导出给开发者"
                    }
                ) {
                    TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = {
                        logExportState = if (DebugLog.export(context)) LOG_EXPORTED else LOG_EXPORT_FAILED
                    }) { Text("导出") }
                }

                if (logExportState != LOG_IDLE) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        if (logExportState == LOG_EXPORT_FAILED) "导出失败" else "已导出",
                        style = MaterialTheme.typography.labelSmall,
                        // ⚠️ 设置页**跟随系统主题**（不在 `PixelCakeWorkspaceTheme` 里），
                        // 所以成功色要按**生效主题**选，不能写死一个：
                        // 旧的 `Ok`(#0F7B4F) 是浅色页语义，深色底上只有 3.54:1（达不到 AA）；
                        // 工作台那组 `WsOk`(#4CC38A) 是 8.46:1。
                        // ⚠️ 判据用 [LocalDarkTheme]（生效主题），不是 `isSystemInDarkTheme()` ——
                        // 后者在「编辑页强制深色」的场景下会取错色板（见 Theme.kt 的说明）。
                        color = if (logExportState == LOG_EXPORT_FAILED) {
                            MaterialTheme.colorScheme.error
                        } else if (LocalDarkTheme.current) WsOk else Ok
                    )
                }
            }
        }
    }

    if (showModelCenter) {
        ModelCenterSheet(onDismiss = { showModelCenter = false })
    }

    if (showAbout) {
        AboutSheet(onDismiss = { showAbout = false })
    }
}

/**
 * 「标题 + 说明」在上、控件在下的设置块。
 *
 * ## 为什么 chip 组不能和说明排在同一行
 *
 * 三档中文标签（「跟随系统 / 浅色 / 深色」）加起来动辄 240dp。同排布局下，
 * 左侧说明列只剩 ~84dp ⇒ 说明被压成三行 —— 而**被压成三行的说明，用户是不会读的**，
 * 等于这条设置没有解释。改成上下分栏后，说明始终一到两行，chip 组也不会被挤掉。
 *
 * 开关类（Switch）本身很窄（~52dp），继续用同排的 [SettingRow]：
 * 一行一个设置是移动端的标准节奏，能不换行就不换行。
 */
@Composable
private fun SettingChips(
    title: String,
    subtitle: String,
    chips: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // v0.7.3（审计 A3）：行题 15sp → 13sp SemiBold（§M `.srow .st b` 13px/600）
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.s))
        chips()
    }
}

/**
 * 一行设置：左侧「标题 + 说明」，右侧任意尾随控件。
 *
 * 抽出来的动机与 §2.2 的「统一卡片」同源：这一页有十来行结构完全一致的设置项，
 * 逐行手写必然出现「有的行左边 padding 16、有的 12」这类漂移，而这类漂移正是
 * 界面显脏的主要来源。**结构重复的地方就必须收敛成一处。**
 *
 * 尾随控件用 `@Composable () -> Unit` 而不是泛型：开关、chip 组、文字、按钮都要能放，
 * 事先枚举类型只会让调用点更啰嗦（而且每加一种控件就要改这里）。
 */
/**
 * 「端侧模型」状态快照（UI 3.0 §N）。
 *
 * 之所以拍快照而不是直接读 `MlMaskProvider.xxx`：那是个普通 `object`，字段靠 `@Volatile`
 * 维护、**没有可观察性**。直接读不会在推理状态变化时触发重组（界面会永远停在第一次进页面
 * 那一刻），而给它套 `State` 会侵入每帧都在跑的推理路径。折中 = 进页面读一次 + 手动刷新。
 */
private data class ModelStatus(
    val accelerator: String?,
    val resolved: Boolean,
    val disabled: Boolean,
    val permanent: Boolean,
    val oom: Int,
    val threshold: Int,
    val cachedSide: Int,
    val lastError: String? = null
)

/**
 * 端侧模型的状态两行（嵌在「AI 与性能」卡片里）。
 *
 * ## 为什么这些内部状态值得显示出来
 *
 * 「自动蒙版不可用」有**两种完全不同的原因**，而编辑器里只会说一句「对象识别不可用」：
 * - 模型还没加载（第一次打开照片才预热）→ 等一下就好；
 * - 连续 OOM 被熔断（`permanentlyDisabled`）→ **只有重启 App 才能恢复**。
 *
 * 两者在画面上表现一模一样，用户只会判定「功能坏了」。把 OOM 计数与熔断状态摆出来，
 * 是这个页面唯一的价值。
 */
@Composable
private fun ModelStatusRows(refreshKey: Int, onRefresh: () -> Unit) {
    val status = remember(refreshKey) {
        ModelStatus(
            accelerator = MlMaskProvider.accelerator,
            resolved = MlMaskProvider.isModelResolved,
            disabled = MlMaskProvider.isDisabled,
            permanent = MlMaskProvider.isPermanentlyDisabled,
            oom = MlMaskProvider.oomCountForUi,
            threshold = MlMaskProvider.oomThreshold,
            cachedSide = MlMaskProvider.cachedSideForUi,
            lastError = MlMaskProvider.lastLoadErrorForUi
        )
    }
    // ⚠️ 顺序有意义：熔断优先于「就绪」。一台设备可能**先成功加载过**（accelerator 非 null）、
    // 之后才 OOM 熔断 —— 那时若先判 accelerator，就会显示「就绪」，把真正的问题盖掉。
    val stateText = when {
        status.permanent ->
            "已永久关闭：连续 OOM ${status.oom}/${status.threshold}，重启 App 才恢复"
        status.disabled ->
            "本会话已关闭：OOM ${status.oom}/${status.threshold}，换图后会再试"
        status.accelerator != null -> "就绪 · 走 ${status.accelerator}"
        status.resolved -> "已加载，但未报告加速器名"
        // v0.7.2（审计 C-F5）：把「预热过了但失败」与「还没预热」区分开 ——
        // 旧文案「打开一张照片后自动预热」在失败场景是误导。
        status.lastError != null -> "加载失败 · ${status.lastError}"
        else -> "未加载 · 打开一张照片后自动预热"
    }
    SettingRow(title = "模型状态", subtitle = stateText) {
        // v0.7.2（审计 C-F9）：裸 Text 的触达只有 ~30×18dp（全页唯一 <48dp 目标）且无
        // 按钮语义 —— 换 TextButton 一举两得。
        TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = onRefresh) { Text("刷新") }
    }
    Spacer(Modifier.height(Spacing.s))
    SettingRow(
        title = "概率缓存",
        subtitle = if (status.cachedSide > 0) {
            "当前图已推理（${status.cachedSide}×${status.cachedSide}）"
        } else {
            "空 · 还没推理过任何图片"
        }
    ) {}
}

/**
 * 开关行（审计 C-F10）：整行 `toggleable(role = Role.Switch)` + Switch 空回调 ——
 * M3 无障碍合并语义的标准做法：TalkBack 把行文字读作开关名，点行任意处都能拨；
 * 视觉上与 [SettingRow] 完全一致。
 */
@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onChange
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.m)
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                // 右留白：防止两行文字贴到开关/chip 上（靠 `SpaceBetween` 不会自动留缝）。
                .padding(end = Spacing.m)
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing()
    }
}

/**
 * 单选 chip 组。
 *
 * 圆角走 [Radius.chip]（14dp）：`FilterChip` 的 M3 默认圆角是 8dp，
 * 与本 App「14 / 22 / 28 / 40 + 胶囊」这套阶梯不是一族 —— 不显式指定就会露出「外来控件」的痕迹
 * （导出按钮曾经栽在同一件事上：M3 默认 4dp 圆角夹在一堆玻璃胶囊中间）。
 */
@Composable
private fun <T> ChipSelector(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                shape = Radius.chip,
                // 选中态走共享琥珀配色（截图实测：裸 FilterChip 是 M3 紫灰 —— A-P1-1 的
                // 第三个漏网点；教训：全局配色纪律必须落在「每一处裸 FilterChip」上）
                colors = amberChipColors()
            )
        }
    }
}

/** 递归统计目录占用（字节）。放在 IO 线程调用。 */
private fun File.dirSizeBytes(): Long =
    walkBottomUp().filter { it.isFile }.sumOf { it.length() }

/** 清空目录内容但保留目录本身。 */
private fun File.clearChildren() {
    listFiles()?.forEach { child ->
        runCatching { child.deleteRecursively() }
    }
}
