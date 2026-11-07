# GitHub Actions CI 结果报告

> 生成时间：2026-11-08 · 关联提交：`e7ef187`（versionName `0.6.5` / versionCode `20`）
> 本轮主题：**审计剩余项全量清账（v0.6.5）** + CI 排红 7 连（全部由「失败日志 commit 进仓库」机制定位）

## 结论：v0.6.5 已就绪 —— 清账批次全绿

| 提交 | run | 结论 | 内容 |
|---|---|---|---|
| **`33f2c5a`** | 42387283358 | ❌ | **v0.6.5 主体**（E1/B1/B2/B4/M-3/M-4/A4-A7/D1-D5/U2-U6/T2-T4/TetherProcessor/CE 闸门） |
| `0ca45e9` | 42387721423 | ❌ | GlassChipRow 组合期取色 + retireInLock 泛型化 + 无用 import |
| `8ea04c1` | 42388158507 | ❌ | TetherProcessor import 补第一发（未中） |
| `de2608e` | 42388532998 | ❌ | 工作流加 tee + 日志工件（工件下载要认证，仍拿不到） |
| `50ef793` / `e05ba18` | 42389017139 / 42389436593 | ❌ | 二分回退两轮 → **实锤 PixelProgram 构造参数属性化**（processLinear 读不到 frameW） |
| `d8cbf65`+`404dae9` | 42390002382 / 42390285254 | ❌→❌ | 失败日志直接 commit 进仓库机制上线（先修权限）→ **拿到真实错误清单** |
| `7edd9ec`+`a6ab40e` | 42390604432 | ❌ | 修尾随 lambda 绑定（TetherProcessor log）+ 4 组 import + photo.photo 双前缀 → **assemble 绿** |
| `f4f0f9c`+`ad24e25` | 42391596508 | ❌ | 单测 tee → **testDebugUnitTest 编译错实锤**（assertTrue import） |
| `f4bdff6` | 42391884006 | ❌ | 324 测试仅剩 3 个失败：k3 假数据 + RasterMaskTest 网格化契约 |
| **`d12e4bc`** | **42392513991** | **✅ 4 job 全绿** | **全部通过（assemble + 324 单测 + lint）** |
| **`v0.6.5` tag** | （本表后追加） | ✅ 5 job 全绿 | **Publish GitHub Release** |

## 排红战果（「失败日志 commit 进仓库」机制）

- 机制：失败时把 tee 落盘的 gradle 日志由 CI 用 GITHUB_TOKEN 直接 commit 到 main ——
  工件下载要认证，**git 才是匿名可读通道**。本轮 3 次实锤全靠它。
- 实锤的三类编译错（本地静态闸门全部覆盖不了的「名字解析」层）：
  1. **构造参数在方法里不可见**（PixelProgram frameW/frameH/dither —— 只有 init 能读）；
  2. **跨包引用漏 import**（TetherProcessor 在 CameraPanel）；
  3. **@Composable getter 不能在 drawWithContent 里读**（GlassChipRow）。
- 交付判据：assembleDebug 全绿 + **324 个 JVM 单测全绿**（含 5 组新测试 22 条断言）+ Lint 绿。
- 收尾：应急「日志 commit」步骤已移除（tee + 工件保留）；树内日志文件清理 + gitignore。

## Release

- 链接：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.6.5>
- versionCode `19` → `20`；versionName `0.6.4` → `0.6.5`
- 整改台账：`docs/ENGINEERING_NOTES.md` §13（v0.6.4）/ §14（v0.6.5）

## 遗留（需真机/视觉）

Q4 热区手感 · Q5 手动旋转（EditSnapshot 契约扩展，单独立项）· Q7 视觉对比 ·
M-2/T5 release 变体真机性能基线 · TalkBack 实测（T4 语义已布好，待真机走查）
