package com.hifn.pixelcake.core.ml

import com.hifn.pixelcake.core.edit.ObjectScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U4「空作用域禁用」的数据源：`ObjectMasks.coveredScopes`。
 * 直接喂 channel-last 概率，验证原生类逐类计数与派生类求和口径。
 */
class CoveredScopesTest {

    private fun masks(probs: FloatArray, side: Int = 8): ObjectMasks =
        ObjectMasks.fromProbs(probs, side, side, side)

    /** 把 (x, y) 处的 [cls] 号类概率设为 [p]（其余通道保持 0）。 */
    private fun setProb(probs: FloatArray, side: Int, x: Int, y: Int, cls: Int, p: Float) {
        probs[(y * side + x) * SkinMaskPostProcess.CLASSES + cls] = p
    }

    @Test
    fun allZero_coversNothing() {
        val m = masks(FloatArray(8 * 8 * SkinMaskPostProcess.CLASSES))
        assertTrue(m.coveredScopes(minPixels = 1).isEmpty())
    }

    @Test
    fun faceSkin_coversNativeAndDerivatives() {
        val side = 8
        val p = FloatArray(side * side * SkinMaskPostProcess.CLASSES)
        // 49 个像素 > 默认阈值 48 —— 同时验证默认 minPixels 在小网格上可用
        for (i in 0 until 49) {
            setProb(p, side, i % side, i / side, SkinMaskPostProcess.CLASS_FACE_SKIN, 0.9f)
        }
        val covered = masks(p, side).coveredScopes()
        assertTrue(ObjectScope.FaceSkin in covered)
        // 派生作用域是并集：面部有 ⇒ 皮肤/人物也有
        assertTrue(ObjectScope.Skin in covered)
        assertTrue(ObjectScope.Person in covered)
        assertFalse(ObjectScope.Hair in covered)
        assertFalse(ObjectScope.Clothes in covered)
    }

    @Test
    fun belowFeatherFloor_ignored() {
        val side = 8
        val p = FloatArray(side * side * SkinMaskPostProcess.CLASSES)
        for (i in 0 until 60) {
            // 0.30 < LO(0.35)：羽化下界以下的概率不算「存在」
            setProb(p, side, i % side, i / side, SkinMaskPostProcess.CLASS_BODY_SKIN, 0.30f)
        }
        assertTrue(masks(p, side).coveredScopes().isEmpty())
    }

    @Test
    fun derivedScope_usesSumNotPerClass() {
        val side = 8
        val p = FloatArray(side * side * SkinMaskPostProcess.CLASSES)
        for (i in 0 until 60) {
            // 每类单独 0.25 都低于 LO，但求和 0.50 > LO —— Skin/Person 必须按求和口径覆盖，
            // 逐类计数会漏掉这种「两类各摊一半」的像素（与 scopeProbability 同口径）。
            setProb(p, side, i % side, i / side, SkinMaskPostProcess.CLASS_FACE_SKIN, 0.25f)
            setProb(p, side, i % side, i / side, SkinMaskPostProcess.CLASS_BODY_SKIN, 0.25f)
        }
        val covered = masks(p, side).coveredScopes()
        assertTrue(ObjectScope.Skin in covered)
        assertTrue(ObjectScope.Person in covered)
        // 原生两类自身都低于阈值，不覆盖
        assertFalse(ObjectScope.FaceSkin in covered)
    }

    @Test
    fun minPixels_filtersNoise() {
        val side = 8
        val p = FloatArray(side * side * SkinMaskPostProcess.CLASSES)
        // 只有一个像素的「头发」：默认阈值(48)下是噪声、不算存在；minPixels=1 下算
        setProb(p, side, 0, 0, SkinMaskPostProcess.CLASS_HAIR, 0.9f)
        assertFalse(ObjectScope.Hair in masks(p, side).coveredScopes())
        assertTrue(ObjectScope.Hair in masks(p, side).coveredScopes(minPixels = 1))
    }
}
