# GitHub Actions CI 结果报告

> 生成时间：2026-11-07 · 关联提交：`faba8fe`（versionName `0.6.4` / versionCode `19`）
> 本轮主题：**外部审计整改批次（qwen3.8 + hy4 双审）→ v0.6.4 发布**

## 结论：v0.6.4 已发布 —— 整改批次一次全绿

| 提交 | run | 结论 | 内容 |
|---|---|---|---|
| `548792c` | 42068020622 | ✅ | 全量功能 + README/docs（v0.6.3 发布基线） |
| **`5419255`** | **42373572438** | **✅** | **3 个 P0 修复（系留 ARW 走 LibRaw / 陈旧直方图清位 / exifinterface 依赖）+ 两份审计入库** |
| `6df6fc2` | 42375995348 | ⏸ cancelled | v0.6.4 整改批次（被同并发组的 import 补丁 run 让位） |
| **`faba8fe`** | **42376153705** | **✅ 4 job 全绿** | **v0.6.4 整改批次 + serialize import 补丁 —— 本轮验收 run** |
| **`v0.6.4` tag** | **42376920012** | ✅ 5 job 全绿 | **Publish GitHub Release** |

## 验收 run（42376153705）job 明细

| Job | 结论 |
|---|---|
| Lint (Android Lint) | ✅（13 步） |
| Build Debug APK（含 `testDebugUnitTest` 拦门） | ✅（16 步） |
| Check signing secrets | ✅ |
| Signed Release（R8 + 资源收缩 + arm64-v8a） | ✅（16 步） |
| Publish GitHub Release | ⏭ skipped（非 tag run） |

## 本轮落地的整改（详见 `docs/ENGINEERING_NOTES.md` §13）

- **P0-2**：EXIF 方向层落地 —— 解码烘焙 Orientation 矩阵（5/7 镜像类顺序已推导 + `ExifOrientationTest`），导出回写最小 EXIF 集（方向恒正 + 机身/拍摄参数，GPS 不复制）
- **P1-3**：导出可取消 —— sRGB 逐带 `isCancelled`；Exporter 双检查点，取消即删 IS_PENDING 记录不留幽灵文件；CE 重抛
- **P1-2**：直方图按带累加（19MB/帧短命垃圾清零）+ 进 `renderMutex` + `isActive` 检查点
- **P1-5**：系留开开关播种基线（旧片不重处理）+ 坏对象记账 + 位图回收单一出口 + 消费 `renderIntoSrgb` 返回值 + arrivalLog 上界
- **U4/U1**：空作用域禁用（`coveredScopes` + chip 禁用 + 说明）；无滤镜时隐藏强度滑块
- **P1-15**：悬空 `rawCachePath`（cacheReleased 区分）/ NotApplicable Hint 按类说真话 / 已删分类文案清理
- **T1**：`EditSnapshotJson` —— `EditDocument(version+params+retouch+layers) ⇄ JSON`，`encodeDefaults=true`，迁移骨架同构 `AppSettings.migrateSettings`
- 新增测试 14 条断言：`ExifOrientationTest`(3) + `CoveredScopesTest`(5) + `EditSnapshotJsonTest`(6)

## Release

- 链接：<https://github.com/hifn123p/pixel-cake-app/releases/tag/v0.6.4>
- versionCode `18` → `19`；versionName `0.6.3` → `0.6.4`
- 新依赖：`androidx.exifinterface:exifinterface:1.3.7`、`org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`（+ `kotlin.plugin.serialization` 编译器插件，与 Kotlin 2.2.21 同源）

## 遗留（hy4 §5 阶段 2/3，未在本轮）

B1/B2/B5/B6 内存项（需真机 memorySnapshot 基线）· E1 属性码撞码 · T2 导航层 / T3 横屏 / T4 无障碍（待 §6 Q3/Q4 决策）· release 变体真机抄性能数据（M-2/T5）· `catch(Throwable)` CE 重抛静态闸门（本轮人工核对 4 处均合规）
