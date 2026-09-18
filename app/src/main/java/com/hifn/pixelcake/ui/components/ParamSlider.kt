package com.hifn.pixelcake.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
 *        否则一次拖动会往历史里塞几十条（FIX_LIST F08）。
 */
// Slider 的「自定义 thumb / track」重载在本项目的 Material3 版本里被标记为实验性 API
// （报错原文：This material API is experimental and is likely to change or to be removed in the future）⇒ 必须显式 opt-in。
// 这里是有意使用：白色圆拇指 + 投影是「像 iOS」的关键一笔（见上方 KDoc），不因一个 opt-in 注解放弃该设计。
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
    onDraggingChange: (Boolean) -> Unit = {}
) {
    var dragging by remember { mutableStateOf(false) }
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

    Column(modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
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
        Slider(
            value = value,
            onValueChange = { raw ->
                if (!dragging) {
                    dragging = true
                    onDraggingChange(true)
                }
                onValueChange(if (step > 0f) round(raw / step) * step else raw)
            },
            valueRange = valueRange,
            onValueChangeFinished = {
                dragging = false
                onDraggingChange(false)
                onValueChangeFinished()
            },
            // thumbColor 只对默认拇指生效；这里仍然显式写出来，是为了「万一回退到默认拇指」
            // 时不会悄悄退回强调色小圆（自定义拇指见下）。轨道两色必须一起给：
            // 只改 active 会让未选中的轨道继续用主题默认色，在深色工作台上明显偏亮。
            colors = SliderDefaults.colors(
                thumbColor = thumbColor,
                activeTrackColor = accent,
                inactiveTrackColor = trackInactive
            ),
            thumb = {
                // 圆用 Radius.pill 表达而不是 CircleShape：圆角阶梯只允许「四档 + 胶囊」，
                // 给等宽高的方块套胶囊就是正圆（与 GlassCircleButton 同一套约定）。
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .shadow(
                            elevation = 3.dp,
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
