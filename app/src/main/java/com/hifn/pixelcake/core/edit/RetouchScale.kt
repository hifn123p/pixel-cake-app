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

    /**
     * 由归一化画笔描迹构建**画笔蒙版**（尺寸对齐目标图）。
     *
     * **无描迹时返回 `null`** —— 表示「用户未圈定局部作用域」。至于「未圈定」等价于**整幅**
     * （编辑器，见 [editorSkinMask]）还是**不执行**（相机批量链路直接传 `null`），由**调用点**决定。
     * 尺寸非法（`w/h <= 0`）时同样返回 `null`（无处可施加）。
     */
    fun brushMask(
        w: Int,
        h: Int,
        strokes: List<Pair<Float, Float>>,
        radiusNorm: Float
    ): RetouchMask? {
        if (w <= 0 || h <= 0 || strokes.isEmpty()) return null
        val minDim = minOf(w, h)
        val radius = (radiusNorm * minDim).toInt().coerceAtLeast(1)
        val brushStrokes = strokes.map { (nx, ny) ->
            BrushStroke((nx * w).toInt(), (ny * h).toInt(), radius)
        }
        return RasterMask.fromStrokes(w, h, brushStrokes)
    }

    /**
     * 「无自动蒙版」时的皮肤蒙版口径（P1b 旧行为，仍保留给不需要 ML 的调用方与单测）。
     *
     * **无描迹时返回 [FullMask]（作用域 = 整幅），而不是 `null`。** 这是「调用方显式声明作用域」的落点：
     * 编辑器里用户没画画笔时，磨皮/液化本就该作用于整幅（滑杆一拖就有可见效果），由**调用方**表达这个
     * 意图；`null` 则专门留给「不执行」——相机批量链路显式传 `null`，避免把背景一起磨/形变。
     * 语义约定见 [RetouchMask] KDoc 与 `docs/P1b_DESIGN.md` §4。
     *
     * 只有尺寸非法（`w/h <= 0`，无处可施加）时才返回 `null`。
     */
    fun skinMask(
        w: Int,
        h: Int,
        strokes: List<Pair<Float, Float>>,
        radiusNorm: Float
    ): RetouchMask? {
        if (w <= 0 || h <= 0) return null
        return brushMask(w, h, strokes, radiusNorm) ?: FullMask
    }

    /**
     * **编辑器**皮肤蒙版口径（P1p-1b）：把「自动蒙版（ML）」与「画笔描迹」合成为最终作用域。
     * 口径见 `docs/P1p_DESIGN.md` §7。
     *
     * - [autoMask] 为 `null`（自动蒙版关闭 / 模型不可用）：退回 P1 行为 —— 无描迹 ⇒ [FullMask]
     *   （滑杆即有可见效果），有描迹 ⇒ 画笔栅格；
     * - [autoMask] 非 `null`：无描迹 ⇒ **直接用 ML 蒙版**（不再退化为整幅，否则自动蒙版形同虚设）；
     *   有描迹 ⇒ `max(ML, 画笔)`（画笔是用户**显式补正**，只能扩大作用域，见 [MaxMask]）。
     *
     * 与相机批量链路的差异就写在**调用点**：那边显式传 `null` ⇒ 皮肤类算子根本不执行。
     */
    fun editorSkinMask(
        w: Int,
        h: Int,
        strokes: List<Pair<Float, Float>>,
        radiusNorm: Float,
        autoMask: RetouchMask?
    ): RetouchMask? {
        if (w <= 0 || h <= 0) return null
        // 自动蒙版关闭：完全等同 P1 旧口径，直接复用 [skinMask]，避免出现两条并行实现。
        if (autoMask == null) return skinMask(w, h, strokes, radiusNorm)
        return mergeMasks(autoMask.resampleTo(w, h), brushMask(w, h, strokes, radiusNorm))
    }

    /**
     * 把「ML 皮肤蒙版」与「画笔描迹蒙版」合并（P1p-1，口径见 `docs/P1p_DESIGN.md` §7）。
     *
     * 逐点取最大（[MaxMask]）：画笔是用户**显式补正**，取 `max` 才符合直觉。
     * 任一侧为 `null` 时返回另一侧；两侧都 `null` 才返回 `null`（= 该算子不执行）。
     */
    fun mergeMasks(ml: RetouchMask?, brush: RetouchMask?): RetouchMask? = when {
        ml == null -> brush
        brush == null -> ml
        else -> MaxMask(ml, brush)
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
