package com.hifn.pixelcake.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.ui.theme.Motion
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing
import com.hifn.pixelcake.ui.theme.glassSurface
import com.hifn.pixelcake.ui.theme.rememberGlassTint
import com.hifn.pixelcake.ui.theme.segmentIndicator

/**
 * 一级导航项（`docs/UI_DESIGN.md` §3.3 方案 A）。
 *
 * **刻意只有两个**：`相册历史` 现阶段没有持久化数据源，做了就是空壳，留给 P3（NAS）再提为第三个。
 */
enum class PixelCakeTab(val label: String) {
    Darkroom("调色台"),
    Settings("设置")
}

private object Shell {
    /** TabBar 玻璃条高度 */
    val barHeight = 56.dp
}

/**
 * 应用外壳：底部悬浮玻璃 TabBar + 页面转场。
 *
 * 编辑器**不套在这一层里** —— 它是全屏工作台，进入后 TabBar 直接消失（把画面全交给预览区）。
 * 所以调用方应按「编辑器 / 其余」两分支渲染，而不是在 [AppShell] 内部判断。
 *
 * 转场按属性拆开：**缩放走空间弹簧、淡入淡出走 `tween` + `durationFor`**
 * （原因见 `Motion` 的类文档 —— 回弹弹簧绝不能碰 alpha）。TabBar 是 iOS 观感的关键一笔，
 * 所以缩放用空间族而不是淡入替代。
 *
 * @param content 当前 Tab 的页面内容
 */
@Composable
fun AppShell(
    current: PixelCakeTab,
    onSelect: (PixelCakeTab) -> Unit,
    content: @Composable (PixelCakeTab) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 内容区底部留出玻璃条的高度，避免最后一张卡被压住
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = Shell.barHeight + Spacing.xxl)
        ) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    // Tab 是**平级**的：淡入淡出 + 轻微放大，不做左右推
                    // （左右推是层级导航的语义，用在 Tab 上会让人觉得「迷路了」）
                    (fadeIn(tween(Motion.durationFor(Motion.base), easing = Motion.easingOut)) +
                        scaleIn(
                            initialScale = 0.98f,
                            animationSpec = Motion.springSpatial()
                        )) togetherWith
                        fadeOut(tween(Motion.durationFor(Motion.fast), easing = Motion.easingIn))
                },
                label = "tab"
            ) { tab ->
                content(tab)
            }
        }

        GlassTabBar(
            current = current,
            onSelect = onSelect,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/**
 * 悬浮玻璃 TabBar。
 *
 * 选中指示块用 [animateDpAsState] 在两个等宽格位之间滑动（iOS 观感）；
 * 两个 Tab 等宽，所以不需要测量每个 item 的实际宽度，直接用 `maxWidth / count` 即可。
 *
 * 指示块位移是**空间属性** → 走 [Motion.springSpatial]（阻尼 0.6，滑动到位时轻微回弹）。
 * 强调色一律取 `colorScheme.primary`，不直接写 `Seed` —— 深色主题下 primary 是
 * 降饱和版本，直接写 `Seed` 会既刺眼又对比度不足。
 *
 * 指示块材质走共享的 [segmentIndicator]（UI-6）：原先这里与 `GlassSegmentedBar`
 * 各写了一遍强调色平色块，是审计 L3 记的两套近似实现；收敛后「选中态长什么样」
 * 全 App 只有一处定义。
 */
@Composable
private fun GlassTabBar(
    current: PixelCakeTab,
    onSelect: (PixelCakeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = PixelCakeTab.entries
    val tint = rememberGlassTint()

    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = Spacing.xl, vertical = Spacing.l)
            .fillMaxWidth()
            .height(Shell.barHeight)
            .glassSurface(tint, Radius.pill)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxHeight().weight(1f)) {
            val itemWidth = maxWidth / tabs.size
            val index = tabs.indexOf(current).coerceAtLeast(0)
            val indicatorX by animateDpAsState(
                targetValue = itemWidth * index,
                animationSpec = Motion.springSpatial(),
                label = "tabIndicator"
            )
            val accent = MaterialTheme.colorScheme.primary

            // 选中指示块（先画，位于文字之下）
            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(Spacing.xs)
                    .segmentIndicator(Radius.pill)
            )

            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEach { tab ->
                    val selected = tab == current
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(selected = selected, onClick = { onSelect(tab) }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
