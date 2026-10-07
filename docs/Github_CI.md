# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-10-07（本地）
> 关联提交：`b3da665`（versionName `0.4.7` / versionCode `12` —— **本轮已发版**）
> 本轮主题：**全量代码审查修复并发布 v0.4.7** —— 覆盖 `app/src` 全部 121 个 `.kt`，
> 修 21 个 P0 + 5 项性能 + 4 项 UI 规范，新增 `SilentBehaviorRegressionTest`（13 条断言）

## 结论：v0.4.7 已发布 —— 6 个 run（修复 3 + 抬版本 1 + tag 1 + 报告 1）

| 阶段 | 运行 | ref / 提交 | 结论 |
|---|---|---|---|
| (1) main 推「审查修复」 | run **37582299361** | `b193109` | FAILURE（2 个 `e:`：`EditEngine.kt:383` 参数类型错、`ParamPanel.kt:792/807` 重载冲突） |
| (2) main 推修复 | run **37582756671** | `4a0803a` | FAILURE（`Lint` **绿**、`Build` 红 ⇒ 主编译已过，仅 `compileDebugUnitTestKotlin` 挂：测试里一个多余 import） |
| (3) main 推修复 | run **37583175408** | `31dad31` | **4 job 全绿** |
| (4) main 推 CI 报告 + 通道发现 | run — | `e07c8b7` | 绿（本文件上一版 + ENGINEERING_NOTES 5.3 改写） |
| (5) main 推「抬版本号」 | run **37585139224** | `b3da665` | **4 job 全绿**（tag 的前置闸门） |
| (6) tag 触发发布 | run **37585912776** | `v0.4.7` | **5 job 全绿**（含 `Publish GitHub Release`） |

> **两步走的闸门设计生效**：只有「main 已绿」的 commit `b3da665` 才被打了 tag，
> tag 又落在同一个 commit 上（`git rev-list -n 1 v0.4.7` 已复核）。

## Release

- 链接：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.4.7>
- 附件：`pixelcake-v0.4.7-release.apk` **29.06 MB**（签名 APK）
- `versionCode` `11` → `12`；`versionName` `0.4.6` → `0.4.7`

## 任务（Job）总览 — run `37585912776`（tag run，最终绿）

| Job | 结论 | 说明 |
|---|---|---|
| Check signing secrets | success | 探测到 `KEYSTORE_BASE64` |
| Build Debug APK | success | `assembleDebug` + `testDebugUnitTest` 全过（含新增 13 条断言） |
| Lint (Android Lint) | success | `lintDebug` 通过 |
| Signed Release | success | 解 PKCS12 keystore → `assembleRelease`（R8）→ 签名 APK + mapping |
| Publish GitHub Release | success | `v*` tag 触发，上传 APK 并创建 Release（`contents: write`） |

## 本轮两轮红的报错 / 根因 / 修复

### run 37582299361 —— 两个编译错（都在改过的文件里）

```text
e: EditEngine.kt:383:38 Argument type mismatch: actual type is LayerStack, but RetouchState? was expected
e: ParamPanel.kt:792:5 Conflicting overloads
e: ParamPanel.kt:807:5 Conflicting overloads
e: ParamPanel.kt:812:9 Overload resolution ambiguity between candidates
```

| 根因 | 修复 |
|---|---|
| `render()` 把 `LayerStack` **位置传**给 `renderIntoSrgb` 的第 4 形参（那里是 `retouch: RetouchState?`） | 改具名 `layers = layers`；**并把另外两处 7 参数位置传参也一并改成具名**（`MainActivity:413` / `:874`） |
| `BeautyPartPanel` 里 `eyesSliders()` 被定义了**两遍**（改面板时先插一次、删未接线滑块时又插一次） | 删掉重复定义；加了脚本核对「本仓库两个文件均无重名函数」 |

### run 37582756671 —— 测试侧一个多余 import

```text
e: SilentBehaviorRegressionTest.kt:4:45 Unresolved reference ColorTransferParams
   Execution failed for task ':app:compileDebugUnitTestKotlin'
```

| 根因 | 修复 |
|---|---|
| `ColorTransferParams` 定义在 `core/edit/RetouchState.kt`（包 `core.edit`），与测试**同包**；却写了 `...core.edit.retouch.ColorTransferParams` 的 import | 删掉该import 并留注释；顺手把测试侧符号全核一遍（签名 / 四个 LUT id 与 opcode 表 / `GrainNoise.shapeOf` / `EditParams` 字段名） |

> 这一轮的形态正是 `ENGINEERING_NOTES.md` §5.7 写的那一条：**Lint 绿而 Build 红 ⇒ 主编译已过，
> 错在测试侧**。所以「Lint 绿」不能当作「全部通过」。

## 本轮另一个重要发现：**本机 SSH 已不可用，push 改走 HTTPS**

- `ssh.github.com:443` → `Connection reset` / `Connection timed out`（TCP 能建，SSH 层被阻断）
- `github.com:22` → `Connection refused`；`ssh.github.com` 只解析到单个 IP（`20.205.243.160`）
- `https://github.com` → **200，且无 MITM 证书问题**；系统 credential manager 里已有凭据

⇒ 本轮全部推送（含 tag）都用 `git push https://github.com/hifn123p/pixel-cake-app.git <ref>`
（**不改仓库 remote 配置**）。完整诊断与 PowerShell 传参坑见 `ENGINEERING_NOTES.md` §5.3。

## 发版一致性核对（§5.2「四者必须同时改」）

| 项 | 值 | 状态 |
|---|---|---|
| `app/build.gradle.kts` | `versionCode = 12` / `versionName = 0.4.7` | 已改 |
| git tag | `v0.4.7` → `b3da665`（= 已验证的 main commit） | 已打并推送 |
| `AboutSheet` 显示 | `appVersionLabel()` 从 `PackageManager` 读 PackageInfo 后拼版本标签 | **自动跟随**，无需改 |
| `docs/Github_CI.md` | 本文件 | 已覆盖重写 |
| `README.md` | 版本徽章 + 版本历史 + 「tag 齐全」说明 | 已改 |

## 历史回归表

| 版本 | run 数 | 结论 | 备注 |
|---|---|---|---|
| v0.4.5 发版轮 | 4 | 绿 | 引入新文件时首轮红 |
| v0.4.6 发版轮 | 4 | 绿 | 首轮红（`imageEpoch` 局部变量前向引用）→ 修复 → main 绿 → tag 绿 → 报告轮绿 |
| **v0.4.7 发版轮** | **6** | 绿 | 修复轮 3 个（两个编译错逐层暴露）+ 抬版本 1 + tag 1 + 报告 1 |

## 待办

1. `docs/CURVE_HISTOGRAM_DESIGN.md` 仍不存在 —— `Histogram.kt` 的 KDoc 引用了它（悬空引用）
2. `SettingsMigrationTest` 待第一条真实设置迁移落地时同批补（当前 v0 → v1 为空迁移）
3. `ui/editor/curves/` 三个文件是死代码且内含 3 个 P0（`pointerInput(Unit)` 捕获陈旧值、
   命中测试把 dp 当 px、拖动灵敏度 1:1）—— **待决策**：接回曲线分类，还是整包删除
4. **真机验收（本轮改动直接影响这两条）**：
   - 「批次 4 锐化必须晚于磨皮」—— 本轮把 JPEG 路径的阶段顺序修正到与 RAW 一致，
     即从「先锐化后磨皮」变成「先磨皮后锐化」，JPEG 导出的锐化观感会**变强**（这是修复方向）
   - 「批次 3 预览 ↔ 导出一致性」—— 同上，JPEG 导出的观感会与预览重新对齐
   - 其余见 `ENGINEERING_NOTES.md` §5.6
