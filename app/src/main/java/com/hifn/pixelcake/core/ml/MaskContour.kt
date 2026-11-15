package com.hifn.pixelcake.core.ml

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 蒙版**等值线**提取（marching squares，v0.7.2：拾取可视化「琥珀虚线轮廓」的几何来源）。
 *
 * ## 为什么自己写而不是用 Path 检测
 *
 * 输入是 256² 的低分辨率概率网格（[FloatGrid]），轮廓 = 「概率 > [threshold]」区域的边界。
 * Android 没有现成的网格等值线 API，而 marching squares 是这类问题的教科书解法：
 * 逐格判定 2×2 采样点的内外状态（16 种 case），在格边上生成线段，最后把线段**链成闭环**。
 *
 * ## 输出约定
 *
 * 返回若干条**闭合**折线（外轮廓与孔洞都是闭环）；顶点是**归一化图像坐标**（∈ [0,1]²），
 * 与 `ObjectMasks.scopeAt` / 蒙版渲染同一直角（推理输入是整图拉伸，网格坐标即图像坐标）。
 * 线性插值让轮廓在格内亚像素平滑，比「阶梯式单元格描边」好看且与羽化蒙版的视觉边界一致。
 *
 * 纯 Kotlin、零 Android 依赖（JVM 单测钉住：方块 → 一个四角闭环；空网格 → 零条）。
 */
object MaskContour {

    /** 闭环链接的端点吸附容差（归一化坐标）；marching squares 生成的端点是格边上的精确交点。 */
    private const val SNAP = 1e-4f

    /**
     * 提取等值线。
     *
     * @param grid 概率网格（值域 [0,1]）
     * @param threshold 内外判定阈值；与拾取可视化的填充透明度口径一致即可（默认 0.5）
     * @return 闭合折线列表；顶点 = (u, v) 归一化图像坐标。网格全空/全满时返回空/单条全边界环。
     */
    fun contours(grid: FloatGrid, threshold: Float = 0.5f): List<List<Pair<Float, Float>>> {
        val w = grid.w
        val h = grid.h
        if (w < 2 || h < 2) return emptyList()

        // 每条线段 = 一对归一化端点。marching squares 逐格产出，随后按端点吸附链成环。
        val segments = ArrayList<Pair<Pair<Float, Float>, Pair<Float, Float>>>(1024)

        // ⚠️ 网格**外**视为 0（蒙版语义：图像之外没有蒙版）—— 这样「全满网格」的等值线
        // 恰好落在网格边缘，而不是数学上跑到网格外去（无 padding 时全满 = 零交叉 = 零轮廓）。
        fun sample(x: Int, y: Int): Float =
            if (x in 0 until w && y in 0 until h) grid.data[y * w + x] else 0f

        // 格边上的插值交点：两采样点 a(内/外) b，t = (threshold - a) / (b - a)
        fun lerp(ax: Int, ay: Int, bx: Int, by: Int, va: Float, vb: Float): Pair<Float, Float> {
            val t = if (abs(vb - va) < 1e-9f) 0.5f else ((threshold - va) / (vb - va)).coerceIn(0f, 1f)
            val gx = ax + (bx - ax) * t
            val gy = ay + (by - ay) * t
            // 顶点 → 归一化图像坐标（网格铺满整图）；padding 区的交点 clamp 回 [0,1]
            return Pair(
                (gx / (w - 1).coerceAtLeast(1)).coerceIn(0f, 1f),
                (gy / (h - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            )
        }

        // 逐格扫描：格子由四角采样点 (x..x+1, y..y+1) 定义，含一圈 padding 区
        for (y in -1 until h) {
            for (x in -1 until w) {
                // 四角采样：tl / tr / br / bl
                val tl = sample(x, y)
                val tr = sample(x + 1, y)
                val br = sample(x + 1, y + 1)
                val bl = sample(x, y + 1)
                var code = 0
                if (tl > threshold) code = code or 8
                if (tr > threshold) code = code or 4
                if (br > threshold) code = code or 2
                if (bl > threshold) code = code or 1
                if (code == 0 || code == 15) continue

                // 四条格边上的交点（只在跨阈值的边上存在）
                val top = lerp(x, y, x + 1, y, tl, tr)
                val right = lerp(x + 1, y, x + 1, y + 1, tr, br)
                val bottom = lerp(x, y + 1, x + 1, y + 1, bl, br)
                val left = lerp(x, y, x, y + 1, tl, bl)

                // 16 种 case（等价 case 已按「内区域在右手侧」统一方向，链环时只按端点吸附、不看方向）
                when (code) {
                    1, 14 -> seg(segments, left, bottom)
                    2, 13 -> seg(segments, bottom, right)
                    3, 12 -> seg(segments, left, right)
                    4, 11 -> seg(segments, top, right)
                    6, 9 -> seg(segments, top, bottom)
                    7, 8 -> seg(segments, left, top)
                    5 -> { seg(segments, left, top); seg(segments, bottom, right) }
                    10 -> { seg(segments, top, right); seg(segments, left, bottom) }
                }
            }
        }
        return chainLoops(segments)
    }

    private fun seg(
        out: MutableList<Pair<Pair<Float, Float>, Pair<Float, Float>>>,
        a: Pair<Float, Float>,
        b: Pair<Float, Float>
    ) {
        out.add(Pair(a, b))
    }

    /**
     * 把无向线段集合链成闭环：从任一线段出发，按端点吸附（容差 [SNAP]）寻找下一段，
     * 回到起点即成环。理论上 marching squares 的线段恰好两两配对成环；数值噪声导致的
     * 断链段直接丢弃（视觉上等价于把噪声缺口抹平，不会产生开放折线）。
     */
    private fun chainLoops(
        segments: List<Pair<Pair<Float, Float>, Pair<Float, Float>>>
    ): List<List<Pair<Float, Float>>> {
        if (segments.isEmpty()) return emptyList()
        val used = BooleanArray(segments.size)
        val loops = ArrayList<List<Pair<Float, Float>>>(4)

        fun near(a: Pair<Float, Float>, b: Pair<Float, Float>): Boolean =
            abs(a.first - b.first) < SNAP && abs(a.second - b.second) < SNAP

        for (start in segments.indices) {
            if (used[start]) continue
            used[start] = true
            val loop = ArrayList<Pair<Float, Float>>(64)
            var cur = segments[start].second
            loop.add(segments[start].first)
            var closed = false
            for (guard in 0 until segments.size) {
                loop.add(cur)
                if (near(cur, loop.first())) {
                    closed = true
                    break
                }
                var next: Pair<Float, Float>? = null
                for (cand in segments.indices) {
                    if (used[cand]) continue
                    val (a, b) = segments[cand]
                    if (near(cur, a)) {
                        used[cand] = true
                        next = b
                        break
                    }
                    if (near(cur, b)) {
                        used[cand] = true
                        next = a
                        break
                    }
                }
                val n = next ?: break // 断链（数值噪声）：放弃这条不完整的环
                cur = n
            }
            // 环至少 3 个顶点才成「轮廓」；退化环（单格交点重复）丢弃
            if (closed && loop.size >= 3) {
                loops.add(loop.map { (u, v) ->
                    Pair(
                        ((u * 10000f).roundToInt() / 10000f),
                        ((v * 10000f).roundToInt() / 10000f)
                    )
                })
            }
        }
        return loops
    }
}
