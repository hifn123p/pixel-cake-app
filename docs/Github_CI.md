# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-11-02（本地）
> 关联提交：`aaf0a34`（versionName `0.5.1` / versionCode `14` —— **本轮不发版**）
> 本轮主题：**UI 3.0 第二批** —— 曲线本体 / 历史丝带 / 导出回执 / 自适应亮档 / 模型状态

## 结论：2 个 run（1 红 1 绿），main 已绿；**未打 tag、未建 Release**

| 阶段 | 运行 | ref / 提交 | 结论 |
|---|---|---|---|
| (1) main 推 UI 3.0 第二批 | run **41459602649** | `50929ef` | **红**（`EditorScreen.kt:352:33`） |
| (2) 把计算块移到声明之后 | run **41460439627** | `aaf0a34` | **4 job 绿**（含 `Signed Release`；`Publish` 按设计跳过） |

## 失败轮（run `41459602649`）：局部变量前向引用

`Build` 与 `Lint` 报的是同一批 `e:` 行 —— 真编译错，不是 infra 抖动：

```
e: .../ui/editor/EditorScreen.kt:352:33 Unresolved reference 'histogram'.
e: .../ui/editor/EditorScreen.kt:353:17 Unresolved reference 'histogram'.
e: .../ui/editor/EditorScreen.kt:354:34 Function invocation 'size(...)' expected.
e: .../ui/editor/EditorScreen.kt:355:18 'operator' modifier is required on 'fun <T> Comparable<T>.compareTo(other: T): Int'.
e: .../ui/editor/EditorScreen.kt:359:22 Cannot infer type for type parameter 'V'. Specify it explicitly.
e: .../ui/editor/EditorScreen.kt:359:61 Unresolved reference 'div' for operator '/'.
> Task :app:compileDebugKotlin FAILED
```

- **根因**：Kotlin **局部变量按声明顺序可见**。`histogram` 由 `produceState` 在第 389 行声明，
  而新加的 `lumaCentroid` 写在第 352 行 —— 提前引用即 `Unresolved reference`。
- **后四条全是级联噪音**：`h` 的类型推不出来 -> `h.first.size` 无从解析（报成「`size(...)` 调用」）
  -> `bins < 2` 找不到 `compareTo` -> `/` 找不到 `div`。**只要把声明搬对，全部消失**。
- **修复**：`aaf0a34` 把 `lumaCentroid` 与 `LaunchedEffect` 整体搬到 `produceState` 之后，
  1 file changed / +28 −23（含注释）。

> 这与 v0.4.6 的 `imageEpoch` 是**同一类错误**（那次在 `MainActivity`）。见
> `docs/ENGINEERING_NOTES.md` §5.7。

## 任务（Job）总览 — run `41460439627`（最终绿）

| Job | 结论 | 耗时 | 说明 |
|---|---|---|---|
| Check signing secrets | success | 0.1 min | 探测到 `KEYSTORE_BASE64` |
| Build Debug APK | success | 3.0 min | `assembleDebug` + `testDebugUnitTest` 全过 |
| Lint (Android Lint) | success | 3.1 min | `lintDebug` 通过 |
| Signed Release | success | 4.3 min | 解 PKCS12 -> `assembleRelease`（R8）-> 签名 APK + mapping |
| Publish GitHub Release | skipped | — | 非 tag 触发，按设计跳过 |

整轮 **7.7 分钟**。

## 本轮改了什么

### 1. 曲线编辑器（新增 `ui/components/CurveEditor.kt`）

曲线页此前只有「黑场 / 中间调 / 白场」三个数字滑块，**没有曲线本体** —— 用户是在盲调。

- 画的是**分段线性折线**，与 `ColorMath.buildLumaLut` 完全同构。若画样条平滑曲线，
  屏幕形状就不等于引擎真正生效的映射 —— **那比没有图更坏**。
- 锚点 **x 不可拖**：`ToneCurve` 的三个锚点 x 固定为 0 / 128 / 255，允许横拖会造出引擎
  表达不了的状态；每档 y 各有合法区间，拖出即 clamp（防止「白场压到黑场以下」）。
- 手势用 `rememberUpdatedState` 持有最新锚点：`pointerInput(Unit)` 只在首次组合建闭包，
  直接读参数会让「拖动每次都从原值起算」；而 `pointerInput(points)` 又会每帧重启手势。

### 2. 自适应亮档（UI3.0 §H）

照片本身很亮时深色 chrome 压不住画面，规范要求切纸色（`.litchrome`）。

- 判据：直方图的**亮度重心**。`Histogram.normalize` 是乘性缩放（除以峰值），不改变各 bin 的
  相对权重，所以归一化数据照样反映整体偏亮/偏暗 —— 不必再扫一遍像素。
- **双阈值滞回（0.66 / 0.58）**：单阈值会让临界亮度照片每帧横跳，整条 chrome 闪个不停。
- `ChromeSkin` 同时 provide `LocalDarkTheme = false` 与 `LocalContentColor`。
  **漏后者会让文字消失**（`MaterialTheme` 不下发 ContentColor），见 §5.7 的历史坑。

### 3. 历史丝带（UI3.0 §09）

- 列出 `0..depth` 每一步，点任意一步 `onSeekHistory(i)` 跳回；重做方向的步骤也在列表里。
- **跳步后必须 `applySnapshot(history.current)`**：`seekTo` 只搬栈、不碰界面持有的
  params/retouch/layers，漏这一步就是「栈动了、界面没动」的永久漂移。
- 「重置」从顶栏移走，改由列表里的「起始（未编辑）」承担 —— 比 `history.reset()`
  清空整个栈更好，因为往回走是**可逆**的。`onReset` 参数保留未删（它还负责清运行时蒙版，
  与「恢复快照」不等价）。

### 4. 导出回执（UI3.0 §I）

已导出卡：格式/质量、**全分辨率**尺寸（不是预览尺寸）、预设、分享（`ACTION_SEND` +
`EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION`，三者缺一对方就拿到「无法访问」）与完成。

### 5. 无障碍与模型状态

- `IconAction` 补 `semantics { contentDescription = label }`：`label` 从 v0.4 起一直传进来却
  **从未被使用**，顶栏图标对读屏是无声的。
- 设置页内联「端侧模型」两行：把「未加载」与「OOM 熔断」区分开 —— 两者画面表现一样，
  用户只会判定「功能坏了」。`MlMaskProvider` 只新增只读访问器，字段仍不可外部写。

## 静态闸门：本轮漏 1 处，并修好了闸门自身的 bug

| 缺陷 | 发现方式 |
|---|---|
| `CurveEditor.kt` 的 `ToneCurve` API、区间类型（`0f..48f` 才能 `.roundToInt()`） | 人眼核源码 |
| `heightIn` / `rememberUpdatedState` 等 import | import 闸门 |
| 括号/注释/字符串配平 | 状态机扫描 |
| **`histogram` 局部变量前向引用** | **❌ 漏网，靠 CI 抓到** |

**为什么漏**：既有 import 闸门只管「名字有没有 import」，不管「声明顺序」。补做了
`gate_forward.py`（前向引用启发式），但**第一版有 bug** —— 块注释开头 `/*` 被重复计数
（depth 1→2），于是从第一个 KDoc 起整份文件被当成注释吞掉，输出「无前向引用」。

**验证方式（值得保留）**：用 `git show <红轮 commit>:<file>` 导出坏版本喂给闸门 ——
必须报出那处错误，否则闸门不可信。修好后旧版报「352 引用 histogram，408 才声明」，
修复版干净。全仓扫余下的告警均为 lambda 形参 / `const val` 造成的误报。

> 教训：**新写的检查工具必须用「已知的坏输入」反向验证**，否则它只提供虚假安全感。

## 历史回归表

| 轮次 | run 数 | 结论 | 备注 |
|---|---|---|---|
| v0.4.5 发版轮 | 4 | 绿 | 引入新文件时首轮红 |
| v0.4.6 发版轮 | 4 | 绿 | 首轮红（`imageEpoch` 局部变量前向引用） |
| v0.4.7 发版轮 | 6 | 绿 | 两个编译错逐层暴露（main -> 测试侧） |
| v0.5.0 发版轮 | 4 | 绿 | 一个 import 错 + 一个测试越界 |
| v0.5.1 发版轮 | 2 | 绿 | 一次通过（零红轮） |
| UI 3.0 第一批（非发版） | 2 | 绿 | 1 红 1 绿；`detectHorizontalDragGestures` 漏 import |
| **UI 3.0 第二批（非发版）** | **2** | 绿 | 1 红 1 绿；`histogram` 局部变量前向引用 |

> 两次都是「红一轮就收住」：改动虽然几百行，但**只有一处真错**，且都不在新文件里
> —— 新文件（`Rail.kt` / `CurveEditor.kt`）这次反而是干净的。

## 本轮网络现实

三次网络操作（`5dd41f9..50929ef`、`50929ef..aaf0a34` 及两次 fetch）全部**一次成功**，
统一用 `-c http.sslVerify=false -c http.curloptResolve=github.com:443:140.82.121.3`。
`CRYPT_E_NO_REVOCATION_CHECK`（代理 MITM 证书做不了吊销检查）依旧存在，
`http.schannelCheckRevoke=false` 对它无效；`sslVerify=false` 只作用于单条命令，
**不改任何持久化配置**。

## 待真机验收

1. **曲线页**：三个锚点能否拖动；拖到区间边界是否被卡住（不会越过黑场/白场）；
   松手后下方三个数字是否同步；曲线形状是否**直角折线**（不是平滑曲线）。
2. **历史丝带**：顶栏「历史」入口；跳步后画面是否真的跟着回退（不是只改了文字）；
   撤销三步后那三步是否仍在列表里可点回。
3. **导出回执**：导出成功是否立刻弹卡（不留空窗）；「分享」能否拉起系统 chooser 并成功发送；
   「完成」与点遮罩是否都能关掉。
4. **自适应亮档**：拍一张过曝/白墙的照片，chrome 是否切纸色且文字仍清晰；
   照片变暗时是否切回深色（**两种状态都不要闪**）。
5. **读屏**：顶栏 7 个图标是否都能报出名称（返回/撤销/重做/历史/对比原图/分割对比/导出）。
6. **设置 → AI 与性能**：模型状态与概率缓存两行是否有值；「刷新」按钮是否生效。
7. 回归：编辑器整体滚动、滑块拖动、分割对比叠加（本轮新增两层全屏遮罩，注意别挡住手势）。
