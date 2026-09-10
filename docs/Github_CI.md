# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-10（本地）
> 关联提交：`6d5f4e389ac723806cbe59138eafc1d3d1539709`
> 运行链接：<https://github.com/hifn123p/pixel-cake-app/actions/runs/34485237118>

## 结论：❌ 失败（failure）—— 单测编译错误（主代码已通过）

push 到 `main` 触发 `Android CI`。本轮为 retouch 参数顺序修正（commit `6d5f4e3`，对应 run `34485237118`）。
**好消息**：主代码 `MainActivity.kt` 与 `EditEngine.kt` 已编译通过（`compileDebugKotlin` ✅、`Lint` ✅）。
**坏消息**：`Build Debug APK` 的 `:app:compileDebugUnitTestKotlin` 任务因单测 `NeutralGrayTest.kt` 的类型不匹配而失败，
导致未产出 APK、单测未执行。

## 任务（Job）总览

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ❌ failure | `:app:compileDebugUnitTestKotlin` 编译单测失败 → 未上传 APK |
| Lint (Android Lint) | ✅ success | `Run lint`（lintDebug）通过 |
| Check signing secrets | ✅ success | 探测 `KEYSTORE_BASE64` 是否存在 |
| Signed Release | ⏭ skipped | 未配置 `KEYSTORE_BASE64` secret |

> 主代码已绿（Lint + compileDebugKotlin 均过）；本轮仅卡在单测编译。修一处 `.toInt()` 即可重新全绿。

## 失败详情

- **失败任务**：`:app:compileDebugUnitTestKotlin`
- **出错文件**：`app/src/test/java/com/hifn/pixelcake/core/edit/retouch/NeutralGrayTest.kt`
- **报错（节选）**：

```
e: .../NeutralGrayTest.kt:20:44 Argument type mismatch: actual type is 'Long', but 'Int' was expected.
e: .../NeutralGrayTest.kt:20:58 Argument type mismatch: actual type is 'Long', but 'Int' was expected.
e: .../NeutralGrayTest.kt:20:70 Argument type mismatch: actual type is 'Long', but 'Int' was expected.
> Task :app:compileDebugUnitTestKotlin FAILED
```

- **出错行**：

```kotlin
// NeutralGrayTest.kt:15-20
var seed = 12345L                       // Long
for (i in px.indices) {
    seed = (seed * 1103515245 + 12345) and 0x7fffffff   // Long
    val n = ((seed % 80) - 40)          // Long
    val v = (128 + n).coerceIn(0, 255)  // Long（128 + n 为 Long，coerceIn 推断为 Long）
    px[i] = 0xff000000.toInt() or (v shl 16) or (v shl 8) or v
    //                              ↑ v 是 Long，而 Int.or / Int.shl 要求 Int → 类型不符
}
```

## 根因分析（Kotlin 整数类型推断）

`seed` 声明为 `Long`（`12345L`），后续 `n`、`v` 全部推断为 `Long`。
`or` / `shl` 是 `Int` 的中缀扩展（`infix fun Int.or(other: Int)` / `infix fun Int.shl(bitCount: Int)`），
要求操作数为 `Int`。`v: Long` 与它混用触发 `Argument type mismatch: actual type is 'Long', but 'Int' was expected`。

注意：第 36 行 `preservesHardEdge` 里的 `v = if (x < w / 2) 20 else 220` 是 `Int` 字面量，未受影响——只有
`smoothsFlatNoisyRegion` 里这段 LCG 噪声生成因 `seed = 12345L` 整条链变成了 `Long`。

## 修复方案（最小改动，仅改 `NeutralGrayTest.kt` 一行）

把 `v` 显式转成 `Int`（`.coerceIn(0, 255)` 结果域本就在 0–255，转 Int 安全）：

```kotlin
// NeutralGrayTest.kt:19 —— 加 .toInt()
val v = (128 + n).coerceIn(0, 255).toInt()
```

或在第 18 行把 `n` 转 Int（二选一即可）：

```kotlin
val n = ((seed % 80) - 40).toInt()
```

其余代码（`or` / `shl` / `0xff000000.toInt()`）保持不变，全部按 `Int` 运算。

## 历史回归记录

| Run | 提交 | 结论 | 失败点 |
|---|---|---|---|
| `34379432771` | `0c972ee` | ❌ failure | 单测 F04 |
| `34430484406` | `71f9737` | ❌ failure | 编译 MainActivity.kt:97 缺 import |
| `34433386106` | `610f85f` | ✅ success | 无 |
| `34481989723` | `9c67297` | ❌ failure | 编译 MainActivity.kt:110/195 尾随 lambda 绑错参数（retouch 接入） |
| `34485237118` | `6d5f4e3` | ❌ failure | 单测 NeutralGrayTest.kt:20 `Long`/`Int` 类型不匹配 |

## 后续步骤

1. 按上面方案改 `NeutralGrayTest.kt` 第 19 行（加 `.toInt()`）或第 18 行。
2. **全量推送**本地修改 → 触发新一轮 CI。
3. 预期：单测编译通过 → 单测全绿 → Build 上传 debug APK；Lint 已稳定转绿；release 仍因无 keystore 跳过。
4. 新一轮结果继续覆盖写入本文件。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 retouch 参数顺序修正后主代码已绿，仅余单测一处 Long/Int 类型不匹配。*
