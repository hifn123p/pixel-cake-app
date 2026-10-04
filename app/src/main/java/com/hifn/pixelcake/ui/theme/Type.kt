package com.hifn.pixelcake.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,  // 从 24 提升到 28，增强标题视觉冲击力
        lineHeight = 36.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,  // 从 16 提升到 18，提高可读性
        lineHeight = 26.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,  // 从 14 提升到 15，改善阅读体验
        lineHeight = 22.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,  // 从 12 提升到 13，提高小字可读性
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp
    ),
    // UI 改版新增的第 5 档字号（caption）。
    // ⚠️ 这是**上限**：全 App 字号只允许 28 / 18 / 15 / 13 / 12 五级
    // （`docs/UI_DESIGN.md` §2.4 验收清单）。层次靠字重 + 颜色深浅表达，不靠继续加字号。
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,  // 从 11 提升到 12，最小可读字号
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    // `bodySmall` 是 Material 默认的 12sp —— 与我们的 labelMedium 撞号，会让「字号数」变成 6 级。
    // 这里把它**别名到 caption**，而不是去改所有历史调用点（`CameraPanel` 等尚未迁移的文件）：
    // 一处收敛，全局立刻合规，且不改动任何既 layout 的语义。
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
)
