package com.hifn.pixelcake.camera

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P2 PoC-2/3：对**已建立的会话**做一次「协议体检」，产出可回传的 [CameraPtpReport]。
 *
 * 为什么不再自己开会话：会话建立（授权 → 打开设备 → `OpenSession`）现在统一由
 * [CameraSession.open] 负责，界面持有一个长生命周期会话复用于列图/拉图/批量。
 * 若体检再自己开一次会话，就会出现两条会话建立路径（且要额外握手一轮，真机上更慢也更易失败）。
 * 所以这里只做「体检」这一件事：枚举每个存储的对象数 + 采样末尾若干条 `ObjectInfo`，
 * 把连接步骤一并收进报告。
 *
 * **全程只读**：只发 `GetObjectHandles` / `GetObjectInfo`，不写相机、不删文件。
 * 拉取文件与批处理见 [CameraSession.download] 与 [CameraBatch]。
 */
object CameraConnection {

    /** 每个存储采样多少个对象（取句柄尾部——句柄通常按时间递增，尾部即最新）。 */
    private const val SAMPLE_OBJECTS = 8

    /**
     * 体检。
     *
     * @param steps 会话建立过程中收集到的原始步骤描述（未编号），会被编号后并入报告
     */
    suspend fun inspect(
        session: CameraSession,
        steps: List<String> = emptyList()
    ): CameraPtpReport = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val log = ArrayList<String>(steps.size + 4)

        fun note(message: String) {
            log.add("${log.size + 1}) $message")
        }

        for (step in steps) note(step)

        val handleCounts = LinkedHashMap<Int, Int>()
        val handleLists = LinkedHashMap<Int, List<Int>>()
        for (storageId in session.storageIds) {
            val query = session.objectHandles(storageId)
            handleCounts[storageId] = query.handles.size
            handleLists[storageId] = query.handles
            note("存储 ${PtpProtocol.hex8(storageId)}：对象 ${query.handles.size} 个（${query.mode}）")
        }

        val samples = ArrayList<PtpObjectInfo>()
        for (handles in handleLists.values) {
            if (handles.isEmpty()) continue
            for (handle in handles.takeLast(SAMPLE_OBJECTS)) {
                session.objectInfo(handle)?.let { samples.add(it) }
            }
        }
        note("对象采样完成：${samples.size} 个（照片候选 ${samples.count { CameraPhotoFilter.isPhoto(it) }}）")

        CameraPtpReport(
            deviceLabel = session.deviceLabel,
            steps = log,
            openSessionCode = session.openSessionCode,
            deviceInfo = session.deviceInfo,
            storages = session.storages,
            handleCounts = handleCounts.toMap(),
            handleQueryMode = session.handleQueryMode,
            samples = samples.toList(),
            elapsedMs = System.currentTimeMillis() - startedAt,
            notices = emptyList(),
            failure = null
        )
    }
}
