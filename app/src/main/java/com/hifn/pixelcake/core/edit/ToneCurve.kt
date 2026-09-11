package com.hifn.pixelcake.core.edit

/**
 * 亮度曲线（luma curve）控制点的换算与存取。
 *
 * 引擎侧 [PixelProgram] 已支持 `EditParams.lumaPoints` → [ColorMath.buildLumaLut]（256 项 LUT），
 * 但 UI 若直接操作「任意长度控制点数组」会很笨重。这里把曲线收敛为**三点锚点模型**：
 * 黑场 `(0, black)`、中间调 `(128, mid)`、白场 `(255, white)`，与 DEV_PLAN §3.3 的
 * `{"op":"curves","params":{"luma":[[0,0],[128,140],[255,255]]}}` 完全同构。
 *
 * 抽成独立纯对象（零 Android 依赖）的两个理由：
 *  - **可 JVM 单测**：锚点 ↔ 控制点的映射写错会让曲线整体跑偏，且真机上很不容易看出来；
 *  - **UI 与预设共用**：预设若日后要带曲线，直接塞 [points] 的产物即可，口径一致。
 */
object ToneCurve {

    const val BLACK_X = 0
    const val MID_X = 128
    const val WHITE_X = 255

    const val BLACK_DEFAULT = 0
    const val MID_DEFAULT = 128
    const val WHITE_DEFAULT = 255

    /** UI 的滑块区间（也供 UI 直接引用，避免两处写死不一致）。 */
    val BLACK_RANGE = 0f..48f
    val MID_RANGE = 64f..192f
    val WHITE_RANGE = 208f..255f

    /** 恒等曲线（默认态）。 */
    val IDENTITY: List<Pair<Int, Int>> =
        listOf(BLACK_X to BLACK_DEFAULT, MID_X to MID_DEFAULT, WHITE_X to WHITE_DEFAULT)

    /** 三个锚点 → 控制点列表（各值钳到 0..255）。 */
    fun points(black: Int, mid: Int, white: Int): List<Pair<Int, Int>> = listOf(
        BLACK_X to black.coerceIn(0, 255),
        MID_X to mid.coerceIn(0, 255),
        WHITE_X to white.coerceIn(0, 255)
    )

    fun black(points: List<Pair<Int, Int>>): Int = anchor(points, BLACK_X, BLACK_DEFAULT)
    fun mid(points: List<Pair<Int, Int>>): Int = anchor(points, MID_X, MID_DEFAULT)
    fun white(points: List<Pair<Int, Int>>): Int = anchor(points, WHITE_X, WHITE_DEFAULT)

    /** 是否为恒等曲线（UI 据此决定要不要显示「重置曲线」）。 */
    fun isIdentity(points: List<Pair<Int, Int>>): Boolean =
        black(points) == BLACK_DEFAULT && mid(points) == MID_DEFAULT && white(points) == WHITE_DEFAULT

    /** 缺锚点（例如别处塞进来的自定义曲线）时回退到恒等值，绝不让 UI 出现空洞。 */
    private fun anchor(points: List<Pair<Int, Int>>, x: Int, fallback: Int): Int =
        points.firstOrNull { it.first == x }?.second ?: fallback
}
