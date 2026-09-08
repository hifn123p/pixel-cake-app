package com.hifn.pixelcake.arw

import android.content.Context
import android.net.Uri
import com.hifn.pixelcake.core.decode.DecodedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P1b 接入点：RAW 全分辨率解码。
 *
 * 当前 LibRaw NDK 尚未接入，临时回退到内嵌预览解码（与 M0b/P1a 行为一致），
 * 仅在此处集中切换。待 NDK 就绪后实现 [decodeViaLibRaw] 的 JNI 调用即可，
 * 上层 [com.hifn.pixelcake.core.decode.Decoder] 无需改动。
 *
 * 注意：A7C II 内嵌预览仅约 1616px，全量解码（~33MP 线性）才是 P1b 的目标。
 */
object ArwFullDecoder {

    /** P1b 总开关：false=走 Kotlin 预览回退；true=走 LibRaw 全量解码（待实现）。 */
    var useLibRaw: Boolean = false

    suspend fun decodeFull(context: Context, uri: Uri, longEdge: Int): DecodedImage? =
        if (useLibRaw) decodeViaLibRaw(context, uri, longEdge)
        else decodeViaPreview(context, uri, longEdge)

    private suspend fun decodeViaPreview(context: Context, uri: Uri, longEdge: Int): DecodedImage? =
        withContext(Dispatchers.IO) {
            val bmp = ArwPreviewDecoder.decodePreview(context, uri, longEdge) ?: return@withContext null
            DecodedImage(bmp, "image/arw", bmp.width, bmp.height, true)
        }

    // TODO(P1b): 经 JNI 调 LibRaw 全量解码 ARW，返回默认色彩空间 Bitmap（必要时线性化以便 EditEngine 处理）。
    private suspend fun decodeViaLibRaw(context: Context, uri: Uri, longEdge: Int): DecodedImage? {
        // 占位：NDK 未接入前回退到预览，保证现有链路不中断。
        return decodeViaPreview(context, uri, longEdge)
    }
}
