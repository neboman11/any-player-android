package com.anyplayer.android.feature.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.auth.StoredConnection
import com.anyplayer.android.feature.playback.service.resolvePlaybackArtworkUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import javax.inject.Singleton

@Singleton
@UnstableApi
class Media3PlaybackController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val audioCacheManager: AudioCacheManager
) {
    companion object {
        private const val TAG = "Media3PlaybackCtrl"

        /** Prefix marking a synthetic AI DJ voice-over [MediaItem.mediaId], used to spot it
         *  in the raw ExoPlayer timeline without it ever entering the domain-level queue. */
        const val DJ_FILLER_MEDIA_ID_PREFIX = "dj-filler:"
    }

    /** Mutable so a DJ interstitial can be played once, at most, at a time; identifies which
     *  timeline item (if any) is currently the AI DJ voice-over, for both insertion modes
     *  (see [insertInterstitial] and [playInterstitialStandalone]). */
    private var activeInterstitialMediaId: String? = null
    private var standaloneInterstitialEndedCallback: (() -> Unit)? = null
    var interstitialListener: InterstitialTransitionListener? = null

    val isPlayingInterstitial: Boolean
        get() = activeInterstitialMediaId != null

    /** A manual transport skip during an AI DJ break is treated as "skip the break," not
     *  a real track skip - standalone playback (Spotify/Mixed modes) is force-ended right
     *  away so its `onEnded` callback resumes the caller's own advance immediately; a
     *  local-mode splice just needs ExoPlayer's normal seek-to-next, which naturally
     *  carries past it exactly like any other timeline item. */
    fun skipInterstitial() {
        val endedId = activeInterstitialMediaId ?: return
        val onEnded = standaloneInterstitialEndedCallback
        if (onEnded != null) {
            activeInterstitialMediaId = null
            standaloneInterstitialEndedCallback = null
            playerInstance.stop()
            playerInstance.clearMediaItems()
            interstitialListener?.onInterstitialEnded(endedId)
            onEnded()
        } else {
            playerInstance.seekToNextMediaItem()
        }
    }

    fun clearStandaloneInterstitial() {
        val endedId = activeInterstitialMediaId ?: return
        activeInterstitialMediaId = null
        standaloneInterstitialEndedCallback = null
        playerInstance.stop()
        playerInstance.clearMediaItems()
        interstitialListener?.onInterstitialEnded(endedId)
    }

    private val playerInstance: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(audioCacheManager.buildPlayerDataSourceFactory())
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    30_000,
                    60_000,
                    1_000,
                    3_000
                )
                .build()
        )
        .build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                CompatLog.d(TAG, "playbackState=$name mediaIndex=${currentMediaItemIndex} positionMs=$currentPosition bufferedPositionMs=$bufferedPosition")

                // Standalone interstitial playback (Spotify/Mixed modes, see
                // playInterstitialStandalone) uses a single-item timeline with no next
                // item to transition into, so its completion is only observable here.
                if (playbackState == Player.STATE_ENDED) {
                    val endedId = activeInterstitialMediaId
                    val onEnded = standaloneInterstitialEndedCallback
                    if (endedId != null && onEnded != null) {
                        activeInterstitialMediaId = null
                        standaloneInterstitialEndedCallback = null
                        clearMediaItems()
                        interstitialListener?.onInterstitialEnded(endedId)
                        onEnded()
                    }
                }
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                CompatLog.d(TAG, "isLoading=$isLoading mediaIndex=${currentMediaItemIndex} positionMs=$currentPosition bufferedPositionMs=$bufferedPosition")
            }

            override fun onPlayerError(error: PlaybackException) {
                CompatLog.e(TAG, "playerError code=${error.errorCodeName} mediaIndex=${currentMediaItemIndex} positionMs=$currentPosition", error)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                CompatLog.d(TAG, "playWhenReady=$playWhenReady reason=$reason")
            }

            // Local-mode interstitial splice (see insertInterstitial): fires when ExoPlayer's
            // own auto-advance carries playback into or out of a spliced-in dj-filler item.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val newId = mediaItem?.mediaId
                if (newId != null && newId.startsWith(DJ_FILLER_MEDIA_ID_PREFIX)) {
                    activeInterstitialMediaId = newId
                    interstitialListener?.onInterstitialStarted(newId)
                    return
                }
                val endedId = activeInterstitialMediaId ?: return
                activeInterstitialMediaId = null
                val index = (0 until mediaItemCount).firstOrNull { getMediaItemAt(it).mediaId == endedId }
                index?.let { removeMediaItem(it) }
                interstitialListener?.onInterstitialEnded(endedId)
            }
        })
    }

    val player: Player
        get() = playerInstance

    fun setQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean): Int {
        val playableTracks = tracks.filter(::isMedia3PlayableTrack)
        if (playableTracks.isEmpty()) {
            playerInstance.clearMediaItems()
            playerInstance.stop()
            return -1
        }

        val mediaItems = playableTracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(track.url)
                .setCustomCacheKey("${track.source}:${track.id}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .setArtworkUri(track.resolvePlaybackArtworkUri())
                        .build()
                )
                .build()
        }

        val startTrackId = tracks.getOrNull(startIndex)?.id
        val mappedIndex = playableTracks.indexOfFirst { it.id == startTrackId }.takeIf { it >= 0 } ?: 0

        // Clear first so the controller's cached currentMediaItemIndex resets to INDEX_UNSET
        // before the new timeline arrives, preventing PlayerInfo.Builder.build() checkState failures.
        playerInstance.clearMediaItems()
        playerInstance.setMediaItems(mediaItems, mappedIndex.coerceIn(0, mediaItems.lastIndex), 0L)
        playerInstance.prepare()
        playerInstance.playWhenReady = autoPlay

        return mappedIndex
    }

    fun playFromIndex(index: Int) {
        if (playerInstance.mediaItemCount == 0) return
        playerInstance.seekToDefaultPosition(index.coerceIn(0, playerInstance.mediaItemCount - 1))
        playerInstance.playWhenReady = true
    }

    fun play() {
        playerInstance.playWhenReady = true
        playerInstance.play()
    }

    fun pause() {
        playerInstance.pause()
    }

    fun togglePlayPause() {
        if (playerInstance.isPlaying) pause() else play()
    }

    fun next() {
        if (playerInstance.mediaItemCount == 0) return
        playerInstance.seekToNextMediaItem()
        playerInstance.playWhenReady = true
    }

    /** Clears a fatal playback error and re-prepares. ExoPlayer stops responding to
     *  play()/pause()/seek once [Player.getPlayerError] is set - prepare() is the documented
     *  way to retry, and is required before play/pause/skip will have any effect again. */
    fun retryAfterError() {
        playerInstance.prepare()
    }

    fun previous(): Boolean {
        if (playerInstance.mediaItemCount == 0) return false
        // Only seek to previous if we're not at the first item
        if (playerInstance.currentMediaItemIndex > 0) {
            playerInstance.seekToPreviousMediaItem()
            playerInstance.playWhenReady = true
            return true
        }
        return false
    }

    fun seekTo(positionMs: Long) {
        playerInstance.seekTo(positionMs)
    }

    fun setVolume(volume: Int) {
        playerInstance.volume = (volume.coerceIn(0, 100) / 100f)
    }

    fun setShuffle(enabled: Boolean) {
        playerInstance.shuffleModeEnabled = enabled
    }

    fun setRepeatMode(mode: RepeatMode) {
        playerInstance.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
    }

    fun snapshot(): PlaybackSnapshot {
        val state = when {
            playerInstance.playbackState == Player.STATE_BUFFERING -> PlaybackStateType.BUFFERING
            playerInstance.playerError != null -> PlaybackStateType.ERROR
            playerInstance.isPlaying -> PlaybackStateType.PLAYING
            playerInstance.mediaItemCount > 0 -> PlaybackStateType.PAUSED
            else -> PlaybackStateType.IDLE
        }

        val repeat = when (playerInstance.repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }

        val windowCount = playerInstance.mediaItemCount
        val shuffleEnabled = playerInstance.shuffleModeEnabled
        val shuffledMediaIndices: List<Int> = if (windowCount > 0) {
            val timeline = playerInstance.currentTimeline
            val list = mutableListOf<Int>()
            var idx = timeline.getFirstWindowIndex(shuffleEnabled)
            while (idx != C.INDEX_UNSET && list.size < windowCount) {
                list.add(idx)
                idx = timeline.getNextWindowIndex(idx, Player.REPEAT_MODE_OFF, shuffleEnabled)
            }
            if (list.size == windowCount) list else (0 until windowCount).toList()
        } else emptyList()

        return PlaybackSnapshot(
            state = state,
            positionMs = playerInstance.currentPosition.coerceAtLeast(0L),
            durationMs = playerInstance.duration.takeIf { it > 0L } ?: 0L,
            currentMediaIndex = playerInstance.currentMediaItemIndex,
            volume = (playerInstance.volume * 100f).toInt().coerceIn(0, 100),
            shuffle = playerInstance.shuffleModeEnabled,
            repeatMode = repeat,
            shuffledMediaIndices = shuffledMediaIndices
        )
    }

    /** Local/provider-mode AI DJ splice: adds [fileUri] as a timeline item immediately after
     *  the currently playing item, without touching/rebuffering it. ExoPlayer's own
     *  auto-advance then carries playback into it (and, once left behind, [onMediaItemTransition]
     *  removes it) with the same zero-gap behavior as a normal queue transition. No-op if
     *  nothing is currently loaded (nothing to splice after). */
    fun insertInterstitial(fileUri: Uri, mediaId: String) {
        if (playerInstance.mediaItemCount == 0) return
        val insertAt = (playerInstance.currentMediaItemIndex + 1)
            .coerceAtMost(playerInstance.mediaItemCount)
        playerInstance.addMediaItem(
            insertAt,
            MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(fileUri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle("AnyPlayer DJ").build())
                .build()
        )
    }

    /** Spotify/Mixed-mode AI DJ playback: the shared ExoPlayer is idle whenever a Spotify
     *  track is current, so this commandeers it for a one-shot standalone play of [fileUri],
     *  invoking [onEnded] once playback completes (see [Player.STATE_ENDED] handling in the
     *  listener above) so the caller can resume its own advance logic. */
    fun playInterstitialStandalone(fileUri: Uri, mediaId: String, onEnded: () -> Unit) {
        standaloneInterstitialEndedCallback = onEnded
        activeInterstitialMediaId = mediaId
        interstitialListener?.onInterstitialStarted(mediaId)
        playerInstance.setMediaItem(
            MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(fileUri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle("AnyPlayer DJ").build())
                .build()
        )
        playerInstance.prepare()
        playerInstance.playWhenReady = true
    }
}

/** Notifies interested listeners (see [DjInterstitialPlayer]) when the shared ExoPlayer
 *  instance starts or finishes playing an AI DJ voice-over, regardless of which insertion
 *  mechanism ([Media3PlaybackController.insertInterstitial] or
 *  [Media3PlaybackController.playInterstitialStandalone]) is in use. */
interface InterstitialTransitionListener {
    fun onInterstitialStarted(mediaId: String)
    fun onInterstitialEnded(mediaId: String)
}

data class PlaybackSnapshot(
    val state: PlaybackStateType,
    val positionMs: Long,
    val durationMs: Long,
    val currentMediaIndex: Int,
    val volume: Int,
    val shuffle: Boolean,
    val repeatMode: RepeatMode,
    val shuffledMediaIndices: List<Int>
)

/** Whether [track] can be handed to ExoPlayer directly: Spotify tracks are always
 *  excluded (driven separately over Connect, see [SpotifyPlaybackController]), and a
 *  local/provider track needs a non-blank URL with a scheme Media3's default data
 *  source factories can resolve. */
/** Source/URL-presence check shared with call sites (PlaybackQueueManager, LocalPlaybackOps)
 *  exercised by plain-JUnit tests without Robolectric - those can't use
 *  [isMedia3PlayableTrack]'s android.net.Uri scheme validation below, since Uri isn't
 *  mocked outside a Robolectric run. */
internal fun isLocallyPlayableTrack(track: Track): Boolean =
    track.source != SourceType.SPOTIFY && !track.url.isNullOrBlank()

internal fun isMedia3PlayableTrack(track: Track): Boolean {
    if (!isLocallyPlayableTrack(track)) return false
    val raw = track.url?.trim().orEmpty()
    if (raw.isEmpty()) return false
    val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
    val scheme = parsed.scheme?.lowercase().orEmpty()
    return when (scheme) {
        "http", "https", "file", "content", "android.resource" -> true
        else -> false
    }
}

internal fun buildJellyfinRequestHeaders(
    requestUri: Uri,
    connection: StoredConnection?,
    clientName: String,
    deviceName: String,
    deviceId: String,
    version: String
): Map<String, String> {
    if (connection?.source != SourceType.JELLYFIN) return emptyMap()

    val token = connection.token?.trim().orEmpty()
    val parsedServerUri = connection.serverUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::parse)
    val serverAuthority = parsedServerUri?.authority?.lowercase().orEmpty()
    val serverScheme = parsedServerUri?.scheme?.lowercase().orEmpty()
    val requestAuthority = requestUri.authority?.lowercase().orEmpty()
    val requestScheme = requestUri.scheme?.lowercase().orEmpty()

    if (
        token.isBlank() ||
        serverAuthority.isBlank() ||
        serverScheme.isBlank() ||
        requestScheme.isBlank() ||
        serverAuthority != requestAuthority ||
        serverScheme != requestScheme
    ) {
        return emptyMap()
    }

    return mapOf(
        "X-Emby-Token" to token,
        "Authorization" to "MediaBrowser Token=\"$token\", Client=\"$clientName\", Device=\"$deviceName\", DeviceId=\"$deviceId\", Version=\"$version\""
    )
}
