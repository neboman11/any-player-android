package com.anyplayer.android.feature.djfiller

import android.net.Uri
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.djfiller.model.AI_DJ_PRESENTATION_TRACK
import com.anyplayer.android.feature.djfiller.model.PreparedFiller
import com.anyplayer.android.feature.playback.InterstitialTransitionListener
import com.anyplayer.android.feature.playback.Media3PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the actual per-mode AI DJ playback (splicing into the local queue vs. standalone
 *  playback during Spotify/Mixed segments - see [Media3PlaybackController.insertInterstitial]
 *  and [Media3PlaybackController.playInterstitialStandalone]), and publishes a synthetic
 *  "AnyPlayer DJ" [nowPlayingOverride] track that UI/lock-screen/session code should prefer
 *  over the real current track whenever it's non-null. */
@Singleton
class DjInterstitialPlayer @Inject constructor(
    private val media3PlaybackController: Media3PlaybackController
) : InterstitialTransitionListener {

    private val mutableNowPlayingOverride = MutableStateFlow<Track?>(null)
    val nowPlayingOverride: StateFlow<Track?> = mutableNowPlayingOverride.asStateFlow()

    val isPlayingInterstitial: Boolean
        get() = media3PlaybackController.isPlayingInterstitial

    init {
        media3PlaybackController.interstitialListener = this
    }

    override fun onInterstitialStarted(mediaId: String) {
        mutableNowPlayingOverride.value = AI_DJ_PRESENTATION_TRACK
    }

    override fun onInterstitialEnded(mediaId: String) {
        mutableNowPlayingOverride.value = null
    }

    /** Local/provider-streamed mode: splice [filler] into the live ExoPlayer timeline right
     *  after the currently playing item; ExoPlayer's own auto-advance then carries playback
     *  into it with zero gap, same as a normal queue transition. */
    fun insertLocal(filler: PreparedFiller) {
        val mediaId = "${Media3PlaybackController.DJ_FILLER_MEDIA_ID_PREFIX}${UUID.randomUUID()}"
        media3PlaybackController.insertInterstitial(Uri.fromFile(filler.audioFile), mediaId)
    }

    /** Spotify/Mixed mode: the shared ExoPlayer is idle whenever a Spotify track is current,
     *  so [filler] plays standalone on it; [onEnded] resumes the caller's own advance logic
     *  (e.g. the Spotify `.next()` call that was deferred to make room for this). */
    fun playStandalone(filler: PreparedFiller, onEnded: () -> Unit) {
        val mediaId = "${Media3PlaybackController.DJ_FILLER_MEDIA_ID_PREFIX}${UUID.randomUUID()}"
        media3PlaybackController.playInterstitialStandalone(Uri.fromFile(filler.audioFile), mediaId, onEnded)
    }
}
