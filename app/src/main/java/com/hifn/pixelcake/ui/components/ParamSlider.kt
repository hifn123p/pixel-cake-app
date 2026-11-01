package com.hifn.pixelcake.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.ui.theme.LocalDarkTheme
import com.hifn.pixelcake.ui.theme.Motion
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.SliderThumbShadow
import com.hifn.pixelcake.ui.theme.SliderTrackDark
import com.hifn.pixelcake.ui.theme.SliderTrackLight
import com.hifn.pixelcake.ui.theme.Spacing
import kotlin.math.round

/**
 * 参数滑块（`docs/UI_DESIGN.md` §4.3：带数值气泡）。
 *
 * ## 为什么数值做成「标签行右侧的胶囊」而不是「跟着拇指飘的气泡」
 *
 * 跟着拇指走需要知道轨道内部 padding 才能把气泡对齐到拇指中心，而 Material3 `Slider`
 * 的轨道内边距不是公开 API —— 一旦它改版，气泡就会明显偏位。与其做一个随时会错位的动效，
 * 不如把数值放在**标签行右侧**并用强调色 + 轻微放大来提示「这个值正在被你改动」：
 * 读数永远对齐、永远不遮挡画面，且拖动时眼睛不需要在两个位置之间来回跳。
 *
 * ## 拖动时的两段动画按属性分派（`Motion` 类文档）
 *
 * - **颜色** → [Motion.springEffects]（临界阻尼；回弹弹簧用在颜色上会越过目标色再弹回，发脏）；
 * - **缩放** → [Motion.springSpatialFast]（空间属性，跟手）。
 *
 * 这两行正好是「按属性分族」的最小示例：同一个「正在拖动」的布尔量，驱动两个不同族的动画。
 *
 * ## 拇指改成「白色实心圆 + 投影」（UI-6）
 *
 * M3 默认拇指是一颗**强调色小圆**（本项目的深色档 #9C86F7）。它的问题是读起来像「一个彩色
 * 状态点」而不是「一个可以抓住的实体」，而且在深色工作台上与强调色描边、选中态混成一片。
 * iOS 的滑块拇指是**纯白 + 柔和投影**：白色在深底上天然最高对比（一眼看到「手柄在哪」），
 * 投影则把它从轨道上「抬」起来，给出可拖拽的物理暗示。
 *
 * 白色是**主题相关**的，必须走 [LocalDarkTheme] 而不是 `isSystemInDarkTheme()` ——
 * 本控件目前只在编辑页（强制深色）使用，但浅色页面里纯白拇指会直接消失，
 * 所以这里不写死白色（硬约定见 `LocalDarkTheme` 的文档）。
 *
 * ## 拖动状态上报（[onDraggingChange]）
 *
 * 拖动中会回调 `true`，松手回调 `false`。上层据此**临时隐藏非参数 UI**（顶栏、工具条），
 * 让预览区独占视线 —— 这是「隐形式」交互的核心，也是本控件存在的第二个理由。
 *
 * @param step 量化步长；传 0 表示不量化（连续）。量化在**回调前**完成，上层拿到的值已对齐。
 * @param onValueChangeFinished 松手回调。撤销栈只应在这里入栈一次，
 *        否则一次拖动会往历史里塞几十条（PHASE_DESIGN_HISTORY.md（审查台账） F08）。
 */
// Slider 的「自定义 thumb / track」重载在本项目的 Material3 版本里被标记为实验性 API
// （报错原文：This material API is experimental and is likely to change or to be removed in the future）⇒ 必须显式 opt-in。
// 这里是有意使用：白色圆拇指 + 投影是「像 iOS」的关键一笔（见上方 KDoc），不因一个 opt-in 注解放弃该设计。

/**
 * 拖动读数上报（UI 3.0「中央 HUD」的数据通道）。
 *
 * [ParamSlider] 在拖动期间把「参数名 + 格式化后的当前值」上报给外层（`EditorScreen` 据此
 * 在照片上显示巨大读数 HUD），松手上报 `null` 收回。默认实现是空函数 —— 没有编辑器
 * 上层的场合（设置页等）零开销、零依赖。
 *
 * 为什么走 CompositionLocal 而不是加回调参数：全 App 几十处 [ParamSlider] 经 [EditSlider]
 * 一类的封装间接调用，逐层穿参会让 6 个分组函数的签名同时膨胀；CompositionLocal 让
 * 「想要 HUD 的页面 provide，其它场合自动为空」，零签名变更。
 */
val LocalHudReporter = staticCompositionLocalOf<((Pair<String, String>?) -> Unit)> { {} }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 0f,
    format: (Float) -> String = { "%.2f".format(it) },
    onDraggingChange: (Boolean) -> Unit = {},
    enabled: Boolean = true
) {
    var dragging by remember { mutableStateOf(false) }
    // ⚠️ CompositionLocal.current 只能在组合期读取 —— 拖动回调是非组合 lambda，
    // 必须在这里先把上报函数捕获成局部量，回调里只调它（否则编译不过）。
    val reportHud = LocalHudReporter.current
    // 拖动途中本组合被销毁（第二根手指点了未 inert 的二级分组 chip / 作用域 chip /
    // 画布工具 chip ⇒ `when` 分支切换换掉了面板内容）时，M3 Slider **不会**回调
    // `onValueChangeFinished` ⇒ 上层的 `dragging` 永远收不到 false ⇒ 顶栏/工具条
    // 一直 `alpha=0` 且 inert（返回、导出、重置全部点不到）。onDispose 兜底复位。
    DisposableEffect(Unit) {
        onDispose {
            if (dragging) {
                dragging = false
                onDraggingChange(false)
            }
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val dark = LocalDarkTheme.current

    val valueColor by animateColorAsState(
        targetValue = if (dragging) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = Motion.springEffects(),
        label = "paramValueColor"
    )
    val valueScale by animateFloatAsState(
        targetValue = if (dragging) 1.12f else 1f,
        animationSpec = Motion.springSpatialFast(),
        label = "paramValueScale"
    )

    val thumbColor = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val trackInactive = if (dark) SliderTrackDark else SliderTrackLight

    // —— 紧凑形态（UI 3.0 §03 `.sl`：标签与数值同行 ≈44dp/条）——
    //
    // 旧版是「标签行 22 + Slider 48」两行式 ≈70dp/条 —— 这正是 Deck 高度偏差的根因：
    // 3 滑块的组按它算要 292dp，规范 S=168 连自己的 CSS 都装不下（§9.3-A 的根因账）。
    // 紧凑化后 ≈46dp/条：chips 36 + 3×46 + Hint 30 + padding 28 ≈ 232dp，Deck S（248）
    // 有真实余量；4 滑块的组仍走内容区滚动兜底。
    //
    // ⚠️ 46dp 里 Slider 只占 26dp —— M3 Slider 默认被
    // `LocalMinimumInteractiveComponentEnforcement` 抬到 48dp 触控热区，这里显式关闭并把
    // 高度收到 26dp（轨道 3px + 20dp 拇指居中）。代价：触控热区从 48 降到 26dp ——
    // 这是**刻意的**：Deck 是拇指主战场，密排的滑杆间距本身就是热区边界；
    // Snapseed / Lightroom 的紧凑滑杆同为 ~24-30dp 口径。
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ⚠️ **必须显式写 `color`**：`LocalContentColor` 只由 `Surface` 提供，而参数面板是
            // 一张 `Modifier.containerSurface` 的卡片（`GlassCard`，**不是** `Surface`）⇒ 不写
            // `color` 就会去继承**外层**主题的 contentColor。App 处于浅色主题时那是
            // `Ink #1B1B1F`，压在 `ContainerDark #1B1B22` 的卡片上 ⇒ 对比度≈1:1，**文字消失**。
            // 真机话术：「底部调色工具栏菜单进度条没有对应的文字」。
            //
            // 根因已在 `PixelCakeWorkspaceTheme` 修掉；这里再显式写一遍，是为了与 `GroupLabel` /
            // `Hint` / 右侧数值同一个口径 —— 面板里每个 `Text` 都自带颜色，不依赖外部是否 provide。
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = format(value),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = valueColor,
                modifier = Modifier
                    .scale(valueScale)
                    .background(
                        if (dragging) accent.copy(alpha = 0.12f) else Color.Transparent,
                        Radius.pill
                    )
                    .padding(horizontal = Spacing.s, vertical = Spacing.xs)
            )
        }
        // 触控热区收缩只作用于这一只滑杆（CompositionLocal 局部下发，不外泄）
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Slider(
            value = value,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(26.dp),
            onValueChange = { raw ->
                if (!dragging) {
                    dragging = true
                    onDraggingChange(true)
                }
                val out = if (step > 0f) round(raw / step) * step else raw
                onValueChange(out)
                // 中央 HUD（UI 3.0）：把「参数名 + 格式化值」报给上层；无上层时是空实现
                reportHud(Pair(label, format(out)))
            },
            valueRange = valueRange,
            onValueChangeFinished = {
                dragging = false
                onDraggingChange(false)
                reportHud(null)
                onValueChangeFinished()
            },
            // thumbColor 只对默认拇指生效；这里仍然显式写出来，是为了「万一回退到默认拇指」
            // 时不会悄悄退回强调色小圆（自定义拇指见下）。轨道两色必须一起给：
            // 只改 active 会让未选中的轨道继续用主题默认色，在深色工作台上明显偏亮。
            colors = SliderDefaults.colors(
                thumbColor = thumbColor,
                activeTrackColor = accent,
                inactiveTrackColor = trackInactive,
                // ⚠️ disabled 三个色**必须显式给**。M3 的默认 disabled 态是「按 38% 不透明度
                // 整条轨道」，那会让「关掉的部位」看起来像「只是不可用」，而我们要表达的是
                // 「这里本来有东西、只是这个部位关了」—— 所以保留轨迹形状，只压暗。
                disabledThumbColor = thumbColor.copy(alpha = 0.38f),
                disabledActiveTrackColor = accent.copy(alpha = 0.38f),
                disabledInactiveTrackColor = trackInactive
            ),
            thumb = {
                // 圆用 Radius.pill 表达而不是 CircleShape：圆角阶梯只允许「四档 + 胶囊」，
                // 给等宽高的方块套胶囊就是正圆（与 GlassCircleButton 同一套约定）。
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .alpha(if (enabled) 1f else 0.38f)
                        .shadow(
                            elevation = 4.dp,  // 从 3.dp 提升到 4.dp，增强立体感
                            shape = Radius.pill,
                            clip = false,
                            ambientColor = SliderThumbShadow,
                            spotColor = SliderThumbShadow
                        )
                        .background(thumbColor, Radius.pill)
                )
            }
        )
        }
    }
}
