package com.hifn.pixelcake.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字阶（UI 3.0「Aperture」：28 / 20 / 15 / 13 / 11 五级）。
 *
 * **严格 5 级**，字重只允许 Regular / Medium / SemiBold 三种。
 * 层次靠「字重 + 颜色深浅」表达，不靠继续加字号。
 *
 * ## 使用红线（§06 性能与字号审计）
 *
 * - 可点击文字标签 ≥ 11sp，且 12sp 以下不得作为**唯一**编码（必须伴随图标或颜色）；
 * - 状态徽章 ≥ 10.5sp；辅助信息（时间戳/参数名）允许 10sp 且必须配 Ink-3 弱色；
 * - 一切数值 tabular-nums（`fontFeatureSettings = "tnum"`）——HUD 读数与滑块数值尤其如此；
 * - 系统字体缩放 fontScale 1.0–1.3 全量适配；≥1.3 在设置提供「放大文本」开关。
 */
val Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.3.sp
    ),
    // 第 5 档（caption）：**下限**，只用于「辅助说明 / 状态回显」这类一句话的文字。
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    // `bodySmall` 与 labelMedium 同号会撞级，这里别名到 caption 档（11sp），
    // 而不是去改所有历史调用点：一处收敛，全局立刻合规。
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
)
