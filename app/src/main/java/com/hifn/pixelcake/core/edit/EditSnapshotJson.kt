package com.hifn.pixelcake.core.edit

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 参数栈的**可持久化文档**（T1，审计 P1-11）。
 *
 * 就是 [EditSnapshot]（params + retouch + layers）套一个版本号：
 * 「另存自定义预设」「项目/会话文件」「P3 给 Rust 引擎的契约」三件事的共同前提
 * —— **没有格式就没有持久化**（DEV_PLAN §3.3）。本文件是⇄ JSON 的**唯一入口**：
 * 调用方不直接碰 `Json` 实例，键名/版本/迁移策略才能只在一份代码里说清楚。
 *
 * ## 兼容性纪律（与 [com.hifn.pixelcake.ui.settings.migrateSettings] 同一条思路）
 *
 * - **新增字段**：data class 上必须带默认值 ⇒ 旧 JSON 读不到新键自然回落默认值，
 *   这就是向前兼容本身，不需要写迁移段；
 * - **删除 / 改义字段**：JSON 键还是那个键、含义变了才必须迁移 ——
 *   在 [EditSnapshotJson.migrate] 里加一段 `if (from < N)`，只描述 N-1 → N 的变化；
 * - **未知键**：[EditSnapshotJson] 的 `ignoreUnknownKeys = true` ⇒ 新版本写的 JSON
 *   被旧版本 App 读到时不会炸（丢弃即可，宁可丢编辑状态也不要崩）。
 *
 * ## 序列化只加注解、不改语义
 *
 * `EditParams` / [RetouchState] / [ObjectLayer] 及其嵌套全部只加了 `@Serializable`，
 * JSON 键 = 字段名（不写 `@SerialName` 别名）—— 键名与 KDoc 一一对应，排查问题时
 * 「JSON 里的键」能直接 grep 到字段。**全量编码**（`encodeDefaults = true`）：键集自描述、
 * 可 diff，且 `version` 永远显式在档（缺了它，升版本后的迁移从根上失效）。
 */
@Serializable
data class EditDocument(
    val version: Int = EditSnapshotJson.CURRENT_VERSION,
    val params: EditParams = EditParams(),
    val retouch: RetouchState = RetouchState(),
    val layers: List<ObjectLayer> = emptyList()
)

/**
 * [EditDocument] ⇄ JSON 字符串的编解码与迁移入口。
 */
object EditSnapshotJson {

    /** 当前文档格式版本。**每次改字段含义（而非新增字段）时 +1 并写迁移段。** */
    const val CURRENT_VERSION = 1

    private val json = Json {
        // 未知键 = 更新版本的文档被旧代码读到：丢弃该键继续读，绝不抛错
        ignoreUnknownKeys = true
        // **全量编码**（含默认值）。这必须是持久化格式的默认选择：
        // ① `version` 等于默认值时若被省略，将来升到 v2 后，v1 文档会被静默解码成 v2
        //    —— 迁移从根上失效；② 键集自描述，可 diff、可归档。
        // 代价是中性编辑的存档从「几十字节」涨到 ~1.5KB —— 对预设/项目文件无感。
        encodeDefaults = true
        isLenient = false
    }

    /** 编码。永不失败（全部字段是基本类型与 List）。 */
    fun encode(document: EditDocument): String = json.encodeToString(EditDocument.serializer(), document)

    /**
     * 解码 + 迁移。
     *
     * @return null 仅当字符串根本不是本格式的 JSON（手改坏 / 别的 App 的文件）——
     *   调用方应按「文件损坏」处理，**而不是**静默回落到全新空白编辑：
     *   拿默认值覆盖用户的编辑状态比拒绝打开危险得多。
     */
    fun decode(text: String): EditDocument? {
        val doc = try {
            json.decodeFromString(EditDocument.serializer(), text)
        } catch (t: Throwable) {
            // SerializationException / IllegalArgumentException 都可能：统一按「不是本格式」算。
            // 本函数非挂起、无 CE 风险，catch Throwable 不违反「CE 重抛」闸门。
            return null
        }
        return migrate(doc)
    }

    /**
     * 逐段迁移（v0/v1 → CURRENT）。
     *
     * 当前状态：v1 是初始版本，迁移为恒等。**写法模板**（与 AppSettings.migrateSettings 同构）：
     * `if (from < N) { …N-1→N 的变换… }` —— 只描述一步的变化，老用户跳版本时逐段补跑。
     */
    private fun migrate(doc: EditDocument): EditDocument {
        val from = doc.version
        if (from >= CURRENT_VERSION) return doc
        var d = doc
        if (from < 1) {
            // v0 → v1：初始格式本身即 v1，无需变换。占位段保住「逐段迁移」的骨架。
        }
        return d.copy(version = CURRENT_VERSION)
    }
}
