package com.hifn.pixelcake.core.camera

import com.hifn.pixelcake.camera.CameraPhoto
import com.hifn.pixelcake.camera.CameraPhotoFilter
import com.hifn.pixelcake.camera.PtpObjectInfo

/**
 * 系留轮询的**纯逻辑状态机**（批 5 可测化，hy4 阶段 2 判据 11）。
 *
 * 从 `CameraPanel` 的 `LaunchedEffect` 里抽出「哪些对象是新的、哪些该记账跳过」的决策，
 * 让 K1/K2/K3 三条判据各自有一条**会红的 JVM 回归测试**（假设备：句柄与对象信息全用假数据）：
 * - **K2 基线播种**：开系留时卡上已有的对象全部记为已知，绝不把旧片当新片处理；
 * - **K3 坏对象记账**：读不到 Info / 非照片的对象**先记账再跳过** —— 否则它会永远
 *   占住每轮 [maxPerRound] 的名额，新片排在后面永远进不来；
 * - **K1 路由契约**：交给处理回调的 [CameraPhoto] 携带 `isRaw` 标记（ARW → LibRaw
 *   线性管线的路由依据），由单测钉住「RAW 对象必须以 `isRaw == true` 交付」。
 *
 * 下载 / 渲染 / 导出留在 UI 层（要 Android 上下文），本类只回答「这一轮该处理谁」。
 */
class TetherProcessor(
    /** 到达流日志（界面可见）；实现方负责上界。 */
    private val log: (String) -> Unit,
    /** 每轮最多处理多少张（保持旧口径：单轮 3 张，避免长卡拖住轮询节奏）。 */
    private val maxPerRound: Int = 3
) {
    private val known = LinkedHashSet<Int>()

    /** 已记账的对象数（诊断用）。 */
    val knownCount: Int get() = known.size

    /** 下载失败计数（句柄 → 已失败次数）；只对「传输失败」的交还重试有意义。 */
    private val failCounts = HashMap<Int, Int>()

    /**
     * K2：开系留先播种基线 —— 卡上已有的对象全部记为已知。
     * 失败（PTP 失步/拔线）时调用方**不得进入轮询**：无基线的轮询会把全卡当新片。
     */
    fun seedBaseline(handles: Collection<Int>) {
        known.addAll(handles)
    }

    /**
     * 下载失败后**交还**句柄（v0.7.2，审计 C-F3）：下一轮重新进入候选 ——
     * 「先记账后下载」的原口径会把一次 USB 抖动变成这张照片**永久漏导**，而唯一的
     * 痕迹是一行会被滚出显示区的日志。
     *
     * 重试有上界（[MAX_RETRY_PER_PHOTO]）：持续坏对象在记满次数后保持记账、不再
     * 占每轮名额 —— 否则它会永远挤在 [maxPerRound] 的前排，新片反而进不来（K3 同源）。
     * **只对传输失败调用**：RAW 渲染失败是确定性错误，重试只会反复白白下载整张卡。
     */
    fun releaseForRetry(handle: Int) {
        val n = (failCounts[handle] ?: 0) + 1
        failCounts[handle] = n
        if (n >= MAX_RETRY_PER_PHOTO) {
            known.add(handle) // 记满：保持记账，停止重试
            log("对象 #$handle 连续传输失败 $n 次，停止自动重试")
        } else {
            known.remove(handle)
            log("对象 #$handle 传输失败，将在下轮重试（$n/$MAX_RETRY_PER_PHOTO）")
        }
    }

    /**
     * 一轮轮询：返回本轮要处理的**新照片**（已按 [CameraPhotoFilter.isPhoto] 过滤）。
     *
     * @param handles 当前卡上的全部对象句柄
     * @param objectInfo 句柄 → 对象信息；null = 读不到（K3：记账后跳过）
     */
    suspend fun collectNewPhotos(
        handles: List<Int>,
        objectInfo: suspend (Int) -> PtpObjectInfo?
    ): List<CameraPhoto> {
        val fresh = handles.filter { it !in known }
        val out = ArrayList<CameraPhoto>(minOf(fresh.size, maxPerRound))
        for (h in fresh.take(maxPerRound)) {
            val info = objectInfo(h)
            if (info == null) {
                // K3：坏对象也要记账 —— 否则它每轮都出现在 fresh 里，永久占位。
                known.add(h)
                log("跳过无法读取信息的对象 #$h")
                continue
            }
            if (!CameraPhotoFilter.isPhoto(info)) {
                // 非照片（目录 / 主题包等）：记账后静默跳过 —— 这是正常对象，不是错误。
                known.add(h)
                continue
            }
            known.add(h)
            out.add(CameraPhoto(h, info))
        }
        return out
    }

    companion object {
        /** 单张照片的最大自动重试次数（含首次失败）。 */
        const val MAX_RETRY_PER_PHOTO = 3
    }
}
