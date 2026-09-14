# GitHub Actions CI 结果报告

> 由 push 触发的工作流运行结果整理。本文件每次 CI 后**覆盖重写**（前一次报告已清空）。
> 生成时间：2026-09-14（本地）
> 关联提交：`e633ec6093ba7a923e1974590218085d1b55bb22`
> 运行链接：<https://github.com/hifn123p/pixel-cake-app/actions/runs/34859073004>

## 结论：✅ 成功（success）—— 4 个 job 全绿，**首次产出签名 Release APK**

push 到 `main` 触发 `Android CI`。本轮配置了 4 个 Secrets（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`），
`check-signing` 探测到 keystore ⇒ **`Signed Release` 不再跳过，首次真正构建并产出签名 APK**。
同时固定了 debug 签名（入库 `app/debug.keystore`）并补上 LiteRT 的 R8 规则。**一次通过，无失败 job。**

## 任务（Job）总览

| Job | 结论 | 说明 |
|---|---|---|
| Build Debug APK | ✅ success | `assembleDebug`（固定 debug 签名）+ `testDebugUnitTest` 全过 → 上传 debug APK |
| Lint (Android Lint) | ✅ success | `lintDebug` 通过 |
| Check signing secrets | ✅ success | 探测到 `KEYSTORE_BASE64`（4 个 Secrets 已配） |
| **Signed Release** | ✅ **success** | **首次运行**：解出 PKCS12 keystore → `assembleRelease`（启用 R8）→ 上传签名 APK |

## 本轮产出物（Artifacts）

| Artifact | 大小 | 说明 |
|---|---|---|
| `pixelcake-release-e633ec6…` | ≈21.8 MB | **签名 Release APK**（首次产出，保留 90 天） |
| `pixelcake-debug-e633ec6…` | ≈32.5 MB | Debug APK（保留 90 天） |
| `lint-report-e633ec6…` | ≈30 KB | Android Lint HTML 报告（保留 7 天） |

下载：Actions → 本轮 run `34859073004` → 页面底部 Artifacts；或在 GitHub API `…/actions/runs/34859073004/artifacts`。

## 本批提交

| 提交 | 说明 |
|---|---|
| `e633ec6` | `chore(signing)`: 固定 debug 签名（入库 `app/debug.keystore`，PKCS12）+ release 改用 PKCS12 Secrets + LiteRT R8 规则（首次启用 release） |

改动明细：

1. **`app/build.gradle.kts`** — 新增 `signingConfigs`：
   - `debug`：绑定入库的 `app/debug.keystore`（PKCS12，口令为 Android 公开约定 `android`/`androiddebugkey`）。
     动机：以前 CI 从不注入 keystore，AGP 会在每台全新 runner 上**自动生成** debug 密钥 ⇒ 两次构建的 debug 包
     「包名相同、签名不同」⇒ 真机覆盖安装被拒（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`，ColorOS 提示「证书冲突」）。
   - `release`：`storeType = "PKCS12"`（Secrets 里存的是 openssl 导出的 `.p12`，非 keytool 的 JKS）。
2. **`.github/workflows/android.yml`** — release job 解出 `pixelcake.p12`（原 `.jks`）并对应清理；
   build job 新增「打印 debug APK 签名证书指纹」步骤（签名漂移回归护栏，非致命）。
3. **`app/proguard-rules.pro`** — 新增 LiteRT 的 `-keep`/`-dontwarn`（R8 首次启用前的必要护栏：
   LiteRT 经 JNI/反射访问自身类，不 keep 会在 release 包运行期崩，且 debug 不复现）。
4. **`app/debug.keystore`**（新增入库）— Android 标准 debug 密钥库（PKCS12，2690 B，sha256 `9fe4dbdd…`）。

## 🔐 安全边界（重要）

- 入库的 **`debug.keystore` 是 Android 标准调试密钥**，其口令（`android`/`androiddebugkey`）是**公开约定、非机密**，
  入库只为让 debug 签名跨构建稳定；`.gitignore` 用 `!debug.keystore` 有意反忽略。
- ⚠️ **release keystore 绝不能入库**：它只存在于 GitHub Secrets（`KEYSTORE_BASE64` 等），
  CI 通过 `$RUNNER_TEMP` 临时落盘、job 结束即删。请勿把 release 密钥文件提交进仓库。
- 若将来**误提交**了任何真实密钥/口令，仅删除文件**不够** —— 必须用 `git filter-repo`（或 BFG）清理**历史**并立刻轮换该密钥。

## 历史回归记录

| Run | 提交 | 结论 | 失败点 |
|---|---|---|---|
| `34744787789` | `ca5410b` | ❌ failure | ActionTile 缺 `dp` 导入 + AppShell `togetherTo` 笔误（两处编译错误） |
| `34745071202` | `fbc5e49` | ✅ success | 无（UI-1 改版编译修复后全绿） |
| `34745274221` | `30b73e3` | ✅ success | 无（CI 报告 docs-only 提交） |
| `34816082925` | `c3841cd` | ❌ failure | AppShell `transitionSpec` 中调用 `@Composable` 的 `Motion.durationFor` |
| `34816492199` | `ca4ac11` | ✅ success | 无（Motion token 去 `@Composable` 后全绿） |
| `34816912452` | `7c3eae1` | ✅ success | 无（CI 报告 docs-only 提交） |
| `34859073004` | `e633ec6` | ✅ success | 无（**首次 Signing Release 成功**，4 job 全绿） |

## 后续步骤

1. 真机（一加15）分别下载 **release 签名 APK** 与 debug APK 验证：
   - **release 包**：R8 混淆后 LiteRT/MediaPipe 推理、相机 PTP、ARW 解码等是否正常（首次跑 R8，重点看运行期）；
   - **debug 包**：确认与上一版可**覆盖安装**（不再报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）；
   - 功能面：UI 改版（玻璃 TabBar/Sheet/组件）、P1p-2 人脸检测→液化锚点、ML 自动蒙版、retouch、相机批量、Beauty、ToneCurve。
2. 如需**正式发布**（Tag + Release 页面附件），可另建 `tag`/`release` 工作流或用 `gh release`；当前 CI 只把签名 APK 作为 Actions Artifact 上传（保留 90 天）。
3. 如有新修改，按固定流程 **全量推送** → 触发 CI → 结果覆盖写入本文件再推送。
4. 如遇 lint job 报 `Gradle build daemon disappeared`，先 **rerun-failed-jobs** 重试一次（用 `ci_rerun.py`）。

---
*本报告由 push 后 GitHub Actions 运行结果自动整理；本轮 4 job 全绿，首次产出签名 Release APK。*
