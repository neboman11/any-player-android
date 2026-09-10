package com.anyplayer.android.feature.playback

/** Tracks how long a given (trackId, positionMs) pair has persisted across polls, to
 *  distinguish a genuine stall from a single-poll race (e.g. the bridge simply hasn't
 *  caught up yet right after a track starts). Single owner for the "same track & position
 *  -> accumulate elapsed time since first observed, else (re)start the watch" logic that
 *  was previously hand-copied at every call site (SpotifyPlaybackOps.sync's two watches,
 *  MixedPlaybackOps.sync's two watches) - a fix to that logic (e.g. suppressing it during
 *  a manual skip) now only needs applying once, here, instead of at each copy. */
internal class StallWatch {
    // Not private-set: PlaybackRecoveryState lives in this module's test source set too,
    // and tests need to seed/backdate a watch directly to simulate elapsed dwell time
    // without a real delay.
    var trackId: String? = null
    var positionMs: Long = -1L
    var sinceMs: Long = 0L

    /** Records [trackId]/[positionMs] as the current observation at [nowMs]. Returns the
     *  elapsed time since this exact (track, position) was first observed, or 0 if it just
     *  (re)started because either differed from the last observation. */
    fun update(nowMs: Long, trackId: String?, positionMs: Long): Long {
        val same = this.trackId == trackId && this.positionMs == positionMs
        if (!same) {
            this.trackId = trackId
            this.positionMs = positionMs
            this.sinceMs = nowMs
            return 0L
        }
        return nowMs - sinceMs
    }

    fun clear() {
        trackId = null
        positionMs = -1L
        sinceMs = 0L
    }
}

/**
 * Bookkeeping for PlaybackQueueManager's auto-advance and error-recovery state
 * machines (mixed-mode media3 end-of-track stall detection, Spotify end-of-track
 * auto-advance, Spotify Connect recovery-after-interruption, and Media3 fatal-error
 * retry). Grouped here since these fields are only ever read/written together as a
 * cohesive "what's the recovery state" concern, separate from the queue/mode state
 * PlaybackQueueManager itself owns.
 */
internal class PlaybackRecoveryState {
    var mixedAutoAdvanceTrackId: String? = null
    val mixedMediaEndStall = StallWatch()

    var spotifyAutoAdvanceInFlight = false
    var spotifyAutoAdvanceTrackId: String? = null

    var spotifyRecoveryInFlight = false
    var spotifyRecoveryLastAttemptMs = 0L
    var spotifyRecoveryAttempts = 0

    /** Dwell-time bookkeeping for a Spotify track that stays reported as
     *  PLAYING-but-not-actually-playing (not near track end): distinguishes a
     *  genuine stuck Connect session from the single-poll lag right after a
     *  track starts, or a momentary state read mid-transition. See
     *  [SpotifyPlaybackOps.sync] / [MixedPlaybackOps.sync]. */
    val spotifyMidTrackStall = StallWatch()

    /** Dwell-time bookkeeping for the opposite case: Spotify's own server keeps reporting
     *  `is_playing: true` with a frozen position - e.g. a ghost/zombie Connect session left
     *  behind after the app hosting playback was killed abruptly rather than cleanly paused.
     *  The mid-track-stall watch above can't catch this since it only fires when the
     *  snapshot reports NOT playing. See [SpotifyPlaybackOps.sync]. */
    val spotifyGhostPlayingStall = StallWatch()

    /** Tracks retries of a Media3 (Jellyfin/local) player that entered a fatal error state -
     *  ExoPlayer stops responding to play()/seek() once playerError is set, until re-prepared. */
    var media3ErrorRecoveryTrackId: String? = null
    var media3ErrorRecoveryAttempts = 0
    var media3ErrorRecoveryLastAttemptMs = 0L

    var manualSkipInFlight = false
    var lastAcknowledgedEndOfTrackCount = 0L

    fun resetSpotifyAutoAdvanceState() {
        spotifyAutoAdvanceInFlight = false
        spotifyAutoAdvanceTrackId = null
        lastAcknowledgedEndOfTrackCount = 0L
    }

    fun resetSpotifyRecoveryState() {
        spotifyRecoveryInFlight = false
        spotifyRecoveryLastAttemptMs = 0L
        spotifyRecoveryAttempts = 0
    }

    fun clearMidTrackStallWatch() {
        spotifyMidTrackStall.clear()
    }

    fun clearGhostPlayingStallWatch() {
        spotifyGhostPlayingStall.clear()
    }

    /** Called at every deliberate-user-action call site (play/pause/skip/etc) to cancel
     *  whichever stall watch might be running. [SpotifyPlaybackOps.sync]'s own per-tick
     *  maintenance of each watch uses the private single-watch clears above instead, since
     *  each watch's condition naturally not holding on a given tick (e.g. mid-track-stall's
     *  `!isPlaying` being false) must NOT be treated as clearing the *other*, unrelated watch. */
    fun resetSpotifyMidTrackStallState() {
        clearMidTrackStallWatch()
        clearGhostPlayingStallWatch()
    }

    fun resetSpotifyConnectionState() {
        resetSpotifyAutoAdvanceState()
        resetSpotifyRecoveryState()
        resetSpotifyMidTrackStallState()
    }

    fun resetMixedMediaEndStallState() {
        mixedMediaEndStall.clear()
    }
}
