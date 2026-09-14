package com.hifn.pixelcake.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.ui.theme.ContainerBorderDark
import com.hifn.pixelcake.ui.theme.ContainerBorderLight
import com.hifn.pixelcake.ui.theme.ContainerLevel
import com.hifn.pixelcake.ui.theme.LocalDarkTheme
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing
import com.hifn.pixelcake.ui.theme.containerColor
import com.hifn.pixelcake.ui.theme.pressScale

/**
 * 预设缩略图行（`docs/UI_DESIGN.md` §1.5 的 **A 档**：预设卡片带**真实缩略图**）。
 *
 * ## 为什么预设值得一张真图
 *
 * 预设的名字（「日系」「莫兰迪」「波特拉」）对绝大多数人是**无信息**的 —— 用户只能靠「点一下看看」
 * 来理解它。而预设恰恰是「一眼定生死」的选择：缩略图把「点开-看-撤销」三次操作压成一次浏览。
 * 竞品（VSCO / 醒图）的预设列表几乎都有缩略图，这不是装饰，是**减少无效操作**。
 *
 * ## 与 [GlassChipRow] 的关系
 *
 * 两者都是「一组互斥小选项 + 横向滚动不换行」（高度恒定，避免面板高度跳变）。
 * 区别只在**信息量**：chip 只承载一个词，本控件承载「一张图 + 一个词」。
 * 所以没有去重构成一个带可选缩略图的巨型控件 —— 那会让 chip 路径多背一层可空判断。
 *
 * ## 选中态用 2px 强调描边（对「1px 描边」纪律的一次显式例外）
 *
 * 72dp 的缩略图上，1px 强调线在照片的复杂内容上几乎看不见，选中态会**读不出来**。
 * 这是「状态可见性」压过「描边纪律」的场景，因此明确写成 2px，并只用于选中态
 * （未选中仍是 1px 极淡描边，不破坏整体观感）。
 *
 * @param items    选项
 * @param selected 当前选中项；传 null 表示「无选中」
 * @param thumb    取缩略图；**返回 null 表示还在生成中**，此时显示占位底色（不是错误态）
 * @param size     缩略图边长（方形）；显示尺寸，不是位图像素尺寸
 */
@Composable
fun <T> PresetThumbRow(
    items: List<T>,
    selected: T?,
    label: (T) -> String,
    thumb: (T) -> Bitmap?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 64.dp
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        items.forEach { item ->
            PresetThumb(
                label = label(item),
                image = thumb(item),
                selected = item == selected,
                enabled = enabled,
                size = size,
                onClick = { onSelect(item) }
            )
        }
    }
}

@Composable
private fun PresetThumb(
    label: String,
    image: Bitmap?,
    selected: Boolean,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val accent = MaterialTheme.colorScheme.primary
    // 只包一层包装、不复制像素；以位图实例为 key，重组时不会反复重建。
    val imageBitmap: ImageBitmap? = remember(image) { image?.asImageBitmap() }
    val borderColor = if (selected) accent else {
        if (LocalDarkTheme.current) ContainerBorderDark else ContainerBorderLight
    }

    Column(
        modifier = Modifier
            .width(size)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .alpha(if (enabled) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(Radius.chip)
                // 占位底色用 High 档：缩略图未就绪时不是「空洞」，而是一块与卡片同族的浅浮面。
                .background(ContainerLevel.High.containerColor())
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = borderColor,
                    shape = Radius.chip
                ),
            contentAlignment = Alignment.Center
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
