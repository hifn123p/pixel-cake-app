package com.hifn.pixelcake.core.edit

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 编辑会话的**可持久化记录**（v0.7.2：进程死亡恢复，§19.4 遗留项的全量落地）。
 *
 * ## 解决什么
 *
 * AppRoot 的全部状态都是 `remember`（无 ViewModel），进程在后台被杀后用户回来，
 * 只剩一张空首页 —— RAW 重导 + 整段重修。本记录把「还原图」所需的最小集合持久化：
 * 源 uri + 旋转圈数 + **整条编辑时间线**（含撤销/重做位置）。恢复时重新解码源图、
 * 从 pristine 重推旋转、重建历史 —— 会话无缝续上。
 *
 * ## 与 [EditDocument] 的关系
 *
 * 时间线的每一步复用 [EditDocument]（params/retouch/layers 的既有 JSON 文档，
 * T1 的唯一入口），本类只补「uri + 旋转 + 时间线位置」三个外壳字段 ——
 * JSON 兼容性纪律（新增字段带默认值）原样继承。
 *
 * ## 持久化时机与清除（调用方契约，见 MainActivity）
 *
 * - **写**：编辑器打开期间，可观察状态（params/retouch/layers/旋转/uri）每次变化
 *   防抖落盘（编辑是低频写、SharedPreferences 单键覆盖，无放大风险）；
 * - **清**：`editorOnBack`（用户主动退出）与换图瞬间 —— 前者是「会话结束」的语义，
 *   后者由新会话的首笔写入覆盖；App 冷启动恢复成功后同样清除（一次性凭据）。
 * - **权限**：源 uri 必须是可持久化授权（SAF / Photo Picker 均可 `takePersistableUriPermission`）；
 *   不可持久化的授权（部分机型 picker 拒绝）恢复时解码失败 ⇒ 按损坏处理清除，不留幽灵会话。
 */
@Serializable
data class SessionRecord(
    val version: Int = SessionJson.CURRENT_VERSION,
    /** 源图 uri（字符串形式；恢复时 parse 回 Uri） */
    val srcUri: String,
    /** 旋转圈数（0..3）。恢复时从 pristine 重推，零累积损失 —— 与会话内旋转同一条路径 */
    val rotationQuarterTurns: Int = 0,
    /** 时间线中「当前态」的下标（= EditHistory.step） */
    val position: Int = 0,
    /** 整条编辑时间线（undo 栈 + 当前 + redo 栈，时间序） */
    val timeline: List<EditDocument> = emptyList()
)

/**
 * [SessionRecord] ⇄ JSON 的唯一入口（与 [EditSnapshotJson] 同一套兼容性纪律）。
 */
object SessionJson {

    const val CURRENT_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }

    /** 编码。永不失败（全部字段是基本类型与 List）。 */
    fun encode(record: SessionRecord): String = json.encodeToString(SessionRecord.serializer(), record)

    /**
     * 解码。null = 不是本格式的 JSON（旧版本残留 / 手改坏）—— 调用方按「无会话」处理。
     * 时间线为空或 position 越界的记录同样视为无效（半截写入的保护）。
     */
    fun decode(text: String): SessionRecord? {
        val record = try {
            json.decodeFromString(SessionRecord.serializer(), text)
        } catch (t: Throwable) {
            return null
        }
        if (record.srcUri.isBlank() || record.timeline.isEmpty() ||
            record.position < 0 || record.position >= record.timeline.size
        ) return null
        return record
    }
}
