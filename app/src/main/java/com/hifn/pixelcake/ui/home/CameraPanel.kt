package com.hifn.pixelcake.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.camera.CameraConnection
import com.hifn.pixelcake.camera.CameraProbe
import com.hifn.pixelcake.camera.UsbCameraScanner
import com.hifn.pixelcake.camera.UsbDeviceSummary
import com.hifn.pixelcake.diag.DebugLog
import kotlinx.coroutines.launch

/**
 * P2 相机直连面板（PoC-1 检测 → PoC-2/3 连接握手）。
 *
 * - 检测：只读 `UsbManager.deviceList`，**不申请权限、无弹窗**。
 * - 连接：申请 USB 授权 → 打开设备 → PTP `OpenSession` → 读机型 → 枚举存储与对象（**全程只读**）。
 *
 * 首次点「连接并握手」会弹系统 USB 授权框，点「允许」后结果一次性列出
 * （步骤轨迹 + 机型 + 卡内对象数 + 末尾样本文件名）。真机上若失败，导出调试日志回传即可——
 * 每一步都写进 `CAMERA` 日志。
 */
@Composable
fun CameraPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var devices by remember { mutableStateOf(emptyList<UsbDeviceSummary>()) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var scanned by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(emptyList<String>()) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "相机直连（P2 · PoC-2/3）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "把 A7C2 用 USB 连上手机，机身「USB 连接」设为 MTP（或 PC Remote），先检测、再握手。" +
                    "握手为只读操作，不会改动相机里的任何文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            OutlinedButton(
                onClick = {
                    val list = UsbCameraScanner.scan(context)
                    devices = list
                    scanned = true
                    if (list.none { it.deviceName == selectedName }) {
                        selectedName = list.firstOrNull { CameraProbe.isSonyCamera(it) }?.deviceName
                    }
                    DebugLog.i(DebugLog.TAG_CAMERA, "usb scan", mapOf("count" to list.size))
                    for (d in list) {
                        DebugLog.i(
                            DebugLog.TAG_CAMERA, "usb device",
                            mapOf(
                                "vendorId" to CameraProbe.hex(d.vendorId),
                                "productId" to CameraProbe.hex(d.productId),
                                "ifs" to d.interfaceClasses.joinToString(","),
                                "mode" to CameraProbe.modeHint(d)
                            )
                        )
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text("1. 检测 USB 设备")
            }

            if (scanned) {
                if (devices.isEmpty()) {
                    Text(
                        "未检测到 USB 设备（请确认相机已连接；机身「USB 连接」若设为「仅充电」则不会出现在总线上）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                } else {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        for (d in devices) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) { selectedName = d.deviceName },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = d.deviceName == selectedName,
                                    onClick = { selectedName = d.deviceName },
                                    enabled = !busy
                                )
                                Text(
                                    (if (CameraProbe.isSonyCamera(d)) "★ " else "") + CameraProbe.describe(d),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val name = selectedName ?: return@Button
                    val device = UsbCameraScanner.find(context, name)
                    if (device == null) {
                        lines = listOf("设备已从 USB 总线断开，请重新检测。")
                        return@Button
                    }
                    busy = true
                    lines = emptyList()
                    DebugLog.i(DebugLog.TAG_CAMERA, "ptp connect start", mapOf("device" to name))
                    scope.launch {
                        val report = CameraConnection.connect(context, device)
                        lines = report.summaryLines()
                        DebugLog.i(
                            DebugLog.TAG_CAMERA, "ptp connect done",
                            mapOf(
                                "ok" to report.ok,
                                "failure" to (report.failure ?: "-"),
                                "objects" to report.handleCounts.values.sum(),
                                "ms" to report.elapsedMs
                            )
                        )
                        busy = false
                    }
                },
                enabled = !busy && selectedName != null,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text("2. 连接并握手（PoC-2/3 · 只读）")
            }

            if (busy) {
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "握手中…（首次会弹出 USB 授权框，请点「允许」）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!busy && lines.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Column {
                    for (line in lines) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
