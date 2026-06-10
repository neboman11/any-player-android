package com.anyplayer.android.feature.playback

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.anyplayer.android.BuildConfig
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.auth.SecureConnectionStore
import com.anyplayer.android.feature.auth.StoredConnection
import com.anyplayer.android.playback.resolvePlaybackArtworkUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import androidx.media3.common.util.UnstableApi
import javax.inject.Singleton

@Singleton
@UnstableApi
class Media3PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureConnectionStore: SecureConnectionStore
) {
    private val playerInstance: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(createDataSourceFactory())
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
    }

    val player: Player
        get() = playerInstance

    private fun createDataSourceFactory(): DefaultDataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
        return DefaultDataSource.Factory(
            context,
            ResolvingDataSource.Factory(httpFactory) { dataSpec ->
                val jellyfinHeaders = buildJellyfinRequestHeaders(
                    requestUri = dataSpec.uri,
                    connection = secureConnectionStore.read(SourceType.JELLYFIN),
                    clientName = "Any Player",
                    deviceName = Build.MODEL ?: "Android",
                    deviceId = Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ANDROID_ID
                    )?.takeIf { it.isNotBlank() } ?: "any-player-android",
                    version = BuildConfig.VERSION_NAME.ifBlank { "dev" }
                )
                if (jellyfinHeaders.isEmpty()) {
                    dataSpec
                } else {
                    dataSpec.withRequestHeaders(jellyfinHeaders)
                }
            }
        )
    }

    fun setQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean): Int {
        val playableTracks = tracks.filter(::isMedia3Playable)
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

    private fun isMedia3Playable(track: Track): Boolean {
        if (track.source == com.anyplayer.android.core.model.SourceType.SPOTIFY) {
            return false
        }
        val raw = track.url?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase().orEmpty()
        return when (scheme) {
            "http", "https", "file", "content", "android.resource" -> true
            else -> false
        }
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
