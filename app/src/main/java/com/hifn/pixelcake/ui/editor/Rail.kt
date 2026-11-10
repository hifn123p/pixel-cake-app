package com.hifn.pixelcake.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing

/**
 * UI 3.0「Aperture」的图标引擎与编辑器工具栏（Rail）。
 *
 * ## 为什么图标用 SVG PathData 现画，而不用 material-icons
 *
 * `material-icons-extended` 是几 MB 的大依赖（本项目刻意没引入）；`material-icons-core`
 * 又没有「半填充圆 / 光圈曲线」这类仪器化图标。设计稿（docs/UI3.0.html §03）的图标本来就是
 * 24×24 的线性 SVG（1.7 圆头）—— 用 [PathParser] 直接吃同一份 PathData，**设计与实现
 * 同源**：文档里改路径，这里照抄即可。解析在 [remember] 里做一次，绘制是纯 drawPath。
 *
 * ## Rail 的语义（与旧「一级工具条」的差别）
 *
 * Rail 不再是「面板的开关」——它切换的是底部控制台（Deck）的内容，屏幕上没有任何东西
 * 会展开或收起（One-Surface 架构，见 `EditorScreen`）。选中态 = 琥珀软底胶囊 + 琥珀图标。
 */

/** 一个图标 = 若干条 24×24 的 SVG 子路径；[fill] 为 true 时填充绘制，否则描边。 */
data class IconPath(val data: String, val fill: Boolean = false)
data class IconDef(val paths: List<IconPath>)

/** 全 App 共用的线性图标（与 docs/UI3.0.html 的内联 SVG 逐字同源）。 */
object AppIcons {
    val Back = IconDef(listOf(IconPath("M15,5l-7,7 7,7")))
    val Undo = IconDef(listOf(
        IconPath("M8,6l-4,4 4,4"),
        IconPath("M4,10h9.5a5.5,5.5 0 1,1 0,11H10"),
    ))
    val Redo = IconDef(listOf(
        IconPath("M16,6l4,4 -4,4"),
        IconPath("M20,10h-9.5a5.5,5.5 0 1,0 0,11H14"),
    ))
    val Reset = IconDef(listOf(
        IconPath("M20,12a8,8 0 1,1 -2.4,-5.7"),
        IconPath("M20,3.5V8h-4.5"),
    ))
    // 历史（UI 3.0 §09 顶栏入口）：时钟 + 回退箭头。指针指到 12 点方向再向右下，
    // 与 `Undo` 的「弧形 + 箭头」同一套画法，两个图标并排时不会读成同一个意思。
    val History = IconDef(listOf(
        IconPath("M3.2,12a8.8,8.8 0 1,0 2.9,-6.5"),
        IconPath("M3,5.2V9.4h4.2"),
        IconPath("M12,7.6V12l3.2,2.1"),
    ))
    val Eye = IconDef(listOf(
        IconPath("M2.5,12S6,5.8 12,5.8 21.5,12 21.5,12 18,18.2 12,18.2 2.5,12 2.5,12Z"),
        IconPath("M9.2,12a2.8,2.8 0 1,0 5.6,0a2.8,2.8 0 1,0 -5.6,0Z"),
    ))
    val User = IconDef(listOf(
        IconPath("M8.4,8a3.6,3.6 0 1,0 7.2,0a3.6,3.6 0 1,0 -7.2,0Z"),
        IconPath("M4.5,20c1.6,-3.6 4.2,-5.2 7.5,-5.2s5.9,1.6 7.5,5.2"),
    ))
    val Sun = IconDef(listOf(
        IconPath("M12,4a8,8 0 0,1 0,16Z", fill = true),
        IconPath("M4,12a8,8 0 1,0 16,0a8,8 0 1,0 -16,0Z"),
    ))
    val Drop = IconDef(listOf(
        IconPath("M12,3.5s6,6.6 6,10.6a6,6 0 1,1 -12,0C6,10.1 12,3.5 12,3.5Z"),
    ))
    val Curve = IconDef(listOf(
        IconPath("M7.5,3.5h9a4,4 0 0,1 4,4v9a4,4 0 0,1 -4,4h-9a4,4 0 0,1 -4,-4v-9a4,4 0 0,1 4,-4Z"),
        IconPath("M6.5,17.5c5.5,0 6.5,-11 11,-11"),
    ))
    val Spark = IconDef(listOf(
        IconPath("M12,3l1.9,5.1L19,10l-5.1,1.9L12,17l-1.9,-5.1L5,10l5.1,-1.9Z"),
    ))
    val Grid = IconDef(listOf(
        IconPath("M6,4h3a2,2 0 0,1 2,2v3a2,2 0 0,1 -2,2H6a2,2 0 0,1 -2,-2V6a2,2 0 0,1 2,-2Z"),
        IconPath("M15,4h3a2,2 0 0,1 2,2v3a2,2 0 0,1 -2,2h-3a2,2 0 0,1 -2,-2V6a2,2 0 0,1 2,-2Z"),
        IconPath("M6,13h3a2,2 0 0,1 2,2v3a2,2 0 0,1 -2,2H6a2,2 0 0,1 -2,-2v-3a2,2 0 0,1 2,-2Z"),
        IconPath("M15,13h3a2,2 0 0,1 2,2v3a2,2 0 0,1 -2,2h-3a2,2 0 0,1 -2,-2v-3a2,2 0 0,1 2,-2Z"),
    ))
    val Image = IconDef(listOf(
        IconPath("M7,5h10a3,3 0 0,1 3,3v8a3,3 0 0,1 -3,3H7a3,3 0 0,1 -3,-3V8a3,3 0 0,1 3,-3Z"),
        IconPath("M7.4,10a1.6,1.6 0 1,0 3.2,0a1.6,1.6 0 1,0 -3.2,0Z"),
        IconPath("M4.5,17l4.5,-4.5 3,3 3.5,-3.5 4,4"),
    ))
    val Folder = IconDef(listOf(
        IconPath("M3.5,7A2.5,2.5 0 0,1 6,4.5h4l2,2.5h6A2.5,2.5 0 0,1 20.5,9.5v7A2.5,2.5 0 0,1 18,19H6a2.5,2.5 0 0,1 -2.5,-2.5V7Z"),
    ))
    val Camera = IconDef(listOf(
        IconPath("M4,8.5A2.5,2.5 0 0,1 6.5,6H9l1.4,-2h3.2L15,6h2.5A2.5,2.5 0 0,1 20,8.5v8a2.5,2.5 0 0,1 -2.5,2.5h-11A2.5,2.5 0 0,1 4,16.5v-8Z"),
        IconPath("M8.6,12.5a3.4,3.4 0 1,0 6.8,0a3.4,3.4 0 1,0 -6.8,0Z"),
    ))
    val Split = IconDef(listOf(
        IconPath("M6.5,4.5h11a3,3 0 0,1 3,3v9a3,3 0 0,1 -3,3h-11a3,3 0 0,1 -3,-3v-9a3,3 0 0,1 3,-3Z"),
        IconPath("M12,4.5v15"),
        IconPath("M7.5,12h2M14.5,12h2"),
    ))
    /** 准星（「点哪修哪」拾取入口：作用域行尾的 36dp 按钮）。 */
    /** 魔法棒（「自动增强」入口：顶栏，启发式 v1）。 */
    val Wand = IconDef(listOf(
        IconPath("M4,20L14.5,9.5"),
        IconPath("M17,3l0.9,2.1L20,6l-2.1,0.9L17,9l-0.9,-2.1L14,6l2.1,-0.9Z", fill = true),
    ))
    val Target = IconDef(listOf(
        IconPath("M7,12a5,5 0 1,0 10,0a5,5 0 1,0 -10,0Z"),
        IconPath("M12,2.5v4M12,17.5v4M2.5,12h4M17.5,12h4"),
    ))
    /** 光圈徽标（品牌唯一图形；首页成片卡右上角，App 图标同源路径）。 */
    val Aperture = IconDef(listOf(
        IconPath("M2,12a10,10 0 1,0 20,0a10,10 0 1,0 -20,0Z"),
        IconPath("M14.31,8l5.74,9.94M9.69,8h11.48M7.38,12l5.74,-9.94M9.69,16l-5.74,-9.94M14.31,16H2.83M16.62,12l-5.74,9.94"),
    ))

    /** Q5 手动旋转（顺时针 90° 步进）。Material 图标 rotate-right（Apache-2.0）。 */
    val Rotate = IconDef(listOf(
        IconPath("M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z", fill = true)
    ))
}

/**
 * 按 [AppIcons] 的 IconDef 画一个线性图标（24×24 设计网格，等比缩到 [size]）。
 * 描边 1.7 圆头圆角 —— 与设计稿逐字同口径。
 */
@Composable
fun PathIcon(
    def: IconDef,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 21.dp,
    strokeWidth: Float = 1.7f,
) {
    val parsed = remember(def) {
        def.paths.map { p ->
            val path = PathParser().parsePathString(p.data).toPath()
            p to path
        }
    }
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) {
            drawIconPaths(parsed, tint, strokeWidth)
        }
    }
}

private fun DrawScope.drawIconPaths(
    parsed: List<Pair<IconPath, Path>>,
    tint: Color,
    strokeWidth: Float,
) {
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    for ((def, path) in parsed) {
        if (def.fill) {
            drawPath(path, tint)
        } else {
            drawPath(path, tint, style = stroke)
        }
    }
}

/** Rail 上的一格：图标 + 11sp 文字标签（双编码，§6.1 字号红线：可点击文字 ≥11sp）。 */
@Composable
private fun RailItem(
    label: String,
    def: IconDef,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️ 未选中用 **Ink-3**（`--ink-3: #6B6963`，UI3.0 `.rk{color:var(--ink-3)}`），
    // 不是 `onSurfaceVariant`（#ABA79E）—— 后者比规范亮一档，会让「未选中」看起来像次要标题。
    val contentColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outline
    Column(
        modifier = modifier
            .background(
                // 规范 `--amber-soft: rgba(255,180,84,.14)` ⇒ **14%**（原为 16%）。
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else Color.Transparent,
                Radius.pill
            )
            .selectable(
                selected = selected,
                // ⚠️ 必须是 `selectable` 而不是 `clickable`：Rail 是**单选**导航
                // （六选一），`selectable(selected=…)` 才会让 TalkBack 播报「已选中 / 未选中」。
                // 用 `clickable` 时读屏用户完全听不出当前在哪一档 —— 而这正是本项目
                // 「导航要能只靠一个感官操作」的要求。
                // 口径与 `AppShell.GlassTabBar` 一致（那里用的就是 `selectable`）。
                role = Role.Tab,
                interactionSource = null,
                indication = null,
                onClick = onClick
            )
            // ⚠️ 规范 CSS 写的是 `.rk{padding:7px 0 6px}`，但那套 CSS 自己就装不进规范标注的
            // 「高 58dp」：图标 21 + 文字行高 16 + padding 13 + 图标文字间距 3 = 53dp，
            // 再加 Rail 自身的上下 padding 就到 61dp 了（HTML 的 11px 文字行高只有 ~13px，
            // Compose 的 labelSmall 行高是 16sp —— 差的正是这 3px）。
            // 规范这里**自相矛盾**，所以取「58dp 装得下」的那一侧：内边距收到 5/4。
            .padding(top = 5.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // 规范 `.rk{gap:3px}`：图标与文字之间留 3px 呼吸（原来完全没有间距，文字紧贴图标）
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        PathIcon(def = def, tint = contentColor)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 编辑器一级工具栏（Rail）：悬浮胶囊，六工具，图标 + 文字。
 *
 * 视觉：Deck 同色（`surface`）胶囊 + 1px 高光描边；选中项 = 琥珀 16% 软底。
 * 它切换的是 Deck 内容，屏幕上没有任何东西会展开或收起（One-Surface）。
 */
@Composable
fun EditorRail(
    selected: EditorCategory,
    onSelect: (EditorCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val edge = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, Radius.pill)
            .border(1.dp, edge.copy(alpha = 0.55f), Radius.pill)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val items = remember {
            listOf(
                Triple(EditorCategory.Portrait, AppIcons.User, "人像"),
                Triple(EditorCategory.Light, AppIcons.Sun, "明暗"),
                Triple(EditorCategory.Color, AppIcons.Drop, "颜色"),
                Triple(EditorCategory.Curve, AppIcons.Curve, "曲线"),
                Triple(EditorCategory.Detail, AppIcons.Spark, "细节"),
                Triple(EditorCategory.Preset, AppIcons.Grid, "预设"),
            )
        }
        for ((cat, def, label) in items) {
            RailItem(
                label = label,
                def = def,
                selected = cat == selected,
                onClick = { onSelect(cat) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
