package com.anyplayer.android.core.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunPlanningTest {
    private fun q(vararg tracks: Track, cursor: Int = 0): QueueState =
        QueueOps.replace(QueueState(), tracks.toList(), cursor, Random(1))

    private fun Run?.describe(): List<String> = this!!.items.map {
        when (it) {
            is PlanItem.Song -> "${it.entry.track.label}@${it.orderPos}"
            is PlanItem.Break -> "break:${it.breakId}"
        }
    }

    @Test
    fun backendFor_classifiesBySourceAndScheme() {
        assertEquals(BackendKind.SPOTIFY, backendFor(sp("s")))
        assertEquals(BackendKind.SPOTIFY, backendFor(sp("s").copy(id = "spotify:track:4uLU6hMCjMI75M1A2tKUQC")))
        assertEquals(BackendKind.EXO, backendFor(jf("a")))
        listOf("https://h/x", "HTTP://h/x", "file:///sdcard/x.mp3", "content://media/1", "android.resource://pkg/raw/x")
            .forEach { assertEquals(it, BackendKind.EXO, backendFor(dead("x").copy(url = it))) }
        assertEquals(BackendKind.UNPLAYABLE, backendFor(dead("x")))
        assertEquals(BackendKind.UNPLAYABLE, backendFor(dead("x").copy(url = "")))
        assertEquals(BackendKind.UNPLAYABLE, backendFor(dead("x").copy(url = "any-player://local/x")))
    }

    @Test
    fun backendFor_invalidSpotifyIdIsUnplayable() {
        val base = sp("s")
        assertEquals(BackendKind.UNPLAYABLE, backendFor(base.copy(id = "short")))
        assertEquals(BackendKind.UNPLAYABLE, backendFor(base.copy(id = "4uLU6hMCjMI75M1A2tKUQ-")))
        assertEquals(BackendKind.UNPLAYABLE, backendFor(base.copy(id = "4uLU6hMCjMI75M1A2tKUQCX")))
        assertEquals(BackendKind.UNPLAYABLE, backendFor(base.copy(id = "", url = "https://x")))
        assertEquals(BackendKind.SPOTIFY, backendFor(base.copy(id = " 4uLU6hMCjMI75M1A2tKUQC ")))
    }

    @Test
    fun runBoundaries_splitByBackendKind() {
        val s = q(jf("a"), jf("b"), sp("s1"), sp("s2"), jf("c"))
        val exo = planRun(s, 0, null)!!
        assertEquals(BackendKind.EXO, exo.kind)
        assertEquals(listOf("a@0", "b@1"), exo.describe())
        val spotify = planRun(s, 2, null)!!
        assertEquals(BackendKind.SPOTIFY, spotify.kind)
        assertEquals(listOf("s1@2", "s2@3"), spotify.describe())
        assertEquals(listOf("c@4"), planRun(s, 4, null).describe())
    }

    @Test
    fun unplayableEntries_areSkippedInsideRunWithoutEndingIt() {
        val s = q(jf("a"), dead("u1"), jf("b"), dead("u2"), sp("s1"), dead("u3"), sp("s2"))
        assertEquals(listOf("a@0", "b@2"), planRun(s, 0, null).describe())
        assertEquals(listOf("s1@4", "s2@6"), planRun(s, 3, null).describe())
        assertEquals(BackendKind.SPOTIFY, planRun(s, 3, null)!!.kind)
    }

    @Test
    fun unplayableStart_skipsToFirstPlayable() {
        val s = q(dead("u1"), dead("u2"), jf("a"))
        val run = planRun(s, 0, null)!!
        assertEquals(BackendKind.EXO, run.kind)
        assertEquals(listOf("a@2"), run.describe())
    }

    @Test
    fun noPlayableEntry_returnsNull() {
        assertNull(planRun(q(dead("u1"), sp("s").copy(id = "bad")), 0, null))
        assertNull(planRun(q(jf("a")), 5, null))
        assertNull(planRun(q(jf("a")), -1, null))
        assertNull(planRun(QueueState(), 0, null))
    }

    @Test
    fun runStartingMidRun_startsAtRequestedPosition() {
        val s = q(jf("a"), jf("b"), jf("c"))
        assertEquals(listOf("b@1", "c@2"), planRun(s, 1, null).describe())
    }

    @Test
    fun run_doesNotWrapEvenWithRepeatAll() {
        val s = QueueOps.setRepeat(q(jf("a"), jf("b"), cursor = 1), RepeatMode.ALL)
        assertEquals(listOf("b@1"), planRun(s, 1, null).describe())
    }

    @Test
    fun run_followsShuffledPlayOrder() {
        val s = QueueOps.replace(QueueState(shuffle = true), listOf(jf("a"), jf("b"), jf("c"), jf("d")), 2, Random(8))
        val run = planRun(s, 0, null)!!
        assertEquals(s.playOrderIds(), run.items.map { (it as PlanItem.Song).entry.track.id })
        assertEquals(listOf(0, 1, 2, 3), run.items.map { (it as PlanItem.Song).orderPos })
    }

    @Test
    fun maxItems_capsRun() {
        val s = q(*Array(10) { jf("t$it") })
        assertEquals(listOf("t0@0", "t1@1", "t2@2"), planRun(s, 0, null, maxItems = 3).describe())
        assertEquals(50, planRun(q(*Array(80) { jf("t$it") }), 0, null)!!.items.size)
    }

    @Test
    fun maxItems_countsOnlyPlannedItemsNotSkippedEntries() {
        val s = q(jf("a"), dead("u"), dead("v"), jf("b"), jf("c"))
        assertEquals(listOf("a@0", "b@3"), planRun(s, 0, null, maxItems = 2).describe())
    }

    @Test
    fun breakIsSplicedBeforeTargetInExoRun() {
        val s = q(jf("a"), jf("b"), jf("c"))
        val pending = PendingBreak(beforeUid = s.entries[2].uid, breakId = "brk", audioUri = "file:///dj/brk.wav")
        val run = planRun(s, 0, pending)!!
        assertEquals(listOf("a@0", "b@1", "break:brk", "c@2"), run.describe())
        assertEquals("file:///dj/brk.wav", (run.items[2] as PlanItem.Break).audioUri)
    }

    @Test
    fun breakAtRunStart_isSplicedFirstInExoRun() {
        val s = q(jf("a"), jf("b"))
        val pending = PendingBreak(s.entries[0].uid, "brk", "file:///b.wav")
        assertEquals(listOf("break:brk", "a@0", "b@1"), planRun(s, 0, pending).describe())
    }

    @Test
    fun breakCountsTowardsMaxItems() {
        val s = q(jf("a"), jf("b"), jf("c"))
        val pending = PendingBreak(s.entries[1].uid, "brk", "file:///b.wav")
        assertEquals(listOf("a@0", "break:brk"), planRun(s, 0, pending, maxItems = 2).describe())
    }

    @Test
    fun breakForDuplicateTrackId_usesUidNotTrackId() {
        val s = q(jf("a"), jf("a"), jf("a"))
        val pending = PendingBreak(s.entries[2].uid, "brk", "file:///b.wav")
        assertEquals(listOf("a@0", "a@1", "break:brk", "a@2"), planRun(s, 0, pending).describe())
    }

    @Test
    fun spotifyRunStopsBeforeBreakTarget() {
        val s = q(sp("s1"), sp("s2"), sp("s3"))
        val pending = PendingBreak(s.entries[2].uid, "brk", "file:///b.wav")
        val run = planRun(s, 0, pending)!!
        assertEquals(BackendKind.SPOTIFY, run.kind)
        assertEquals(listOf("s1@0", "s2@1"), run.describe())
        assertTrue(run.items.none { it is PlanItem.Break })
        assertEquals(Run(BackendKind.EXO, listOf(PlanItem.Break("brk", "file:///b.wav"))), breakRun(pending))
        // After the break the next SPOTIFY run starts at the target.
        assertEquals(listOf("s3@2"), planRun(s, 2, pending).describe())
    }

    @Test
    fun breakTargetOutsideRun_isIgnored() {
        val s = q(jf("a"), sp("s1"), jf("b"))
        val pending = PendingBreak(s.entries[2].uid, "brk", "file:///b.wav")
        assertEquals(listOf("a@0"), planRun(s, 0, pending).describe())
    }
}
