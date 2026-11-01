package com.hifn.pixelcake.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 圆角 token（UI 3.0「Aperture」：12 / 20 / 28 + 胶囊）。
 *
 * 梯级判据：**内容小、容器大** —— 小控件（chip / 分段项 / 缩略图）12dp，
 * 卡片与控制台（Deck）20dp，底部 Sheet 28dp（对齐屏幕圆角，勿改），胶囊单列。
 * 照片是**内容**，一律取最小档（12dp）——用容器半径啃照片四角是设计事故。
 */
object Radius {
    /** 12dp：小控件 —— chip / 分段项 / 小按钮 / 缩略图 / 照片预览 */
    val chip: Shape = RoundedCornerShape(12.dp)

    /** 20dp：卡片 —— 容器卡 / 控制台 Deck / 信息卡 / 列表项 */
    val card: Shape = RoundedCornerShape(20.dp)

    /** 28dp：抽屉 —— 底部 Sheet（与屏幕圆角对齐，勿改） */
    val sheet: Shape = RoundedCornerShape(28.dp)

    /** 24dp：外壳级大卡（首页成片卡等首屏展示位） */
    val shell: Shape = RoundedCornerShape(24.dp)

    /** 胶囊：工具栏 Rail / 顶栏 / 提示条 / 滑块轨道 / 圆形按钮。不参与圆角层级，单独成类 */
    val pill: Shape = RoundedCornerShape(percent = 50)
}
