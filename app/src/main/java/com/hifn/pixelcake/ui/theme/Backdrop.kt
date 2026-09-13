package com.hifn.pixelcake.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 静态模糊底图（`docs/UI_DESIGN.md` §6 / UI-5）。
 *
 * ## 为什么必须有它，以及为什么它**不是**实时模糊
 *
 * 真·毛玻璃（backdrop blur）要把下层内容渲进一层再采样，在大图上极贵；滚动/拖动滑块时
 * 每帧重算必然掉帧。但修图 App 的玻璃永远浮在一张**静止的预览图**之上 —— 所以正确解法是：
 *
 * 1. **一次性**把照片缩到 28px 宽、做两轮盒式模糊、存成一张小图（约 500 字节像素）；
 * 2. 绘制时把它拉伸铺满（双线性过滤），视觉上就是一张柔和的大面积模糊底；
 * 3. 之后无论怎么滚动、拖动、切页签，这张底图都**不再重算**。
 *
 * 28px 宽放大到 1080px 是约 38 倍拉伸，双线性插值本身就完成了「模糊」的大部分工作，
 * 盒式模糊只是把缩略图里的硬边再抹一遍。代价是**一次性**的、微秒级的，
 * 而不是每帧的、毫秒级的 —— 这是整个改版最重要的性能取舍。
 *
 * ⚠️ 绝不要在拖动/滚动过程中调用它。用 `remember(source)` 钉住即可。
 *
 * @param width 缩略图宽度。28 是实测的「够柔和但仍保留大致色彩分布」的取值。
 */
fun blurredBackdrop(source: Bitmap, width: Int = BACKDROP_WIDTH): ImageBitmap? {
    if (source.width <= 0 || source.height <= 0 || source.isRecycled) return null

    val w = width.coerceIn(1, source.width)
    val h = (source.height.toFloat() * w / source.width).toInt().coerceIn(1, source.height)

    // 用 Canvas 画进一张**全新的** ARGB_8888 位图，而不是 createScaledBitmap：
    // 后者在尺寸相同时会直接返回同一个实例，后续 setPixels 会就地改掉用户的照片。
    val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(small).drawBitmap(
        source, null, Rect(0, 0, w, h),
        Paint().apply { isFilterBitmap = true }
    )

    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    // 两轮半径 1 的盒式模糊 ≈ 高斯模糊的粗略近似。小图上跑，开销可忽略。
    repeat(2) { boxBlur(px, w, h, radius = 1) }
    small.setPixels(px, 0, w, 0, 0, w, h)

    return small.asImageBitmap()
}

/** 缩略图宽度。改动它只影响「柔和程度」，不影响性能量级。 */
private const val BACKDROP_WIDTH = 28

/**
 * 原地盒式模糊（可分离：先横向再纵向）。
 *
 * 边缘用 clamp（`coerceIn`）而不是补 0，否则四周会出现一圈黑边 —— 玻璃底图上的黑边会非常刺眼。
 */
private fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int) {
    val tmp = IntArray(px.size)

    // 横向
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
            for (dx in -radius..radius) {
                val c = px[row + (x + dx).coerceIn(0, w - 1)]
                a += (c ushr 24) and 0xFF
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
            tmp[row + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }

    // 纵向
    for (y in 0 until h) {
        for (x in 0 until w) {
            var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
            for (dy in -radius..radius) {
                val c = tmp[(y + dy).coerceIn(0, h - 1) * w + x]
                a += (c ushr 24) and 0xFF
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
            px[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }
}
