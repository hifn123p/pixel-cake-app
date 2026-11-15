package com.hifn.pixelcake.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hifn.pixelcake.ui.theme.ContainerBorderDark
import com.hifn.pixelcake.ui.theme.ContainerBorderLight
import com.hifn.pixelcake.ui.theme.ContainerLevel
import com.hifn.pixelcake.ui.theme.LocalDarkTheme
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing
import com.hifn.pixelcake.ui.theme.containerColor
import com.hifn.pixelcake.ui.theme.pressScale

/**
 * 预设缩略图组（`docs/UI_DESIGN.md` §1.5 的 **A 档**：预设卡片带**真实缩略图**）。
 *
 * ## 为什么预设值得一张真图
 *
 * 预设的名字（「日系」「莫兰迪」「波特拉」）对绝大多数人是**无信息**的 —— 用户只能靠「点一下看看」
 * 来理解它。而预设恰恰是「一眼定生死」的选择：缩略图把「点开-看-撤销」三次操作压成一次浏览。
 * 竞品（VSCO / 醒图）的预设列表几乎都有缩略图，这不是装饰，是**减少无效操作**。
 *
 * ## 卡片形态（UI3.0 §03 `.pcard`，v0.7.2 对齐，审计 A-P3-6）
 *
 * - **两列**、`aspect-ratio 1 : 0.8`（横向卡片）：名称叠在图内左下角胶囊，不再占图外一行 ——
 *   旧版「方形小格 + 图下一行字」在几十个预设的场景里信息密度低、行数爆炸；
 * - 选中右上角 18dp 琥珀对勾徽章：选中态从「只看描边」升级为双编码（描边 + 徽章）；
 * - 圆角走 [Radius.chip]（12dp）：规范 14px 不在梯上，取最近档（与直方图条同一决策）。
 *
 * ## 与 [GlassChipRow] 的关系
 *
 * 两者都是「一组互斥小选项」；预设卡片按可用宽度换行，便于直接浏览完整模板列表。
 * 区别只在**信息量**：chip 只承载一个词，本控件承载「一张图 + 一个词」。
 * 所以没有去重构成一个带可选缩略图的巨型控件 —— 那会让 chip 路径多背一层可空判断。
 *
 * ## 选中态用 2px 强调描边（对「1px 描边」纪律的一次显式例外）
 *
 * 缩略图上，1px 强调线在照片的复杂内容上几乎看不见，选中态会**读不出来**。
 * 这是「状态可见性」压过「描边纪律」的场景，因此明确写成 2px，并只用于选中态
 * （未选中仍是 1px 极淡描边，不破坏整体观感）。
 *
 * @param items    选项
 * @param selected 当前选中项；传 null 表示「无选中」
 * @param thumb    取缩略图；**返回 null 表示还在生成中**，此时显示占位底色（不是错误态）
 * @param size     单元**最小**宽度的提示（规范固定两列，此参数只下限兜底，超窄屏不挤成一条）
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun <T> PresetThumbRow(
    items: List<T>,
    selected: T?,
    label: (T) -> String,
    thumb: (T) -> Bitmap?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 96.dp
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val gap = Spacing.m
        // 规范固定两列；`size` 退化为最小宽度提示（防超窄屏）。
        val cols = ((maxWidth + gap) / (size + gap)).toInt().coerceAtLeast(2)
        val cellW = (maxWidth - gap * (cols - 1)) / cols
        val cellH = cellW * 0.8f
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            items.forEach { item ->
                PresetThumb(
                    label = label(item),
                    image = thumb(item),
                    selected = item == selected,
                    enabled = enabled,
                    width = cellW,
                    height = cellH,
                    onClick = { onSelect(item) }
                )
            }
        }
    }
}

@Composable
private fun PresetThumb(
    label: String,
    image: Bitmap?,
    selected: Boolean,
    enabled: Boolean,
    width: Dp,
    height: Dp,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val accent = MaterialTheme.colorScheme.primary
    // 只包一层包装、不复制像素；以位图实例为 key，重组时不会反复重建。
    val imageBitmap: ImageBitmap? = remember(image) { image?.asImageBitmap() }
    val borderColor = if (selected) accent else {
        if (LocalDarkTheme.current) ContainerBorderDark else ContainerBorderLight
    }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .alpha(if (enabled) 1f else 0.4f)
            .clip(Radius.chip)
            // 占位底色用 High 档：缩略图未就绪时不是「空洞」，而是一块与卡片同族的浅浮面。
            .background(ContainerLevel.High.containerColor())
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = Radius.chip
            )
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // 名称叠在图内左下角（规范 `.pcard .nm`：白字胶囊），替代旧版「图下一行字」。
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .widthIn(max = width - 12.dp)
                .clip(Radius.pill)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
        if (selected) {
            // 右上 18dp 琥珀对勾徽章（规范 `.pcard .ck`）。
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(18.dp)
                    .clip(Radius.pill)
                    .background(accent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }
        }
    }
}
