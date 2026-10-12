package com.anyplayer.android.feature.playback

/** Tracks how long a given (trackId, positionMs) pair has persisted across polls, to
 *  distinguish a genuine stall from a single-poll race (e.g. the bridge simply hasn't
 *  caught up yet right after a track starts). Single owner for the "same track & position
 *  -> accumulate elapsed time since first observed, else (re)start the watch" logic that
 *  was previously hand-copied at every call site (SpotifyPlaybackOps.sync's watch,
 *  MixedPlaybackOps.sync's watches) - a fix to that logic (e.g. suppressing it during
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

    var spotifyRecoveryInFlight = false
    var spotifyRecoveryLastAttemptMs = 0L
    var spotifyRecoveryAttempts = 0

    /** Dwell-time bookkeeping for Spotify's own server reporting `is_playing: true` with a
     *  frozen position - e.g. a ghost/zombie Connect session left behind after the app
     *  hosting playback was killed abruptly rather than cleanly paused. A reported pause is
     *  never watched: it is indistinguishable from an external pause (another app taking
     *  audio focus), which must be honored. See [SpotifyPlaybackOps.sync]. */
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
        lastAcknowledgedEndOfTrackCount = 0L
    }

    /** Called by the sync loops whenever a snapshot shows Spotify actually playing - the
     *  only real confirmation that a recovery worked, so only it re-arms the attempt cap. */
    fun onSpotifyPlaybackObserved() {
        spotifyRecoveryAttempts = 0
    }

    fun resetSpotifyRecoveryState() {
        spotifyRecoveryInFlight = false
        spotifyRecoveryLastAttemptMs = 0L
        spotifyRecoveryAttempts = 0
    }

    fun clearGhostPlayingStallWatch() {
        spotifyGhostPlayingStall.clear()
    }

    fun resetSpotifyConnectionState() {
        resetSpotifyAutoAdvanceState()
        resetSpotifyRecoveryState()
        clearGhostPlayingStallWatch()
    }

    fun resetMixedMediaEndStallState() {
        mixedMediaEndStall.clear()
    }
}
