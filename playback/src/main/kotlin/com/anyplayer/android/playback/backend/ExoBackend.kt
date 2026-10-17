package com.anyplayer.android.playback.backend

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.anyplayer.android.core.model.BackendKind
import com.anyplayer.android.core.model.PlanItem
import com.anyplayer.android.core.model.Run
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * ExoPlayer backend: a loaded [Run] becomes the player's playlist, so transitions inside a run
 * are native and gapless. ExoPlayer handles audio focus and becoming-noisy.
 */
@Singleton
class ExoBackend @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlaybackBackend {
    override val kind: BackendKind = BackendKind.EXO

    private val _events = MutableSharedFlow<BackendEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<BackendEvent> = _events.asSharedFlow()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                _events.tryEmit(BackendEvent.ItemStarted(exo.currentMediaItemIndex))
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = emitPlaying()

        override fun onPlaybackStateChanged(playbackState: Int) {
            emitPlaying()
            if (playbackState == Player.STATE_ENDED) _events.tryEmit(BackendEvent.RunEnded)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            _events.tryEmit(BackendEvent.PositionDiscontinuity)
        }

        override fun onPlayerError(error: PlaybackException) {
            _events.tryEmit(BackendEvent.Error(error.message ?: error.errorCodeName))
        }
    }

    private val exo: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { it.addListener(listener) }
    }

    private fun emitPlaying() {
        _events.tryEmit(
            BackendEvent.PlayingChanged(
                isPlaying = exo.isPlaying,
                buffering = exo.playbackState == Player.STATE_BUFFERING && exo.playWhenReady,
            ),
        )
    }

    override suspend fun load(run: Run, startIndex: Int, positionMs: Long, playWhenReady: Boolean) {
        exo.setMediaItems(run.items.map(::toMediaItem), startIndex, positionMs)
        exo.playWhenReady = playWhenReady
        exo.prepare()
    }

    override suspend fun seekToItem(indexInRun: Int, positionMs: Long) {
        exo.seekTo(indexInRun, positionMs)
        if (exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED) exo.prepare()
    }

    override suspend fun play() {
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.play()
    }

    override suspend fun pause() = exo.pause()

    override suspend fun seekTo(ms: Long) = exo.seekTo(ms)

    override suspend fun stop() {
        exo.stop()
        exo.clearMediaItems()
    }

    override fun positionMs(): Long = exo.currentPosition

    override fun durationMs(): Long = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L

    private fun toMediaItem(item: PlanItem): MediaItem = when (item) {
        is PlanItem.Song -> {
            val track = item.entry.track
            MediaItem.Builder()
                .setMediaId(item.entry.uid.toString())
                .setUri(track.url)
                .setCustomCacheKey("${track.source}:${track.id}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .build(),
                )
                .build()
        }
        is PlanItem.Break -> MediaItem.Builder()
            .setMediaId("dj-filler:${item.breakId}")
            .setUri(item.audioUri)
            .build()
    }
}
