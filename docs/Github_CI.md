# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-10（本地）
> 关联提交：`e68ac15e8b495d6075ab129dddbc29b2db496545`
> 运行链接：<https://github.com/hifn123p/pixel-cake-app/actions/runs/34486614190>

## 结论：✅ 成功（success）—— retouch 整改 + 单测类型修复全部通过

push 到 `main` 触发 `Android CI`。本轮为单测 `NeutralGrayTest.kt` 的 `Long`→`Int` 类型修复（commit `e68ac15`，对应 run `34486614190`）。
前一轮（run `34485237118`）主代码已绿、仅单测编译因 `v: Long` 与 `or`/`shl` 的 `Int` 运算冲突失败；本轮加上 `.toInt()` 后**单测编译通过**，
全链路打通：Build 编译 → 单测执行 → Lint 全部转绿，并上传 debug APK。

## 任务（Job）总览

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `compileDebugKotlin` + `testDebugUnitTest` 全过 → 上传 debug APK |
| Lint (Android Lint) | ✅ success | `Run lint`（lintDebug）通过 |
| Check signing secrets | ✅ success | 探测 `KEYSTORE_BASE64` 是否存在 |
| Signed Release | ⏭ skipped | 未配置 `KEYSTORE_BASE64` secret |

> 本轮产出 APK artifact（Build 成功上传）；release 因无签名 secret 跳过。retouch 局部调整功能至此端到端可编译、可测、可 lint。

## 本轮关键修复

- **文件**：`app/src/test/java/com/hifn/pixelcake/core/edit/retouch/NeutralGrayTest.kt`
- **改动**：第 19 行 `val v = (128 + n).coerceIn(0, 255)` → `val v = (128 + n).coerceIn(0, 255).toInt()`
- **原因**：`seed = 12345L`（`Long`）使 `n` / `v` 整条链推断为 `Long`；`or` / `shl` 是 `Int` 中缀运算，要求 `Int`。`.toInt()` 把 0–255 域安全转回 `Int`，消除类型不匹配。

## 历史回归记录

| Run | 提交 | 结论 | 失败点 |
|---|---|---|---|
| `34379432771` | `0c972ee` | ❌ failure | 单测 F04 |
| `34430484406` | `71f9737` | ❌ failure | 编译 MainActivity.kt:97 缺 import |
| `34433386106` | `610f85f` | ✅ success | 无 |
| `34481989723` | `9c67297` | ❌ failure | 编译 MainActivity.kt:110/195 尾随 lambda 绑错参数（retouch 接入） |
| `34485237118` | `6d5f4e3` | ❌ failure | 单测 NeutralGrayTest.kt:19/20 `Long`/`Int` 类型不匹配 |
| `34486614190` | `e68ac15` | ✅ success | 无（单测 `.toInt()` 修复后全绿） |

## 后续步骤

1. 真机（一加15）下载本轮 debug APK 验证 retouch 局部调整功能运行是否正常（已预留调试日志）。
2. 如有新修改，按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
3. 如需发布签名 Release，需在仓库 Secrets 配置 `KEYSTORE_BASE64`（及别名/密码），否则 release job 持续跳过。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；retouch 整改链路（run 34481989723 → 34485237118 → 34486614190）历经两处编译/类型问题，本轮全绿。*
