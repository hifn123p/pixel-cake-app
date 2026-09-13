package com.hifn.pixelcake.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.ui.theme.Seed
import com.hifn.pixelcake.ui.theme.Spacing
import com.hifn.pixelcake.ui.theme.pressScale

/**
 * 动作卡：一条可点的大块区域（标题 + 说明 + 右侧指示）。
 *
 * ## 为什么不用 Material 的 `Button`
 *
 * 1. **视觉**：实心 Button 会把强调色大面积铺开，而强调色在本项目里只允许用在
 *    「当前选中 / 关键动作」上（§2.4 验收清单）。成排的实心按钮会让界面立刻变廉价。
 * 2. **触感**：这里用 [pressScale]（按下缩放）代替默认涟漪，并传 `indication = null`
 *    避免「缩放 + 涟漪」两层反馈叠在一起。
 * 3. **信息量**：导入动作需要一句说明（「16-bit 线性 RAW」），Button 装不下副标题。
 *
 * 触发整块的点击而不只是文字：44dp+ 的高度是误触下限，整块可点才够手指友好。
 */
@Composable
fun ActionTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    trailing: String? = null
) {
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .alpha(if (enabled) 1f else 0.4f)
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (accent) Seed else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.s))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 卡片内动作之间的细分隔留白。用留白而不是分割线分层（§2.2）。 */
@Composable
fun ActionTileDivider() {
    Spacer(Modifier.fillMaxWidth().height(Spacing.xs))
}
