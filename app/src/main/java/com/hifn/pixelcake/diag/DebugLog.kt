package com.hifn.pixelcake.diag

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内置结构化调试日志模块（DEV_PLAN §7）。
 *
 * 这是「无本地构建环境」下唯一的联调回路：真机跑起来后，所有关键路径都打点，
 * 用户通过系统分享把日志文件发回来即可定位问题。
 *
 * 设计要点：
 * - 落盘到 `files/debug/log_<session>.txt`，单文件约 2MB 滚动；
 * - 格式 `yyyy-MM-dd HH:mm:ss.SSS [LEVEL] TAG: msg {kv}`；
 * - 启动即在 [LIFECYCLE] 打设备能力 + 分辨率选档快照，便于排查 OOM；
 * - 写日志永不抛异常（避免日志拖垮主流程）。
 */
object DebugLog {

    const val TAG_LIFECYCLE = "LIFECYCLE"
    const val TAG_IMPORT = "IMPORT"
    const val TAG_DECODE = "DECODE"
    const val TAG_EDIT = "EDIT"
    const val TAG_ML = "ML"
    const val TAG_CAMERA = "CAMERA"
    const val TAG_API = "API"
    const val TAG_ERROR = "ERROR"

    private const val DIR = "debug"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val sessionFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private var logFile: File? = null
    private var writer: BufferedWriter? = null
    private val ready = AtomicBoolean(false)

    fun init(context: Context) {
        if (ready.get()) return
        synchronized(this) {
            if (ready.get()) return
            val appCtx = context.applicationContext
            val dir = File(appCtx.filesDir, DIR)
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, "log_${sessionFmt.format(Date())}.txt")
            writer = BufferedWriter(FileWriter(logFile, true))
            ready.set(true)
            i(TAG_LIFECYCLE, "DebugLog initialized", mapOf("file" to (logFile?.absolutePath ?: "null")))
        }
    }

    @Synchronized
    fun d(tag: String, msg: String, kv: Map<String, Any>? = null) = write("DEBUG", tag, msg, kv)

    @Synchronized
    fun i(tag: String, msg: String, kv: Map<String, Any>? = null) = write("INFO", tag, msg, kv)

    @Synchronized
    fun w(tag: String, msg: String, kv: Map<String, Any>? = null) = write("WARN", tag, msg, kv)

    @Synchronized
    fun e(tag: String, msg: String, kv: Map<String, Any>? = null) = write("ERROR", tag, msg, kv)

    private fun write(level: String, tag: String, msg: String, kv: Map<String, Any>?) {
        if (!ready.get()) return
        try {
            val ts = dateFmt.format(Date())
            val kvStr = kv?.entries?.joinToString(", ") { "${it.key}=${it.value}" }
                ?.let { " {$it}" } ?: ""
            writer?.append("$ts [$level] $tag: $msg$kvStr\n")
            writer?.flush()
            maybeRotate()
        } catch (_: Exception) {
            // 日志失败绝不影响主流程
        }
    }

    private fun maybeRotate() {
        val f = logFile ?: return
        if (f.length() <= MAX_BYTES) return
        try {
            writer?.close()
            val backup = File(f.parentFile, "${f.nameWithoutExtension}.old.txt")
            if (backup.exists()) backup.delete()
            f.renameTo(backup)
            writer = BufferedWriter(FileWriter(f, false))
        } catch (_: Exception) {
        }
    }

    /** 启动快照：设备能力 + 分辨率选档（来自 [com.hifn.pixelcake.ui.home.resolutionProfile]）。 */
    fun dumpBoot(snapshot: String) {
        i(TAG_LIFECYCLE, "boot snapshot", mapOf("summary" to snapshot))
    }

    /**
     * 导出调试日志：通过系统分享把当前日志文件发出去。
     * 调用方传入的 [context] 必须是 Activity（Compose 的 LocalContext 即为 Activity）。
     * 返回是否成功唤起分享。
     */
    fun export(context: Context): Boolean {
        val f = logFile ?: return false
        if (!f.exists()) return false
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context.applicationContext,
                context.applicationContext.packageName + AUTHORITY_SUFFIX,
                f
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "导出调试日志"))
            true
        } catch (e: Exception) {
            this.e(TAG_ERROR, "export log failed", mapOf("err" to (e.message ?: e.javaClass.simpleName)))
            false
        }
    }
}
