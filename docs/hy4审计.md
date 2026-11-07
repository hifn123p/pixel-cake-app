# PixelCake 全量审计与 `qwen3.8审计.md` 核对报告（hy4）

> 对象：`hifn123p/pixel-cake-app` 本地工作树 @ `8784274`（`versionName 0.6.3` / `versionCode 18`）
> 日期：2026-11-06 · 视角：App 架构师 / 开发者 · 性质：**只读审计，零代码改动、零 push、零 CI 触发**
> 输出：`docs/hy4审计.md`（新建，当前为未跟踪文件；是否入库由项目所有人决定）

---

## 0. 执行口径、方法与边界

### 0.1 关于「清空上下文和记忆」

本次**不引用本次会话此前任何报告、结论或记忆**（包括前三轮 UI 设计稿、9 月的审计报告、工作区 `.workbuddy/memory/`），所有结论从仓库重新取证。
**没有删除任何文件**：删除本地记忆或历史报告属于不可逆操作，需单独明确授权，故未执行。这不影响审计独立性。

### 0.2 基线事实（git 实测）

| 项 | 值 | 证据 |
|---|---|---|
| 分支 / HEAD | `main` @ `8784274`（2026-11-06 01:04） | `git log -1` |
| HEAD 提交内容 | docs 清理（删 `ui_preview.html`）+ README 全量更新 + CI 报告覆盖 | `git log -1 --format` |
| 父提交 / v0.6.3 tag | `548792c`，tag `v0.6.3` 指向它，且是 HEAD 的祖先 | `git rev-list -n 1 v0.6.3`、`merge-base --is-ancestor` |
| 源码规模 | 主源码 **87 个 `.kt` / 19,765 行**；测试 **39 个 `.kt` / 5,416 行 / 294 个 `@Test`**；`androidTest` **0** | `git ls-files` + `wc -l` + `git grep -c @Test` |
| 文档 | 9 份 `.md`（`README` + 8 份 `docs/*.md`）+ 2 份 HTML 规范 + `app_icon.svg` | `git ls-files` |
| tag 数 | 18 | `git tag \| wc -l` |
| 子模块 | LibRaw `dde798dd` / LibRaw-cmake `eb98e432`，**本机均未 checkout**（`-` 前缀） | `git submodule status` |
| 工作树 | 2 个未跟踪文件：`_cite_check.txt`、`docs/qwen3.8审计.md`；**无已跟踪文件改动** | `git status --porcelain -uall` |
| remote | `git@github.com:hifn123p/pixel-cake-app.git`（**SSH**） | `git remote -v` |
| 本地 `origin/main` 引用 | 停在 `46dc90c`，比 HEAD 少 14 个提交；`.git/FETCH_HEAD` 为 0 字节 | `git log origin/main..HEAD \| wc -l` |

⚠️ **一条必须说清的不确定性**：本地 `origin/main` 引用停在旧位置，**不能据此断言「14 个提交未推送」**。
理由是 `v0.6.3` tag 与 `docs/Github_CI.md` 记录的 tag run `42069327363` 表明 `548792c` 及之前确已在 GitHub 上构建并发布；更可能是本地 remote 引用长期未同步（remote 配的是 SSH，而 `ENGINEERING_NOTES.md:377` 自陈「SSH 通道本机已完全不可用、push/fetch 一律走 HTTPS」——**remote 配置与该自陈直接冲突**）。
按项目规则（「我通知你是否上传、是否检查 CI 结果」），本轮**未 fetch、未查询远端 CI**。
⇒ 可确认的只有：`docs/Github_CI.md` 记录到 `548792c` 的 v0.6.3 tag run 全绿，**HEAD `8784274`（docs 清理提交）的 CI 结果没有登记**；当前 HEAD 没有本次有效的 CI 证据。

### 0.3 方法（四轮）

1. **全量只读发现**：git 状态/提交/tag/子模块 + 全部 9 份 md + 2 份 HTML 规范（约 7,000 行，短文档自读、长文档由 3 个独立子代理分组精读并交叉对照）。
2. **分组复核 qwen 论断**：3 个子代理分别精读「相机/系留」「渲染/内存/并发」「UI/架构/文档」代码组，逐条判定成立与否，每条要求看前后 ≥15 行。
3. **亲自回读反证**：对争议条目与核心 P0/P1，本人逐段 Read 关键函数 ≥15 行上下文（清单见附录 A），并对 `produceState` 的换键语义查了 AndroidX 官方源码。
4. **数字自洽 / 安全面 / 文档声称差距**：重新统计规模数字、单位口径、零命中类断言、敏感信息、文档版本与悬空引用。

### 0.4 证据等级

| 标 | 含义 |
|---|---|
| 【一手】 | 本人亲自 Read 目标行前后上下文，机制能复述 |
| 【复核】 | 子代理精读 + 本人抽查关键行/函数 |
| 【源码】 | AndroidX 官方实现（外部一手源码） |
| 【git】 | git 命令实测 |
| 【文档】 | 文档自陈，未独立验证 |
| 【推断】 | 由代码路径推导，**运行时结论需真机验证** |

### 0.5 边界（与 qwen 相同，必须声明）

本机无 Android 构建环境：**未编译、未跑测试、未装机**。所有内存数字是按代码常量折算的量级估算；所有「用户会看到 X」是从代码路径推导，不是观察。本文对 qwen 的「证伪」是**源码层面**的；涉及运行时行为（OOM、掉帧、USB、EXIF 触发面、R8 后行为）的结论仍需真机确认。

---

## 1. 当前项目到哪一步

- **代码状态**：`v0.6.3` 已发布（tag + Release 记录），HEAD 是在 tag 之上一个纯文档提交。README 路线图把 M0a/M0b/P1a/P1b/P1+/批次 1~5/UI v2.0/v2.1/UI 3.0/P2 全部标 ✅，P3 标 🔜。
- **CI 状态**：`docs/Github_CI.md` 记录 v0.6.3 tag run `42069327363`「5 job 全绿」。CI 确为 5 个 job（`build / lint / check-signing / release / publish`），单测与 lint 为真拦门【git】。**但 HEAD `8784274` 的运行结果未登记**，且本地 `origin/main` 引用陈旧，远端当前状态无法从本地判定。
- **真机验收状态**：`ENGINEERING_NOTES.md:502` 标题自陈「5.6 待真机验收清单（**至今未闭环**）」，其下 L504–509 的十余条（涂抹落点、导出内存日志、自动蒙版、人脸锚点、A7C2 直连、锐化晚于磨皮、批次 3/4/5 各条…）**没有一条被划掉**【文档】。`UI_DESIGN.md:1164` 对 16 屏的统计是 **✅ 7 ｜ ⚠️ 5 ｜ ❌ 4**【一手】——与 README「UI 3.0 全量改版 ✅」直接冲突。
- **一句话**：代码与设计产出非常充分，**但「已实现」与「已验证」之间隔着一层从未闭环的真机验收；发布产物（release/R8）也没有任何一次装机运行记录。**

---

## 2. 全量审计（七个维度）

### 2.1 目标与定位

- 计划（`DEV_PLAN.md §1.1`）与 README 一致：安卓端 AI 人像精修 → 相机直连 → NAS 批量。硬约束（本地零开发环境、CI 唯一编译器、一加15 真机、内置调试日志、wb-issues 面板）在代码与 workflow 中均有落点。
- **真正的产品层问题**（qwen §2.9 提出，本文认可但标为「需人决定」）：商业「像素蛋糕」卖的是**影楼批量工作流**，当前实现是**专业向手工精修**（81 项参数、44 套预设、8 个对象作用域、真 16-bit 线性 RAW）。而「批量」只有 `CameraBatch` 数据层，UI 侧 K/L 屏标 ❌，唯一入口是 §2.4 那个不可信的系留面板 ⇒ **批量今天没有一条端到端可用路径**【复核】。
- 这不是缺陷，是**参照系选择**。建议把「专业向手工精修 + 真 RAW 宽容度 + 全本地」写成 README 的一句话定位（它今天为真），或把 P2/P3 投入转向「相册批量 + 项目 + 另存预设」。**由项目所有人定，不擅自决定。**

### 2.2 技术路线

**成立且是本仓最硬的技术资产**【一手/复核】：
- 非破坏参数栈 + 代理预览 + 导出重放同栈；`renderIntoSrgb` 调色主循环只持 `IntArray(BAND_ROWS*w)`；`renderLinearFile` 导出时 16-bit 母版留 native、Kotlin 每带拉 32 行。
- 双缓冲交换在同一个非挂起段完成（`MainActivity.kt:490–508`），`RenderBatch(completed, epoch)` 两个正交量分开，提交守卫 `!completed || epoch != imageEpoch`；昂贵渲染配 `.conflate().collect` 而非 `collectLatest`。
- 「中性 ⇒ 整段跳过」在 `DetailPass.isNeutral` 与 `EditEngine` 调用判据互相钉住；LUT id 在构造期收敛成 Int opcode（`PixelProgram.kt:130–136`），理由按 33MP 像素数计算。
- LiteRT `CompiledModel` + GPU→CPU 降级 + MediaPipe 现成模型（不做 ONNX→TFLite 转换）、`noCompress += "tflite"`、降级 `createOrNull() → null` 且在设置页可见。
- `AndroidManifest.xml` 零 `INTERNET` 权限【git】⇒「照片不出手机」由权限声明钉死，比隐私文案更硬；同时它是一台天然的架构护栏（任何 P3/模型下载/崩溃上报都必须显式改变它）。

**需要限定口径的一处**：qwen「RAW 母版**始终**留 native」只对**导出路径**成立。预览路径 `decodeLinearProxy`（`ArwFullDecoder.kt:69–91`）明确用 `src.readAll()` 把代理分辨率线性母版整幅取回 JVM 堆（约 16MB），随后 `src.close()`【一手】。qwen 自己 B7 又列了「LinearImage(16MB) 常驻」，两处自相矛盾。

**未闭的合规项**：LibRaw LGPL-2.1 静态链接的「可重新链接」义务，`DEV_PLAN §8` 已登记「分发前需复核」，目前无对应产物【文档】。

### 2.3 UI 设计

- **规范真值确实分裂**，但**范围比 qwen 说的小**【一手】：
  - `UI_DESIGN.md:5/:1130` 称 `UI3.0.html` 是「当前规范唯一真值来源 / 已全部落地」；而 `UI3.0.html:1659` 自称「概念稿 v2.2…与 Studio 互为备选，评审后择一落地或混搭」，`UI3.0.html:6` title 是「Aperture 概念稿」。**这是真矛盾**。
  - `UI2.0.html:6/:320` 的 title 是「UI 3.0『Studio』设计稿」，而 `UI_DESIGN.md:1133` 称它为「UI 2.0 存档」——命名陷阱成立。
  - 但 qwen 说「四个 theme 文件 KDoc **仍全部**指向旧文档」**不成立**：只有 `Spacing.kt:6` 指向 `UI_DESIGN.md`；`Color.kt`、`Radius.kt`、`Type.kt` 已指向 UI 3.0 / `UI3.0.html`【复核 + 一手核 §2.6】。
  - qwen 说「`ENGINEERING_NOTES §2.6` 仍写旧圆角」**不成立**：`ENGINEERING_NOTES.md:194–197` 有 2026-10-25 订正「不再写死数值，一律以 `UI3.0.html` + `Radius.kt` 为准」【一手】。真正的问题是 **§9.1 的 L724–725 仍留着一句过时自陈**「§2.6 仍写圆角 14/22/40，三方不一致需订正」【一手】——即「文档内部自相矛盾」，不是「§2.6 没改」。
- **触控热区 26dp vs 48dp 冲突成立**【复核】：`ENGINEERING_NOTES.md:864–885` 明确登记，`UI_DESIGN.md:651/:690` 明文「保留 48dp 热区」，`UI3.0.html:122` 只给 `.sl-track{height:24px}` 的**视觉**口径。这是已算过、未擅自改的手感/无障碍取舍，推荐 B(36dp) 并真机试。
- **Deck「永不滚动」规范与代码偏离有账**：`UI3.0.html:672/:688` 要求 S=168/L=330 且永不滚动；`UI_DESIGN.md:1199–1200` 自陈代码 S=248dp + `verticalScroll` 兜底，并写明「拆组之后才能去掉」【复核】。属已登记的产品决策，不是隐藏 bug。
- **完成度统计冲突**：README 路线图 UI 3.0 ✅ vs `UI_DESIGN.md:1164` ✅7/⚠️5/❌4【一手】。

### 2.4 功能实现与可达性

**已确认可达**：87 个主源码文件里，`EditParams` 各字段均有真实消费者；「宁可不给、不给假的」纪律被执行（细部位美容假面板已删、`EditLerp.kt:18` 明写死字段不参与）。Rail 六工具 + `scopeMode` 三态与 `buildLayerStack` 的 `withoutWholeImageStages()` 语义对齐【复核】。

**「能操作但无效果 / 无入口」的死区**（逐条已回源码）：
| # | 现象 | 判定 |
|---|---|---|
| U1 | `lutId="none"` + `lutIntensity=0.8f`（`EditModel.kt:186–187`），而 `PixelProgram` 在 `lutOp=0` 时无分支 ⇒ 滤镜强度默认显示 80% 却空转 | **成立**【复核】 |
| U2 | 强度=0 时锐化半径/细节等修饰量不生效，`DetailPass.kt:111–120` KDoc 明说是设计；UI 不禁用不置灰 | **成立**（但 qwen 把暗角/颗粒也塞进「DetailPass KDoc」属引文扩张） |
| U3 | `ColorGrading.isNeutral` 只看 sat/lum，`PixelProgram` 整段跳过 ⇒ 色相 chip 无效果 | **成立**【复核】 |
| U4 | `ObjectMasks.maskFor` 永不返回 null（空对象给全 0 蒙版），`buildLayerStack` 只跳过 `maskOf→null`，chip 的 `itemEnabled` 只看模型可用 ⇒ 风光片上 7 个空作用域可选、可调、还能被标「已调整」 | **成立，最像「功能坏了」的一条**【复核】 |
| U5 | qwen 说「对象层 LUT 无入口」 | **不成立**，见 §3.4 |
| U6 | `onReset` 从顶栏移除、参数保留在签名里「未接线」，`MainActivity` 实现体零调用点 | **成立**【复核】 |
| — | `PresetCategory` 定义并给 44 套预设赋值，但 `ui/` 零消费 ⇒ 无分类导航；`NeutralGrayParams.threshold` 真实参与边缘判据但 UI 无控件 | **成立**【复核】 |

### 2.5 代码逻辑（正确性 / 并发 / 断链）

**预览渲染路径的并发纪律一手确认零违反**【复核】：`conflate().collect` + 提交守卫 + 非对称回收 + 多处 `CancellationException` 显式重抛。qwen 给这段的公道话是对的（且它自评「比第一版评价高」，说明做了复核）。

**主要缺口**：
- **A8 直方图陈旧值**（qwen 称 ROI 最高，本文确认**成立且比它说的更广**）：`EditorScreen.kt:441` 是 `produceState<…>(null, renderVersion, rendered)`。AndroidX 官方实现是 `val result = remember { mutableStateOf(initialValue) }` + `LaunchedEffect(keys = keys)` ⇒ **键变化只重启 producer，不重置 value**【源码】。因此不只是「换图后第一帧」，而是**每次渲染完成（`renderStamp++`）直方图都滞后一帧**；而 producer 无取消检查点（`runCatching` 内纯 CPU 循环，`EditorScreen.kt:443–464`【一手】），旧 producer 仍会跑完并写回 `value` ⇒ 可能**乱序覆盖**。`onAutoEnhance`（`MainActivity.kt:990–1002`【一手】）只有 `if (h != null)`，无「属于当前图/当前帧」校验，且 `history.push(...)` 进撤销栈 ⇒ 用户撤销不掉「自己没做过的事」。
- **A2** 同一 producer 不进 `renderMutex`、读的是被双缓冲降级为「下一批写入目标」的位图，`runCatching.getOrNull()` 把读已回收位图的异常吞成 null ⇒ 混帧 / 19MB 白烧 / 退出编辑器直方图静默消失【复核】。
- **A3** RAW 主路径可取消（progress lambda），`renderIntoSrgb` 无取消入口；`onCancelExport` 只置 flag 不 cancel 协程 ⇒ 取消后文件照样进相册而 UI 写「已取消导出」（幽灵文件）【复核】。
- **A7** `Decoder.kt:104`、`CameraSession.kt:76`、`UsbPermission.kt:91`、`Exporter.kt:62/70` 的 `catch(Throwable|Exception)` 吞 CE，而同文件 `Decoder.kt:50–53` 显式重抛并写了理由 ⇒ 一处做对、另一处没对齐【复核】。
- **K1/K2/K3 系留三处**（详见 §3，判级需校准）。
- **E1** `PtpProtocol.kt:60–61` `DP_F_NUMBER` 与 `DP_EXPOSURE_TIME` 同为 `0x500D`，至少一个是错的【一手】；`getDeviceProp` 一律按 u16 解、不查 `GetDevicePropDesc` datatype（`CameraSession.kt:63–69`【一手】）。
- **G3** `Decoder.kt:98–101` 注释断言「65MB 缓存彻底泄漏、F21 没修」，而 `:105` 的 catch 第一句就是 `releaseCache(...)`【一手】⇒ 注释描述了不存在的 bug。

### 2.6 性能与内存

**账（十进制 MB；33MP = 7008×4672×4B = 130.97MB）**：

| # | 事实 | 量级 | 备注 |
|---|---|---|---|
| B1 | sRGB 全幅导出 `fullBase` 与 `fullTarget` 同活跨过渲染与编码（`MainActivity.kt:879–944`） | **261.9MB**（2×130.97） | qwen 写「250MB」是 **249.8 MiB**，与全仓十进制口径串档【复核】 |
| B2 | `RasterMask.fromStrokes` = `FloatArray(w*h)`（`Mask.kt:62`），导出档 131MB / 预览约 11MB 每帧 | 131MB 峰值 | **有条件**：`RetouchScale.kt:62` 在无描迹时返回 null + `FullMask`（O(1)）【复核】 |
| B4 | `raw_bridge.cpp:118` `dcraw_make_mem_image()` 早于 `:144` `recycle()` ⇒ 4 通道 262MB + 3 通道 196MB 必然共存 | **≥458MB native 下界** | 且 196MB `image` 一直活到 `closeLinear`（`:166`→`:283–290`），**贯穿整个导出**而非瞬时峰值【一手】 |
| B5 | 直方图每完成帧 `IntArray(w*h)` + 3×`ByteArray(n)` | ≈19.6MB/帧 churn | 与 A2 叠加【复核】 |
| B6 | `NeutralGray.boxBlur` 每带新建 2 个窗口 `IntArray`；可复用 `applyBanded/boxBlurInto` 在生产路径**零调用**（只有测试参照用） | ≈370MB churn（峰值 ≈30MB） | 【复核】 |
| B7 | 预览常驻 4 张代理位图 + `LinearImage` ≈58MB | 常量 | 只有 `rendered`+`renderSpare` 是双缓冲代价，其余归因偏宽【复核】 |
| B3 | 液化 `IntArray(spanRows*w)` + `IntArray(bw*bh)` 来自包围盒/源行跨度而非整幅 | 典型 ≈46+12MB | qwen 第一版误判 250MB 后已自行降到 P2，**这次判对了**【复核】 |

**档位缓解未实装**：`DEV_PLAN §6.2` 给 6–8GB 档的「单 Bitmap 复用 / 禁并行多份 / 导出即 recycle」在代码里没有对应实现，而 `resolutionProfile()` 对中端仍返回 7008【复核】。
**结论**：`DEV_PLAN §6.2` 的 F05「33MP 峰值 ~130MB」**只对逐像素上色链成立**，对 retouch / 笔刷 / 原生三侧不成立。这是「文档承诺了、代码没做」，比「算子太重」更可执行。

**其它**：零 dither（16-bit→8-bit 只有 `+0.5f` 四舍五入）、零热节流观测、每帧 `DebugLog.flush()` 且 `@Synchronized`、`EditEngine.kt:373` `jobs.forEach { it.get() }` 无超时不可取消、`EditEngine.render()` 零调用、`tetherScope` 声明未用【复核/git】。

### 2.7 文档体系

**这是本项目头号系统性风险**，且**双向漂移**四个方向都有实例：
- **超前**：`DEV_PLAN §2.1` 给 A7C2 直连打 ✅，`UI_DESIGN §11` 的 J′/K/L/L′ 四屏 ❌，且无一次真机成功会话记录【文档 + 复核】。
- **滞后**：`DEV_PLAN §0` 停在 2026-10-25 / v0.5.0，`§10`（L372）写「当前版本 v0.5.0 / versionCode 13」，而 `build.gradle.kts` 是 0.6.3 / 18【git】；`PtpTransport` 注释仍写「对象流式下载留待 PoC-4」而 `CameraSession` 早已实装。
- **描述不存在的缺陷**：`Decoder.kt:98–101`（G3）。
- **要求而代码没做**：`ObjectScope.Accessories` KDoc 明令 UI 必须挂覆盖范围 Hint，全仓「配饰」只在注释里出现【复核】。
- **悬空引用**：`ui_preview.html` 在 HEAD 已删，但 `DEV_PLAN:367`、`UI_DESIGN:1132`、`OBJECT_TONE_DESIGN:446/479`、`TONING_DESIGN:561` 共 5 处仍引用【git】。
- **重复/编号问题**：`README.md:255` 与 `:256` 两行完全相同【git】；`ENGINEERING_NOTES` 目录只列到 §8 而正文有 §9–§12；§9.3-C 历史丝带在 L746 写「已处置」、L780/L832/L930 写「未做/仍未实现」、L996 又写「已补齐」——**同一事项同日多处结论相反**【复核】。
- **README 自相矛盾**：路线图 UI 3.0 ✅ 全量，而文档索引指向的 `UI_DESIGN §11` 统计是 ✅7/⚠️5/❌4【一手】。

---

## 3. 对 `docs/qwen3.8审计.md` 的核对结论

### 3.1 总体判定

**有道理，主体可信，但不是「无误」；它的方法论质量高于它的逐条准确率。**

- 它自报「四轮复核、13 条断言被推翻」，`_cite_check.txt`（86 行逐文件:行号核对清单）证明引用核对确实做过；附录 B 的「为什么会错」对下一会话有真实复用价值。
- **规模数字全部精确**：87 个主源码 / 19,765 行、39 个测试文件 / 294 `@Test`、18 个 tag、`androidTest` 0、子模块未 checkout、`ui/` 语义声明 5 处、`INTERNET` 0、`rememberSaveable` 1 处、EXIF/序列化/thermal/dither/runBlocking 零命中——逐项实测吻合【git】。
- **核心结论成立**：P1 技术路线选对且执行纪律好、P2 不是「有 bug」而是四处各自不正确叠加、文档双向漂移是系统性风险、EXIF/方向这一整层从未进设计视野、A8 是代价最小后果最重的一条、CI 补偿体系有效但覆盖边界清楚。这些都站得住。
- **但它仍有 ≥8 处实质误判 + 多处自身瑕疵**（下两节），且**判级偏重**：3 条 P0 里至少 1 条（K2/K3 合成那条）应降为 P1。它自己批评别人「文档自称状态 vs 代码实际没核」，恰好是它这几处误判的成因。

### 3.2 成立且高价值（采纳）

| 论断 | 本人/复核证据 |
|---|---|
| M-1 零 EXIF / 零方向 / 导出不写元数据 | `git grep` 零命中；`Decoder.kt:127–143` 用 `BitmapFactory.decodeStream`（不应用 Orientation）【一手】 |
| K1 系留 ARW 必失败 | `CameraPanel.kt:148` 用 `decodeFullRes`，`Decoder.kt:73–74` 对 `.arw` 直接 null【一手】 |
| A8 陈旧直方图喂自动增强 | 官方 `produceState` 源码 + `EditorScreen.kt:441` + `MainActivity.kt:990–1002`【源码/一手】，且比它说的更广（见 §2.5） |
| B4 native ≥458MB 下界 | `raw_bridge.cpp:118 / :142–144`【一手】 |
| A2 / A3 / A7 / G3 / U1 / U3 / U4 / U6 / T1 / T4 / G1 | 逐条复核成立【复核】 |
| §9「值得保留的」7 条 | 分带纪律、双缓冲 + `completed/epoch`、中性跳过、LibRaw 生命周期、降级契约、陷阱知识、CI 闸门 —— 本文同意，别改坏 |
| 附录 B 的自我纠错方法 | 「异步问题先确定哪一行抛、落在哪个 catch」「取消语义的真相通常在调用点」「读了注释没读注释下面的代码」—— 价值高于正文多数单条 |

### 3.3 需校准（方向对，措辞/判级过头）

| 论断 | 校准 |
|---|---|
| K2/K3 合成 P0：「取最老 + 不播种 + 队首阻塞 ⇒ 排空整卡、可能永不推进」 | 我模拟了 `filter+take(3)` 状态演变【一手读 `CameraPanel.kt:130–163`】：队首坏对象时**每轮仍推进 2 个**（`b/c` 被记账，下一轮窗口补进 `d/e`）；只有窗口 3 个全坏才完全停滞。且每轮要串行跑完下载/解码/渲染/编码，「3 秒排空整卡」不成立。**真缺陷仍在**（不播种基线 ⇒ 先处理旧片、坏对象永久占位），但应降 **P1** 并改写措辞。qwen 附录 B 说「正常每次推进 3 个」与正文「永远推进不了」各错一半。 |
| E1「仪表盘数字必错」 | 撞码常量成立，但 `CameraSession.kt:73` 的 `tetherSnapshot` **硬编码** `0x5001/0x5007/0x500D`，根本不引用那两个撞码常量 ⇒ 它们是死常量【一手】。真正影响数值的是「一律 u16」：`BatteryLevel` 为 1 字节时 `:66` `size<2` 返回 null（电量不显示），不是「数字必错」。 |
| E3(b)「claimInterface 失败原因只写日志不上屏」 | `CameraSession.kt:309–312` 把失败转成文案，`CameraPanel.kt:281` 写进 `lines` ⇒ **失败本身上屏了**；只有「MediaProvider 占用、请换 PC 遥控」这类**具体诊断**留在日志【复核】。建议仍在（把诊断串上屏，改动极小、回报高），但定性要改。 |
| E4「DNG 打不开」 | 系留/编辑器路径走不通成立，但 `PtpData.looksLikeRaw` 把 dng 也算 RAW ⇒ 批量 `processOne` 走 `renderLinearFile`，DNG 可能反而能打开【复核】。 |
| B1「250MB」 | 单位串档：全仓统一十进制，两张全幅 = **261.9MB**；250 是 249.8 **MiB**【复核】。 |
| B3b「`maskRowSpan` 每行 w 次 sample、无早停」 | `RetouchLayer.kt:290` 内层有 `break`【一手】；Beauty bbox 扫描范围是条带 `h=spanRows` 不是全幅；真正的第二遍全幅扫描是 `Beauty.centroid`。 |
| §2.1「两处 CE 显式重抛」 | 实际 3 处（`:442–444`、`:555–556`、`:947–949`），`Decoder.kt` 另有 `:50–53`、`:75–76`【复核】。 |
| M-2「真机验收装的是 debug 包」 | 是过程事实，仓库只能证明 CI 常规产出 debug APK；`proguard-rules.pro:22`「release 任务此前从未运行」与 workflow 已有的 `assembleRelease` 冲突，qwen 照抄未校正【复核】。 |
| T5「release 侧是 -O2」 | 配置与 CMakeLists 都没有 `CMAKE_BUILD_TYPE`，仓库无 `.cxx` 产物可查 ⇒ **无法从仓库证实**；若 AGP 未注入，debug/release 同为 -O0【复核】。 |
| §4.10「AppSettings 注释声称迁移有假 SharedPreferences 三条覆盖、测试零命中」 | **不成立**，见下节。 |

### 3.4 不成立 / 错觉（重点，均为亲自回读确认）

1. **§3.2「四个 theme 文件 KDoc 仍全部指向旧文档」——错。** 只有 `Spacing.kt:6` 指向 `UI_DESIGN.md`；`Color.kt` / `Radius.kt` / `Type.kt` 已指向 UI 3.0【复核】。
2. **§3.2「`ENGINEERING_NOTES §2.6` 仍写旧圆角」——错，且违反它自己的方法。** `ENGINEERING_NOTES.md:194–197` 有 2026-10-25 订正「不再写死数值，以 `UI3.0.html` + `Radius.kt` 为准」【一手】。qwen 采信了 §9.1 L724–725 那句**过时自陈**并标 ★，没读 §2.6 正文。真实问题是「文档内部自相矛盾」，定位错了。
3. **§3.2 / G2「§9.6 把 ParamSlider 紧凑化列为前置未做项」——错。** 那句在 **§9.5** L796（根因分析）；§9.6 L852 写「滑块紧凑化后 ≈46dp/条」、L866 写「ParamSlider **本轮**把滑杆收成 26dp」【一手】⇒ 早已做了。G2「账本比代码旧 4 天」也没有时间证据（UI_DESIGN 最后提交 11-02、EditorScreen 11-04、ENGINEERING_NOTES 与 HEAD 同为 11-06）【复核】。
4. **§4.10 末段「AppSettings 注释声称的保障并不存在」——时态读反，整条不成立。** `AppSettings.kt:119–129` 白纸黑字：「当前状态：v0 → v1 是**空迁移**」「**等第一条真实迁移落地时**，必须同批补 `SettingsMigrationTest`（用假 SharedPreferences 覆盖三条…）」【一手】。那是**未来测试的规格**，不是对既有测试的声称；空迁移阶段没测试完全符合计划，却被 qwen 升到总账 P2。
5. **U5「对象层 LUT 无入口」——不成立，而且真问题被它错开了。** 本人核到实参绑定：`EditorScreen.kt:874` 传 `params = shownParams`、`:893` 传 `onParamChange = writeParams`；`writeParams`（`:302–305`）在 `activeLayer != null` 时写回当前层；`activeScope`（`:298`）只随 `original` 重置、切分类不清空；`ParamPanel.kt:420–424` 在 `NotApplicable` 下**只加一句 Hint 仍渲染** `PresetParams`，`:1222` `LutGroup(params, onParamChange, …)` ⇒ **先选对象作用域、再切「预设→滤镜」即可逐层设 LUT**【一手】。更要命的是 `ParamPanel.kt:392` 那句 Hint 写「『预设』是整幅操作，不受当前作用域影响」——**UI 文案否认了代码的实际行为**。这才该修。
6. **E3(a)「选中接口未验同时具备 bulk IN + OUT」——反证没做透。** `PtpTransport.kt:502–509` 两个 `?: run { …; return null }` 分别保证 IN/OUT 非空【复核】。真实缺陷是「选接口时未把端点完备性纳入选择条件、端点缺失时整体失败而不回退到其它候选」，不是「未验证」。qwen 附录 B 已「半推翻」却仍把它当真缺陷保留。
7. **K4「下载文件从不删除」「不传蒙版 ⇒ 口径不一致」——过头。** `CameraBatch.newSingleTarget` 每次 `pruneOldFiles`（1 小时兜底），下载失败即删【复核】；`renderIntoSrgb` 的 `mask` 有默认值 null，批量路径显式传的也是 null ⇒ 语义相同【复核】。准确说法是「成功路径不像批量那样导完即删，只靠 1 小时兜底」。
8. **T2「全 UI 只有 1 处 `rememberSaveable`」/ T4「5 处语义声明」——把注释和 import 当成代码。** 那 1 处是 `ParamPanel.kt:169` 的 **KDoc 文字**，实际代码 0 处；5 处语义里含 1 个 import 与 1 处 KDoc，有效声明只有 3 处【复核】。T3 的「AppShell 按高度分支」实为 `maxWidth / tabs.size` 按宽度均分【复核】。
9. **A1「吞掉一切失败」「CE 不被捕获」措辞不严**：`catch (_: Exception)` 在 JVM 上**会**吞 try 内 suspend 抛的 CE，只是下一轮 try 外的 `delay(3000)` 立刻再抛才没形成忙循环；`Error/Throwable` 也不在捕获范围【复核】。结论（不是 ANR）仍对。
10. **D2「三个按钮 busy 不在 finally」——是 4 处**（漏了「导入」按钮 `CameraPanel.kt:492/502`）【复核】。

### 3.5 qwen 文档自身的瑕疵

- **数字自洽失败**（它自称做过「数字自洽轮」）：`:96` 写「Android 14+ 合计 **56.4%**」，`:98` 写「降到 34（覆盖 **71.8%**）或 33（**83.6%**）」——minSdk 34 就是 Android 14+，同一件事两个数；且 71.8%/83.6% 在「资料来源」（`:480–482`）里**没有任何出处**。
- **外部数据标注虚高**：`:96` 称「官方分布」，实际来源是 AnTuTu 转引 Heise + 自媒体 + IT之家；`:478` 又混用「谷歌开发者峰会经 Android Studio 口径」。○ 标注形式合理，但来源可追溯性与口径一致性不合格，且它被用作「承重理由」。
- **编号错乱**：§9 出现两个 `7.`（`:442`、`:444`），随后 `:445` 是 `8.`、`:446` 是 `9.`；§6 阶段 0 用 1–7，阶段 1 又从 **5** 重新起（5/6/7 重复）。
- **「工作树干净」不准确**：`git status` 有 2 个未跟踪文件，其中 **`qwen3.8审计.md` 就是它自己**。且它未入库 ⇒ 下一克隆者根本看不到这份审计（讽刺的是它批评别人文档腐化）。
- 文字瑕疵：`:472` 出现 `；，`；同句称 A8「代价最小、后果最重」而总账把 A8 列 P1。
- **方法论反讽**：`:15` 自陈「每个进结论的位置都看过前后至少 15 行」，但 §2.6、§9.6、AppSettings KDoc、`rememberSaveable`、theme KDoc 这几处恰恰只要多读 15 行就能避免误判。

### 3.6 qwen 漏掉、本轮独立发现

1. **A8 的作用面比「换图第一帧」大得多**：键含 `renderVersion`，**每次渲染完成**都重启 producer 且不重置 value ⇒ 拖拽过程中每一帧直方图都滞后，且无取消检查点的旧 producer 会写回 value，可能乱序覆盖【源码 + 一手】。
2. **`Decoder.decodeArw` 的悬空缓存路径（真残留缺陷）**：`Decoder.kt:105` 已 `releaseCache(cache.absolutePath)` 删掉缓存文件，但 `:112–114` 只写 warn、`:115–124` 仍返回带 `rawCachePath = cache.absolutePath` 的 `DecodedImage` ⇒ 指向已删除文件；后续 RAW 导出分支 `openLinear` 会失败降级【一手】。qwen 只说「注释描述了不存在的 bug」，没往下读。
3. **`ParamPanel.kt:392` 的 Hint 与代码行为矛盾**（见 §3.4 第 5 条）。
4. **CI 证据边界与 remote 配置冲突**：`docs/Github_CI.md` 只记到 `548792c`，HEAD `8784274` 无登记；`git remote -v` 仍是 **SSH**，而 `ENGINEERING_NOTES.md:377` 自陈「SSH 已完全不可用、一律 HTTPS」【git】。qwen 完全没看这一层。
5. **README ↔ UI_DESIGN 的完成度直接冲突**（✅ 全量 vs ✅7/⚠️5/❌4）【一手】。
6. **`CameraPanel.kt:156` 忽略 `renderIntoSrgb` 的 Boolean 返回值**，而 `EditEngine.kt:89–90` KDoc 明写「调用方必须消费，否则一张尺寸不对的旧位图会被当成品上屏」；`CameraBatch` 用 `if (!ok) throw` 消费了 ⇒ 又一个「同文件一处做对另一处没对齐」【复核】。
7. **系留异常路径泄漏 + 零线索**：`:155–157` 任一抛异常时 `bmp`/`decoded.bitmap` 无人回收，且被 `:162` 空 catch 吞掉【复核】。
8. **`PtpTransport.downloadObject` 最后一次 Response 连容器类型都不验**（不只事务号）；`CameraSession.readObjectInfo` 不检查响应码【复核】。
9. **`B6` 的可复用跨带路径在整个生产代码零调用**（只有测试参照用），比 qwen 写的「RetouchLayer 没走」更强【复核】。

### 3.7 一句话结论

> **qwen 的「判断」值得采纳，「证据」必须逐条复核。**
> 它的方向性结论（P1 成立 / P2 不可信 / 文档腐化 / 元数据盲区 / A8 / CI 边界）大体正确，规模数字精确，自我纠错方法可复用；但它在**「文档自称状态 vs 代码实际」这一层反复失手**，把 §9.1 的过时自陈当成 §2.6 的现状、把 §9.5 的句子安到 §9.6、把未来测试规格读成已声称的保障、把有隐蔽入口的 U5 判成无入口。
> 因此：**不可把它的总账直接当排期依据**；本文 §4 是校准后的版本。

---

## 4. 综合问题清单（校准后）

> 判级口径：P0 = 现在就错且一次点击能碰到；P1 = 需排期；P2 = 债；P3 = 发布前必闭的合规/决策。

### P0

| # | 问题 | 证据 | 与 qwen 判级差异 |
|---|---|---|---|
| P0-1 | **系留 ARW 必失败**：走 `decodeFullRes` 而它对 `.arw` 设计上返回 null | `CameraPanel.kt:148` + `Decoder.kt:73–74`【一手】 | 一致，保留 P0 |
| P0-2 | **EXIF / 方向 / 元数据整层缺失**：解码不认 Orientation，导出不写元数据，无手动旋转；RAW 经 LibRaw 会应用机身 flip、JPEG 不会 ⇒ 同机同场景方向可能不一致 | `git grep` 零命中 + `Decoder.kt:127–143`【一手/git】 | 一致，保留 P0；但注明「方向错是条件性的（取决于图片来源），元数据丢失是无条件的」 |
| P0-3 | **A8 陈旧直方图写进参数栈**：换图/每次渲染完成后 `produceState` 不重置 value，`onAutoEnhance` 无身份校验且 `history.push` | 官方 `produceState` 源码 + `EditorScreen.kt:441,765` + `MainActivity.kt:990–1002`【源码/一手】 | qwen 列 P1；本文**升 P0**：它会改用户没做过的参数且不可撤销，且作用面比它说的更广 |

### P1（按 ROI 排序）

| # | 问题 | 证据 |
|---|---|---|
| P1-1 | **文档一次性收口**：DEV_PLAN 版本停在 v0.5.0/13；5 处 `ui_preview.html` 悬空；README 重复行 + ✅ 全量 vs UI_DESIGN ✅7/⚠️5/❌4；ENGINEERING_NOTES §9.1 过时自陈、§9.3-C 状态四处打架；`Decoder.kt:98–101` 假 bug 注释；`PtpTransport` PoC-4 注释 | 【git/一手】**极小代价、最该先做** |
| P1-2 | A2 直方图无取消检查点 + 不进锁 + 异常吞成 null | `EditorScreen.kt:441–465`、`MainActivity.kt:253–256`【复核】 |
| P1-3 | A3 JPEG 导出不可取消 ⇒ 幽灵文件 | `EditEngine.kt` + `MainActivity.kt:916,923,972–976`【复核】 |
| P1-4 | U4 空作用域可选可调可标记「已调整」；U1 滤镜强度 80% 空转 | `ObjectMasks`/`ObjectLayers`/`ParamPanel:484,487`、`EditModel:186–187`【复核】 |
| P1-5 | **K2/K3 校准版**：系留不播种基线 ⇒ 先处理旧片；坏对象永不记账 ⇒ 永久占位（但每轮仍推进，非「永不推进」） | `CameraPanel.kt:113,130–139`【一手】**由 P0 降 P1** |
| P1-6 | B1 双份全幅（261.9MB 十进制）+ §6.2 档位缓解未实装 | `MainActivity.kt:879–944`、`DEV_PLAN §6.2`【复核】 |
| P1-7 | B2 笔刷全幅 FloatArray（有条件）+ B5 直方图 19MB/帧 | `Mask.kt:62`、`EditorScreen.kt:448–453`【复核】 |
| P1-8 | B4 native ≥458MB 下界且 196MB 贯穿导出；`openLinear` 无预算 | `raw_bridge.cpp:118,144,166`【一手】 |
| P1-9 | M-2/T5 发布产物（R8 + shrinkResources）与验收产物（debug、native 无优化）分裂，且 release 侧优化级别无法从仓库证实 | `build.gradle.kts`、workflow、CMakeLists【复核】 |
| P1-10 | E1 属性码撞码 + 一律 u16 不查 datatype；E3(b) 具体诊断不上屏 | `PtpProtocol.kt:60–61`、`CameraSession.kt:63–69`【一手】 |
| P1-11 | T1 参数栈 JSON 序列化不存在 ⇒ 另存预设 / 项目 / P3 契约三件事同时被卡 | `DEV_PLAN §3.3` + `git grep` 零命中【git/复核】 |
| P1-12 | T2 无导航层 / 无 ViewModel ⇒ 系留只能模态、模型中心降级内联、位图生命周期手写在 1,325 行 Activity | `MainActivity.kt:129,613,633,1087`【复核】 |
| P1-13 | T3 横屏零适配（`ui/editor/` 零命中）而 manifest 允许自由旋转 | 【git/复核】 |
| P1-14 | T4 无障碍整层未建立（有效语义声明仅 3 处、文案零资源） | 【git/复核】 |
| P1-15 | **本轮新增**：Decoder 悬空 `rawCachePath`、ParamPanel `NotApplicable` Hint 与写回行为矛盾、CameraPanel 忽略 `renderIntoSrgb` 返回值、系留异常路径位图泄漏 | 【一手/复核】 |
| P1-16 | **CI 证据与 remote 配置**：HEAD 无 CI 登记；remote 仍是 SSH 而工程笔记自陈 SSH 不可用 | 【git】需你通知后才能查远端 |

### P2

A4/A5/A6/A7、B3/B3b/B6、D1–D5、K4 校准版、E2/E4/E5、M-3/M-4/M-5、U2/U3/U6、U5 的文案修正、`ParamPanel:589` 引用已删除「效果」分类、`PresetCategory`/`threshold` 无 UI、横滑 chips 无截断提示、死代码（`EditEngine.render` / `tetherScope` / `MlMaskProvider.reset`）、`arrivalLog` 无上界、事务号校验不对称、`MAX_INLINE_BYTES` 注释滞后。

### P3（发布前必闭）

LibRaw LGPL-2.1 relink 义务、minSdk 36 的对外发布决策、`.gitmodules` 浮动 `branch = master`、`device_filter.xml` / USB 插拔广播、无障碍清单、真机验收清单整体闭环。

---

## 5. 整改建议方向

排序原则：**先让证据可信 → 再修必然错 → 再清死区与告知 → 最后才加功能。**
本项目最独特的失败模式是「没有判据就打 ✅」，所以每条都要带验收判据。

### 阶段 0 · 证据与账本（最高 ROI，先做）

1. **把当前状态钉死**：确认 HEAD `8784274` 的远端/CI 状态（需你通知后执行），把结果覆盖写进 `docs/Github_CI.md`；核对 remote SSH/HTTPS 与 `ENGINEERING_NOTES §5.2` 的自陈是否一致。
   **判据**：任何「当前全绿」的说法都能指向一个本次有效的 run + commit + variant。
2. **文档一次性收口**（P1-1）：版本号、5 处悬空引用、README 重复行与完成度表述、§9.1/§9.3-C 的自相矛盾、假 bug 注释、PoC-4 注释。
   **判据**：任何一条 token 或「这做没做」的争议能在一个文件里结束；`git grep ui_preview.html` 零残留。
3. **建立「声明 ↔ 调用点」交叉核对**（可脚本化）：把文档里的 ✅ 与调用点检索、文档里的「未做/待/留待」与代码里的实现符号互相对照。本轮 qwen 与我们共同发现的误判，一大半属于这一类。
   **判据**：新提交里出现「已实现 / 未做」字样时，闸门能给出对应符号的存在性检查。

### 阶段 1 · 修必然错（代码）

4. **P0-3 A8**：让直方图生产者输出 `(stamp, epoch, 曲线)`，消费方要求与当前图/当前帧匹配；键变时先置 null；`onAutoEnhance` 在匹配前置灰并写「正在分析这张图…」。
   **判据**：换一张明显欠曝图后立刻点「自动」，建议值来自新图（用 `auto enhance {ev,c}` 日志对比）；连续拖滑块时直方图与画面同代。
5. **P0-1 K1 + P1-5 K2/K3**：系留改走 `renderLinearFile` / 复用批量路径；开系留播种 `knownHandles`；`takeLast(3)`；两处 `continue` 前记账；空 catch 改成写 `arrivalLog` + `DebugLog.w`；消费 `renderIntoSrgb` 返回值；位图回收进 `finally`。
   **判据**：卡上有旧片时打开系留**不产生任何下载**；放一张 ARW 进去能出成片；混有目录对象时仍能推进；任一失败界面能看到是哪一步。
6. **P0-2 M-1**：解码侧 `ExifInterface` + `Matrix.postRotate`；导出侧回写最小 EXIF 集；可选手动旋转工具。
   **判据**：`Orientation=6` 的竖拍片进 App 是正的、导出也是正的；同名 ARW/JPEG 方向一致。
7. **P1-2/3 A2/A3**：直方图进 `renderMutex`、按带累加、加 `isActive` 检查点；给 `renderIntoSrgb` 传取消回调（照抄 RAW 侧 lambda）；统一 `catch(Throwable)` 的 CE 重抛（可加成本地静态闸门）。
   **判据**：取消后相册**没有**该文件；拖滑块时直方图与画面同代、GC 抖动消失。

### 阶段 2 · 内存与诚实界面

8. **B1 + 档位实装**：把 `DEV_PLAN §6.2` 中端三条缓解落成代码；B2/B5 网格化（`FloatGrid` 模式已存在）；B6 接上已有的可复用跨带路径。
   **判据**：`memorySnapshot()` 峰值与导出日志有**日期 + 机型 + 变体（debug/release）**；33MP + 笔刷下 `heapPct` 峰值有明确读数。
9. **装一次 release 包**跑「导入 ARW → 自动蒙版 → 人脸检测 → 导出」，抄回 `render done {ms}` 与 `memorySnapshot()`。
   **判据**：文档里每个性能/内存数字都带来源与变体；R8 后的 ML 路径有一次真机运行记录。
10. **U4 > U1 > U2/U3 > U5 文案 > U6**：姿态只有两种——**接通**或**显式禁用 + 一句说明**，不允许「能操作但无效果」。修掉 `ParamPanel:392` 那句与实际行为矛盾的 Hint、`ParamPanel:589` 引用已删分类的文案；给横滑 chips 加截断提示。
11. **`BulkIo` 缝 + 假设备测试**（最老三个 handle / ARW 对象 / 中途拔线）。
    **判据**：K1/K2/K3/E1 各自有一条会红的回归测试。

### 阶段 3 · 需要你先回答 §6 才能开工

12. **T1 序列化先行**：`EditParams/RetouchState/LayerStack ⇄ JSON` + `version` 字段 + 迁移钩子（借 `AppSettings.migrateSettings` 已有骨架）。**顺序不能反：没有格式就没有持久化**；它一次性解锁另存预设、项目/会话、P3 与 Rust 引擎契约。
13. **T2 最小导航层**（自写 sealed 路由 + back stack 即可，不必上 Hilt/Navigation），把位图生命周期从 `onBack` 那段「最危险的回收」挪进可测试的生命周期对象；顺带解锁「系留期间可修图」与独立模型中心页。
14. **T3 横屏最小可用**（`BoxWithConstraints` 按宽度分支，Deck 变右侧栏）、**T4 无障碍语义清单**（chip / 滑块 / 自绘图标 / 画布手势 / 直方图 / Deck 手柄），文案收进 `strings.xml`。
    **判据**：横握看 3:2 横构图时直方图 + 3 条滑块全可见；闭眼用 TalkBack 能走完「导图 → 调曝光 → 导出」。
15. **产品路线二选一**：相册批量交付（K/L 屏）或专业手工（AGSL 混合上色链 + 预览↔导出一致性验收）。**不该都做**。

### 一条护栏化建议（比逐条修更值钱）

`ENGINEERING_NOTES §2/§3` 那批「被真机 bug 逼出来的」纪律目前**只停在文档层**，而本轮发现的 A2/A7、「同文件一处做对另一处没对齐」都是同类错误在新代码里复发。建议把三条升级成代码/闸门：
- 「位图 + 锁」收成唯一入口的内部类型（A2/A4/B5 一起消失）；
- 「`catch(Throwable)` 未伴随 CE 重抛」加进本地静态检查（仓库已有 `kotlin_gates.py` 骨架）；
- 「文档 ✅ / 未做」与调用点符号的交叉核对脚本。

---

## 6. 需要你决定（不擅自定）

| # | 问题 | 建议 |
|---|---|---|
| Q1 | 定位对齐影楼批量还是专业手工 | 现阶段把「专业向手工精修 + 真 RAW + 全本地」写进 README 一句话（它今天为真） |
| Q2 | P2 对外措辞 | 改名「从相机导入」；接 CRSDK 做真系留是另一个项目；撤入口会浪费已写好的下载/导出管道 |
| Q3 | 「永不滚动」是否保留为硬约束 | 改写规范：代码已给出算过的账（3 滑块 232dp / S=248 有余量），继续写「永不滚动」只会让下个会话去「修」它 |
| Q4 | 热区 26 / 36 / 48dp | 36dp 折中，**必须真机试手感** |
| Q5 | 是否给用户手动旋转工具 | 都要（M-1 自动化解决多数，方向标记本身可能损坏） |
| Q6 | minSdk 36 是否刻意只为自用 | 12 个月内不对外发布就保留并写清理由；否则降 34/33（注意 qwen 给的覆盖率数字自相矛盾，需重新取权威数据） |
| Q7 | 首页紫→金渐变与暖石墨品牌语言冲突 | 视觉方向，需真机对比；技术上是一行 |
| Q8 | **是否 fetch / 查远端 CI、是否 push、是否把 `hy4审计.md` 与 `qwen3.8审计.md` 入库** | 按项目规则等你通知；本轮未执行 |

---

## 7. 结论

| 门禁 | 结论 | 依据 |
|---|---|---|
| 代码规模与工程纪律 | **PASS** | 87 主源码 / 294 JVM 单测 / 5 道 CI job / 分带与双缓冲纪律一手确认零违反 |
| 技术路线 | **PASS** | CPU 分带 + LibRaw 真 16-bit 线性 + LiteRT 降级链，三段都成立 |
| 功能与需求一致性 | **FAIL** | 3 条 P0（系留 ARW 必失败、EXIF/方向整层缺失、陈旧直方图写参数）+ 16 条 P1；UI_DESIGN 自统计 ✅7/⚠️5/❌4 而 README 标全量 ✅ |
| 发布就绪 | **BLOCKED** | 待真机验收清单一条未闭环；release/R8 产物无一次装机记录；HEAD 无本次有效 CI 证据；LGPL relink 未闭 |
| `qwen3.8审计.md` 可信度 | **有道理，但需校准** | 方向性结论与规模数字可信；≥8 处实质误判、4 类自身瑕疵；总账中 K2/K3 应降 P1，A8 应升 P0 |

> **最终一句话**：这个项目的**代码质量明显高于它的证据质量**——2 万行像素管线、294 个单测、成体系的工程纪律都是真的；缺少的不是能力，是「把 ✅ 绑定到可复核证据」的收尾工序。下一轮最值钱的不是再加功能，而是**阶段 0 的证据与账本收口 + 三条 P0**。

---

## 附录 A · 本人亲自回读复核的关键位置（非子代理转述）

| 文件 | 行 | 核了什么 |
|---|---|---|
| `docs/ENGINEERING_NOTES.md` | 190–203 | §2.6 是否已订正圆角（已订正） |
| `docs/ENGINEERING_NOTES.md` | 718–731 / 790–801 / 827–871 | §9.1 过时自陈、§9.5 前置句、§9.6 紧凑化已做、26dp 冲突 |
| `docs/UI_DESIGN.md` | 1162–1166 | ✅7/⚠️5/❌4 统计 |
| `app/.../ui/settings/AppSettings.kt` | 112–133 | 空迁移 + 未来测试规格（时态） |
| `app/.../ui/editor/ParamPanel.kt` | 313–354 / 385–426 / 1210–1237 | 函数签名、`NotApplicable` 仍渲染、`LutGroup` 绑定、PERCENT_U |
| `app/.../ui/editor/EditorScreen.kt` | 294–311 / 437–489 / 870–900 | `shownParams`/`writeParams` 定义、histogram producer、`params = shownParams` + `onParamChange = writeParams` |
| `app/.../ui/home/CameraPanel.kt` | 108–170 | 系留轮询全貌（K1/K2/K3/A1/M-5） |
| `app/.../core/decode/Decoder.kt` | 66–84 / 98–124 / 127–150 | `decodeFullRes` 对 ARW 返 null、假 bug 注释 + 悬空 `rawCachePath`、`BitmapFactory.decodeStream` |
| `app/.../MainActivity.kt` | 985–1008 | `onAutoEnhance` 无身份校验 + `history.push` |
| `app/.../camera/PtpProtocol.kt` | 54–67 | `0x500D` 撞码 |
| `app/.../camera/CameraSession.kt` | 58–81 | `tetherSnapshot` 硬编码属性码、u16 解析 |
| `app/.../core/edit/retouch/RetouchLayer.kt` | 282–299 | `maskRowSpan` 内层 `break` |
| `app/.../arw/ArwFullDecoder.kt` | 62–100 | `decodeLinearProxy` 用 `readAll()` 整幅回 JVM 堆 |
| `app/src/main/cpp/raw_bridge.cpp` | 108–180 | `make_mem_image` 早于 `recycle`、196MB 常驻到 `closeLinear` |
| AndroidX `ProduceState.kt`（官方源码） | 全文件 | 所有重载均为 `remember { mutableStateOf(initialValue) }` + `LaunchedEffect(keys)` ⇒ 换键**不重置 value** |

## 附录 B · 与 qwen 判级差异速查

| 项 | qwen | hy4 | 理由 |
|---|---|---|---|
| A8 陈旧直方图 | P1（称「最需立刻修」） | **P0** | 会写用户没做过的参数并进撤销栈；且每帧都滞后、可能乱序覆盖 |
| K2/K3 系留取最老 + 队首阻塞 | P0 | **P1** | 「永不推进」「排空整卡」不成立；真缺陷（不播种基线、坏对象占位）仍在 |
| M-1 EXIF/方向 | P0 | P0（附条件说明） | 元数据丢失无条件，方向错条件性 |
| K1 ARW 必失败 | P0 | P0 | 一手确认 |
| E1 仪表盘数字必错 | P1 | **P1（改定性）** | 撞码常量是死常量；真问题是 u16 硬解与 datatype 未查 |
| §3.2 规范真值「四方不一致」 | P1 | **P1（缩小范围）** | 只有 `Spacing.kt` 指向旧文档；§2.6 已订正 |
| §9.6 紧凑化未做 / 账本旧 4 天 | P1(G2) | **不成立** | 章节错置 + 无时间证据 |
| AppSettings 迁移测试缺失 | P2 | **不成立** | 空迁移阶段的未来规格，非已声称保障 |
| U5 对象层 LUT 无入口 | P1(U5) | **不成立，改报文案矛盾** | 有隐蔽入口，`ParamPanel:392` Hint 否认代码行为 |
| E3(a) 未验 bulk IN/OUT | P1 | **不成立** | `PtpTransport:502–509` 两个 elvis 已保证 |
| B1「250MB」 | P1 | P1（口径修正） | 应为 261.9MB 十进制 / 249.8MiB |
| B4 ≥458MB | P1 | P1（补「持续驻留」） | 196MB 贯穿导出，非瞬时峰值 |

---

*本文为只读审计。未修改任何代码、构建脚本、CI 或文档（除新建本文）；未 push、未 fetch、未触发 CI、未运行 Gradle。*
