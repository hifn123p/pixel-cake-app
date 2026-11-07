package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1（审计 P1-11）：参数栈 ⇄ JSON 的四条不变量 ——
 * round-trip 逐字段相等 / 未知键容忍 / 坏串拒绝 / 版本迁移。
 */
class EditSnapshotJsonTest {

    private val doc = EditDocument(
        version = EditSnapshotJson.CURRENT_VERSION,
        params = EditParams(
            exposureEv = 0.6f, contrast = 0.4f, temperature = 0.3f,
            vignetteAmount = 0.5f, grainAmount = 0.4f, grainSize = 0.6f,
            sharpenAmount = 0.8f, lutId = "warm", lutIntensity = 0.9f,
            lumaPoints = listOf(0 to 4, 128 to 128, 255 to 251),
            hsl = HslMix().withSat(2, 0.4f),
            grading = ColorGrading(shadows = GradingBand(hue = 0.55f, sat = 0.3f))
        ),
        retouch = RetouchState(
            beauty = BeautyParams(slimFace = 0.4f, eyeEnlarge = 0.2f),
            colorTransfer = ColorTransferParams(refId = "mono", intensity = 0.8f)
        ),
        layers = listOf(
            ObjectLayer(ObjectScope.FaceSkin, EditParams(exposureEv = 0.25f), strength = 0.8f),
            ObjectLayer(ObjectScope.Background, EditParams(saturation = -0.15f))
        )
    )

    @Test
    fun roundtrip_isFieldEqual() {
        val back = EditSnapshotJson.decode(EditSnapshotJson.encode(doc))
        assertEquals(doc, back)
    }

    @Test
    fun encode_isFullyQualified() {
        val json = EditSnapshotJson.encode(EditDocument())
        // encodeDefaults = true ⇒ 键集自描述：version / exposureEv / layers 全部显式在档
        // —— version 缺席会让「升版本后的迁移」从根上失效（见 EditSnapshotJson 的注释）
        assertTrue(json.contains("\"version\":1"))
        assertTrue(json.contains("\"exposureEv\":0.0"))
        assertTrue(json.contains("\"layers\":[]"))
        // 全量编码的中性存档仍在 KB 级（45 个字段 + 嵌套），对预设/项目文件无感
        assertTrue(json.length < 6000)
    }

    @Test
    fun decode_toleratesUnknownKeys() {
        val json = EditSnapshotJson.encode(doc)
        // 模拟「更新版本的文档被旧代码读到」：多出的键必须被丢弃而不是抛错
        val patched = json.replace("{\"version\"", "{\"future_key\":1,\"version\"")
        assertEquals(doc, EditSnapshotJson.decode(patched))
    }

    @Test
    fun decode_corruptText_returnsNull() {
        // 「不是本格式」必须显式报告，绝不静默回落默认值覆盖用户编辑
        assertNull(EditSnapshotJson.decode("not json at all"))
        assertNull(EditSnapshotJson.decode("{\"version\":\"x\"}"))
    }

    @Test
    fun decode_oldVersion_migratesToCurrent() {
        val v0 = EditDocument(version = 0, params = EditParams(exposureEv = 0.5f))
        val back = EditSnapshotJson.decode(EditSnapshotJson.encode(v0))
        assertNotEquals(null, back)
        assertEquals(EditSnapshotJson.CURRENT_VERSION, back!!.version)
        // 迁移只动版本号，不碰内容
        assertEquals(0.5f, back.params.exposureEv, 1e-6f)
    }

    @Test
    fun roundtrip_layersSurviveScopeEnum() {
        val back = EditSnapshotJson.decode(EditSnapshotJson.encode(doc))!!
        assertEquals(listOf(ObjectScope.FaceSkin, ObjectScope.Background), back.layers.map { it.scope })
        assertEquals(0.8f, back.layers[0].strength, 1e-6f)
    }
}
