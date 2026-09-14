# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-14（本地）
> 关联提交：`ca4ac119e40a4566fec330b04322b6cdf5ce9b15`
> 运行链接：<https://github.com/hifn123p/pixel-cake-app/actions/runs/34816492199>

## 结论：✅ 成功（success）—— UI 改版继续（玻璃组件扩展 / 动效 token 无障碍坍缩）

push 到 `main` 触发 `Android CI`。本轮为 **UI-1c**：玻璃组件扩展与页面接线（`c3841cd`，21 文件 +1406/-359）
+ 动效 token 的「系统移除动画」坍缩修正（`ca4ac11`）。
首跑 run `34816082925` 因 `AppShell.kt` 两处 `@Composable` 上下文错误（Build + Lint 双红），
修正后 run `34816492199` **一次通过**，产出 debug APK。

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
| `34816082925` | `c3841cd` | ❌ failure（Build + Lint） | `AppShell.kt:82` 与 `:87` 报 `@Composable invocations can only happen from the context of a @Composable function`：`AnimatedContent` 的 `transitionSpec` lambda 里调用了 `Motion.durationFor(...)`，而该函数当时被标注 `@Composable`。 |
| `34816492199` | `ca4ac11` | ✅ success | 去掉 `Motion.durationFor` / `Motion.reduceMotion` 的 `@Composable` 标注后全绿 |

> **根因**：`Motion.reduceMotion()` 用 `remember { !ValueAnimator.areAnimatorsEnabled() }`，被标了 `@Composable`，
> 连带 `durationFor()` 也是 `@Composable`。但 `transitionSpec` 是 `AnimatedContent` 的**非合成**作用域 lambda，
> 在其中调用 `@Composable` 必然编译失败。
>
> **修复与理由**：`areAnimatorsEnabled()` 只是一次静态读取，**不需要**合成作用域；且 `remember` 无 key ⇒ 永不刷新，
> 反而无法响应系统开关变化。故两者改为**普通函数**（`fun durationFor(ms: Int): Int = if (reduceMotion()) 0 else ms`），
> 于是「合成内」与「`transitionSpec` 等非合成上下文」都可调用 —— 与 `UI_DESIGN.md` §5 要求的
> `tween(Motion.durationFor(Motion.base), easing = ...)` 写法在**任何位置**都成立。顺带删掉失效的 `remember` 导入。
> `EditorScreen` 那 6 处调用点不受影响（非 composable 函数当然可被 composable 调用）。

## 本批提交

| 提交 | 说明 |
|---|---|
| `c3841cd` | `feat(ui)`: UI-1c 玻璃组件扩展与页面接线（新增 GlassCircleButton/ImportSheet/PresetThumbRow/CameraSheet + 主题与页面调整） |
| `ca4ac11` | `fix(ui)`: Motion.durationFor/reduceMotion 去掉 `@Composable`（可在 transitionSpec 等非合成上下文调用） |

新增文件：`ui/components/{GlassCircleButton,ImportSheet,PresetThumbRow}.kt`、`ui/home/CameraSheet.kt`；
改动：`MainActivity`、`ui/components/{ActionTile,GlassCard,GlassSegmentedBar,ParamSlider}`、
`ui/editor/{EditorScreen,ParamPanel}`、`ui/home/HomeScreen`、`ui/settings/SettingsScreen`、
`ui/shell/AppShell`、`ui/theme/{Color,Glass,Motion,Radius,Theme}`、`docs/UI_DESIGN.md`、`docs/ui_preview.html`。

## 历史回归记录

| Run | 提交 | 结论 | 失败点 |
|---|---|---|---|
| `34708984614` | `40863e8` | ❌ failure | 单测 FaceAnchorTest 未限定嵌套类 `RetouchLayer.FaceAnchor` → Unresolved reference |
| `34709253707` | `7c5f3db` | ✅ success | 无（补 import 后全绿，P1p-2 人脸检测链路落地） |
| `34709474264` | `3d5b62c` | ✅ success | 无（CI 报告 docs-only 提交） |
| `34744787789` | `ca5410b` | ❌ failure | ActionTile 缺 `dp` 导入 + AppShell `togetherTo` 笔误（两处编译错误） |
| `34745071202` | `fbc5e49` | ✅ success | 无（UI-1 改版编译修复后全绿） |
| `34745274221` | `30b73e3` | ✅ success | 无（CI 报告 docs-only 提交） |
| `34816082925` | `c3841cd` | ❌ failure | AppShell `transitionSpec` 中调用 `@Composable` 的 `Motion.durationFor` |
| `34816492199` | `ca4ac11` | ✅ success | 无（Motion token 去 `@Composable` 后全绿） |

## 后续步骤

1. 真机（一加15）下载本轮 debug APK 验收 **UI 改版**：玻璃 TabBar、导入 Sheet、预设缩略图行、相机 Sheet、圆形图标按钮、
   设置页、编辑器工具栏 / 参数面板 / 导出 Sheet、背景与动效（对照 `docs/UI_DESIGN.md` 与 `docs/ui_preview.html`）。
2. **无障碍回归**：系统「设置 → 开发者选项 → 动画程序时长缩放 = 关闭」后，所有 `tween` 淡入淡出应立即到位、无残影
   （这正是本轮 `durationFor` 的护栏所保证的行为）。
3. 一并回归 P1p-2 人脸检测 → 液化锚点、ML 自动蒙版、retouch、相机批量、Beauty、ToneCurve。
4. 如有新修改，按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
5. 如遇 lint job 报 `Gradle build daemon disappeared`，先 **rerun-failed-jobs** 重试一次（用 `ci_rerun.py`）。
6. 如需发布签名 Release，需在仓库 Secrets 配置 `KEYSTORE_BASE64`（及别名/密码），否则 release job 持续跳过。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 UI 改版继续，`@Composable` 上下文错误修复后全绿。*
