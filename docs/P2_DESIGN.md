# P2 设计稿（A7C2 直连 · v0.3）

> 状态：**设计稿 + PoC-1/2/3/4/5 已落地**（USB 枚举 → 授权 → PTP 会话 → 存储/对象枚举 →
> 流式拉图 → 批量套预设导出）。本文为 `DEV_PLAN.md` §0/§4「P2」的细化设计。
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
> 能进 CI 的部分（协议容器编解码、数据集解析、报告格式化）**全部下沉为纯 Kotlin + JVM 单测**，
> 使真机上只剩「USB 端点与相机实际行为」这一小块不确定性。

---

## 1. 范围与目标

| 项 | P2 做 | P2 不做 |
|---|---|---|
| 连接 | USB（宿主模式）直连 A7C2 | 云端/NAS（P3） |
| 拉图 | 枚举相机照片、下载 **ARW/JPEG** 到 App 缓存 | 删除/改写相机内文件 |
| 处理 | 拉到的 ARW 走**现有 P1 管线**套预设 | 新的解码/调色算法 |
| 遥控 | **评估**「边拍边看」（可能受限于官方 SDK） | 承诺 liveview 一定可用 |
| 批处理 | 单张 + 批量队列（进度 / 文件边界取消） | 后台服务/常驻 |

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

> ⚠️ 待真机确认：A7C2 各模式下的 **VID/PID 与接口类**。PoC-1 面板会打印
> `0x054C:xxxx` 与接口类（6=StillImage / 8=MSC / 0xFF=Vendor），并据此判定当前模式。

---

## 3. 技术方案对比（拉图）

| 方案 | 说明 | 优点 | 代价/风险 | 结论 |
|---|---|---|---|---|
| **A. 纯 Kotlin 最小 PTP/MTP 客户端** | 直接用 `UsbDeviceConnection` 的 bulk 端点跑 PTP 容器（Open/GetDeviceInfo/GetStorageIDs/GetObjectHandles/GetObjectInfo/GetObject） | 无第三方、无 NDK、无许可；与现有纯 Kotlin 风格一致；**协议层可 JVM 单测** | 协议实现量中等（~900 行）；USB 端点行为必须真机调试 | **推荐（已落地 PoC-1/2/3）** |
| B. libmtp（NDK 静态链接） | 复用成熟 MTP 库 | 功能全（含事件） | 引入 native 依赖、构建复杂、许可核对；CI 构建变重 | 备选（若 A 的协议坑太多） |
| C. Sony Camera Remote SDK | 官方 CRSDK（prebuilt .so + EULA） | 官方支持遥控/liveview | 需申请与许可、闭源 prebuilt、与 arm64/NDK 版本匹配；分发受限 | 仅当「边拍边看」必须时评估 |
| D. Wi-Fi ScalarWebAPI | 相机 Wi-Fi 上的 HTTP API | 无 USB 依赖 | A7C2 支持面有限、需切换相机 Wi-Fi、Android 本地网络权限（API 37 起为运行时权限） | 后置/备选 |

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
| **PoC-1** | USB 主机检测：枚举设备、识别 Sony VID/PID 与接口类、打印模式；UI 面板 + 日志 | 仅 USB 枚举（免权限） | ✅ |
| **PoC-2** | 授权 + 打开设备 + 声明 PTP 接口 + `OpenSession` + `GetDeviceInfo`（读机型/固件/序列号/支持操作） | 真机（授权弹窗） | ✅ |
| **PoC-3** | 枚举存储与对象：`GetStorageIDs` → `GetStorageInfo` → `GetObjectHandles` → `GetObjectInfo`（存储容量 + 对象数 + 末尾样本文件名） | PoC-2 | ✅ |
| **PoC-4** | `GetObject` 流式下载单张 ARW 到 cache → 复用 P1 管线套预设 → 「相机直出」第一张 | PoC-3 | ✅ |
| **PoC-5** | 批量拉图 + 预设批处理队列（可取消、进度） | PoC-4 | ✅ |
| PoC-6（可选） | 边拍边看 / 遥控（评估 CRSDK 或 ScalarWebAPI） | 决策 | ⬜ |

---

## 6. PTP 实现要点（PoC-2/3 已落地）

### 6.1 容器格式（为什么字节序/长度必须严格）

PTP over USB 一律 **little-endian**，容器 = 12 字节头 + 负载：

```
length(u32) | type(u16) | code(u16) | transactionId(u32) | payload[]
```

一次事务 **Command →（可选 Data）→ Response**。两个最容易踩的坑：

1. **数据阶段是否存在，必须由操作码决定**（`OpenSession`/`CloseSession` 没有 Data）。
   猜错会把 Response 当成 Data 读，然后卡在等待一个永远不来的负载上。
2. **设备可能不发 Data 只回 Response**（例如不支持该操作时直接 `OperationNotSupported`）。
   读到一个 `type=Response` 的头时，必须立刻当作响应处理，而不是继续读负载。

### 6.2 文件清单

| 文件 | 层次 | 作用 |
|---|---|---|
| `camera/PtpProtocol.kt` | 纯 Kotlin | 容器编解码、操作码/响应码/格式码常量、小端读写、可读名 |
| `camera/PtpData.kt` | 纯 Kotlin | `PtpReader`（越界不抛异常）+ DeviceInfo/StorageInfo/ObjectInfo/StorageIDs/ObjectHandles 解析 |
| `camera/PtpTransport.kt` | Android | 选 PTP 接口与 Bulk 端点、`claimInterface`、三阶段事务、`GetObject` 流式下载、读满/空包重试 |
| `camera/UsbPermission.kt` | Android | 授权：`FLAG_MUTABLE` PendingIntent + 广播/系统 action 双注册 + 300ms 轮询兜底，25s 超时 |
| `camera/CameraSession.kt` | Android | **长生命周期会话**：授权→打开→OpenSession→设备/存储信息；`listPhotos` / `download` / `closeGracefully` |
| `camera/CameraPhoto.kt` | 纯 Kotlin | `CameraPhoto` / `CameraPhotoList`（枚举结果含截断诊断）/ 照片过滤器（后缀优先，格式码兜底） |
| `camera/CameraBatch.kt` | Android | 批量流水线：逐张 `download` → 套预设渲染 → 导出 → **导完即删**；取消只在文件边界 |
| `camera/CameraConnection.kt` | Android | PoC-3 体检（`CameraSession` 薄封装，采样末尾对象 → 报告）；只读，用完即关 |
| `camera/CameraPtpReport.kt` | 纯 Kotlin | 报告数据类 + 摘要行（用户直接回传的那份文本） |
| `core/edit/RetouchScale.kt` | 纯 Kotlin | 归一化 retouch 参数 → 渲染态（编辑器与批处理**共用口径**，保证所见即所得） |
| `ui/home/CameraPanel.kt` | UI | 检测 → 选择设备 → 连接（建常驻会话）→ 列图 → 单张导入 / 批量套预设 → 断开 |

### 6.3 授权：为什么是「广播 + 轮询」双保险

`UsbManager.requestPermission` 只能靠 PendingIntent 收回结果，而：

- PendingIntent **必须 `FLAG_MUTABLE`**——系统要往里填 `EXTRA_DEVICE` / `EXTRA_PERMISSION_GRANTED`；
- 授权广播的 action 在不同 ROM 上不完全一致（自定义 action 与系统 action 都注册一遍）；
- 极端情况下广播可能收不到。

因此同时启动一个 300ms 间隔的 `hasPermission()` 轮询，**谁先到算谁**，25s 超时。
少了这条兜底，一次「广播没送到」就会让整轮真机验证白跑。

### 6.4 流式下载与批量（PoC-4/5 已落地）

**长生命周期会话**。一次握手后 `CameraSession` 被 UI 持有，列图 / 拉单张 / 跑批量复用同一会话，
直到点「断开连接」或面板被销毁（`DisposableEffect` 兜底 `close()`）。
每次操作重开会话意味着「授权 + 打开设备 + `claimInterface` + `OpenSession`」全部重来，
既慢又容易在相机端留下半开状态。所有 USB I/O 串行化在会话内部 `Mutex` 上
（`PtpTransport.transactionId` 是可变状态，并发发命令会错位；UI 即使误并发也只会排队）。

**流式下载，不整段进堆**。`PtpTransport.downloadObject(handle, sink, onProgress)`
按 256KB 分块写 `OutputStream`，RAW 35–57MB 全程不加载进堆。长度未知（`0xFFFFFFFF`）或为 0 直接放弃。

**取消只在文件边界**。中途停止 `GetObject` 的数据阶段会让数据流与响应错位、会话必须废弃，
因此**不做「半张拉取就中断」**：批量的取消标志在**每张开始前**判断一次，当前张一定跑完。

**批量导出与清理**。每张「拉取 → 套预设 → 导出到相册」后**立即删除临时文件**，
无论拉多少张，峰值磁盘占用只有一张的量级；另有 `pruneOldFiles` 兜底清理 >1h 的残留
（单张拉进编辑器的那一份会留到编辑结束，别误删近期文件）。

**目标文件名必须带后缀**。下游 `Decoder` 判定 RAW **只看后缀**（`.arw`）；
相机偶尔不给文件名（只有句柄命中），此时按相机自报类型补 `.ARW` / `.JPG`，
否则 ARW 会被当成 JPEG 走 `BitmapFactory` 而解码失败。

### 6.5 真机验证步骤

USB 连接 A7C2 → 机身「USB 连接」设 **MTP**（或 PC Remote）→ 首页
「1. 检测 USB 设备」→ 选中 `★ Sony ...` → 「2. 连接并握手」→ 首次弹授权框点「允许」→
面板列出卡内照片（新 → 旧）。

- **单张**：某行点「导入」→ 拉取完成后自动打开编辑器（ARW 走 16-bit 线性管线）。
- **批量**：勾选若干张 → 选一个预设 chip → 「3. 批量导入并套预设」→ 观察进度条；
  可点「取消（当前文件处理完即停）」。
- **断开**：点「断开连接」结束会话（或离开面板自动释放）。

若任一步失败，导出调试日志，`CAMERA` 日志里会有每条事务、每个文件、失败点。

---

## 7. 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| **接口被系统 MTP 服务占用**（`claimInterface` 失败）：Android 检测到 MTP 设备后可能由 MediaProvider 先打开 | **高** | 已把 `claimInterface` 结果单独打日志；失败时报告直接给结论。备选：机身切 PC Remote（厂商接口类 0xFF，系统通常不接管） |
| A7C2 某些模式不暴露可用的 MTP 接口 | 中 | PoC-1 先探明各模式接口类；必要时引导用户切到 MTP 模式 |
| `GetObjectHandles(0xFFFFFFFF)` 不被支持 | 中 | 已实现回退到 `associationHandle=0`（仅根层），并把「用了哪条路」写进报告 |
| 自研 PTP 协议细节多、真机调试耗时 | 中 | 协议层/数据集解析下沉为 JVM 单测；真机只剩 USB 行为 |
| 传输大文件（ARW 35–57MB）耗时/OOM | 中 | **已落地**：`GetObject` 256KB 分块流式写文件，不整段进堆；进度回调 + 文件边界取消 |
| 批量处理占满磁盘 / 中断留垃圾 | 低 | **已落地**：导完即删（峰值 ≈ 一张）+ `pruneOldFiles` 清理 >1h 残留 |
| 相机不给文件名 → 后缀缺失被误判为 JPEG | 中 | **已落地**：按相机自报类型补 `.ARW`/`.JPG`（`CameraBatch.targetName`） |
| 相机端「PC Remote」占用导致 MTP 不可用 | 中 | UI 明确提示模式切换 |
| 边拍边看依赖官方 SDK | 高 | 不承诺；先交付「拍完拉图」，liveview 单列评估 |
| targetSdk 37 后 `ACCESS_LOCAL_NETWORK` 运行时权限（Wi-Fi 方案） | 中 | 优先 USB；Wi-Fi 方案后置 |

---

## 8. 决策记录

- **D1 拉图方案**：**A 纯 Kotlin 最小 MTP**（已落地）。
- **D2 相机模式**：默认引导 **MTP**；若 `claimInterface` 被系统占用，再试 **PC Remote**。
- **D3 批处理范围**：单张（PoC-4）与批量队列（PoC-5）**均已落地**；批量取消只在文件边界生效。
- **D4 边拍边看**：本期只评估不实现。
- **D5 UI 归属**：首页独立「相机直连」入口（已落地）。
- **D6 失败姿态**：任何一步失败都返回**带原因的报告**并写日志，不重试、不静默——
  真机每轮验证成本高，必须一次拿到足够信息。
- **D7 会话模型**：**长生命周期会话**（`CameraSession`）由 UI 持有，列图/拉图/批量复用；
  I/O 串行化在会话内 `Mutex`，面板销毁兜底释放 USB。
- **D8 下载落盘**：只落 `cacheDir/camera/`，导完即删；单张拉进编辑器的那份留到编辑结束（或 >1h 清理）。

---

*本稿为 P2 设计 + PoC-1/2/3/4/5 落地说明；PoC-6（边拍边看 / 遥控）待决策，下一步为真机验收 PoC-4/5。*
