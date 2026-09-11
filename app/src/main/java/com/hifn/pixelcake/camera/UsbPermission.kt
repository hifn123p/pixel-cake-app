package com.hifn.pixelcake.camera

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hifn.pixelcake.diag.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * USB 设备访问授权。
 *
 * `UsbManager.requestPermission` 只能通过 PendingIntent 收回结果，而授权广播的投递行为
 * 在不同 ROM 上并不完全一致，因此这里做成**广播 + 轮询双保险**：
 *
 * - 广播是主路径：立刻拿到结果，无需等待；
 * - 轮询是兜底：300ms 一次查 `hasPermission()`，覆盖「广播没送到」的情况；
 * - 谁先到算谁，25s 超时。
 *
 * 少了轮询兜底，一次「广播没送到」就会让整轮真机验证白跑——真机验证机会很贵。
 */
object UsbPermission {

    /** 自定义授权 action（作为 PendingIntent 的基准 action）。 */
    const val ACTION_USB_PERMISSION = "com.hifn.pixelcake.action.USB_PERMISSION"

    /**
     * 系统 UsbService 发广播时可能用的 action。
     * 该字符串未在 `UsbManager` 上公开为常量，故以字面量声明，与自定义 action 一起注册。
     */
    private const val SYSTEM_PERMISSION_ACTION = "android.hardware.usb.action.USB_PERMISSION"

    private const val TIMEOUT_MS = 25_000L
    private const val POLL_INTERVAL_MS = 300L

    /** 已有授权则立刻返回 true；否则请求并等待（见类注释的双保险策略）。 */
    suspend fun request(context: Context, manager: UsbManager, device: UsbDevice): Boolean {
        if (manager.hasPermission(device)) return true

        val granted = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action != ACTION_USB_PERMISSION && action != SYSTEM_PERMISSION_ACTION) return
                val fromIntent: UsbDevice? =
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (fromIntent == null || fromIntent.deviceName != device.deviceName) return
                granted.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(SYSTEM_PERMISSION_ACTION)
        }
        // NOT_EXPORTED：PendingIntent 由本应用创建，发送方 uid 即本应用，收得到且不对外暴露
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        return try {
            manager.requestPermission(device, permissionIntent(context))
            withTimeoutOrNull(TIMEOUT_MS) {
                coroutineScope {
                    val poll = launch {
                        while (isActive) {
                            if (manager.hasPermission(device)) {
                                granted.complete(true)
                                return@launch
                            }
                            delay(POLL_INTERVAL_MS)
                        }
                    }
                    val result = granted.await()
                    poll.cancel()
                    result
                }
            } ?: false
        } catch (e: Exception) {
            DebugLog.e(
                DebugLog.TAG_CAMERA, "usb permission request failed",
                mapOf("err" to (e.message ?: e.javaClass.simpleName))
            )
            false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * 授权用的 PendingIntent。
     *
     * 必须 `FLAG_MUTABLE`：系统要往这个 Intent 里填 `EXTRA_DEVICE` 与 `EXTRA_PERMISSION_GRANTED`，
     * 不可变 PendingIntent 会让接收方拿不到「是否授权成功」。
     */
    @SuppressLint("MutableImplicitPendingIntent")
    private fun permissionIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
    }
}
