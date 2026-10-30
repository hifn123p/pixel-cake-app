# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-10-25（本地）
> 关联提交：`deca6d4`（versionName `0.5.0` / versionCode `13` —— **本轮已发版**）
> 本轮主题：**第二轮全量审查**（3 个 P1 + 12 个 P2 + 删 `ui/editor/curves/` 死代码 + 3 个新测试）
> 与 **UI v2.1**（预览缩放手势、调色菜单 7->6、RGB 直方图、尺寸回调）

## 结论：v0.5.0 已发布 —— 4 个 run（3 次修复 + 1 次发布）

| 阶段 | 运行 | ref / 提交 | 结论 |
|---|---|---|---|
| (1) main 推「审查修复 + 抬版本」 | run **40189067999** | `3f237dc` | FAILURE（`EditorScreen.kt:58` import 了不存在的顶层 extension `getDistance`） |
| (2) main 推修复 | run **40189438733** | `462989e` | FAILURE（`Lint` **绿**、`Build` 红 => 编译已过，挂在 `testDebugUnitTest`：`HistogramTest` 两处数组越界） |
| (3) main 推修复 | run **40189823880** | `deca6d4` | **4 job 全绿**（tag 的前置闸门） |
| (4) tag 触发发布 | run **40190769073** | `v0.5.0` | **5 job 全绿**（含 `Publish GitHub Release`） |

> **两步走的闸门设计生效**：只有「main 已绿」的 commit `deca6d4` 才被打了 tag，
> tag 落在同一个 commit 上（`git rev-list -n 1 v0.5.0` 已复核）。

## Release

- 链接：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.5.0>
- 附件：`pixelcake-v0.5.0-release.apk` **29.05 MB**（签名 APK）
- `versionCode` `12` -> `13`；`versionName` `0.4.7` -> `0.5.0`

## 任务（Job）总览 — run `40190769073`（tag run，最终绿）

| Job | 结论 | 说明 |
|---|---|---|
| Check signing secrets | success | 探测到 `KEYSTORE_BASE64` |
| Build Debug APK | success | `assembleDebug` + `testDebugUnitTest` 全过（含 3 个新测试文件） |
| Lint (Android Lint) | success | `lintDebug` 通过 |
| Signed Release | success | 解 PKCS12 keystore -> `assembleRelease`（R8）-> 签名 APK + mapping |
| Publish GitHub Release | success | `v*` tag 触发，上传 APK 并创建 Release（`contents: write`） |

## 本轮两轮红的报错 / 根因 / 修复

### run 40189067999 —— 一个多余的 import

```text
e: EditorScreen.kt:58:37 Unresolved reference 'getDistance'
```

| 根因 | 修复 |
|---|---|
| `Offset.getDistance()` 是**成员函数**（Compose 1.0 起就是），从来不需要 import；而 `androidx.compose.ui.geometry.getDistance` 这个**顶层 extension 并不存在** | 删掉该import；调用点 `(c.position - first.position).getDistance()` 本身完全正确 |

### run 40189438733 —— 测试自己越界（不是断言不通过）

```text
HistogramTest > meanShift_identicalIsZero FAILED (HistogramTest.kt:74)
HistogramTest > meanShift_brighterAfterMeansPositive FAILED (HistogramTest.kt:65)
```

| 根因 | 修复 |
|---|---|
| 两处都写了 `IntArray(4)` 却往索引 `10` / `9` / `200` 写值 => `IndexOutOfBoundsException`。**生产代码一行未改**（`Histogram.meanShift` 本来就对） | 数组开满 `Histogram.BINS`，并留注释说明为什么不能写 `IntArray(4)` |

> ⚠️ **本轮最重要的一条读日志判据**（已写进 `ENGINEERING_NOTES.md` §7.5）：
> **CI 日志里「测试 FAILED」但**没有** `expected:/but was:` 行
> => 大概率不是断言不通过，而是**测试自己抛了异常**。
> 本地无编译器时极易误判成「被测代码算错了」而去改**生产代码**。
> 正确读法：先确认有没有 `expected/but was`；没有就去看 `at <类>.<方法>(行号)` 指向哪一行 ——
> 那是**抛异常的那一行**，不是「断言失败的那一行」。

## 发版一致性核对（§5.2「四者必须同时改」）

| 项 | 值 | 状态 |
|---|---|---|
| `app/build.gradle.kts` | `versionCode = 13` / `versionName = 0.5.0` | 已改 |
| git tag | `v0.5.0` -> `deca6d4`（= 已验证的 main commit） | 已打并推送 |
| `AboutSheet` 显示 | `appVersionLabel()` 从 `PackageManager` 读 PackageInfo 后拼版本标签 | **自动跟随**，无需改 |
| `docs/Github_CI.md` | 本文件 | 已覆盖重写 |
| `README.md` | 版本徽章 + 版本历史 + 路线图 + 特性表 | 已改 |

## 本轮网络现实（§5.3 的补充）

- `ssh.github.com:443` 仍不可用（reset / timeout），`github.com:22` refused
- `https://api.github.com` 通（200），`https://github.com` 的 connect **被抽样阻断**：
  同一 IP `20.205.243.166` 用 python 直连能握手成功，而 git 的 connect 超时 21s
  => **纯间歇性**，不是配置问题。
- ⇒ **push 必须做重试**：本轮推 tag 连续失败 6 次，第 7 次成功。
  `git push` 重复无害（tag 幂等），所以「失败就重试」是安全策略。
- 推 tag 建议用 `git -c http.curloptResolve=github.com:443:<ip>` 强制 IPv4
  （本机 git 2.53 支持；仅作用于单条命令，不改仓库/全局配置）。

## 历史回归表

| 版本 | run 数 | 结论 | 备注 |
|---|---|---|---|
| v0.4.5 发版轮 | 4 | 绿 | 引入新文件时首轮红 |
| v0.4.6 发版轮 | 4 | 绿 | 首轮红（`imageEpoch` 局部变量前向引用） |
| v0.4.7 发版轮 | 6 | 绿 | 两个编译错逐层暴露（main -> 测试侧） |
| **v0.5.0 发版轮** | **4** | 绿 | 一个 import 错 + 一个测试越界；新增 3 个测试文件 |

## 待办

1. ~~`docs/CURVE_HISTOGRAM_DESIGN.md` 悬空引用~~ **已闭合（2026-10-30）**：`Histogram.kt` 的 KDoc
   已改指向实际归属（曲线与管线顺序 → `TONING_DESIGN.md`；直方图 UI 入口 → `UI_DESIGN.md` §4.0.10）
2. `SettingsMigrationTest` 待第一条真实设置迁移落地时同批补
3. **真机验收（本轮改动直接影响这些）**：
   - **预览缩放**（UI v2.1 新功能）：双指缩放 <= 8x、平移边界、双击 2.5x 的锚点是否对准点击处
   - **调色菜单 7 -> 6**：分类重排后「明暗/颜色」两页的参数有没有漏项；LUT 是否已被「预设 -> 滤镜」接管
   - **RGB 直方图**：是否挂在「明暗」页；与实际参数是否对得上
   - **尺寸回调**：字阶 28->24/18->16 等在小屏上会不会挤
   - **ARW 预览候选过滤**：超限 ARW 现在回退到合规次大候选，需验证常见机型仍取到全尺寸预览
