package com.anyplayer.android.feature.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Media3PlaybackController @Inject constructor(
    @ApplicationContext context: Context
) {
    private val playerInstance: ExoPlayer = ExoPlayer.Builder(context).build()

    val player: Player
        get() = playerInstance

    fun setQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean): Int {
        val playableTracks = tracks.filter { !it.url.isNullOrBlank() }
        if (playableTracks.isEmpty()) {
            playerInstance.clearMediaItems()
            playerInstance.stop()
            return -1
        }

        val mediaItems = playableTracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(track.url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .setArtworkUri(track.imageUrl?.let(android.net.Uri::parse))
                        .build()
                )
                .build()
        }

        val startTrackId = tracks.getOrNull(startIndex)?.id
        val mappedIndex = playableTracks.indexOfFirst { it.id == startTrackId }.takeIf { it >= 0 } ?: 0

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
        playerInstance.seekToNextMediaItem()
        playerInstance.playWhenReady = true
    }

    fun previous() {
        playerInstance.seekToPreviousMediaItem()
        playerInstance.playWhenReady = true
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

        return PlaybackSnapshot(
            state = state,
            positionMs = playerInstance.currentPosition.coerceAtLeast(0L),
            durationMs = playerInstance.duration.takeIf { it > 0L } ?: 0L,
            currentMediaIndex = playerInstance.currentMediaItemIndex,
            volume = (playerInstance.volume * 100f).toInt().coerceIn(0, 100),
            shuffle = playerInstance.shuffleModeEnabled,
            repeatMode = repeat
        )
    }
}

data class PlaybackSnapshot(
    val state: PlaybackStateType,
    val positionMs: Long,
    val durationMs: Long,
    val currentMediaIndex: Int,
    val volume: Int,
    val shuffle: Boolean,
    val repeatMode: RepeatMode
)
