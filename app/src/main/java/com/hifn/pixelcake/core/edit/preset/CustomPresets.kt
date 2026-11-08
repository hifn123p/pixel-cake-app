package com.hifn.pixelcake.core.edit.preset

import com.hifn.pixelcake.core.edit.EditParams
import com.hifn.pixelcake.core.edit.RetouchState
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 用户自定义预设（v0.6.6「用户预设库」，README 规划中 → 已实现）。
 *
 * **解锁链**：v0.6.4 T1（EditSnapshotJson / @Serializable EditParams）打通了参数栈 ⇄ JSON，
 * 本类是它的第一批持久化用户数据 —— `docs/PHASE_DESIGN_HISTORY.md` §12.4 里
 * 「另存预设 | 需要预设持久化层」的前置就此满足。
 *
 * ## 保存语义（与既有哲学对齐）
 *
 * - 保存 **整图参数 + 人像精修**（`MainActivity` 的预设注释：「预设只描述整图参数 + 人像精修」），
 *   **不含对象图层**（层是按这张图搭的结构，换图无意义）；
 * - **不含瑕疵描迹**：[com.hifn.pixelcake.core.edit.InpaintStroke] 是「这张图的这个点」，
 *   存进预设会在别的图上指到错误的位置 —— [capture] 统一置空，与「画笔轨迹属工具态不入快照」
 *   同一条哲学；
 * - 磨皮半径等归一化参数（radiusNorm）跨分辨率成立 ✓ 可保存。
 */
@Serializable
data class CustomPreset(
    /** `custom:` 前缀 + 8 位 uuid —— 与内置预设 id（"portra" 等）永不冲突。 */
    val id: String,
    val name: String,
    val params: EditParams = EditParams(),
    val retouch: RetouchState = RetouchState(),
    val createdAt: Long = 0L
) {
    /** 转成 UI/引擎通用的 [Preset]（相机批量、系留、编辑器三处同型消费）。 */
    fun asPreset(): Preset =
        Preset(id = id, name = name, category = PresetCategory.Custom, params = params, retouch = retouch)

    companion object {
        fun capture(name: String, params: EditParams, retouch: RetouchState, now: Long): CustomPreset =
            CustomPreset(
                id = "custom:" + UUID.randomUUID().toString().substring(0, 8),
                name = name,
                params = params,
                retouch = retouch.copy(inpaint = emptyList()),
                createdAt = now
            )
    }
}

/** 持久化格式（带版本号；新增字段须带默认值 = 向前兼容本身）。 */
@Serializable
data class CustomPresetFile(
    val version: Int = CustomPresets.VERSION,
    val presets: List<CustomPreset> = emptyList()
)

/**
 * 自定义预设的持久化（T1 之后的第一批用户数据落盘）。
 *
 * [decode]/[encode] 是纯文本函数（JVM 可测）；[Store] 是文件包装 —— 读失败时把坏文件
 * 改名为 `.corrupt` 留证再返回空，**绝不**让下一次 save 把用户的预设静默覆盖掉
 * （与 `EditSnapshotJson.decode` 的「坏文件显式处理」同一原则）。
 */
object CustomPresets {

    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun decode(text: String): List<CustomPreset> = try {
        json.decodeFromString<CustomPresetFile>(text).presets
    } catch (t: Throwable) {
        // 非 suspend、格式级失败：按「无自定义预设」继续（调用方保留坏文件留证）
        emptyList()
    }

    fun encode(list: List<CustomPreset>): String =
        json.encodeToString(CustomPresetFile(version = VERSION, presets = list))

    /** 文件存储。所有 IO 由调用方决定调度（UI 层一律 Dispatchers.IO）。 */
    class Store(private val file: File) {

        fun load(): List<CustomPreset> {
            if (!file.exists()) return emptyList()
            val text = try {
                file.readText()
            } catch (t: Throwable) {
                return emptyList()
            }
            val list = decode(text)
            if (list.isEmpty() && text.isNotBlank()) {
                // 解析失败但文件存在：留证（改名 .corrupt），避免下一次 save 覆盖掉用户数据
                val backup = File(file.parentFile, file.name + ".corrupt")
                if (!backup.exists()) {
                    runCatching {
                        file.copyTo(backup, overwrite = false)
                    }
                }
            }
            return list
        }

        /** 原子写（tmp + rename）；rename 失败（Windows 目标被占用等）退回直接覆盖。 */
        fun save(list: List<CustomPreset>) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encode(list))
            if (!tmp.renameTo(file)) {
                file.writeText(encode(list))
                tmp.delete()
            }
        }
    }
}
