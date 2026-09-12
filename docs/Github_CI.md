# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-13（本地）
> 关联提交：`7c5f3db8ba0098a495700d92bf670f4260ea9210`
> 运行链接：<https://github.com/hifn123p/pixel-cake-app/actions/runs/34709253707>

## 结论：✅ 成功（success）—— P1p-2 人脸检测链路落地（内核 / 运行时 / 锚点接线）

push 到 `main` 触发 `Android CI`。本轮为 **P1p-2 人脸检测**三连提交：
检测内核（`2448e99`）→ 人脸检测运行时（`88a0648`）→ 人脸锚点接线（`40863e8`）。
首跑 run `34708984614` 因新单测 `FaceAnchorTest.kt` 未限定嵌套类名而**编译失败**（仅单测编译阶段），
修正后 run `34709253707` **一次通过**，产出 debug APK。

## 任务（Job）总览（最终）

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `assembleDebug` + `testDebugUnitTest` 全过 → 上传 debug APK |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Check signing secrets | ✅ success | 探测 `KEYSTORE_BASE64` 是否存在 |
| Signed Release | ⏭ skipped | 未配置 `KEYSTORE_BASE64` secret |

## 本轮失败 → 修复

| Run | 提交 | 结论 | 失败点 / 修复 |
|---|---|---|---|
| `34708984614` | `40863e8` | ❌ failure | Build job 的 `:app:compileDebugUnitTestKotlin` 失败：新单测 `FaceAnchorTest.kt` 全篇以**未限定名** `FaceAnchor.fromDetection(...)` 引用类型，报一串 `Unresolved reference 'FaceAnchor'` 及级联的 `faceX`/`faceY`/`eyeX`/`eyeY`。 |
| `34709253707` | `7c5f3db` | ✅ success | 补一行 `import com.hifn.pixelcake.core.edit.retouch.RetouchLayer.FaceAnchor`（`FaceAnchor` 是 `object RetouchLayer` 的**嵌套类**；同包内不会自动可见，必须限定或导入）后全绿 |

> 诊断要点：`FaceAnchor` 定义在 `core/edit/retouch/RetouchLayer.kt` 内、**嵌套于 `object RetouchLayer`**（`RetouchLayer.FaceAnchor`）。
> 生产侧调用（`MainActivity.kt` / `EditEngine.kt` / `RetouchLayerTest.kt`）均写作 `RetouchLayer.FaceAnchor`；仅新单测漏了限定。
> 单测文件虽与 `RetouchLayer` **同包**（`com.hifn.pixelcake.core.edit.retouch`），但**同包不等于同类作用域** —— 嵌套类不写在 `RetouchLayer.` 后或 import 进来，就解析不到。属性 `faceX/...` 的报错是 `a` 类型推断失败后的级联，非独立问题。

## 本批提交

| 提交 | 说明 |
|---|---|
| `2448e99` | `feat(ml)`: P1p-2a 人脸检测内核（anchor 生成 + 解码 + 加权 NMS + letterbox） |
| `88a0648` | `feat(ml)`: P1p-2b 人脸检测运行时（模型入库 + LiteRT 实现 + 提供者） |
| `40863e8` | `feat(ml)`: P1p-2c 人脸锚点接线（脸中心/眼心 → 液化 centroid/eyeCentroid）+ UI 回显 |
| `7c5f3db` | `test`: FaceAnchorTest 补 `RetouchLayer.FaceAnchor` 导入（修复单测编译） |

## 历史回归记录

| Run | 提交 | 结论 | 失败点 |
|---|---|---|---|
| `34699770791` | `1a2f3bd` | ✅ success | 无（测试期望修正后全绿，P1p ML 自动蒙版接线落地） |
| `34699973933` | `2310fc7` | ✅ success | 无（CI 报告 docs-only 提交） |
| `34703405933` | `350378d` | ✅ success | attempt 1 lint job Gradle daemon 崩溃（基础设施抖动）→ rerun attempt 2 全绿 |
| `34703874001` | `fe534e5` | ✅ success | 无（CI 报告 docs-only 提交） |
| `34708984614` | `40863e8` | ❌ failure | 单测 FaceAnchorTest 未限定嵌套类 `RetouchLayer.FaceAnchor` → Unresolved reference |
| `34709253707` | `7c5f3db` | ✅ success | 无（补 import 后全绿，P1p-2 人脸检测链路落地） |

## 后续步骤

1. 真机（一加15）下载本轮 debug APK 验证 **P1p-2 人脸检测 → 液化锚点**：`slimFace`/`slimJaw` 落在真实脸中心、`eyeEnlarge` 落在眼心；对比「检测到人脸 / 无人脸（回退蒙版质心）」两种情形，并对比预览与导出是否一致（归一化换算护栏）。
2. 一并回归 ML 自动蒙版、retouch、相机批量、Beauty、ToneCurve（已预留调试日志）。
3. 如有新修改，按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
4. 如遇 lint job 报 `Gradle build daemon disappeared`，先 **rerun-failed-jobs** 重试一次（用 `ci_rerun.py`）。
5. 如需发布签名 Release，需在仓库 Secrets 配置 `KEYSTORE_BASE64`（及别名/密码），否则 release job 持续跳过。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 P1p-2 人脸检测链路落地，单测嵌套类限定修复后全绿。*
