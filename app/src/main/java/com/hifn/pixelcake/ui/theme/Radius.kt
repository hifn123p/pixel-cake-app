package com.hifn.pixelcake.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 圆角 token（`docs/UI_DESIGN.md` §2.1：12 / 20 / 28 / 36 dp 四级阶梯 + 胶囊）。
 *
 * Compose 没有原生 squircle（超椭圆），用「半径足够大的圆角」近似 iOS 观感。
 * **全 App 只允许这四档 + 胶囊**：混用圆角（按钮 4dp、卡片 12dp、输入框全圆）
 * 是最容易让人看出「像七个时期拼出来的」的地方。
 */
object Radius {
    /** 12dp：小控件 —— chip / 分段项 / 小按钮 */
    val chip: Shape = RoundedCornerShape(12.dp)

    /** 20dp：卡片 —— 玻璃卡 / 信息卡 / 列表项 */
    val card: Shape = RoundedCornerShape(20.dp)

    /** 28dp：抽屉 —— 底部参数面板 / ModalBottomSheet */
    val sheet: Shape = RoundedCornerShape(28.dp)

    /** 36dp：外壳 —— 大容器 / 预览框 */
    val shell: Shape = RoundedCornerShape(36.dp)

    /** 胶囊：悬浮工具条 / 提示条 / 滑块轨道。不参与圆角层级，单独成类 */
    val pill: Shape = RoundedCornerShape(percent = 50)
}
