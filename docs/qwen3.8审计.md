# PixelCake 项目审计报告

> 对象：`hifn123p/pixel-cake-app` @ `8784274`（`versionName 0.6.3` / `versionCode 18`，18 个 tag，工作树干净）
> 日期：2026-11-06 · 视角：产品经理 + 架构师 · 性质：**只读审计，零代码改动**
>
> ⚠️ **本文经过四轮：初版 + 三轮复核（覆盖面 / 反证 / 数字自洽）。** 复核累计有 13 条断言被推翻或大幅校准（覆盖面轮 11 条、反证轮 2 条），其中含一条曾被列为 P0 的"主线程忙轮询"——实际不存在。
> 全部纠正与「为什么会错」记在 **附录 B**，那是这份报告里对其他会话最有复用价值的部分。

---

## 0. 方法与证据等级

本机无构建环境 ⇒ **审计工具只有读码**。为了不让"读了行号"冒充"理解了机制"，本文遵守两条规则：

1. 每个进结论的位置，都用 `Read`/`sed` 看过**前后至少 15 行**，而不是只看 grep 命中行；
2. 每条结论标证据等级：

| 标 | 含义 |
|---|---|
| ★ | 一手读码：看过完整函数/分支，机制能自己复述 |
| ◐ | 一手核对命中行 + 依赖文档自陈或转述，机制未逐行追 |
| ○ | 外部数据（非本仓库） |

**范围**：87 个 `.kt`（19,765 行）+ `raw_bridge.cpp` + 39 个测试文件（294 个 `@Test`，`app/src/androidTest` 不存在 ★）+ 8 份 md 文档（4,945 行）+ 两份 HTML 规范 + CI workflow + 构建脚本。

**没有做的**：没编译、没跑测试、没装机。所有内存数字是按代码常量折算的**量级估算**；所有"用户会看到 X"的表述是从代码路径**推导**的，不是观察到的。

---

## 1. 结论摘要

**一句话**：P1（手机端修图）的技术路线选对了、执行纪律罕见地好，可以对外用；但**它有一次没被真机验证过**，并且**照片方向/元数据这一整层从未进入设计视野**——这条比审计里任何内存问题都更早会被客户撞到。P2（相机系留）当前不是"有 bug"，而是**四处各自不正确叠加成一个不可信的功能**，其中两处（ARW 必失败、取最老的照片）意味着**这个功能从未按设计意图成功运行过一次**。

| 维度 | 分 | 承重理由 |
|---|---|---|
| ① 可行性与技术路线 | **7.5** | CPU 分带 + LibRaw 真解码 + LiteRT 三段都正确，降级契约成体系（★）。扣：P2 路线选浅一层（轮询冒充系留）、minSdk 36 把可装面压到 25.4% ○、LGPL 静态链接义务未闭 |
| ② UI 与功能一致性 | **4.5** | 分类学与作用域语义自洽（★），但 **6 处"能操作但无效果"**（其中 2 处引擎按设计就该如此、UI 没跟上）+ 规范真值三方不一致 + 方向/EXIF 零处理；再扣 **T4 无障碍整层未建立（全 ui/ 仅 5 处语义声明 ★）** 与 **T3 横屏零适配 ★** |
| ③ 架构与常规性问题 | **5.5** | 预览渲染路径的并发纪律**一手确认零违反**（★，这点比第一版评价高）。扣：直方图缺取消检查点、**A8 陈旧直方图喂参数写入**、sRGB 导出两份全幅、原生 ≥458MB 下界、JPEG 导出不可取消 + 幽灵文件、系留异常被吞；再扣 **T1 参数栈序列化不存在** 与 **T2 无导航层/无 ViewModel** |
| ④ 可交付性 | **3.5** | 自用可以；对外缺方向/元数据、缺持久化、缺批量、验收清单自 v0.4.6 **一条未闭环**（◐）、**发布包开 R8 而验收只装 debug 包，且 debug 侧 native 是 -O0**（★ T5）、无序列化格式（T1）、无导航层（T2）、无障碍与横屏（T3/T4） |

**如果只做四件事**（按性价比排）：① **A8**——给直方图加"属于哪张图"的标记，1 处改动挡住"用上一张照片的分布写参数"（★，本文 ROI 最高的一条）；② **M-1** 方向与 EXIF（一次改动覆盖所有导入导出）；③ 系留的四处不正确逐个消掉或整块降级改名（§4.4）；④ 直方图生产者的取消检查点 + JPEG 导出取消 + `claimInterface` 原因上屏（§4.3 / §4.6 E3）。

---

## 2. 可行性与技术路线（问题 ①）

### 2.1 端侧 CPU 分带管线 —— 成立，且核心纪律一手确认

非破坏参数栈 + 预览跑代理图 + 导出重放同一栈 + 分带逐段上色。架构上它同时买到三件事：滑块成本可控、导出精度不被预览绑死、"改滑块绝不重新解 RAW"（`DEV_PLAN.md` §3.1）。

**一手确认做对了的部分**（这是本项目最硬的技术资产）：

- `renderIntoSrgb` 全程只持有一条 `IntArray(BAND_ROWS*w)`，`getPixels`→算→`setPixels` 每带闭环（`EditEngine.kt:110-129` ★）；RAW 侧更彻底：16-bit 线性母版**始终留在原生侧**，Kotlin 每次只拉 32 行（★）。**逐像素上色链没有任何一处物化全幅像素数组**——这句话我逐条查过分配点。
- 双缓冲交换在**同一个非挂起段**里完成：`rendered = target` / `renderSpare = previous`，并且代码注释把"为什么这里读 `rendered` 安全"论证清楚了（`MainActivity.kt:490-508` ★）。**正在显示的那块位图确实只读。**
- 昂贵渲染配的是 `.conflate().collect`（不是 `collectLatest`），`RenderBatch(completed, epoch)` 两个正交量分开，提交守卫在 `!completed || epoch != imageEpoch` 上（★）；`CancellationException` 在 `:442-444`、`:555-556` 显式重抛（★）。
- 阶段"中性 ⇒ 整段跳过"，且**两处判据互相钉住**：`DetailPass.isNeutral` 的 KDoc 明写"这条判据必须与 `EditEngine` 是否调用本阶段一致"（★）。
- `LUT id` 在**构造期**收敛成 Int opcode，理由是"逐像素做 `String.equals` 在 33MP 导出约 6600 万次比较"（`PixelProgram.kt:123-137` ★）。

**需要修正的口径**：项目记录的"一帧 100~300ms"（`ENGINEERING_NOTES.md:242`、`UI_DESIGN.md:611` ★）出现在**根因分析**段落里，是解释"为什么 `collectLatest` 吃光 CPU"的量级说明，**没有标注测量条件**。好消息是逐帧耗时已经进了代码：`RenderBatch.ms` + `DebugLog.d("render done", {ms,stamp,layers})`（`MainActivity.kt:526-536` ★）。⇒ 所以"是否真的 100~300ms"现在是**零成本可回答**的问题，而文档里用未标口径的估计值支撑了一条架构结论，这是应该补的一次真机读数。

**替代路线评估**：AGSL/RenderEffect 能把上色链做到 <16ms/帧，但邻域算子（磨皮/锐化/纹理）与蒙版在 AGSL 上要么做不了要么精度改变，而"预览↔导出逐位一致性"这套测试体系（`OBJECT_TONE_DESIGN.md` §12）会整块作废。⇒ **正确解是混合**：纯 LUT 类段（曝光/曲线/白平衡/分级/LUT）走 AGSL 预览，邻域与蒙版仍走 CPU。**但不该现在做**（§6 阶段 3）。

### 2.2 ARW / LibRaw —— 算法侧是一手确认的最优，内存模型有一处**下界已证实**

`raw_bridge.cpp` 的解码顺序我逐行读过 ★：`open_file → unpack() → dcraw_process() → dcraw_make_mem_image() → 取 cam_mul → rp->recycle()`。

- **正确性（这是全仓工程质量最高的一段）**：`use_camera_wb=1 / output_bps=16 / gamm={1,1} / no_auto_bright=1` ⇒ 输出是**相机原生白平衡 + 相机 CFA（导出 AHD、代理 half_size）真 16-bit 线性**，不是中性近似；白平衡乘子回传给参数栈。四条退出路径都配 `dcraw_clear_mem`，异常不跨 JNI，`ReleaseStringUTFChars` 五个 return 都有。Kotlin 侧 `getAndSet(0)` + `ioLock` 关死 use-after-free（`RawLinearSource.kt:87-93` ★）。
- **内存**：因为 `recycle()` 排在 `dcraw_make_mem_image()` **之后**（`:118` vs `:144`），**4 通道 ushort 结果（33MP≈262MB）与 3 通道导出缓冲（≈196MB）必然同时存在 ⇒ 原生峰值下界 ≈458MB**。这是代码顺序决定的，不是估计。
  ⚠️ 第一版我写的"≈520MB"把 `rawdata`(≈66MB) 也加了进去 —— **那一条我没核实**：LibRaw 在 `dcraw_process` 阶段是否已释放 rawdata 取决于其内部与 `user_keep_raw`，而本机子模块未 checkout（`git submodule status` 显示 `-dde798dd…` ★），无源码可查。⇒ 结论只到"≥458MB 已证实，是否更高未核实"。
- `MAX_READ_ALL_BYTES = 64MB`（`RawLinearSource.kt:97`）守的是 `readAll`，管不到 `openLinear` ★。⇒ 原生侧**没有任何预算**。

### 2.3 端侧 ML —— 一手确认为最优解

`NNAPI 已废弃 → LiteRT CompiledModel + GPU→CPU 级联`；直接用 MediaPipe 现成 `selfie_multiclass_256x256`（16MB，Apache-2.0，已核实随包 ★）而**不做 ONNX→TFLite 转换**，把转换风险整段消掉；`noCompress += "tflite"` 走 mmap；降级收口 `createOrNull() → null` + OOM 计数 + 会话禁用，并在设置页**可见**（`SettingsScreen.kt:427-437` ★：显示 `oom/threshold` 与"本会话已关闭/已永久关闭"）。缓存 `@Volatile` + `@Synchronized` + 写后成功顺序，无竞态（◐，来自专题走查后抽查）。这一段没有可挑的。

`ONDEVICE_MODEL_PLAN.md` 的 M1~M7 也务实（明确不做 GFPGAN/SD）。唯一要在落地前量的：**M1/M2 走 MediaPipe Tasks、现有两模型走 LiteRT，双 runtime 并存的进程驻留没有预算过**（文档 §5 风险①自认）。

### 2.4 USB 相机直连（P2）—— 路线选浅了一层；实现有四处各自不正确

**路线层**（★ = 一手确认协议面）：实现的是**标准 PTP 的只读文件访问**（GetDeviceInfo/OpenSession/GetStorageIDs/GetObjectHandles/GetObjectInfo/GetObject），协议写得干净（小端、12 字节容器、按 opcode 判定是否有数据阶段、`0xFFFFFFFF` 当"长度未知"而不是 4GB）。但：

- 全仓零 `CaptureImage`/`SendObject`、零 `0x9xxx` 厂商扩展、零 PTPD/SCSI；传输层只开 bulk IN/OUT（◐，专题走查逐行核对过端点选择）。⇒ **"边拍边看"在实现上只能是每 3 秒轮询存储卡**（`CameraPanel.kt:130-131` ★ 我亲眼看到那个 `delay(3000)` 循环）。
- `UI_DESIGN.md` §11 的 J′/K/L/L′ 四屏标 ❌；`PHASE_DESIGN_HISTORY.md` 对 CRSDK 的判定是"仅当『边拍边看』必须时评估"、"D4 边拍边看：本阶段只评估不实现"（★）。而 `DEV_PLAN.md:41` 写"PoC-1~5 全部落地"、§2.1 给 A7C2 直连打 ✅（★）。⇒ **矩阵里的 ✅ 是打早了**：PoC 的 ✅ 只能表示"协议自洽 + 单测绿"，不表示可交付。
- 机身模式：UI 教用户设成 MTP（`CameraPanel.kt:182` ★），而 MTP 是系统 MediaProvider 会 claim 接口的模式；`claimInterface` 失败只写日志（◐）。代码里认得 PC-Remote（class `0xFF`）但没有把它变成用户指引（◐）。**这条我判定为"代码修不了的产品约束"**，但注意：**至今没有任何一次真机成功会话的证据**，所以它同样可能只是"没人试过"。

**实现层四处**（全部 ★，逐行读过，见 §4.4 的 A1~A4）：ARW 走一条设计上必返 `null` 的解码分支；取的是**最老的三个对象**且首次不播种基线；队首遇到非照片对象会**永久阻塞**；异常一律被 `catch (_: Exception)` 吞掉。

**判据与姿态**：底下"下载→落盘→套预设→导出"的管道本身是好的（`CameraBatch` 用对了 `renderLinearFile` + `finally` 复位 + 导完即删）。**该改的是名字和承诺**，不是重写。

### 2.5 P3（NAS）—— 现在无可审；但有一条约束已经能判

`未启动`。§3.6 那条"RAW 一次落盘 + 代理图 + 参数栈回传，绝不每步回传整张 RAW（35–57MB ≈ 10–12s/张）"（★文档）是先算带宽再定架构的正确姿势。要记住的约束：`DEV_PLAN` §6.3 自陈 targetSdk 37 会把 `ACCESS_LOCAL_NETWORK` 变运行时权限（◐）—— P3 的地基直接在这条上。

### 2.6 minSdk 36 的市场面（PM 层最贵的一条）

`minSdk = 36`（Android 16）★。截至 2026-10 的官方分布：**Android 16 占活跃设备 25.4%**，Android 14+ 合计 56.4% ○。⇒ 今天只有约四分之一的手机能装（10 个月前它还是 7.5% ○，曲线很陡）。

而 `DEV_PLAN` §6.1 自己写了"**无 API>31 强依赖**，将来下调成本低"，风险表把它评为"低"——**这两处互相矛盾**：对一个打算发小红书引流的 App，砍掉四分之三的设备不是低风险。降到 34（覆盖 71.8% ○）或 33（83.6% ○）的成本是把编译期 API 用法过一遍 lint `NewApi`。**它的产品价值高于本审计里 90% 的代码修复项**，但只在决定对外发布时才需要做 ⇒ 应该变成一个**显式、有日期的决策**，而不是现状默认。

### 2.7 「CI 是唯一编译器」 —— 有效，且被证明有效；但覆盖边界要说清

`Github_CI.md` ★：v0.6.2→0.6.3 这轮 10 次 run，失败绝大多数是纯编译期错误（缺 import、前向引用、补丁插错作用域）。CI 现在是**四道真闸门**：`assembleDebug` + `testDebugUnitTest` + `lintDebug`（去掉 `continue-on-error`）+ 签名 release；NDK 真编译（`submodules: recursive` + toolchain）。加上本地两道静态闸门（括号配平 + import/调用点核对）。

**这套补偿体系是有效的**——本审计发现的问题**没有一条是"编译不过"类**，说明闸门守住了它能守的层。它的边界也很清楚：**CI 证明不了任何需要设备的事**（USB、NDK 运行时、Compose 交互、位图生命周期），于是自然长出"待真机验收清单"；而 `ENGINEERING_NOTES` §5.6 那份清单从 v0.4.6 到今天**一条没划掉**（★）。这是流程缺口，不是技术缺口。

另外两个一手发现的流程风险：`git submodule status` 显示两个子模块**本机未 checkout**（★）⇒ 新克隆忘带 `--init --recursive` 就 CMake 失败，而 CI 一直绿 ⇒ **风险只落在人身上**；`.gitmodules` 给 LibRaw 钉的是 `branch = master`（浮动 ◐）。

### 2.8 许可 —— 一条已登记、未关闭的雷

LibRaw 是 LGPL-2.1，**闭源 APK 静态链接触发"可重新链接"义务**（需随包提供 object 形式或允许重链接的构建方式 + 构建脚本）。`DEV_PLAN` §8 登记了这条并写"分发前需复核"（★）——**目前还没有对应产物**。发布前必闭。

### 2.9 竞品对照 —— 功能矩阵与"像素蛋糕"的价值点错位（PM 最重要的一段）

像素蛋糕（商业软件）卖的不是"81 个参数"，是**影楼工作流**：一次设定 → 批量套全店 → 自动磨皮液化 → 交付。本项目当前形态是**专业向手工精修**。错位在三处，全部一手可证：

- 手工侧确实做到了专业级（81 项参数、44 套预设、8 个对象作用域图层、真 16-bit 线性 RAW）——**这是超过 Snapseed 的自由度**，也是本项目最独特的资产。
- 批量侧只有 `camera/CameraBatch.kt` 这一个数据层，UI 缺失（K/L 屏 ❌），而**唯一能触达它的入口是系留/相机面板**，即 §2.4 那个不可信的功能。⇒ **"批量"今天没有一条端到端可用路径** ★。
- 交付侧缺三样最关键的：**没有项目/会话概念**（全 UI 只有 1 处 `rememberSaveable`，在 `ParamPanel`；无 Room，★）；**没有另存预设**（预设是内置常量，无持久化层 ◐）；**没有跨照片同步参数**（影楼刚需第一）。

⇒ **技术路线没错，参照系选错了。** 要么把定位说清楚（"专业向手工精修 + 真 RAW 宽容度 + 全本地"——这个定位在中文市场有真空，今天是**真的**，所以可以现在就写进 README 的一句话定位），要么把 P2/P3 的投入转向"相册批量 + 项目 + 另存预设"。这个选择必须人来定（§7-Q1）。

---

## 3. UI 合理性与功能一致性（问题 ②）

### 3.1 结构现状：分类学与引擎语义自洽（★，一手确认）

Rail 六工具在 `Rail.kt:254-263`（人像/明暗/颜色/曲线/细节/预设）★。每个分类带一个 `scopeMode`：人像/预设 = `NotApplicable`、明暗/颜色/曲线 = `Layerable`、细节 = `WholeImageOnly`（`EditorToolbar.kt`，◐+引擎侧 ★）。

**这是本项目 UI 设计最成熟的一处决策**，因为它和 `buildLayerStack` 的真实行为对齐：`withoutWholeImageStages()` 会把逐层无效的段剥掉（`ObjectLayers.kt:293-301` ◐），UI 用 `WholeImageStageNotice` 说明 ★。一级 ≤7 的数量判据守住了（312dp/6 = 52dp）。

### 3.2 规范真值三方不一致 —— 最便宜的 P1，也是最容易复发的坑

`ENGINEERING_NOTES` §9.1 已经指出问题但**没关闭**（★）：`UI3.0.html` 是当前真值（`:root` + CSS）★，`UI_DESIGN.md` §4.0.8/§4.0.9 是旧值（24/16/14/12/11、圆角 14/22/28/40、滑块 48dp 热区）★，而 `Spacing/Color/Radius/Type.kt` 的 KDoc **仍全部指向旧文档**（◐），`ENGINEERING_NOTES` §2.6 也仍写旧圆角 ★。

外加两条命名陷阱（★，一手核过 `<title>`）：`UI2.0.html` 的标题是「**UI 3.0「Studio」**设计稿」，`UI3.0.html` 的标题是「UI「Aperture」**概念稿**」。⇒ `UI_DESIGN.md` §10 与 `DEV_PLAN.md` §9 把前者称"UI 2.0 存档"是错的（`README.md` 的说法才对）；而两份 HTML 一份自称概念稿、文档却称它"唯一真值"。

**还有一条本审计新发现**：`ENGINEERING_NOTES` §9.6 把"先把 `ParamSlider` 改成紧凑形态"列为 Deck 高度合规的**前置未做项**；但代码**已经做了**——`ParamSlider.kt:148-158` 的注释自己算了一遍紧凑化后的账（≈46dp/条，3 滑块组 ≈232dp，4 滑块组"仍走内容区滚动兜底"）★。⇒ **账本比代码旧**，而且"永不滚动"的违背已经是**写在代码里的刻意取舍**，不是遗漏。这类"文档滞后于代码"和"文档超前于代码（§2.4 的 ✅）"两个方向都要治。

修法（半小时内可关闭）：四个 theme 文件的 KDoc 改指 `UI3.0.html` + §9.1 那张最小真值集；`UI_DESIGN.md` §4.0.8/§4.0.9 顶部标注"数值已被 UI 3.0 覆盖，**除 48dp 热区条目**"；删 5 处 `ui_preview.html` 悬空引用（★：`DEV_PLAN:367`、`UI_DESIGN:1132`、`OBJECT_TONE_DESIGN:446/479`、`TONING_DESIGN:561`，文件已在本 HEAD 删除）；`DEV_PLAN` §0/§10 的 v0.5.0/versionCode 13 更新到 0.6.3/18（★）；§2.1 的 P2 ✅ 改成 🔜。

### 3.3 死区：6 处"能操作但无效果"

**先说清楚一件事**：`EditParams` 全部 37 个字段都有真实消费者（逐像素 23 项 `PixelProgram.kt:170-281,346-568`、邻域 10 项 `DetailPass.kt:324-386`，◐专题走查逐函数核对），量程与重置默认值和模型字面量对齐（★抽查 3 处）。「宁可不给、不给假的」这条纪律**确实执行了**——"细部位美容"假面板已删除，13+13 个 `BeautyParams`/`BodyPartBeautyParams` 死字段由 `EditLerp.kt:18` 明写"不参与"（◐）。

| # | 现象 | 一手证据 | 定性 |
|---|---|---|---|
| **U1** | **滤镜强度默认显示 80%，但没选 LUT 时拖动零变化** | `EditModel.kt:186-187`（`lutId="none"`, `lutIntensity=0.8f`）+ `PixelProgram.kt:130-137`（`else -> 0`）★ | **真缺陷**：默认值与前提状态矛盾。修法：未选 LUT 时 `lutIntensity` 归 0 并禁用滑块 |
| **U2** | **修饰量在强度为 0 时不生效**（锐化半径/细节/蒙版、降噪两个"细节"、暗角起点/过渡/圆度、颗粒尺寸/对比） | `DetailPass.kt:110-120` ★ 的 KDoc **明说这是设计**："强度为 0 时改它们不产生任何像素变化，因此不该让整段生效" | **不是缺陷，是 UI 没跟上语义**：面板不禁用、不置灰 ⇒ 用户读到的是"坏了"。修法：强度=0 时灰态 + 一句 hint |
| **U3** | **彩色分级的色相 chip 在该区 sat=lum=0 时无效果** | `EditModel.kt:70-73` ★（`isNeutral = sat == 0f && lum == 0f`，KDoc 明写"色相不参与判断"） | 同 U2：**引擎语义正确，UI 缺告知** |
| **U4** | **空作用域照样能选、能调、还亮"已调整"标记** | `ObjectMasks.maskFor` KDoc ★"永不返回 null；某作用域在本图为空时得到**合法的全 0 蒙版**" + `buildLayerStack` 的过滤表 ★（`maskOf → null` 才 `continue`，全 0 蒙版**不跳过**）+ `ParamPanel.kt:479-486` ★（chip 的 `itemEnabled` 只看 `available`=模型可用，**不看该对象是否存在**；`label` 用 `adjusted(sc)` 加" •"） | **本审计最该优先修的 UI 问题**：风光片上除"背景"外 7 个作用域都能进入、拖半天一个像素不变、且界面没有任何东西解释为什么。修法是加一个 `isEmpty`（网格求和即可，O(1) 缓存）→ chip 置灰 + hint |
| **U5** | **对象层的 LUT 永不可达** | LUT 的唯一 UI 入口在"预设"，而预设有 `scopeMode = NotApplicable` ⇒ 没有作用域行；而 `ObjectLayers.kt:205`（◐）不剥离 `lutOp` ⇒ 引擎支持逐层 LUT | **有效果无入口**。"按对象套滤镜"是专业功能，缺的只是把 LUT chip 也在作用域层露出来 |
| **U6** | **「重置」不可达** | `UI_DESIGN` §11.1 自陈：`onReset` 已从顶栏移除、参数保留在签名里"未接线，不要静默删除"（★文档），实现体在 `MainActivity`（清画笔/清层）无调用点（◐）；文档同时承认历史面板"起始"与它**语义不等价**（★） | 低频动作，但"有实现、有注释、无入口"是典型的断链 |

另外：`NeutralGrayParams.threshold = 24` 被 `NeutralGray` 真实使用、UI 无控件（★：`grep threshold` 在 `ui/` 只命中设置页的 OOM 阈值）；`PresetCategory` 每个预设都带、**UI 零使用**（★ grep 证实 `ui/` 里 `category` 全是 `EditorCategory`）⇒ 44 套预设无分类导航。（第一版我写的 `Presets.byCategory` **这个 API 不存在**，见附录 B。）

### 3.4 作用域诚实性

`细节` 显示作用域行但整组被剥空，UI 给了 `WholeImageStageNotice` ——**方向正确**，三处要修（◐，来自专题精读 ParamPanel 后抽查）：二级 chips（锐化/降噪/暗角/质感/颗粒）在说明态**仍照常可点**，点进去 5 格是同一段说明 ⇒ 告知该放在 chip 层而不是选中之后；那段说明**引用了已删除的"效果"分类**（`ParamPanel.kt:589` ◐）——文档腐化已经漏进 UI 文案；`LayerHeader`（层强度）也出现在"人像"面板顶部，而人像是 `NotApplicable` ⇒ 呈现一个与内容无关的滑块。

还有一条**注释里写着"必须做"而 UI 没有**的：`ObjectScope.Accessories` 的 KDoc 明令"必须在面板里挂一句 Hint 说明实际覆盖范围"（◐），全仓"配饰"只出现在注释里（★）。

模型侧能力是够的：6 类全被映射（`SkinMaskPostProcess.classIndexOf`，穷尽 `when` ◐），8 作用域 = 6 原生 + 2 并集（★），所以 人物/皮肤/头发/衣物/配饰**都算得出来**；不一致的只是"空结果时的告知"（=U4）。

### 3.5 无障碍、热区与可发现性

| 项 | 一手事实 | 我的判断 |
|---|---|---|
| 滑块热区 **26dp** | `ParamSlider.kt:153-158,197` ★：显式 `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)` + `height(26.dp)`，注释写明**这是刻意的**并引 Snapseed/Lightroom 的 24–30dp 口径，且"4 滑块组仍走滚动兜底" | 代码与 `UI_DESIGN` §4.0.8 的"保留 48dp 热区"对撞，`ENGINEERING_NOTES` §9.6 给了三方案并推荐 **B(36dp)**。⇒ 这是**已知且已算过的手感取舍**，不是 bug；但它是**无障碍合规风险**（TalkBack 命中率、WCAG 目标尺寸），需要真机定夺而不是推理。我同意 §9.6 纪律 7：UI3.0 的 `24px` 是**视觉**口径，不是热区许可 |
| 顶栏图标 34dp / Deck 手柄 20dp | ◐（专题度量） | 同上，均 <48dp |
| 读屏语义 | 顶栏已补 `.semantics{contentDescription}`（★文档 §11.1）、Rail 用 `selectable(Role.Tab)`（★文档 §9.2）；准星拾取按钮与直方图条点击区无语义（◐） | 局部补齐，未成体系 |
| 语义色对比度 | `WsOk/WsWarn/WsErr/WsInfo` 已接上，旧色在深底 2.87~3.85:1 不达标已按场景分开（★文档 §9.5 附实测对比度表） | 这里换出来的两条纪律（语义色按「场景×生效主题」二维选、新增 token 要当场接上）是**本仓库最有普适价值的两条** |
| 可发现性 | `ParamPanel.kt:486-491` ★：作用域 9 项走 `singleRow + compact`，注释自己承认"末尾几项靠横滑（低频决策，横滑比『先缩小预览』便宜）" | 权衡有账、但**没有任何截断提示**（无渐隐、无计数）⇒ 用户不知道还有 5 项。加提示成本极低 |
| 设备特性 | `usb.host required=false` ★；无相机权限（Photo Picker，正确 ★）；HEIF 靠平台解码、无 `hasSystemFeature` 探测（◐） | 合理 |

---

## 4. 架构与常规性问题（问题 ③）

### 4.1 分层

包结构语义清晰，`core` 大量零-Android 纯函数（这是 294 个 JVM 单测能存在的原因 ★）。两个结构性问题：

- **`MainActivity` 是 1,325 行的上帝对象**（★行数），且无 ViewModel/Hilt/Room（`DEV_PLAN` §5.2 明记未引入 ★）。状态靠 `remember`，用 manifest 的 `configChanges` 覆盖方向/尺寸/密度来回避重建 ★。⇒ **进程被杀 = 正在修的稿全丢**，无恢复路径；也没有"作品/项目"这个数据对象。注意一个反面证据说明团队清楚自己在做什么：`compareBase` 的同步复制**刻意不切线程**，理由写得很清楚——"切线程引入挂起点，若在复制期间被取消，`compareBase` 永远是 null ⇒『按住看原图』静默退化"（`MainActivity.kt:512-524` ★）。**同一份文件里既有这种精细的并发推理，又完全没有状态持久化**，这个反差本身就是架构结论。
- **架构文档与代码分叉**：`DEV_PLAN` §5.2 的架构树里**没有 `camera/` 这个包**（11 个文件 ★，本项目的第二大块），而它列"规划未建"的 `ui/export/`、`ui/gallery/` 实际已由 `ExportSheet.kt` / `HomeScreen.kt` 承担（★文件存在）。

### 4.2 内存（一手核对分配点后的真实账）

前提：`AndroidManifest.xml` **未开 `largeHeap`**（★）⇒ Java 堆上限即 `heapgrowthlimit`；单份 33MP ARGB_8888 = 125MB（7008×4672×4）；`memorySnapshot()` 已打进导出前后两个点（★）。

| # | 一手位置 | 事实 | 量级 | 判级 |
|---|---|---|---|---|
| **B1** | `MainActivity.kt:879-889` + `:916` + `:941-944` ★ | JPEG/HEIF 全幅导出：`fullBase`（解码得到）与 `fullTarget`（`createBitmap`）**同时活**，跨过 `renderIntoSrgb` 与 `Exporter.export`，在 `finally` 一起回收 | 33MP JPEG 时 **250MB**；手机自拍的 4032×3024 只 ≈100MB | 🟠 P1 |
| **B2** | `Mask.kt:62` `RasterMask.fromStrokes` = `FloatArray(w*h)` ← `RetouchScale.kt:56-69`（`strokes.isEmpty() → null`）← `buildSkinMask` @ `MainActivity.kt:382/820/901` ★ | 画过笔刷后，**每次预览渲染都新建一份全幅 Float 蒙版**（预览档 ≈11MB/帧，导出档 **131MB**）；`resampleTo` 是第二个全幅分配点 ★。**这个 131MB 是项目自己的代码写的**：`ObjectLayers.kt:144-145` ★ 明令"**绝不**要把画笔栅格 `RasterMask` 接进对象层——它的 `resampleTo` 会真的分配 `FloatArray(w*h)`，33MP 下是 131MB" ⇒ **代价在对象层那一侧被想清楚了，在预览/导出这一侧却照旧发生** | 峰值 131MB + 每帧 11MB 纯 GC 压力 | 🟠 P1 |
| **B3** | `RetouchLayer.kt:266-277` + `Beauty.kt:91` ★ | 液化：`buf = IntArray(spanRows*w)` 与 `out = IntArray(bw*bh)` 同时在。**`spanRows`/bbox 来自 `maskRowSpan`（脸的包围盒 ± 锚点位移）**，不是整幅 | 典型 ≈46+12MB；整幅（125MB）只是上界 | 🟡 P2（第一版误判为 250MB/HIGH，见附录 B） |
| **B3b** | `RetouchLayer.kt:282-299` ★ + `Beauty.kt:78-88` ★ | `maskRowSpan` 对**每一行**做 `w` 次 `mask.sample`（★ 读过全函数，无早停），`Beauty` 的 bbox 扫描再来一遍 ⇒ **每次液化至少 2 次全幅采样**（`centroid` 那第三遍我没逐行核，故不写 3×） | 无分配，但纯 CPU 时间直接加在导出耗时上 | 🟡 P2 |
| **B4** | `raw_bridge.cpp:118-144` ★ | `recycle()` 在 `make_mem_image()` 之后 ⇒ 4 通道(262MB)+3 通道(196MB)**必然共存** | 原生 **≥458MB 已证实**；rawdata 是否叠加未核实（★子模块未 checkout） | 🟠 P1（12GB 旗舰不构成 P0；风险在与 B1 叠加在 6–8GB 档） |
| **B5** | `EditorScreen.kt:441-465` ★ | 直方图每次发射：`IntArray(w*h)` + 3×`ByteArray(n)` ≈ **19MB**，键含 `renderVersion` ⇒ **每个完成帧重启一次**；且无取消检查点（详见 A2） | 19MB/帧 churn ⇒ 拖滑块时的 GC 抖动与 CPU 抢占 | 🟠 P1 |
| **B6** | `NeutralGray.kt:194-195` ◐ | `boxBlur` 每带新建两个窗口大小 IntArray；可复用版本在 `:129-131` 存在但 `RetouchLayer` 没走它 | 33MP 导出 ~370MB **churn**（峰值只 ~30MB） | 🟡 P2 |
| **B7** | `MainActivity.kt:478-508`、`:357`、`:524` ★ | 预览常驻 `rendered` + `renderSpare` + `compareBase` + 源图 + `LinearImage`(16MB) ≈ **58MB** | **这是双缓冲的必然代价，故意的，别改** | ✅ |

**档位策略没落地成代码（本审计新发现 ★）**：`resolutionProfile()` 只提供 `fullResLongEdge`/`proxyLongEdge` 两个数字，全仓 3 个调用点；`DEV_PLAN` §6.2 给中端 6–8GB 档的三条缓解（**单 Bitmap 复用、禁并行多份、导出即 recycle**）**在代码里没有对应实现**，而中端照样拿 7008。⇒ 所以 B1 的准确定性是"**文档承诺了、代码没做**"，比"算子太重"更可执行：把那三条按档位实装即可。

**结论**：`DEV_PLAN` §6.2 的 F05「33MP 导出峰值降到 ~130MB 量级、≥8GB 安全」**只对逐像素上色链成立**（★我核到那条链确实全程无全幅像素数组），对 retouch/笔刷/原生三侧不成立。

### 4.3 并发与取消：预览路径一手确认干净，新代码里八处缺口（A1~A8）

**先给公道话（★一手确认，且比第一版的评价更高）**：`conflate().collect` + `RenderBatch(completed, epoch)` + 提交守卫 + 非对称回收规则，全部正确；`imageEpoch` 与 `completed` 两个正交量分得很清；两处 `CancellationException` 显式重抛。**两次全量审查换来的纪律（`ENGINEERING_NOTES` §3.2/§3.4）在这条路径上一条没违反。**

| # | 一手位置 | 机制 | 症状 |
|---|---|---|---|
| **A1** | `CameraPanel.kt:130-163` ★ | `delay(3000)` 在 `try` **外面**（第一版据此推的"忙循环 ANR"是错的，见附录 B）。真实缺陷：`:162` 的 `catch (_: Exception) { }` **吞掉一切** USB/PTP/IO 失败，`tetherSnapshot` 的 `:76` 同样 ★ | 系留出任何问题时界面**完全没有线索**（连"失败"都不写），只能去翻日志 |
| **A2** | `EditorScreen.kt:441-465` + `MainActivity.kt:500-508` ★ | 双缓冲交换把**旧前台位图降级成下一批的写入目标** ★。直方图生产者键在 `(renderVersion, rendered)`，被取代时**只在 `produceState` 边界取消**——而 `getPixels` + `for (i in 0 until n)` **没有任何取消检查点** ⇒ 它继续跑完，读的正是下一批正在写的块。另外 `runCatching` 把"读被回收位图"的异常（`getPixels` 会抛，Android 有检查 ★）吞成 `null` | ① **混帧的直方图**（与画面不同代，甚至一帧里两个形状）；② 19MB + 全量统计**白烧一遍**，和真渲染抢 CPU/GC ⇒ 拖滑块掉帧；③ 退出编辑器时**直方图静默消失**。这条违反的是 `MainActivity.kt:253-256` 自己立的不变式"位图像素的持有者进同一把锁" ★ |
| **A3** | `EditEngine.kt:110-129` vs `:172` ★ + `MainActivity.kt:855-859` vs `:916` ★ | **RAW 主路径可取消**（`renderLinearFile` 的 progress lambda 返回 `!exportCancelled.get()` ★）。缺取消点的只有 sRGB/JPEG 路径：`renderIntoSrgb` 根本没有取消入口 | 取消 JPEG 导出要等整幅渲染 + 编码跑完；期间点取消 ⇒ 文件**照样进相册**而 UI 显示"已取消导出"（★ 判据在 `:972-976`，`exportedUri != null` 但 `cancelled` ⇒ 不发回执、状态写"已取消"）⇒ **幽灵文件** |
| **A4** | `MainActivity.kt:1046-1049` ◐ | `scope.launch { renderMutex.withLock { recycle() } }`：**无竞争时 `withLock` 不挂起** ⇒ 可能在应用 `editorOpen=false` 的那次重组之前同步回收，而 `asImageBitmap` 包装还在（`EditorScreen.kt:375` ★ 确实存在这个 remember） | 低概率的绘制已回收位图；`Backdrop.kt` 有 `isRecycled` 防护，绘制路径未防护（◐） |
| **A5** | `CameraBatch.kt`（`Dispatchers.IO` 内回调 `onProgress` 写 `mutableStateOf`）◐；`CameraPanel.kt:120` `tetherParams` 同类 | 后台线程写 Compose 状态 | 进度条滞后/不刷新；`CameraBatch` 的进度写点密度很高（每块一次） |
| **A6** | `MainActivity.kt:251-269` + `:1283-1296` ◐ | 首帧前**持 `renderMutex`** 跑 10 张预设缩略图，不可取消 | 打开大图的**首帧被排队** |
| **A7** | `Decoder.kt:104`、`CameraSession.kt:76`、`UsbPermission.kt:91`、`Exporter.kt:62`（均 `catch(Throwable)`/`catch(Exception)`）◐ | 吞 `CancellationException`。**而同一个文件的 `Decoder.kt:50-53` ★ 显式重抛 CE 并写了理由**："吞掉它会把『取消』伪装成『无法解码该文件』报给用户（与 CameraBatch / 导出协程同一纪律）" ⇒ **正确答案就在 50 行之外，没被复制到 104 行** | 取消变成 no-op；`UsbPermission` 那条会把"被取消"报成"25s 未获授权"（◐） |
| **A8** | `EditorScreen.kt:765` + `MainActivity.kt:990-996` ★（第三轮新发现，**本文最需要立刻修的一条**） | 「自动增强」直接吃 `histogram` 这个 `produceState` 的值，而 **`produceState` 在键变化重启生产者时不会把 `value` 重置** ⇒ 新图的第一帧算出来之前，`histogram` 仍是**上一张图的**。`onAutoEnhance` 里只有 `if (h != null)`，**没有任何"这张直方图属于当前这张图"的校验**（★ 读过整个 lambda） | 换完照片立刻点「自动」⇒ **用上一张图的曝光分布给新图写参数**，并且这一步 `history.push(...)` 进撤销栈（★），用户撤销不掉"自己没做过的事"。同一个 `histogram` 还被 §H 自适应亮档消费（`lumaCentroid`，★ 仅影响 chrome 配色，后果轻）⇒ **A2 的严重性要从"显示不准"上调为"会写错参数"**。修法：让生产者输出 `(stamp, epoch, 曲线)`，消费方要求匹配；或者 `produceState` 键变时先置 null |

**没有的（★一手，避免过度纠偏）**：全仓零 `runBlocking`、零 `Thread.sleep`；USB 解析循环都有界；权限等待有 `withTimeoutOrNull` + `isActive`；指针手势是 suspend 不 spin；预览路径两次渲染不会重叠（单 `collect` + 锁）。

### 4.4 系留功能不正确性的四处（K1~K4，全部 ★）

这四处叠加起来是本文最该优先处理的成品缺陷，但**每一处的修复代价都很小**：

| # | 事实 | 后果 |
|---|---|---|
| **K1** | `CameraPanel.kt:148` 用 `Decoder.decodeFullRes`，而该函数 `:73-74` **设计上对 `.arw` 返回 `null`**（RAW 必须走 `renderLinearFile`；`CameraBatch` 就走对了） | **ARW 永远显示"· 解码失败"** ⇒ 主打的 Sony RAW 工作流一次都没成功过 |
| **K2** | `:135` `newH.take(3)` 取的是**升序列表的前 3 个 = 最老的 3 张**（同文件之外的 `CameraSession.kt:181-182` 自己写着"句柄通常按时间递增 → 取尾部（最新）后倒序"并真的用了 `takeLast().asReversed()` ★）；且 `knownHandles` 初值空、开系留时**不播种基线**（`:113/:134` ★） | 一开系留 ⇒ **从卡上最早的照片开始，每 3 秒推进 3 张把整张卡排空**（用户看到的是"我拍的新片不出、相册里全是旧片"） |
| **K3** | `:136/:137` 两处 `continue`（`objectInfo` 失败、非照片对象）**发生在写入 `knownHandles` 之前** ★ | **队首阻塞**：只要队首有一个目录对象/元数据对象或一次 Info 读取失败，每轮都卡在同样的前 3 个上，永远推进不了（这才是"重下同一批"的真实成因） |
| **K4** | `:148` 长边硬编码 `2048`（无视档位）、`:156` 不传蒙版、`:141-160` 下载的文件**从不删除**（批量路径 `CameraBatch.kt:163-165` 删了 ★），`:158-159` 两个 `recycle` 不在 `finally` ★ | 与批量路径画质/口径不一致；配合 K2 的"排空整卡" ⇒ `cacheDir` 一路涨（200 张 ≈10GB）；异常路径漏 16MB/张 |

**另外两处工程性缺口**（★）：manifest 无 `device_filter.xml`、无 ATTACHED/DETACHED 广播 ⇒ 每次插线都要手动点"检测"；`CameraSession` 出错返回空列表 ⇒ 拔线后 `while(tetherOn)` **静默轮询到永远**（`session` 保持非 null ◐）。

**还有事务号的一处不对称**（★ 一手）：命令路径 `execute()` 明确校验并置失步（`PtpTransport.kt:170-186`），而 `readContainer()` 拿到 header 后**不比对 transactionId**（`:345-388`）⇒ `downloadObject` 的最后一次 Response（`:332`）**可以是上一条事务的**。

### 4.5 无响应 / 卡死（校准后）

| # | 一手事实 | 校准后的判级 |
|---|---|---|
| D1 | `CameraPanel.kt:170` `onDispose { session?.close() }` 在主线程 → `PtpTransport.close() = synchronized(ioLock)`（`:440` ★）。但 `ioLock` 是 `bulkIn`/`bulkOut` **每 256KB 分块**短暂持有（`:57/:63` ★），一次 256KB 传输正常是毫秒级；`DATA_TIMEOUT_MS=10_000`（`:454` ★）只在设备停顿那一次生效 | 🟡 P2（第一版"5~10 秒必现硬冻"过头）。定性：违反"绝不在主线程同步抢 I/O 锁"的纪律，**最坏一次超时** |
| D2 | 三个按钮的 `busy = false` 写在协程体末尾而非 `finally`（`:310-311` 等 ★），**而同文件批量路径用了 `finally`**（`:603-607` ★） | 🟡 P2（`CameraSession` API 设计上不抛 ⇒ 需要未预期异常；真正成立的是"同一文件内纪律不一致"） |
| D3 | `DebugLog.kt:63-87`：四个公开方法全 `@Synchronized`，每次 `write` 都 `writer.flush()` ★；`MainActivity.kt:531` 每帧一条 ★ | 🟡 P2（每帧一次磁盘写 + 监视器竞争；`maybeRotate` 也在锁内） |
| D4 | `SettingsScreen.kt:274` 主线程递归 `deleteRecursively()`（◐，同文件注释写着不该这样）；可能删掉正在传输的系留临时文件 | 🟡 P2 |
| D5 | `EditEngine.kt:373` `jobs.forEach { it.get() }`：**无超时、不可取消**的线程池等待 ★（我读到这一行；它的注释在 `UI_DESIGN:614` 里被当作"列方向不可能出现半成品"的**洗清证据**） | 🟡 P2。⚠️ 注意这条与那条"洗清"论证并不矛盾：正因为它同步等齐，带内并行才安全；风险是**原生侧一旦卡住就永远等** |
| ✅ | 第一版列为 P0 的"主线程忙轮询" **不存在** | 撤回 |

### 4.6 正确性 / 一致性 / 断链（其余）

| # | 一手事实 | 判级 |
|---|---|---|
| E1 | **PTP 属性码表内部自相矛盾**：`PtpProtocol.kt:59-61` ★ ——`DP_EXPOSURE_INDEX = 0x5007`（注 ISO）、`DP_F_NUMBER = 0x500D`（注光圈）、**`DP_EXPOSURE_TIME = 0x500D`（注快门）** ⇒ 两个不同常量同值，至少一个必错；`CameraSession.kt:72` ★ 的快照把 `0x5007` 当 `iso`、`0x500D` 当 `fNumber`；`getDeviceProp` ★ 一律按 **u16** 解析，**从不查 `GetDevicePropDesc` 的 datatype/form**；UI 显示裸整数 | 🟠 P1（专业用户看仪表盘，数字错了比没有更糟；且**没有任何东西能告诉你哪个是错的**） |
| E2 | `CameraSession.kt` `OpenSession` 返回的 SessionID 被忽略（◐）；无 `GetNumObjects`/`GetPartialObject` 续传（◐） | 🟡 P2 |
| E3 | `pickPtpInterface`（`PtpTransport.kt:543-548` ★）：**优先 class 6（静像设备），取不到再退 class `0xFF`（厂商特定）** ⇒ PC-Remote 形态在代码里其实是接得住的（第一版说它"只取 class 6"，第三轮已更正，见附录 B）。真正的缺陷是 **(a)** 选中后不验该接口是否同时有 bulk IN + bulk OUT ★ ⇒ 复合设备可能在接口 0 上握手成功但传输错乱；**(b)** `claimInterface` 失败时，注释与日志都写明了真凶（`:530-538` ★："真机上最可能踩到：系统的 MTP 服务（MediaProvider）已占用该接口"），**但界面只给一句"失败"** ⇒ 用户自助换模式的唯一线索被压进了 logcat | 🟠 P1（**(b) 是极小改动、极高回报**：把 reason 串到 UI，并直接写"把机身 USB 连接改成『PC 遥控』或『图像』"） |
| E4 | `Decoder.kt:48/73` ★ 只匹配 `.arw`，而 `CameraPhoto.kt:69`（◐）把 DNG 当照片接受 ⇒ **DNG 能在相机面板下载、进编辑器打不开** | 🟡 P2 |
| E5 | `MAX_INLINE_BYTES = 1MiB`（`PtpTransport.kt:369-372` ★ 读到该分支）⇒ 对象数超 ~262k 时 `GetObjectHandles` 会失败 | 🟢 P3（现实碰不到，但注释仍写着"真正的对象流式下载留待 PoC-4" ★，与 `CameraSession` 已有的流式下载**互相矛盾**——又一处文档/注释滞后） |
| **G1** | `ENGINEERING_NOTES` §5.6 的待真机验收清单一条未闭环 ★；`DEV_PLAN` §0/§10 停在 v0.5.0/versionCode 13 而 HEAD 是 0.6.3/18 ★；`README.md` 有**一行重复的 `docs/archive/` 表格行**（★ `grep -c` = 2） | 🟠 P1（**文档腐化是本项目的头号系统性风险**，见下） |
| **G2** | `UI_DESIGN` §11 落地表里"强度环未做 / 点哪修哪未接线 / 『自动』是预留位"**三条都已被代码推进**（★：强度环在 `EditorScreen.kt:316` + `ParamPanel.kt:1196`；`scopeAt` 在 `EditorScreen.kt:568`；`AutoEnhance.kt` 存在）；§9.6 说 ParamSlider 紧凑化"未做"而代码已完成 ★ | 🟠 P1（**账本比代码旧 4 天**，而它是唯一的落地真值来源） |
| **G3** | `Decoder.kt:100-104` ★：注释断言"抛异常时 catch 吞掉、这份 65MB 缓存文件路径从未交给任何人 ⇒ 彻底泄漏、F21 没修这条"，**而紧接着的 `catch` 第一句就是 `ArwFullDecoder.releaseCache(...)`** ⇒ 文件已释放 | 🟠 P1（不是资源泄漏，是**KDoc 描述了一个不存在的 bug** ⇒ 下个会话会去"修"它。`Histogram.kt` 那条同类问题 10-25 已修 ★，说明这类病已出现过两次） |
| G4 | `EditEngine.kt:376-388` `render()` 零调用（★ 含危险的 `recycleBase`）；`CameraPanel.kt:115` `tetherScope` 声明未用 ★；`MlMaskProvider/MlFaceProvider.reset()` 零调用（◐）；5 处 `ui_preview.html` 悬空 ★。**另有一处我差点报错的**：`ObjectMasks.resampleTo`（`:114`）确实无生产调用点 ★，但它的 KDoc 承诺的"跨分辨率共享同一份网格"这个性质**由同名的 `ObjectMask.resampleTo` 经 `ObjectLayers.kt:207` 每帧交付** ★ ⇒ 定性从"注释撒谎"降为"**两个同名 `resampleTo` 极易混**，读者会以为生产路径经过那个没人调的方法"。（第三轮反证推翻，见附录 B） | 🟡 P2（死代码本身无害，**歧义命名与留着过时的注释才是危害**） |

### 4.7 元数据 / 方向 / 构建产物（第一轮覆盖面扫描补上的一层）

| # | 一手事实 | 判级 |
|---|---|---|
| **M-1** | **全项目零 EXIF、零方向处理** ★：`grep ExifInterface\|TAG_ORIENTATION\|postRotate\|rotateDegrees` 在 `app/src/main/java/` **零命中**；解码走 `BitmapFactory.decodeStream`（`Decoder.kt:131-143` ★，**它不应用 EXIF Orientation**，`ImageDecoder` 才会）；`Exporter` 不写任何元数据；编辑器**没有手动旋转工具** ★ | 🔴 **P0**（唯一一条新升 P0）：任何 `Orientation=6/8` 的图（竖拍片、部分相机 JPEG、下载图）在 App 里横躺、**导出后仍横躺，用户没有任何补救入口**；同时每次导出丢相机/镜头/曝光/GPS/色彩标记。还有一处**内部分裂**：ARW 走 LibRaw，`dcraw_process` 默认应用机身 flip ⇒ 同机同场景 **RAW 是正的、JPEG 是歪的**。是否必现取决于图片来源（有些厂商把旋转烘进像素），但**元数据丢失是无条件的** |
| **M-2** | `app/build.gradle.kts`：release `isMinifyEnabled=true` + `isShrinkResources=true` ★；`proguard-rules.pro` 自陈"**本项目 release 任务此前从未运行**，本段是首次启用 R8 前的必要护栏"，并点明 LiteRT"不 keep 就是 release-only 崩溃、debug 不复现"★；真机验收装的是 debug 包（`applicationIdSuffix=".debug"` ★）。CI 确实解码 keystore 并注入 `KEYSTORE_PATH`（★ 核过 workflow，所以第一版我怀疑的"发布包被 debug 签名"**不成立**） | 🟠 P1：**发到 Release 页的 APK 与验收过的 APK 不是同一个产物**，且 R8 后的 ML 路径没有任何一次真机运行记录 |
| **M-3** | 16-bit 线性 → 8-bit sRGB 只有 `+0.5f` 四舍五入 ★，**全仓零 dither**（`grep dither\|ordered\|抖动` 零命中 ★） | 🟡 P2：大幅拉曲线/拉阴影后，天空与肤色的渐变会出现色带。修法便宜（导出前 8×8 Bayer 或 ±0.5 随机化），且不与已有确定性颗粒冲突 |
| **M-4** | **零热节流处理** ★（`grep thermal\|ThermalStatus` 零命中）。满核逐带渲染每帧 100~300ms 级 | 🟡 P2："拖滑块越用越卡"没有观测手段也没有降档策略。至少接 `PowerManager.getThermalHeadroom()` 进 `DebugLog` |
| **M-5** | `CameraPanel.kt:140-160` `arrivalLog` 每次 `+` **复制整个 `List<String>`** 且**无上界** ★（显示端只 `takeLast(5)`，`:346` ★） | 🟡 P2（与 K2 的"排空整卡"叠加 ⇒ O(n²) 且每张照片 3~5 次全表复制 + 触发重组） |

### 4.8 测试与 CI：最成体系的一块，和它精确的盲区

覆盖 39 文件 / 294 `@Test` ★，CI 里 `testDebugUnitTest` + `lintDebug` 都是真拦门 ★。测试选点很专业——`ColorMath` / `ToningMath` / `DetailPass` / `ObjectLayers` / `ToneCurve` / `Presets` / `RetouchLayer` 等价性 / `SkinMaskPostProcess` / `FitContentRect` / `PreviewZoom`，还有专门守"静默失效"的 `SilentBehaviorRegressionTest` ★。**这套设计的收益已经兑现**：敢在只靠 CI 的环境下动核心算子，靠的就是它。

盲区正好是本审计 P0/P1 的所在地 ★：`PtpTransport` 没有可注入的 `BulkIo` 缝（`ENGINEERING_NOTES:644` 已登记为后续）⇒ 传输层不可测；`CameraSession`/`UsbPermission`/**系留循环（四处不正确全住在这）**零覆盖；`RawNative` 零 golden 测试（CI 编 `.so` = 只做类型检查，不跑）；**零 androidTest** ⇒ 位图生命周期、Compose 交互、EXIF、R8 后的 ML 全在无人看守区。

⇒ 最小高杠杆动作：给 `PtpTransport` 加一个接口 + 三种剧本的假设备（最老三个 handle / ARW 对象 / 中途拔线）。**K1、K2、K3、E1 的事务号问题会当场各有一条测试红。**

### 4.9 四条重复出现的元问题（架构师真正该拿走的东西）

单条缺陷好修。上面这些发现里有一个**共同的形状**，它才是这个项目下一步的真正风险：

**① 同文件内"一处做对、另一处没对齐"** —— 四个一手实例：`Decoder.kt:50-53` 显式重抛 CE 并写了理由，`:104` 同文件却没有（★）；`CameraPanel.kt:603-607` 的批量路径把 `busy/progress/phase` 放进 `finally`，`:310` 那三个按钮没放（★）；`PtpTransport.kt:174-181` 的 `execute()` 校验事务号并置失步，`readContainer()`/`downloadObject` 不比对（★）；`NeutralGray.kt:129-131` 已经有可复用缓冲的写法，`:194-195` 的 `boxBlur` 仍每带新建（◐）。⇒ **每一次审查都找到了正确答案，只是答案没有被传播。** 这说明缺的不是能力或勤奋，是"同一模式全仓扫一遍"的收尾工序——这一条几乎每个问题都能省掉一次往返。

**② 文档与代码双向漂移，四个方向都有实例** —— 文档**超前**（`DEV_PLAN` §2.1 给 P2 打 ✅ 而四屏 ❌、无真机成功记录 ★）、文档**滞后**（`UI_DESIGN` §11 落地表比代码旧 4 天、§9.6 说紧凑化"未做"而代码已完成 ★、`PtpTransport.kt:370` 注释仍写"对象流式下载留待 PoC-4" 而 `CameraSession` 早就实装了流式下载 ★）、文档**描述了不存在的缺陷**（`Decoder.kt` 那段"彻底泄漏"★）、文档**要求而代码没做**（`ObjectScope.Accessories` KDoc 明令 UI 必须挂覆盖范围提示 ◐）。⇒ 这四类**可以用一个脚本互相打勾**：把"文档里的 ✅"和"调用点检索"、"文档里的『未做/待/留待』"和"代码里的实现符号"做交叉核对。成本远低于每次人工审计。

**③ 纪律停在文档层，没有变成护栏** —— `ENGINEERING_NOTES` §3.2/§3.4（协作取消的返回值必须被消费、昂贵渲染不能配 `collectLatest`）在 `MainActivity` 预览路径上**一手确认零违反**，而同类错误出现在新代码里：A2（缺取消检查点）、A3（缺取消入口）、A7（吞 CE）。⇒ 值得升级的三个具体护栏：把"位图 + 锁"收成一个唯一入口的内部类型（A2/A4/B5 一起消失）；给 `PtpTransport` 加 `BulkIo` 接口（§4.8）；把"catch(Throwable) 未伴随 CE 重抛"做成一条本地静态检查（本仓已有 `kotlin_gates.py` 的骨架，加一条规则即可）。

**④ "从来没进过设计视野"的那一层，才是唯一的新 P0** —— 全项目文档里 **`EXIF` 零命中、`旋转` 在计划/工程笔记/README 里零命中、`dither` 与 `thermal` 零命中**（★）。这不是"做错了"，是**没被想到**。性能与并发可以被审得这么细，仍然会漏掉"图像 App 的基本面"。⇒ 方法论教训：**下一个审计周期应该先问"这个类别的 App 通常必须有什么"（EXIF/方向/色彩管理/元数据/抖动/直方图口径/热与电量），再问"我们做错了什么"**。前者靠清单，后者靠读码——本项目一直只有后者。

### 4.10 第一轮覆盖面扫描补上的五层（此前所有版本都没看过这些类别）

**T1 · 文档宣布的「参数栈 JSON」在代码里根本不存在（🟠 P1）**
`DEV_PLAN` §3.3 用一整节定义交换格式（`{"op": "exposure", "params": {...}}`，并写明"P1/P2/P3 与预设模板**共用同一格式**"★）。但 `grep JSONObject|toJson|fromJson|kotlinx.serialization|Serializer` 在 `app/src/main/java/` **零命中** ★。真实形态：`EditParams`/`RetouchState` 是内存里的 data class，44 套预设是 Kotlin 常量（`Presets.kt` ★），没有任何序列化。

⇒ 这一条改写了我在 §2.9 写的"缺持久化层"：**不是缺存储，是没有可写出的格式**。它一次性卡住三件事——「另存预设」（§11 F 屏 ❌）、「项目/会话」概念、以及 P3 与 Rust 引擎的接口契约（文档说共用，实际两边格式都还不存在）。同时 §5.6 里"批次 5 的 6 条验收"之一正是围绕这个 JSON 栈设计的，那条验收**在代码里无处落地**。修法是定义 `EditParams ⇄ JSON` + 一个 `version` 字段与迁移钩子（本项目的设置层已经有 `migrateSettings` 的骨架 ★，可以借同一个模式），一次投入解锁三件事。

**T2 · App 没有导航层，这是 `onBack` 危险的真正原因（🟠 P1）**
`MainActivity.kt:129/613/633/1087` ★：全屏切换靠 `var editorOpen by remember { mutableStateOf(false) }` + `if (editorOpen) { EditorScreen(...) }`，**没有 Navigation、没有 back stack、没有 ViewModel**（依赖清单 ★ 只有 core-ktx / activity-compose / lifecycle-runtime-ktx / Compose / LiteRT / JUnit）。

⇒ 三个"降级实现"其实是同一个缺口的症状：模型中心内联进设置页（§11 N 屏 ⚠️ ★）、系留只能模态（`HomeScreen.kt:64` + `CameraSheet.kt:69` ★：相机 UI 是弹层，**关面板即 `session.close()`** ⇒ 系留期间不能同时修图，与 §J/K「仪表盘常驻」的形态直接冲突）、以及 `onBack` 那段被他们自己称作"本文件最危险的一段回收"（★ `MainActivity.kt:1015` 注释）。**位图生命周期之所以要手写在 Activity 里，是因为没有承载它的层。** 这条比"MainActivity 太长"更接近根因。

**T3 · 横屏完全没有适配（🟠 P1，产品级）**
manifest 是 `screenOrientation="fullSensor"` + `configChanges` 含 `orientation`（★ 允许任意旋转且不重建）；而 `grep BoxWithConstraints|orientation|isLandscape` 在整个 `ui/editor/` **零命中** ★，`AppShell.kt:150` 那一处只按**高度**分支。规范里的横屏双栏（J′ 系留、L′ 会话批量）标 ❌（★）。

⇒ 后果：**横着看一张横构图照片（修图最自然的姿势）时，App 只是把竖屏布局压扁**——照片带变成一条，Deck 248dp 在矮屏上吃掉一半高度。这与 §3.2 的 Deck 争议是同一场压力测试的两端，而它从未被登记过。targetSdk 37 之后 ≥600dp 还会**强制自由方向**（`DEV_PLAN` §6.3 ★）。

**T4 · 无障碍不是"局部缺失"，是"整层未建立"（🟠 P1）**
整个 `ui/` 目录里 `contentDescription` / `semantics {` 一共 **5 处** ★，而交互控件（chip、滑块、图标按钮、画布手势、直方图条、Deck 手柄）数以百计。机制解释得通：图标是自绘 `PathIcon`，lint 的 `ContentDescription` 检查看不见 Canvas 绘制 ⇒ **CI 那道真拦门在这一层是静音的**（◐ 判读：`HardcodedText`/`ContentDescription` 默认是 warning，不 abort 构建）。同一条也适用于文案：`strings.xml` 只有 4 行（★ 仅 app_name 之类），**全部用户可见文案硬编码在 Kotlin 里** ⇒ 无法本地化、无法统一审校、读屏播报的就是工程命名。

⇒ 我 §3.5 原来写"局部补齐，未成体系"，**评价过轻**。正确定性：M3 组件自带的免费语义是当前的全部家当，一旦离开 M3 组件（本项目大量如此）就没有任何东西了。

**T5 · 真机验收的产物与发布的产物差两层，其中一层影响性能测量（🟠 P1，补强 M-2）**
`app/build.gradle.kts`：`abiFilters += "arm64-v8a"` ★（单一 ABI，注释理由为"缩短 CI 构建并规避 x86 NDK 差异"）、debug/release 都没有给 CMake 传 `-DCMAKE_BUILD_TYPE`（★ 只设了 `path` 与 `version`）。

⇒ 两个后果：**(a) 单一 ABI 关掉了唯一的非真机验证路径**——x86_64 模拟器里 `libraw` 不存在，ARW 全链路**无法用模拟器验证**（而 ARW 恰好是 `RawNative` 零测试的那块，§4.8）。**(b) debug 包的 native 走 AGP 默认（Debug ⇒ `-O0`）**，而真机验收装的正是 debug 包（★ `applicationIdSuffix=".debug"`）⇒ **所有关于"打开大图多久"的观感与 `DebugLog` 里的解码耗时，都不是发布产物的数值**（release 侧是 `-O2` + R8 + shrinkResources）。这把 M-2 从"R8 没在真机跑过"升级为"验收产物与发布产物在**字节码**与**机器码**两层都不同"，并且直接给 F05 那条内存/性能结论的口径打了问号：**用它测出来的数字去验证 §6.2 的预算表，本身就不成立**。判据很轻：装一次 release 包，把 `render done {ms}` 与 `memorySnapshot()` 各抄一遍，两份数值同时入档。

**顺带核完的三条"看起来像 bug"（结论：干净，记在这里免得下个会话重复怀疑）**
`Histogram.compute` 对 signed ByteArray 做了 `values[i].toInt() and 0xFF`（★ 正确，且 `step > count` 时仍采第 0 个以避免全黑图被算成空图——边界想到了）；`AutoEnhance` 用峰值归一化曲线算百分位，理由"线性归一不改变 CDF 形状"（★ 数学上成立）；`Backdrop.blurredBackdrop` 是 28px 宽一次性缩略 + 盒式模糊、`remember(source)` 钉住、并有 `isRecycled` 前置检查（★ 与 `Engineering_notes` "Compose 没有 backdrop blur" 的取舍自洽）。`Exporter` 的 `quality` 来自 `settings.jpegQuality`（★ 不是死旋钮；PNG 忽略 quality 属编码器语义，无害）。`EditHistory` 的撤销栈无上限 ★，但每个 `EditSnapshot` 是三个不可变小对象（`params/retouch/layers`），增长慢 ⇒ 记 P3。

**两处"注释声称的保障并不存在"（本审计累计 2 例，见 §4.9②）**：`AppSettings.kt:128` ★ 声称迁移"用假 `SharedPreferences` 覆盖三条"，而 `grep -rln "migrateSettings" app/src/test/` **零命中** ★ ——`Github_CI.md` 待办里那条"`SettingsMigrationTest` 待第一条真实迁移落地时同批补"（★）**其实已经落地了迁移代码，测试却没补**。

---

## 5. 问题总账（重排）

P0 = 现在就是坏的且一次点击能碰到；P1 = 需要排期；P2 = 债。

| 级别 | 组 | 问题 | 修复代价 |
|---|---|---|---|
| 🔴 **P0** | 图像 | **M-1 零 EXIF/零方向**：解码不认 Orientation、导出不写元数据、无手动旋转、RAW 与 JPEG 行为不一致 | 小-中（解码侧一次 `ExifInterface` + `Matrix`；导出侧一次回写；可选加旋转工具） |
| 🔴 **P0** | 相机 | **K1 ARW 必失败**（走 `decodeFullRes` 而非 `renderLinearFile`） | **极小**（复用批量路径已有写法） |
| 🔴 **P0** | 相机 | **K2/K3 取最老 + 不播种 + 队首阻塞** ⇒ 新照片不出、旧片被排空、可能永不推进 | 小（`takeLast` + 播种 + 两处 `continue` 前记账） |
| 🟠 **P1** | 内存 | **B1** sRGB 导出双份全幅（250MB）+ **§4.2 档位缓解未实装** | 中（按档位实装"单目标/禁并行/导出即回收"） |
| 🟠 **P1** | 内存 | **B2** 笔刷全幅 Float 蒙版（导出 131MB / 每帧 11MB）；**B5** 直方图 19MB/帧 | 小-中（网格化：`FloatGrid` 模式已存在 ★） |
| 🟠 **P1** | 并发 | **A2** 直方图生产者缺取消检查点 + 读正在被写的块 ⇒ 混帧/白烧/静默消失 | 小（进锁 + 带内采样 + `isActive`） |
| 🟠 **P1** | 并发 | **A3** JPEG 导出不可取消 ⇒ 幽灵文件 | 小（给 `renderIntoSrgb` 传取消回调，照抄 RAW 侧 lambda） |
| 🟠 **P1** | UI | **U4** 空作用域可选可调可标记（最像"功能坏了"）；**U1** 滤镜强度默认 80% 空转 | 小（`isEmpty` + 灰态） |
| 🟠 **P1** | UI | **U2/U3** 引擎语义正确但 UI 不告知；**U5** 对象层 LUT 无入口；**U6** 重置无入口 | 小 |
| 🟠 **P1** | 文档 | **G1/G2/G3** 版本停在 v0.5.0、落地表比代码旧 4 天、KDoc 描述不存在的 bug、规范真值三方不一致、5 处悬空引用 | **极小**（半小时）但**最该先做** |
| 🟠 **P1** | 构建 | **M-2** release 开 R8 而验收只装 debug ⇒ 发布产物从未运行过 | 小（装一次 release 包跑 ML + 导出） |
| 🟠 **P1** | 相机 | **E1** 属性码同值 + 不查 datatype ⇒ 仪表盘数字必错 | 小（查 `GetDevicePropDesc`） |
| 🟠 **P1** | 正确性 | **A8** 直方图陈旧值喂给「自动增强」⇒ 用上一张图的分布写参数并进撤销栈（★） | **极小**（输出带 stamp/epoch，消费方校验） |
| 🟠 **P1** | 架构 | **T1** 文档宣布的参数栈 JSON 在代码里不存在（无序列化）⇒ 另存预设 / 项目 / P3 契约三件事同时被卡（★） | 中（一次性投入，解锁三项） |
| 🟠 **P1** | 架构 | **T2** 无导航层/无 ViewModel（`if (editorOpen)` 切屏）⇒ 系留只能模态、模型中心降级内联、位图生命周期手写在 Activity（★） | 大（但可只做"页面 + back stack"最小层） |
| 🟠 **P1** | 产品 | **T3** 横屏零适配，而 manifest 允许自由旋转；横构图照片的自然姿势拿不到双栏（★） | 中（先做"横屏时 Deck 变右侧栏"一档） |
| 🟠 **P1** | 无障碍 | **T4** 全 `ui/` 仅 5 处语义声明，文案零资源；lint 对自绘图标静音（★） | 中（需要一张控件语义清单逐类补） |
| 🟠 **P1** | 工程 | **T5** 单 ABI 关掉模拟器验证 ARW 的路径；debug 包 native 为 `-O0` ⇒ 验收产物与发布产物在字节码+机器码两层不同（★） | 极小（装一次 release 抄数值） |
| 🟠 **P1** | 相机 | **E3(b)** 代码知道 `claimInterface` 失败是 MediaProvider 抢占，却只把"失败"显示给用户（★） | **极小**（reason 上屏 + 一句换模式指引） |
| 🟡 **P2** | 测试 | `AppSettings.kt:128` 声称迁移有"假 SharedPreferences 三条覆盖"，`app/src/test/` 里零命中（★） | 极小 |
| 🟠 **P1** | 产品 | §2.4 P2 措辞（轮询≠系留）、§2.9 定位错位、§2.6 minSdk 决策 | **决策项** |
| 🟡 **P2** | 内存 | B3/B3b/B6/B4 是否叠加 rawdata（未核实） | 中 |
| 🟡 **P2** | 并发 | A4 无竞争时同步 recycle、A5 后台写状态、A6 首帧排队、A7 吞 CE ×4 | 小 |
| 🟡 **P2** | 无响应 | D1 主线程抢 ioLock、D2 busy 不在 finally、D3 每帧 flush、D4 主线程递归删除、D5 无超时 pool wait | 小 |
| 🟡 **P2** | 相机 | K4（长边硬编码/不传蒙版/文件不删/recycle 不在 finally）、拔线静默轮询、无 device_filter、事务号不对称、E2~E4、M-5 | 小-中 |
| 🟡 **P2** | 质量 | M-3 无 dither、M-4 无热观测、§3.5 热区/语义/截断提示 | 小-中 |
| 🟢 **P3** | 合规 | §2.8 LGPL relink；`MAX_INLINE_BYTES`；`.gitmodules` 浮动分支 | 发布前必闭 |

---

## 6. 下一步路线

排序原则：**先修"一次点击就碰到且必然错"的 → 再修"承诺了没做"的 → 再清死区与告知 → 最后才加功能。** 每条带验收判据，因为本项目的失败模式恰恰是"没有判据就打 ✅"。

### 阶段 0（1~3 天）—— 修必然错的两件事

1. **M-1 方向与元数据**：解码时对 JPEG/HEIF 读 `ExifInterface` + `Matrix.postRotate`；导出时回写最小 EXIF 集（方向、拍摄参数、色彩标记）。判据：一张 `Orientation=6` 的手机竖拍片，进 App 是正的、导出也是正的；一张 ARW 与其同名 JPEG 方向一致。
2. **K1 + K2 + K3**：系留改走 `renderLinearFile`；`takeLast(3)`；开系留时播种 `knownHandles`；两处 `continue` 之前把 handle 记进 known。判据：卡上有旧片时打开系留**不产生任何下载**；放一张 ARW 进去，5 秒内出成片；卡上混有目录对象时仍能推进。
3. **A1 异常可见**：`catch (_: Exception)` 改成写 `arrivalLog` + `DebugLog.w`（同文件批量路径已有正确写法可照抄）。判据：任意一步失败，界面上能看到是哪一步、什么原因。
4. **G1/G2/G3/§3.2 文档对齐**（半小时）：版本号、落地表、被修好的注释、KDoc 指向、悬空引用、P2 的 ✅ 改 🔜。判据：**任何一条 token 或"这做没做"的争议能在一个文件里结束。**
5. **A8 直方图陈旧值守卫**（本文新发现、代价最小、后果最重的一条）：让 `produceState` 输出带 `(stamp, epoch)`，`onAutoEnhance` 要求与当前图匹配，否则按钮置灰并写"正在分析这张图…"。判据：**换一张明显欠曝的图后立刻点「自动」，得到的建议值必须来自新图**（用 `DebugLog` 里 `auto enhance {ev, c}` 与两张图应有的方向差对比）。
6. **E3(b) 把失败原因显示出来**（★ 代码里已经有那句诊断）：`claimInterface` 失败的 reason 串到界面，并给出"机身 USB 连接改成『PC 遥控』/『图像』再试"。判据：**故意在 MTP 下连一次，用户能看到下一步该做什么**。
7. **T5 的第一半**：装一次 release 包跑「导入 ARW → 自动蒙版 → 导出」，把 `render done {ms}` 与 `memorySnapshot()` 各抄一遍入档。判据：文档里每个性能数字都带**日期 + 机型 + 变体（debug/release）**。

### 阶段 1（1~2 周）—— 消灭静默失败

5. **A2** 直方图：进 `renderMutex`、按带累加（复用已有分带纪律）、加 `isActive` 检查点。判据：拖滑块时直方图与画面同代；GC 抖动消失；退出编辑器不静默变空。
6. **A3** JPEG 导出取消 + 幽灵文件。判据：取消后相册**没有**该文件。
7. **B2 + B5** 全幅 Float/IntArray 网格化（`FloatGrid` 模式已存在 ★）。判据：`heapPct` 峰值 < 60%（33MP + 笔刷）。
8. **B1 + 档位实装**：按 `DEV_PLAN` §6.2 把中端档三条缓解落成代码。判据：6~8GB 档导出不再同时持有两份全幅位图。
9. **跑一次 release 包（M-2）+ 抄一次真机 `ms` 与 `memorySnapshot` 读数（§2.1/B4/A1）** ⇒ 这一步同时闭环 §5.6 里挂了最久的两条。判据：文档里的每个性能/内存数字都有**日期 + 机型 + 来源**。
10. **`BulkIo` 缝 + 假设备测试**（三种剧本）。判据：阶段 0 的第 2 条有回归测试兜着。

### 阶段 2（2~6 周）—— 诚实的界面

11. **U4 > U1 > U2/U3 > U5/U6** 逐条处理，姿态只有两种：**接通**或**显式禁用 + 一句说明**；不允许"能操作但无效果"（这是 §3.3 六条的共同根，也是本项目"宁可不给不给假的"纪律的自然延伸）。
12. 作用域/细节的告知下沉到 **chip 层**；删掉引用已不存在的"效果"分类的 UI 文案。
13. **§3.5 的两个已知取舍定下来**：热区选 B(36dp) 并真机试手感；横滑 chips 加截断提示（渐隐 + 计数）。
14. **E1 属性码**：查 `GetDevicePropDesc` 的 datatype，界面按格式化值显示（或直接撤掉这三格）。
15. **T1 + 状态持久化**：**先定义参数栈的序列化**（`EditParams/RetouchState/LayerStack ⇄ JSON` + `version` 字段 + 迁移钩子，借 `AppSettings.migrateSettings` 已有的模式），再做按 uri 落盘与重进恢复。⇒ 顺序不能反：**没有格式就没有持久化**，而"另存预设""跨照片同步参数""P3 与 Rust 引擎的契约"全部以它为地基（§2.9 / §4.10 T1）。同时把"起始/重置"语义分清（U6）。判据：修图中划掉 App 再进，能回到上次状态；且用户手存的一个预设在换图后仍可选。
16. **T4 无障碍成体系**：先列控件语义清单（chip / 滑块 / 自绘图标 / 画布手势 / 直方图 / Deck 手柄），逐个补 `contentDescription` + `stateDescription`/`RangeInfo`；把用户可见文案收进 `strings.xml`（当前只有 4 行 ★）。判据：**闭眼用 TalkBack 能走完"导图 → 调曝光 → 导出"**。
17. **T3 横屏最小可用**：不要求做 J′/L′ 双栏，先让横屏时 Deck 变成右侧栏（一处 `BoxWithConstraints` 按宽度分支），照片带拿到高度主导。判据：横握手机看一张 3:2 横构图，直方图 + 3 条滑块全部可见、无需缩放预览。

### 阶段 3（6~12 周）—— 需要 §7 的答案才能开工

18. **往"批量交付"走**：优先做**相册入口**的"选片 → 套预设 → 批量导出"（绕开 §2.4 那条不稳的路），加"另存预设"与跨照片同步参数，把 K/L 屏从 ❌ 变 ✅。这是"像素蛋糕"叙事的最小可交付。
19. **往"专业手工"走**：优先做预览↔导出一致性验收（§5.6 批次 3/4 那两条）+ **混合 AGSL 上色链**（把每帧从 100~300ms 级降到 <16ms）。
20. **T2 补最小导航层**：把"页面"从 `if (editorOpen)` 升成一个有 back stack 的导航层（不必上 Hilt/Navigation 库，一个自写的 sealed 路由 + `rememberSaveable` 就够）。⇒ 它同时解锁：**系留期间可以开着修图**（§4.10 T2 的模态限制）、独立模型中心页（§11 N 屏的规范形态）、返回栈与深链、以及把位图生命周期从 `onBack` 那段"最危险的回收"里挪进可测试的生命周期对象。**先做 T1 再做 T2**（否则导航状态也没法保存）。
21. 模型侧按 `ONDEVICE_MODEL_PLAN.md` 的 M1/M2 走，**先量双 runtime 驻留**。
22. **发布前必闭**：minSdk 决策、LGPL relink、M-1 的元数据完整度、DNG 分支、`device_filter.xml`、T4 无障碍清单。

**三条判断**：(a) 阶段 0/1 排在一切新功能之前，因为现在多一个功能 = 多一条无人看守的 P0；(b) 阶段 3 的两条路**不该都做**——资源是这个量级，选错比选慢贵得多；(c) **T1（序列化）与 T2（导航层）是这两个阶段里唯一"做一件事解锁三件事"的项**，其余都是修一处好一处，排期时值得优先占位。

---

## 7. 需要产品决策的问题（我不擅自定，也不该由代码定）

| # | 问题 | 选项 | 我的倾向 |
|---|---|---|---|
| Q1 | **定位对齐哪个参照系**（影楼批量 vs 专业手工） | 见 §2.9 / §6 阶段 3 (a)(b) | 现阶段先把"专业向手工精修 + 真 RAW + 全本地"写进 README 一句话定位，**因为它今天是真的** |
| Q2 | **P2 对外怎么措辞** | ① 改名「从相机导入」；② 接 CRSDK 做真系留；③ 撤下入口 | **①**（② 是另一个项目；③ 浪费已经写好的下载/导出管道） |
| Q3 | **"永不滚动"要不要保留为硬约束** | 拆 chip 组 / 接受滚动并改写规范 / 拉高 Deck | **改写规范**：代码已给出算过的账（3 滑块 232dp / S=248 有余量 ★），继续写"永不滚动"只会让下个会话去"修"它 |
| Q4 | **热区 26 / 36 / 48dp** | A 现状 / B 36 / C 48 | **B**，且必须真机试手感 |
| Q5 | **是否要给用户一个旋转工具** | 只自动 / 也手动 | **都要**：M-1 的自动化解决 95%，但方向标记本身可能损坏；专业 App 没有手动旋转说不过去 |
| Q6 | **minSdk 36 是为"只用一加15"刻意选的吗** | 保留 / 降到 34 或 33 | 12 个月内不对外发布就**保留并写清理由**；否则降 |
| Q7 | 首页紫→金渐变与"暗房暖石墨"品牌语言相反（`§9.3-E` 至今未动 ★） | 保留 / 换 / 两版并行 | 视觉方向问题，需要真机对比；技术上已收进 `Color.kt`，改动是一行 |

---

## 8. 未能验证的边界（诚实清单）

1. **一切运行时事实**：没编译、没跑测试、没装机。内存是按代码常量的量级推导；"用户会看到 X"是从代码路径推导，不是观察。
2. **M-1 的实际触发面**：哪些图片来源真的带 `Orientation≠1` 需要真机统计；元数据丢失是无条件的，方向错是条件性的——我只证明了"代码完全不处理这件事"。
3. **LibRaw 峰值是否叠加 rawdata(66MB)**：本机子模块未 checkout，无源码可查 ★。已证实的只有 ≥458MB 下界。
4. **A7C2 真机行为**：`claimInterface` 在 MTP 下的实际成功率、ARW 格式码 `0xB101` 是否被这台机器上报、handle 是否真单调升序（代码这么假设，注释写"通常"★）—— 三条全未验证，而它们决定 P2 能不能救。
5. **`◐` 标记的条目**：细节 chips 的置灰现状、`ObjectMasks` 缓存并发的完整推理、`DeviceCapabilities` 的 HEIF 探测缺失、E2/E3/E4、`CameraBatch` 进度写点密度——这些我核对了命中行与上下文，但**没有逐行读完整个函数**，用作排期依据前值得再确认一遍。
6. **`100~300ms`** 的口径（§2.1）：文档值未标测量条件；代码有逐帧 `ms` 但从未回抄。
7. 我没做**反向验证**：改掉 K2 之后会不会漏掉新照片、minSdk 降档的 `NewApi` 成本各需要 10 分钟——改之前应该先做掉。

---

## 9. 值得保留的（写给下一个会话，别把它们改坏）

1. **分带纪律**（`EditEngine.kt:110-129` ★ / `renderLinearFile`）：逐像素上色链全程不物化全幅像素数组，RAW 每次只拉 32 行。这是 33MP 能在手机上编辑的根因，**本审计确认它没有被任何一处绕过**。
2. **双缓冲 + `completed`/`epoch` 双正交量 + 提交守卫**（★）：一次并发缺陷修复被完整保留到现在，并且注释把"为什么这里读 `rendered` 安全"论证清楚了。**别把它改回单缓冲**。
3. **"中性 ⇒ 整段跳过"，且两处判据互相钉住**（`DetailPass.isNeutral` KDoc ★）+ **构造期收敛**（LUT 字符串→Int opcode，理由按 33MP 像素数算 ★）。这种"把代价算到像素数量级再决定放哪儿"的习惯贯穿全仓。
4. **LibRaw 桥的生命周期**（★）：四条退出路径都配 `dcraw_clear_mem`、`recycle()` 顺序有注释、异常不跨 JNI、五个 `ReleaseStringUTFChars` 都在。
5. **降级契约的形状**：ML `createOrNull→null` + OOM 计数 + 会话禁用**且在设置页可见**（★）；ARW 失败回退内嵌预览；`Exporter` 的 IS_PENDING 静默失败路径被显式排除（★，`MainActivity.kt:968-971` 的注释指出回执判据已覆盖它）。"永不抛"是明确纪律而不是运气，且有 `SilentBehaviorRegressionTest` 守着。
6. **`ENGINEERING_NOTES` §2/§3 那批一手换来的陷阱知识**（Crossfade 的 Box、`selectable` 重载静默失效、`LocalContentColor` 只有 Surface provide、协作取消的返回值必须被消费、语义色按「场景×生效主题」二维选、"算，不要抄"）★。本审计对它们的唯一批评是**还停留在文档层、没变成类型或测试护栏**（§4.3 的 A2/A7 正是同类问题在新代码里复发）—— 这恰恰以它们的质量为前提：值得从文档升级到代码。
7. **零本地环境下的四道 CI 闸门 + 294 个纯逻辑单测**：让一个 2 万行像素管线项目在 18 个版本里没烂掉。**要补的是它覆盖不到的那一层，不是推翻它。**

7. **零网络权限 = "全本地"是内核层保证而不是口号**（★ manifest 里 `INTERNET` 出现 0 次）。一个宣称"照片不出手机"的 App 用权限声明把它钉死，比任何隐私政策措辞都硬；任何后续功能（P3、模型下载、崩溃上报）都**必须显式改变这条**，因此它同时是一台天然的架构护栏。
8. **"把代价算到数量级"的习惯在 UI 侧同样存在**（★）：`Backdrop.kt` 用 **28px 宽缩略 + 一次性盒式模糊 + `remember(source)` 钉住**替代 Compose 根本不存在的 backdrop blur，并写明"绝不要在拖动过程中调用它"；`Histogram.compute` 对 signed ByteArray 做了 `and 0xFF` 并处理了"step 大于 count 时仍采第 0 个，否则全黑图会被画成平线"；`AutoEnhance` 主动放弃饱和度/鲜艳度建议，理由是"从一维直方图推断色彩状态太不可靠，宁可不给"。**这三处我逐个读过实现且都没找到毛病**，写在这里是为了让后续审计不要把它们当嫌疑点重复审。
9. **`ObjectLayers.kt:144-145` 那句"绝不要把 RasterMask 接进来，33MP 下是 131MB"**（★）——本文 §4.2 B2 的数值就是它给的。把它放在 §9 是因为它证明**团队是在这个精度上做决策的**；B2 的成立恰恰说明这个精度目前只覆盖了对象层一侧。

---

## 附录 B · 本次复核推翻/校准的断言

第一版把四路子代理的结论当成证据用了，只抽查了一部分。**这一版的差别只有一条方法**：每个进结论的位置看过前后 ≥15 行。上表就是没这样做的代价（13 条 = 第一轮 11 条 + 反证轮 2 条）——列在这里是因为**每一条都是会被下个会话重复踩的坑**。

| 原断言 | 复核结果 | 为什么会错 |
|---|---|---|
| 🔴「系留循环吞 CE ⇒ 主线程忙轮询 ⇒ ANR（P0）」 | **完全推翻**：`delay(3000)` 在 `try` **外**（`:131` vs `:132` ★），取消时它抛 CE 不被捕获 ⇒ 循环正常退出。真实缺陷只是异常被吞、用户看不到失败原因（A1） | 只看到"catch 包住 suspend 调用"就推出"死循环"，没核对 `try` 的边界。**异步问题必须先确定"哪一行抛、落在哪个 catch"** |
| 「此后每 3 秒重下同一批」 | **推翻**：正常行为是每次推进 3 个、最老→最新排空整张卡；"重下同一批"只在队首是非照片对象时成立（K3 ★） | 把"未播种"直接推成"重复下载"，没模拟 `filter`+`take(3)` 在下一轮的状态 |
| 「导出整体不可取消」 | **只对一半**：RAW 主路径通过 progress lambda 可取消（★ `:855-859`），缺取消点的只是 sRGB/JPEG 路径 | 只读了 `EditEngine` 的循环，没读**调用点**怎么传取消信号。取消语义的真相通常在调用点 |
| 「直方图读被回收位图 = 原生 use-after-free」 | **说重了**：`getPixels` 对已回收位图抛 Java 异常并被吞 ⇒ 症状是"直方图静默消失"。真正机制是**生产者无取消检查点**（A2 ★） | 把"违反锁不变式"直接翻译成"内存破坏"。Android 对位图访问是有检查的，native 崩溃要区分"谁在检查" |
| 「LibRaw 峰值 ≈520MB，列 P0」 | **降 P1 + 改口径**：已证实的是 `recycle()` 在 `make_mem_image()` 之后 ⇒ 262+196 ≈ **458MB 下界**；rawdata 是否叠加**未核实**（子模块本机不存在 ★）；12GB 旗舰不构成 P0 | 把注释里三个数字直接相加当同一时刻峰值，没核到释放顺序 |
| 「液化同时两份整幅 ≈250MB（HIGH）」 | **降 MED**：`spanRows`/bbox 来自脸的包围盒 ★，典型 ≈46+12MB，整幅只是上界 | 看到 `IntArray(spanRows*w)` 就按 `spanRows=h` 算，没读它的来源 |
| 「`Presets.byCategory` 零调用」 | **该 API 不存在**（★ grep 零命中）。真实情况：`PresetCategory` 字段存在、UI 零使用（★） | 引用了转述里的符号名，没验证它存在 |
| 「`Decoder.kt:104` 吞 CE ⇒ 65MB 缓存泄漏」 | **泄漏不存在**：那个 catch 第一句就 `releaseCache`（★）。真实问题是**注释断言"彻底泄漏、F21 没修"而代码修了**（G3） | 读了注释没读注释下面的代码 |
| 「A1 内存取证需先加日志（1~2 周）」 | **日志已埋好**：`:910-911` 与 `:966` ★。只欠真机读一次 | 把待办清单的"未闭环"读成"未实现" |
| 「关面板主线程硬冻 5~10 秒（P0）」 | **降 P2**：`ioLock` 每 256KB 分块短暂持有 ★，正常毫秒级；10s 只在设备停顿的一次超时 | 把 `DATA_TIMEOUT_MS` 当成整段下载的持锁时长 |
| 「一次异常 ⇒ spinner 永转（P1）」 | **触发条件收窄**：`CameraSession` API 设计上不抛；真正成立的是同文件内纪律不一致（D2 ★） | 把"没有 finally"等价于"必现卡死"，没评估异常源 |
| 「`ObjectMasks.resampleTo` 只有测试在用 ⇒ 它的 KDoc 在撒谎」 | **定性推翻**（第三轮反证）：该方法确实无生产调用点（★ grep `core/ml` 零命中），但它承诺的"跨分辨率共享网格"这一性质**由同名的 `ObjectMask.resampleTo` 经 `ObjectLayers.kt:207` 每帧交付**（★）⇒ 是**歧义命名**，不是假注释（G4 已按此改写） | 把"这个方法没人调"直接推成"它的说明是错的"，没找同名实现 |
| 「`pickPtpInterface` 只取 class-6 接口 ⇒ PC-Remote 完全没被接住」 | **半推翻**：代码是 `class 6 ?: class 0xFF`（★ `:543-548`），厂商特定类**接得住**；未验 bulk IN/OUT、以及"claim 失败原因不上屏"才是真的缺陷（E3 已按此改写并升 P1） | 只看了 `firstOrNull` 的第一支，没读 `?:` 后面那支 |

**漏掉的（第一版完全没有）**：M-1 零 EXIF/方向（P0）、M-2 release/debug 产物分裂、档位缓解未实装、B4 的 ≥458MB 下界这一**可证实**版本、A2 的真正根因（缺取消检查点）、M-3 无 dither、M-4 无热观测、M-5 日志复制、G2 落地表比代码旧、事务号校验的不对称。

**结论的变化**：P0 从 5 条变成 3 条（相机两条 + 方向/EXIF 一条），第三轮又补出一条**代价最小、后果最重**的 A8（陈旧直方图喂给「自动增强」⇒ 用上一张图写参数）；，"内存 P0"降级为"文档承诺未实装的 P1 + 一条已证实的原生下界"；新增的唯一 P0 是**审计第一版根本没看的那一层（元数据）**——这本身就是本项目方法论的教训：**性能与并发审得再细，也会漏掉"从来没进过设计视野"的那一层。**

---

## 资料来源（本文里唯一的外部数据 ○）

Android 版本分布（§2.6 的 minSdk 判断依据，谷歌 2026-10 开发者峰会经 Android Studio 口径，统计窗口为 7 天内连过 Play 的活跃设备）：

- [Android 版本分布更新（AnTuTu，2026-11-02，转引 Heise 整理数据）](http://antutu.com/doc/138226) —— Android 16 = 25.4%，Android 14+ = 56.4%，Android 13 及更早 = 43.6%
- [Android 16 成为使用最广泛的版本（ruancan，2026-10-31）](https://www.ruancan.com/p/307207.html)
- [谷歌安卓开发者峰会版本份额（IT之家，2026-10-30）](https://view.inews.qq.com/a/20261030A07RW900) —— 含逐版本比例表；Android 16 在 2025-12 为 7.5%
