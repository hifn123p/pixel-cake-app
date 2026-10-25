package com.hifn.pixelcake.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字阶（`docs/UI_DESIGN.md` §2.1 / §4.0.9）。
 *
 * **严格 5 级：24 / 16 / 14 / 12 / 11**，字重只允许 Regular / Medium / Semibold 三种。
 * 层次靠「字重 + 颜色深浅」表达，不靠继续加字号 —— §2.3 反面清单第 6 条
 * （「字号继续增加」是廉价感的来源）就是给这一条画的线。
 *
 * ⚠️ §4.0.8（UI v2.0）曾把五级整体抬到 28/18/15/13/12：真机结论（§4.0.9）是
 * 「小字层 13/13/12 三档几乎同大、层次被压平；28sp 的 Sheet 标题在 6.7 寸屏上过于吵」，
 * 已整体回调回 §2.1 的原始标尺。**不要再以「提高可读性」为由逐级 +1**：
 * 每一级都 +1 的净效果是「全 App 变大、层次不变」，那不是可读性，是膨胀。
 */
val Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    // 第 5 档（caption）：**下限**，只用于「辅助说明 / 状态回显」这类一句话的文字。
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    // `bodySmall` 是 Material 默认的 12sp —— 与 labelMedium 同号，故**别名到 labelMedium 档**，
    // 而不是去改所有历史调用点（`CameraPanel` 等）：一处收敛，全局立刻合规。
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
)
