package com.hifn.pixelcake.session

import android.content.Context
import com.hifn.pixelcake.core.edit.SessionJson
import com.hifn.pixelcake.core.edit.SessionRecord

/**
 * 编辑会话记录的落盘（v0.7.2 进程死亡恢复）。
 *
 * 刻意**独立于 [com.hifn.pixelcake.ui.settings.AppSettings]**：会话记录是「一次性凭据」
 * —— 恢复成功或失败都要被清掉，与用户偏好（永久保留）的生命周期完全不同，混进同一个
 * prefs 文件迟早有人把「清会话」写成「清设置」。
 *
 * 写入用 `apply()`（异步落盘）：编辑态防抖回调在主线程，这里不阻塞；单键覆盖无放大写。
 */
object SessionStore {

    private const val PREFS = "session_store"
    private const val KEY = "record"

    /** 读取。损坏 / 非本格式 / 半截写入一律返回 null（调用方按「无会话」处理）。 */
    fun load(context: Context): SessionRecord? = runCatching {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        SessionJson.decode(text)
    }.getOrNull()

    fun save(context: Context, record: SessionRecord) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, SessionJson.encode(record)).apply()
        }
    }

    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply()
        }
    }
}
