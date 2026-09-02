package com.anyplayer.android.feature.playback

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
    var mixedMediaEndStallTrackId: String? = null
    var mixedMediaEndStallPositionMs: Long = -1L
    var mixedMediaEndStallSinceMs: Long = 0L

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
    var spotifyMidTrackStallTrackId: String? = null
    var spotifyMidTrackStallPositionMs: Long = -1L
    var spotifyMidTrackStallSinceMs: Long = 0L

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

    fun resetSpotifyMidTrackStallState() {
        spotifyMidTrackStallTrackId = null
        spotifyMidTrackStallPositionMs = -1L
        spotifyMidTrackStallSinceMs = 0L
    }

    fun resetSpotifyConnectionState() {
        resetSpotifyAutoAdvanceState()
        resetSpotifyRecoveryState()
        resetSpotifyMidTrackStallState()
    }

    fun resetMixedMediaEndStallState() {
        mixedMediaEndStallTrackId = null
        mixedMediaEndStallPositionMs = -1L
        mixedMediaEndStallSinceMs = 0L
    }
}
