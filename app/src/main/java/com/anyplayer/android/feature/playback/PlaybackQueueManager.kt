package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.AudioNormalizationSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    companion object {
        private const val TAG = "PlaybackQueueManager"
    }

    private val maxPersistedQueueTracks = 5000
    private val errorHandler = CoroutineExceptionHandler { _, _ -> }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playableQueueIndices: List<Int> = emptyList()
    private var isRestoring = false
    private var spotifyMode = false
    private var mixedMode = false
    private var mixedAutoAdvanceTrackId: String? = null
    private var mixedMediaEndStallTrackId: String? = null
    private var mixedMediaEndStallPositionMs: Long = -1L
    private var mixedMediaEndStallSinceMs: Long = 0L
    private var spotifyAutoAdvanceInFlight = false
    private var spotifyAutoAdvanceTrackId: String? = null
    private var spotifyAutoAdvanceLastAttemptMs = 0L
    /** Timestamp when we first detected endOfTrack but currentTrackId hadn't changed yet. */
    private var spotifyEndOfTrackDetectedMs = 0L
    /** The trackId that was playing when we detected endOfTrack; used for the safety fallback. */
    private var spotifyEndOfTrackWaitingForTrackId: String? = null
    private var spotifyRecoveryInFlight = false
    private var spotifyRecoveryLastAttemptMs = 0L
    private var spotifyRecoveryAttempts = 0
    private var manualSkipInFlight = false
    private var lastAcknowledgedEndOfTrackCount = 0L
    private var spotifyCurrentQueueIndex = 0
    private var persistTickCounter = 0
    private var lastPersistFingerprint = 0L
    /** O(1) track-ID → queue-index lookup; rebuilt by [rebuildQueueCaches]. */
    private var trackIdIndexMap: Map<String, Int> = emptyMap()
    /** Cached list of track IDs matching [mutableStatus].queue order; rebuilt by [rebuildQueueCaches]. */
    private var cachedQueueTrackIds: List<String> = emptyList()
    private var addToQueueInsertionOffset: Int = 0

    /**
     * Gate that blocks [restorePersistedState] until the provider auth layer
     * (and therefore the Rust/librespot session) has been initialised. The
     * service layer completes this after [ProviderAuthRepository.restoreAll].
     */
    private val providerRestoreGate = CompletableDeferred<Unit>()

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
    private val mutableAudioNormalizationSettings = MutableStateFlow(AudioNormalizationSettings())

    val status: StateFlow<PlaybackStatus> = mutableStatus.asStateFlow()
    val audioNormalizationSettings: StateFlow<AudioNormalizationSettings> =
        mutableAudioNormalizationSettings.asStateFlow()

    suspend fun restorePersistedStateNowIfNeeded() {
        if (mutableStatus.value.queue.isNotEmpty() || isRestoring) {
            ensureWarmSessionState()
            return
        }
        restorePersistedState()
        ensureWarmSessionState()
    }

    fun ensureWarmSessionState() {
        val state = mutableStatus.value
        if (state.queue.isEmpty() || state.currentTrack != null) {
            return
        }
        val fallbackTrack = state.orderedQueue.firstOrNull() ?: state.queue.firstOrNull() ?: return
        mutableStatus.value = state.copy(
            currentTrack = fallbackTrack,
            state = if (state.state == PlaybackStateType.IDLE) PlaybackStateType.PAUSED else state.state,
            duration = fallbackTrack.durationMs ?: state.duration
        )
    }

    fun signalProviderRestoreComplete() {
        providerRestoreGate.complete(Unit)
    }

    private fun rebuildQueueCaches(queue: List<Track>) {
        val idMap = HashMap<String, Int>(queue.size * 2)
        val idList = ArrayList<String>(queue.size)
        queue.forEachIndexed { index, track ->
            val rawId = track.id
            idList.add(rawId)
            idMap.putIfAbsent(rawId, index)
            val normalized = normalizeSpotifyTrackId(rawId)
            if (normalized != rawId) {
                idMap.putIfAbsent(normalized, index)
            }
        }
        trackIdIndexMap = idMap
        cachedQueueTrackIds = idList
    }

    private fun findQueueIndex(trackId: String): Int {
        trackIdIndexMap[trackId]?.let { return it }
        trackIdIndexMap[normalizeSpotifyTrackId(trackId)]?.let { return it }
        return -1
    }

    fun playFromTrackId(trackId: String): Boolean {
        val state = mutableStatus.value
        if (state.queue.isEmpty()) {
            return false
        }
        val target = findQueueIndex(trackId)
        if (target < 0) {
            return false
        }
        playFromIndex(target)
        return true
    }

    init {
        CompatLog.i(TAG, "PlaybackQueueManager initialized")
        scope.launch {
            mutableAudioNormalizationSettings.value =
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    spotifyPlaybackController.getAudioNormalizationSettings()
                }
            // Wait for the service layer to complete provider/session restore
            // so the Rust/librespot session is initialised before we restore
            // any Spotify-backed persisted queue.
            providerRestoreGate.await()
            restorePersistedState()
            while (true) {
                syncFromPlaybackEngine()
                persistTickCounter++
                // Persist every 3s (6 × 500ms) instead of every 500ms
                if (persistTickCounter >= 6) {
                    persistTickCounter = 0
                    persistState()
                }
                delay(500)
            }
        }
    }

    fun setAudioNormalization(enabled: Boolean, strictMode: Boolean) {
        val next = AudioNormalizationSettings(enabled = enabled, strictMode = strictMode)
        mutableAudioNormalizationSettings.value = next

        scope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                spotifyPlaybackController.setAudioNormalizationSettings(enabled, strictMode)
            }

            val requestedVolume = mutableStatus.value.volume.coerceIn(0, 100)
            val currentTrackSource = mutableStatus.value.currentTrack?.source
            val outputVolume = kotlinx.coroutines.withContext(Dispatchers.IO) {
                spotifyPlaybackController.normalizeVolumeForSource(
                    requestedVolume,
                    currentTrackSource ?: SourceType.ALL
                )
            }

            if (spotifyMode || currentTrackSource == SourceType.SPOTIFY) {
                spotifyPlaybackController.setVolume(requestedVolume)
            } else {
                media3PlaybackController.setVolume(outputVolume)
            }
            persistStateAsync()
        }
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, autoPlay: Boolean = true) {
        Log.i(
            TAG,
            "setQueue size=${tracks.size} startIndex=$startIndex autoPlay=$autoPlay spotifyMode=${tracks.isNotEmpty() && tracks.all { it.source == SourceType.SPOTIFY }} mixedMode=${tracks.any { it.source == SourceType.SPOTIFY } && tracks.any { it.source != SourceType.SPOTIFY }}"
        )
        val hasSpotify = tracks.any { it.source == SourceType.SPOTIFY }
        val hasNonSpotify = tracks.any { it.source != SourceType.SPOTIFY }
        spotifyMode = tracks.isNotEmpty() && tracks.all { it.source == SourceType.SPOTIFY }
        mixedMode = hasSpotify && hasNonSpotify

        if (tracks.isEmpty()) {
            resetSpotifyAutoAdvanceState()
            resetSpotifyRecoveryState()
            resetMixedMediaEndStallState()
            playableQueueIndices = emptyList()
            rebuildQueueCaches(emptyList())
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
            resetSpotifyAutoAdvanceState()
            resetSpotifyRecoveryState()
            resetMixedMediaEndStallState()
            rebuildQueueCaches(tracks)
            spotifyCurrentQueueIndex = index
            media3PlaybackController.setQueue(emptyList(), 0, false)
            mutableStatus.value = mutableStatus.value.copy(
                queue = tracks,
                orderedQueue = tracks,
                currentTrack = tracks[index],
                state = if (autoPlay) PlaybackStateType.PLAYING else PlaybackStateType.PAUSED,
                position = 0,
                duration = tracks[index].durationMs ?: 0
            )
            // Only start the Spotify queue when autoPlay is true. startQueue() begins playback
            // immediately; when autoPlay is false we defer until play()/togglePlayPause() is called,
            // which falls back to startQueue() when resume fails.
            if (autoPlay) {
                scope.launch {
                    var started = spotifyPlaybackController.startQueue(cachedQueueTrackIds, index)
                    if (!started) {
                        delay(350)
                        started = spotifyPlaybackController.startQueue(cachedQueueTrackIds, index)
                    }
                    if (started) {
                        spotifyPlaybackController.setVolume(mutableStatus.value.volume)
                    } else {
                        mutableStatus.value = mutableStatus.value.copy(
                            state = PlaybackStateType.ERROR,
                            errorMessage = spotifyErrorOrDefault("Spotify failed to start playback")
                        )
                    }
                }
            }
            persistStateAsync()
            return
        }

        if (mixedMode) {
            resetSpotifyAutoAdvanceState()
            resetSpotifyRecoveryState()
            resetMixedMediaEndStallState()
            rebuildQueueCaches(tracks)
            media3PlaybackController.setQueue(emptyList(), 0, false)
            val clampedIndex = index.coerceIn(0, tracks.lastIndex)
            val selectedTrack = tracks[clampedIndex]
            if (!autoPlay && selectedTrack.source != SourceType.SPOTIFY) {
                media3PlaybackController.setQueue(listOf(selectedTrack), 0, false)
            }
            mutableStatus.value = mutableStatus.value.copy(
                queue = tracks,
                orderedQueue = buildOrderedQueue(
                    queue = tracks,
                    currentTrackId = selectedTrack.id,
                    shuffleEnabled = mutableStatus.value.shuffle
                ),
                currentTrack = selectedTrack,
                state = if (autoPlay) PlaybackStateType.PLAYING else PlaybackStateType.PAUSED,
                position = 0,
                duration = selectedTrack.durationMs ?: 0,
                errorMessage = null
            )
            if (autoPlay) {
                playMixedTrackAtIndex(clampedIndex)
            }
            persistStateAsync()
            return
        }

        playableQueueIndices = tracks.mapIndexedNotNull { queueIndex, track ->
            queueIndex.takeIf { !track.url.isNullOrBlank() && track.source != SourceType.SPOTIFY }
        }
        rebuildQueueCaches(tracks)
        resetSpotifyAutoAdvanceState()
        resetSpotifyRecoveryState()
        resetMixedMediaEndStallState()
        val mappedIndex = media3PlaybackController.setQueue(tracks, index, autoPlay)
        scope.launch {
            val media3Volume = kotlinx.coroutines.withContext(Dispatchers.IO) {
                spotifyPlaybackController.normalizeVolumeForSource(
                    mutableStatus.value.volume,
                    SourceType.ALL
                )
            }
            media3PlaybackController.setVolume(media3Volume)
        }
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

    fun addNextInQueue(track: Track) {
        val state = mutableStatus.value
        if (state.queue.isEmpty()) {
            setQueue(listOf(track), startIndex = 0, autoPlay = false)
            return
        }
        val currentIndex = currentQueueIndex(state)
        val insertionIndex = (currentIndex + 1 + addToQueueInsertionOffset)
            .coerceAtMost(state.queue.size)

        val updatedQueue = state.queue.toMutableList().apply { add(insertionIndex, track) }
        val updatedOrdered = if (state.orderedQueue.isNotEmpty()) {
            val orderedCurrentIndex = state.currentTrack
                ?.let { ct -> state.orderedQueue.indexOfFirst { it.id == ct.id } }
                ?.takeIf { it >= 0 }
                ?: 0
            val orderedInsertionIndex = (orderedCurrentIndex + 1 + addToQueueInsertionOffset)
                .coerceAtMost(state.orderedQueue.size)
            state.orderedQueue.toMutableList().apply { add(orderedInsertionIndex, track) }
        } else {
            updatedQueue
        }

        addToQueueInsertionOffset++
        rebuildQueueCaches(updatedQueue)
        mutableStatus.value = state.copy(
            queue = updatedQueue,
            orderedQueue = updatedOrdered
        )

        if (spotifyMode) {
            val currentSpotifyIndex = currentQueueIndex(state).coerceIn(0, updatedQueue.lastIndex)
            scope.launch {
                val snapshot = spotifyPlaybackController.snapshot()
                val currentPositionMs = snapshot?.progressMs ?: state.position
                val wasPlaying = snapshot?.isPlaying ?: (state.state == PlaybackStateType.PLAYING)
                val started = spotifyPlaybackController.startQueue(cachedQueueTrackIds, currentSpotifyIndex)
                if (started) {
                    if (currentPositionMs > 0) {
                        spotifyPlaybackController.seekTo(currentPositionMs)
                    }
                    if (!wasPlaying) {
                        spotifyPlaybackController.pause()
                    }
                    spotifyPlaybackController.setVolume(mutableStatus.value.volume)
                }
            }
        }
        persistStateAsync()
    }

    fun playFromIndex(index: Int) {
        val state = mutableStatus.value
        Log.i(
            TAG,
            "playFromIndex index=$index queueSize=${state.queue.size} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${state.currentTrack?.id}"
        )
        if (state.queue.isEmpty()) return
        val target = index.coerceIn(0, state.queue.lastIndex)

        if (spotifyMode) {
            scope.launch {
                val started = spotifyPlaybackController.startQueue(cachedQueueTrackIds, target)
                if (started) {
                    spotifyCurrentQueueIndex = target
                    spotifyPlaybackController.setVolume(mutableStatus.value.volume)
                }
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
                        errorMessage = spotifyErrorOrDefault("Spotify failed to start playback")
                    )
                }
                persistStateAsync()
            }
            return
        }

        if (mixedMode) {
            playMixedTrackAtIndex(target)
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
        val initial = mutableStatus.value
        Log.i(
            TAG,
            "togglePlayPause state=${initial.state} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${initial.currentTrack?.id}"
        )
        if (mixedMode) {
            val state = mutableStatus.value
            val currentTrack = state.currentTrack
            if (currentTrack == null) return
            scope.launch {
                val isPlaying = state.state == PlaybackStateType.PLAYING
                val success = when {
                    currentTrack.source == SourceType.SPOTIFY && isPlaying -> spotifyPlaybackController.pause()
                    currentTrack.source == SourceType.SPOTIFY && !isPlaying -> {
                        var ok = spotifyPlaybackController.play()
                        if (!ok) {
                            val fallback = spotifyFallbackQueue(state, currentTrack.id)
                            ok = if (fallback != null) {
                                spotifyPlaybackController.startQueue(fallback.first, fallback.second)
                            } else false
                        }
                        ok
                    }
                    isPlaying -> {
                        media3PlaybackController.pause(); true
                    }
                    else -> {
                        media3PlaybackController.play(); true
                    }
                }
                mutableStatus.value = state.copy(
                    state = if (!success) PlaybackStateType.ERROR else if (isPlaying) PlaybackStateType.PAUSED else PlaybackStateType.PLAYING,
                    errorMessage = if (!success) spotifyErrorOrDefault("Playback command failed") else null
                )
                persistStateAsync()
            }
            return
        }

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
                        val currentIndex = currentQueueIndex(state)
                        ok = spotifyPlaybackController.startQueue(cachedQueueTrackIds, currentIndex)
                    }
                    ok
                }
                val nextState = if (!success) PlaybackStateType.ERROR else if (state.state == PlaybackStateType.PLAYING) PlaybackStateType.PAUSED else PlaybackStateType.PLAYING
                mutableStatus.value = state.copy(
                    state = nextState,
                    errorMessage = if (!success) spotifyErrorOrDefault("Spotify command failed") else null
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
        val initial = mutableStatus.value
        Log.i(
            TAG,
            "play state=${initial.state} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${initial.currentTrack?.id}"
        )
        if (mixedMode) {
            val state = mutableStatus.value
            val currentTrack = state.currentTrack ?: return
            scope.launch {
                val success = if (currentTrack.source == SourceType.SPOTIFY) {
                    var ok = spotifyPlaybackController.play()
                    if (!ok) {
                        val fallback = spotifyFallbackQueue(state, currentTrack.id)
                        ok = if (fallback != null) {
                            spotifyPlaybackController.startQueue(fallback.first, fallback.second)
                        } else false
                    }
                    ok
                } else {
                    media3PlaybackController.play()
                    true
                }
                mutableStatus.value = mutableStatus.value.copy(
                    state = if (success) PlaybackStateType.PLAYING else PlaybackStateType.ERROR,
                    errorMessage = if (success) null else spotifyErrorOrDefault("Failed to resume playback")
                )
                persistStateAsync()
            }
            return
        }

        if (spotifyMode) {
            val state = mutableStatus.value
            scope.launch {
                // Try to resume first (works if track is already loaded/paused).
                // If that fails (e.g. player is in Stopped state after app restart),
                // fall back to reloading the queue and playing from the current track.
                var success = spotifyPlaybackController.play()
                if (!success && state.queue.isNotEmpty()) {
                    val currentIndex = currentQueueIndex(state)
                    success = spotifyPlaybackController.startQueue(cachedQueueTrackIds, currentIndex)
                }
                mutableStatus.value = mutableStatus.value.copy(
                    state = if (success) PlaybackStateType.PLAYING else PlaybackStateType.ERROR,
                    errorMessage = if (success) null else spotifyErrorOrDefault("Spotify failed to resume playback")
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
        val initial = mutableStatus.value
        Log.i(
            TAG,
            "pause state=${initial.state} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${initial.currentTrack?.id}"
        )
        if (mixedMode) {
            val state = mutableStatus.value
            val currentTrack = state.currentTrack ?: return
            scope.launch {
                val success = if (currentTrack.source == SourceType.SPOTIFY) {
                    spotifyPlaybackController.pause()
                } else {
                    media3PlaybackController.pause()
                    true
                }
                mutableStatus.value = mutableStatus.value.copy(
                    state = if (success) PlaybackStateType.PAUSED else PlaybackStateType.ERROR,
                    errorMessage = if (success) null else spotifyErrorOrDefault("Failed to pause playback")
                )
                persistStateAsync()
            }
            return
        }

        if (spotifyMode) {
            scope.launch {
                val success = spotifyPlaybackController.pause()
                mutableStatus.value = mutableStatus.value.copy(
                    state = if (success) PlaybackStateType.PAUSED else PlaybackStateType.ERROR,
                    errorMessage = if (success) null else spotifyErrorOrDefault("Spotify failed to pause")
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
        if (mixedMode) {
            val state = mutableStatus.value
            val currentTrack = state.currentTrack ?: return
            scope.launch {
                if (currentTrack.source == SourceType.SPOTIFY) {
                    spotifyPlaybackController.seekTo(positionMs)
                } else {
                    media3PlaybackController.seekTo(positionMs)
                }
                val duration = if (state.duration <= 0) Long.MAX_VALUE else state.duration
                mutableStatus.value = state.copy(position = positionMs.coerceIn(0, duration))
                persistStateAsync()
            }
            return
        }

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
        val requestedVolume = volume.coerceIn(0, 100)

        if (spotifyMode) {
            scope.launch {
                spotifyPlaybackController.setVolume(requestedVolume)
                mutableStatus.value = mutableStatus.value.copy(volume = requestedVolume)
                persistStateAsync()
            }
            return
        }

        scope.launch {
            val currentTrackSource = mutableStatus.value.currentTrack?.source
            val outputVolume = kotlinx.coroutines.withContext(Dispatchers.IO) {
                spotifyPlaybackController.normalizeVolumeForSource(
                    requestedVolume,
                    currentTrackSource ?: SourceType.ALL
                )
            }
            media3PlaybackController.setVolume(outputVolume)
            mutableStatus.value = mutableStatus.value.copy(volume = requestedVolume)
            persistStateAsync()
        }
    }

    fun setShuffle(enabled: Boolean) {
        if (mixedMode) {
            val state = mutableStatus.value
            mutableStatus.value = state.copy(
                shuffle = enabled,
                orderedQueue = buildOrderedQueue(
                    queue = state.queue,
                    currentTrackId = state.currentTrack?.id,
                    shuffleEnabled = enabled
                )
            )
            persistStateAsync()
            return
        }

        if (spotifyMode) {
            val state = mutableStatus.value
            mutableStatus.value = state.copy(
                shuffle = enabled,
                orderedQueue = buildOrderedQueue(
                    queue = state.queue,
                    currentTrackId = state.currentTrack?.id,
                    shuffleEnabled = enabled
                )
            )
            persistStateAsync()
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
        val state = mutableStatus.value
        Log.i(
            TAG,
            "next state=${state.state} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${state.currentTrack?.id}"
        )
        if (mixedMode) {
            if (state.queue.isEmpty()) return
            val sequence = mixedPlaybackSequence(state)
            if (sequence.isEmpty()) return
            val currentId = state.currentTrack?.id
            val currentIndex = sequence.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
            val nextTrack = sequence.getOrNull(currentIndex + 1) ?: return
            playMixedTrackById(nextTrack.id)
            return
        }

        if (spotifyMode) {
            manualSkipInFlight = true
            resetSpotifyRecoveryState()
            scope.launch {
                try {
                    if (state.queue.isEmpty()) return@launch
                    val currentIndex = resolveSpotifyQueueIndex(state)
                    val targetIndex = (currentIndex + 1).coerceAtMost(state.queue.lastIndex)
                    val targetTrack = state.queue.getOrNull(targetIndex)
                    if (targetTrack == null) {
                        mutableStatus.value = mutableStatus.value.copy(
                            errorMessage = "No track available at target index"
                        )
                        return@launch
                    }
                    val success = startSpotifyAtQueueIndex(targetIndex)
                    if (!success) {
                        spotifyAutoAdvanceInFlight = false
                        Log.w(TAG, "Spotify next failed: ${spotifyErrorOrDefault("unknown error")}")
                    } else {
                        spotifyCurrentQueueIndex = targetIndex
                    }
                    addToQueueInsertionOffset = 0
                    mutableStatus.value = mutableStatus.value.copy(
                        currentTrack = if (success) targetTrack else state.currentTrack,
                        state = if (success) PlaybackStateType.PLAYING else PlaybackStateType.ERROR,
                        position = if (success) 0L else state.position,
                        duration = if (success) (targetTrack.durationMs ?: state.duration) else state.duration,
                        errorMessage = if (success) null else spotifyErrorOrDefault("Spotify failed to skip to next track")
                    )
                    persistStateAsync()
                } finally {
                    manualSkipInFlight = false
                }
            }
            return
        }

        media3PlaybackController.next()
        mutableStatus.value = mutableStatus.value.copy(state = PlaybackStateType.PLAYING)
        persistStateAsync()
    }

    fun previous() {
        val state = mutableStatus.value
        Log.i(
            TAG,
            "previous state=${state.state} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${state.currentTrack?.id}"
        )
        if (mixedMode) {
            if (state.queue.isEmpty()) return
            val sequence = mixedPlaybackSequence(state)
            if (sequence.isEmpty()) return
            val currentId = state.currentTrack?.id
            val currentIndex = sequence.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
            val prevTrack = sequence.getOrNull(currentIndex - 1) ?: return
            playMixedTrackById(prevTrack.id)
            return
        }

        if (spotifyMode) {
            manualSkipInFlight = true
            resetSpotifyRecoveryState()
            scope.launch {
                try {
                    if (state.queue.isEmpty()) return@launch
                    val currentIndex = resolveSpotifyQueueIndex(state)
                    val targetIndex = (currentIndex - 1).coerceAtLeast(0)
                    if (targetIndex == currentIndex && currentIndex == 0) {
                        return@launch
                    }
                    val targetTrack = state.queue.getOrNull(targetIndex)
                    if (targetTrack == null) {
                        mutableStatus.value = mutableStatus.value.copy(
                            errorMessage = "No track available at target index"
                        )
                        return@launch
                    }
                    val success = startSpotifyAtQueueIndex(targetIndex)
                    if (success) {
                        spotifyCurrentQueueIndex = targetIndex
                    }
                    mutableStatus.value = mutableStatus.value.copy(
                        currentTrack = if (success) targetTrack else state.currentTrack,
                        state = if (success) PlaybackStateType.PLAYING else PlaybackStateType.ERROR,
                        position = if (success) 0L else state.position,
                        duration = if (success) (targetTrack.durationMs ?: state.duration) else state.duration,
                        errorMessage = if (success) null else spotifyErrorOrDefault("Spotify failed to go to previous track")
                    )
                    persistStateAsync()
                } finally {
                    manualSkipInFlight = false
                }
            }
            return
        }

        // Only call previous if there's a queue and we're not at the first track
        if (state.queue.isEmpty()) return
        val moved = media3PlaybackController.previous()
        if (moved) {
            mutableStatus.value = mutableStatus.value.copy(state = PlaybackStateType.PLAYING)
            persistStateAsync()
        }
    }

    private suspend fun syncFromPlaybackEngine() {
        if (isRestoring) return
        if (mixedMode) {
            val state = mutableStatus.value
            val currentTrack = state.currentTrack ?: return
            if (currentTrack.source == SourceType.SPOTIFY) {
                val spotifySnapshot = spotifyPlaybackController.snapshot()
                if (spotifySnapshot == null) {
                    if (state.state == PlaybackStateType.PLAYING) {
                        Log.w(TAG, "Mixed-mode Spotify snapshot unavailable; attempting recovery")
                        maybeRecoverSpotifyTrack(
                            queueTrackIds = listOf(currentTrack.id),
                            startIndex = 0,
                            failureMessage = "Spotify snapshot unavailable after disconnect"
                        )
                    }
                    return
                }
                val duration = currentTrack.durationMs ?: state.duration
                val nearTrackEnd = isNearTrackEnd(
                    positionMs = spotifySnapshot.progressMs,
                    durationMs = duration,
                    toleranceMs = 1500L
                )
                mutableStatus.value = state.copy(
                    state = if (spotifySnapshot.isPlaying) PlaybackStateType.PLAYING else PlaybackStateType.PAUSED,
                    position = spotifySnapshot.progressMs,
                    duration = duration,
                    volume = state.volume,
                    shuffle = state.shuffle,
                    repeatMode = spotifySnapshot.repeatMode,
                    orderedQueue = mixedPlaybackSequence(state)
                )
                if (spotifySnapshot.endOfTrackCount > lastAcknowledgedEndOfTrackCount) {
                    lastAcknowledgedEndOfTrackCount = spotifySnapshot.endOfTrackCount
                    val sequence = mixedPlaybackSequence(state)
                    val currentIndex = sequence.indexOfFirst { it.id == currentTrack.id }.takeIf { it >= 0 } ?: 0
                    val nextTrack = sequence.getOrNull(currentIndex + 1)
                    if (nextTrack != null) {
                        playMixedTrackById(nextTrack.id)
                        return
                    }
                }
                if (state.state == PlaybackStateType.PLAYING && !spotifySnapshot.isPlaying) {
                    val sequence = mixedPlaybackSequence(state)
                    val currentIndex = sequence.indexOfFirst { it.id == currentTrack.id }.takeIf { it >= 0 } ?: 0
                    val nextTrack = sequence.getOrNull(currentIndex + 1)
                    if (nearTrackEnd && nextTrack != null) {
                        playMixedTrackById(nextTrack.id)
                        return
                    }
                    maybeRecoverSpotifyTrack(
                        queueTrackIds = listOf(currentTrack.id),
                        startIndex = 0,
                        failureMessage = "Spotify failed to recover mixed-track playback"
                    )
                }
            } else {
                val snapshot = media3PlaybackController.snapshot()
                val sequence = mixedPlaybackSequence(state)
                val currentIndex = sequence.indexOfFirst { it.id == currentTrack.id }
                val effectiveDuration = snapshot.durationMs.takeIf { it > 0 } ?: (currentTrack.durationMs ?: state.duration)
                val nearTrackEnd = isNearTrackEnd(
                    positionMs = snapshot.positionMs,
                    durationMs = effectiveDuration,
                    toleranceMs = 1200L
                )
                val reachedEnd = snapshot.durationMs > 0L && snapshot.positionMs >= (snapshot.durationMs - 1000L)
                val transitionedOutOfPlaying =
                    state.state == PlaybackStateType.PLAYING && snapshot.state != PlaybackStateType.PLAYING
                if (transitionedOutOfPlaying && reachedEnd) {
                    if (mixedAutoAdvanceTrackId != currentTrack.id) {
                        mixedAutoAdvanceTrackId = currentTrack.id
                        resetMixedMediaEndStallState()
                        val nextTrack = sequence.getOrNull(currentIndex + 1)
                        if (nextTrack != null) {
                            playMixedTrackById(nextTrack.id)
                            return
                        }
                    }
                }

                if (state.state == PlaybackStateType.PLAYING && snapshot.state == PlaybackStateType.PLAYING && nearTrackEnd) {
                    val nowMs = System.currentTimeMillis()
                    val sameTrack = mixedMediaEndStallTrackId == currentTrack.id
                    val samePosition = mixedMediaEndStallPositionMs == snapshot.positionMs
                    if (!sameTrack || !samePosition) {
                        mixedMediaEndStallTrackId = currentTrack.id
                        mixedMediaEndStallPositionMs = snapshot.positionMs
                        mixedMediaEndStallSinceMs = nowMs
                    } else {
                        val stalledMs = nowMs - mixedMediaEndStallSinceMs
                        if (stalledMs >= 1800L && mixedAutoAdvanceTrackId != currentTrack.id) {
                            mixedAutoAdvanceTrackId = currentTrack.id
                            val nextTrack = sequence.getOrNull(currentIndex + 1)
                            if (nextTrack != null) {
                                Log.w(
                                    TAG,
                                    "Detected mixed Media3 end stall; forcing next track transition after ${stalledMs}ms"
                                )
                                resetMixedMediaEndStallState()
                                playMixedTrackById(nextTrack.id)
                                return
                            } else {
                                resetMixedMediaEndStallState()
                            }
                        }
                    }
                } else {
                    resetMixedMediaEndStallState()
                }

                if (snapshot.state == PlaybackStateType.PLAYING) {
                    mixedAutoAdvanceTrackId = null
                }
                mutableStatus.value = state.copy(
                    state = snapshot.state,
                    position = snapshot.positionMs,
                    duration = effectiveDuration,
                    volume = state.volume,
                    shuffle = state.shuffle,
                    repeatMode = snapshot.repeatMode,
                    orderedQueue = mixedPlaybackSequence(state)
                )
            }
            return
        }

        if (spotifyMode) {
            val spotifySnapshot = spotifyPlaybackController.snapshot()
            val state = mutableStatus.value
            if (spotifySnapshot == null) {
                if ((state.state == PlaybackStateType.PLAYING || state.state == PlaybackStateType.ERROR) && state.queue.isNotEmpty()) {
                    val currentIndex = currentQueueIndex(state)
                    Log.w(TAG, "Spotify snapshot unavailable; attempting queue recovery at index=$currentIndex state=${state.state}")
                    maybeRecoverSpotifyTrack(
                        queueTrackIds = cachedQueueTrackIds,
                        startIndex = currentIndex,
                        failureMessage = "Spotify snapshot unavailable after interruption"
                    )
                }
                return
            }
            val duration = state.currentTrack?.durationMs ?: state.duration
            val nearTrackEnd = isNearTrackEnd(
                positionMs = spotifySnapshot.progressMs,
                durationMs = duration,
                toleranceMs = 1500L
            )
            if (spotifySnapshot.endOfTrackCount > lastAcknowledgedEndOfTrackCount && !manualSkipInFlight) {
                val nowMs = System.currentTimeMillis()
                val previousTrackId = state.currentTrack?.id
                Log.d(
                    TAG,
                    "Spotify endOfTrack detected; waiting for Rust auto-advance trackId=$previousTrackId count=${spotifySnapshot.endOfTrackCount}"
                )
                lastAcknowledgedEndOfTrackCount = spotifySnapshot.endOfTrackCount
                if (spotifyEndOfTrackWaitingForTrackId != previousTrackId) {
                    spotifyEndOfTrackDetectedMs = nowMs
                    spotifyEndOfTrackWaitingForTrackId = previousTrackId
                }
            }
            if (spotifyEndOfTrackWaitingForTrackId != null) {
                val snapshotTrackId = spotifySnapshot.currentTrackId
                val rustAdvanced = snapshotTrackId != null &&
                    !spotifyTrackIdsMatch(snapshotTrackId, spotifyEndOfTrackWaitingForTrackId!!)
                if (rustAdvanced) {
                    if (state.queue.isNotEmpty()) {
                        val advancedTrackIndex = snapshotTrackId
                            ?.let { findQueueIndexNear(it, spotifyCurrentQueueIndex, state.queue) }
                            ?.takeIf { it >= 0 }
                        if (advancedTrackIndex != null) {
                            spotifyCurrentQueueIndex = advancedTrackIndex
                        }
                    }
                    spotifyEndOfTrackWaitingForTrackId = null
                    spotifyEndOfTrackDetectedMs = 0L
                    addToQueueInsertionOffset = 0
                } else {
                    val elapsedMs = System.currentTimeMillis() - spotifyEndOfTrackDetectedMs
                    if (elapsedMs >= 2000L && !spotifyAutoAdvanceInFlight) {
                        Log.w(TAG, "Rust did not auto-advance after ${elapsedMs}ms; issuing one spotifyNext()")
                        spotifyAutoAdvanceInFlight = true
                        scope.launch {
                            val success = spotifyPlaybackController.next()
                            spotifyAutoAdvanceInFlight = false
                            if (success) {
                                val waitingTrackId = spotifyEndOfTrackWaitingForTrackId
                                val advancedTrackIndex = awaitSpotifyAdvance(
                                    previousTrackId = waitingTrackId,
                                    state = mutableStatus.value,
                                    timeoutMs = 1200L
                                )
                                if (advancedTrackIndex != null) {
                                    spotifyCurrentQueueIndex = advancedTrackIndex
                                    addToQueueInsertionOffset = 0
                                } else {
                                    Log.w(TAG, "spotifyNext() returned success without advancing; falling back to startQueue")
                                    fallbackAdvanceSpotifyQueue(waitingTrackId, mutableStatus.value)
                                    addToQueueInsertionOffset = 0
                                }
                            } else {
                                Log.w(TAG, "Safety spotifyNext() failed; falling back to startQueue")
                                fallbackAdvanceSpotifyQueue(spotifyEndOfTrackWaitingForTrackId, state)
                                addToQueueInsertionOffset = 0
                            }
                            spotifyEndOfTrackWaitingForTrackId = null
                            spotifyEndOfTrackDetectedMs = 0L
                        }
                        return
                    }
                }
            }
            if (state.state == PlaybackStateType.PLAYING && !spotifySnapshot.isPlaying && spotifyEndOfTrackWaitingForTrackId == null) {
                if (nearTrackEnd && !manualSkipInFlight) {
                    return
                }
                Log.w(
                    TAG,
                    "Spotify expected PLAYING but snapshot is paused; attempting queue recovery at current track"
                )
                val currentIndex = currentQueueIndex(state)
                maybeRecoverSpotifyTrack(
                    queueTrackIds = cachedQueueTrackIds,
                    startIndex = currentIndex,
                    failureMessage = "Spotify failed to recover playback after interruption"
                )
            }
            if (state.state == PlaybackStateType.ERROR && !spotifySnapshot.isPlaying && state.queue.isNotEmpty()) {
                val currentIndex = currentQueueIndex(state)
                Log.w(TAG, "Spotify in ERROR state; attempting recovery at index=$currentIndex attempts=$spotifyRecoveryAttempts")
                maybeRecoverSpotifyTrack(
                    queueTrackIds = cachedQueueTrackIds,
                    startIndex = currentIndex,
                    failureMessage = "Spotify failed to recover from error state"
                )
                return
            }
            if (spotifyAutoAdvanceInFlight) {
                spotifyAutoAdvanceInFlight = false
                spotifyAutoAdvanceTrackId = null
            }
            val mappedTrackIndex = spotifySnapshot.currentTrackId?.let { id ->
                findQueueIndexNear(id, spotifyCurrentQueueIndex, state.queue).takeIf { it >= 0 }
            }
            if (mappedTrackIndex != null) {
                spotifyCurrentQueueIndex = mappedTrackIndex
            }
            val mappedTrack = mappedTrackIndex?.let { state.queue[it] }
            val effectiveShuffle = state.shuffle
            mutableStatus.value = state.copy(
                state = if (spotifySnapshot.isPlaying) PlaybackStateType.PLAYING else PlaybackStateType.PAUSED,
                position = spotifySnapshot.progressMs,
                duration = mappedTrack?.durationMs ?: state.duration,
                currentTrack = mappedTrack ?: state.currentTrack,
                volume = state.volume,
                shuffle = effectiveShuffle,
                repeatMode = spotifySnapshot.repeatMode,
                orderedQueue = state.orderedQueue.ifEmpty { state.queue }
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
            volume = state.volume,
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
        setAudioNormalization(
            persisted.audioNormalizationEnabled,
            persisted.audioNormalizationStrictMode
        )

        // Set shuffle flag BEFORE setQueue so buildOrderedQueue uses the
        // persisted value instead of the default (false). This prevents
        // generating a new random shuffle order on every restore.
        mutableStatus.value = mutableStatus.value.copy(shuffle = persisted.shuffle)

        val startIndex = persisted.currentQueueIndex?.coerceIn(0, persisted.queue.lastIndex) ?: 0
        val shouldAutoPlay = false

        setQueue(persisted.queue, startIndex = startIndex, autoPlay = shouldAutoPlay)

        // Restore the persisted orderedQueue if available and valid, so the
        // shuffled order is preserved across restarts instead of re-randomizing.
        val persistedOrdered = persisted.orderedQueue
        if (persisted.shuffle && !persistedOrdered.isNullOrEmpty()) {
            val persistedIds = persistedOrdered.map { it.id }.toSet()
            val currentIds = persisted.queue.map { it.id }.toSet()
            if (persistedIds == currentIds) {
                mutableStatus.value = mutableStatus.value.copy(orderedQueue = persistedOrdered)
            }
        }

        setVolume(persisted.volume)
        setRepeatMode(persisted.repeatMode)
        if (persisted.positionMs > 0 && !spotifyMode) {
            seekTo(persisted.positionMs)
        }

        if (spotifyMode && persisted.queue.isNotEmpty()) {
            val restoreTrackIds: List<String>
            val restoreStartIndex: Int
            if (persisted.shuffle && !persistedOrdered.isNullOrEmpty()) {
                restoreTrackIds = persistedOrdered.map { it.id }
                val expectedTrackId = persisted.queue.getOrNull(startIndex)?.id
                restoreStartIndex = if (expectedTrackId != null) {
                    restoreTrackIds.indexOfFirst { normalizeSpotifyTrackId(it) == normalizeSpotifyTrackId(expectedTrackId) }
                        .takeIf { it >= 0 } ?: 0
                } else 0
            } else {
                restoreTrackIds = cachedQueueTrackIds
                restoreStartIndex = startIndex
            }

            val expectedTrackId = restoreTrackIds.getOrNull(restoreStartIndex)
            val started = spotifyPlaybackController.startQueue(restoreTrackIds, restoreStartIndex)
            if (started) {
                spotifyPlaybackController.setVolume(mutableStatus.value.volume)
                if (expectedTrackId != null && persisted.positionMs > 0) {
                    var readyForSeek = false
                    for (attempt in 1..10) {
                        delay(200)
                        val snap = spotifyPlaybackController.snapshot()
                        if (snap?.currentTrackId != null &&
                            spotifyTrackIdsMatch(snap.currentTrackId, expectedTrackId)
                        ) {
                            readyForSeek = true
                            break
                        }
                    }
                    if (readyForSeek) {
                        spotifyPlaybackController.seekTo(persisted.positionMs)
                        delay(100)
                    }
                }
                spotifyPlaybackController.pause()
            }
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
        val audioNorm = mutableAudioNormalizationSettings.value
        val positionBucket = state.position / 1000L
        var fp = state.queue.size.toLong()
        fp = fp * 31 + (state.queue.firstOrNull()?.id?.hashCode()?.toLong() ?: 0L)
        fp = fp * 31 + (state.queue.lastOrNull()?.id?.hashCode()?.toLong() ?: 0L)
        fp = fp * 31 + (state.currentTrack?.id?.hashCode()?.toLong() ?: 0L)
        fp = fp * 31 + positionBucket
        fp = fp * 31 + state.volume
        fp = fp * 31 + if (state.shuffle) 1L else 0L
        fp = fp * 31 + state.repeatMode.ordinal
        fp = fp * 31 + if (audioNorm.enabled) 1L else 0L
        fp = fp * 31 + if (audioNorm.strictMode) 1L else 0L
        fp = fp * 31 + state.state.ordinal
        if (fp == lastPersistFingerprint) {
            return
        }
        lastPersistFingerprint = fp
        val currentQueueIndex = state.currentTrack?.let { currentTrack ->
            findQueueIndex(currentTrack.id).takeIf { it >= 0 }
        }
        val persistedQueue = if (state.queue.size > maxPersistedQueueTracks) {
            emptyList()
        } else {
            state.queue
        }
        val persistedCurrentQueueIndex = currentQueueIndex?.takeIf { it < persistedQueue.size }
        val payload = PersistedPlaybackState(
            queue = persistedQueue,
            orderedQueue = if (state.shuffle && state.orderedQueue.isNotEmpty()) state.orderedQueue else null,
            currentQueueIndex = persistedCurrentQueueIndex,
            positionMs = state.position,
            shuffle = state.shuffle,
            repeatMode = state.repeatMode,
            volume = state.volume,
            audioNormalizationEnabled = audioNorm.enabled,
            audioNormalizationStrictMode = audioNorm.strictMode,
            state = state.state
        )
        playbackStateStore.write(json.encodeToString(payload))
    }

    @Serializable
    private data class PersistedPlaybackState(
        val queue: List<Track>,
        val orderedQueue: List<Track>? = null,
        val currentQueueIndex: Int?,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: RepeatMode,
        val volume: Int,
        val audioNormalizationEnabled: Boolean = false,
        val audioNormalizationStrictMode: Boolean = false,
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

    private fun spotifyTrackIdsMatch(queueTrackId: String, snapshotTrackId: String): Boolean {
        if (queueTrackId == snapshotTrackId) return true
        return normalizeSpotifyTrackId(queueTrackId) == normalizeSpotifyTrackId(snapshotTrackId)
    }

    private fun trackIdsMatch(queueTrackId: String, targetTrackId: String): Boolean {
        if (queueTrackId == targetTrackId) return true
        return normalizeSpotifyTrackId(queueTrackId) == normalizeSpotifyTrackId(targetTrackId)
    }

    private fun normalizeSpotifyTrackId(value: String): String =
        value.removePrefix("spotify:track:").trim()

    private fun spotifyErrorOrDefault(defaultMessage: String): String =
        spotifyPlaybackController.lastError?.takeIf { it.isNotBlank() } ?: defaultMessage

    private fun resetSpotifyAutoAdvanceState() {
        spotifyAutoAdvanceInFlight = false
        spotifyAutoAdvanceTrackId = null
        spotifyAutoAdvanceLastAttemptMs = 0L
        lastAcknowledgedEndOfTrackCount = 0L
        spotifyEndOfTrackDetectedMs = 0L
        spotifyEndOfTrackWaitingForTrackId = null
    }

    private fun resetSpotifyRecoveryState() {
        spotifyRecoveryInFlight = false
        spotifyRecoveryLastAttemptMs = 0L
        spotifyRecoveryAttempts = 0
    }

    fun resetSpotifyConnectionState() {
        spotifyAutoAdvanceInFlight = false
        spotifyAutoAdvanceTrackId = null
        spotifyAutoAdvanceLastAttemptMs = 0L
        spotifyEndOfTrackDetectedMs = 0L
        spotifyEndOfTrackWaitingForTrackId = null
        resetSpotifyRecoveryState()
    }

    private fun resetMixedMediaEndStallState() {
        mixedMediaEndStallTrackId = null
        mixedMediaEndStallPositionMs = -1L
        mixedMediaEndStallSinceMs = 0L
    }

    private fun isNearTrackEnd(positionMs: Long, durationMs: Long, toleranceMs: Long): Boolean {
        if (durationMs <= 0L) return false
        val threshold = (durationMs - toleranceMs).coerceAtLeast(0L)
        return positionMs >= threshold
    }

    private fun maybeRecoverSpotifyTrack(
        queueTrackIds: List<String>,
        startIndex: Int,
        failureMessage: String
    ) {
        if (queueTrackIds.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        val cooldownMs = if (spotifyRecoveryAttempts >= 1) 4000L else 2500L
        if (spotifyRecoveryInFlight || (nowMs - spotifyRecoveryLastAttemptMs) < cooldownMs) {
            Log.d(
                TAG,
                "Skipping Spotify recovery attempt inFlight=$spotifyRecoveryInFlight cooldownMs=${nowMs - spotifyRecoveryLastAttemptMs} attempts=$spotifyRecoveryAttempts"
            )
            return
        }
        if (spotifyRecoveryAttempts >= 3) {
            Log.w(TAG, "Spotify recovery exhausted after $spotifyRecoveryAttempts attempts")
            return
        }
        spotifyRecoveryInFlight = true
        spotifyRecoveryLastAttemptMs = nowMs
        spotifyRecoveryAttempts++
        val attempt = spotifyRecoveryAttempts
        Log.i(TAG, "Attempting Spotify recovery (attempt $attempt) startIndex=$startIndex queueSize=${queueTrackIds.size}")
        scope.launch {
            val recovered = spotifyPlaybackController.startQueue(queueTrackIds, startIndex)
            if (recovered) {
                spotifyCurrentQueueIndex = startIndex.coerceIn(0, (queueTrackIds.size - 1).coerceAtLeast(0))
                spotifyPlaybackController.setVolume(mutableStatus.value.volume)
                Log.i(TAG, "Spotify recovery succeeded on attempt $attempt")
                spotifyRecoveryAttempts = 0
            } else {
                val state = mutableStatus.value
                Log.w(TAG, "Spotify recovery attempt $attempt failed: ${spotifyErrorOrDefault("unknown error")}")
                mutableStatus.value = state.copy(
                    state = PlaybackStateType.ERROR,
                    errorMessage = spotifyErrorOrDefault(failureMessage)
                )
                persistStateAsync()
            }
            spotifyRecoveryInFlight = false
        }
    }

    private suspend fun awaitSpotifyAdvance(
        previousTrackId: String?,
        state: PlaybackStatus,
        timeoutMs: Long
    ): Int? {
        if (previousTrackId.isNullOrBlank() || state.queue.isEmpty()) return null
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadlineMs) {
            val snapshotTrackId = spotifyPlaybackController.snapshot()?.currentTrackId
            val advancedTrackId = snapshotTrackId
                ?.takeIf { !spotifyTrackIdsMatch(it, previousTrackId) }
            if (advancedTrackId != null) {
                val advancedIndex = findQueueIndexNear(advancedTrackId, spotifyCurrentQueueIndex, state.queue)
                if (advancedIndex >= 0) {
                    return advancedIndex
                }
            }
            delay(100)
        }
        return null
    }

    private suspend fun fallbackAdvanceSpotifyQueue(endedTrackId: String?, state: PlaybackStatus) {
        if (state.queue.isEmpty()) return
        val fallbackSnapshotTrackId = spotifyPlaybackController.snapshot()?.currentTrackId
        if (!fallbackSnapshotTrackId.isNullOrBlank() && !spotifyTrackIdsMatch(
                fallbackSnapshotTrackId,
                endedTrackId.orEmpty()
            )
        ) {
            val advancedIndex = findQueueIndexNear(fallbackSnapshotTrackId, spotifyCurrentQueueIndex, state.queue)
            if (advancedIndex >= 0) {
                spotifyCurrentQueueIndex = advancedIndex
            }
            Log.i(TAG, "Spotify track already advanced; skipping fallback restart")
            return
        }

        val endedIndex = endedTrackId
            ?.let { findQueueIndexNear(it, spotifyCurrentQueueIndex, state.queue) }
            ?.takeIf { it >= 0 }
            ?: currentQueueIndex(state)
        val nextIdx = endedIndex + 1
        if (nextIdx in state.queue.indices && nextIdx > endedIndex) {
            startSpotifyAtQueueIndex(nextIdx)
            spotifyCurrentQueueIndex = nextIdx
        } else {
            Log.i(TAG, "Skipping Spotify fallback restart; endedIndex=$endedIndex queueSize=${state.queue.size}")
        }
    }

    private suspend fun startSpotifyAtQueueIndex(targetIndex: Int): Boolean {
        val state = mutableStatus.value
        if (!spotifyMode || state.queue.isEmpty()) return false
        val safeIndex = targetIndex.coerceIn(0, state.queue.lastIndex)
        val started = spotifyPlaybackController.startQueue(cachedQueueTrackIds, safeIndex)
        if (started) {
            spotifyCurrentQueueIndex = safeIndex
            spotifyPlaybackController.setVolume(state.volume)
        }
        return started
    }

    private suspend fun resolveSpotifyQueueIndex(state: PlaybackStatus): Int {
        val snapshotTrackId = spotifyPlaybackController.snapshot()?.currentTrackId
        if (!snapshotTrackId.isNullOrBlank()) {
            val snapshotIndex = findQueueIndexNear(snapshotTrackId, spotifyCurrentQueueIndex, state.queue)
            if (snapshotIndex >= 0) {
                return snapshotIndex
            }
        }
        return currentQueueIndex(state)
    }

    private fun currentQueueIndex(state: PlaybackStatus): Int {
        val currentId = state.currentTrack?.id ?: return 0
        val idx = findQueueIndexNear(currentId, spotifyCurrentQueueIndex, state.queue)
        if (idx >= 0) {
            return idx
        }
        return spotifyCurrentQueueIndex.coerceIn(0, state.queue.lastIndex.coerceAtLeast(0))
    }

    private fun findQueueIndexNear(trackId: String, preferredIndex: Int, queue: List<Track>): Int {
        if (queue.isEmpty()) return -1
        val clampedPreferred = preferredIndex.coerceIn(0, queue.lastIndex)
        if (trackIdsMatch(queue[clampedPreferred].id, trackId)) {
            return clampedPreferred
        }
        var distance = 1
        while (clampedPreferred - distance >= 0 || clampedPreferred + distance <= queue.lastIndex) {
            val forward = clampedPreferred + distance
            if (forward <= queue.lastIndex && trackIdsMatch(queue[forward].id, trackId)) {
                return forward
            }
            val backward = clampedPreferred - distance
            if (backward >= 0 && trackIdsMatch(queue[backward].id, trackId)) {
                return backward
            }
            distance++
        }
        return -1
    }

    private fun mixedPlaybackSequence(state: PlaybackStatus): List<Track> =
        state.orderedQueue.takeIf { it.isNotEmpty() } ?: state.queue

    /**
     * Builds a startQueue fallback for mixed-mode Spotify sessions: collects all Spotify tracks
     * from [mixedPlaybackSequence] in order and returns a pair of (ids, startIndex).
     * Returns null if there are no Spotify tracks in the sequence.
     */
    private fun spotifyFallbackQueue(state: PlaybackStatus, currentTrackId: String): Pair<List<String>, Int>? {
        val ids = mixedPlaybackSequence(state)
            .filter { it.source == SourceType.SPOTIFY }
            .map { it.id }
        if (ids.isEmpty()) return null
        val index = ids.indexOfFirst { trackIdsMatch(it, currentTrackId) }.takeIf { it >= 0 } ?: 0
        return ids to index
    }

    private fun buildOrderedQueue(
        queue: List<Track>,
        currentTrackId: String?,
        shuffleEnabled: Boolean
    ): List<Track> {
        if (!shuffleEnabled || queue.size <= 1) return queue
        val current = currentTrackId?.let { id -> queue.firstOrNull { it.id == id } }
        val remainder = if (current != null) queue.filterNot { it.id == current.id } else queue
        val shuffledRemainder = remainder.shuffled()
        return if (current != null) listOf(current) + shuffledRemainder else shuffledRemainder
    }

    private fun playMixedTrackAtIndex(index: Int) {
        val state = mutableStatus.value
        if (state.queue.isEmpty()) return
        val target = index.coerceIn(0, state.queue.lastIndex)
        val track = state.queue[target]
        val previousTrack = state.currentTrack

        scope.launch {
            if (previousTrack?.source == SourceType.SPOTIFY && track.source != SourceType.SPOTIFY) {
                spotifyPlaybackController.pause()
            } else if (previousTrack != null && previousTrack.source != SourceType.SPOTIFY && track.source == SourceType.SPOTIFY) {
                media3PlaybackController.pause()
                media3PlaybackController.setQueue(emptyList(), 0, false)
                var waited = 0L
                while (waited < 600L) {
                    val snap = media3PlaybackController.snapshot()
                    if (snap.state != PlaybackStateType.PLAYING) break
                    delay(50)
                    waited += 50
                }
            }

            val success = if (track.source == SourceType.SPOTIFY) {
                media3PlaybackController.setQueue(emptyList(), 0, false)
                var started = spotifyPlaybackController.startQueue(listOf(track.id), 0)
                if (!started) {
                    delay(350)
                    started = spotifyPlaybackController.startQueue(listOf(track.id), 0)
                }
                if (started) {
                    spotifyPlaybackController.setVolume(mutableStatus.value.volume)
                }
                started
            } else {
                val mappedIndex = media3PlaybackController.setQueue(listOf(track), 0, true)
                val normalizedVolume = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    spotifyPlaybackController.normalizeVolumeForSource(
                        mutableStatus.value.volume,
                        track.source
                    )
                }
                media3PlaybackController.setVolume(normalizedVolume)
                mappedIndex >= 0
            }

            mutableStatus.value = mutableStatus.value.copy(
                currentTrack = track,
                state = if (success) PlaybackStateType.PLAYING else PlaybackStateType.ERROR,
                position = 0,
                duration = track.durationMs ?: 0,
                errorMessage = if (success) null else spotifyErrorOrDefault("Failed to start track")
            )
            persistStateAsync()
        }
    }

    private fun playMixedTrackById(trackId: String) {
        val state = mutableStatus.value
        val target = findQueueIndex(trackId)
        if (target < 0) return
        playMixedTrackAtIndex(target)
    }

}
// Alias android.util.Log usages to CompatLog so we don't have to replace every
// call site in one pass. Files compiled in JVM unit tests will use CompatLog.
typealias Log = com.anyplayer.android.core.log.CompatLog
