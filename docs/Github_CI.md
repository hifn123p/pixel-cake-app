# GitHub Actions CI 结果报告

> 生成时间：2026-11-08 · 关联提交：`2b93cfb`（versionName `0.6.6` / versionCode `21`）
> 本轮主题：**用户预设库（v0.6.6）** —— T1 序列化解锁的首个功能

## 结论：v0.6.6 已发布

| 提交 | run | 结论 | 内容 |
|---|---|---|---|
| **`be54544`** | 42476389892 | ❌ | **v0.6.6 主体**（CustomPresets 持久化层 + 我的预设页 + 相机/系留接入 + 8 断言单测） |
| `dc3a8e3` | 42476680847 | ❌ | 恢复失败日志 commit 步骤（`git add -f` 顶掉 gitignore）→ **拿到真实错误** |
| `fb95575` | （rebase 后并入） | — | **ParamPanel 补 remember import**（MyPresetsSection 是该文件第一个 remember 使用点，其余 4 错全是级联）|
| **`2b93cfb`** | **42479294925** | **✅ 4 job 全绿** | **assemble + 332 单测 + lint 全部通过** |
| **`v0.6.6` tag** | **42479968494** | **✅ 5 job 全绿** | **Publish GitHub Release** |

## 本轮教训（两类重复模式）

1. **新文件第一个使用点 = import 缺口**：ParamPanel 此前完全没有 remember 的使用，
   新增的组合函数一用它就 unresolved —— 且**同文件后续 4 个错误全是级联**（类型推断坍塌），
   真实根因只有 1 行。教训：新组合函数用到「本文件从未用过的 API」时，先查文件 import 区。
2. **失败日志 commit 机制常态化**：工件下载要认证，git 是唯一匿名可读通道。
   `git add -f` 必须带（日志文件已进 .gitignore）。机制本已在上轮收尾时移除，本轮证明
   「无日志的 CI 排红等于盲调」，永久恢复。

## Release

- 链接：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.6.6>
- versionCode `20` → `21`；versionName `0.6.5` → `0.6.6`
- 功能台账：`docs/ENGINEERING_NOTES.md` §15（保存语义 / 坏文件留证 / 接线面 / 8 断言单测）

## 遗留（需真机/视觉/立项）

Q4 热区手感 · Q5 手动旋转（EditSnapshot 契约扩展）· Q7 视觉对比 ·
M-2/T5 release 真机性能基线 · TalkBack 走查 · **用户预设库真机验收**（保存/应用/删除/相机批量复用）
