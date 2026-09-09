package com.anyplayer.android.feature.playback

import com.anyplayer.android.feature.djfiller.DjFillerScheduler
import com.anyplayer.android.feature.djfiller.DjInterstitialPlayer

/**
 * Shared "is a filler due for [upcomingTrackId]; if so play it standalone on the shared
 * ExoPlayer before [onAdvance], otherwise run [onAdvance] immediately" hook used by every
 * next()/sync() advance path in [SpotifyPlaybackOps]/[MixedPlaybackOps] - previously
 * hand-copied at each of the 5 call sites, which is how the pause-before-hijack step below
 * got silently dropped from two of them. [pauseActiveSpotify], when supplied, runs before
 * the interstitial starts so a still-playing Spotify session can't audibly overlap it -
 * callers whose current track is never Spotify at this point (the natural end-of-track
 * paths, where Spotify has already stopped, and the local/Media3 leg, which is already the
 * same player) pass none.
 */
internal suspend fun DjFillerScheduler.playFillerThenAdvance(
    djInterstitialPlayer: DjInterstitialPlayer,
    upcomingTrackId: String?,
    pauseActiveSpotify: (suspend () -> Unit)? = null,
    onAdvance: () -> Unit
) {
    val filler = consumeReadyFillerIfDue(upcomingTrackId)
    if (filler != null) {
        pauseActiveSpotify?.invoke()
        djInterstitialPlayer.playStandalone(filler, onAdvance)
    } else {
        onAdvance()
    }
}
