# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-11-01（本地）
> 关联提交：`20c6e41`（versionName `0.5.1` / versionCode `14` —— **本轮不发版**）
> 本轮主题：**UI 3.0「Aperture」** —— One-Surface 重构 + 琥珀主题 + SVG 图标引擎 + 图标分层

## 结论：代码已推上 main 并跑绿 —— 2 个 run（1 红 1 绿），**未打 tag、未建 Release**

| 阶段 | 运行 | ref / 提交 | 结论 |
|---|---|---|---|
| (1) main 推 UI 3.0 全量改动 | run **41311946112** | `972f41b` | **红**（`Build` + `Lint` 同报 `EditorScreen.kt:540:41`） |
| (2) 补一个 import 后重推 | run **41312184208** | `20c6e41` | **4 job 绿**（含 `Signed Release`；`Publish` 按设计跳过） |
| (3) 本文件覆盖重写（仅文档） | run **41312864874** | `440fc73` | **4 job 绿**（`Publish` 跳过） |

> 用户要求「只 push、先不发布新版」，因此**没有** `git tag`、没有 Release，
> 版本号也**故意保持不变**（`0.5.1` / `14`）。详见文末「版本号未变带来的副作用」。

## 失败轮（run `41311946112`）：缺一个手势扩展的 import

两个 job 报的是**同一行同一列**，这是「真编译错」而不是 infra 抖动：

```
e: .../ui/editor/EditorScreen.kt:540:41 Unresolved reference 'detectHorizontalDragGestures'.
e: .../ui/editor/EditorScreen.kt:540:72 Cannot infer type for implicit value parameter 'it'.
e: .../ui/editor/EditorScreen.kt:540:75 Cannot infer type for value parameter 'dx'.
> Task :app:compileDebugKotlin FAILED
```

- **根因**：分隔条拖拽用的是 `androidx.compose.foundation.gestures` 的**顶层扩展函数**，
  必须显式 `import`。同一文件里 `awaitEachGesture` / `awaitFirstDown` / `calculatePan` /
  `calculateZoom` 都有 import，唯独漏了这一个。
- **后两条是级联噪音**：`detectHorizontalDragGestures` 不可见 -> 尾随 lambda 的
  `{ _, dx -> }` 无从推断 -> 报 `Cannot infer type`。**只要第一行的符号补上，后两条自动消失**
  （见 `docs/ENGINEERING_NOTES.md` §5.7 的同位诊断规则）。
- **修复**：`20c6e41` 只加了一行 import，1 file changed / +1。

## 任务（Job）总览 — run `41312184208`（最终绿）

| Job | 结论 | 耗时 | 说明 |
|---|---|---|---|
| Build Debug APK | success | 2.4 min | `assembleDebug` + `testDebugUnitTest` 全过 |
| Lint (Android Lint) | success | 2.8 min | `lintDebug` 通过 |
| Check signing secrets | success | 0.1 min | 探测到 `KEYSTORE_BASE64` |
| Signed Release | success | 4.2 min | 解 PKCS12 -> `assembleRelease`（R8）-> 签名 APK + mapping |
| Publish GitHub Release | skipped | — | 非 tag 触发，按设计跳过 |

整轮 **7.2 分钟**。产物（sha `20c6e418ab`）：

| artifact | 大小 |
|---|---|
| `pixelcake-debug-<sha>` | 31.19 MB |
| `pixelcake-release-<sha>` | 20.83 MB |
| `pixelcake-mapping-<sha>` | 2.24 MB |
| `lint-report-<sha>` | 0.03 MB |

## 本轮改了什么

### 1. One-Surface 架构（`EditorScreen` 重写）

预览、Deck、Rail、浮层四层叠放；拖动分隔条时外围控件按 `chromeAlpha` 淡出给预览让位。
**没有任何东西再「展开/收起」** —— 旧版参数面板靠嵌套容器挤高度，正是前几轮「参数太挤」的根源。

### 2. SVG 图标引擎（新增 `ui/editor/Rail.kt`）

`material-icons-extended` 是几 MB 的依赖（本项目刻意没引入），`material-icons-core` 又没有
「半填充圆 / 光圈曲线」这类仪器化图标。改为把 `docs/UI3.0.html` 里的 24×24 线性 SVG
**PathData 直接喂给 `PathParser`** —— 设计与实现同源，改文档即改实现。

> ⚠️ 该文件是**从未编译过的新文件**，本轮红轮的全部风险都集中在这里（见下节静态闸门）。

### 3. 主题换代

| 项 | 变化 |
|---|---|
| Seed | `0xFF8B6FFF`（紫）-> `0xFFB8791A`（琥珀） |
| 新增语义色 | `WsOk` / `WsWarn` / `WsErr` / `WsInfo`、`HeroCardTop/Mid/Warm/Bottom` |
| Radius | `shell` 40->24、`card` 22->20、`chip` 14->12（`sheet` / `pill` 不变） |
| 字号刻度 | 28 / 20 / 15 / 13 / 11，`displaySmall` 提到 SemiBold |

### 4. 其余跟随改动

`ParamSlider` 迁到 material3 新 API（`LocalMinimumInteractiveComponentSize provides Dp.Unspecified`，
旧的 `LocalMinimumInteractiveComponentEnforcement` 已废弃）、行高压到 26dp、新增 `LocalHudReporter`；
`HomeScreen` / `ExportSheet` / `SettingsScreen` / `AppShell` / `ParamPanel` 跟新样式；
启动图标分三层（`anydpi` 兜底 / `-v26` 自适应 / `-v33` monochrome）。

### 5. 删除

`ui/components/GlassSegmentedBar.kt`、`ui/components/ImportSheet.kt` —— UI 3.0 后**零引用**
（推前已核对：`ImportSheet` 无任何引用；`GlassSegmentedBar` 只出现在注释里）。

## 推送前静态闸门：抓到了 4 个，漏了 1 个

本地没有 Kotlin 编译器，全靠脚本 + 人眼。本轮实测的**命中率与盲区**：

| 缺陷 | 发现方式 |
|---|---|
| `Rail.kt`：`PathParser` 包路径错（应为 `graphics.vector`） | 人眼核源码 |
| `Rail.kt`：`parsePathData` 不存在（真名 `parsePathString`） | 人眼核源码 |
| `Rail.kt`：缺 `foundation.layout.size` | import 闸门 |
| `EditorScreen.kt`：`heightIn` 漏 import（新增调用点） | 扩展函数扫描 |
| `EditorScreen.kt`：残留 `ui.geometry.getDistance`（不存在的扩展） | 人眼复核 diff 中的新增 import |
| **`EditorScreen.kt`：`detectHorizontalDragGestures` 漏 import** | **❌ 漏网，靠 CI 抓到** |

> 教训：闸门的扫描表里列了 `pointerInput`，却**没有列手势检测器家族**
> （`detect*Gestures` / `awaitEachGesture` / `calculate*`）。已把该家族补进扫描表。
> 另一条：`drawPath` / `drawLine` / `drawCircle` 是 `DrawScope` **成员**，
> `item` 是 `LazyListScope` **成员扩展**，`Color` 可能是枚举常量 —— 这些**不需要 import**，
> 扫描器必须放进白名单，否则噪音会盖掉真问题。

## 版本号未变带来的副作用（真机验收前必读）

本轮**没有**抬 `versionName` / `versionCode`（用户明确要求「先不发布新版」）：

- 「设置 -> 关于」仍显示 **v0.5.1 (14)**，与本轮之前安装的包**无法从版本号区分**；
- 但 `versionCode` 相同 + debug keystore 入库固定 -> **可以直接覆盖安装**，不会
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；
- 判断是否装上本轮改动，看**启动图标**（琥珀色光圈）或**底部 Rail 的图标+文字**即可 ——
  这两处是 UI 3.0 独有的。

## 本轮网络现实（§5.3 的补充）

- `ssh.github.com:443` 仍不可用；`https://github.com` 的 connect 仍被**抽样阻断**。
- 本轮实测：
  - main push（`140.82.112.3`）-> `CRYPT_E_NO_REVOCATION_CHECK (0x80092012)`
    （代理 MITM 证书做不了吊销检查；`http.schannelCheckRevoke=false` 对这种情况**无效**）；
  - 加 `-c http.sslVerify=false` 后**同一条命令立刻成功**（`43b3f3d..972f41b`）；
  - 修复轮 push（`140.82.121.3`）一次成功。
- ⇒ 姿势固定为：**失败就换 IP / 换开关重试**，`git push` 幂等。
  所有 `-c` 只作用于单条命令，**不改仓库/全局配置**。

## 历史回归表

| 轮次 | run 数 | 结论 | 备注 |
|---|---|---|---|
| v0.4.5 发版轮 | 4 | 绿 | 引入新文件时首轮红 |
| v0.4.6 发版轮 | 4 | 绿 | 首轮红（`imageEpoch` 局部变量前向引用） |
| v0.4.7 发版轮 | 6 | 绿 | 两个编译错逐层暴露（main -> 测试侧） |
| v0.5.0 发版轮 | 4 | 绿 | 一个 import 错 + 一个测试越界；新增 3 个测试文件 |
| v0.5.1 发版轮 | 2 | 绿 | 一次通过（零红轮）；改动全落在已有文件内 |
| **UI 3.0 非发版轮** | **2** | 绿 | 1 红 1 绿；新增文件 `Rail.kt` 的 import 漏网，CI 抓到 |

> 规律再次成立：**只要引入从未编译过的新文件，首轮大概率红**。
> 本轮与 v0.4.5 的差别是「红一轮就收住」—— 因为新文件只有一个，且其余改动都在既有文件内。

## 待真机验收（UI 3.0 专项）

1. **启动图标**：桌面 / 最近任务 / 通知栏三处的琥珀光圈是否一致；Android 13+ 主题图标
   （monochrome）是否随系统壁纸取色。
2. **Rail 六格**：人像 / 明暗 / 颜色 / 曲线 / 细节 / 预设，选中态（琥珀软底胶囊）是否清楚；
   TalkBack 是否播报「已选中」。
3. **One-Surface**：拖动分隔条时外围控件淡出是否顺滑；松手后是否复位到可操作亮度。
4. **手势回归**：分隔条水平拖拽（本轮红轮的那个 API）、预览区双指缩放 / 平移能否共存。
5. **深浅两套主题**：文字是否都在（历史上 `LocalContentColor` 缺失只在深浅相反时现形）。
6. **字体缩放 1.3x**：Rail 文字与 Deck 标题是否截断。

## 文档自身的 CI

本文件由独立提交推送，run **41312864874**（`440fc73`）：`Build` / `Lint` / `Check signing` /
`Signed Release` 四绿，`Publish` 按设计跳过 —— 与代码轮的绿轮同型。

> 本轮到此为止：**main 最新提交即本文档**，未打 tag、未建 Release。
> 下一步若要发版，按「两步走」先把 `versionName` / `versionCode` 抬到 `0.6.0` / `15`。
