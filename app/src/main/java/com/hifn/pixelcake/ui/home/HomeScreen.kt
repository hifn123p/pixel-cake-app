package com.hifn.pixelcake.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.ui.components.CardMaterial
import com.hifn.pixelcake.ui.components.GlassCard
import com.hifn.pixelcake.ui.editor.AppIcons
import com.hifn.pixelcake.ui.editor.IconDef
import com.hifn.pixelcake.ui.editor.PathIcon
import com.hifn.pixelcake.ui.theme.Glass
import com.hifn.pixelcake.ui.theme.HeroCardBottom
import com.hifn.pixelcake.ui.theme.HeroActBg
import com.hifn.pixelcake.ui.theme.HeroCardGlow
import com.hifn.pixelcake.ui.theme.HeroCardMid
import com.hifn.pixelcake.ui.theme.HeroCardTop
import com.hifn.pixelcake.ui.theme.HeroCardWarm
import com.hifn.pixelcake.camera.UsbCameraScanner
import com.hifn.pixelcake.ui.settings.AppSettings
import com.hifn.pixelcake.ui.theme.Radius
import com.hifn.pixelcake.ui.theme.SeedOnDark
import com.hifn.pixelcake.ui.theme.Spacing
import com.hifn.pixelcake.ui.theme.pressScale
import com.hifn.pixelcake.ui.theme.rememberGlassTint
import java.io.File

/**
 * 调色台（首页，UI 3.0「Aperture」：成片感入口）。
 *
 * ## 首页只有「一张成片卡 + 三个入口」
 *
 * 视觉重心是一张圆角 24dp 的渐变「成片卡」（真机语义：这里会是最新的作品位），
 * 三个入口（相册 / 文件 ARW / 相机直连）以玻璃胶囊沉在卡底 —— 打开哪条路，一眼可选。
 * 旧版的光圈环形按钮退役：一个符号撑不起首屏，也读不出「可以点」。
 *
 * ## 三个入口直达，不再经过导入 Sheet
 *
 * Sheet 那一跳只是「把同一组选项再念一遍」；入口胶囊本身就是选项。
 * 相机直连仍走 [CameraSheet]（模态：会话必须随面板关闭而释放，理由见其 KDoc）。
 *
 * ## 「最近编辑」位（预留）
 *
 * 需要编辑会话持久化（预设库阶段，P4）；在持久化落地之前**不渲染空容器**
 * —— 宁可不给，不给假的。
 *
 * ## 这一页保留两处玻璃（§1.6 允许的浮层用法）
 *
 * 状态消息卡与成片卡上的入口胶囊是浮层；页面本体是暖纸底。
 *
 * @param loading 正在解码（33MP ARW 的代理线性解码耗时可见，必须给反馈）
 * @param message 状态消息（解码失败提示），为空则不显示
 * @param onOpenLocalFile 相机直连拉取的本地缓存文件 → 打开编辑器（P2）
 */
@Composable
fun HomeScreen(
    settings: AppSettings,
    onOpenSettings: () -> Unit = {},
    onImportPhoto: () -> Unit,
    onImportArw: () -> Unit,
    loading: Boolean = false,
    message: String = "",
    onOpenLocalFile: (File) -> Unit = {}
) {
    val context = LocalContext.current
    // 只为了把「导出长边」口径递给相机 Sheet；首屏不再展示任何设备信息。
    val profile = remember { context.probeCapabilities().resolutionProfile() }

    var showCamera by remember { mutableStateOf(false) }
    // v0.7.2（§M「连接后自动打开会话」）：USB 状态广播 + 到场探测。USB_STATE 是系统
    // 保护广播（targetSdk 34+ 的运行时接收器仍需显式 flag）；组合时相机可能已插着
    // （设置刚打开 / 冷启动），所以除了接收器还当场探测一次。
    if (settings.cameraAutoOpen) {
        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (UsbCameraScanner.scan(context).isNotEmpty()) showCamera = true
                }
            }
            val filter = IntentFilter("android.hardware.usb.action.USB_STATE")
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            if (UsbCameraScanner.scan(context).isNotEmpty()) showCamera = true
            onDispose { context.unregisterReceiver(receiver) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeTopBar(onOpenSettings = onOpenSettings)

            // 状态消息只在「解码失败」这类真需要看见的场景出现，因此不占固定高度。
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

            HeroCard(
                loading = loading,
                onPickPhoto = onImportPhoto,
                onPickArw = onImportArw,
                onConnectCamera = { showCamera = true },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.page, vertical = Spacing.m)
            )
        }

        if (showCamera) {
            CameraSheet(
                longEdge = profile.fullResLongEdge,
                settings = settings,
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
 * 首页顶栏：只有标题。`statusBarsPadding()` 不可省（`enableEdgeToEdge()` 后内容从 y=0 起画）。
 */
@Composable
private fun HomeTopBar(onOpenSettings: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(Spacing.controlHeight + Spacing.m)
            .padding(horizontal = Spacing.page),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // v0.7.3（审计 A7）：顶栏字标 20sp → 15sp SemiBold（§02 `.wm` 15px）
        Text(
            "调色台",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        // v0.7.3（审计 A7）：规范 §02 右上角 sliders 入口钮（进设置页）
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(Radius.pill)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = null,
                    indication = null
                ) { onOpenSettings() },
            contentAlignment = Alignment.Center
        ) {
            PathIcon(def = AppIcons.Sliders, tint = MaterialTheme.colorScheme.onSurface, size = 19.dp)
        }
    }
}

// ———————————————————————————————————————————————————————————————
// 成片卡（首页唯一视觉重心）
// ———————————————————————————————————————————————————————————————

/**
 * 成片卡：占满余下高度与整个内容宽的圆角 24dp 大卡。
 *
 * ## 渐变即「未导入的作品位」
 *
 * 卡面用暖调多段渐变（金色时刻）抽象占位 —— 它暗示「这里将是一张照片」，
 * 而不是一块空白的灰。真机导入历史落地后，这里会换成最近一次编辑的成片缩略。
 *
 * ## 三个入口胶囊
 *
 * 相册 / 文件 ARW / 相机直连，玻璃胶囊（浮在渐变之上，§1.6 允许的浮层用法）。
 * 禁用态（解码中）整卡不可点；胶囊点击各自直达，无中间 Sheet。
 *
 * ## 光圈徽标
 *
 * 右上角 40dp 线性光圈（品牌唯一图形；按压动效属 P1 收尾项，此处静态）。
 */
@Composable
private fun HeroCard(
    loading: Boolean,
    onPickPhoto: () -> Unit,
    onPickArw: () -> Unit,
    onConnectCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = rememberGlassTint()
    Box(
        modifier = modifier
            .fillMaxWidth()
            // v0.7.3（审计 C5）：成片卡高度 ≈42% 屏（mock .hero-card 344/812）——
            // 旧版 weight(1f) 撑满余屏（~75%），卡片吞掉「最近编辑」区。
            .heightIn(max = 344.dp)
            .clip(Radius.shell)
            .background(
                // 规范 `.ph-golden` 的 5 个停靠点（v0.7.2 补齐第 4/5 档，审计 A-P3-11/Q7）：
                // 旧版把 E9A25C 放在卡底，缺了最底部 16% 提亮到 F2C078 的「暖光收边」。
                Brush.verticalGradient(
                    0f to HeroCardTop,
                    0.34f to HeroCardMid,
                    0.62f to HeroCardWarm,
                    0.84f to HeroCardBottom,
                    1f to HeroCardGlow
                )
            )
            .border(Glass.borderWidth, tint.border, Radius.shell)
    ) {
        // 中央琥珀辉光：让大色块「有呼吸」，调高会与「照片是唯一彩色主体」冲突
        // （色值走品牌琥珀 token，不再硬编码 —— §9.3-E 已入库）
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            SeedOnDark.copy(alpha = 0.22f),
                            Color.Transparent
                        ),
                        center = androidx.compose.ui.geometry.Offset(0.62f, 0.55f),
                        radius = 620f
                    )
                )
        )

        // 光圈徽标（右上角，品牌锚点）
        PathIcon(
            def = AppIcons.Aperture,
            tint = Color.White.copy(alpha = 0.8f),
            size = 40.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.l),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                "开始一次修图",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Text(
                "全程本机处理 · 预览 2048 / 导出全分辨率 · RAW 16-bit",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = Spacing.xs)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                HeroAction("相册", AppIcons.Image, enabled = !loading, onClick = onPickPhoto, modifier = Modifier.weight(1f))
                HeroAction("文件 ARW", AppIcons.Folder, enabled = !loading, onClick = onPickArw, modifier = Modifier.weight(1f))
                HeroAction("相机直连", AppIcons.Camera, enabled = !loading, onClick = onConnectCamera, modifier = Modifier.weight(1f))
            }
        }

        // 解码中：进度环盖在卡中央（不可点态由各入口的 enabled 表达）
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                    color = Color.White
                )
            }
        }
    }
}

/** 成片卡上的玻璃入口胶囊（图标 + 11sp 文字；§6.1 红线：图标伴随，允许 11sp）。 */
@Composable
private fun HeroAction(
    label: String,
    // ⚠️ 类型必须是 [IconDef]（`AppIcons` 是个 `object`，**不能**当参数类型）：
    // 写成 `def: AppIcons` 会编译失败（object 类型不是类型表达式）。
    // 调用点传的是 `AppIcons.Image` 这类 `IconDef` 实例，`PathIcon(def = def)` 随之对上。
    def: IconDef,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .pressScale(interaction)
            // 规范 `.hc-act`：底 rgba(16,14,17,.45)（原硬编码 0xB8121014 α0.72 偏重 + 违反
            // §9.3-E 收编纪律，审计 C-F7/A-P3-11）；radius 14px 不在圆角梯上，取 chip 档 12dp。
            .clip(Radius.chip)
            .background(HeroActBg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PathIcon(
            def = def,
            tint = Color.White,
            size = 19.dp,
            modifier = Modifier.padding(bottom = Spacing.xs)
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White,
            fontWeight = FontWeight.Medium)
    }
}
