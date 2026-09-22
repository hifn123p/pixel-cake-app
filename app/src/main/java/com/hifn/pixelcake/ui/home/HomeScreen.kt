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
import com.hifn.pixelcake.ui.components.ImportSheet
import com.hifn.pixelcake.ui.theme.Glass
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.Spacing
import com.hifn.pixelcake.ui.theme.glassSurface
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
 * 1. **展示位**：一块占满余下高度与**整个内容宽**的玻璃，承载唯一的视觉重心（[StartHero]）；
 * 2. **唯一动作**：展示位本身。点它才问「照片 / 文件 / 连接设备」。
 *
 * 曾经的「这台设备能做什么」2×2 规格表与「工程信息」折叠区**已删除**（用户口径：
 * 「首页介绍不对，删了吧，极简风格」）。设备能力仍在需要处直接计算 —— 相机 Sheet 要用
 * [ResolutionProfile.fullResLongEdge]，那条链路没动；只是**不再往首屏摆**。
 *
 * ## 减少的入口数量：全屏只有**一个**「＋」
 *
 * 从「三个等价选项 → 用户每次重新做决定」变成「一个动作 → 想清楚再给选项」。
 * ⚠️ 这里踩过一次真机反馈的坑：顶栏曾经**也**挂了一个「＋」，与展示位的「＋」是同一个语义，
 * 于是首屏出现两个加号 —— 用户读到的不是「随时可达」，而是「重复且不知道该点哪个」。
 * **同一个语义只允许有一个落点**，所以顶栏那个已删除，展示位成为唯一入口。
 *
 * ## `fillMaxWidth()` 不能省（真机 bug 的根因）
 *
 * [StartHero] 是 `Column` 里带 `weight(1f)` 的**唯一**子项：`weight` 只分配**高度**，
 * 不分配宽度。缺 `fillMaxWidth()` 时 `Box` 的宽度退化为「内容宽度」（即那个「＋」字形的宽度），
 * 于是首屏出现「左边一条竖着的窄玻璃条 + 右边一整片空白」—— 这正是用户报的
 * 「首页大面积空白，加号挤在左侧一长条」。宽度必须由组件**自己**申明，
 * 不能指望父级：父级给的是高度约束，不是宽度。
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
            HomeTopBar()

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
 * 首页顶栏：**只有标题**。
 *
 * ## 为什么这里没有「＋」（真机反馈修正）
 *
 * 顶栏曾经也挂一个 [GlassCircleButton]「＋」，理由是「主操作在任何滚动位置都一点即达」。
 * 但首屏**根本不可滚动**，那个「随时可达」是伪需求；而它与展示位的「＋」是**同一个语义**，
 * 结果首屏出现两个加号，用户先要判断「这两个是不是一回事」——多一次犹豫就是纯损失。
 *
 * 所以顶栏退化成纯标题：**标题栏只负责说明「这是哪一页」，动作归内容区**。
 * 唯一入口 [StartHero] 就长在视觉重心上，不需要第二个。
 *
 * `statusBarsPadding()` 不可省（`enableEdgeToEdge()` 后内容从 y=0 起画）。
 */
@Composable
private fun HomeTopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(Spacing.controlHeight + Spacing.m)
            .padding(horizontal = Spacing.page),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("调色台", style = MaterialTheme.typography.titleMedium)
    }
}

// ———————————————————————————————————————————————————————————————
// 展示位（全屏唯一视觉重心）
// ———————————————————————————————————————————————————————————————

/**
 * 展示位：一块占满余下高度、**且占满整个内容宽度**的玻璃，中心是唯一入口。
 *
 * ## ⚠️ `fillMaxWidth()` 是这个组件的生存条件
 *
 * 调用方把它放进 `Column` 并只给了 `weight(1f)` —— `weight` 分配的是**高度**。
 * 少了 `fillMaxWidth()`，`Box` 宽度退化为内容宽度（≈ 一个「＋」字的宽度），
 * 首屏就会变成「左侧一条窄玻璃条 + 右侧一整片空白」。宽度必须组件自己申明。
 *
 * ## 为什么保留一层几乎看不见的光晕
 *
 * 极简不等于「一块死板的灰」。强调色 α 0.18 的径向渐变只负责让大色块**有呼吸**，
 * 它不是装饰，也不是品牌色展示 —— 调高就会与「照片是唯一彩色主体」冲突（编辑页尤其致命）。
 *
 * 光晕取 `colorScheme.primary` 而不是 [com.hifn.pixelcake.ui.theme.Seed]：深色主题下
 * primary 是降饱和版本，直接写 `Seed` 会让首屏浮起一块过饱和的紫。
 *
 * ## 文案口径（真机反馈修正）
 *
 * 曾经只有符号、没有文案，理由是「『开始一张新的作品』这类句子是产品在解释自己」。
 * 但纯符号 + 大面积留白被读成**页面没加载出来**（用户原话：「首页大面积空白」）。
 * 现在只补**一行动作标签 + 一行极短的范围说明**（「相册 / 文件 / 相机直连」）——
 * 它回答的是「点了会发生什么」，不是「这是什么产品」，与「不写说明书」并不冲突。
 * 依旧不写任何一句话以上的叙述。
 *
 * 「＋」放在 72dp 玻璃圆里，是为了让它从一个**孤立的字符**变成一个**可点的实体**：
 * 裸字符在空态里读起来像装饰，圆形实体才读起来像按钮。
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
            .fillMaxWidth()
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .glassSurface(tint, Radius.pill, Glass.borderWidth),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "＋",
                        style = MaterialTheme.typography.displaySmall,
                        // 用材质自带的正文色，而不是猜一个 colorScheme 槽位 ——
                        // 玻璃的推荐前景色由 GlassTint 定义（见 GlassTint.content）。
                        color = tint.content
                    )
                }
                Spacer(Modifier.height(Spacing.l))
                Text(
                    "导入照片",
                    style = MaterialTheme.typography.titleMedium,
                    color = tint.content
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "相册 / 文件 / 相机直连",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint.content.copy(alpha = 0.72f)
                )
            }
        }
    }
}
