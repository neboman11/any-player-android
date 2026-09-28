package com.anyplayer.android.feature.playback

import com.anyplayer.android.feature.auth.spotify.SpotifyPlaybackState

/** Keeps Spotify Connect polling state coherent across the service poller and playback commands. */
internal class SpotifyPlaybackStateCache(
    private val endOfTrackToleranceMs: Long,
    private val emptyPollsBeforeClear: Int = DEFAULT_EMPTY_POLLS_BEFORE_CLEAR
) {
    private var lastKnownState: SpotifyPlaybackState? = null
    private var lastPolledAtMs: Long = 0L
    private var consecutiveEmptyPolls: Int = 0
    private var manualPauseExpiresAtMs: Long = 0L
    private var manualPauseCommandInFlight: Boolean = false

    @Synchronized
    fun update(polled: SpotifyPlaybackState?, nowMs: Long) {
        if (polled == null) {
            consecutiveEmptyPolls += 1
            if (consecutiveEmptyPolls >= emptyPollsBeforeClear) {
                lastKnownState = null
                lastPolledAtMs = 0L
                if (!manualPauseCommandInFlight) manualPauseExpiresAtMs = 0L
            }
            return
        }

        val manualPauseExpected = isManualPauseExpected(nowMs)
        lastKnownState = mergeEndOfTrackCount(
            previous = lastKnownState,
            polled = polled,
            toleranceMs = endOfTrackToleranceMs,
            manualPauseExpected = manualPauseExpected
        )
        consecutiveEmptyPolls = 0
        lastPolledAtMs = nowMs
        if (!polled.isPlaying && !manualPauseExpected) {
            manualPauseExpiresAtMs = 0L
        }
    }

    @Synchronized
    fun markManualPause(nowMs: Long, gracePeriodMs: Long) {
        manualPauseCommandInFlight = true
        manualPauseExpiresAtMs = nowMs + gracePeriodMs.coerceAtLeast(0L)
    }

    @Synchronized
    fun extendManualPauseAfterSuccessfulCommand(nowMs: Long, gracePeriodMs: Long) {
        manualPauseCommandInFlight = false
        manualPauseExpiresAtMs = nowMs + gracePeriodMs.coerceAtLeast(0L)
    }

    @Synchronized
    fun clearManualPause() {
        manualPauseCommandInFlight = false
        manualPauseExpiresAtMs = 0L
    }

    @Synchronized
    fun isManualPauseExpected(nowMs: Long): Boolean =
        manualPauseCommandInFlight ||
            (manualPauseExpiresAtMs > 0L && nowMs <= manualPauseExpiresAtMs)

    @Synchronized
    fun snapshot(nowMs: Long): SpotifyPlaybackState? {
        val cached = lastKnownState ?: return null
        if (isManualPauseExpected(nowMs)) return cached.copy(isPlaying = false)
        return if (cached.isPlaying) {
            extrapolatePosition(cached, (nowMs - lastPolledAtMs).coerceAtLeast(0L))
        } else {
            cached
        }
    }

    @Synchronized
    fun clear() {
        lastKnownState = null
        lastPolledAtMs = 0L
        consecutiveEmptyPolls = 0
        manualPauseExpiresAtMs = 0L
        manualPauseCommandInFlight = false
    }

    private companion object {
        const val DEFAULT_EMPTY_POLLS_BEFORE_CLEAR = 3
    }
}
