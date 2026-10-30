# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-10-30（本地）
> 关联提交：`681da13`（versionName `0.5.1` / versionCode `14` —— **本轮已发版**）
> 本轮主题：**真机反馈（一加15）—— 参数面板腾出高度**
> （chip 行改单行横滚 + 紧凑内边距 / 预设缩略图按宽度反算边长 / 预览·参数分割比重算）

## 结论：v0.5.1 已发布 —— **2 个 run，一次通过，零红轮**

| 阶段 | 运行 | ref / 提交 | 结论 |
|---|---|---|---|
| (1) main 推「UI 修正 + 抬版本」 | run **41079595382** | `681da13` | **4 job 全绿**（`Publish` 非 tag 跳过） |
| (2) tag 触发发布 | run **41081158258** | `v0.5.1` | **5 job 全绿**（含 `Publish GitHub Release`） |

> 这是发版流程的**理想形态**：修复与抬版本同批推送 -> main 一次绿 -> 打 tag -> 发布。
> 没有「首轮红」的原因之一是：本轮改动全部落在**已有文件的既有函数内**
> （无新增文件、无新增类型、无新依赖），且推送前做了 import / 具名实参 / 参数顺序的静态核对。

## Release

- 链接：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.5.1>
- 附件：`pixelcake-v0.5.1-release.apk` **29.06 MB**（30,472,601 字节，签名 APK）
- `versionCode` `13` -> `14`；`versionName` `0.5.0` -> `0.5.1`
- 发布时间：2026-10-30T15:33:24Z

## 任务（Job）总览 — run `41081158258`（tag run，最终绿）

| Job | 结论 | 说明 |
|---|---|---|
| Check signing secrets | success | 探测到 `KEYSTORE_BASE64` |
| Build Debug APK | success | `assembleDebug` + `testDebugUnitTest` 全过 |
| Lint (Android Lint) | success | `lintDebug` 通过 |
| Signed Release | success | 解 PKCS12 keystore -> `assembleRelease`（R8）-> 签名 APK + mapping |
| Publish GitHub Release | success | `v*` tag 触发，上传 APK 并创建 Release（`contents: write`） |

> main run `41079595382` 与 tag run 的差别只有最后一项：非 tag 时 `Publish` 按设计 **skip**。

## 本轮改了什么（为什么改）

真机反馈原话：「直方图和调节按钮被压到手机以外的区域，必须先缩小预览才能用」。

### 1. 面板里的 chip 行：换行 -> 单行 + 横向滚动

`GlassChipRow` 新增两个参数：

| 参数 | 作用 |
|---|---|
| `singleRow` | `Row + horizontalScroll`，高度恒定一行（默认仍是 `FlowRow` 换行） |
| `compact` | label 内边距从 `Spacing.m` 收到 `Spacing.s`，同宽多放 1~2 个 chip |

`ParamPanel` 里 **作用域行 + 5 个一级分类的二级行**全部启用。固定开销从约 **290dp** 降到约 **130dp**。

> ⚠️ 实现上把 chip 的生成逻辑抽成**单个** `@Composable` lambda，两条容器分支只换容器、不换内容。
> 两处各写一遍 `forEach` 迟早会漂移（一边记得传 `itemEnabled`、另一边忘了）。

### 2. 预设缩略图：按可用宽度反算边长

`PresetThumbRow` 改用 `BoxWithConstraints`：以 `size` 为**最小单元**估列数，再用可用宽度反算
`cell` 边长。原先「固定边长 + FlowRow」会让最后一行右侧留白，且面板变宽时格子不跟着长。

### 3. 预览/参数分割比重算

| 常量 | 旧值 | 新值 |
|---|---|---|
| `SPLIT_MIN` | 0.22 | **0.18** |
| `SPLIT_MAX` | 0.78 | **0.72** |
| `SPLIT_DEFAULT` | 0.45 | **0.34** |

`0.34 = 3:2 横构图照片按宽度贴合所需高度 / 屏幕可用高度`（287 / 835 ≈ 0.34）。
0.45 多出来的部分是**预览区里的空气**，不是照片。

> ⚠️ 这三个常量是一组：只调默认值而不压固定行 = 把空气挪到参数区；
> 只压固定行而不调默认值 = 横构图用户仍白付 130px。

## 发版一致性核对（§5.2「四者必须同时改」）

| 项 | 值 | 状态 |
|---|---|---|
| `app/build.gradle.kts` | `versionCode = 14` / `versionName = 0.5.1` | 已改 |
| git tag | `v0.5.1` -> `681da13`（= 已验证的 main commit） | 已打并推送 |
| `AboutSheet` 显示 | 从 `PackageManager` 读 PackageInfo 后拼版本标签 | **自动跟随**，无需改 |
| `docs/Github_CI.md` | 本文件 | 已覆盖重写 |

## 本轮网络现实（§5.3 的补充）

- `ssh.github.com:443` 仍不可用；`https://github.com` 的 connect **仍被抽样阻断**。
- 环境有本地代理 `127.0.0.1:7583`（`HTTP(S)_PROXY`），git 走 CONNECT 隧道。
- 本轮实测：
  - main push（`140.82.112.3`）**一次成功**；
  - tag push 连续两次失败 —— `140.82.112.3` 报 `schannel: server closed abruptly (missing close_notify)`、
    `20.205.243.166` 报 `CONNECT tunnel failed, response 502`；
  - 换 `140.82.121.3` **成功**。
- ⇒ 结论不变：**push 失败就换 IP 重试**，`git push` 幂等，重试安全。
  `git -c http.curloptResolve=github.com:443:<ip>` 只作用于单条命令，不改仓库/全局配置。

## 历史回归表

| 版本 | run 数 | 结论 | 备注 |
|---|---|---|---|
| v0.4.5 发版轮 | 4 | 绿 | 引入新文件时首轮红 |
| v0.4.6 发版轮 | 4 | 绿 | 首轮红（`imageEpoch` 局部变量前向引用） |
| v0.4.7 发版轮 | 6 | 绿 | 两个编译错逐层暴露（main -> 测试侧） |
| v0.5.0 发版轮 | 4 | 绿 | 一个 import 错 + 一个测试越界；新增 3 个测试文件 |
| **v0.5.1 发版轮** | **2** | 绿 | **一次通过（零红轮）**；改动全落在已有文件内 |

## 待真机验收（本轮改动直接影响这些）

1. **chip 行横滑**：作用域 9 项、二级分组 3~5 项是否都能滑到末尾；半露的 chip 是否足够提示可滑。
2. **紧凑内边距**：双字 chip 是否仍好点（间距变窄后误触有没有增加）。
3. **预设缩略图**：格子是否随屏宽变大、最后一行是否铺满（不再右侧留白）。
4. **默认分割比 0.34**：横构图照片是否仍「够大」；竖构图是否会显得偏小（可拖分隔条自救）。
5. **参数区容量**：直方图 + 4 个滑块是否无需缩小预览即可见（本轮核心目标）。
6. 回归：`PresetThumbRow` 在极窄宽度下 `cols` 是否恒 >= 1（不会出现 0 列除零）。
