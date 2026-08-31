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
    private val mixedOps = MixedPlaybackOps(
        media3PlaybackController = media3PlaybackController,
        spotifyPlaybackController = spotifyPlaybackController,
        audioCacheManager = audioCacheManager,
        localOps = localOps,
        spotifyOps = spotifyOps,
        context = context,
        isSpotifyMode = { spotifyMode },
        isMixedMode = { mixedMode },
        isNearTrackEnd = ::isNearTrackEnd,
        applyNormalizedMedia3Volume = ::applyNormalizedMedia3Volume,
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
                mixedOps.playMixedTrackAtIndex(clampedIndex)
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
            mixedOps.playMixedTrackAtIndex(target)
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
            mixedOps.togglePlayPause()
            return
        }
        if (spotifyMode) {
            spotifyOps.togglePlayPause()
            return
        }
        localOps.togglePlayPause()
    }

    fun play() {
        val initial = context.mutableStatus.value
        CompatLog.i(
            TAG,
            "play state=${initial.state} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${initial.currentTrack?.id}"
        )
        if (mixedMode) {
            mixedOps.play()
            return
        }
        if (spotifyMode) {
            spotifyOps.play()
            return
        }
        localOps.play()
    }

    fun pause() {
        val initial = context.mutableStatus.value
        CompatLog.i(
            TAG,
            "pause state=${initial.state} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${initial.currentTrack?.id}"
        )
        if (mixedMode) {
            mixedOps.pause()
            return
        }
        if (spotifyMode) {
            spotifyOps.pause()
            return
        }
        localOps.pause()
    }

    fun seekTo(positionMs: Long) {
        if (mixedMode) {
            mixedOps.seekTo(positionMs)
            return
        }
        if (spotifyMode) {
            spotifyOps.seekTo(positionMs)
            return
        }
        localOps.seekTo(positionMs)
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
            mixedOps.setShuffle(enabled)
            return
        }
        if (spotifyMode) {
            spotifyOps.setShuffle(enabled)
            return
        }
        localOps.setShuffle(enabled)
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
            mixedOps.next(state)
            return
        }
        if (spotifyMode) {
            spotifyOps.next(state)
            return
        }
        localOps.next()
    }

    fun previous() {
        val state = context.mutableStatus.value
        CompatLog.i(
            TAG,
            "previous state=${state.state} spotifyMode=$spotifyMode mixedMode=$mixedMode current=${state.currentTrack?.id}"
        )
        if (mixedMode) {
            mixedOps.previous(state)
            return
        }
        if (spotifyMode) {
            spotifyOps.previous(state)
            return
        }
        localOps.previous(state)
    }

    private suspend fun syncFromPlaybackEngine() {
        if (isRestoring) return
        if (mixedMode) {
            mixedOps.sync()
            return
        }
        if (spotifyMode) {
            spotifyOps.sync()
            return
        }
        localOps.sync()
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

    // Thin re-export: LocalPlaybackOps holds a `triggerPrefetch` callback bound to this
    // method (needed at LocalPlaybackOps construction time, before mixedOps exists), and
    // setQueue()'s local-fallthrough branch also calls this directly.
    private fun triggerPrefetch(): Unit = mixedOps.triggerPrefetch()

}
// Alias android.util.Log usages to CompatLog so we don't have to replace every
// call site in one pass. Files compiled in JVM unit tests will use CompatLog.
typealias Log = com.anyplayer.android.core.log.CompatLog
