# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-10-07（本地）
> 关联提交：`31dad31`（versionName `0.4.6` / versionCode `11` —— **本轮未抬版本、未发版**）
> 本轮主题：**全量代码审查修复** —— 覆盖 `app/src` 全部 121 个 `.kt`，
> 修 21 个 P0 + 5 项性能 + 4 项 UI 规范，新增 `SilentBehaviorRegressionTest`（13 条断言）

## 结论：3 个 run 后全绿 —— 两轮红都是**真编译错**（不是 infra 抖动）

| 阶段 | 运行 | ref / 提交 | 结论 |
|---|---|---|---|
| (1) main 推「审查修复」 | run **37582299361** | `b193109` | FAILURE（2 个 `e:`：`EditEngine.kt:383` 参数类型错、`ParamPanel.kt:792/807` 重载冲突） |
| (2) main 推修复 | run **37582756671** | `4a0803a` | FAILURE（`Lint` **绿**、`Build` 红 ⇒ 主编译已过，仅 `compileDebugUnitTestKotlin` 挂：测试里一个多余 import） |
| (3) main 推修复 | run **37583175408** | `31dad31` | **4 job 全绿**（`Publish` 非 tag 按设计 skip） |

> 三个 run 全在 `main`，**未打 tag** ⇒ 本轮没有 Release，`versionCode` 保持 `11`。
> 下次发版按 §5.4 两步走：先抬版本号 → 等 main 绿 → 再在**已验证的 commit** 上打 tag。

## 任务（Job）总览 — run `37583175408`（最终绿）

| Job | 结论 | 说明 |
|---|---|---|
| Check signing secrets | success | 探测到 `KEYSTORE_BASE64` |
| Build Debug APK | success | `assembleDebug` + `testDebugUnitTest` 全过（含新增的 13 条断言） |
| Lint (Android Lint) | success | `lintDebug` 通过 |
| Signed Release | success | 解 PKCS12 keystore → `assembleRelease`（R8）→ 签名 APK + mapping |
| Publish GitHub Release | skip | 非 tag push，按设计跳过 |

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
| `render()` 把 `LayerStack` **位置传**给 `renderIntoSrgb` 的第 4 形参（那里是 `retouch: RetouchState?`） | 改具名 `layers = layers`；**并把另外两处 7 参数位置传参也一并改成具名**（`MainActivity:413` / `:874`）—— 同类错位漏一个调用点就要再等一轮 CI |
| `BeautyPartPanel` 里 `eyesSliders()` 被定义了**两遍**（改面板时先插一次、删未接线滑块时又插一次） | 删掉重复定义；加了脚本核对「本仓库两个文件均无重名函数」 |

### run 37582756671 —— 测试侧一个多余 import

```text
e: SilentBehaviorRegressionTest.kt:4:45 Unresolved reference ColorTransferParams
   Execution failed for task ':app:compileDebugUnitTestKotlin'
```

| 根因 | 修复 |
|---|---|
| `ColorTransferParams` 定义在 `core/edit/RetouchState.kt`（包 `core.edit`），与测试**同包**；我却写了 `...core.edit.retouch.ColorTransferParams` 的 import，而 `retouch` 包里没有这个类 | 删掉该 import，并在原处留注释说明「为什么不需要」，免得下次又补上。已顺手把测试侧符号全核一遍（§5.7 纪律）：`ColorTransfer.apply` 签名、四个 LUT id 与 opcode 表、`GrainNoise.shapeOf`、`EditParams` 字段名 |

> 这一轮的形态正是 `ENGINEERING_NOTES.md` §5.7 写的那一条：**Lint 绿而 Build 红 ⇒ 主编译已过，
> 错在测试侧**。所以「Lint 绿」不能当作「全部通过」。

## 本轮另一个重要发现：**本机 SSH 已不可用，push 改走 HTTPS**

- `ssh.github.com:443` → `Connection reset` / `Connection timed out`（TCP 能建，SSH 层被阻断）
- `github.com:22` → `Connection refused`；`ssh.github.com` 只解析到单个 IP（`20.205.243.160`）
- `https://github.com` → **200，且无 MITM 证书问题**；系统 credential manager 里已有凭据

⇒ 本轮三次推送全部用 `git push https://github.com/hifn123p/pixel-cake-app.git main`
（**不改仓库 remote 配置**）。完整诊断与 PowerShell 传参坑见 `ENGINEERING_NOTES.md` §5.3。

## 历史回归表

| 版本 | run数 | 结论 | 备注 |
|---|---|---|---|
| v0.4.6 发版轮 | 4 | 绿 | 首轮红（`imageEpoch` 局部变量前向引用）→ 修复 → main 绿 → tag 绿 → 报告轮绿 |
| 本轮（审查修复） | 3 | 绿 | 首轮红（2 个编译错）→ 二轮红（测试侧 import）→ 三轮绿 |

## 待办（本轮遗留）

1. `docs/CURVE_HISTOGRAM_DESIGN.md` 仍不存在 —— `Histogram.kt` 的 KDoc 引用了它（悬空引用，未修）
2. `SettingsMigrationTest` 待第一条真实设置迁移落地时同批补（当前 v0 → v1 为空迁移）
3. `ui/editor/curves/` 三个文件是死代码且内含 3 个 P0（`pointerInput(Unit)` 捕获陈旧值、
   命中测试把 dp 当 px、拖动灵敏度 1:1）—— **待决策**：接回曲线分类，还是整包删除
4. 待真机验收清单见 `ENGINEERING_NOTES.md` §5.6（本轮改动影响其中「批次 4 锐化必须晚于磨皮」与
   「批次 3 预览↔导出一致性」两条 —— 本轮刚把 JPEG 路径的阶段顺序修正到与 RAW 一致）
