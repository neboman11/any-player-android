package com.anyplayer.android.core.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueOpsTest {
    private val tracks = listOf("a", "b", "c", "d", "e").map { jf(it) }

    private fun queue(start: Int = 0, shuffle: Boolean = false, seed: Int = 1): QueueState =
        QueueOps.replace(QueueState(shuffle = shuffle), tracks, start, Random(seed))

    @Test
    fun replace_withoutShuffle_keepsInsertionOrderAndStartIndex() {
        val s = queue(start = 2)
        assertEquals(listOf(0, 1, 2, 3, 4), s.order)
        assertEquals(2, s.cursor)
        assertEquals("c", s.current?.track?.id)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), s.entries.map { it.uid })
        assertEquals(6L, s.nextUid)
        assertEquals(1L, s.contentVersion)
    }

    @Test
    fun replace_emptyTracks_clearsQueue() {
        val s = QueueOps.replace(queue(), emptyList(), 0, Random(1))
        assertTrue(s.entries.isEmpty())
        assertEquals(-1, s.cursor)
        assertNull(s.current)
    }

    @Test
    fun replace_startIndexIsClamped() {
        assertEquals(4, QueueOps.replace(QueueState(), tracks, 99, Random(1)).cursor)
        assertEquals(0, QueueOps.replace(QueueState(), tracks, -3, Random(1)).cursor)
    }

    @Test
    fun replace_uidsKeepIncreasingAcrossReplacements() {
        val s = QueueOps.replace(queue(), tracks.take(2), 0, Random(1))
        assertEquals(listOf(6L, 7L), s.entries.map { it.uid })
    }

    @Test
    fun replace_withShuffleAndExplicitStart_putsStartFirstAndShufflesRest() {
        val s = queue(start = 3, shuffle = true)
        assertEquals(0, s.cursor)
        assertEquals("d", s.current?.track?.id)
        assertEquals((0..4).toSet(), s.order.toSet())
        assertEquals(5, s.order.size)
    }

    @Test
    fun replace_withShuffleSingleTrack_startsAtIt() {
        val s = QueueOps.replace(QueueState(shuffle = true), listOf(jf("x")), 0, Random(3))
        assertEquals(listOf(0), s.order)
        assertEquals(0, s.cursor)
    }

    @Test
    fun replace_resetsPlayNextOffset() {
        val stacked = QueueOps.playNext(queue(), jf("n"))
        assertEquals(1, stacked.playNextOffset)
        assertEquals(0, QueueOps.replace(stacked, tracks, 0, Random(1)).playNextOffset)
    }

    @Test
    fun playNext_stacksInCallOrderAfterCurrent() {
        var s = queue(start = 1)
        s = QueueOps.playNext(s, jf("x"))
        s = QueueOps.playNext(s, jf("y"))
        s = QueueOps.playNext(s, jf("z"))
        assertEquals(listOf("a", "b", "x", "y", "z", "c", "d", "e"), s.playOrderIds())
        assertEquals(3, s.playNextOffset)
        assertEquals("b", s.current?.track?.id)
        assertEquals(s.entries.indices.toList(), s.order)
    }

    @Test
    fun playNext_offsetResetsWhenCursorMoves() {
        var s = queue(start = 0)
        s = QueueOps.playNext(s, jf("x"))
        s = QueueOps.advance(s)!!
        assertEquals("x", s.current?.track?.id)
        assertEquals(0, s.playNextOffset)
        s = QueueOps.playNext(s, jf("y"))
        assertEquals(listOf("a", "x", "y", "b", "c", "d", "e"), s.playOrderIds())
    }

    @Test
    fun playNext_onEmptyQueue_createsSingleCurrentEntry() {
        val s = QueueOps.playNext(QueueState(), jf("x"))
        assertEquals(listOf("x"), s.playOrderIds())
        assertEquals(0, s.cursor)
        assertEquals(0, s.playNextOffset)
        assertEquals(1L, s.entries.single().uid)
    }

    @Test
    fun playNext_underShuffle_insertsAfterCurrentInPlayOrder() {
        var s = queue(start = 2, shuffle = true, seed = 7)
        val before = s.playOrderIds()
        s = QueueOps.playNext(s, jf("x"))
        s = QueueOps.playNext(s, jf("y"))
        assertEquals(listOf(before[0], "x", "y") + before.drop(1), s.playOrderIds())
        assertEquals(s.entries.indices.toSet(), s.order.toSet())
        // Unshuffling keeps the inserted entries right after the current entry.
        val off = QueueOps.setShuffle(s, false, Random(1))
        assertEquals(listOf("a", "b", "c", "x", "y", "d", "e"), off.playOrderIds())
        assertEquals("c", off.current?.track?.id)
    }

    @Test
    fun advance_movesForwardAndReturnsNullAtEndWithoutRepeat() {
        var s = queue(start = 3)
        s = QueueOps.advance(s)!!
        assertEquals("e", s.current?.track?.id)
        assertNull(QueueOps.advance(s))
    }

    @Test
    fun advance_repeatAllWraps() {
        val s = QueueOps.setRepeat(queue(start = 4), RepeatMode.ALL)
        val wrapped = QueueOps.advance(s, wrap = true)!!
        assertEquals(0, wrapped.cursor)
        assertEquals("a", wrapped.current?.track?.id)
        assertNull(QueueOps.advance(s, wrap = false))
    }

    @Test
    fun advance_repeatOneDoesNotWrap() {
        val s = QueueOps.setRepeat(queue(start = 4), RepeatMode.ONE)
        assertNull(QueueOps.advance(s))
    }

    @Test
    fun advance_onEmptyQueue_isNull() {
        assertNull(QueueOps.advance(QueueState()))
    }

    @Test
    fun back_movesBackAndReturnsNullAtStartEvenWithRepeatAll() {
        val s = queue(start = 1)
        assertEquals("a", QueueOps.back(s)!!.current?.track?.id)
        val atStart = QueueOps.setRepeat(queue(start = 0), RepeatMode.ALL)
        assertNull(QueueOps.back(atStart))
    }

    @Test
    fun advanceAndBack_followShuffledOrder() {
        val s = queue(start = 0, shuffle = true, seed = 11)
        val next = QueueOps.advance(s)!!
        assertEquals(s.entryAt(1), next.current)
        assertEquals(s.entryAt(0), QueueOps.back(next)!!.current)
    }

    @Test
    fun setShuffle_on_keepsCurrentAtCursorAndHistory() {
        val s = queue(start = 2)
        val on = QueueOps.setShuffle(s, true, Random(5))
        assertTrue(on.shuffle)
        assertEquals(2, on.cursor)
        assertEquals("c", on.current?.track?.id)
        assertEquals(s.current, on.current)
        assertEquals(listOf("a", "b"), on.history().map { it.track.id })
        assertEquals(setOf("d", "e"), on.upcoming().map { it.track.id }.toSet())
        assertEquals((0..4).toSet(), on.order.toSet())
    }

    @Test
    fun setShuffle_off_restoresIdentityAndFollowsCurrent() {
        val on = queue(start = 3, shuffle = true, seed = 9)
        val off = QueueOps.setShuffle(on, false, Random(1))
        assertEquals(listOf(0, 1, 2, 3, 4), off.order)
        assertEquals(3, off.cursor)
        assertEquals("d", off.current?.track?.id)
    }

    @Test
    fun setShuffle_unchanged_returnsSameState() {
        val s = queue()
        assertTrue(s === QueueOps.setShuffle(s, false, Random(1)))
    }

    @Test
    fun setShuffle_withoutCurrent_shufflesEverything() {
        val s = QueueState(entries = tracks.mapIndexed { i, t -> QueueEntry(i + 1L, t) }, order = tracks.indices.toList())
        val on = QueueOps.setShuffle(s, true, Random(2))
        assertEquals(-1, on.cursor)
        assertEquals((0..4).toSet(), on.order.toSet())
    }

    @Test
    fun jump_withDuplicateTrackIds_isDistinctByUid() {
        val dupes = listOf(jf("a"), jf("b"), jf("a"), jf("a"))
        val s = QueueOps.replace(QueueState(), dupes, 0, Random(1))
        val third = s.entries[2]
        val fourth = s.entries[3]
        assertEquals(third.track, fourth.track)
        assertNotEquals(third.uid, fourth.uid)

        val j3 = QueueOps.jumpToUid(s, third.uid)
        assertEquals(2, j3.cursor)
        assertEquals(third.uid, j3.current?.uid)
        val j4 = QueueOps.jumpToUid(s, fourth.uid)
        assertEquals(3, j4.cursor)
        assertEquals(fourth.uid, j4.current?.uid)
        assertEquals(listOf("a"), j3.upcoming().map { it.track.id })
        assertEquals(fourth.uid, j3.upcoming().single().uid)
    }

    @Test
    fun upcomingAndHistory_followCursor() {
        val s = queue(start = 2)
        assertEquals(listOf("a", "b"), s.history().map { it.track.id })
        assertEquals(listOf("d", "e"), s.upcoming().map { it.track.id })
    }
    @Test
    fun replace_randomStart_picksRandomStartOnlyWhenRequested() {
        val starts = (1..40).map { seed ->
            QueueOps.replace(QueueState(shuffle = true), tracks, 0, Random(seed), randomStart = true).current!!.track.id
        }.toSet()
        assertTrue("random start should vary across seeds, got $starts", starts.size > 1)
        (1..40).forEach { seed ->
            val kept = QueueOps.replace(QueueState(shuffle = true), tracks, 0, Random(seed))
            assertEquals("restore/sync keep the given start", "a", kept.current!!.track.id)
        }
        // Without shuffle randomStart is ignored.
        assertEquals("a", QueueOps.replace(QueueState(), tracks, 0, Random(3), randomStart = true).current!!.track.id)
        // Explicit non-zero start is never randomized.
        assertEquals("c", QueueOps.replace(QueueState(shuffle = true), tracks, 2, Random(3), randomStart = true).current!!.track.id)
    }

    @Test
    fun playNext_offsetResetsOnBackAndJump() {
        val stacked = QueueOps.playNext(queue(start = 2), jf("x"))
        assertEquals(0, QueueOps.back(stacked)!!.playNextOffset)
        assertEquals(0, QueueOps.jumpToUid(stacked, stacked.entries[4].uid).playNextOffset)
    }

    @Test
    fun jumpToUid_unknownUid_isNoOp() {
        val s = queue()
        assertTrue(s === QueueOps.jumpToUid(s, 999))
    }

    @Test
    fun cursorMoves_doNotBumpContentVersion() {
        val s = queue(start = 1)
        val v = s.contentVersion
        assertEquals(v, QueueOps.advance(s)!!.contentVersion)
        assertEquals(v, QueueOps.back(s)!!.contentVersion)
        assertEquals(v, QueueOps.jumpToUid(s, s.entries[4].uid).contentVersion)
        assertEquals(v, QueueOps.setRepeat(s, RepeatMode.ALL).contentVersion)
        assertTrue(QueueOps.advance(s)!!.entries === s.entries)
        assertTrue(QueueOps.advance(s)!!.order === s.order)
    }

    @Test
    fun contentChanges_bumpContentVersion() {
        val s = queue(start = 1)
        val v = s.contentVersion
        assertEquals(v + 1, QueueOps.playNext(s, jf("x")).contentVersion)
        assertEquals(v + 1, QueueOps.setShuffle(s, true, Random(1)).contentVersion)
        assertEquals(v + 1, QueueOps.replace(s, tracks, 0, Random(1)).contentVersion)
    }

    @Test
    fun playNext_insertsIntoEntriesAfterCurrentPlusOffset() {
        var s = queue(start = 3, shuffle = true, seed = 5) // current = entry 3 ("d")
        s = QueueOps.playNext(s, jf("x"))
        s = QueueOps.playNext(s, jf("y"))
        assertEquals(listOf("a", "b", "c", "d", "x", "y", "e"), s.entries.map { it.track.id })
        assertEquals(listOf("d", "x", "y"), s.playOrderIds().take(3))
    }
}
