# GitHub Actions CI 结果报告

> 生成时间：2026-11-08 · 关联提交：`e794781`（versionName `0.7.0` / versionCode `22`）
> 本轮主题：**架构与诊断（v0.7.0）** —— 导航层 / 模型中心 / 预设分享

## 结论：v0.7.0 已发布

| 提交 | run | 结论 | 内容 |
|---|---|---|---|
| **`714a31a`** | 42483266937 | ❌ | **v0.7.0 主体**（NavStack/模型中心/预设导入导出/.gitmodules 钉子）—— 4 组编译错 |
| `de2608e`+`dc3a8e3` | 42486690047 / 42476680847 | ❌ | 恢复/加固失败日志 commit 机制（`git add -f`）→ 实锤 |
| **`8c40d70`** | 42485590146 | ❌ | **修 7 组错**（NavStack 显式泛型等）—— 但 PresetParams 签名漏 onImport 仍在 |
| **`024ff8c`** | 42486291177 | ❌ | **PresetParams 签名补 onImport** —— 只剩 Call B 一处（见下） |
| `ae9cd48` | 42487125453 | ❌ | 摆正 Call A（ParamPanel→PresetParams 传外层名）—— Call B 反向被污染 |
| `4abc2ba` | 42487332764 | ❌ | Call B 补 onImport —— **只差最后一步** |
| `8351564` | 42487878831 | ❌ | NavStackTest 显式泛型 + 缓存清剿步骤 |
| **`e794781`** | **42489581102** | **✅ 4 job 全绿** | **withFreshIds 测试逻辑修正 + 清理树内陈旧日志 —— 全部通过（338 单测）** |
| **`v0.7.0` tag** | **42490180023** | **✅ 5 job 全绿** | **Publish GitHub Release** |

## 本轮教训（两条新纪律）

1. **「链条式接线」必须在收尾时整体核对**：onImport 从 UI 到存储要穿过 5 个签名
   （MyPresetsSection → PresetParams → ParamPanel → EditorScreen → MainActivity），
   多轮编辑后两处调用点的参数名**交叉污染**（Call A 传了内层名、Call B 引用了外层名），
   而逐个修复时又把两块互相写反 —— 收尾时应该用一次整体 grep 验证调用链两端。
2. **CI 日志文件会作为「陈旧证据」留在树里**：ci-log 提交的 assemble/lint/unit-tests.log
   在后续 run 里不会被更新（每个 job 只 tee 自己的那个），误读它们会得出与树不符的
   错误行号。本轮「幻影错误」实为读到了 7e34d22 时代的旧日志。已清理树内日志 + gitignore。

## Release

- 链接：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.7.0>
- versionCode `21` → `22`；versionName `0.6.6` → `0.7.0`
- 功能台账：`docs/ENGINEERING_NOTES.md` §15（v0.6.6）/ §16 待补（v0.7.0）

## 遗留（需真机/视觉/立项）

Q4 热区手感 · Q5 手动旋转（EditorScreen 手势几何手术，单独立项）· Q7 视觉对比 ·
M-2/T5 release 真机性能基线 · TalkBack 走查 · v0.6.6/v0.7.0 真机验收
（投屏通道可复用：`%TEMP%\pc_test\` 三个脚本）
