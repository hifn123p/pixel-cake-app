# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-12（本地）
> 关联提交：`1a2f3bde4a16b69936026e14e7680f8ffa7e0836`
> 运行链接：<https://github.com/hifn123p/pixel-cake-app/actions/runs/34699770791>

## 结论：✅ 成功（success）—— P1p ML 自动蒙版接线全绿

push 到 `main` 触发 `Android CI`。本轮为 **P1p ML 自动蒙版**功能落地：
接入 LiteRT + MediaPipe 皮肤分割模型（自动蒙版内核，commit `13a691d`）→ 编辑器自动蒙版接线
（ML∪画笔 / 开关 / 预览+导出四路径，commit `8161ec0`）→ 设计稿与进度同步（`7aba165`/`324fb4f`）。
首跑 run `34687865512` 因单测断言失败红灯，修正后 run `34699770791` **一次通过**，产出 debug APK。

## 任务（Job）总览

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `compileDebugKotlin` + `testDebugUnitTest` 全过 → 上传 debug APK |
| Lint (Android Lint) | ✅ success | `Run lint`（lintDebug）通过 |
| Check signing secrets | ✅ success | 探测 `KEYSTORE_BASE64` 是否存在 |
| Signed Release | ⏭ skipped | 未配置 `KEYSTORE_BASE64` secret |

> 本轮产出 APK artifact；失败点已修复，无遗留失败 job。

## 本批提交

| 提交 | 说明 |
|---|---|
| `7aba165` | `docs`: P1+ ML 自动蒙版设计稿 + 更正 DEV_PLAN 过期 NNAPI 口径 |
| `13a691d` | `feat(ml)`: P1p-1a 接入 LiteRT + MediaPipe 皮肤分割模型（自动蒙版内核） |
| `8161ec0` | `feat(ml)`: P1p-1b 编辑器自动蒙版接线（ML∪画笔 / 开关 / 预览+导出四路径） |
| `324fb4f` | `docs`: 同步 P1p-1b 进度（DEV_PLAN 矩阵与口径 + P1p_DESIGN 进度表） |
| `1a2f3bd` | `test`: 修正 SkinMaskPostProcessTest 角落期望值（按实际邻居数均值 → 角落 1/4 而非 1/9） |

## 本轮失败 → 修复

| Run | 提交 | 结论 | 失败点 / 修复 |
|---|---|---|---|
| `34687865512` | `324fb4f` | ❌ failure | 单测运行时断言：`SkinMaskPostProcessTest.smooth3x3AveragesSevenNeighbours`——`smooth3x3` 按**窗口内实际存在的邻居数**取均值，角落仅 4 格 → 期望应为 `1/4`，测试误写 `1/9`。主代码编译 + Lint 已绿，属测试期望错误而非实现 bug。 |
| `34699770791` | `1a2f3bd` | ✅ success | 修正测试角落期望值 + 重命名方法后全绿 |

> 诊断要点：`smooth3x3` 对边界窗口按 `cnt`（实际邻居数）取均值，corner 分母=4、center 分母=9；
> 姊妹测试 `smooth3x3UsesExistingNeighbourCountAtCorners`（全 1 平滑后仍全 1）本就只在「按实际邻居」语义下成立，可作为交叉验证。

## 历史回归记录

| Run | 提交 | 结论 | 失败点 |
|---|---|---|---|
| `34604049465` | `304096c` | ❌ failure | 单测 CameraBatchTest.kt `isPhoto` 传 `CameraPhoto` 而非 `PtpObjectInfo` |
| `34604552494` | `ac6e1aa` | ✅ success | 无（单测类型修复后全绿） |
| `34606236187` | `ac742c8` | ✅ success | 无（ToneCurve 模块首跑） |
| `34612770955` | `280dbfb` | ✅ success | 无（相机批量/传输与 Beauty 优化全绿） |
| `34621140044` | `c829bc1` | ✅ success | 无（retouch mask/流式化整改全绿） |
| `34687865512` | `324fb4f` | ❌ failure | 单测 SkinMaskPostProcessTest 角落期望值误写 1/9（应 1/4） |
| `34699770791` | `1a2f3bd` | ✅ success | 无（测试期望修正后全绿，P1p ML 自动蒙版接线落地） |

## 后续步骤

1. 真机（一加15）下载本轮 debug APK 验证 **ML 自动蒙版**（LiteRT/MediaPipe 皮肤分割推理、ML∪画笔合并、预览/导出四路径），以及 retouch、相机批量、Beauty、ToneCurve 运行（已预留调试日志）。
2. 如有新修改，按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
3. 如需发布签名 Release，需在仓库 Secrets 配置 `KEYSTORE_BASE64`（及别名/密码），否则 release job 持续跳过。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 P1p ML 自动蒙版接线落地，测试期望修正后全绿。*
