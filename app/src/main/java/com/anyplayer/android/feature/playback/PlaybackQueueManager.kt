package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.AudioNormalizationSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackQueueManager @Inject constructor(
    private val media3PlaybackController: Media3PlaybackController,
    private val spotifyPlaybackController: SpotifyPlaybackController,
    private val playbackStateStore: PlaybackStateStore,
    private val audioCacheManager: AudioCacheManager,
    private val json: Json
) {
    companion object {
        private const val TAG = "PlaybackQueueManager"
    }

    private val maxPersistedQueueTracks = 5000
    private val context = PlaybackEngineContext(spotifyPlaybackController)
    private var isRestoring = false
    private var spotifyMode = false
    private var mixedMode = false
    private var persistTickCounter = 0
    private var lastPersistFingerprint = 0L

    private val localOps = LocalPlaybackOps(
        media3PlaybackController = media3PlaybackController,
        audioCacheManager = audioCacheManager,
        context = context,
        applyNormalizedMedia3Volume = ::applyNormalizedMedia3Volume,
        triggerPrefetch = ::triggerPrefetch,
        persistStateAsync = ::persistStateAsync
    )
    private val spotifyOps = SpotifyPlaybackOps(
        spotifyPlaybackController = spotifyPlaybackController,
        context = context,
        isSpotifyMode = { spotifyMode },
        isNearTrackEnd = ::isNearTrackEnd,
        persistStateAsync = ::persistStateAsync
    )

    /**
     * Gate that blocks [restorePersistedState] until the provider auth layer
     * is initialised. The
     * service layer completes this after [ProviderAuthRepository.restoreAll].
     */
    private val providerRestoreGate = CompletableDeferred<Unit>()

    private val mutableAudioNormalizationSettings = MutableStateFlow(AudioNormalizationSettings())

    val status: StateFlow<PlaybackStatus> = context.mutableStatus.asStateFlow()
    val audioNormalizationSettings: StateFlow<AudioNormalizationSettings> =
        mutableAudioNormalizationSettings.asStateFlow()

    suspend fun restorePersistedStateNowIfNeeded() {
        if (context.mutableStatus.value.queue.isNotEmpty() || isRestoring) {
            ensureWarmSessionState()
            return
        }
        restorePersistedState()
        ensureWarmSessionState()
    }

    fun ensureWarmSessionState() {
        val state = context.mutableStatus.value
        if (state.queue.isEmpty() || state.currentTrack != null) {
            return
        }
        val fallbackTrack = state.orderedQueue.firstOrNull() ?: state.queue.firstOrNull() ?: return
        context.mutableStatus.value = state.copy(
            currentTrack = fallbackTrack,
            state = if (state.state == PlaybackStateType.IDLE) PlaybackStateType.PAUSED else state.state,
            duration = fallbackTrack.durationMs ?: state.duration
        )
    }

    fun signalProviderRestoreComplete() {
        providerRestoreGate.complete(Unit)
    }

    init {
        CompatLog.i(TAG, "PlaybackQueueManager initialized")
        context.scope.launch {
            mutableAudioNormalizationSettings.value =
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    spotifyPlaybackController.getAudioNormalizationSettings()
                }
            // Wait for the service layer to finish restoring provider state
            // before restoring any Spotify-backed persisted queue.
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

    // Normalization must happen off the calling coroutine's dispatcher (it can hit disk/CPU
    // work), so every media3 volume-set call site normalizes on IO before applying it.
    private suspend fun applyNormalizedMedia3Volume(volume: Int, source: SourceType) {
        val outputVolume = kotlinx.coroutines.withContext(Dispatchers.IO) {
            spotifyPlaybackController.normalizeVolumeForSource(volume, source)
        }
        media3PlaybackController.setVolume(outputVolume)
    }

    fun setAudioNormalization(enabled: Boolean, strictMode: Boolean) {
        val next = AudioNormalizationSettings(enabled = enabled, strictMode = strictMode)
        mutableAudioNormalizationSettings.value = next

        context.scope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                spotifyPlaybackController.setAudioNormalizationSettings(enabled, strictMode)
            }

            val requestedVolume = context.mutableStatus.value.volume.coerceIn(0, 100)
            val currentTrackSource = context.mutableStatus.value.currentTrack?.source

            if (spotifyMode || currentTrackSource == SourceType.SPOTIFY) {
                spotifyPlaybackController.setVolume(requestedVolume)
            } else {
                applyNormalizedMedia3Volume(requestedVolume, currentTrackSource ?: SourceType.ALL)
            }
            persistStateAsync()
        }
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, autoPlay: Boolean = true) {
        CompatLog.i(
            TAG,
            "setQueue size=${tracks.size} startIndex=$startIndex autoPlay=$autoPlay spotifyMode=${tracks.isNotEmpty() && tracks.all { it.source == SourceType.SPOTIFY }} mixedMode=${tracks.any { it.source == SourceType.SPOTIFY } && tracks.any { it.source != SourceType.SPOTIFY }}"
        )
        val hasSpotify = tracks.any { it.source == SourceType.SPOTIFY }
        val hasNonSpotify = tracks.any { it.source != SourceType.SPOTIFY }
        spotifyMode = tracks.isNotEmpty() && tracks.all { it.source == SourceType.SPOTIFY }
        mixedMode = hasSpotify && hasNonSpotify

        if (tracks.isEmpty()) {
            audioCacheManager.cancelPrefetch()
            context.lastPrefetchedForTrackId = null
            context.recovery.resetSpotifyAutoAdvanceState()
            context.recovery.resetSpotifyRecoveryState()
            context.recovery.resetMixedMediaEndStallState()
            context.playableQueueIndices = emptyList()
            context.spotifyQueueRequiresReload = false
            context.queueIndexCache.rebuildQueueCaches(emptyList())
            media3PlaybackController.setQueue(emptyList(), 0, false)
            context.mutableStatus.value = context.mutableStatus.value.copy(
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

        val index = QueueOrderingUtils.resolveInitialStartIndex(
            tracks = tracks,
            requestedStartIndex = startIndex,
            autoPlay = autoPlay,
            shuffleEnabled = context.mutableStatus.value.shuffle,
            spotifyMode = spotifyMode
        )

        if (spotifyMode) {
            audioCacheManager.cancelPrefetch()
            context.lastPrefetchedForTrackId = null
            context.recovery.resetSpotifyAutoAdvanceState()
            context.recovery.resetSpotifyRecoveryState()
            context.recovery.resetMixedMediaEndStallState()
            context.queueIndexCache.rebuildQueueCaches(tracks)
            context.queueIndexCache.spotifyCurrentQueueIndex = index
            media3PlaybackController.setQueue(emptyList(), 0, false)
            context.mutableStatus.value = context.mutableStatus.value.copy(
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
            context.spotifyQueueRequiresReload = !autoPlay
            if (autoPlay) {
                context.scope.launch {
                    var started = spotifyPlaybackController.startQueue(context.queueIndexCache.cachedQueueTrackIds, index)
                    if (!started) {
                        delay(350)
                        started = spotifyPlaybackController.startQueue(context.queueIndexCache.cachedQueueTrackIds, index)
                    }
                    if (started) {
                        context.spotifyQueueRequiresReload = false
                        spotifyPlaybackController.setVolume(context.mutableStatus.value.volume)
                    } else {
                        context.spotifyQueueRequiresReload = true
                        context.mutableStatus.value = context.mutableStatus.value.copy(
                            state = PlaybackStateType.ERROR,
                            errorMessage = spotifyOps.spotifyErrorOrDefault("Spotify failed to start playback")
                        )
                    }
                }
            }
            persistStateAsync()
            return
        }

        if (mixedMode) {
            context.recovery.resetSpotifyAutoAdvanceState()
            context.recovery.resetSpotifyRecoveryState()
            context.recovery.resetMixedMediaEndStallState()
            context.queueIndexCache.rebuildQueueCaches(tracks)
            media3PlaybackController.setQueue(emptyList(), 0, false)
            val clampedIndex = index.coerceIn(0, tracks.lastIndex)
            val selectedTrack = tracks[clampedIndex]
            if (!autoPlay && selectedTrack.source != SourceType.SPOTIFY) {
                media3PlaybackController.setQueue(listOf(selectedTrack), 0, false)
            }
            context.mutableStatus.value = context.mutableStatus.value.copy(
                queue = tracks,
                orderedQueue = QueueOrderingUtils.buildOrderedQueue(
                    queue = tracks,
                    currentTrackId = selectedTrack.id,
                    shuffleEnabled = context.mutableStatus.value.shuffle
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

        context.spotifyQueueRequiresReload = false
        context.playableQueueIndices = tracks.mapIndexedNotNull { queueIndex, track ->
            queueIndex.takeIf { !track.url.isNullOrBlank() && track.source != SourceType.SPOTIFY }
        }
        context.queueIndexCache.rebuildQueueCaches(tracks)
        context.recovery.resetSpotifyAutoAdvanceState()
        context.recovery.resetSpotifyRecoveryState()
        context.recovery.resetMixedMediaEndStallState()
        val mappedIndex = media3PlaybackController.setQueue(tracks, index, autoPlay)
        context.scope.launch {
            applyNormalizedMedia3Volume(context.mutableStatus.value.volume, SourceType.ALL)
        }
        if (mappedIndex < 0) {
            context.mutableStatus.value = context.mutableStatus.value.copy(
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
        val selectedTrack = tracks.getOrNull(context.playableQueueIndices.getOrNull(mappedIndex) ?: index) ?: tracks[index]
        context.mutableStatus.value = context.mutableStatus.value.copy(
            queue = tracks,
            orderedQueue = tracks,
            currentTrack = selectedTrack,
            state = if (autoPlay) PlaybackStateType.PLAYING else PlaybackStateType.PAUSED,
            position = 0,
            duration = selectedTrack.durationMs ?: 0
        )
        if (autoPlay && !context.mutableStatus.value.shuffle) {
            context.lastPrefetchedForTrackId = selectedTrack.id
            triggerPrefetch()
        }
        persistStateAsync()
    }

    fun addNextInQueue(track: Track) {
        val state = context.mutableStatus.value
        if (state.queue.isEmpty()) {
            setQueue(listOf(track), startIndex = 0, autoPlay = false)
            return
        }
        val currentIndex = context.queueIndexCache.currentQueueIndex(state)
        val insertionIndex = (currentIndex + 1 + context.addToQueueInsertionOffset)
            .coerceAtMost(state.queue.size)

        val updatedQueue = state.queue.toMutableList().apply { add(insertionIndex, track) }
        val updatedOrdered = if (state.orderedQueue.isNotEmpty()) {
            val orderedCurrentIndex = state.currentTrack
                ?.let { ct -> state.orderedQueue.indexOfFirst { it.id == ct.id } }
                ?.takeIf { it >= 0 }
                ?: 0
            val orderedInsertionIndex = (orderedCurrentIndex + 1 + context.addToQueueInsertionOffset)
                .coerceAtMost(state.orderedQueue.size)
            state.orderedQueue.toMutableList().apply { add(orderedInsertionIndex, track) }
        } else {
            updatedQueue
        }

        context.addToQueueInsertionOffset++
        val hasSpotify = updatedQueue.any { it.source == SourceType.SPOTIFY }
        val hasNonSpotify = updatedQueue.any { it.source != SourceType.SPOTIFY }
        spotifyMode = updatedQueue.isNotEmpty() && updatedQueue.all { it.source == SourceType.SPOTIFY }
        mixedMode = hasSpotify && hasNonSpotify
        context.playableQueueIndices = if (!spotifyMode && !mixedMode) {
            updatedQueue.mapIndexedNotNull { queueIndex, queuedTrack ->
                queueIndex.takeIf { !queuedTrack.url.isNullOrBlank() && queuedTrack.source != SourceType.SPOTIFY }
            }
        } else {
            emptyList()
        }
        context.queueIndexCache.rebuildQueueCaches(updatedQueue)
        context.mutableStatus.value = state.copy(
            queue = updatedQueue,
            orderedQueue = updatedOrdered
        )

        if (spotifyMode) {
            val currentTrackId = state.currentTrack?.id
            val spotifyQueue = if (state.shuffle && updatedOrdered.isNotEmpty()) updatedOrdered else updatedQueue
            val spotifyTrackIds = spotifyQueue.map { it.id }
            val currentSpotifyIndex = currentTrackId
                ?.let { trackId -> spotifyQueue.indexOfFirst { trackIdsMatch(it.id, trackId) } }
                ?.takeIf { it >= 0 }
                ?: context.queueIndexCache.currentQueueIndex(state).coerceIn(0, spotifyQueue.lastIndex)
            context.scope.launch {
                val snapshot = spotifyPlaybackController.snapshot()
                val currentPositionMs = snapshot?.progressMs ?: state.position
                val wasPlaying = snapshot?.isPlaying ?: (state.state == PlaybackStateType.PLAYING)
                if (wasPlaying) {
                    val started = spotifyPlaybackController.startQueue(spotifyTrackIds, currentSpotifyIndex)
                    if (started) {
                        context.spotifyQueueRequiresReload = false
                        if (currentPositionMs > 0) {
                            spotifyPlaybackController.seekTo(currentPositionMs)
                        }
                        spotifyPlaybackController.setVolume(context.mutableStatus.value.volume)
                    } else {
                        context.spotifyQueueRequiresReload = true
                    }
                } else {
                    context.spotifyQueueRequiresReload = true
                }
            }
        } else if (!mixedMode) {
            val currentId = state.currentTrack?.id
            val queueIndex = currentId?.let { context.queueIndexCache.findQueueIndex(it) }?.takeIf { it >= 0 } ?: 0
            val mediaIndex = context.playableQueueIndices.indexOf(queueIndex).takeIf { it >= 0 } ?: 0
            media3PlaybackController.setQueue(
                updatedQueue,
                mediaIndex,
                state.state == PlaybackStateType.PLAYING
            )
        }
        persistStateAsync()
    }

    fun playFromIndex(index: Int) {
        val state = context.mutableStatus.value
        CompatLog.i(
            TAG,
            "playFromIndex index=$index queueSize=${state.queue.size} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${state.currentTrack?.id}"
        )
        if (state.queue.isEmpty()) return
        val target = index.coerceIn(0, state.queue.lastIndex)

        if (spotifyMode) {
            spotifyOps.playFromIndex(state, target)
            return
        }
        if (mixedMode) {
            playMixedTrackAtIndex(target)
            return
        }
        localOps.playFromIndex(state, target)
    }

    fun togglePlayPause() {
        val initial = context.mutableStatus.value
        CompatLog.i(
            TAG,
            "togglePlayPause state=${initial.state} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${initial.currentTrack?.id}"
        )
        if (mixedMode) {
            togglePlayPauseMixed()
            return
        }
        if (spotifyMode) {
            spotifyOps.togglePlayPause()
            return
        }
        localOps.togglePlayPause()
    }

    private fun togglePlayPauseMixed() {
        val state = context.mutableStatus.value
        val currentTrack = state.currentTrack
        if (currentTrack == null) return
        context.scope.launch {
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
            context.mutableStatus.value = state.copy(
                state = if (!success) PlaybackStateType.ERROR else if (isPlaying) PlaybackStateType.PAUSED else PlaybackStateType.PLAYING,
                errorMessage = if (!success) spotifyOps.spotifyErrorOrDefault("Playback command failed") else null
            )
            persistStateAsync()
        }
    }

    fun play() {
        val initial = context.mutableStatus.value
        CompatLog.i(
            TAG,
            "play state=${initial.state} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${initial.currentTrack?.id}"
        )
        if (mixedMode) {
            playMixed()
            return
        }
        if (spotifyMode) {
            spotifyOps.play()
            return
        }
        localOps.play()
    }

    private fun playMixed() {
        val state = context.mutableStatus.value
        val currentTrack = state.currentTrack ?: return
        context.scope.launch {
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
            context.mutableStatus.value = context.mutableStatus.value.copy(
                state = if (success) PlaybackStateType.PLAYING else PlaybackStateType.ERROR,
                errorMessage = if (success) null else spotifyOps.spotifyErrorOrDefault("Failed to resume playback")
            )
            persistStateAsync()
        }
    }

    fun pause() {
        val initial = context.mutableStatus.value
        CompatLog.i(
            TAG,
            "pause state=${initial.state} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${initial.currentTrack?.id}"
        )
        if (mixedMode) {
            pauseMixed()
            return
        }
        if (spotifyMode) {
            spotifyOps.pause()
            return
        }
        localOps.pause()
    }

    private fun pauseMixed() {
        val state = context.mutableStatus.value
        val currentTrack = state.currentTrack ?: return
        context.scope.launch {
            val success = if (currentTrack.source == SourceType.SPOTIFY) {
                spotifyPlaybackController.pause()
            } else {
                media3PlaybackController.pause()
                true
            }
            context.mutableStatus.value = context.mutableStatus.value.copy(
                state = if (success) PlaybackStateType.PAUSED else PlaybackStateType.ERROR,
                errorMessage = if (success) null else spotifyOps.spotifyErrorOrDefault("Failed to pause playback")
            )
            persistStateAsync()
        }
    }

    fun seekTo(positionMs: Long) {
        if (mixedMode) {
            seekToMixed(positionMs)
            return
        }
        if (spotifyMode) {
            spotifyOps.seekTo(positionMs)
            return
        }
        localOps.seekTo(positionMs)
    }

    private fun seekToMixed(positionMs: Long) {
        val state = context.mutableStatus.value
        val currentTrack = state.currentTrack ?: return
        context.scope.launch {
            if (currentTrack.source == SourceType.SPOTIFY) {
                spotifyPlaybackController.seekTo(positionMs)
            } else {
                media3PlaybackController.seekTo(positionMs)
            }
            val duration = if (state.duration <= 0) Long.MAX_VALUE else state.duration
            context.mutableStatus.value = state.copy(position = positionMs.coerceIn(0, duration))
            persistStateAsync()
        }
    }

    fun setVolume(volume: Int) {
        val requestedVolume = volume.coerceIn(0, 100)

        if (spotifyMode) {
            spotifyOps.setVolume(requestedVolume)
            return
        }
        localOps.setVolume(requestedVolume)
    }

    fun setShuffle(enabled: Boolean) {
        if (mixedMode) {
            setShuffleMixed(enabled)
            return
        }
        if (spotifyMode) {
            spotifyOps.setShuffle(enabled)
            return
        }
        localOps.setShuffle(enabled)
    }

    private fun setShuffleMixed(enabled: Boolean) {
        val state = context.mutableStatus.value
        context.mutableStatus.value = state.copy(
            shuffle = enabled,
            orderedQueue = QueueOrderingUtils.buildOrderedQueue(
                queue = state.queue,
                currentTrackId = state.currentTrack?.id,
                shuffleEnabled = enabled
            )
        )
        persistStateAsync()
    }

    fun setRepeatMode(mode: RepeatMode) {
        if (spotifyMode) {
            spotifyOps.setRepeatMode(mode)
            return
        }
        localOps.setRepeatMode(mode)
    }

    fun next() {
        val state = context.mutableStatus.value
        CompatLog.i(
            TAG,
            "next state=${state.state} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${state.currentTrack?.id}"
        )
        if (mixedMode) {
            nextMixed(state)
            return
        }
        if (spotifyMode) {
            spotifyOps.next(state)
            return
        }
        localOps.next()
    }

    private fun nextMixed(state: PlaybackStatus) {
        if (state.queue.isEmpty()) return
        val sequence = mixedPlaybackSequence(state)
        if (sequence.isEmpty()) return
        val currentId = state.currentTrack?.id
        val currentIndex = sequence.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
        val nextTrack = sequence.getOrNull(currentIndex + 1) ?: return
        playMixedTrackById(nextTrack.id)
    }

    fun previous() {
        val state = context.mutableStatus.value
        CompatLog.i(
            TAG,
            "previous state=${state.state} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${state.currentTrack?.id}"
        )
        if (mixedMode) {
            previousMixed(state)
            return
        }
        if (spotifyMode) {
            spotifyOps.previous(state)
            return
        }
        localOps.previous(state)
    }

    private fun previousMixed(state: PlaybackStatus) {
        if (state.queue.isEmpty()) return
        val sequence = mixedPlaybackSequence(state)
        if (sequence.isEmpty()) return
        val currentId = state.currentTrack?.id
        val currentIndex = sequence.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
        val prevTrack = sequence.getOrNull(currentIndex - 1) ?: return
        playMixedTrackById(prevTrack.id)
    }

    private suspend fun syncFromPlaybackEngine() {
        if (isRestoring) return
        if (mixedMode) {
            syncFromPlaybackEngineMixed()
            return
        }
        if (spotifyMode) {
            spotifyOps.sync()
            return
        }
        localOps.sync()
    }

    private suspend fun syncFromPlaybackEngineMixed() {
        val state = context.mutableStatus.value
        val currentTrack = state.currentTrack ?: return
        if (currentTrack.source == SourceType.SPOTIFY) {
                val spotifySnapshot = spotifyPlaybackController.snapshot()
                if (spotifySnapshot == null) {
                    if (state.state == PlaybackStateType.PLAYING) {
                        CompatLog.w(TAG, "Mixed-mode Spotify snapshot unavailable; attempting recovery")
                        spotifyOps.maybeRecoverSpotifyTrack(
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
                context.mutableStatus.value = state.copy(
                    state = if (spotifySnapshot.isPlaying) PlaybackStateType.PLAYING else PlaybackStateType.PAUSED,
                    position = spotifySnapshot.progressMs,
                    duration = duration,
                    volume = state.volume,
                    shuffle = state.shuffle,
                    repeatMode = spotifySnapshot.repeatMode,
                    orderedQueue = mixedPlaybackSequence(state)
                )
                if (spotifySnapshot.endOfTrackCount > context.recovery.lastAcknowledgedEndOfTrackCount) {
                    context.recovery.lastAcknowledgedEndOfTrackCount = spotifySnapshot.endOfTrackCount
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
                    // A mid-track pause here is a legitimate external state (user paused in
                    // Spotify directly, or another Connect client took over) - state was already
                    // synced to PAUSED above. Do not force playback back on.
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
                    if (context.recovery.mixedAutoAdvanceTrackId != currentTrack.id) {
                        context.recovery.mixedAutoAdvanceTrackId = currentTrack.id
                        context.recovery.resetMixedMediaEndStallState()
                        val nextTrack = sequence.getOrNull(currentIndex + 1)
                        if (nextTrack != null) {
                            playMixedTrackById(nextTrack.id)
                            return
                        }
                    }
                }

                if (state.state == PlaybackStateType.PLAYING && snapshot.state == PlaybackStateType.PLAYING && nearTrackEnd) {
                    val nowMs = System.currentTimeMillis()
                    val sameTrack = context.recovery.mixedMediaEndStallTrackId == currentTrack.id
                    val samePosition = context.recovery.mixedMediaEndStallPositionMs == snapshot.positionMs
                    if (!sameTrack || !samePosition) {
                        context.recovery.mixedMediaEndStallTrackId = currentTrack.id
                        context.recovery.mixedMediaEndStallPositionMs = snapshot.positionMs
                        context.recovery.mixedMediaEndStallSinceMs = nowMs
                    } else {
                        val stalledMs = nowMs - context.recovery.mixedMediaEndStallSinceMs
                        if (stalledMs >= 1800L && context.recovery.mixedAutoAdvanceTrackId != currentTrack.id) {
                            context.recovery.mixedAutoAdvanceTrackId = currentTrack.id
                            val nextTrack = sequence.getOrNull(currentIndex + 1)
                            if (nextTrack != null) {
                                CompatLog.w(
                                    TAG,
                                    "Detected mixed Media3 end stall; forcing next track transition after ${stalledMs}ms"
                                )
                                context.recovery.resetMixedMediaEndStallState()
                                playMixedTrackById(nextTrack.id)
                                return
                            } else {
                                context.recovery.resetMixedMediaEndStallState()
                            }
                        }
                    }
                } else {
                    context.recovery.resetMixedMediaEndStallState()
                }

                if (snapshot.state == PlaybackStateType.PLAYING) {
                    context.recovery.mixedAutoAdvanceTrackId = null
                }
                context.mutableStatus.value = state.copy(
                    state = snapshot.state,
                    position = snapshot.positionMs,
                    duration = effectiveDuration,
                    volume = state.volume,
                    shuffle = state.shuffle,
                    repeatMode = snapshot.repeatMode,
                    orderedQueue = mixedPlaybackSequence(state)
                )
                if (currentTrack.id != context.lastPrefetchedForTrackId) {
                    context.lastPrefetchedForTrackId = currentTrack.id
                    triggerPrefetch()
                }
            }
    }

    private suspend fun restorePersistedState() {
        // Guards against restorePersistedStateNowIfNeeded() and this function's own
        // init{} caller both entering before either has suspended once (both check
        // queue.isEmpty()/isRestoring before the first suspend point), which used to
        // let both proceed and call setQueue() twice with identical, stale params.
        if (isRestoring) return
        isRestoring = true

        val raw = playbackStateStore.read()
        if (raw == null) {
            isRestoring = false
            return
        }
        val persisted = runCatching {
            json.decodeFromString<PersistedPlaybackState>(raw)
        }.getOrNull()
        if (persisted == null) {
            isRestoring = false
            return
        }

        if (persisted.queue.isEmpty()) {
            isRestoring = false
            return
        }

        setAudioNormalization(
            persisted.audioNormalizationEnabled,
            persisted.audioNormalizationStrictMode
        )

        // Set shuffle flag BEFORE setQueue so buildOrderedQueue uses the
        // persisted value instead of the default (false). This prevents
        // generating a new random shuffle order on every restore.
        context.mutableStatus.value = context.mutableStatus.value.copy(shuffle = persisted.shuffle)

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
                context.mutableStatus.value = context.mutableStatus.value.copy(orderedQueue = persistedOrdered)
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
                restoreTrackIds = context.queueIndexCache.cachedQueueTrackIds
                restoreStartIndex = startIndex
            }

            val expectedTrackId = restoreTrackIds.getOrNull(restoreStartIndex)
            val started = spotifyPlaybackController.startQueue(restoreTrackIds, restoreStartIndex)
            if (started) {
                context.spotifyQueueRequiresReload = false
                spotifyPlaybackController.setVolume(context.mutableStatus.value.volume)
                if (expectedTrackId != null && persisted.positionMs > 0) {
                    var readyForSeek = false
                    for (attempt in 1..10) {
                        delay(200)
                        val snap = spotifyPlaybackController.snapshot()
                        if (snap?.currentTrackId != null &&
                            trackIdsMatch(snap.currentTrackId, expectedTrackId)
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

        // Spotify's own pause is only meaningful once something was actually
        // started (handled above, right after startQueue succeeds). On cold
        // launch nothing has been started yet, so calling the generic pause()
        // here for a Spotify current track would just fail against nothing
        // that was ever playing.
        if (!shouldAutoPlay && context.mutableStatus.value.currentTrack?.source != SourceType.SPOTIFY) {
            pause()
        }

        isRestoring = false
        persistStateAsync()
    }

    private fun persistStateAsync() {
        context.scope.launch(Dispatchers.IO + context.errorHandler) {
            persistState()
        }
    }

    private suspend fun persistState() {
        if (isRestoring) {
            return
        }
        val state = context.mutableStatus.value
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
            context.queueIndexCache.findQueueIndex(currentTrack.id).takeIf { it >= 0 }
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

    fun resetSpotifyConnectionState() = context.recovery.resetSpotifyConnectionState()

    private fun isNearTrackEnd(positionMs: Long, durationMs: Long, toleranceMs: Long): Boolean {
        if (durationMs <= 0L) return false
        val threshold = (durationMs - toleranceMs).coerceAtLeast(0L)
        return positionMs >= threshold
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

    private fun triggerPrefetch() {
        val state = context.mutableStatus.value
        if (spotifyMode || state.queue.isEmpty()) return
        val currentId = state.currentTrack?.id ?: return
        val queue = if (mixedMode) mixedPlaybackSequence(state) else state.orderedQueue
        val currentIdx = queue.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: return
        val upcoming = queue
            .drop(currentIdx + 1)
            .filter { !it.url.isNullOrBlank() && it.source != SourceType.SPOTIFY }
        audioCacheManager.prefetchTracks(upcoming)
    }

    private fun playMixedTrackAtIndex(index: Int) {
        val state = context.mutableStatus.value
        if (state.queue.isEmpty()) return
        val target = index.coerceIn(0, state.queue.lastIndex)
        val track = state.queue[target]
        val previousTrack = state.currentTrack

        context.scope.launch {
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
                    spotifyPlaybackController.setVolume(context.mutableStatus.value.volume)
                }
                started
            } else {
                val mappedIndex = media3PlaybackController.setQueue(listOf(track), 0, true)
                applyNormalizedMedia3Volume(context.mutableStatus.value.volume, track.source)
                mappedIndex >= 0
            }

            context.mutableStatus.value = context.mutableStatus.value.copy(
                currentTrack = track,
                state = if (success) PlaybackStateType.PLAYING else PlaybackStateType.ERROR,
                position = 0,
                duration = track.durationMs ?: 0,
                errorMessage = if (success) null else spotifyOps.spotifyErrorOrDefault("Failed to start track")
            )
            persistStateAsync()
        }
    }

    private fun playMixedTrackById(trackId: String) {
        val state = context.mutableStatus.value
        val target = context.queueIndexCache.findQueueIndex(trackId)
        if (target < 0) return
        playMixedTrackAtIndex(target)
    }

}
// Alias android.util.Log usages to CompatLog so we don't have to replace every
// call site in one pass. Files compiled in JVM unit tests will use CompatLog.
typealias Log = com.anyplayer.android.core.log.CompatLog
