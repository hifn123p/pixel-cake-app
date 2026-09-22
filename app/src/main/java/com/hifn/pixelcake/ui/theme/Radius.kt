package com.hifn.pixelcake.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 圆角 token（`docs/UI_DESIGN.md` §2.1：14 / 22 / 28 / 40 dp 四级阶梯 + 胶囊）。
 *
 * Compose 没有原生 squircle（超椭圆），用「半径足够大的圆角」近似 iOS 观感。
 * **全 App 只允许这四档 + 胶囊**：混用圆角（按钮 4dp、卡片 12dp、输入框全圆）
 * 是最容易让人看出「像七个时期拼出来的」的地方。
 *
 * ## 为什么从 12 / 20 / 28 / 36 调到 14 / 22 / 28 / 40（UI-6）
 *
 * 这不是「圆一点更好看」的随手调参，而是三件事：
 *
 * 1. **向 iOS 26 的实际半径靠拢**：iOS 26 的控件圆角比前代明显更大，
 *    12dp 的 chip 在 Android 上看着「方」，14dp 才进入同一观感区间；
 * 2. **拉开两端**：小档 +2、大档 +4，让「小控件更软」和「大容器更圆」都更成立。
 *    ⚠️ 这**不是**为了让档间差值均匀（实际是 8 / 6 / 12，反而更不均匀）——
 *    均匀差值从来不是目标，**看起来连续**才是；
 * 3. **零调用点改动**：四档仍是四个具名 [Shape]，只改数值，全 App 跟着变。
 *
 * 比引入自定义 squircle（超椭圆 n≈4）便宜得多，而后者在没有真机比对前风险更高。
 *
 * ⚠️ `sheet` 刻意保持 28dp：底部 Sheet 的圆角要与「屏幕圆角」同一套视觉语言，
 * 而屏幕圆角不受我们控制，改动它会让 Sheet 与屏幕边缘看起来不同心。
 */
object Radius {
    /** 14dp：小控件 —— chip / 分段项 / 小按钮 / 缩略图 */
    val chip: Shape = RoundedCornerShape(14.dp)

    /** 22dp：卡片 —— 容器卡 / 玻璃卡 / 信息卡 / 列表项 */
    val card: Shape = RoundedCornerShape(22.dp)

    /** 28dp：抽屉 —— 底部参数面板 / ModalBottomSheet（与屏幕圆角对齐，勿改） */
    val sheet: Shape = RoundedCornerShape(28.dp)

    /**
     * 40dp：外壳 —— 大容器 / 首屏展示位。
     *
     * ⚠️ **不含照片预览框**。预览框的圆角走 [chip]（14dp），因为照片是**内容**、
     * 不是容器 —— 给内容套容器半径，等于用装饰啃掉画面四角（边角常带有效信息）。
     * 见 `EditorScreen` 预览区的 KDoc 与 `docs/UI_DESIGN.md` §4.0.3 #3。
     */
    val shell: Shape = RoundedCornerShape(40.dp)

    /** 胶囊：悬浮工具条 / 提示条 / 滑块轨道 / 圆形按钮。不参与圆角层级，单独成类 */
    val pill: Shape = RoundedCornerShape(percent = 50)
}
