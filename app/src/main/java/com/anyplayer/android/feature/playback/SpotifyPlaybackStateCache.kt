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

    @Synchronized
    fun update(polled: SpotifyPlaybackState?, nowMs: Long) {
        if (polled == null) {
            consecutiveEmptyPolls += 1
            if (consecutiveEmptyPolls >= emptyPollsBeforeClear) {
                lastKnownState = null
                lastPolledAtMs = 0L
                manualPauseExpiresAtMs = 0L
            }
            return
        }

        val manualPauseExpected = nowMs <= manualPauseExpiresAtMs
        lastKnownState = mergeEndOfTrackCount(
            previous = lastKnownState,
            polled = polled,
            toleranceMs = endOfTrackToleranceMs,
            manualPauseExpected = manualPauseExpected
        )
        consecutiveEmptyPolls = 0
        lastPolledAtMs = nowMs
        if (!polled.isPlaying) {
            manualPauseExpiresAtMs = 0L
        }
    }

    @Synchronized
    fun markManualPause(nowMs: Long, gracePeriodMs: Long) {
        manualPauseExpiresAtMs = nowMs + gracePeriodMs.coerceAtLeast(0L)
    }

    @Synchronized
    fun extendManualPauseAfterSuccessfulCommand(nowMs: Long, gracePeriodMs: Long) {
        if (lastKnownState?.isPlaying != false) {
            markManualPause(nowMs, gracePeriodMs)
        }
    }

    @Synchronized
    fun clearManualPause() {
        manualPauseExpiresAtMs = 0L
    }

    @Synchronized
    fun snapshot(nowMs: Long): SpotifyPlaybackState? {
        val cached = lastKnownState ?: return null
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
    }

    private companion object {
        const val DEFAULT_EMPTY_POLLS_BEFORE_CLEAR = 3
    }
}
