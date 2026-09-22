package com.hifn.pixelcake.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hifn.pixelcake.core.decode.ExportFormat

/**
 * 主题模式。
 *
 * ## 为什么要暴露「跟随系统 / 浅色 / 深色」三态而不是一个布尔开关
 *
 * 布尔开关一旦加上「跟随系统」，就必须再补一个「用户到底有没有显式选过」的维度 ——
 * 那是两个状态挤在一个布尔里，必然出错（典型症状：用户选了「深色」，系统切到浅色时被覆盖）。
 * 三态枚举把这个维度**显式建模**出来了。
 *
 * ⚠️ 它**不影响编辑页**：编辑页由 `PixelCakeWorkspaceTheme` 恒定为深色工作台
 * （理由见 `docs/UI_DESIGN.md` §8 决策点 1）。这里的模式只管首页 / 设置页。
 */
enum class ThemeMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色");

    /**
     * @param systemDark 系统当前是否为深色（由 `isSystemInDarkTheme()` 读取）
     * @return 本模式最终生效的主题是否为深色
     */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }
}

/**
 * 导出 JPEG 质量档。
 *
 * ## 为什么要给档位而不是给 1..100 的滑杆
 *
 * JPEG 质量是一个**用户没有能力判断**的参数：92 与 95 的差别肉眼基本看不出，
 * 但体积会差 30%。给滑杆等于把「不知道该怎么选」的负担丢给用户；
 * 给三个带**后果说明**的档位，用户只需要回答「我是要画质还是要体积」。
 * 这与设置页「只放一年改一次的东西」的口径也一致。
 *
 * ⚠️ 只对 JPEG 生效。PNG 无损，`quality` 参数对它没有意义（见 `Exporter.export`）。
 */
enum class JpegQuality(val label: String, val value: Int, val note: String) {
    High("高", 95, "画质优先，文件最大"),
    Standard("标准", 92, "默认，画质与体积平衡"),
    Small("小", 80, "体积优先，适合快速分享")
}

/** SharedPreferences 文件名。改它等于丢掉所有用户的既有设置，非必要不要动。 */
private const val PREFS_NAME = "pixelcake_settings"

private const val KEY_THEME_MODE = "theme_mode"
private const val KEY_LOW_TRANSPARENCY = "low_transparency"
private const val KEY_EXPORT_FORMAT = "export_format"
private const val KEY_JPEG_QUALITY = "jpeg_quality"
private const val KEY_AUTO_MASK = "auto_mask"

/** 版本戳存放的键。它自己不需要迁移，也不对应任何设置项。 */
private const val KEY_SETTINGS_VERSION = "settings_version"

/**
 * 设置数据的**架构版本**（与 App 的 `versionName` 毫无关系 —— 别跟着发版一起抬）。
 *
 * ## 为什么需要它
 *
 * 因为「同一个键改了含义」这种改动**没有替代的识别手段**。举个将来一定会发生的例子：
 * `theme_mode` 现在存的是枚举序号（`Int`），哪天改成存名字（`String`）——
 * 老用户磁盘上躺着的是 `0/1/2`，新代码按名字去读会拿到 `null` 并退回默认值：
 * 用户没做任何操作，主题却自己变回了「跟随系统」。他只会觉得「这 App 有 bug」，
 * 而且这个 bug **不会产生任何日志**（读默认值是合法路径，不是异常路径）。
 *
 * 版本戳把这种情形从「无法区分」变成「可区分」：读到 `from < N` 就知道手上是旧格式，
 * 该先翻译再读。
 *
 * ## 改键语义时必须走的三步（缺一不可）
 *
 * 1. [CURRENT_SETTINGS_VERSION] +1；
 * 2. 在 [migrateSettings] 里加一段 `if (version < N) { …; version = N }`，
 *    **只描述 N-1 → N 这一步的变化**；
 * 3. 那段迁移的 KDoc 里写清三件事：旧值是什么格式、新值是什么格式、
 *    以及老用户（`from` 远小于 N-1，中间跳了好几个版本）该落到哪个默认值。
 *
 * ## 什么时候**不用**动它
 *
 * 单纯新增一个键、或废弃一个键，都不需要迁移：新键读不到自然会走默认值。
 * 只有「键还是那个键，含义变了」才必须写 —— 因为那时旧值会被新代码**当成新格式**读进去。
 */
internal const val CURRENT_SETTINGS_VERSION = 1

/**
 * 把磁盘上的设置数据升级到 [CURRENT_SETTINGS_VERSION]。
 *
 * ## 执行模型：一次性 · 逐段 · 写回
 *
 * 读出 `version` 后，依次跑 `version+1 → version+2 → … → CURRENT` 的**每一段**，
 * 最后把版本戳写成当前版本。**必须逐段跑**，不能「直接按最新格式重写一遍」：
 * 每一段迁移只知道它那一步前后的格式，跳段会让中间几步的变换被整体漏掉
 * （典型后果：用户从 v1 直升 v4，v2/v3 引入的键全部拿到默认值）。
 *
 * ## 为什么用 `commit()` 而不是 `apply()`
 *
 * `apply()` 是异步落盘，进程有可能在它写下去之前被杀掉。那样**下次启动会重跑迁移**，
 * 而迁移函数天然的写法是「读旧键 → 算新值 → 写新键 → 删旧键」：重跑时旧键已经没了，
 * 读到默认值 → 用默认值覆盖用户刚迁移好的设置。想用 `apply()` 就必须让每一段迁移
 * 幂等到能容忍这种重放，而那是很容易写错的约束。`commit()` 从根上只跑一次，
 * 代价是一次同步磁盘 IO —— 它只在 `AppSettings` 构造时发生一趟，量也极小。
 *
 * ## 当前状态：v0 → v1 是**空迁移**
 *
 * v1 不改任何键的语义，它只做一件事：**把版本戳写下去**。这一步不产生数据变化，
 * 但它把「装过 v1 之前的包」与「之后装的包」区分开了 —— 前者从不写 `settings_version`
 * （读出来恒为 0），后者一定写过。没有这一步，将来的 v2 迁移就无法判断
 * 「这个键里躺的是上古老格式，还是本来就没写过」。
 *
 * ⚠️ 也正因为当前迁移是空的，它**没有可被失败的内容**（断言「版本戳写回了」不会失败）。
 * 等第一条真实迁移落地时，必须**同批**补 `SettingsMigrationTest`（JVM 单测，
 * 用假 `SharedPreferences` 覆盖三条：「跳版本升级」「重复调用不二次转换」「老用户落默认值」）。
 * 不做测试的迁移等于把「用户设置是否被正确翻译」交给运气。
 */
@Suppress("ApplySharedPref")
private fun migrateSettings(prefs: SharedPreferences) {
    var version = prefs.getInt(KEY_SETTINGS_VERSION, 0)
    if (version >= CURRENT_SETTINGS_VERSION) return

    val editor = prefs.edit()

    if (version < 1) {
        // v0 → v1：空迁移（见本函数 KDoc「当前状态」）。
        // 这里刻意什么都不做。**不要**为了「看起来做了事」而顺手重写一遍默认值 ——
        // 那会把 v0 时代存下的用户设置全部抹成默认值，是本文件里最容易犯的错。
        version = 1
    }

    // 将来的迁移加在这里，形如：
    //   if (version < 2) { migrateTo2(prefs, editor); version = 2 }
    // 注意每段的判断条件用**局部 `version`**（它随每一段推进）而不是 `from`，
    // 这是「逐段跑」能生效的关键。

    // 落盘用常量而不是局部 `version`：即使将来有人加了迁移却忘了推进 `version`，
    // 最终写下去的版本号依然是正确的，不会退化成「每次启动都重跑一遍迁移」。
    editor.putInt(KEY_SETTINGS_VERSION, CURRENT_SETTINGS_VERSION)
    editor.commit()
}

/**
 * 应用设置（可持久化）。
 *
 * ## 为什么用一个持有 SharedPreferences 的对象，而不是在 `MainActivity` 里散着放 state
 *
 * 设置项**跨越两个页面**：设置页里改、首页 / 编辑页里读，其中「主题模式」还被
 * `setContent` 最外层的主题包装读（比 `AppRoot` 更外层）。散着放的后果是
 * 「谁持有、谁下发」每次都要重新讨论一遍，而且迟早出现「设置页改了、另一处还读旧值」。
 * 收成一个对象之后：**读的地方读属性、写的地方写属性**，只有一条路径。
 *
 * ## 为什么用 `mutableStateOf` 包一层
 *
 * 这样属性的读写在 Compose 里就是**可观察的状态**：设置页改一下，所有读到它的可组合项
 * 自动重组，不需要任何额外的回调链。持久化（写 SharedPreferences）放在 setter 里顺带做掉，
 * 调用方不需要记得「改完还要存」——忘记存是这类设置最常见的 bug。
 *
 * ## 为什么**不**把 ImageLoader / ML 缓存之类的运行态也塞进来
 *
 * 这个类只放「用户选的、要活过进程重启的」值。运行态（当前照片、蒙版缓存）放在
 * `AppRoot` 里 —— 混在一起会让「重启后该恢复什么」变得说不清。
 *
 * @param prefs 由 [rememberAppSettings] 注入。用 `applicationContext` 取，避免持有 Activity 引用。
 */
@Stable
class AppSettings internal constructor(private val prefs: SharedPreferences) {

    init {
        // ⚠️ 必须排在**本类第一行**。Kotlin 按声明顺序执行属性初始化器与 `init` 块，
        // 而下一条属性会立刻从 `prefs` 读值并缓存进 `mutableStateOf`。迁移若排在它后面，
        // 读到的就是**迁移前**的旧格式值，且此后永远不会再读第二次 —— 症状是
        // 「迁移跑了、值也读了，用户拿到的还是旧值」，且没有任何异常可查。
        migrateSettings(prefs)
    }

    /** 主题模式（首页 / 设置页；编辑页恒为深色，不受它影响）。 */
    private var themeModeState by mutableStateOf(
        ThemeMode.entries.getOrElse(prefs.getInt(KEY_THEME_MODE, 0)) { ThemeMode.System }
    )
    var themeMode: ThemeMode
        get() = themeModeState
        set(value) {
            themeModeState = value
            prefs.edit().putInt(KEY_THEME_MODE, value.ordinal).apply()
        }

    /** 「降低透明度」：毛玻璃降级为实心底（全 App 生效，经 `LocalLowTransparency` 下发）。 */
    private var lowTransparencyState by mutableStateOf(prefs.getBoolean(KEY_LOW_TRANSPARENCY, false))
    var lowTransparency: Boolean
        get() = lowTransparencyState
        set(value) {
            lowTransparencyState = value
            prefs.edit().putBoolean(KEY_LOW_TRANSPARENCY, value).apply()
        }

    /** 默认导出格式（编辑器的导出 Sheet 用它作为初始值）。 */
    private var exportFormatState by mutableStateOf(
        ExportFormat.entries.getOrElse(prefs.getInt(KEY_EXPORT_FORMAT, 0)) { ExportFormat.JPEG }
    )
    var exportFormat: ExportFormat
        get() = exportFormatState
        set(value) {
            exportFormatState = value
            prefs.edit().putInt(KEY_EXPORT_FORMAT, value.ordinal).apply()
        }

    /**
     * 导出 JPEG 质量。
     *
     * 存的是**质量数值**而不是枚举序号：序号会随枚举重排而漂移，把用户存的
     * 「标准（92）」在下次发版后静默变成别的档位 —— 数值是稳定标识。
     * 读回时按数值查档，查不到（例如旧版本写过 90）退回默认档。
     */
    private var jpegQualityState by mutableStateOf(
        JpegQuality.entries.firstOrNull { it.value == prefs.getInt(KEY_JPEG_QUALITY, JpegQuality.Standard.value) }
            ?: JpegQuality.Standard
    )
    var jpegQuality: JpegQuality
        get() = jpegQualityState
        set(value) {
            jpegQualityState = value
            prefs.edit().putInt(KEY_JPEG_QUALITY, value.value).apply()
        }

    /** 自动蒙版（AI 皮肤识别）默认开关。编辑页里的开关与它是**同一个状态**。 */
    private var autoMaskState by mutableStateOf(prefs.getBoolean(KEY_AUTO_MASK, true))
    var autoMask: Boolean
        get() = autoMaskState
        set(value) {
            autoMaskState = value
            prefs.edit().putBoolean(KEY_AUTO_MASK, value).apply()
        }

    /**
     * 恢复默认设置。
     *
     * 刻意**不碰**缓存目录、不碰用户的照片 —— 它只重置这个类里的值。
     * 「清理缓存」是设置页里另一个独立动作，两者语义不同，不合并。
     *
     * 也刻意**不动** `settings_version`：版本戳描述的是「磁盘上的数据是什么格式」，
     * 不是一项用户偏好。把它一起重置会退化成「恢复到某个旧格式」，下次启动重跑迁移 ——
     * 那时候用户丢失的不只是设置，还有迁移本该保护的数据。
     */
    fun reset() {
        themeMode = ThemeMode.System
        lowTransparency = false
        exportFormat = ExportFormat.JPEG
        jpegQuality = JpegQuality.Standard
        autoMask = true
    }
}

/**
 * 取（并记住）本进程的设置对象。
 *
 * 用 `remember(context)` 钉住：`LocalContext` 在同一个 Activity 里是稳定引用，
 * 但配置变化后可能换成新实例 —— 用 context 当 key 可以让它跟着重建，
 * 而 SharedPreferences 本身是单例（同一个文件只解析一次），重建的代价可以忽略。
 */
@Composable
fun rememberAppSettings(): AppSettings {
    val context = LocalContext.current
    return remember(context) {
        AppSettings(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    }
}

/**
 * 版本号文案（`v0.2.0 (2)`）。
 *
 * 走 `PackageManager` 而**不用 `BuildConfig`** —— AGP 8 起 `buildConfig` 默认关闭，
 * 本项目并未开启，引用它编译不过。
 *
 * 抽成顶层函数是因为设置页与关于页都要显示它；写两遍的后果是
 * 「关于页显示版本、设置页显示另一个口径」，对排查真机问题的人是干扰。
 */
internal fun appVersionLabel(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "v${info.versionName} (${info.longVersionCode})"
}.getOrNull() ?: "v?"
