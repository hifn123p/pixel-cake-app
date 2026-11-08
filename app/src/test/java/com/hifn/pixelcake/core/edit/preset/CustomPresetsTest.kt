package com.hifn.pixelcake.core.edit.preset

import com.hifn.pixelcake.core.edit.ColorTransferParams
import com.hifn.pixelcake.core.edit.EditParams
import com.hifn.pixelcake.core.edit.HslMix
import com.hifn.pixelcake.core.edit.InpaintStroke
import com.hifn.pixelcake.core.edit.RetouchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 用户预设库（v0.6.6）：持久化层的不变量 ——
 * round-trip 逐字段相等 / capture 剥离瑕疵描迹 / 坏文件留证不覆盖 / 原子写无残留。
 */
class CustomPresetsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleRetouch() = RetouchState(
        colorTransfer = ColorTransferParams(refId = "jp", intensity = 0.6f)
    )

    private fun sampleParams() = EditParams(
        exposureEv = 0.6f, contrast = 0.3f,
        hsl = HslMix().withSat(2, 0.4f),
        lutId = "warm", lutIntensity = 0.9f
    )

    @Test
    fun capture_stripsInpaintStrokes() {
        val p = CustomPreset.capture(
            "影楼人像", sampleParams(),
            sampleRetouch().copy(inpaint = listOf(InpaintStroke(10, 20, 5))),
            now = 42L
        )
        assertTrue("瑕疵描迹属于这张图，不进预设", p.retouch.inpaint.isEmpty())
        assertEquals("影楼人像", p.name)
        assertTrue("id 带 custom: 前缀，与内置预设永不冲突", p.id.startsWith("custom:"))
        assertEquals(42L, p.createdAt)
    }

    @Test
    fun asPreset_mapsToCustomCategory() {
        val p = CustomPreset.capture("我的", sampleParams(), RetouchState(), 1L)
        val preset = p.asPreset()
        assertTrue(preset.id.startsWith("custom:"))
        assertEquals(PresetCategory.Custom, preset.category)
        assertEquals(p.params, preset.params)
        assertEquals(p.retouch, preset.retouch)
    }

    @Test
    fun encodeDecode_roundtrip() {
        val list = listOf(
            CustomPreset.capture("甲", sampleParams(), sampleRetouch(), 1L),
            CustomPreset.capture("乙", EditParams(), RetouchState(), 2L)
        )
        val back = CustomPresets.decode(CustomPresets.encode(list))
        assertEquals(list, back)
    }

    @Test
    fun decode_toleratesUnknownKeys() {
        val json = CustomPresets.encode(listOf(CustomPreset.capture("甲", sampleParams(), RetouchState(), 1L)))
        val patched = json.replace("{\"version\"", "{\"future\":true,\"version\"")
        assertEquals(1, CustomPresets.decode(patched).size)
    }

    @Test
    fun withFreshIds_regenOnlyTaken() {
        val a = CustomPreset.capture("甲", EditParams(), RetouchState(), 1L)
        val b = CustomPreset.capture("乙", EditParams(), RetouchState(), 2L)
        val existing = listOf(a)
        val out = CustomPresets.withFreshIdsWhereTaken(listOf(a, b), existing)
        assertEquals(2, out.size)
        assertTrue("未冲突的条目 id 原样保留", out[0].id == a.id)
        assertTrue("冲突的条目重新生成 id", out[1].id != b.id && out[1].id.startsWith("custom:"))
        assertEquals("名称原样保留", "乙", out[1].name)
    }

    @Test
    fun store_saveLoad_roundtrip() {
        val store = CustomPresets.Store(tmp.newFile("presets.json"))
        val list = listOf(CustomPreset.capture("甲", sampleParams(), sampleRetouch(), 7L))
        store.save(list)
        assertEquals(list, store.load())
    }

    @Test
    fun store_missingFile_returnsEmpty() {
        val store = CustomPresets.Store(tmp.root.resolve("not_exist.json"))
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun store_corruptFile_keepsEvidenceAndReturnsEmpty() {
        val f = tmp.newFile("presets.json")
        f.writeText("{ this is not json")
        val store = CustomPresets.Store(f)
        assertTrue("坏文件按空处理", store.load().isEmpty())
        assertTrue(
            ".corrupt 留证副本必须存在（下次 save 不覆盖用户数据）",
            tmp.root.resolve("presets.json.corrupt").exists()
        )
    }

    @Test
    fun store_saveThenLoad_overwritesCleanly() {
        val store = CustomPresets.Store(tmp.newFile("presets.json"))
        store.save(listOf(CustomPreset.capture("甲", EditParams(), RetouchState(), 1L)))
        store.save(emptyList())
        assertTrue("保存空列表后重读应为空（删除链路）", store.load().isEmpty())
        assertFalse("原子写不留 .tmp 残留", tmp.root.resolve("presets.json.tmp").exists())
    }
}
