package com.hifn.pixelcake.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.hifn.pixelcake.camera.CameraBatch
import com.hifn.pixelcake.camera.CameraConnection
import com.hifn.pixelcake.camera.CameraPhotoList
import com.hifn.pixelcake.camera.CameraProbe
import com.hifn.pixelcake.camera.CameraSession
import com.hifn.pixelcake.camera.CameraSessionResult
import com.hifn.pixelcake.camera.UsbCameraScanner
import com.hifn.pixelcake.camera.UsbDeviceSummary
import com.hifn.pixelcake.core.edit.preset.Presets
import com.hifn.pixelcake.diag.DebugLog
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P2 相机直连面板：**检测 → 握手 → 列图 → 拉图**（单张导入编辑器 / 批量套预设导出）。
 *
 * 设计要点：
 * - 会话**常驻**：一次握手后 `CameraSession` 一直持有，浏览列表、拉单张、跑批量复用同一会话
 *   （每次操作重开会话都要「授权 + 打开设备 + OpenSession」重来，既慢又易在相机端留半开状态）。
 *   `DisposableEffect` 兜底释放 USB；另有显式「断开连接」。
 * - **只读**直到用户点拉取：列图只发 `GetObjectHandles/GetObjectInfo`，不写、不删相机文件。
 * - 批量**导完即删**临时文件（峰值磁盘 ≈ 一张）；取消只在**文件边界**生效
 *   （半途中断 `GetObject` 会让数据流与响应错位、会话作废）。
 *
 * @param longEdge 导出长边上限，与编辑器导出口径一致（预览/导出所见即所得）。
 * @param onOpenLocalFile 单张拉取成功后回调本地缓存文件，由上层打开编辑器。
 */
@Composable
fun CameraPanel(
    longEdge: Int,
    onOpenLocalFile: (File) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var devices by remember { mutableStateOf(emptyList<UsbDeviceSummary>()) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var scanned by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(emptyList<String>()) }

    var session by remember { mutableStateOf<CameraSession?>(null) }
    var photoList by remember { mutableStateOf<CameraPhotoList?>(null) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var presetId by remember { mutableStateOf("none") }
    var progress by remember { mutableStateOf<CameraBatch.Progress?>(null) }
    var batchLines by remember { mutableStateOf(emptyList<String>()) }
    val batchCancel = remember { AtomicBoolean(false) }

    // 会话常驻 → 离开面板必须兜底释放 USB（HomeScreen 的 LazyColumn 滑出也会触发）。
    DisposableEffect(Unit) {
        onDispose {
            val s = session
            s?.close()
            DebugLog.i(DebugLog.TAG_CAMERA, "camera panel disposed", mapOf("connected" to (s != null)))
        }
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "相机直连（P2 · 拉图 + 批量套预设）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "把 A7C2 用 USB 连上手机，机身「USB 连接」设为 MTP。握手后可浏览卡内照片：" +
                    "单张拉进编辑器精修，或选中多张 + 一个预设一键批量导出到相册。列图全程只读。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            // ---------------- 1. 检测 ----------------
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

            // ---------------- 2. 握手（建立常驻会话） ----------------
            Button(
                onClick = {
                    val name = selectedName ?: return@Button
                    val device = UsbCameraScanner.find(context, name)
                    if (device == null) {
                        lines = listOf("设备已从 USB 总线断开，请重新检测。")
                        return@Button
                    }
                    busy = true
                    phase = "连接并握手…（首次会弹出 USB 授权框，请点「允许」）"
                    lines = emptyList()
                    photoList = null
                    selected = emptySet()
                    batchLines = emptyList()
                    DebugLog.i(DebugLog.TAG_CAMERA, "ptp connect start", mapOf("device" to name))
                    scope.launch {
                        val steps = ArrayList<String>()
                        when (val opened = CameraSession.open(context, device) { steps.add(it) }) {
                            is CameraSessionResult.Failed -> {
                                lines = steps + "✗ 失败：${opened.reason}"
                                DebugLog.w(
                                    DebugLog.TAG_CAMERA, "ptp connect failed",
                                    mapOf("reason" to opened.reason)
                                )
                            }
                            is CameraSessionResult.Ok -> {
                                val s = opened.session
                                session = s
                                phase = "读取照片列表 + 协议体检…"
                                // 连上即做一次体检（PoC-2/3 报告：各存储对象数 + 末尾样本）
                                // 并读一次列表，省去用户再点一下。
                                val report = CameraConnection.inspect(s, steps)
                                lines = report.summaryLines()
                                val list = s.listPhotos()
                                photoList = list
                                selected = list.photos.map { it.handle }.toSet()
                                DebugLog.i(
                                    DebugLog.TAG_CAMERA, "ptp connect ok",
                                    mapOf(
                                        "device" to s.deviceLabel,
                                        "storages" to s.storageIds.size,
                                        "objects" to report.handleCounts.values.sum(),
                                        "photos" to list.photos.size,
                                        "inspectMs" to report.elapsedMs
                                    )
                                )
                            }
                        }
                        busy = false
                        phase = ""
                    }
                },
                enabled = !busy && selectedName != null && session == null,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text("2. 连接并握手（建立会话）")
            }

            // ---------------- 已连接：列图 / 拉图 / 批量 ----------------
            val s = session
            if (s != null) {
                HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

                Text(
                    "已连接：${s.deviceLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (s.storages.isNotEmpty()) {
                    Text(
                        "存储：${s.storagesSummary()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            phase = "读取照片列表…"
                            scope.launch {
                                val list = s.listPhotos()
                                photoList = list
                                selected = list.photos.map { it.handle }.toSet()
                                busy = false
                                phase = ""
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("刷新照片列表")
                    }
                    OutlinedButton(
                        onClick = {
                            busy = true
                            phase = "正在断开…"
                            scope.launch {
                                runCatching { s.closeGracefully() }
                                s.close()
                                session = null
                                photoList = null
                                selected = emptySet()
                                progress = null
                                busy = false
                                phase = ""
                                lines = lines + "已断开连接"
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("断开连接")
                    }
                }

                val list = photoList
                if (list != null) {
                    Text(
                        list.summaryLines().joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    if (list.photos.isEmpty()) {
                        Text(
                            "未识别到可拉取的照片（卡内可能没有照片，或只检查了最新的一段对象）。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "选择要处理的照片（已选 ${selected.size} / ${list.photos.size}）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = {
                                    selected = if (selected.size == list.photos.size) {
                                        emptySet()
                                    } else {
                                        list.photos.map { it.handle }.toSet()
                                    }
                                },
                                enabled = !busy
                            ) {
                                Text(if (selected.size == list.photos.size) "全不选" else "全选")
                            }
                        }

                        // 照片行：勾选 + 单张导入
                        for (photo in list.photos) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = photo.handle in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + photo.handle
                                        else selected - photo.handle
                                    },
                                    enabled = !busy
                                )
                                Text(
                                    photo.label(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        busy = true
                                        phase = "正在拉取 ${photo.filename}…"
                                        scope.launch {
                                            val target = CameraBatch.newSingleTarget(context, photo)
                                            val outcome = s.download(photo.handle, target) { done, total ->
                                                progress = CameraBatch.Progress(
                                                    1, 1, photo.filename, "拉取中", done, total
                                                )
                                            }
                                            progress = null
                                            busy = false
                                            phase = ""
                                            if (outcome.ok) {
                                                lines = lines +
                                                    "已拉取 ${photo.filename}（${outcome.bytes / 1024 / 1024}MB）→ 打开编辑器"
                                                DebugLog.i(
                                                    DebugLog.TAG_CAMERA, "single pull ok",
                                                    mapOf("file" to target.name, "bytes" to outcome.bytes)
                                                )
                                                onOpenLocalFile(target)
                                            } else {
                                                lines = lines + "拉取 ${photo.filename} 失败：${outcome.message}"
                                                DebugLog.w(
                                                    DebugLog.TAG_CAMERA, "single pull failed",
                                                    mapOf("handle" to photo.handle, "msg" to outcome.message)
                                                )
                                            }
                                        }
                                    },
                                    enabled = !busy
                                ) {
                                    Text("导入")
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

                        Text(
                            "批量：选中照片 + 一个预设 → 一键套用并导出到相册",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (p in Presets.ALL) {
                                FilterChip(
                                    selected = p.id == presetId,
                                    onClick = { presetId = p.id },
                                    enabled = !busy,
                                    label = { Text(p.name) }
                                )
                            }
                        }

                        val chosen = list.photos.filter { it.handle in selected }
                        val rawCount = chosen.count { it.isRaw }
                        val otherCount = chosen.size - rawCount
                        val preset = Presets.byId(presetId) ?: Presets.ALL.first()

                        Button(
                            onClick = {
                                busy = true
                                batchCancel.set(false)
                                batchLines = emptyList()
                                progress = null
                                phase = "批量处理中…"
                                scope.launch {
                                    val summary = CameraBatch.run(
                                        context = context,
                                        session = s,
                                        photos = chosen,
                                        preset = preset,
                                        longEdge = longEdge,
                                        onProgress = { progress = it },
                                        isCancelled = { batchCancel.get() }
                                    )
                                    batchLines = summary.summaryLines()
                                    progress = null
                                    busy = false
                                    phase = ""
                                    DebugLog.i(
                                        DebugLog.TAG_CAMERA, "batch done",
                                        mapOf(
                                            "ok" to summary.okCount, "total" to summary.items.size,
                                            "cancelled" to summary.cancelled, "ms" to summary.elapsedMs
                                        )
                                    )
                                }
                            },
                            enabled = !busy && chosen.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Text(
                                "3. 批量导入并套「${preset.name}」（${chosen.size} 张 · " +
                                    "RAW $rawCount / 其他 $otherCount）"
                            )
                        }
                    }
                }
            }

            // ---------------- 忙碌 / 进度 ----------------
            if (busy) {
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        phase.ifEmpty { "处理中…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val p = progress
            if (p != null) {
                Spacer(Modifier.height(8.dp))
                if (p.bytesTotal > 0L) {
                    LinearProgressIndicator(
                        progress = { p.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    p.text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (busy && progress != null) {
                TextButton(
                    onClick = { batchCancel.set(true) },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("取消（当前文件处理完即停）")
                }
            }

            if (!busy && batchLines.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Column {
                    for (line in batchLines) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
