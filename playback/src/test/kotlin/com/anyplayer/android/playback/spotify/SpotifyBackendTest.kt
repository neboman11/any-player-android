package com.anyplayer.android.playback.spotify

import com.anyplayer.android.core.model.BackendKind
import com.anyplayer.android.core.model.PlanItem
import com.anyplayer.android.core.model.QueueEntry
import com.anyplayer.android.core.model.Run
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.playback.backend.BackendEvent
import com.anyplayer.android.playback.backend.ItemKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyBackendTest {

    private class Env(
        val backend: SpotifyBackend,
        val api: FakeSpotifyPlayerApi,
        val events: MutableList<BackendEvent>,
    ) {
        fun started(): List<Long> = events.filterIsInstance<BackendEvent.ItemStarted>().map { (it.key as ItemKey.Song).uid }
        fun runEnded(): Boolean = events.any { it is BackendEvent.RunEnded }
        fun errors(): List<String> = events.filterIsInstance<BackendEvent.Error>().map { it.message }
        fun playing(): List<BackendEvent.PlayingChanged> = events.filterIsInstance<BackendEvent.PlayingChanged>()
    }

    private fun TestScope.env(): Env {
        val clock = { testScheduler.currentTime }
        val api = FakeSpotifyPlayerApi(clock)
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job))
        val backend = SpotifyBackend(api, SpotifyDeviceResolver(api, FakeSpotifyAppLauncher(), FakeSpotifyDevicePreferenceStore(), clock), scope, clock)
        val events = mutableListOf<BackendEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { backend.events.collect { events += it } }
        return Env(backend, api, events)
    }

    private fun id(n: Int) = "trk" + n.toString().padStart(19, '0')

    private fun Env.entry(uid: Long, n: Int = uid.toInt(), durationMs: Long = 200_000L): QueueEntry {
        api.durations[id(n)] = durationMs
        return QueueEntry(uid, Track(id = id(n), title = "t$n", artist = "a", durationMs = durationMs, source = SourceType.SPOTIFY))
    }

    private fun run(vararg entries: QueueEntry) =
        Run(BackendKind.SPOTIFY, entries.mapIndexed { i, e -> PlanItem.Song(e, i) })

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun itemStarted_followsNativeAdvance_matchingIdAndLinkedFrom() = runTest {
        val e = env()
        val a = e.entry(1, durationMs = 10_000)
        val b = e.entry(2, durationMs = 10_000)
        val c = e.entry(3, durationMs = 10_000)
        e.api.relinked[id(3)] = "relinked0000000000000z"

        e.backend.load(1, run(a, b, c), 0, 0, playWhenReady = true)
        assertEquals(listOf(1L), e.started())
        assertEquals("start:phone:0:0:${id(1)},${id(2)},${id(3)}", e.api.startCalls().single())

        advance(11_500)
        assertEquals(listOf(1L, 2L), e.started())

        advance(10_000)
        assertEquals("linked_from id maps to the run item", listOf(1L, 2L, 3L), e.started())
        assertTrue(e.events.all { it.loadId == 1L })
    }

    @Test
    fun runIndexIsMonotonic_resetToFirstPausedAtEndEndsRun() = runTest {
        val e = env()
        val a = e.entry(1, durationMs = 60_000)
        val b = e.entry(2, durationMs = 60_000)
        e.backend.load(1, run(a, b), 1, 0, playWhenReady = true)
        advance(4_100)
        assertEquals(listOf(2L), e.started())

        // Device jumps back to the first uri while playing: not honoured outside the settle window.
        e.api.setPlaying(listOf(id(1), id(2)), 0, 1_000)
        advance(4_000)
        assertEquals(listOf(2L), e.started())
        assertFalse(e.runEnded())

        // Spotify's end-of-list behaviour: first uri, paused at 0.
        e.api.setPlaying(listOf(id(1), id(2)), 0, 0, isPlaying = false)
        advance(2_100)
        assertTrue(e.runEnded())
        assertEquals("never reported backwards", listOf(2L), e.started())
    }

    @Test
    fun settleWindow_ignoresStaleTrackAfterLoad() = runTest {
        val e = env()
        val a = e.entry(1, durationMs = 120_000)
        val b = e.entry(2, durationMs = 120_000)
        e.api.setPlaying(listOf(id(2)), 0, 50_000) // the device still plays b from an earlier context
        e.api.startDelayMs = 2_500

        e.backend.load(1, run(a, b), 0, 0, playWhenReady = true)
        advance(2_100) // poll sees the stale b inside the settle window
        advance(4_000)

        assertEquals(listOf(1L), e.started())
        assertFalse(e.runEnded())
        assertTrue(e.playing().any { it.isPlaying && !it.buffering })
    }

    @Test
    fun unknownTrack_endsRunOnlyAfterTwoConsecutivePolls() = runTest {
        val e = env()
        val a = e.entry(1, durationMs = 120_000)
        val b = e.entry(2, durationMs = 120_000)
        e.backend.load(1, run(a, b), 0, 0, playWhenReady = true)
        advance(4_100)

        e.api.playForeign("foreign000000000000000")
        advance(2_000) // poll at 6 s
        assertFalse(e.runEnded())
        advance(2_000) // poll at 8 s
        assertTrue(e.runEnded())
    }

    @Test
    fun updateUpcoming_runEndMovedEarlier_pausesAtEndOfItem() = runTest {
        val e = env()
        val a = e.entry(1, durationMs = 20_000)
        val b = e.entry(2, durationMs = 20_000)
        val c = e.entry(3, durationMs = 20_000)
        e.backend.load(1, run(a, b, c), 0, 0, playWhenReady = true)
        advance(3_000)

        e.backend.updateUpcoming(1, run(a), ItemKey.Song(1))
        advance(16_500) // t = 19.5 s
        assertFalse(e.runEnded())
        assertFalse(e.api.calls.contains("pause"))

        advance(500) // t = 20 s: timer fired at remaining − 300 ms
        assertTrue(e.runEnded())
        assertTrue(e.api.calls.contains("pause"))
        assertEquals("device never advanced into b", id(1), e.api.currentTrack())
        assertEquals(listOf(1L), e.started())
        assertEquals(1, e.api.startCalls().size)
    }

    @Test
    fun updateUpcoming_reloadsOnlyWhenNextItemChanges_debounced() = runTest {
        val e = env()
        val a = e.entry(1, durationMs = 60_000)
        val b = e.entry(2, durationMs = 60_000)
        val c = e.entry(3, durationMs = 60_000)
        val d = e.entry(4, durationMs = 60_000)
        val x = e.entry(5, durationMs = 60_000)
        val y = e.entry(6, durationMs = 60_000)
        e.backend.load(1, run(a, b, c), 0, 0, playWhenReady = true)
        advance(10_100)

        e.backend.updateUpcoming(1, run(a, b, c, d), ItemKey.Song(1)) // beyond the loaded window
        e.backend.updateUpcoming(1, run(a, b, x, c), ItemKey.Song(1)) // divergence after the next item
        advance(1_000)
        assertEquals(1, e.api.startCalls().size)

        e.backend.updateUpcoming(1, run(a, x, b, c), ItemKey.Song(1))
        advance(100)
        e.backend.updateUpcoming(1, run(a, y, b, c), ItemKey.Song(1))
        advance(400)

        val starts = e.api.startCalls()
        assertEquals("debounced into one reload", 2, starts.size)
        val parts = starts.last().split(":")
        assertEquals("0", parts[2])
        assertEquals(listOf(id(1), id(6), id(2), id(3)).joinToString(","), parts[4])
        val position = parts[3].toLong()
        assertTrue("resumes mid-track, was $position", position in 10_500L..12_000L)
        assertEquals(listOf(1L), e.started())
    }

    @Test
    fun externalMidTrackPause_isReportedByPlayerAndNotRecovered() = runTest {
        val e = env()
        val a = e.entry(1, durationMs = 200_000)
        e.backend.load(1, run(a), 0, 0, playWhenReady = true)
        advance(4_100)

        e.api.externalPause()
        advance(2_000)
        val last = e.playing().last()
        assertFalse(last.isPlaying)
        assertTrue(last.byPlayer)

        advance(30_000)
        assertEquals(1, e.api.startCalls().size)
        assertFalse(e.api.calls.contains("resume"))
        assertTrue(e.errors().isEmpty())
    }

    @Test
    fun stalledProgress_recoversThreeTimesThenFails() = runTest {
        val e = env()
        val a = e.entry(1, durationMs = 300_000)
        e.backend.load(1, run(a), 0, 0, playWhenReady = true)
        advance(4_100)
        e.api.frozen = true // ghost session: is_playing stays true, progress stops

        advance(12_000)
        val firstRecovery = e.api.startCalls().getOrNull(1)
        assertEquals("start:phone:0:4100:${id(1)}", firstRecovery)

        advance(60_000)
        assertEquals(4, e.api.startCalls().size)
        assertEquals(
            listOf("Couldn't get Spotify playback back (Spotify playback stalled while reporting playing). Press play to try again."),
            e.errors(),
        )

        advance(30_000)
        assertEquals("no restarts after exhaustion", 4, e.api.startCalls().size)

        e.api.frozen = false
        val resumes = e.api.calls.count { it == "resume" }
        e.backend.play()
        assertEquals("play re-arms recovery", resumes + 1, e.api.calls.count { it == "resume" })
    }

    @Test
    fun load_adoptsTargetAlreadyPlayingWithinThreeSeconds() = runTest {
        val e = env()
        val a = e.entry(1, durationMs = 200_000)
        val b = e.entry(2, durationMs = 200_000)
        e.api.setPlaying(listOf(id(1), id(2)), 0, 5_000)

        e.backend.load(1, run(a, b), 0, 6_000, playWhenReady = true)

        assertTrue(e.api.startCalls().isEmpty())
        assertEquals(listOf(1L), e.started())
        assertTrue(e.playing().last().let { it.isPlaying && !it.buffering })
        assertEquals(5_000L, e.backend.positionMs())
    }

    @Test
    fun load_forcesShuffleOffAndMapsRepeat() = runTest {
        val e = env()
        val a = e.entry(1)
        e.api.setPlaying(listOf(id(9)), 0, 0)
        e.api.shuffle = true

        e.backend.load(1, run(a), 0, 0, playWhenReady = true)
        val calls = e.api.calls
        assertTrue(calls.indexOf("shuffle:false") in 0 until calls.indexOfFirst { it.startsWith("start:") })
        assertTrue(calls.contains("repeat:OFF"))

        e.backend.setRepeatOne(true)
        assertEquals("repeat:ONE", e.api.calls.last())
    }

    @Test
    fun pausedLoad_noNetworkUntilPlay_thenStartsAtPosition() = runTest {
        val e = env()
        val a = e.entry(1)
        val b = e.entry(2)
        e.backend.load(1, run(a, b), 1, 42_000, playWhenReady = false)
        advance(20_000)
        assertTrue(e.api.calls.isEmpty())
        assertEquals(listOf(2L), e.started())

        e.backend.play()
        assertEquals("start:phone:1:42000:${id(1)},${id(2)}", e.api.startCalls().single())
    }

    @Test
    fun pause_reportsUserPause_andIgnoresStalePlayingPollsDuringGrace() = runTest {
        val e = env()
        val a = e.entry(1)
        e.backend.load(1, run(a), 0, 0, playWhenReady = true)
        advance(4_100)

        e.backend.pause()
        val paused = e.playing().last()
        assertFalse(paused.isPlaying)
        assertFalse(paused.byPlayer)

        e.api.setPlaying(listOf(id(1)), 0, 4_100) // stale "playing" report right after the pause
        advance(4_000)
        assertFalse(e.playing().last().isPlaying)
    }

    @Test
    fun pauseRejected_whileSpotifyIsAlreadyPaused_isNotAnError() = runTest {
        val e = env()
        val a = e.entry(1)
        e.backend.load(1, run(a), 0, 0, playWhenReady = true)
        advance(4_100)
        e.api.setPlaying(listOf(id(1)), 0, 4_100, isPlaying = false) // user paused in the Spotify app
        e.api.failures["pause"] = 1 // Spotify: 403 "Restriction violated"

        e.backend.pause()
        assertEquals(emptyList<String>(), e.errors())
        assertFalse(e.playing().last().isPlaying)
    }

    @Test
    fun pauseRejected_whileSpotifyKeepsPlaying_isReported() = runTest {
        val e = env()
        val a = e.entry(1)
        e.backend.load(1, run(a), 0, 0, playWhenReady = true)
        advance(4_100)
        e.api.failures["pause"] = 1

        e.backend.pause()
        assertEquals(listOf(SpotifyMessages.commandFailed("pause")), e.errors())
    }

    @Test
    fun repeatOrVolumeRejectedAfterStart_doesNotReportTheStartAsFailed() = runTest {
        val e = env()
        val a = e.entry(1)
        e.backend.setVolume(40)
        e.api.failures["setRepeatMode"] = 1
        e.api.failures["setVolume"] = 1

        e.backend.load(1, run(a), 0, 0, playWhenReady = true)
        advance(4_100)
        assertEquals(emptyList<String>(), e.errors())
        assertTrue(e.playing().last().isPlaying)
    }

    @Test
    fun setVolume_isDebounced() = runTest {
        val e = env()
        val a = e.entry(1)
        e.backend.load(1, run(a), 0, 0, playWhenReady = true)
        e.backend.setVolume(10)
        e.backend.setVolume(20)
        e.backend.setVolume(30)
        advance(400)
        assertEquals(listOf("volume:30"), e.api.calls.filter { it.startsWith("volume:") })
    }

    @Test
    fun startFailure_emitsLegacyErrorTaggedWithLoadId() = runTest {
        val e = env()
        val a = e.entry(1)
        e.api.devicesList.clear()
        e.api.failures["startQueue"] = 0
        val backend = e.backend
        backend.load(7, run(a), 0, 0, playWhenReady = true)
        advance(10_000)
        val error = e.events.filterIsInstance<BackendEvent.Error>().single()
        assertEquals(7L, error.loadId)
        assertEquals(ItemKey.Song(1), error.key)
        assertEquals(SpotifyMessages.NO_DEVICE, error.message)
    }
    @Test
    fun pausedLoad_checksShuffleWhenPlaybackActuallyStarts() = runTest {
        val e = env()
        val a = e.entry(1)
        e.api.setPlaying(listOf(id(9)), 0, 0)
        e.api.shuffle = true
        e.backend.load(1, run(a), 0, 0, playWhenReady = false)
        assertTrue(e.api.calls.isEmpty())
        e.backend.play()
        assertFalse(e.api.shuffle)
        assertTrue(e.api.calls.indexOf("shuffle:false") < e.api.calls.indexOfFirst { it.startsWith("start:") })
    }

    @Test
    fun pollAnchors_onlyEmitOnDriftStateOrTrackChange() = runTest {
        val e = env()
        val a = e.entry(1)
        e.backend.load(19, run(a), 0, 0, playWhenReady = true)
        advance(4_100)
        val positions = e.events.filterIsInstance<BackendEvent.PositionDiscontinuity>().size
        advance(10_000)
        assertEquals(positions, e.events.filterIsInstance<BackendEvent.PositionDiscontinuity>().size)
        e.api.setPlaying(listOf(id(1)), 0, 25_000)
        advance(2_000)
        assertEquals(positions + 1, e.events.filterIsInstance<BackendEvent.PositionDiscontinuity>().size)
        assertTrue(e.events.all { it.loadId == 19L })
    }

    @Test
    fun runEnd_doesNotArmUntilCurrentItemWasObservedPlaying() = runTest {
        val e = env()
        val a = e.entry(1, durationMs = 60_000)
        e.api.startDelayMs = 30_000
        e.api.setPlaying(listOf(id(1)), 0, 0, isPlaying = false)
        e.backend.load(1, run(a), 0, 0, playWhenReady = true)
        advance(8_000)
        assertFalse(e.runEnded())
    }

    @Test
    fun unknownTrack_streakResetsWhenKnownTrackReturns() = runTest {
        val e = env()
        val a = e.entry(1)
        e.backend.load(1, run(a), 0, 0, playWhenReady = true)
        advance(4_100)
        e.api.playForeign("foreign")
        advance(2_000)
        e.api.setPlaying(listOf(id(1)), 0, 6_100)
        advance(2_000)
        e.api.playForeign("foreign")
        advance(2_000)
        assertFalse(e.runEnded())
        advance(2_000)
        assertTrue(e.runEnded())
    }

    @Test
    fun shortenedRun_ignoresUnconfirmedStaleTrackPastStopPoint() = runTest {
        val e = env()
        val a = e.entry(1)
        val b = e.entry(2)
        e.api.setPlaying(listOf(id(2)), 0, 50_000)
        e.api.startDelayMs = 30_000
        e.backend.load(1, run(a, b), 0, 0, playWhenReady = true)
        e.backend.updateUpcoming(1, run(a), ItemKey.Song(1))
        advance(8_000)
        assertFalse(e.runEnded())
    }

    @Test
    fun commands_finishRequestDespiteCallerCancellationAndSerializeStop() = runTest {
        val e = env()
        val a = e.entry(1)
        val gate = CompletableDeferred<Unit>()
        e.api.startGate = gate
        val caller = launch { e.backend.load(8, run(a), 0, 0, playWhenReady = true) }
        runCurrent()
        assertEquals(1, e.api.startCalls().size)
        caller.cancel()
        val stop = launch { e.backend.stop() }
        runCurrent()
        assertFalse(e.api.calls.contains("pause"))
        gate.complete(Unit)
        stop.join()
        assertEquals(id(1), e.api.currentTrack())
        assertTrue(e.api.calls.contains("pause"))
        assertTrue(e.errors().isEmpty())
        val polls = e.api.calls.count { it == "state" }
        advance(20_000)
        assertEquals(polls, e.api.calls.count { it == "state" })
    }

    @Test
    fun shuffleFailure_doesNotStartAnUnorderedRun() = runTest {
        val e = env()
        val a = e.entry(1)
        e.api.shuffle = true
        e.api.setPlaying(listOf(id(9)), 0, 0)
        e.api.failures["setShuffle"] = 1
        e.backend.load(1, run(a), 0, 0, playWhenReady = true)
        assertTrue(e.api.startCalls().isEmpty())
        assertEquals(listOf("Spotify command failed: setShuffle"), e.errors())
    }

    @Test
    fun boundaryPauseFailure_whileSpotifyPlaysOn_reportsErrorInsteadOfClaimingRunEnded() = runTest {
        val e = env()
        val a = e.entry(1, durationMs = 20_000)
        val b = e.entry(2, durationMs = 20_000)
        e.backend.load(1, run(a, b), 0, 0, playWhenReady = true)
        advance(3_000)
        e.backend.updateUpcoming(1, run(a), ItemKey.Song(1)) // a DJ break now comes after a
        e.api.failures["pause"] = 2
        advance(18_500) // past the end of a: Spotify moved on into b and both pauses failed
        // The failure is reported; the next poll past the stop point pauses Spotify and only then
        // hands over (never while it was still playing b).
        assertEquals(listOf("Spotify command failed: pause"), e.errors())
        assertEquals(3, e.api.calls.count { it == "pause" })
    }

    @Test
    fun boundaryPauseRejectedAtTrackEnd_beforeDjBreak_isNotAnError() = runTest {
        val e = env()
        val a = e.entry(1, durationMs = 20_000)
        val b = e.entry(2, durationMs = 20_000)
        e.backend.load(1, run(a, b), 0, 0, playWhenReady = true)
        advance(3_000)
        e.backend.updateUpcoming(1, run(a), ItemKey.Song(1)) // a DJ break now comes after a
        // Spotify answers the end-of-song pause with 403 while a is still finishing, then stops.
        e.api.failures["pause"] = 1
        advance(16_700) // t = 19.7 s: end-of-item pause rejected
        e.api.setPlaying(listOf(id(1), id(2)), 0, 20_000, isPlaying = false)
        advance(1_500)
        assertEquals(emptyList<String>(), e.errors())
        assertTrue(e.runEnded())
    }

    @Test
    fun lateEndCheck_afterSpotifyAlreadyStopped_endsRunWithout403Error() = runTest {
        val e = env()
        val a = e.entry(1, durationMs = 10_000)
        e.backend.load(1, run(a), 0, 0, playWhenReady = true)
        advance(9_500)
        // The track finished just before the end check: Spotify has no active playback (GET 204)
        // and rejects the boundary pause with 403.
        e.api.available = false
        e.api.failures["pause"] = 1
        advance(1_500)
        assertEquals(emptyList<String>(), e.errors())
        assertTrue(e.runEnded())
    }

    @Test
    fun frozenRawProgress_nearEndOfMidRunItemStillRecovers() = runTest {
        val e = env()
        val a = e.entry(1, durationMs = 60_000)
        val b = e.entry(2, durationMs = 60_000)
        e.backend.load(1, run(a, b), 0, 0, playWhenReady = true)
        advance(59_000)
        e.api.frozen = true
        advance(12_000)
        assertEquals(2, e.api.startCalls().size)
        assertTrue(e.api.startCalls().last().startsWith("start:phone:0:59000:"))
        assertFalse(e.runEnded())
    }
}
