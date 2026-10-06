# PixelCake UI Redesign — 修改文档

> 版本：v2.0 | 日期：2026-10-04 | 范围：字体 / 进度条 / 间距 / 颜色对比度 + 控件触控优化

## 一、字号体系（Type.kt）

严格控制在 **5 级**，消除此前零散的非阶梯字号：

| 语义 | 旧 | 新 |
|---|---|---|
| displaySmall | 24sp | **28sp** |
| titleMedium | 16sp | **18sp** |
| bodyMedium | 14sp | **15sp** |
| labelMedium | 12sp | **13sp** |
| labelSmall | 11sp | **12sp** |

## 二、品牌色（Color.kt）

- Seed：`0xFF7C5CFF` → **`0xFF8B6FFF`**（提亮 11 级，明度↑）
- SeedOnDark：`0xFF9C86F7` → **`0xFFA894FF`**（同步提亮，保持色相 252 一致）

> Material You 动态取色**未启用**，避免干扰调色判断。

## 三、滑块（ParamSlider.kt）

- 拇指直径：24dp → **20dp**；保留 Material Slider 的 48dp 触控热区
- 阴影：3dp → **4dp**
- 形状统一走 `Radius.pill`
- 移除滑块整行上下额外留白，降低连续调节项的纵向占比
- 移除有兼容性风险的 `activeTrackStroke` 参数

## 四、间距阶梯（Spacing.kt）

| 语义 | 旧 | 新 |
|---|---|---|
| xl | 24dp | **20dp**（新增 xl=20dp，原 xl 值归入 m） |
| xxl | 32dp | **28dp** |
| controlHeight | 44dp | **48dp** |

## 五、编辑器面板（EditorScreen.kt）

垂直间距由 `Spacing.s` → **`Spacing.m`**，呼吸感提升。

## 六、参数面板（ParamPanel.kt）

- GroupLabel 间距同步更新
- Hint 改用 `bodySmall` + `Spacing.m`

## 七、玻璃胶囊 TabBar（AppShell.kt）

TabBar 高度 56 → **64dp**，触控舒适度提升。

## 八、玻璃 SegmentedBar（GlassSegmentedBar.kt）

选中项增加水平 padding，视觉权重更均衡。

## 九、玻璃 ChipRow（GlassChipRow.kt）

- chip 按可用宽度自动换行，模板与选项不再需要横向寻找
- 行间距按 `Spacing.xs`，保留 chip 本身的触控热区

## 十、导出面板（ExportSheet.kt）

功能逻辑已完整覆盖 Lightroom 风格参数：
- 基础：曝光、对比度、高光、阴影、白色、黑色、高光恢复、WB
- 氛围：自然饱和度、饱和度、去雾、曲线、HSL、彩色分级、LUT
- 细节：晕影、颗粒、锐化、降噪、清晰度、纹理
- 人像：皮肤笔刷、瑕疵移除、美颜、追色、预设

导出后按钮形态切换（实心→描边 + 文案变"再导一次" + 说明文字），防止重复导出 RAW 全分辨率。

## 待办

- [ ] 编译验证：`.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain`
- [ ] 真机触控热区与多行布局验证（64dp TabBar / 48dp slider 热区）
