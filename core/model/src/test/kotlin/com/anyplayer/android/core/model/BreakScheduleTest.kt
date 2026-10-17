package com.anyplayer.android.core.model

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class BreakScheduleTest {
    private fun queue(count: Int = 20): QueueState = QueueOps.replace(QueueState(), (0 until count).map {
        Track(id = "$it", title = "$it", artist = "Artist", source = SourceType.CUSTOM, url = "file:///song$it.wav")
    }, 0, Random(0))
    private fun enabled(q: QueueState) = reduce(BreakSchedule(), ScheduleEvent.Enabled(true), q, Random(9))
    private fun move(s: BreakSchedule, q: QueueState, cursor: Int) = reduce(s, ScheduleEvent.CursorMoved, q.copy(cursor = cursor), Random(0))

    @Test fun `enable prepares future target immediately with random interval`() {
        val q = queue()
        for (seed in 0..100) {
            val s = reduce(BreakSchedule(), ScheduleEvent.Enabled(true), q, Random(seed))
            assertTrue(s.songsUntilBreak in 3..5)
            assertEquals(q.entryAt(s.songsUntilBreak + 1).uid, s.targetUid)
            assertEquals(s.songsUntilBreak, s.songsAway(q))
        }
    }
    @Test fun `forward jump counts every real song`() {
        val q = queue(); val s = enabled(q)
        val moved = move(s, q, 2)
        assertEquals(s.songsUntilBreak - 2, moved.songsUntilBreak)
        assertEquals(s.targetUid, moved.targetUid)
    }
    @Test fun `repeated cursor event does not count`() {
        val q = queue(); val s = enabled(q)
        assertEquals(s, move(s, q, 0))
    }
    @Test fun `backward movement reanchors without counting`() {
        val q = queue(); val s = move(enabled(q), q, 2)
        val back = move(s, q, 0)
        assertEquals(s.songsUntilBreak, back.songsUntilBreak)
        assertEquals(0, back.anchorOrderPos)
    }
    @Test fun `content edits reanchor without counting`() {
        val q = queue(); val s = enabled(q)
        val edited = q.copy(cursor = 2, contentVersion = q.contentVersion + 1)
        val result = reduce(s, ScheduleEvent.CursorMoved, edited, Random(0))
        assertEquals(s.songsUntilBreak, result.songsUntilBreak)
        assertEquals(edited.contentVersion, result.orderVersion)
    }
    @Test fun `DJ entries never count and are never targets`() {
        val q = queue(); val s = enabled(q)
        val entries = q.entries.mapIndexed { i, e -> if (i in 1..3) e.copy(track = e.track.copy(isDjFiller = true)) else e }
        val mixed = q.copy(entries = entries)
        val result = move(s, mixed, 3)
        assertEquals(s.songsUntilBreak, result.songsUntilBreak)
        assertFalse(mixed.entries.first { it.uid == result.targetUid }.track.isDjFiller)
    }
    @Test fun `unplayable entries are not break targets`() {
        val q = queue(); val s = enabled(q)
        val pos = q.orderPosOfUid(s.targetUid!!)
        val mixed = q.copy(entries = q.entries.mapIndexed { i, e -> if (i == pos) e.copy(track = e.track.copy(url = null)) else e })
        val result = reduce(s, ScheduleEvent.CursorMoved, mixed, Random(0))
        assertEquals(mixed.entryAt(pos + 1).uid, result.targetUid)
    }
    @Test fun `failed generation retries after three more real songs`() {
        val q = queue(); val failed = reduce(enabled(q), ScheduleEvent.GenerationFailed, q, Random(0))
        assertEquals(3, failed.songsUntilBreak); assertTrue(failed.failedOnCurrent)
        assertEquals(failed, move(failed, q, 0))
        val next = move(failed, q, 1)
        assertFalse(next.failedOnCurrent); assertEquals(2, next.songsUntilBreak)
    }
    @Test fun `missing due break resets to exactly three`() {
        val q = queue(); val s = enabled(q)
        val passed = move(s, q, s.songsUntilBreak + 1)
        assertEquals(3, passed.songsUntilBreak); assertTrue(passed.failedOnCurrent)
    }
    @Test fun `break start immediately schedules next break`() {
        val q = queue(); val s = enabled(q)
        val current = q.copy(cursor = s.songsUntilBreak)
        val next = reduce(s, ScheduleEvent.BreakStarted, current, Random(1))
        assertTrue(next.songsUntilBreak in 3..5)
        assertNotEquals(s.targetUid, next.targetUid)
    }
    @Test fun `disable clears target and UI distance`() {
        val q = queue(); val off = reduce(enabled(q), ScheduleEvent.Enabled(false), q, Random(0))
        assertNull(off.targetUid); assertNull(off.songsAway(q)); assertEquals(3, off.songsUntilBreak)
    }
    @Test fun `short queue has no target but retains schedule`() {
        val q = queue(2); val s = enabled(q)
        assertNull(s.targetUid); assertNotNull(s.songsAway(q))
    }
    @Test fun `duplicate track ids target unique queue slot`() {
        val q = queue().let { it.copy(entries = it.entries.map { e -> e.copy(track = e.track.copy(id = "duplicate")) }) }
        val s = enabled(q); assertEquals(q.entryAt(s.songsUntilBreak + 1).uid, s.targetUid)
    }
    @Test fun `queue replacement rerolls and clears failure`() {
        val q = queue(); val failed = reduce(enabled(q), ScheduleEvent.GenerationFailed, q, Random(0))
        val s = reduce(failed, ScheduleEvent.QueueReplaced, q, Random(2))
        assertTrue(s.songsUntilBreak in 3..5); assertFalse(s.failedOnCurrent)
    }
}
