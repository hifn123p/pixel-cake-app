# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-11（本地）
> 关联提交：`c40323f60a1bf92c09a145357c83b2a78e1012c7`
> 运行链接：<https://github.com/hifn123p/pixel-cake-app/actions/runs/34601040254>

## 结论：✅ 成功（success）—— 相机 PTP 连接模块编译/单测/Lint 全绿

push 到 `main` 触发 `Android CI`。本轮新增相机 **PTP（Picture Transfer Protocol）连接模块**
（`PtpTransport` / `PtpProtocol` / `PtpData` / `CameraConnection` / `CameraPtpReport` + 三个单测），
并接入 `UsbCameraScanner` / `CameraPanel` / `HomeScreen`（commit `36ba91e` 引入，`c40323f` 修一处编译错误）。
对应 run `34601040254`。

## 任务（Job）总览

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `compileDebugKotlin` + `testDebugUnitTest` 全过 → 上传 debug APK |
| Lint (Android Lint) | ✅ success | `Run lint`（lintDebug）通过 |
| Check signing secrets | ✅ success | 探测 `KEYSTORE_BASE64` 是否存在 |
| Signed Release | ⏭ skipped | 未配置 `KEYSTORE_BASE64` secret |

> 本轮产出 APK artifact；无失败 job。

## 本轮修复回顾（1 处）

- **run `34600171311`（failure）**：`:app:compileDebugKotlin` 报
  `PtpTransport.kt:48:68 Function invocation 'responseName()' expected.`
- **根因**：字符串模板里写了 `$responseName()`。Kotlin 模板中 `$name` 只解析**简单变量**；`responseName` 是
  `fun responseName(): String`，写成 `$responseName` 相当于把**函数当值**引用，`()` 变成字面量文本 → 编译报错。
- **修复（commit `c40323f`）**：模板内函数调用必须加花括号 → `${responseName()}`：
  ```kotlin
  return "${PtpProtocol.operationName(operationCode)} → ${responseName()}$dataNote，${elapsedMs}ms"
  ```
- 全仓扫描 `\$[A-Za-z_]\w*\(` 确认仅此一处，无同类残留。

## 历史回归记录

| Run | 提交 | 结论 | 失败点 |
|---|---|---|---|
| `34433386106` | `610f85f` | ✅ success | 无 |
| `34481989723` | `9c67297` | ❌ failure | MainActivity.kt:110/195 尾随 lambda 绑错参数 |
| `34485237118` | `6d5f4e3` | ❌ failure | 单测 NeutralGrayTest.kt Long/Int 类型不匹配 |
| `34486614190` | `e68ac15` | ✅ success | 无 |
| `34496632034` | `02e5b15` | ❌ failure | `Presets.kt` 块注释嵌套（`*.json`）吞掉整文件 |
| `34498542664` | `a1cbbf4` | ❌ failure | `Preset` 未 import + `PresetRow`/`ColorTransferRow` 未定义 |
| `34500365041` | `84a3cc0` | ✅ success | 无 |
| `34504178268` | `163fe93` | ✅ success | 无（相机模块 P2 首跑） |
| `34600171311` | `36ba91e` | ❌ failure | PtpTransport.kt:48 模板内 `$responseName()` 未加花括号 |
| `34601040254` | `c40323f` | ✅ success | 无（PTP 模块修复后全绿） |

## 后续步骤

1. 真机（一加15）下载本轮 debug APK 验证 PTP 相机连接 / 报表与拍摄模块运行是否正常（已预留调试日志）。
2. 如有新修改，按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
3. 如需发布签名 Release，需在仓库 Secrets 配置 `KEYSTORE_BASE64`（及别名/密码），否则 release job 持续跳过。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮相机 PTP 连接模块经一处字符串模板修复后全绿。*
