package com.hifn.pixelcake.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.hifn.pixelcake.core.edit.EditParams

/**
 * 「照片上横拖调参」（UI 3.0 双通道调节）的参数登记簿。
 *
 * ## 工作方式
 *
 * Deck 里的每一只 [EditSlider]（逐像素 tonal 参数）在组合期把自己**登记**进这里：
 * 标签、量程、步进、格式化器、以及 `(EditParams, Float) -> EditParams` 的纯函数写回。
 * 照片上的横拖手势（`previewGestures`）取**当前激活**的那条规格（默认 = 本组第一个登记的，
 * 即用户碰过的最后一只——因为 [register] 对新组首个登记者自动设为激活），
 * 把拖动位移换算成参数增量后走同一条 `writeParams` 通道。
 *
 * ## 为什么靠「登记」而不是「回调穿参」
 *
 * 滑块 → 分组函数 → 面板 → 编辑器要穿四层，而横拖需要的是 `(EditParams, Float) -> EditParams`
 * 这个**纯函数**加当前值 —— 回调签名会让六个分组函数全部膨胀。登记簿让 EditSlider
 * 在组合期（SideEffect，每次重组都会用最新值刷新）自己报到，零签名变更。
 *
 * ## 生命周期
 *
 * 每对「一级分类 × 二级分组」一个实例（`EditorScreen` 按 key remember）：
 * 切组即换簿 ⇒ 旧组的规格不可能泄漏到新手势里。照片层的手势协程通过 Snapshot 状态
 * 读取最新登记 —— 读侧无需任何同步。
 *
 * ## 边界（v1）
 *
 * 只登记 **tonal**（[EditParams]）参数；人像的 RetouchState 滑块（磨皮/瘦脸）不登记 ——
 * 它们的写回是 `(RetouchState) -> RetouchState`，另一套闭包，留给下一批。
 */
data class ParamAdjustSpec(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val format: (Float) -> String,
    val apply: (EditParams, Float) -> EditParams,
    val value: Float,
)

class ParamAdjustRegistry {
    var specs by mutableStateOf(emptyMap<String, ParamAdjustSpec>())
        private set

    /** 当前被照片横拖控制的参数；组内首个登记者自动成为默认（通常 = 组的第一个滑块）。 */
    var activeLabel by mutableStateOf<String?>(null)
        private set

    fun register(spec: ParamAdjustSpec) {
        specs = specs + (spec.label to spec)
        if (activeLabel == null) activeLabel = spec.label
    }

    /** 横拖每次位移后同步最新值 —— 下一帧的增量基于它累积，不会丢步。 */
    fun updateValue(label: String, value: Float) {
        specs[label]?.let { specs = specs + (label to it.copy(value = value)) }
    }

    fun activeSpec(): ParamAdjustSpec? = activeLabel?.let { specs[it] }
}

/** 编辑器内 provide；无编辑器场合（预览 / 测试）为 null，[EditSlider] 据此跳过登记。 */
val LocalParamAdjust = staticCompositionLocalOf<ParamAdjustRegistry?> { null }
