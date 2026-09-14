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
import com.hifn.pixelcake.ui.theme.ContainerLevel
import com.hifn.pixelcake.ui.theme.GlassTint
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing
import com.hifn.pixelcake.ui.theme.containerSurface
import com.hifn.pixelcake.ui.theme.glassSurface
import com.hifn.pixelcake.ui.theme.rememberGlassTint

/**
 * 卡片材质。
 *
 * 这是 §1.6「玻璃收敛」在 API 上的落点：**默认实心**。
 * 玻璃只在「这块东西真的浮在别的内容之上」时才用。
 */
enum class CardMaterial { Container, Glass }

/**
 * 卡片：全 App 的基础容器（`docs/UI_DESIGN.md` §2.2）。
 *
 * ## 默认材质是**实心容器**，不再默认玻璃
 *
 * 名字里的「Glass」是历史遗留 —— 它现在的身份是「全 App 的标准卡片容器」，材质可切。
 * 判据只有一条：**这块卡浮在别的内容之上了吗？**
 *
 * - 设置页分组、参数面板、关于页卡片 → 没浮 → [CardMaterial.Container]（默认）✅
 * - 首屏展示位、底部 Sheet、悬浮工具条、胶囊提示 → 浮了 → [CardMaterial.Glass]
 *
 * 为什么承载型容器不该用玻璃：玻璃的价值是「透出下层」，而卡片底下本来就是纯色页面底，
 * 透出来没有任何信息量；反而每一层都要多画一根高光描边，叠两层就开始显脏。
 *
 * ## 刻意**不画边框、不投阴影**
 *
 * 层次靠「底色差 + 1px 极淡描边 + 留白」表达（实心与玻璃共用这一条规则）。
 * 到处用粗描边是界面显笨重的头号原因 —— `EditorScreen` 早期用 `OutlinedCard`
 * 给每个分组描边就是这个毛病。
 *
 * @param material 材质：默认实心容器；需要浮层观感时传 [CardMaterial.Glass]
 * @param level    仅 [CardMaterial.Container] 生效的容器档位（越亮越「浮」）
 * @param tint     仅 [CardMaterial.Glass] 生效；传 null 时按系统主题自动解析
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = Radius.card,
    material: CardMaterial = CardMaterial.Container,
    level: ContainerLevel = ContainerLevel.Container,
    tint: GlassTint? = null,
    contentPadding: PaddingValues = PaddingValues(Spacing.cardInner),
    content: @Composable ColumnScope.() -> Unit
) {
    // 无条件求值：材质在一次组合内由调用点决定（是常量），但把 remember 放进条件分支
    // 会让它的调用位置随材质变化，反而更脆。开销只是读一次主题 + 一次 CompositionLocal。
    val autoTint = rememberGlassTint()
    val surface = when (material) {
        CardMaterial.Container -> Modifier.containerSurface(shape = shape, level = level)
        CardMaterial.Glass -> Modifier.glassSurface(tint ?: autoTint, shape)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(surface)
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
 *
 * **保留玻璃**：它确实浮在照片之上，是 §1.6 里明确允许的浮层用法。
 * 并且固定走 `opaque = true`：浮在亮照片上时，半透明胶囊的对比度不可控，读不清。
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
