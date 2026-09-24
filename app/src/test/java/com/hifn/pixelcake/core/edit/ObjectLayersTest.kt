package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对象作用域图层单测（批次 5，`docs/OBJECT_TONE_DESIGN.md`）。
 *
 * ## 为什么这组测试是本批**最重要**的那一半
 *
 * 三条渲染入口（预览 `renderIntoSrgb` / 代理 `renderIntoLinear` / 导出 `renderLinearFile`）都要
 * `Bitmap`，在 JVM 上测不了。所以本批所有可持续验证的性质都压在这里 —— 而这个文件**零 Android 依赖**
 * （`ObjectLayers.kt` 是纯 Kotlin）：作用域的组成关系、层的唯一性、叠加代数、四条过滤规则
 * 全都能在没有设备、没有 CI 真机的情况下钉死。
 *
 * ## 覆盖的四组性质
 *
 * 1. **作用域组成**：8 个作用域与 6 个语义槽位的对应关系（派生 = 并集，原生 = 单槽一一对应）。
 * 2. **层的唯一性与增删**：`upsert` / `without` / `hasScope` / `isAdjusted`。
 * 3. **快照必须带上 layers**：`EditSnapshot(params, retouch, layers)` —— 尤其是**位置参数**那种写法。
 * 4. **渲染态构建与叠加代数**：`buildLayerStack` 的四条过滤 + [blendStack8] 的插值语义。
 */
class ObjectLayersTest {

    /** 恒为 0 的合法蒙版：表达「作用域存在、但这张图里没有它的像素」——**不跳过**该层。 */
    private object ZeroMask : RetouchMask {
        override fun sample(px: Int, py: Int) = 0f
        override fun resampleTo(w: Int, h: Int): RetouchMask = this
    }

    /** 记录自己被要求对齐到哪个目标尺寸（验证 `buildLayerStack` 真的调了 `resampleTo`）。 */
    private class RecordingMask : RetouchMask {
        var lastResample: Pair<Int, Int>? = null
        override fun sample(px: Int, py: Int) = 1f
        override fun resampleTo(w: Int, h: Int): RetouchMask {
            lastResample = w to h
            return this
        }
    }

    private fun stackOf(
        vararg layers: ObjectLayer,
        w: Int = 64,
        h: Int = 48,
        maskOf: (ObjectScope) -> RetouchMask? = { FullMask }
    ): LayerStack = buildLayerStack(layers.toList(), maskOf, w, h)

    // ————————————————————————————————————————————————————————————
    // 1. 作用域组成
    // ————————————————————————————————————————————————————————————

    @Test
    fun personIsEverythingExceptBackground() {
        val expected = ScopePart.entries.filter { it != ScopePart.Background }.toSet()
        assertEquals(expected, ObjectScope.Person.parts)
        assertEquals(5, ObjectScope.Person.parts.size)
        assertEquals(setOf(ScopePart.Background), ObjectScope.Background.parts)
        // 互补：并集是全部 6 槽、交集为空（否则「人物」与「背景」会同时命中同一批像素）
        assertEquals(ScopePart.entries.toSet(), ObjectScope.Person.parts + ObjectScope.Background.parts)
        assertTrue(ObjectScope.Person.parts.intersect(ObjectScope.Background.parts).isEmpty())
    }

    @Test
    fun skinIsTheUnionOfBodyAndFaceSkin() {
        assertEquals(setOf(ScopePart.BodySkin, ScopePart.FaceSkin), ObjectScope.Skin.parts)
        assertEquals(ObjectScope.BodySkin.parts + ObjectScope.FaceSkin.parts, ObjectScope.Skin.parts)
    }

    @Test
    fun sixNativeScopesMapOneToOneOntoTheSixSlots() {
        // `parts.single()` 本身就是要守的性质：原生作用域必须**恰好一个**槽位
        // （写成两个的话，它就不是「原生类」而是一个没写进文档的派生作用域）。
        val native = listOf(
            ObjectScope.Background, ObjectScope.Hair, ObjectScope.BodySkin,
            ObjectScope.FaceSkin, ObjectScope.Clothes, ObjectScope.Accessories
        )
        val mapped = native.map { it.parts.single() }
        assertEquals(ScopePart.entries.size, mapped.size)
        assertEquals(ScopePart.entries.toSet(), mapped.toSet())
        // 模型第 5 类在 UI 上被翻译成「配饰」（`ObjectScope.Accessories` 的 KDoc 有理由）
        assertEquals(ScopePart.Others, ObjectScope.Accessories.parts.single())
    }

    @Test
    fun everyScopeHasPartsAndAUniqueLabel() {
        assertEquals(8, ObjectScope.entries.size)
        ObjectScope.entries.forEach { assertTrue(it.label, it.parts.isNotEmpty()) }
        assertEquals(
            "chip 行上的文案不能重复（重复 = 用户分不清点的是哪个）",
            ObjectScope.entries.size,
            ObjectScope.entries.map { it.label }.toSet().size
        )
    }

    // ————————————————————————————————————————————————————————————
    // 2. 层的唯一性与增删
    // ————————————————————————————————————————————————————————————

    @Test
    fun upsertAppendsNewScopeAndKeepsInsertionOrder() {
        val list = emptyList<ObjectLayer>()
            .upsert(ObjectLayer(ObjectScope.Person, EditParams(exposureEv = 1f)))
            .upsert(ObjectLayer(ObjectScope.Background, EditParams(exposureEv = -1f)))
        assertEquals(2, list.size)
        assertEquals(listOf(ObjectScope.Person, ObjectScope.Background), list.map { it.scope })
    }

    @Test
    fun upsertReplacesInPlaceKeepingIndexAndUniqueness() {
        val first = ObjectLayer(ObjectScope.Person, EditParams(exposureEv = 1f))
        val middle = ObjectLayer(ObjectScope.Hair, EditParams(exposureEv = 0.5f))
        val replacement = ObjectLayer(ObjectScope.Person, EditParams(exposureEv = 2f))

        val list = listOf(first, middle).upsert(replacement)
        assertEquals(1, list.count { it.scope == ObjectScope.Person }) // 唯一
        assertEquals(2, list.size)
        // 位置不变（否则每调一次参数，层在列表里跳一次，渲染顺序也跟着变）
        assertEquals(listOf(ObjectScope.Person, ObjectScope.Hair), list.map { it.scope })
        assertEquals(2f, list[0].params.exposureEv, 0f)
        assertSame("未受影响的那一层不该被重建", middle, list[1])
    }

    @Test
    fun withoutRemovesAndAllocatesNothingWhenAbsent() {
        val list = listOf(ObjectLayer(ObjectScope.Person), ObjectLayer(ObjectScope.Hair))
        val after = list.without(ObjectScope.Person)
        assertEquals(listOf(ObjectScope.Hair), after.map { it.scope })

        // 不存在时返回**同一实例**：这个函数在 chip 行的高频路径上被反复调用
        assertSame(after, after.without(ObjectScope.Background))
        assertSame(after, after.without(ObjectScope.FaceSkin))
        assertSame(after, after.without(ObjectScope.Person)) // 仍然没有 Person
    }

    @Test
    fun hasScopeAndIsAdjustedAreDifferentQuestions() {
        val fresh = listOf(ObjectLayer(ObjectScope.Person))
        assertTrue(fresh.hasScope(ObjectScope.Person))
        assertFalse(fresh.hasScope(ObjectScope.Hair))
        // 刚点 chip 建出来的空层：「有层」但「未调整」
        // （chip 上不亮「已调整」标记、`buildLayerStack` 也会把它滤掉）
        assertFalse(fresh.isAdjusted(ObjectScope.Person))

        val tuned = listOf(ObjectLayer(ObjectScope.Person, EditParams(exposureEv = 0.5f)))
        assertTrue(tuned.isAdjusted(ObjectScope.Person))
        assertFalse("没有这一层时必须是 false，不能抛", tuned.isAdjusted(ObjectScope.Hair))
    }

    // ————————————————————————————————————————————————————————————
    // 3. 快照必须带上 layers
    // ————————————————————————————————————————————————————————————

    @Test
    fun positionalThreeArgSnapshotCarriesLayers() {
        val layers = listOf(ObjectLayer(ObjectScope.Hair, EditParams(exposureEv = 1f)))
        // ⚠️ 这条断言守的就是那个陷阱：给 `EditSnapshot` 加第三个带默认值的字段后，
        // `EditSnapshot(params, retouch)` 这种**位置参数**写法依然编译通过，
        // 却会把 layers 静默丢空 —— 不报错、不崩溃，只是用户撤销一次对象层全没了。
        // 所以第三位必须、且只能是 layers。
        val snap = EditSnapshot(EditParams(exposureEv = 2f), RetouchState(), layers)
        assertEquals(layers, snap.layers)
        assertEquals(2f, snap.params.exposureEv, 0f)
    }

    @Test
    fun snapshotDefaultHasNoLayers() {
        assertTrue(EditSnapshot().layers.isEmpty())
        assertTrue(EditSnapshot(params = EditParams(saturation = 0.5f)).layers.isEmpty())
    }

    @Test
    fun historyUndoRedoRoundTripsLayers() {
        val history = EditHistory()
        val one = listOf(ObjectLayer(ObjectScope.Person, EditParams(exposureEv = 1f)))
        val two = one.upsert(ObjectLayer(ObjectScope.Background, EditParams(exposureEv = -0.5f)))

        history.push(EditSnapshot(layers = one))
        history.push(EditSnapshot(layers = two))

        assertTrue(history.undo())
        assertEquals(one, history.current.layers)
        assertTrue(history.undo())
        assertTrue("回到初始状态：对象层应当被清空", history.current.layers.isEmpty())
        assertTrue(history.redo())
        assertEquals(one, history.current.layers)
        assertTrue(history.redo())
        assertEquals(two, history.current.layers)
    }

    // ————————————————————————————————————————————————————————————
    // 4a. buildLayerStack 的四条过滤
    // ————————————————————————————————————————————————————————————

    @Test
    fun buildSkipsLayerWithZeroStrength() {
        // 强度 0 = 「临时停用」，参数必须留着（所以过滤发生在构建期，不是把参数清掉）
        val s = stackOf(ObjectLayer(ObjectScope.Person, EditParams(exposureEv = 1f), strength = 0f))
        assertFalse(s.isActive)
        assertEquals(0, s.size)
    }

    @Test
    fun buildSkipsNeutralParamsEvenIfTheScopeWasSelected() {
        // 这条是「选中一个作用域这个动作本身不改变任何像素」的直接证明：
        // 用户误触 chip、还没调任何参数 ⇒ 图层栈为空 ⇒ 渲染结果逐位等于整图结果。
        val stack = stackOf(ObjectLayer(ObjectScope.Person))
        assertFalse(stack.isActive)
        assertSame(LayerStack.EMPTY, stack)
    }

    @Test
    fun buildSkipsScopeWhoseMaskIsUnavailable() {
        val layer = ObjectLayer(ObjectScope.Person, EditParams(exposureEv = 1f))
        assertFalse(stackOf(layer, maskOf = { null }).isActive)

        // 但「合法全 0 蒙版」**不能**被跳过：它表达的是「这张图里没有这个对象」，
        // 与「无法提供蒙版」是两回事（后者才是 null）。跳过了会让「模型可用但图中无此人」
        // 与「模型不可用」在渲染层混成一种情况。
        assertTrue(stackOf(layer, maskOf = { ZeroMask }).isActive)
    }

    @Test
    fun buildSkipsWhenInputOrTargetSizeIsDegenerate() {
        val layer = ObjectLayer(ObjectScope.Person, EditParams(exposureEv = 1f))
        assertFalse(buildLayerStack(emptyList(), { FullMask }, 64, 48).isActive)
        assertFalse(buildLayerStack(listOf(layer), { FullMask }, 0, 48).isActive)
        assertFalse(buildLayerStack(listOf(layer), { FullMask }, 64, 0).isActive)
        assertSame(LayerStack.EMPTY, buildLayerStack(listOf(layer), { FullMask }, 0, 0))
    }

    @Test
    fun buildClampsStrengthIntoUnitRange() {
        val over = stackOf(ObjectLayer(ObjectScope.Person, EditParams(exposureEv = 1f), strength = 2.5f))
        assertEquals(1f, over.layers.single().weight, 0f)
        // 负强度等价于「停用」，不是「反向」（否则用户把滑杆拖过头会得到一张反色照片）
        assertFalse(stackOf(ObjectLayer(ObjectScope.Person, EditParams(exposureEv = 1f), strength = -1f)).isActive)
    }

    @Test
    fun buildResamplesMaskToTargetSize() {
        val probe = RecordingMask()
        val s = buildLayerStack(
            listOf(ObjectLayer(ObjectScope.Person, EditParams(exposureEv = 1f))),
            { probe }, 7008, 4672
        )
        assertTrue(s.isActive)
        // 蒙版必须被对齐到**目标尺寸**（预览 2048 / 导出 7008 是同一份网格、不同的目标尺寸）
        assertEquals(7008 to 4672, probe.lastResample)
    }

    @Test
    fun buildKeepsLayerOrderAndReportsSize() {
        val s = stackOf(
            ObjectLayer(ObjectScope.Background, EditParams(exposureEv = -1f)),
            ObjectLayer(ObjectScope.Person, EditParams(exposureEv = 1f))
        )
        assertEquals(2, s.size)
        assertEquals(listOf(ObjectScope.Background, ObjectScope.Person), s.layers.map { it.scope })
    }

    // ————————————————————————————————————————————————————————————
    // 4b. 效果 / 细节两段的剥离
    // ————————————————————————————————————————————————————————————

    @Test
    fun withoutWholeImageStagesZeroesExactlyThoseTwoSections() {
        val p = EditParams(
            exposureEv = 0.7f, contrast = 0.2f, temperature = 0.3f, saturation = -0.1f,
            hsl = HslMix().withSat(4, 12f),
            grading = ColorGrading(shadows = GradingBand(hue = 0.55f, sat = 0.3f)),
            // 效果段（批次 3）
            vignetteAmount = -0.8f, vignetteMidpoint = 0.9f, vignetteFeather = 0.1f, vignetteRoundness = -1f,
            grainAmount = 0.7f, grainSize = 0.9f, grainRoughness = 0.1f,
            // 细节段（批次 4）
            sharpenAmount = 0.9f, sharpenRadius = 3f, sharpenDetail = 0.1f, sharpenMasking = 0.9f,
            nrLuminance = 0.6f, nrLuminanceDetail = 0.1f, nrColor = 0.5f, nrColorDetail = 0.9f,
            clarity = 0.4f, texture = 0.3f
        )
        val s = p.withoutWholeImageStages()

        // 逐像素段原样保留（这一步不能顺手「归一化」任何东西）
        assertEquals(p.exposureEv, s.exposureEv, 0f)
        assertEquals(p.contrast, s.contrast, 0f)
        assertEquals(p.temperature, s.temperature, 0f)
        assertEquals(p.saturation, s.saturation, 0f)
        assertEquals(p.hsl, s.hsl)
        assertEquals(p.grading, s.grading)

        // 效果 + 细节两段全部回到中性：把这个剥好的参数再清掉逐像素那段，应当**恰好等于默认值**。
        // 用「与默认实例比较」而不是逐个字段断言 ⇒ 将来给这两段加字段时这条会自动纳入
        // （漏掉一个字段就会在这里红，而不是在真机上表现为「对象层的暗角不生效」）。
        val onlyTone = s.copy(exposureEv = 0f, contrast = 0f, temperature = 0f, saturation = 0f)
        assertEquals(EditParams(hsl = HslMix().withSat(4, 12f), grading = ColorGrading(shadows = GradingBand(hue = 0.55f, sat = 0.3f))), onlyTone)
    }

    @Test
    fun buildProducesProgramThatIgnoresEffectAndDetailStages() {
        // 上面那条只证明「字段被改了」。这条证明**行为**：带着暗角/颗粒/清晰度的对象层，
        // 其程序与「只留曝光」的程序逐点输出相同 ⇒ 效果/细节对对象层真的没有作用。
        val withStages = EditParams(exposureEv = 0.7f, vignetteAmount = -1f, grainAmount = 1f, clarity = 1f)
        val onlyTone = EditParams(exposureEv = 0.7f)
        val a = buildLayerStack(listOf(ObjectLayer(ObjectScope.Person, withStages)), { FullMask }, 400, 300)
            .layers.single().program
        val b = buildLayerStack(listOf(ObjectLayer(ObjectScope.Person, onlyTone)), { FullMask }, 400, 300)
            .layers.single().program

        for (u in listOf(0.01f, 0.25f, 0.5f, 0.75f, 0.99f)) {
            for (v in listOf(0.01f, 0.5f, 0.99f)) {
                assertEquals(
                    "u=$u v=$v —— 效果/细节必须被剥离，否则「面部的暗角」会真的生效",
                    b.applySrgb8(96, 128, 160, u, v), a.applySrgb8(96, 128, 160, u, v)
                )
            }
        }
    }

    // ————————————————————————————————————————————————————————————
    // 4c. 中性判定
    // ————————————————————————————————————————————————————————————

    @Test
    fun neutralDetectionCoversEverySectionIncludingNewFields() {
        assertTrue(EditParams().isNeutral())

        // 影调 / 白平衡 / 偏好
        assertFalse(EditParams(exposureEv = 0.01f).isNeutral())
        assertFalse(EditParams(contrast = -0.01f).isNeutral())
        assertFalse(EditParams(temperature = 1f).isNeutral())
        assertFalse(EditParams(tint = -1f).isNeutral())
        assertFalse(EditParams(vibrance = 0.5f).isNeutral())
        assertFalse(EditParams(dehaze = 0.5f).isNeutral())

        // 曲线 / 颜色 / LUT
        assertFalse(EditParams(lumaPoints = ToneCurve.IDENTITY + (0 to 10)).isNeutral())
        assertFalse(EditParams(redPoints = listOf(0 to 5, 255 to 250)).isNeutral())
        assertFalse(EditParams(hsl = HslMix().withHue(3, 5f)).isNeutral())
        assertFalse(EditParams(grading = ColorGrading(shadows = GradingBand(sat = 0.2f))).isNeutral())

        // 效果 / 细节：单独改动也算「不中性」。语义是「参数是否等于中性」，
        // 不该为这两段开例外 —— 对**对象层**的例外由 `withoutWholeImageStages` 在构建期表达。
        assertFalse(EditParams(vignetteAmount = -0.5f).isNeutral())
        assertFalse(EditParams(grainAmount = 0.5f).isNeutral())
        assertFalse(EditParams(sharpenAmount = 0.5f).isNeutral())
        assertFalse(EditParams(nrLuminance = 0.5f).isNeutral())
        assertFalse(EditParams(clarity = 0.5f).isNeutral())
        assertFalse(EditParams(texture = -0.5f).isNeutral())
    }

    @Test
    fun lutIntensityOnlyCountsWhenALutIsSelected() {
        assertTrue(EditParams(lutId = "none").isNeutral())
        // 「在 LUT 页拖了一下强度、但没选滤镜」不参与任何计算 ⇒ 必须仍算中性，
        // 否则每次误拖都会凭空造出一个「有效空层」参与逐像素循环。
        assertTrue(EditParams(lutId = "none", lutIntensity = 0.2f).isNeutral())
        // 选了滤镜、且强度非默认 ⇒ 有效
        assertFalse(EditParams(lutId = "kodak-2383", lutIntensity = 0.2f).isNeutral())
    }

    @Test
    fun neutralParamsAreThenStrippedOutOfTheStack() {
        // 两段串起来：一个「只改了效果段」的层，过完 `isNeutral` 与剥离之后仍然是空层
        val layer = ObjectLayer(ObjectScope.Person, EditParams(vignetteAmount = -1f, grainAmount = 1f))
        assertFalse(layer.params.isNeutral())
        assertTrue(layer.params.withoutWholeImageStages().isNeutral())
        assertSame(LayerStack.EMPTY, stackOf(layer))
    }

    // ————————————————————————————————————————————————————————————
    // 4d. 叠加代数（逐像素热路径的可测接缝）
    // ————————————————————————————————————————————————————————————

    private fun blend(base: Int, targets: List<Int>, weights: List<Float>): Int =
        blendStack8(base, targets.size, { k -> targets[k] }, { k -> weights[k] })

    @Test
    fun zeroLayersReturnsBaseBitForBit() {
        val bases = listOf(0x000000, 0xffffff, 0x123456, 0x00ff00, 0x80807f, 0x010203)
        for (b in bases) {
            val argb = 0xff000000.toInt() or b
            // lambda 一旦被调用就说明实现走了不该走的路 ⇒ 用 error 而不是返回常量
            assertEquals(argb, blendStack8(argb, 0, { _: Int -> error("target must not be computed") }, { _: Int -> error("weight must not be read") }))
            assertEquals(argb, blendStack8(argb, -3, { _: Int -> error("target must not be computed") }, { _: Int -> error("weight must not be read") }))
        }
    }

    @Test
    fun fullWeightEqualsTheTargetColour() {
        val base = 0xff102030.toInt()
        val target = 0xffa0b0c0.toInt()
        assertEquals(target, blend(base, listOf(target), listOf(1f)))
        // 权重 > 1 被钳到 1（而不是外插出越界颜色）
        assertEquals(target, blend(base, listOf(target), listOf(7f)))
    }

    @Test
    fun zeroWeightKeepsBaseAndDoesNotEvenComputeTheTarget() {
        val base = 0xff102030.toInt()
        assertEquals(base, blendStack8(base, 1, { _: Int -> error("weight 0 ⇒ 不该白算一轮 applySrgb8") }, { 0f }))
        // 负权重同样跳过
        assertEquals(base, blendStack8(base, 1, { _: Int -> error("negative weight must be skipped") }, { -0.5f }))
    }

    @Test
    fun halfWeightInterpolatesWithRoundHalfUp() {
        // 0 → 255 的 50% = 127.5 → 四舍五入 128（与 PixelProgram.finish 的收尾口径一致）
        assertEquals(0xff808080.toInt(), blend(0xff000000.toInt(), listOf(0xffffffff.toInt()), listOf(0.5f)))
    }

    @Test
    fun accumulatorStaysFloatUntilTheSingleFinalQuantisation() {
        // base=0，两层各 50% 朝白。
        //   单次量化：127.5 → +127.5×0.5 = 191.25 → 191
        //   每层各量化一次：round(127.5)=128 → 128 + 63.5 = 191.5 → 192
        // 差 1 LSB，正是「只在最后量化一次」这个设计的可见形态。
        // 若将来有人把累加器改成 Int，这条会立刻变红。
        val white = 0xffffffff.toInt()
        assertEquals(0xffbfbfbf.toInt(), blend(0xff000000.toInt(), listOf(white, white), listOf(0.5f, 0.5f)))
    }

    @Test
    fun layersAreAppliedInOrderAndOrderChangesTheResult() {
        val white = 0xffffffff.toInt()
        val black = 0xff000000.toInt()
        // 先朝白 50%（→127.5）再朝黑 50%（→63.75 → 64）
        assertEquals(0xff404040.toInt(), blend(0xff000000.toInt(), listOf(white, black), listOf(0.5f, 0.5f)))
        // 反过来：先朝黑（不变）再朝白 50%（→127.5 → 128）
        assertEquals(0xff808080.toInt(), blend(0xff000000.toInt(), listOf(black, white), listOf(0.5f, 0.5f)))
    }

    @Test
    fun outputIsAlwaysOpaque() {
        val out = blend(0xff102030.toInt(), listOf(0xff000000.toInt(), 0xffffffff.toInt()), listOf(0.25f, 0.75f))
        assertEquals(0xff, (out ushr 24) and 0xff)
    }

    @Test
    fun clamp8RoundsHalfUpAndSaturates() {
        assertEquals(0, clamp8(-1f))
        assertEquals(0, clamp8(0f))
        assertEquals(0, clamp8(0.4999f))
        assertEquals(1, clamp8(0.5f))
        assertEquals(2, clamp8(1.5f))
        assertEquals(254, clamp8(254.4f))
        assertEquals(255, clamp8(254.5f))
        assertEquals(255, clamp8(255f))
        assertEquals(255, clamp8(1e9f))
    }
}
