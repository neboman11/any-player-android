package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.feature.auth.spotify.SpotifyPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOLERANCE_MS = 2_000L

class SpotifyConnectBridgeTest {
    @Test
    fun mergeEndOfTrackCount_playingStopsNearTrackStart_countsAsFinished() {
        val previous = playbackState(isPlaying = true, progressMs = 180_000, durationMs = 180_000)
        val polled = playbackState(isPlaying = false, progressMs = 0, durationMs = 180_000)

        val merged = mergeEndOfTrackCount(previous, polled, TOLERANCE_MS)

        assertEquals(1, merged.endOfTrackCount)
    }

    @Test
    fun mergeEndOfTrackCount_playingStopsNearTrackEnd_countsAsFinished() {
        val previous = playbackState(isPlaying = true, progressMs = 178_500, durationMs = 180_000)
        val polled = playbackState(isPlaying = false, progressMs = 179_800, durationMs = 180_000)

        val merged = mergeEndOfTrackCount(previous, polled, TOLERANCE_MS)

        assertEquals(1, merged.endOfTrackCount)
    }

    @Test
    fun mergeEndOfTrackCount_userPausesMidTrack_doesNotCountAsFinished() {
        val previous = playbackState(isPlaying = true, progressMs = 90_000, durationMs = 180_000)
        val polled = playbackState(isPlaying = false, progressMs = 90_000, durationMs = 180_000)

        val merged = mergeEndOfTrackCount(previous, polled, TOLERANCE_MS)

        assertEquals(0, merged.endOfTrackCount)
    }

    @Test
    fun mergeEndOfTrackCount_userPausesNearTrackEnd_doesNotCountAsFinished() {
        val previous = playbackState(isPlaying = true, progressMs = 178_500, durationMs = 180_000)
        val polled = playbackState(isPlaying = false, progressMs = 179_000, durationMs = 180_000)

        val merged = mergeEndOfTrackCount(
            previous,
            polled,
            TOLERANCE_MS,
            manualPauseExpected = true
        )

        assertEquals(0, merged.endOfTrackCount)
    }

    @Test
    fun mergeEndOfTrackCount_wasNotPreviouslyPlaying_doesNotCountAsFinished() {
        val previous = playbackState(isPlaying = false, progressMs = 0, durationMs = 180_000)
        val polled = playbackState(isPlaying = false, progressMs = 0, durationMs = 180_000)

        val merged = mergeEndOfTrackCount(previous, polled, TOLERANCE_MS)

        assertEquals(0, merged.endOfTrackCount)
    }

    @Test
    fun mergeEndOfTrackCount_noPreviousState_doesNotCountAsFinished() {
        val polled = playbackState(isPlaying = false, progressMs = 0, durationMs = 180_000)

        val merged = mergeEndOfTrackCount(previous = null, polled = polled, toleranceMs = TOLERANCE_MS)

        assertEquals(0, merged.endOfTrackCount)
    }

    @Test
    fun mergeEndOfTrackCount_accumulatesOnTopOfPreviousCount() {
        val previous = playbackState(isPlaying = true, progressMs = 179_999, durationMs = 180_000, endOfTrackCount = 3)
        val polled = playbackState(isPlaying = false, progressMs = 0, durationMs = 180_000)

        val merged = mergeEndOfTrackCount(previous, polled, TOLERANCE_MS)

        assertEquals(4, merged.endOfTrackCount)
    }

    @Test
    fun mergeEndOfTrackCount_stillPlaying_doesNotCountAsFinished() {
        val previous = playbackState(isPlaying = true, progressMs = 179_999, durationMs = 180_000)
        val polled = playbackState(isPlaying = true, progressMs = 0, durationMs = 180_000)

        val merged = mergeEndOfTrackCount(previous, polled, TOLERANCE_MS)

        assertEquals(0, merged.endOfTrackCount)
    }

    @Test
    fun extrapolatePosition_addsElapsedTimeToProgress() {
        val cached = playbackState(isPlaying = true, progressMs = 10_000, durationMs = 180_000)

        val extrapolated = extrapolatePosition(cached, elapsedSincePollMs = 1_500)

        assertEquals(11_500, extrapolated.progressMs)
        assertTrue(extrapolated.isPlaying)
    }

    @Test
    fun extrapolatePosition_preservesOtherFields() {
        val cached = playbackState(
            isPlaying = true,
            progressMs = 10_000,
            durationMs = 180_000,
            currentTrackId = "abc123"
        )

        val extrapolated = extrapolatePosition(cached, elapsedSincePollMs = 500)

        assertEquals("abc123", extrapolated.currentTrackId)
        assertFalse(extrapolated.shuffleEnabled)
    }

    @Test
    fun playbackStateCache_clearsStaleStateAfterThreeEmptyPolls() {
        val cache = SpotifyPlaybackStateCache(
            endOfTrackToleranceMs = TOLERANCE_MS,
            emptyPollsBeforeClear = 3
        )
        cache.update(playbackState(isPlaying = true, progressMs = 10_000, durationMs = 180_000), nowMs = 0)

        cache.update(polled = null, nowMs = 2_000)
        assertEquals(13_000L, cache.snapshot(nowMs = 3_000)?.progressMs)
        cache.update(polled = null, nowMs = 4_000)
        assertTrue(cache.snapshot(nowMs = 4_100) != null)

        cache.update(polled = null, nowMs = 6_000)

        assertNull(cache.snapshot(nowMs = 6_100))
    }

    @Test
    fun playbackStateCache_manualPauseNearTrackEnd_doesNotCountCompletion() {
        val cache = SpotifyPlaybackStateCache(endOfTrackToleranceMs = TOLERANCE_MS)
        cache.update(playbackState(isPlaying = true, progressMs = 178_500, durationMs = 180_000), nowMs = 0)
        cache.markManualPause(nowMs = 100, gracePeriodMs = 5_000)

        cache.update(
            polled = playbackState(isPlaying = false, progressMs = 179_000, durationMs = 180_000),
            nowMs = 500
        )

        assertEquals(0L, cache.snapshot(nowMs = 500)?.endOfTrackCount)
    }

    @Test
    fun playbackStateCache_successfulSlowPauseExtendsManualPauseGrace() {
        val cache = SpotifyPlaybackStateCache(endOfTrackToleranceMs = TOLERANCE_MS)
        cache.update(playbackState(isPlaying = true, progressMs = 178_500, durationMs = 180_000), nowMs = 0)
        cache.markManualPause(nowMs = 100, gracePeriodMs = 5_000)

        cache.extendManualPauseAfterSuccessfulCommand(nowMs = 6_000, gracePeriodMs = 5_000)
        cache.update(
            polled = playbackState(isPlaying = false, progressMs = 179_000, durationMs = 180_000),
            nowMs = 6_500
        )

        assertEquals(0L, cache.snapshot(nowMs = 6_500)?.endOfTrackCount)
    }

    private fun playbackState(
        isPlaying: Boolean,
        progressMs: Long,
        durationMs: Long,
        endOfTrackCount: Long = 0,
        currentTrackId: String? = "track-id"
    ) = SpotifyPlaybackState(
        isPlaying = isPlaying,
        progressMs = progressMs,
        durationMs = durationMs,
        endOfTrackCount = endOfTrackCount,
        volumePercent = 100,
        shuffleEnabled = false,
        repeatMode = RepeatMode.OFF,
        currentTrackId = currentTrackId
    )
}
