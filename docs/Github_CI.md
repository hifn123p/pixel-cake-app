# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-12（本地）
> 关联提交：`350378d9de0f6bff9385f3e023879e27d70a7f3f`
> 运行链接：<https://github.com/hifn123p/pixel-cake-app/actions/runs/34703405933>

## 结论：✅ 成功（success）—— P1p-1 可达性审计（清理死代码 + 修正设计稿接口签名）

push 到 `main` 触发 `Android CI`。本轮为 **P1p-1 可达性审计**：清理无生产调用方的死代码 + 修正设计稿接口签名（commit `350378d`）。
**首次尝试（attempt 1）Lint job 因 Gradle daemon 崩溃失败**，但这是**基础设施瞬时抖动**（非代码/lint 问题）；
**重跑失败 job（attempt 2）后三个 job 全部通过**，产出 debug APK。

## 任务（Job）总览（最终 attempt 2）

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest` 全过 → 上传 debug APK |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过（第一次尝试曾因 daemon 崩溃失败，重跑通过） |
| Check signing secrets | ✅ success | 探测 `KEYSTORE_BASE64` 是否存在 |
| Signed Release | ⏭ skipped | 未配置 `KEYSTORE_BASE64` secret |

## attempt 1 失败 → 重跑（关键）

| 尝试 | 结论 | 说明 |
|---|---|---|
| attempt 1 | ❌ failure（仅 lint job） | `lintAnalyzeDebug` 执行中 Gradle daemon 消失：`Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)`，并留下 `hs_err_pid3131.log`（JVM 崩溃日志）。**无任何 lint error**。 |
| attempt 2 | ✅ success | 通过 REST API `.../actions/runs/{id}/rerun-failed-jobs` 重跑失败 job → 全绿 |

判定依据与处置：

- Build job（含 `compileDebugKotlin` + `testDebugUnitTest`）在 attempt 1 即 **success**，说明代码本身健康；
  lint job 的失败发生在 `> Task :app:lintAnalyzeDebug` 阶段，属 daemon 进程级崩溃（多为容器内存压力下 JVM 被 kill / 原生崩溃）。
- 编译期仅有 2 条**警告**（`w:`），非错误，不影响构建：
  - `Mask.kt:46` 覆写参数名与超类型 `RetouchMask` 不一致（`w`/`h`），具名实参调用时可能踩坑；
  - `DeviceCapabilities.kt:59` 使用了已被弃用的 `supportedHdrTypes`。
- 处置：不改代码，直接 **rerun-failed-jobs**（HTTP 201）→ attempt 2 通过。这是 CI 抖动的正确应对，避免用无意义提交污染历史。

## 本批提交

| 提交 | 说明 |
|---|---|
| `350378d` | `chore(ml)`: P1p-1 可达性审计 —— 清理无生产调用方的死代码 + 修正设计稿接口签名 |

## 历史回归记录

| Run | 提交 | 结论 | 失败点 |
|---|---|---|---|
| `34612770955` | `280dbfb` | ✅ success | 无（相机批量/传输与 Beauty 优化全绿） |
| `34621140044` | `c829bc1` | ✅ success | 无（retouch mask/流式化整改全绿） |
| `34687865512` | `324fb4f` | ❌ failure | 单测 SkinMaskPostProcessTest 角落期望值误写 1/9（应 1/4） |
| `34699770791` | `1a2f3bd` | ✅ success | 无（测试期望修正后全绿，P1p ML 自动蒙版接线落地） |
| `34699973933` | `2310fc7` | ✅ success | 无（CI 报告 docs-only 提交） |
| `34703405933` | `350378d` | ✅ success | attempt 1 lint job Gradle daemon 崩溃（基础设施抖动）→ rerun attempt 2 全绿 |

## 后续步骤

1. 真机（一加15）下载本轮 debug APK 验证 **ML 自动蒙版**（LiteRT/MediaPipe 皮肤分割推理、ML∪画笔合并、预览/导出四路径），以及 retouch、相机批量、Beauty、ToneCurve 运行（已预留调试日志）。
2. 如有新修改，按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
3. 如遇 lint job 再次出现 `Gradle build daemon disappeared`，先 **rerun-failed-jobs** 重试一次；若持续复现，再考虑调 `org.gradle.jvmargs` 内存或给 lint 加 `--no-daemon`。
4. 如需发布签名 Release，需在仓库 Secrets 配置 `KEYSTORE_BASE64`（及别名/密码），否则 release job 持续跳过。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 P1p-1 可达性审计落地，lint 抖动重跑后全绿。*
