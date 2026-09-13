package com.hifn.pixelcake.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 间距 token（`docs/UI_DESIGN.md` §2.4 验收清单第 1 条）。
 *
 * **只允许这 7 个值**：4 / 8 / 12 / 16 / 24 / 32 / 48。
 * 出现 13 / 17 / 19 / 22 这类「视觉噪音数字」即视为违规 —— 实测这一项对「整洁感」的
 * 贡献最大，远超配色与字体：随机的间距会让界面瞬间显出「拼凑感」。
 *
 * 语义名（`page` / `cardInner` / `cardGap` / `sectionGap`）优先于尺寸名使用，
 * 这样调整全局密度时只改一处。
 */
object Spacing {
    /** 4dp：图标与文字、chip 内部间隙 */
    val xs = 4.dp

    /** 8dp：同组元素之间的最小间隔 */
    val s = 8.dp

    /** 12dp：卡片之间、列表项之间 */
    val m = 12.dp

    /** 16dp：卡片内边距 */
    val l = 16.dp

    /** 24dp：分组之间、页面左右边距 */
    val xl = 24.dp

    /** 32dp：页面顶部/底部大留白 */
    val xxl = 32.dp

    /** 48dp：空态与首屏的呼吸空间 */
    val xxxl = 48.dp

    /** 页面左右安全边距。加大留白是「高级感」最廉价也最有效的来源 */
    val page = xl

    /** 卡片内边距 */
    val cardInner = l

    /** 卡片之间的竖向间距 */
    val cardGap = m

    /** 分组标题与下一分组的间距 */
    val sectionGap = xl

    /** 可点击控件的最小高度（避免误触） */
    val controlHeight = 44.dp
}
