package com.hifn.pixelcake.ui.editor.curves

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.hifn.pixelcake.core.edit.EditParams
import com.hifn.pixelcake.core.edit.ToneCurve
import com.hifn.pixelcake.ui.theme.Spacing

/**
 * 可拖拽的三点锚点亮度曲线编辑器。
 *
 * 支持亮度曲线和分通道（红/绿/蓝）曲线。
 * 每个通道显示：
 * - 曲线网格背景
 * - 三个可拖拽的锚点手柄（黑场/中间调/白场）
 * - 贝塞尔曲线
 */
@Composable
fun ToneCurvePanel(
    params: EditParams,
    onParamChange: (EditParams) -> Unit,
    channel: CurveTab,
    onChannelChange: (CurveTab) -> Unit,
    modifier: Modifier = Modifier
) {
    // 根据通道获取对应的曲线点
    val points = when (channel) {
        CurveTab.Luma -> params.lumaPoints
        CurveTab.Red -> params.redPoints
        CurveTab.Green -> params.greenPoints
        CurveTab.Blue -> params.bluePoints
    }

    // 计算当前锚点值
    val blackY = ToneCurve.black(points).toFloat()
    val midY = ToneCurve.mid(points).toFloat()
    val whiteY = ToneCurve.white(points).toFloat()
    val isIdentity = ToneCurve.isIdentity(points)

    Column(modifier = modifier.fillMaxWidth()) {
        // 通道选择器
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.xs)) {
            val tabs = listOf(
                CurveTab.Luma to "亮度",
                CurveTab.Red to "红",
                CurveTab.Green to "绿",
                CurveTab.Blue to "蓝"
            )
            tabs.forEach { (tab, label) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tab == channel) Color.White else Color(0x99FFFFFF),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Spacing.s))
                        .background(
                            if (tab == channel) Color(0x338B6FFF) else Color.Transparent,
                            RoundedCornerShape(Spacing.s)
                        )
                        .padding(horizontal = Spacing.s, vertical = Spacing.xs)
                        .clickable { onChannelChange(tab) }
                )
            }
        }
        Spacer(Modifier.height(Spacing.s))

        // 曲线画布
        InteractiveCurveCanvas(
            blackY = blackY,
            midY = midY,
            whiteY = whiteY,
            isIdentity = isIdentity,
            onDragStart = {},
            onDragEnd = {},
            onAnchorDrag = { anchor, newY ->
                val (nb, nm, nw) = when (anchor) {
                    AnchorType.Black -> Triple(newY.toInt(), midY.toInt(), whiteY.toInt())
                    AnchorType.Mid -> Triple(blackY.toInt(), newY.toInt(), whiteY.toInt())
                    AnchorType.White -> Triple(blackY.toInt(), midY.toInt(), newY.toInt())
                }
                val newPoints = ToneCurve.points(
                    nb.coerceIn(0, 255),
                    nm.coerceIn(0, 255),
                    nw.coerceIn(0, 255)
                )
                onParamChange(when (channel) {
                    CurveTab.Luma -> params.copy(lumaPoints = newPoints)
                    CurveTab.Red -> params.copy(redPoints = newPoints)
                    CurveTab.Green -> params.copy(greenPoints = newPoints)
                    CurveTab.Blue -> params.copy(bluePoints = newPoints)
                })
            }
        )

        Spacer(Modifier.height(Spacing.xs))

        // 重置按钮
        if (!isIdentity) {
            TextButton(
                onClick = {
                    val defaultPoints = ToneCurve.IDENTITY
                    onParamChange(when (channel) {
                        CurveTab.Luma -> params.copy(lumaPoints = defaultPoints)
                        CurveTab.Red -> params.copy(redPoints = defaultPoints)
                        CurveTab.Green -> params.copy(greenPoints = defaultPoints)
                        CurveTab.Blue -> params.copy(bluePoints = defaultPoints)
                    })
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重置 ${when (channel) {
                        CurveTab.Luma -> "亮度"
                        CurveTab.Red -> "红"
                        CurveTab.Green -> "绿"
                        CurveTab.Blue -> "蓝"
                    } } 曲线", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 曲线锚点类型。 */
enum class AnchorType { Black, Mid, White }

/** 曲线通道。 */
enum class CurveTab(val label: String) {
    Luma("亮度"),
    Red("红"),
    Green("绿"),
    Blue("蓝")
}
