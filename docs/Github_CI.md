# GitHub Actions CI 结果报告

> **当前状态（2026-11-14 核对）**：本文件仍是 **v0.7.0 发版 CI 报告（历史快照）**，
> 另附 **v0.7.1 阶段仓库内可见的失败 run 记录**。
> 代码基线 HEAD `f578559`（2026-11-11，`versionName 0.7.1` / `versionCode 23`）。
> ⚠️ 按项目规则「我通知你是否上传、是否检查 CI 结果」，本次**未 fetch、未查询远端 CI**，
> 因此下面 v0.7.1 的 run 列表只代表**入库的 `ci-log` 失败日志快照**，不是最终 CI 结论。
> 本文件应在 v0.7.1 出现全绿 run 后按 `ENGINEERING_NOTES.md` §5.4 覆盖重写。

---

## 1. v0.7.0 发版报告（历史快照，2026-11-08）

> 生成时间：2026-11-08 · 关联提交：`e794781`（`versionName 0.7.0` / `versionCode 22`）
> 本轮主题：**架构与诊断** —— 导航层 / 模型中心 / 预设分享

### 结论：v0.7.0 已发布（tag run 全绿）

| 提交 | run | 结论 | 内容 |
|---|---|---|---|
| **`714a31a`** | 42483266937 | ❌ | **v0.7.0 主体**（NavStack / 模型中心 / 预设导入导出 / .gitmodules 钉子）—— 4 组编译错 |
| `de2608e`+`dc3a8e3` | 42486690047 / 42476680847 | ❌ | 恢复/加固失败日志 commit 机制（`git add -f`）→ 实锤 |
| **`8c40d70`** | 42485590146 | ❌ | **修 7 组错**（NavStack 显式泛型等）—— 但 `PresetParams` 签名漏 `onImport` 仍在 |
| **`024ff8c`** | 42486291177 | ❌ | `PresetParams` 签名补 `onImport` —— 只剩 Call B 一处 |
| `ae9cd48` | 42487125453 | ❌ | 摆正 Call A（ParamPanel→PresetParams 传外层名）—— Call B 反向被污染 |
| `4abc2ba` | 42487332764 | ❌ | Call B 补 `onImport` —— 只差最后一步 |
| `8351564` | 42487878831 | ❌ | `NavStackTest` 显式泛型 + workflow 缓存清剿步骤 |
| **`e794781`** | **42489581102** | **✅ 4 job 全绿** | `withFreshIds` 测试逻辑修正 + 清理树内陈旧日志（**当时 338 单测**） |
| **`v0.7.0` tag** | **42490180023** | **✅ 5 job 全绿** | **Publish GitHub Release** |

> 「338 单测」是 v0.7.0 时点数字；当前 `app/src/test` 为 **50 个 `.kt` / 352 个 `@Test`**。
> 上表所有 ✅ 都只描述 **v0.7.0 tag 时点**，不要外推到 v0.7.1。

### 本轮两条新纪律

1. **「链条式接线」必须在收尾时整体核对**：`onImport` 从 UI 到存储要穿过 5 个签名
   （MyPresetsSection → PresetParams → ParamPanel → EditorScreen → MainActivity），
   多轮编辑后两处调用点的参数名交叉污染；逐个修复时又把两块互相写反。
   收尾应一次 grep 验证调用链两端，而不是「修一个跑一次」。
2. **CI 日志文件会作为「陈旧证据」留在树里**：`ci-log` 提交的 assemble / lint / unit-tests.log
   在后续 run 里不会被更新（每个 job 只 tee 自己的那个），误读会得出与树不符的行号。
   本轮「幻影错误」实为读到旧日志；已清理树内日志 + `.gitignore`，
   并在 workflow 加了缓存清剿步骤。

### Release（v0.7.0）

- 链接：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.7.0>
- `versionCode 21 → 22`；`versionName 0.6.6 → 0.7.0`
- 功能台账：`ENGINEERING_NOTES.md` §15（v0.6.6）/ **§16（v0.7.0，已补）**

---

## 2. v0.7.1 阶段：仓库内可见记录（2026-11-11，非最终结论）

> 来源仅为 `git log` 中入库的 `ci-log` 提交与修复提交。**这些是失败日志快照，
> 不表示最后一次 CI 的状态**；HEAD 之后是否有新 run，本次未查。

| run | 类型 | 对应的修复提交 |
|---|---|---|
| 42845871028 | lint failure | `b66863f` `EditRotation` 补 `RetouchLayer` import、`applySessionRotation` 去 `return@let`、`MainActivity` 补 `RotatedMask` import |
| 42846478701 | lint failure | `56da37e` `applySessionRotation` 去标签 + 补 import |
| 42847080783 | lint failure | `77f9146` 补 `RotatedMask` import |
| 42847732601 | unit test failure | `088981d` **全部 Gradle 调用加 `--no-build-cache`** |
| 42849787892 | unit test failure | `a06d24d` `mapAnchorToRender` 改返回 `FloatArray` |
| 42850816384 | lint failure | `1ccbcb7` `MainActivity` 调用适配 → `f578559` CRLF→LF 统一 |

（本地 `origin/main` 引用上另有 `7a20db7` → `42851362889` unit test failure；本次未 fetch。）

**根因**：连续 rebase 后 Gradle build cache 提供陈旧编译结果，产生「幻影 `Unresolved`」；
`core.edit ↔ core.edit.retouch` 的跨包双向依赖在 K1 增量编译下放大了它。
护栏：`088981d` 给 `assembleDebug` / `testDebugUnitTest` / `lintDebug` / `assembleRelease`
**4 处**全部加 `--no-build-cache`，配合 workflow 的缓存清剿步骤。

**Q5 手动旋转**（`d9914a9`）：会话级旋转从无损原图重推导、导出端烘焙、`RotatedMask` 装饰器、
锚点逆映射、旋转进撤销栈；单测 `EditRotationTest`(8) + `LinearImageRotateTest`(5)。
代码与单测已落地，**真机验收未闭环**（判据见 `DEV_PLAN.md` §10、`ENGINEERING_NOTES.md` §17）。

---

## 3. 当前发版状态（§5.2 四者不一致，待补）

| 项 | 当前值 |
|---|---|
| `app/build.gradle.kts` | `versionCode = 23` / `versionName = 0.7.1` |
| git tag | 最后为 **`v0.7.0`**；`git tag --points-at HEAD` 为空，`git describe` = `v0.7.0-15-gf578559` |
| `AboutSheet` | 读 `BuildConfig` ⇒ 显示 0.7.1（与代码一致） |
| 本文件 | 停在 **v0.7.0 / versionCode 22** |

⇒ **v0.7.1 未打 tag、未发 Release、无全绿 run 记录、CI 报告未覆盖**。
按 `ENGINEERING_NOTES.md` §5.2「四者不一致就是发版事故」，这是**待补的发版动作**，
不是功能缺陷。是否打 tag / 是否查远端 CI 由项目所有人决定。
另：本地 HEAD 与本地 `origin/main` 引用**各有 1 个独有提交（分叉）**。

---

## 4. 遗留（需真机 / 视觉 / 立项）

- **Q4** 热区 26/36/48dp 手感；**Q7** 首页渐变视觉对比；
- **M-2/T5** release 变体（R8 + 资源收缩、native 优化级别）真机性能基线；
- **TalkBack 走查**；v0.6.6 / v0.7.0 / **v0.7.1（Q5 旋转）** 真机验收
  （投屏通道可复用：`%TEMP%\pc_test\` 三个脚本）；
- ~~Q5 手动旋转（单独立项）~~ → **已于 v0.7.1 落地**（见 §2），仅余真机验收；
- 本文件待 v0.7.1 全绿后按 `ENGINEERING_NOTES.md` §5.4 覆盖重写。

---

*本报告只陈述仓库内可复核的事实；未编译、未跑测试、未装机、未查询远端 CI。*
