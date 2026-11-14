# GitHub Actions CI 结果报告

> **当前状态（2026-11-14 核对）**：**v0.7.1 收尾轮已全绿**。
> 代码基线 HEAD `33dc523`，`versionName 0.7.1` / `versionCode 23`，工作区干净。
> 本文件已按 `ENGINEERING_NOTES.md` §5.4 **覆盖重写**：只描述本轮**实际查询到的 run**
> 与仓库内可复核的事实。v0.7.0 及更早的内容降级为 §6 的历史回归表，
> 不再作为「当前 CI 状态」引用。

---

## 1. 结论

| 项 | 值 |
|---|---|
| 本轮绿 run | **`43604114311`**（`33dc523`）—— **4 job 全绿**，`352 tests completed` 全过 |
| 本轮红 run | **`43603642492`**（`f81a2dc`）—— `Build Debug APK` 失败，**单测 1 项断言不成立** |
| 编译 | ✅ 通过。红轮也是「代码能编译、只是断言写错」——同轮 `Lint` job 是绿的 |
| 单测 | ✅ 352 / 352（50 个测试 `.kt`，本地静态统计与 CI 输出一致） |
| tag / Release | **未打 tag、未建 Release** —— 本轮无发版请求，`versionName`/`versionCode` 故意未动 |
| 产物 | debug APK **31.53 MB** / release APK **21.21 MB** / mapping **2.92 MB** |

红 1 绿 1，共 **2 个 run**。符合「未引入全新文件时，红一轮后收敛」的常态。

---

## 2. 本轮 run 明细

| # | 提交 | run | 时长 | Check signing | Build Debug APK | Lint | Signed Release | Publish | 结论 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `f81a2dc` | 43603642492 | 2.5 min | ✅ 0.1 min | ❌ **2.5 min** | ✅ 2.1 min | ⏭ skipped | ⏭ skipped | ❌ |
| 2 | `33dc523` | 43604114311 | 6.1 min | ✅ 0.0 min | ✅ 2.8 min | ✅ 2.4 min | ✅ 3.2 min | ⏭ skipped | ✅ |
| 3 | `docs` 轮 | 见 §2.1 | — | — | — | — | — | — | 见下 |

产物对照：

| run | `tests` | `report`(lint) | debug APK | mapping | release APK |
|---|---|---|---|---|---|
| 43603642492 | 0.0 MB | 0.04 MB | — （失败） | — | — |
| 43604114311 | — | 0.04 MB | **31.53 MB** | **2.92 MB** | **21.21 MB** |

> 非 tag 推送时 `Signed Release` **仍然会跑**（约 3~4 分钟），`Publish` 一律 skipped。
> 所以「4 job 全绿」是本项目的常态绿灯口径，5 job 全绿只在 tag 轮出现。

### 2.1 docs 轮

本文件自身也会被推送触发一轮 CI（`git add -A` 全量提交的必然结果）。
该 run 的编号与结论以推送后的回填为准，**不在此处预写**——避免把未发生的 run 写成事实。

---

## 3. 红轮根因：**单测期望值写错，实现是对的**

> run `43603642492` · `f81a2dc` · 2.5 min

### 3.1 报错原文

```
EditRotationTest > mapAnchorToRender_routesIntoUnrotatedFrame FAILED
    java.lang.AssertionError: expected:<49.0> but was:<0.0>
        at .../EditRotationTest.kt:80
352 tests completed, 1 failed
```

关键判据：**`Lint` job 同轮成功**。如果实现里真有问题（符号缺失、类型不匹配），
`Lint` 的 `compileDebugKotlin` 会一起红。这里只有单测挂 ⇒ **代码能编译，是断言与实现对不上**。

### 3.2 谁错了：实现没错

裁定依据不是「让代码去迁就测试」，而是同文件里**当时已通过**的兄弟用例
`rotatedMask_mapsSampleCoords`——它独立钉死了「未旋转渲染 `(0,0)` ↔ 旋转后 `(49,0)`」，
即 `mapMaskPixel` 在 q=1 时是 `(px, py) → (h-1-py, px)`。

把这条取逆：

```
旋转后 (rx, ry) → 未旋转 (ry, h-1-rx)
```

代入 `(49, 0)`、`h = 50`：

```
(ry, h-1-rx) = (0, 50-1-49) = (0, 0)
```

而旧断言写的是 `49`，它的注释算术是 `49-1-0 = 49`——**把 `rx` 代到了 `h-1-py` 的位置，
还把 `49` 当成了宽**。两处都错，所以算出一个符合「看起来像逆变换」的数字。

### 3.3 修复（`33dc523`）

改成**两个角各验一次**，并且把口径来源写进注释：

- 左上 `(0,0)` → `(0, 49)`
- 右上 `(49,0)` → `(0, 0)`

只测一个角时，x/y 写反也可能凑巧通过；两个角能同时钉住方向。

### 3.4 附带核对

- `MainActivity:1099` 调用 `mapAnchorToRender(..., rotationQuarterTurns, ewU, ehU)`，
  传的是**未旋转导出尺寸**，与实现内部的坐标系一致 —— 调用点无需改。
- `EditRotation.mapAnchorToRender` 的 q=1/2/3 三支实现本身保持原样，未动。

### 3.5 可复用的判定套路

> **单测红 + Lint 绿 ⇒ 先怀疑断言，别急着改实现。**
> 找一个**同时通过**的兄弟用例当锚点，用它反推正确口径，再看新断言是不是把坐标轴/尺寸搞混了。
> 直接改实现去迁就新断言，会把已经正确的行为改坏，而且下一个红灯会更难判。

---

## 4. 本轮新纪律：CI 会自己往 `main` 推 `ci-log` 提交

红轮结束后，workflow 会把 `unit-tests.log` / `assemble.log` 等日志 **自动 commit 并 push 到 `main`**：

- `7a20db7` `ci-log: unit test failure (run 42851362889)`
- `c694b7c` `ci-log: unit test failure (run 43603642492)`

⇒ 红轮之后你在本地紧接推送，**必然 non-fast-forward 被拒**。这不是网络问题，别去换 IP 重试。

正确处理（保持线性历史、不改写已推送提交）：

```bash
# 1) 先把远端的自动提交取下来
git fetch https://github.com/hifn123p/pixel-cake-app.git main:refs/remotes/origin/main

# 2) ⚠️ 先确认远端提交只碰了日志文件，与本地改动无重叠
git show --stat c694b7c

# 3) 把本地未推送提交重放到远端之上（线性，不产生 merge 气泡）
git rebase refs/remotes/origin/main

# 4) 再推
git push https://github.com/hifn123p/pixel-cake-app.git main
```

**⚠️ 不要用 `push --force`。** rebase 若报冲突，说明远端这次动到了代码文件——
停下来看冲突内容，改用 merge 或人工合并，别硬来。

另：每次 `docs` 轮、`ci-log` 轮都会再加一个 run，所以「一轮完整收尾」通常
= 代码轮 + docs 轮（+ 红轮的 `ci-log` 轮）。

---

## 5. 发版状态（四者一致性）

| 项 | 当前值 |
|---|---|
| `app/build.gradle.kts` | `versionCode = 23` / `versionName = "0.7.1"` |
| git tag | 最后为 **`v0.7.0`**；`git tag --points-at HEAD` 为空；`git describe` = `v0.7.0-19-g33dc523` |
| `AboutSheet` | 读 `BuildConfig` ⇒ 显示 `v0.7.1 (23)`，与代码一致 |
| 本文件 | 已更新到 v0.7.1 轮（`versionCode 23`） |

⇒ **代码与本文件一致；`v0.7.1` tag 尚未创建**。
按 `ENGINEERING_NOTES.md` §5.2「四者不一致就是发版事故」，这是**待补的发版动作**，
不是功能缺陷。是否打 tag 由项目所有人决定。

发版时的两步走（勿合并为一步）：

1. 抬 `versionName`/`versionCode` → 推 `main` → 等 main 绿；
2. 在**已验证的那个 commit** 上 `git tag -a vX.Y.Z` → `git push origin vX.Y.Z` → 触发 `Publish`。

---

## 6. 历史回归表

> 只列有可复核 run 编号的轮次；v0.6.x 的 run 编号散在各自 tag 提交里，本文件未收录。

| 版本 | 关键 run | 轮数 | 备注 |
|---|---|---|---|
| v0.4.2 | 36663981140 | — | |
| v0.4.3 | 36814801731 | — | |
| v0.4.4 | 37193562293 | — | |
| v0.4.5 | 37204595671 | 4 | main 连红三轮 |
| v0.4.6 | 37443544216 | 3 | 局部变量前向引用被解析成同名类型 |
| v0.4.7 | — | 6 | 引入从未编译过的新文件 |
| v0.5.0 | 40190769073 | 4 | main 连红两轮 |
| v0.5.1 | 41081158258 | 2 | 零红轮：main 4 绿 → tag 5 绿 |
| v0.6.0 ~ v0.6.6 | 见各 tag 提交 | — | v0.6.6 = 用户预设库 |
| v0.7.0 | 42490180023（tag） | 9 | 8 红 → 42489581102 绿 → tag 5 绿（当时 338 单测） |
| **v0.7.1（本轮，未发版）** | **43604114311** | **2** | 43603642492 红（单测断言错）→ 绿（352 单测） |

---

## 7. 遗留（需真机 / 视觉 / 立项）

- **Q4** 热区 26/36/48dp 手感；**Q7** 首页渐变视觉对比；
- **M-2/T5** release 变体（R8 + 资源收缩、native 优化级别）真机性能基线；
- **TalkBack 走查**；v0.6.6 / v0.7.0 / **v0.7.1（Q5 手动旋转）** 真机验收；
- **Q5 手动旋转**：代码与单测已落地（`EditRotationTest` 8 例 + `LinearImageRotateTest` 5 例），
  判据见 `DEV_PLAN.md` §10、`ENGINEERING_NOTES.md` §17，**仅余真机验收**；
- **`ObjectMasks.scopeAt` / `previewGestures(onPick)` 已入库但未接线**：
  调用点没传 `onPick`，功能是惰性的；接线时 `pointerInput` 的 key 里没有 `onPick`，
  直接捕获会拿到首次组合的旧值（同 `CurveEditor` 的坑），需配 `rememberUpdatedState`。

---

*本报告 §1~§6 的 run 数据来自本轮实际查询；§7 为仓库内可复核的静态事实。未装机、未做视觉验收。*
