package com.hifn.pixelcake.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.ui.theme.Glass
import com.hifn.pixelcake.ui.theme.GlassTint
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.glassSurface
import com.hifn.pixelcake.ui.theme.pressScale
import com.hifn.pixelcake.ui.theme.rememberGlassTint

/**
 * 圆形玻璃按钮（顶栏的主操作用它，例如首页右上角的「＋」）。
 *
 * 圆用 [Radius.pill] 表达而不是 `CircleShape`：圆角阶梯只允许「四档 + 胶囊」，
 * 给等宽高的方块套胶囊就是正圆 —— 这样不必引入第五种圆角形状。
 *
 * 用 [pressScale] + `indication = null` 代替默认涟漪（见 `Motion.pressScale` 的说明）。
 * 内容用文字传（本项目没有依赖 `material-icons-extended`，不为几个图标引入整套图标库）。
 *
 * @param label 按钮内容（如「＋」）。语义标签由 `contentDescription` 承担不了文字按钮，
 *        所以这里直接用可见文字，无障碍读屏会读到它。
 */
@Composable
fun GlassCircleButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    enabled: Boolean = true,
    tint: GlassTint? = null
) {
    val resolved = tint ?: rememberGlassTint()
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(size)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .alpha(if (enabled) 1f else 0.4f)
            .glassSurface(resolved, Radius.pill, Glass.borderWidth),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
