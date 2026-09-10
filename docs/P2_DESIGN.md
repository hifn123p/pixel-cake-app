# P2 设计稿（A7C2 直连 · 草稿 v0.1）

> 状态：**设计稿 + PoC-1 已落地**。本文为 `DEV_PLAN.md` §0/§4「P2」的细化设计。
> P1（含 P1b 人像精修）已完成；P2 目标：Sony **A7C2（ILCE-7CM2）** 通过 USB 直连手机 →
> **拉图 → 套预设 → 看/导出**，并评估「边拍边看」可行性。
>
> 依据（已读真实代码）：
> - 导入后处理链已成熟且**与来源解耦**：`Decoder`（ARW 走 LibRaw）/ `EditEngine` /
>   `RetouchLayer` / `Presets.ALL` 全部可原样复用于「相机传来的 ARW」，无需改动。
> - 约束：minSdk 36、仅 arm64-v8a、CI-only 构建（本地零环境）、真机一加15 自测。
>
> **核心约束**：P2 是**强真机依赖**阶段——USB 枚举/PTP 会话/传输都必须插上 A7C2 才能验证，
> 纯编译期（CI）无法覆盖。因此按 PoC 分步推进，每步都可真机验收后再进下一步。

---

## 1. 范围与目标

| 项 | P2 做 | P2 不做 |
|---|---|---|
| 连接 | USB（宿主模式）直连 A7C2 | 云端/NAS（P3） |
| 拉图 | 枚举相机照片、下载 **ARW/JPEG** 到 App 缓存 | 删除/改写相机内文件 |
| 处理 | 拉到的 ARW 走**现有 P1 管线**套预设 | 新的解码/调色算法 |
| 遥控 | **评估**「边拍边看」（可能受限于官方 SDK） | 承诺 liveview 一定可用 |
| 批处理 | 单张优先，批量队列次之 | 后台服务/常驻 |

**验收目标（P2 完成口径）**：USB 连上 A7C2 → App 列出相机照片 → 选一张 ARW 拉到本地 →
自动套用一个预设 → 在编辑器看到「相机直出」成片 → 可导出到相册。

---

## 2. A7C2 的 USB 能力（事实与待验证）

Sony ILCE-7CM2 的机身「USB 连接」菜单一般提供：

| 模式 | 协议 | 对 App 的意义 |
|---|---|---|
| **Mass Storage** | USB MSC | 相机当 U 盘，但 Android 需挂载其卷（常见做法是走 MTP 而非 MSC） |
| **MTP** | PTP + MTP 扩展 | **可枚举/拉图**（文件名/大小/格式 + 下载），是拉图主路径 |
| **PC Remote** | PTP（Sony 扩展） | 遥控/连拍/取景；需官方 SDK 才能安全驱动 |

> ⚠️ 待真机确认（PoC-1 输出）：A7C2 各模式下的 **VID/PID 与接口类**。
> PoC-1 的 USB 检测面板会打印 `0x054C:xxxx` 与接口类（6=StillImage / 8=MSC / 0xFF=Vendor），
> 以此判定当前模式。**这是后续所有实现的前提**。

---

## 3. 技术方案对比（拉图）

| 方案 | 说明 | 优点 | 代价/风险 | 结论 |
|---|---|---|---|---|
| **A. 纯 Kotlin 最小 PTP/MTP 客户端** | 直接用 `UsbDeviceConnection` 的 bulk 端点跑 PTP 容器（Open/GetDeviceInfo/GetStorageIDs/GetObjectHandles/GetObjectInfo/GetObject） | 无第三方、无 NDK、无许可；与现有纯 Kotlin 风格一致；可控 | 协议实现量中等（~600–900 行）；**必须真机调试** | **推荐（PoC 主路径）** |
| B. libmtp（NDK 静态链接） | 复用成熟 MTP 库 | 功能全（含事件） | 引入 native 依赖、构建复杂、许可核对；CI 构建变重 | 备选（若 A 的协议坑太多） |
| C. Sony Camera Remote SDK | 官方 CRSDK（prebuilt .so + EULA） | 官方支持遥控/liveview | 需申请与许可、闭源 prebuilt、与 arm64/NDK 版本匹配；分发受限 | 仅当「边拍边看」必须时评估 |
| D. Wi-Fi ScalarWebAPI | 相机 Wi-Fi 上的 HTTP API | 无 USB 依赖 | A7C2 支持面有限、需切换相机 Wi-Fi、Android 本地网络权限（API 37 起为运行时权限） | 后置/备选 |

**推荐**：拉图走 **A（纯 Kotlin 最小 MTP）**；「边拍边看」先不做承诺，待拉图链路跑通后再评估 C/D。

---

## 4. 与 P1 的复用（关键）

拉图得到的 ARW 直接复用 P1 全链路，**零算法改动**：

```
相机 (USB) ──PTP/MTP GetObject──▶ App cache 里的 x.ARW
        │
        ├─ 打开/预览：ArwPreviewDecoder（内嵌 JPEG，秒开）        ← 复用
        └─ 修图/导出：Decoder/ArwFullDecoder → EditEngine
                        → RetouchLayer → Presets.ALL            ← 复用
```

即：P2 只新增「**取文件**」这一步；只要把文件落到 `cacheDir` 得到路径，就等价于「用户从相册导入了一个 ARW」。

---

## 5. 分阶段 PoC 计划

| 阶段 | 内容 | 依赖 | 状态 |
|---|---|---|---|
| **PoC-1** | USB 主机检测：枚举设备、识别 Sony VID/PID 与接口类、打印模式；UI 面板 + 日志 | 仅 USB 枚举（免权限） | ✅ **本批已落地** |
| PoC-2 | 权限 + 打开设备 + PTP 会话握手（`OpenSession` / `GetDeviceInfo`） | 真机（授权弹窗） | ⬜ |
| PoC-3 | 枚举存储与对象：`GetStorageIDs` → `GetObjectHandles` → `GetObjectInfo`（列出 ARW 数量/文件名） | PoC-2 | ⬜ |
| PoC-4 | `GetObject` 下载单张 ARW 到 cache → 复用 P1 管线套预设 → 「相机直出」第一张 | PoC-3 | ⬜ |
| PoC-5 | 批量拉图 + 预设批处理队列（可取消、进度） | PoC-4 | ⬜ |
| PoC-6（可选） | 边拍边看 / 遥控（评估 CRSDK 或 ScalarWebAPI） | 决策 | ⬜ |

---

## 6. PoC-1 已实现（本批）

| 文件 | 作用 |
|---|---|
| `camera/CameraProbe.kt` | 纯逻辑：Sony VID 判定、接口类→USB 模式提示、`describe()` |
| `camera/UsbCameraScanner.kt` | 只读 `UsbManager.deviceList` 枚举设备（**不需权限**）→ `List<UsbDeviceSummary>` |
| `ui/home/CameraPanel.kt` | 首页「相机直连（P2 · PoC）」卡片：一键检测并列出设备 + 写 `CAMERA` 日志 |
| `camera/CameraProbeTest.kt` | 纯 JVM 单测：VID 判定、模式推断、hex 格式化、描述拼接 |

**真机验证步骤**：USB 连接 A7C2 → 机身 USB 连接设 MTP（或 PC Remote）→ 首页点「检测 USB 设备」
→ 应出现 `★ Sony ... [0x054C:xxxx] · PTP/MTP ...`，并在调试日志 `CAMERA` tag 看到设备明细。

---

## 7. 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| A7C2 某些模式不暴露可用的 MTP 接口 | 中 | PoC-1 先探明各模式接口类；必要时引导用户切到 MTP 模式 |
| 自研 PTP 协议细节多、真机调试耗时 | 中 | 最小闭环（列图+拉单张）；先用 libmtp 作后备 |
| 传输大文件（ARW 35–57MB）耗时/OOM | 中 | 流式写入缓存文件，不整段进堆；进度 + 可取消 |
| 相机端「PC Remote」占用导致 MTP 不可用 | 中 | UI 明确提示模式切换 |
| 边拍边看依赖官方 SDK | 高 | 不承诺；先交付「拍完拉图」，liveview 单列评估 |
| targetSdk 37 后 `ACCESS_LOCAL_NETWORK` 运行时权限（Wi-Fi 方案） | 中 | 优先 USB；Wi-Fi 方案后置 |

---

## 8. 待确认决策

- **D1 拉图方案**：A 纯 Kotlin 最小 MTP（推荐） / B libmtp / C Sony CRSDK。
- **D2 相机模式**：以 **MTP** 为默认引导（推荐）/ PC Remote。
- **D3 批处理范围**：先单张（推荐）/ 直接做批量队列。
- **D4 边拍边看**：本期只评估不实现（推荐）/ 现在就引入 CRSDK。
- **D5 UI 归属**：首页独立「相机」入口（推荐）/ 与「导入」合并。

---

*本稿为 P2 设计 + PoC-1 落地说明；PoC-2 起（权限 + PTP 会话）需真机插上 A7C2 验证 PoC-1 结果后再推进。*
