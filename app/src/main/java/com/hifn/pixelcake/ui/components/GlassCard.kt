package com.hifn.pixelcake.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.hifn.pixelcake.ui.theme.GlassTint
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing
import com.hifn.pixelcake.ui.theme.glassSurface
import com.hifn.pixelcake.ui.theme.rememberGlassTint

/**
 * 玻璃卡：全 App 的基础容器（`docs/UI_DESIGN.md` §2.2）。
 *
 * 刻意**不画边框、不投阴影** —— 层次靠「半透明底 + 1px 高光描边 + 留白」表达。
 * 到处描边框是界面显笨重的头号原因（`EditorScreen` 现状用 `OutlinedCard`
 * 给每个分组描边就是这个毛病）。
 *
 * @param tint   指定玻璃色值；传 null 时按系统主题自动解析
 * @param opaque 降级为实心底（高对比度、内容密集的长列表、低端设备）
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = Radius.card,
    tint: GlassTint? = null,
    opaque: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(Spacing.cardInner),
    content: @Composable ColumnScope.() -> Unit
) {
    val resolved = tint ?: rememberGlassTint(opaque)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(resolved, shape)
            .padding(contentPadding),
        content = content
    )
}

/**
 * 分组标题。
 *
 * 层次只用「字重 + 颜色深浅」表达，**不靠放大字号** ——
 * 一页出现 8 种字号就是「合唱团各唱各的」，这是高级感的第一杀手。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/**
 * 胶囊提示条。
 *
 * 用来替代面板里的纯文本回显（`autoMaskNote` / `liquifyNote`）——
 * 这类「系统状态说明」不该占参数面板的空间，浮在预览区上、自动淡出即可。
 */
@Composable
fun CapsuleNote(
    text: String,
    modifier: Modifier = Modifier,
    tint: GlassTint? = null
) {
    val resolved = tint ?: rememberGlassTint(opaque = true)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = resolved.content,
        modifier = modifier
            .glassSurface(resolved, Radius.pill)
            .padding(horizontal = Spacing.m, vertical = Spacing.s)
    )
}
