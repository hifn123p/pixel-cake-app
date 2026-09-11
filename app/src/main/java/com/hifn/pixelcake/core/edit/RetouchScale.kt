package com.hifn.pixelcake.core.edit

/**
 * retouch 状态的「分辨率无关 → 渲染态」换算。
 *
 * UI 侧的参数刻意做成分辨率无关（磨皮半径/祛瑕半径都是**归一化**值，蒙版描迹是归一化坐标），
 * 这样同一套参数在 2048 代理预览和 7008 全分辨率导出上得到一致效果。
 * 真正送进渲染器前，必须按目标图尺寸换算成像素：
 *
 * - 磨皮半径 `radiusNorm` → `radiusPx = radiusNorm × min(w, h)`
 * - 祛瑕描迹归一化坐标 → 像素坐标，半径同理
 *
 * 抽成公共对象是因为它有两个调用方：编辑器（预览 + 导出，见 `MainActivity`）
 * 与相机批处理（`camera/CameraBatch`）。两处口径必须完全一致，否则「预览所见 ≠ 导出所得」。
 */
object RetouchScale {

    /**
     * @param w 目标图宽（像素）
     * @param h 目标图高（像素）
     * @param inpaintStrokes 归一化祛瑕描迹（x/y ∈ [0,1]）
     * @param inpaintRadiusNorm 归一化祛瑕半径
     */
    fun toRenderState(
        rt: RetouchState,
        w: Int,
        h: Int,
        inpaintStrokes: List<Pair<Float, Float>> = emptyList(),
        inpaintRadiusNorm: Float = 0.01f
    ): RetouchState {
        val minDim = minOf(w, h).coerceAtLeast(1)
        val inpaint = inpaintStrokes.map { (nx, ny) ->
            InpaintStroke(
                (nx * w).toInt(),
                (ny * h).toInt(),
                (inpaintRadiusNorm * minDim).toInt().coerceAtLeast(1)
            )
        }
        return rt.copy(
            neutralGray = NeutralGrayParams(
                strength = rt.neutralGray.strength,
                radiusPx = (rt.neutralGray.radiusNorm * minDim).toInt().coerceAtLeast(1),
                threshold = rt.neutralGray.threshold
            ),
            inpaint = inpaint
        )
    }

    /** 由归一化画笔描迹构建皮肤蒙版（尺寸对齐目标图）；无描迹时返回 null（表示「不限制」）。 */
    fun skinMask(
        w: Int,
        h: Int,
        strokes: List<Pair<Float, Float>>,
        radiusNorm: Float
    ): RasterMask? {
        if (strokes.isEmpty() || w <= 0 || h <= 0) return null
        val minDim = minOf(w, h)
        val radius = (radiusNorm * minDim).toInt().coerceAtLeast(1)
        val brushStrokes = strokes.map { (nx, ny) ->
            BrushStroke((nx * w).toInt(), (ny * h).toInt(), radius)
        }
        return RasterMask.fromStrokes(w, h, brushStrokes)
    }

    /**
     * 按长边上限算目标尺寸。
     *
     * 相机批处理里源尺寸来自相机自报的 `ObjectInfo.imageWidth/Height`；
     * 若相机没给（为 0），退化为 [longEdge] × [longEdge]——只会让归一化半径偏小（效果变温和），
     * 不会崩，也绝不会越界。
     */
    fun fitLongEdge(srcW: Int, srcH: Int, longEdge: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return longEdge to longEdge
        return if (srcW >= srcH) {
            longEdge to (srcH * longEdge / srcW)
        } else {
            (srcW * longEdge / srcH) to longEdge
        }
    }
}
