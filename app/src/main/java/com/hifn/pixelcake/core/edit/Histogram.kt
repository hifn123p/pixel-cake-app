package com.hifn.pixelcake.core.edit

/**
 * 亮度直方图（`docs/CURVE_HISTOGRAM_DESIGN.md` §1）。
 *
 * ## 为什么要有它
 *
 * 曲线编辑器只有「点」没有「底」时，用户是在**盲调**：拖一个点，看到画面变暗，
 * 但不知道是整体偏暗还是只有中间调塌了。Lightroom / Capture One 的曲线面板都会
 * 在曲线下面铺一层直方图作为「底」，让用户看见**像素实际堆在哪儿**。
 *
 * ## 三条通道，为什么不做成一条
 *
 * 只画合并亮度会丢掉本项目最需要的信息：**偏色**。一张肤色照片的 R 峰与 B 峰
 * 可能差 40 灰阶，合并后看不见，而这恰恰是「红/绿/蓝三条分通道曲线」要修的东西。
 * 所以 RGB 三条全出，由调用方决定画几条。
 *
 * ## 采样：每 N 个像素取一个
 *
 * 全量统计在 7008×4672 的导出图上是 3277 万次累加，放进预览路径会掉帧。
 * [compute] 的 `step` 参数就是为此存在：预览取 step=8（约 410 万次，仍在 16ms 预算内），
 * 面板打开时的一次性统计取 step=1。**直方图形状对 step 几乎不敏感**（只是幅值变小），
 * 所以这是安全的近似，不是「换个图算」。
 *
 * ## 纯函数、零 Android 依赖
 *
 * 输入是 [r]/[g]/[b] 三条已知的字节序列（RGB_565 之类的打包格式请先在外面解开），
 * 输出是定长 [BINS] 桶。这样它能被 JVM 单测直接钉住，不需要 Robolectric。
 */
object Histogram {

    /** 桶数 = 灰阶数（0..255）。再密对屏幕没有信息增益，只增加绘制开销。 */
    const val BINS = 256

    /**
     * 单通道直方图。
     *
     * @param values 长度 [count] 的 0..255 序列
     * @param count  有效元素个数（[values] 通常是整张位图，长度远大于 count）
     * @param step   采样步长，1 = 全量。必须 ≥ 1。
     */
    fun compute(values: ByteArray, count: Int, step: Int = 1): IntArray {
        require(step >= 1) { "step 必须 ≥ 1，收到 $step" }
        require(count >= 0 && count <= values.size) { "count=$count 越界（values.size=${values.size}）" }
        val bins = IntArray(BINS)
        if (count == 0) return bins
        // 步长大于 count 时仍要采到第 0 个，否则全黑图会被算成空图（UI 会画出一条平线）。
        var i = 0
        while (i < count) {
            bins[values[i].toInt() and 0xFF]++
            i += step
        }
        return bins
    }

    /**
     * 三通道直方图（`order[0]=R, [1]=G, [2]=B`）。
     *
     * 三条序列必须**等长等步**（同一次 [android.graphics.Bitmap.getPixels] 出来的三个分量缓冲区
     * 天然满足）⇒ 遍历只做一次，省掉两次索引边界检查。
     */
    fun computeRgb(
        r: ByteArray,
        g: ByteArray,
        b: ByteArray,
        count: Int,
        step: Int = 1
    ): Triple<IntArray, IntArray, IntArray> =
        Triple(compute(r, count, step), compute(g, count, step), compute(b, count, step))

    /**
     * 把原始桶数归一化到 0..1，供 UI 直接当高度用。
     *
     * ## 为什么要归一化而不是返回计数
     *
     * UI 画的是**比例**，不是绝对像素数：step 变了计数就整体缩放，若返回计数，
     * 每次换采样步长都得在 UI 侧重算比例，多一处容易写错的地方。
     *
     * ## 为什么用**峰值**归一而不是「除以桶数」
     *
     * 除以桶数会把「一张对比很低的照片」画成贴着顶的锯齿（因为分布很窄，
     * 但每桶计数并不小）。除以峰值则是「让最高的那个桶刚好顶满」——
     * 这正是 Lightroom 直方图的观感：**看形状，不看绝对量**。
     *
     * 全零图（纯黑）返回全 0 而不是 NaN，避免 Canvas 画出 NaN 坐标。
     */
    fun normalize(bins: IntArray): FloatArray {
        val out = FloatArray(bins.size)
        var peak = 0
        for (v in bins) if (v > peak) peak = v
        if (peak == 0) return out
        for (i in bins.indices) out[i] = bins[i].toFloat() / peak
        return out
    }

    /**
     * 「这次调整把直方图推动了多少」的量化值 −1..1，> 0 偏亮。
     *
     * 用途：面板顶部显示一个「曝光 +0.7」式的**实时反馈**。它不是精确的平均亮度
     * （那会让人以为这是「平均亮度值」），而是一个只用于「往哪边推了」的指示。
     *
     * 口径 = （调整后平均灰阶 − 调整前平均灰阶）/ 128。分母取 128 而不是满量程 255，
     * 是为了让 ±1 级调整在指示条上占约一半长度 —— 满量程归一会让所有微调都看不出来。
     *
     * 空图 / 两边完全一致返回 0。
     */
    fun meanShift(before: IntArray, after: IntArray): Float {
        if (before.size != after.size || before.isEmpty()) return 0f
        var sumB = 0L
        var sumA = 0L
        var total = 0L
        for (i in before.indices) {
            val nb = before[i]
            val na = after[i]
            val n = (nb + na).toLong()
            if (n == 0L) continue
            sumB += i.toLong() * nb
            sumA += i.toLong() * na
            total += n
        }
        if (total == 0L) return 0f
        return ((sumA - sumB).toDouble() / total / 128.0).toFloat()
    }

    /**
     * 裁掉左右两端各 [clipRatio] 的像素后的中位数灰阶，0..255。
     *
     * 这是**测光表**（点按测光 / 自动曝光）的标准做法：不裁会严重偏向大面积背景
     * （拍人像时背景占了 90% 像素，中位数就等于背景亮度）；不裁右端则逆光时全被高光拉走。
     * 裁掉两端各 5% 是摄影界「中央重点测光」的通用口径。
     */
    fun clippedMedian(bins: IntArray, clipRatio: Float = 0.05f): Int {
        val total = bins.sum()
        if (total <= 0) return 128
        val clip = (total * clipRatio.coerceIn(0f, 0.49f)).toLong()
        var acc = clip
        var accHigh = total - clip
        for (i in bins.indices) {
            acc += bins[i]
            if (acc >= (clip + total) / 2) {
                // 低位端先越过目标即返回（clip 掉的高位样本不影响中位数口径）。
                return i
            }
        }
        // 极端分布（几乎全在同一灰阶）时上面的循环可能不返回，兜底给最亮的非空桶。
        for (i in bins.indices.reversed()) if (bins[i] > 0) return i
        return 128
    }
}