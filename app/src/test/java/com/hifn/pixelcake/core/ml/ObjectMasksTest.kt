package com.hifn.pixelcake.core.ml

import com.hifn.pixelcake.core.edit.ObjectScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ObjectMasks] / [ObjectMask] 单测（批次 5，`docs/OBJECT_TONE_DESIGN.md` §3 / §5.5 / §8）。
 *
 * 这个文件零 Android 依赖（`FloatGrid` / `ObjectMask` / `ObjectMasks` 都是纯 Kotlin），
 * 于是「推理产出怎么变成 8 路蒙版」这件事可以在 JVM 上完整验证，不需要设备也不需要模型。
 *
 * 守的是四件事：
 * 1. **每个作用域只看自己的槽位**（含两个派生作用域）；
 * 2. **「皮肤作用域」与「磨皮自动蒙版」逐位相同** —— 用户不该看到两套不同的皮肤；
 * 3. **懒构建 + 按作用域缓存**（上界 8 个网格 ≈ 2MB，不是每次调用建一个）；
 * 4. **[ObjectMasks.resampleTo] 只换目标尺寸、共享网格**（`FloatArray(7008×4672)` = 131MB 那条红线）。
 */
class ObjectMasksTest {

    private val side = 8

    /**
     * 造一张概率图：`[x0,x1) × [y0,y1)` 内 **face-skin = 1**，其余全部是 **background = 1**
     * （softmax 的六类和为 1，这里只填两个通道、其余留 0，等价于「要么是人脸要么是背景」）。
     */
    private fun faceBlockProbs(
        x0: Int = 2, y0: Int = 2, x1: Int = 6, y1: Int = 6
    ): FloatArray {
        val p = FloatArray(side * side * SkinMaskPostProcess.CLASSES)
        for (y in 0 until side) {
            for (x in 0 until side) {
                val cls = if (x in x0 until x1 && y in y0 until y1) {
                    SkinMaskPostProcess.CLASS_FACE_SKIN
                } else {
                    SkinMaskPostProcess.CLASS_BACKGROUND
                }
                p[(y * side + x) * SkinMaskPostProcess.CLASSES + cls] = 1f
            }
        }
        return p
    }

    // ————————————————————————————————————————————————————————————
    // 1. 每个作用域只看自己的槽位
    // ————————————————————————————————————————————————————————————

    @Test
    fun eachScopeSeesOnlyItsOwnSlot() {
        val m = ObjectMasks.fromProbs(faceBlockProbs(), side, side, side)

        // 采样点取块心 (4,4) 与角落 (0,0)，都离块边界 ≥2 格 ⇒ 不受 3×3 平滑影响
        assertEquals(1f, m.maskFor(ObjectScope.FaceSkin).sample(4, 4), 1e-6f)
        assertEquals(0f, m.maskFor(ObjectScope.FaceSkin).sample(0, 0), 1e-6f)

        assertEquals(1f, m.maskFor(ObjectScope.Background).sample(0, 0), 1e-6f)
        assertEquals(0f, m.maskFor(ObjectScope.Background).sample(4, 4), 1e-6f)

        // 派生：人物 = 除背景（块内为脸 ⇒ 命中；背景处 ⇒ 不命中）
        assertEquals(1f, m.maskFor(ObjectScope.Person).sample(4, 4), 1e-6f)
        assertEquals(0f, m.maskFor(ObjectScope.Person).sample(0, 0), 1e-6f)

        // 派生：皮肤 = 身体 ∪ 面部（本图只有脸 ⇒ 仍然命中）
        assertEquals(1f, m.maskFor(ObjectScope.Skin).sample(4, 4), 1e-6f)
        // 但「身体」这个原生槽位在本图里是 0 ⇒ 面部与身体确实是两路独立的概率，
        // 派生作用域是真的「并集」，不是「皮肤 = 面部」这种简化。
        assertEquals(0f, m.maskFor(ObjectScope.BodySkin).sample(4, 4), 0f)
    }

    @Test
    fun scopeWithNoPixelsInThisPhotoYieldsALegalAllZeroMask() {
        // 「这张图里没有头发」是物理事实，不是错误：必须是合法的全 0 蒙版
        // （返回 null 会被上游读成「无法提供蒙版」，与「没有」混为一谈）。
        val m = ObjectMasks.fromProbs(faceBlockProbs(), side, side, side)
        for (scope in listOf(ObjectScope.Hair, ObjectScope.Clothes, ObjectScope.Accessories)) {
            val mask = m.maskFor(scope)
            for (y in 0 until side) for (x in 0 until side) {
                assertEquals("$scope x=$x y=$y", 0f, mask.sample(x, y), 0f)
            }
        }
    }

    @Test
    fun personIsTheComplementOfBackgroundOnASoftmaxPartition() {
        // 六类和为 1 时「除背景」既等于其余五类之和、也等于 1 − 背景。
        // 实现取求和（对未归一化输出同样成立），这里验证它与互补关系一致。
        val p = FloatArray(side * side * SkinMaskPostProcess.CLASSES)
        for (i in 0 until side * side) {
            p[i * 6 + SkinMaskPostProcess.CLASS_BACKGROUND] = 0.4f
            p[i * 6 + SkinMaskPostProcess.CLASS_HAIR] = 0.1f
            p[i * 6 + SkinMaskPostProcess.CLASS_CLOTHES] = 0.5f
        }
        val m = ObjectMasks.fromProbs(p, side, side, side)
        val person = m.maskFor(ObjectScope.Person)
        val background = m.maskFor(ObjectScope.Background)
        for (y in 0 until side) for (x in 0 until side) {
            assertEquals("x=$x y=$y", 1f - background.sample(x, y), person.sample(x, y), 1e-6f)
        }
    }

    // ————————————————————————————————————————————————————————————
    // 2. 「皮肤作用域」== 「磨皮自动蒙版」
    // ————————————————————————————————————————————————————————————

    @Test
    fun skinScopeIsBitIdenticalToTheAutomaticSkinMask() {
        // 两者都走 lo=0.35 / hi=0.65 + 一次 3×3 平滑（`ObjectMasks.LO/HI` 与 `MlSkinMask` 的默认值）。
        // 这条断言是「用户不会看到两套皮肤」这个承诺的唯一机器可验证形式。
        val p = FloatArray(side * side * SkinMaskPostProcess.CLASSES)
        for (i in 0 until side * side) {
            p[i * 6 + SkinMaskPostProcess.CLASS_BODY_SKIN] = 0.2f + (i % 5) * 0.2f
            p[i * 6 + SkinMaskPostProcess.CLASS_FACE_SKIN] = 0.15f + (i % 3) * 0.3f
        }
        val targetW = 40
        val targetH = 24
        val autoMask = MlSkinMask.fromProbs(p, side, targetW, targetH)
        val fromScope = ObjectMasks.fromProbs(p, side, targetW, targetH).maskFor(ObjectScope.Skin)

        for (y in 0 until targetH) for (x in 0 until targetW) {
            assertEquals("x=$x y=$y", autoMask.sample(x, y), fromScope.sample(x, y), 0f)
        }
    }

    @Test
    fun thresholdConstantsMatchTheAutomaticMaskDefaults() {
        // 常量漂移会让上面那条等价性测试仍然通过（它比较的是两条同源链路）、
        // 但用户会在界面上看到两套皮肤。所以这里把常量本身也钉住。
        assertEquals(0.35f, ObjectMasks.LO, 0f)
        assertEquals(0.65f, ObjectMasks.HI, 0f)
    }

    // ————————————————————————————————————————————————————————————
    // 3. 懒构建 + 按作用域缓存
    // ————————————————————————————————————————————————————————————

    @Test
    fun gridsAreBuiltLazilyAndCachedPerScope() {
        val m = ObjectMasks.fromProbs(faceBlockProbs(), side, side, side)
        assertEquals(0, m.builtGridCount()) // 构造本身不构建任何网格

        m.maskFor(ObjectScope.Hair)
        assertEquals(1, m.builtGridCount())
        m.maskFor(ObjectScope.Hair)
        assertEquals(1, m.builtGridCount()) // 命中缓存，不重建

        m.maskFor(ObjectScope.Background)
        assertEquals(2, m.builtGridCount())

        // 8 个作用域全取一遍 ⇒ 恰好 8 个网格（≈2MB），不会更多
        ObjectScope.entries.forEach { m.maskFor(it) }
        assertEquals(ObjectScope.entries.size, m.builtGridCount())
        assertEquals(side, m.gridSide)
    }

    @Test
    fun maskCarriesItsScopeAndSurvivesResample() {
        val m = ObjectMasks.fromProbs(faceBlockProbs(), side, side, side)
        val mask = m.maskFor(ObjectScope.Clothes)
        assertTrue(mask is ObjectMask)
        assertEquals(ObjectScope.Clothes, (mask as ObjectMask).scope)
        // resampleTo 返回的仍是带 scope 的网格蒙版（不是「物化成整幅」的实现）
        val up = mask.resampleTo(7008, 4672)
        assertTrue(up is ObjectMask)
        assertEquals(ObjectScope.Clothes, (up as ObjectMask).scope)
    }

    @Test
    fun outOfBoundsSampleIsZero() {
        val mask = ObjectMasks.fromProbs(faceBlockProbs(), side, side, side).maskFor(ObjectScope.Person)
        assertEquals(0f, mask.sample(-1, 0), 0f)
        assertEquals(0f, mask.sample(0, -1), 0f)
        assertEquals(0f, mask.sample(side, 0), 0f)
        assertEquals(0f, mask.sample(0, side), 0f)
    }

    // ————————————————————————————————————————————————————————————
    // 4. resampleTo 只换目标尺寸、共享网格
    // ————————————————————————————————————————————————————————————

    @Test
    fun resampleToSharesTheGridsAndRebuildsNothing() {
        val m = ObjectMasks.fromProbs(faceBlockProbs(), side, side, side)
        m.maskFor(ObjectScope.Background) // 先建一个

        val up = m.resampleTo(64, 32)
        assertNotSame(m, up)
        assertEquals(side, up.gridSide)
        assertEquals(1, up.builtGridCount()) // 共享同一份 Grids ⇒ 计数与原件一致
        assertEquals(1, m.builtGridCount())

        // 在副本上构建新作用域，原件也看得到 ⇒ 证明两者确实共享同一个 Grids 实例
        up.maskFor(ObjectScope.Hair)
        assertEquals(2, m.builtGridCount())

        // 目标尺寸未变时返回自身（避免无谓分配）
        assertSame(m, m.resampleTo(side, side))
    }

    @Test
    fun resampledMaskMatchesADirectConstructionPointByPoint() {
        val probs = faceBlockProbs()
        val base = ObjectMasks.fromProbs(probs, side, side, side)
        base.maskFor(ObjectScope.Person) // 让原件先建好网格
        val up = base.resampleTo(7008, 4672)

        // 与「直接用同一概率、同一目标尺寸构造」的对象逐点一致 ⇒ 只换了目标尺寸，
        // 没有改数据、也没有物化 FloatArray(7008×4672)（那是 131MB）
        val direct = ObjectMasks.fromProbs(probs, side, 7008, 4672)
        val a = up.maskFor(ObjectScope.Person)
        val b = direct.maskFor(ObjectScope.Person)
        for (y in 0 until 4672 step 97) {
            for (x in 0 until 7008 step 151) {
                assertEquals("x=$x y=$y", b.sample(x, y), a.sample(x, y), 0f)
            }
        }
    }
}
