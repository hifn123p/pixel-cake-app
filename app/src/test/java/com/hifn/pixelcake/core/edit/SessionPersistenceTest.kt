package com.hifn.pixelcake.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话持久化（进程死亡恢复，v0.7.2）的纯 JVM 回归：
 * ① EditHistory 时间线导出/恢复 round-trip（含撤销/重做位置）；
 * ② SessionRecord ⇄ JSON round-trip 与无效记录拒绝。
 */
class SessionPersistenceTest {

    private fun snap(ev: Float): EditSnapshot = EditSnapshot(params = EditParams(exposureEv = ev))
    private fun doc(ev: Float): EditDocument = EditDocument(params = EditParams(exposureEv = ev))

    @Test
    fun timeline_roundTrip_preservesUndoRedoPosition() {
        val h = EditHistory(snap(0.0f))
        h.push(snap(0.5f))
        h.push(snap(1.0f))
        h.push(snap(1.5f))
        h.undo() // current = 1.0，redo 栈里有 1.5
        assertEquals(2, h.step)
        assertEquals(1.0f, h.current.params.exposureEv)

        val (timeline, position) = h.exportTimeline()
        // 时间线 = [0.0, 0.5, 1.0, 1.5]，当前在下标 2
        assertEquals(4, timeline.size)
        assertEquals(2, position)

        val restored = EditHistory(snap(9.9f))
        restored.importTimeline(timeline, position)
        assertEquals(1.0f, restored.current.params.exposureEv)
        assertEquals(2, restored.step)
        assertTrue(restored.canRedo)
        restored.redo()
        assertEquals(1.5f, restored.current.params.exposureEv)
        restored.undo()
        restored.undo()
        restored.undo()
        assertEquals(0.0f, restored.current.params.exposureEv)
        assertFalse(restored.canUndo)
    }

    @Test
    fun importTimeline_ignoresInvalidInput() {
        val h = EditHistory(snap(3.21f))
        h.importTimeline(emptyList(), 0)
        h.importTimeline(listOf(snap(1f)), 5)
        assertEquals(3.21f, h.current.params.exposureEv)
        assertFalse(h.canUndo)
    }

    @Test
    fun timeline_roundTrip_preservesPerStepRotation() {
        // v0.7.3（运行时审计 P2-1）：旋转发生在时间线中途 —— 每步文档必须各自携带
        // 当时的旋转圈数，否则「撤销回旋转之前」在进程死亡恢复后失效。
        val h = EditHistory(snap(0.0f))
        h.push(EditSnapshot(params = EditParams(exposureEv = 0.5f), rotationQuarterTurns = 1))
        h.push(EditSnapshot(params = EditParams(exposureEv = 1.0f), rotationQuarterTurns = 1))
        h.push(EditSnapshot(params = EditParams(exposureEv = 1.5f), rotationQuarterTurns = 0))
        val (timeline, position) = h.exportTimeline()
        val record = SessionRecord(
            srcUri = "u", rotationQuarterTurns = 0, position = position,
            timeline = timeline.map {
                EditDocument(
                    params = it.params, retouch = it.retouch,
                    layers = it.layers, rotationQuarterTurns = it.rotationQuarterTurns
                )
            }
        )
        val restored = SessionJson.decode(SessionJson.encode(record))!!
        val snapshots = restored.timeline.map {
            EditSnapshot(
                params = it.params, retouch = it.retouch,
                layers = it.layers, rotationQuarterTurns = it.rotationQuarterTurns
            )
        }
        assertEquals(listOf(0, 1, 1, 0), snapshots.map { it.rotationQuarterTurns })
    }


    @Test
    fun sessionJson_roundTrip() {
        val record = SessionRecord(
            srcUri = "content://media/external/images/media/23",
            rotationQuarterTurns = 2,
            position = 1,
            timeline = listOf(doc(0.0f), doc(0.7f))
        )
        val decoded = SessionJson.decode(SessionJson.encode(record))
        assertEquals(record, decoded)
    }

    @Test
    fun sessionJson_rejectsInvalidRecords() {
        // 空时间线 / 越位 position / 非 JSON 三类都必须拒绝 —— 半截写入与脏数据不能复活成会话
        assertNull(SessionJson.decode("""{"version":1,"srcUri":"","position":0,"timeline":[]}"""))
        assertNull(
            SessionJson.decode(
                SessionJson.encode(SessionRecord(srcUri = "u", timeline = listOf(doc(0f)), position = 3))
            )
        )
        assertNull(SessionJson.decode("not json at all"))
    }

    @Test
    fun sessionJson_unknownKeysIgnored() {
        // 新版本写的 JSON 被旧代码读到：丢弃未知键继续（兼容性纪律）
        val text = SessionJson.encode(SessionRecord(srcUri = "u", timeline = listOf(doc(1f))))
            .replace("""{"version":1""", """{"version":1,"futureField":true""")
        val decoded = SessionJson.decode(text)
        assertEquals("u", decoded?.srcUri)
    }
}
