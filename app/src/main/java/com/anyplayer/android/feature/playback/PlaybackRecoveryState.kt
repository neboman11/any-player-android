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

    /** Timestamp when we first detected endOfTrack but currentTrackId hadn't changed yet. */
    var spotifyEndOfTrackDetectedMs = 0L

    /** The trackId that was playing when we detected endOfTrack; used for the safety fallback. */
    var spotifyEndOfTrackWaitingForTrackId: String? = null

    var spotifyRecoveryInFlight = false
    var spotifyRecoveryLastAttemptMs = 0L
    var spotifyRecoveryAttempts = 0

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
        spotifyEndOfTrackDetectedMs = 0L
        spotifyEndOfTrackWaitingForTrackId = null
    }

    fun resetSpotifyRecoveryState() {
        spotifyRecoveryInFlight = false
        spotifyRecoveryLastAttemptMs = 0L
        spotifyRecoveryAttempts = 0
    }

    fun resetSpotifyConnectionState() {
        resetSpotifyAutoAdvanceState()
        resetSpotifyRecoveryState()
    }

    fun resetMixedMediaEndStallState() {
        mixedMediaEndStallTrackId = null
        mixedMediaEndStallPositionMs = -1L
        mixedMediaEndStallSinceMs = 0L
    }
}
