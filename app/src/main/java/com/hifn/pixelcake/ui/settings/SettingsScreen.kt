package com.hifn.pixelcake.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hifn.pixelcake.core.decode.ExportFormat
import com.hifn.pixelcake.diag.DebugLog
import com.hifn.pixelcake.ui.components.GlassCard
import com.hifn.pixelcake.ui.components.SectionHeader
import com.hifn.pixelcake.ui.theme.Ok
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
@Composable
fun SettingsScreen(
    settings: AppSettings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showAbout by remember { mutableStateOf(false) }
    var logExported by remember { mutableStateOf(false) }
    var cacheMiB by remember { mutableStateOf<Long?>(null) }
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
                Text("设置", style = MaterialTheme.typography.displaySmall)
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

                SettingRow(
                    title = "降低透明度",
                    subtitle = "毛玻璃换成实心底，提升文字可读性"
                ) {
                    Switch(
                        checked = settings.lowTransparency,
                        onCheckedChange = { settings.lowTransparency = it }
                    )
                }

                Spacer(Modifier.height(Spacing.xs))
                Text(
                    // Android 没有 iOS 那种「降低透明度」系统开关（详见 docs/UI_DESIGN.md §6 的更正），
                    // 所以这里必须是应用内开关，不能指望跟随系统。
                    "注：Android 没有系统级的「降低透明度」开关，因此这是应用内设置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

                SettingRow(
                    title = "默认开启自动蒙版",
                    subtitle = "磨皮与液化只作用于 AI 识别出的皮肤区；画笔涂抹可补正"
                ) {
                    Switch(
                        checked = settings.autoMask,
                        onCheckedChange = { settings.autoMask = it }
                    )
                }

                Spacer(Modifier.height(Spacing.s))
                Text(
                    "推理优先走 GPU，失败自动降级到 CPU。加速器状态在编辑器里实时显示。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                TextButton(
                    onClick = {
                        context.cacheDir.clearChildren()
                        cacheMiB = 0L
                    }
                ) { Text("清理缓存") }
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
                    TextButton(onClick = { showAbout = true }) { Text("查看详情") }
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
                    TextButton(onClick = { settings.reset() }) { Text("恢复") }
                }

                Spacer(Modifier.height(Spacing.s))

                SettingRow(
                    title = "导出调试日志",
                    subtitle = if (logExported) "已触发系统分享" else "排查真机问题时导出给开发者"
                ) {
                    TextButton(onClick = { logExported = DebugLog.export(context) }) { Text("导出") }
                }

                if (logExported) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "已导出",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ok
                    )
                }
            }
        }
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
        Text(title, style = MaterialTheme.typography.bodyMedium)
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
            Text(title, style = MaterialTheme.typography.bodyMedium)
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
                shape = Radius.chip
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
