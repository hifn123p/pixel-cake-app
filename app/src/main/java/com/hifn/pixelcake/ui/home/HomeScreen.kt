package com.hifn.pixelcake.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.ui.components.CardMaterial
import com.hifn.pixelcake.ui.components.GlassCard
import com.hifn.pixelcake.ui.components.GlassCircleButton
import com.hifn.pixelcake.ui.components.ImportSheet
import com.hifn.pixelcake.ui.theme.Glass
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing
import com.hifn.pixelcake.ui.theme.pressScale
import com.hifn.pixelcake.ui.theme.rememberGlassTint
import java.io.File

/**
 * 调色台（首页，`docs/UI_DESIGN.md` §3.2.2）。
 *
 * ## 极简：首页只有「一个展示位 + 一个动作」
 *
 * 首页**不介绍产品**。说明性文字、规格罗列、设备参数，放在这里都会被读成「产品说明书」——
 * 而首屏应该是**作品**的位置。所以整屏只剩两样东西：
 *
 * 1. **展示位**：一块占满余下高度的玻璃，承载唯一的视觉重心（[StartHero]）；
 * 2. **唯一动作**：右上角「＋」（[HomeTopBar]），点了才问「照片 / 文件 / 连接设备」。
 *
 * 曾经的「这台设备能做什么」2×2 规格表与「工程信息」折叠区**已删除**（用户口径：
 * 「首页介绍不对，删了吧，极简风格」）。设备能力仍在需要处直接计算 —— 相机 Sheet 要用
 * [ResolutionProfile.fullResLongEdge]，那条链路没动；只是**不再往首屏摆**。
 *
 * ## 减少的入口数量
 *
 * 从「三个等价选项 → 用户每次重新做决定」变成「一个动作 → 想清楚再给选项」。
 * [StartHero] 与右上角「＋」是**同一个语义**（都开 [ImportSheet]）：一个随时可达，
 * 一个在视觉重心上。不做第二个语义不同的入口。
 *
 * ## 这一页保留了两处玻璃（§1.6 允许的浮层用法）
 *
 * 展示位与「解码失败」提示卡都浮在页面底之上，属于浮层类，因此显式传
 * [CardMaterial.Glass] —— 不依赖 [GlassCard] 的默认值（默认已改为实心容器）。
 *
 * @param loading 正在解码（33MP ARW 的代理线性解码耗时可见，必须给反馈）
 * @param message 状态消息（解码失败提示），为空则不显示
 * @param onOpenLocalFile 相机直连拉取的本地缓存文件 → 打开编辑器（P2）
 */
@Composable
fun HomeScreen(
    onImportPhoto: () -> Unit,
    onImportArw: () -> Unit,
    loading: Boolean = false,
    message: String = "",
    onOpenLocalFile: (File) -> Unit = {}
) {
    val context = LocalContext.current
    // 只为了把「导出长边」口径递给相机 Sheet；首屏不再展示任何设备信息。
    val profile = remember { context.probeCapabilities().resolutionProfile() }

    var showImport by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeTopBar(enabled = !loading, onAdd = { showImport = true })

            // 状态消息只在「解码失败」这类真需要看见的场景出现，因此不占固定高度。
            // 它是**瞬时提示**、浮在其他内容之上，故保留玻璃材质。
            if (message.isNotEmpty() && !loading) {
                GlassCard(
                    material = CardMaterial.Glass,
                    modifier = Modifier
                        .padding(horizontal = Spacing.page)
                        .padding(top = Spacing.s)
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            StartHero(
                loading = loading,
                onStart = { showImport = true },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.page)
                    .padding(top = Spacing.s, bottom = Spacing.page)
            )
        }

        if (showImport) {
            ImportSheet(
                onPickPhoto = {
                    showImport = false
                    onImportPhoto()
                },
                onPickArw = {
                    showImport = false
                    onImportArw()
                },
                onConnectCamera = {
                    showImport = false
                    showCamera = true
                },
                onDismiss = { showImport = false }
            )
        }

        if (showCamera) {
            CameraSheet(
                longEdge = profile.fullResLongEdge,
                onOpenLocalFile = onOpenLocalFile,
                onDismiss = { showCamera = false }
            )
        }
    }
}

// ———————————————————————————————————————————————————————————————
// 顶栏
// ———————————————————————————————————————————————————————————————

/**
 * 首页顶栏：左侧标题，右侧「＋」。
 *
 * 刻意**不随内容滚动**（放在可滚动内容之外）：主操作在任何滚动位置都必须一点即达 ——
 * 这是「显而易见优先」的直接体现（§1.4 信条 1）。
 */
@Composable
private fun HomeTopBar(enabled: Boolean, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(Spacing.controlHeight + Spacing.m)
            .padding(horizontal = Spacing.page),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("调色台", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        GlassCircleButton(label = "＋", onClick = onAdd, enabled = enabled, size = 40.dp)
    }
}

// ———————————————————————————————————————————————————————————————
// 展示位（全屏唯一视觉重心）
// ———————————————————————————————————————————————————————————————

/**
 * 展示位：一块占满余下高度的玻璃，中心一个「＋」。
 *
 * ## 为什么保留一层几乎看不见的光晕
 *
 * 极简不等于「一块死板的灰」。强调色 α 0.18 的径向渐变只负责让大色块**有呼吸**，
 * 它不是装饰，也不是品牌色展示 —— 调高就会与「照片是唯一彩色主体」冲突（编辑页尤其致命）。
 *
 * 光晕取 `colorScheme.primary` 而不是 [com.hifn.pixelcake.ui.theme.Seed]：深色主题下
 * primary 是降饱和版本，直接写 `Seed` 会让首屏浮起一块过饱和的紫。
 *
 * ## 为什么只有符号、没有文案
 *
 * 「开始一张新的作品」这类句子是**产品在解释自己**，恰恰是首版被读成说明书的原因。
 * 一个「＋」已经足够表达「从这里开始」，不需要再说一遍。
 */
@Composable
private fun StartHero(
    loading: Boolean,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = rememberGlassTint()
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !loading,
                onClick = onStart
            )
            .clip(Radius.shell)
            .background(tint.surface, Radius.shell)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        Color.Transparent
                    ),
                    radius = 560f
                )
            )
            .border(Glass.borderWidth, tint.border, Radius.shell),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
        } else {
            Text(
                "＋",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
