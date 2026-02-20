package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackQueueManager @Inject constructor(
    private val media3PlaybackController: Media3PlaybackController,
    private val spotifyPlaybackController: SpotifyPlaybackController,
    private val playbackStateStore: PlaybackStateStore,
    private val json: Json
) {
    private val maxPersistedQueueTracks = 500
    private val errorHandler = CoroutineExceptionHandler { _, _ -> }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playableQueueIndices: List<Int> = emptyList()
    private var isRestoring = false
    private var spotifyMode = false

    private val mutableStatus = MutableStateFlow(
        PlaybackStatus(
            state = PlaybackStateType.IDLE,
            shuffle = false,
            repeatMode = RepeatMode.OFF,
            volume = 100,
            currentTrack = null,
            position = 0,
            duration = 0,
            queue = emptyList()
        )
    )

    val status: StateFlow<PlaybackStatus> = mutableStatus.asStateFlow()

    init {
        scope.launch {
            restorePersistedState()
            while (true) {
                syncFromPlaybackEngine()
                persistState()
                delay(500)
            }
        }
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, autoPlay: Boolean = true) {
        spotifyMode = tracks.isNotEmpty() && tracks.all { it.source == SourceType.SPOTIFY }

        if (tracks.isEmpty()) {
            playableQueueIndices = emptyList()
            media3PlaybackController.setQueue(emptyList(), 0, false)
            mutableStatus.value = mutableStatus.value.copy(
                queue = emptyList(),
                orderedQueue = emptyList(),
                currentTrack = null,
                state = PlaybackStateType.IDLE,
                position = 0,
                duration = 0
            )
            persistStateAsync()
            return
        }

        val index = resolveInitialStartIndex(
            tracks = tracks,
            requestedStartIndex = startIndex,
            autoPlay = autoPlay
        )

        if (spotifyMode) {
            media3PlaybackController.setQueue(emptyList(), 0, false)
            mutableStatus.value = mutableStatus.value.copy(
                queue = tracks,
                orderedQueue = tracks,
                currentTrack = tracks[index],
                state = if (autoPlay) PlaybackStateType.PLAYING else PlaybackStateType.PAUSED,
                position = 0,
                duration = tracks[index].durationMs ?: 0
            )
            if (autoPlay) {
                scope.launch {
                    val started = spotifyPlaybackController.startQueue(tracks.map { it.id }, index)
                    if (!started) {
                        mutableStatus.value = mutableStatus.value.copy(
                            state = PlaybackStateType.ERROR,
                            errorMessage = spotifyPlaybackController.lastError
                        )
                    }
                }
            }
            persistStateAsync()
            return
        }

        playableQueueIndices = tracks.mapIndexedNotNull { queueIndex, track ->
            queueIndex.takeIf { !track.url.isNullOrBlank() && track.source != SourceType.SPOTIFY }
        }
        val mappedIndex = media3PlaybackController.setQueue(tracks, index, autoPlay)
        if (mappedIndex < 0) {
            mutableStatus.value = mutableStatus.value.copy(
                queue = tracks,
                orderedQueue = tracks,
                currentTrack = null,
                state = PlaybackStateType.ERROR,
                position = 0,
                duration = 0
            )
            persistStateAsync()
            return
        }
        val selectedTrack = tracks.getOrNull(playableQueueIndices.getOrNull(mappedIndex) ?: index) ?: tracks[index]
        mutableStatus.value = mutableStatus.value.copy(
            queue = tracks,
            orderedQueue = tracks,
            currentTrack = selectedTrack,
            state = if (autoPlay) PlaybackStateType.PLAYING else PlaybackStateType.PAUSED,
            position = 0,
            duration = selectedTrack.durationMs ?: 0
        )
        persistStateAsync()
    }

    fun playFromIndex(index: Int) {
        val state = mutableStatus.value
        if (state.queue.isEmpty()) return
        val target = index.coerceIn(0, state.queue.lastIndex)

        if (spotifyMode) {
            scope.launch {
                val started = spotifyPlaybackController.startQueue(state.queue.map { it.id }, target)
                mutableStatus.value = if (started) {
                    state.copy(
                        currentTrack = state.queue[target],
                        state = PlaybackStateType.PLAYING,
                        position = 0,
                        duration = state.queue[target].durationMs ?: 0
                    )
                } else {
                    state.copy(
                        state = PlaybackStateType.ERROR,
                        errorMessage = spotifyPlaybackController.lastError
                    )
                }
                persistStateAsync()
            }
            return
        }

        val mediaIndex = playableQueueIndices.indexOf(target)
        if (mediaIndex >= 0) {
            media3PlaybackController.playFromIndex(mediaIndex)
        }
        mutableStatus.value = state.copy(
            currentTrack = state.queue[target],
            state = PlaybackStateType.PLAYING,
            position = 0,
            duration = state.queue[target].durationMs ?: 0
        )
        persistStateAsync()
    }

    fun togglePlayPause() {
        if (spotifyMode) {
            val state = mutableStatus.value
            scope.launch {
                val success = if (state.state == PlaybackStateType.PLAYING) {
                    spotifyPlaybackController.pause()
                } else {
                    // Try to resume first (works if track is already loaded/paused).
                    // If that fails (e.g. player is in Stopped state after app restart),
                    // fall back to reloading the queue and playing from the current track.
                    var ok = spotifyPlaybackController.play()
                    if (!ok && state.queue.isNotEmpty()) {
                        val currentIndex = state.currentTrack
                            ?.let { ct -> state.queue.indexOfFirst { it.id == ct.id } }
                            ?.takeIf { it >= 0 } ?: 0
                        ok = spotifyPlaybackController.startQueue(state.queue.map { it.id }, currentIndex)
                    }
                    ok
                }
                val nextState = if (!success) PlaybackStateType.ERROR else if (state.state == PlaybackStateType.PLAYING) PlaybackStateType.PAUSED else PlaybackStateType.PLAYING
                mutableStatus.value = state.copy(
                    state = nextState,
                    errorMessage = if (!success) spotifyPlaybackController.lastError else null
                )
                persistStateAsync()
            }
            return
        }

        media3PlaybackController.togglePlayPause()
        val state = mutableStatus.value
        val next = when (state.state) {
            PlaybackStateType.PLAYING -> PlaybackStateType.PAUSED
            PlaybackStateType.PAUSED,
            PlaybackStateType.IDLE,
            PlaybackStateType.BUFFERING,
            PlaybackStateType.ERROR -> PlaybackStateType.PLAYING
        }
        mutableStatus.value = state.copy(state = next)
        persistStateAsync()
    }

    fun play() {
        if (spotifyMode) {
            val state = mutableStatus.value
            scope.launch {
                // Try to resume first (works if track is already loaded/paused).
                // If that fails (e.g. player is in Stopped state after app restart),
                // fall back to reloading the queue and playing from the current track.
                var success = spotifyPlaybackController.play()
                if (!success && state.queue.isNotEmpty()) {
                    val currentIndex = state.currentTrack
                        ?.let { ct -> state.queue.indexOfFirst { it.id == ct.id } }
                        ?.takeIf { it >= 0 } ?: 0
                    success = spotifyPlaybackController.startQueue(state.queue.map { it.id }, currentIndex)
                }
                mutableStatus.value = mutableStatus.value.copy(
                    state = if (success) PlaybackStateType.PLAYING else PlaybackStateType.ERROR,
                    errorMessage = if (success) null else spotifyPlaybackController.lastError
                )
                persistStateAsync()
            }
            return
        }

        media3PlaybackController.play()
        mutableStatus.value = mutableStatus.value.copy(state = PlaybackStateType.PLAYING)
        persistStateAsync()
    }

    fun pause() {
        if (spotifyMode) {
            scope.launch {
                val success = spotifyPlaybackController.pause()
                mutableStatus.value = mutableStatus.value.copy(
                    state = if (success) PlaybackStateType.PAUSED else PlaybackStateType.ERROR,
                    errorMessage = if (success) null else spotifyPlaybackController.lastError
                )
                persistStateAsync()
            }
            return
        }

        media3PlaybackController.pause()
        mutableStatus.value = mutableStatus.value.copy(state = PlaybackStateType.PAUSED)
        persistStateAsync()
    }

    fun seekTo(positionMs: Long) {
        if (spotifyMode) {
            scope.launch {
                spotifyPlaybackController.seekTo(positionMs)
                val state = mutableStatus.value
                val duration = if (state.duration <= 0) Long.MAX_VALUE else state.duration
                mutableStatus.value = state.copy(position = positionMs.coerceIn(0, duration))
                persistStateAsync()
            }
            return
        }

        media3PlaybackController.seekTo(positionMs)
        val state = mutableStatus.value
        val duration = if (state.duration <= 0) Long.MAX_VALUE else state.duration
        mutableStatus.value = state.copy(position = positionMs.coerceIn(0, duration))
        persistStateAsync()
    }

    fun setVolume(volume: Int) {
        if (spotifyMode) {
            scope.launch {
                spotifyPlaybackController.setVolume(volume)
                mutableStatus.value = mutableStatus.value.copy(volume = volume.coerceIn(0, 100))
                persistStateAsync()
            }
            return
        }

        media3PlaybackController.setVolume(volume)
        mutableStatus.value = mutableStatus.value.copy(volume = volume.coerceIn(0, 100))
        persistStateAsync()
    }

    fun setShuffle(enabled: Boolean) {
        if (spotifyMode) {
            scope.launch {
                spotifyPlaybackController.setShuffle(enabled)
                mutableStatus.value = mutableStatus.value.copy(shuffle = enabled)
                persistStateAsync()
            }
            return
        }

        media3PlaybackController.setShuffle(enabled)
        mutableStatus.value = mutableStatus.value.copy(shuffle = enabled)
        persistStateAsync()
    }

    fun setRepeatMode(mode: RepeatMode) {
        if (spotifyMode) {
            scope.launch {
                spotifyPlaybackController.setRepeatMode(mode)
                mutableStatus.value = mutableStatus.value.copy(repeatMode = mode)
                persistStateAsync()
            }
            return
        }

        media3PlaybackController.setRepeatMode(mode)
        mutableStatus.value = mutableStatus.value.copy(repeatMode = mode)
        persistStateAsync()
    }

    fun next() {
        if (spotifyMode) {
            scope.launch {
                val success = spotifyPlaybackController.next()
                mutableStatus.value = mutableStatus.value.copy(
                    state = if (success) PlaybackStateType.PLAYING else PlaybackStateType.ERROR,
                    errorMessage = if (success) null else spotifyPlaybackController.lastError
                )
                persistStateAsync()
            }
            return
        }

        media3PlaybackController.next()
        mutableStatus.value = mutableStatus.value.copy(state = PlaybackStateType.PLAYING)
        persistStateAsync()
    }

    fun previous() {
        if (spotifyMode) {
            scope.launch {
                val success = spotifyPlaybackController.previous()
                mutableStatus.value = mutableStatus.value.copy(
                    state = if (success) PlaybackStateType.PLAYING else PlaybackStateType.ERROR,
                    errorMessage = if (success) null else spotifyPlaybackController.lastError
                )
                persistStateAsync()
            }
            return
        }

        media3PlaybackController.previous()
        mutableStatus.value = mutableStatus.value.copy(state = PlaybackStateType.PLAYING)
        persistStateAsync()
    }

    private suspend fun syncFromPlaybackEngine() {
        if (spotifyMode) {
            val spotifySnapshot = spotifyPlaybackController.snapshot() ?: return
            val state = mutableStatus.value
            // Auto-advance to the next track when a track ends naturally.
            // The endOfTrack flag is a consume-once signal from the Rust event
            // loop — it is only true for a single snapshot poll cycle.
            if (spotifySnapshot.endOfTrack) {
                next()
                return
            }
            val mappedTrack = spotifySnapshot.currentTrackId?.let { id ->
                state.queue.firstOrNull { it.id == id }
            }
            mutableStatus.value = state.copy(
                state = if (spotifySnapshot.isPlaying) PlaybackStateType.PLAYING else PlaybackStateType.PAUSED,
                position = spotifySnapshot.progressMs,
                duration = mappedTrack?.durationMs ?: state.duration,
                currentTrack = mappedTrack ?: state.currentTrack,
                volume = spotifySnapshot.volumePercent,
                shuffle = spotifySnapshot.shuffleEnabled,
                repeatMode = spotifySnapshot.repeatMode,
                // Spotify manages shuffle internally — we cannot read its shuffled order
                orderedQueue = state.queue
            )
            return
        }

        val snapshot = media3PlaybackController.snapshot()
        val state = mutableStatus.value
        if (state.queue.isEmpty()) return

        val queueIndex = playableQueueIndices.getOrNull(snapshot.currentMediaIndex)
        val mappedTrack = queueIndex?.let { state.queue.getOrNull(it) }
        val orderedQueue: List<Track> = if (snapshot.shuffle && snapshot.shuffledMediaIndices.isNotEmpty()) {
            snapshot.shuffledMediaIndices.mapNotNull { mediaIdx ->
                playableQueueIndices.getOrNull(mediaIdx)?.let { queueIdx ->
                    state.queue.getOrNull(queueIdx)
                }
            }
        } else state.queue

        mutableStatus.value = state.copy(
            state = snapshot.state,
            position = snapshot.positionMs,
            duration = snapshot.durationMs.takeIf { it > 0 } ?: (mappedTrack?.durationMs ?: 0),
            currentTrack = mappedTrack ?: state.currentTrack,
            volume = snapshot.volume,
            shuffle = snapshot.shuffle,
            repeatMode = snapshot.repeatMode,
            orderedQueue = orderedQueue
        )
    }

    private suspend fun restorePersistedState() {
        val raw = playbackStateStore.read() ?: return
        val persisted = runCatching {
            json.decodeFromString<PersistedPlaybackState>(raw)
        }.getOrNull() ?: return

        if (persisted.queue.isEmpty()) {
            return
        }

        isRestoring = true
        val startIndex = persisted.currentQueueIndex?.coerceIn(0, persisted.queue.lastIndex) ?: 0
        val shouldAutoPlay = persisted.state == PlaybackStateType.PLAYING

        setQueue(persisted.queue, startIndex = startIndex, autoPlay = shouldAutoPlay)
        setVolume(persisted.volume)
        setRepeatMode(persisted.repeatMode)
        setShuffle(persisted.shuffle)
        if (persisted.positionMs > 0) {
            seekTo(persisted.positionMs)
        }

        if (!shouldAutoPlay) {
            pause()
        }

        isRestoring = false
        persistStateAsync()
    }

    private fun persistStateAsync() {
        scope.launch(Dispatchers.IO + errorHandler) {
            persistState()
        }
    }

    private suspend fun persistState() {
        if (isRestoring) {
            return
        }
        val state = mutableStatus.value
        val currentQueueIndex = state.currentTrack?.let { currentTrack ->
            state.queue.indexOfFirst { it.id == currentTrack.id }.takeIf { it >= 0 }
        }
        val persistedQueue = if (state.queue.size > maxPersistedQueueTracks) {
            emptyList()
        } else {
            state.queue
        }
        val persistedCurrentQueueIndex = currentQueueIndex?.takeIf { it < persistedQueue.size }
        val payload = PersistedPlaybackState(
            queue = persistedQueue,
            currentQueueIndex = persistedCurrentQueueIndex,
            positionMs = state.position,
            shuffle = state.shuffle,
            repeatMode = state.repeatMode,
            volume = state.volume,
            state = state.state
        )
        playbackStateStore.write(json.encodeToString(payload))
    }

    @Serializable
    private data class PersistedPlaybackState(
        val queue: List<Track>,
        val currentQueueIndex: Int?,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: RepeatMode,
        val volume: Int,
        val state: PlaybackStateType
    )

    private fun resolveInitialStartIndex(
        tracks: List<Track>,
        requestedStartIndex: Int,
        autoPlay: Boolean
    ): Int {
        val normalizedStartIndex = requestedStartIndex.coerceIn(0, tracks.lastIndex)
        val shuffleEnabled = mutableStatus.value.shuffle
        if (!autoPlay || !shuffleEnabled || requestedStartIndex != 0 || tracks.size <= 1) {
            return normalizedStartIndex
        }

        if (spotifyMode) {
            return Random.nextInt(tracks.size)
        }

        val playableIndices = tracks.mapIndexedNotNull { queueIndex, track ->
            queueIndex.takeIf { !track.url.isNullOrBlank() && track.source != SourceType.SPOTIFY }
        }
        return playableIndices.randomOrNull() ?: normalizedStartIndex
    }

}
