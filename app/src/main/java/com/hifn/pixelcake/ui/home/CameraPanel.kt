package com.hifn.pixelcake.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hifn.pixelcake.camera.CameraProbe
import com.hifn.pixelcake.camera.UsbCameraScanner
import com.hifn.pixelcake.diag.DebugLog

/**
 * P2 PoC-1：相机直连的 USB 检测面板。
 *
 * 只做「枚举 + 识别 + 日志」，**不请求权限、不打开设备**——零副作用的 PoC 第一步。
 * 真机验证方法：用 USB 连接 A7C2 并把机身 USB 连接设为 MTP / PC Remote，
 * 点「检测 USB 设备」应列出 `Sony ... [0x054C:xxxx] · PTP/MTP`。
 */
@Composable
fun CameraPanel() {
    val context = LocalContext.current
    var devices by remember { mutableStateOf(emptyList<String>()) }
    var scanned by remember { mutableStateOf(false) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "相机直连（P2 · PoC）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "把 A7C2 用 USB 连上，并把机身「USB 连接」设为 MTP / PC Remote，然后点下方按钮检测。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Button(
                onClick = {
                    val list = UsbCameraScanner.scan(context)
                    devices = list.map { d ->
                        (if (CameraProbe.isSonyCamera(d)) "★ " else "· ") + CameraProbe.describe(d)
                    }
                    scanned = true
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
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text("检测 USB 设备")
            }
            if (scanned) {
                if (devices.isEmpty()) {
                    Text(
                        "未检测到 USB 设备（请确认相机已连接并处于 MTP / PC Remote 模式）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                } else {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        for (d in devices) {
                            Text(d, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
