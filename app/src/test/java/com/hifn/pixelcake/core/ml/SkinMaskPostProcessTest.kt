package com.hifn.pixelcake.core.ml

import com.hifn.pixelcake.core.edit.ObjectScope
import com.hifn.pixelcake.core.edit.ScopePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 后处理纯函数单测（P1p-1，批次 5 扩展）。
 *
 * 这些函数不依赖 Android / LiteRT，是 ML 蒙版可被 JVM 验证的部分。
 *
 * 批次 5 之前这里只测 [SkinMaskPostProcess.skinProbability]（皮肤一路）。对象作用域把
 * 6 类概率全接了出来（[SkinMaskPostProcess.scopeProbability]），于是这里多守三件事：
 * 1. **并集语义**：一个作用域 = 其成员槽位的概率之和（不是 `max`、不是取子蒙版）；
 * 2. **累加顺序与调用方给的 `Set` 无关**（浮点加法不满足结合律 ⇒ 否则预览/导出会 1ulp 分叉）；
 * 3. **重构等价性**：`skinProbability` 必须与 `scopeProbability(Skin)` 逐位相同
 *    （它现在只是后者的一个特例，`MlSkinMask` 仍走前者）。
 */
class SkinMaskPostProcessTest {

    /** 构造 `side × side` 的 channel-last 概率数组，每像素六类全部取同一个值。 */
    private fun uniformProbs(side: Int, v: Float): FloatArray =
        FloatArray(side * side * SkinMaskPostProcess.CLASSES) { v }

    // ————————————————————————————————————————————————————————————
    // 皮肤一路（P1p-1 既有）
    // ————————————————————————————————————————————————————————————

    @Test
    fun skinProbabilitySumsBodyAndFaceSkin() {
        val side = 2
        val probs = FloatArray(side * side * SkinMaskPostProcess.CLASSES)
        // 像素 0：body=0.4, face=0.5 → 0.9
        probs[0 * 6 + SkinMaskPostProcess.CLASS_BODY_SKIN] = 0.4f
        probs[0 * 6 + SkinMaskPostProcess.CLASS_FACE_SKIN] = 0.5f
        // 像素 1：body=0, face=0 （纯背景）
        // 像素 2：body=0.1, face=0.2 → 0.3
        probs[2 * 6 + SkinMaskPostProcess.CLASS_BODY_SKIN] = 0.1f
        probs[2 * 6 + SkinMaskPostProcess.CLASS_FACE_SKIN] = 0.2f
        // 像素 3：body=0.7, face=0.7 → 截断到 1.0
        probs[3 * 6 + SkinMaskPostProcess.CLASS_BODY_SKIN] = 0.7f
        probs[3 * 6 + SkinMaskPostProcess.CLASS_FACE_SKIN] = 0.7f

        val skin = SkinMaskPostProcess.skinProbability(probs, side)
        assertEquals(4, skin.size)
        assertEquals(0.9f, skin[0], 1e-6f)
        assertEquals(0f, skin[1], 0f)
        assertEquals(0.3f, skin[2], 1e-6f)
        assertEquals(1f, skin[3], 0f)
    }

    @Test
    fun skinProbabilityClampsNegativeToZero() {
        val probs = uniformProbs(1, -0.5f)
        val skin = SkinMaskPostProcess.skinProbability(probs, 1)
        assertEquals(0f, skin[0], 0f)
    }

    @Test
    fun skinProbabilityRejectsTooShortInput() {
        assertThrows(IllegalArgumentException::class.java) {
            SkinMaskPostProcess.skinProbability(FloatArray(5), 1)
        }
    }

    // ————————————————————————————————————————————————————————————
    // 语义槽位 → 模型通道（批次 5 新增的**唯一**映射点）
    // ————————————————————————————————————————————————————————————

    @Test
    fun classIndexOfMapsEverySlotToADistinctChannel() {
        val channels = ScopePart.entries.map { SkinMaskPostProcess.classIndexOf(it) }
        // 两两不同（否则两个作用域会指向同一路概率，且不会有任何报错）
        assertEquals(ScopePart.entries.size, channels.toSet().size)
        // 恰好覆盖模型的 6 个通道，不重不漏
        assertEquals((0 until SkinMaskPostProcess.CLASSES).toSet(), channels.toSet())
        // 与 MediaPipe selfie_multiclass 的通道顺序对位（写反了画面就会「圈错东西但不崩」）
        assertEquals(SkinMaskPostProcess.CLASS_BACKGROUND, SkinMaskPostProcess.classIndexOf(ScopePart.Background))
        assertEquals(SkinMaskPostProcess.CLASS_HAIR, SkinMaskPostProcess.classIndexOf(ScopePart.Hair))
        assertEquals(SkinMaskPostProcess.CLASS_BODY_SKIN, SkinMaskPostProcess.classIndexOf(ScopePart.BodySkin))
        assertEquals(SkinMaskPostProcess.CLASS_FACE_SKIN, SkinMaskPostProcess.classIndexOf(ScopePart.FaceSkin))
        assertEquals(SkinMaskPostProcess.CLASS_CLOTHES, SkinMaskPostProcess.classIndexOf(ScopePart.Clothes))
        assertEquals(SkinMaskPostProcess.CLASS_OTHERS, SkinMaskPostProcess.classIndexOf(ScopePart.Others))
    }

    // ————————————————————————————————————————————————————————————
    // 并集语义与确定性（批次 5）
    // ————————————————————————————————————————————————————————————

    @Test
    fun scopeProbabilitySumsOnlyMembersOfTheScope() {
        val side = 2
        val probs = FloatArray(side * side * SkinMaskPostProcess.CLASSES)
        // 像素 0：背景 0.5 + 头发 0.5（其余四类为 0）
        probs[0 * 6 + SkinMaskPostProcess.CLASS_BACKGROUND] = 0.5f
        probs[0 * 6 + SkinMaskPostProcess.CLASS_HAIR] = 0.5f
        // 像素 1：六类各 0.3（和 = 1.8 ⇒ 必然触发钳位）
        for (c in 0 until SkinMaskPostProcess.CLASSES) probs[1 * 6 + c] = 0.3f

        val background = SkinMaskPostProcess.scopeProbability(probs, side, ObjectScope.Background.parts)
        val hair = SkinMaskPostProcess.scopeProbability(probs, side, ObjectScope.Hair.parts)
        val others = SkinMaskPostProcess.scopeProbability(probs, side, ObjectScope.Accessories.parts)
        val person = SkinMaskPostProcess.scopeProbability(probs, side, ObjectScope.Person.parts)

        assertEquals(0.5f, background[0], 1e-6f)
        assertEquals(0.5f, hair[0], 1e-6f)
        assertEquals(0f, others[0], 0f)   // 本像素没有 others
        assertEquals(0.5f, person[0], 1e-6f) // 除背景 = 头发 0.5（背景不计入）
        assertEquals(1f, person[1], 0f)      // 5 × 0.3 = 1.5 → 钳到 1
    }

    @Test
    fun scopeProbabilityIsIndependentOfSetIterationOrder() {
        val side = 3
        val probs = FloatArray(side * side * SkinMaskPostProcess.CLASSES)
        // 取**非二进制可精确表示**的一批值：这样「累加顺序变了 ⇒ 结果差 1ulp」才会真的发生，
        // 用 0.5/0.25 这类值测这条是空转（怎么加都精确相等）。
        for (i in 0 until side * side) {
            probs[i * 6 + SkinMaskPostProcess.CLASS_BACKGROUND] = 0.05f
            probs[i * 6 + SkinMaskPostProcess.CLASS_HAIR] = 0.11f
            probs[i * 6 + SkinMaskPostProcess.CLASS_BODY_SKIN] = 0.13f
            probs[i * 6 + SkinMaskPostProcess.CLASS_FACE_SKIN] = 0.17f
            probs[i * 6 + SkinMaskPostProcess.CLASS_CLOTHES] = 0.19f
            probs[i * 6 + SkinMaskPostProcess.CLASS_OTHERS] = 0.23f
        }
        val parts = ObjectScope.Person.parts
        val keys = SkinMaskPostProcess.scopeProbability(probs, side, parts)
        // 反序插入的 LinkedHashSet / 默认 HashSet：若实现顺着调用方给的 Set 迭代，
        // 这里会分叉 ⇒ 表现为「同一组槽位、预览与导出给出不同像素」。
        val reversed = SkinMaskPostProcess.scopeProbability(probs, side, LinkedHashSet(parts.toList().reversed()))
        val hashed = SkinMaskPostProcess.scopeProbability(probs, side, HashSet(parts))
        for (i in keys.indices) {
            assertEquals("i=$i reversed", keys[i], reversed[i], 0f)
            assertEquals("i=$i hashed", keys[i], hashed[i], 0f)
        }
    }

    @Test
    fun skinProbabilityIsScopeProbabilityOfBodyPlusFaceSkin() {
        val side = 4
        val probs = FloatArray(side * side * SkinMaskPostProcess.CLASSES)
        val rnd = java.util.Random(7)
        for (i in probs.indices) probs[i] = rnd.nextFloat()

        val a = SkinMaskPostProcess.skinProbability(probs, side)
        val b = SkinMaskPostProcess.scopeProbability(probs, side, setOf(ScopePart.BodySkin, ScopePart.FaceSkin))
        // 同时钉住「皮肤作用域的定义 == 磨皮那一路」——两条链路必须指的是同一组槽位
        val c = SkinMaskPostProcess.scopeProbability(probs, side, ObjectScope.Skin.parts)
        for (i in a.indices) {
            assertEquals("i=$i explicit", a[i], b[i], 0f)
            assertEquals("i=$i scope", a[i], c[i], 0f)
        }
    }

    @Test
    fun scopeProbabilityOfEmptyPartSetIsAllZero() {
        // 空集不抛异常、恒返回 0（不是「不执行」——蒙版的 null 才有那个语义）
        val v = SkinMaskPostProcess.scopeProbability(uniformProbs(2, 0.5f), 2, emptySet())
        assertEquals(4, v.size)
        for (x in v) assertEquals(0f, x, 0f)
    }

    @Test
    fun scopeProbabilityRejectsBadInput() {
        val parts = ObjectScope.Person.parts
        assertThrows(IllegalArgumentException::class.java) {
            SkinMaskPostProcess.scopeProbability(FloatArray(5), 1, parts)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkinMaskPostProcess.scopeProbability(FloatArray(6), 0, parts)
        }
    }

    // ————————————————————————————————————————————————————————————
    // 阈值 / 平滑（P1p-1 既有）
    // ————————————————————————————————————————————————————————————

    @Test
    fun thresholdAndFeatherMapsLinearly() {
        val v = floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f)
        val out = SkinMaskPostProcess.thresholdAndFeather(v, 0.35f, 0.65f)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals(0.5f, out[2], 1e-6f)
        assertEquals(1f, out[3], 1e-6f)
        assertEquals(1f, out[4], 1e-6f)
    }

    @Test
    fun thresholdAndFeatherIsInPlaceSafe() {
        val v = floatArrayOf(0.2f, 0.8f)
        SkinMaskPostProcess.thresholdAndFeather(v, 0.3f, 0.7f, v) // out === v
        assertEquals(0f, v[0], 1e-6f)
        assertEquals(1f, v[1], 1e-6f)
    }

    @Test
    fun thresholdAndFeatherDegeneratesToHardThresholdWhenSpanNonPositive() {
        val v = floatArrayOf(0.49f, 0.5f, 0.9f)
        val out = SkinMaskPostProcess.thresholdAndFeather(v, 0.5f, 0.5f)
        assertEquals(0f, out[0], 0f)
        assertEquals(1f, out[1], 0f)
        assertEquals(1f, out[2], 0f)
    }

    @Test
    fun smooth3x3AveragesOverPresentNeighbours() {
        val side = 3
        val v = FloatArray(side * side)
        v[1 * side + 1] = 1f // 仅中心为 1
        SkinMaskPostProcess.smooth3x3(v, side)
        // 按「窗口内实际存在的邻居」取均值：中心满 3×3 窗口 → 1/9；角落仅 4 格 → 1/4
        assertEquals(1f / 9f, v[1 * side + 1], 1e-6f)
        assertEquals(1f / 4f, v[0], 1e-6f)
        assertEquals(1f / 4f, v[2 * side + 2], 1e-6f)
    }

    @Test
    fun smooth3x3UsesExistingNeighbourCountAtCorners() {
        val side = 3
        val v = FloatArray(side * side) { 1f }
        SkinMaskPostProcess.smooth3x3(v, side)
        // 全 1 平滑后仍全 1（角落邻居数少但均值不变）
        for (x in v) assertEquals(1f, x, 1e-6f)
    }
}
