package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.AudioNormalizationSettings
import com.anyplayer.android.feature.djfiller.DjFillerScheduler
import com.anyplayer.android.feature.djfiller.DjInterstitialPlayer
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
    private val json: Json,
    private val djFillerScheduler: DjFillerScheduler,
    private val djInterstitialPlayer: DjInterstitialPlayer
) {
    companion object {
        private const val TAG = "PlaybackQueueManager"
    }

    private val maxPersistedQueueTracks = 5000
    private val context = PlaybackEngineContext(spotifyPlaybackController)
    private var isRestoring = false
    private var persistTickCounter = 0

    // persistStateAsync() launches on Dispatchers.IO, so back-to-back calls can run
    // concurrently on different IO-pool threads with no lock between them - @Volatile
    // guarantees each sees the other's latest write instead of a stale cached value.
    @Volatile
    private var lastPersistFingerprint = 0L

    private val localOps = LocalPlaybackOps(
        media3PlaybackController = media3PlaybackController,
        context = context,
        applyNormalizedMedia3Volume = ::applyNormalizedMedia3Volume,
        triggerPrefetch = ::triggerPrefetch,
        persistStateAsync = ::persistStateAsync,
        djInterstitialPlayer = djInterstitialPlayer
    )
    private val spotifyOps = SpotifyPlaybackOps(
        media3PlaybackController = media3PlaybackController,
        spotifyPlaybackController = spotifyPlaybackController,
        audioCacheManager = audioCacheManager,
        context = context,
        isNearTrackEnd = ::isNearTrackEnd,
        persistStateAsync = ::persistStateAsync,
        djFillerScheduler = djFillerScheduler,
        djInterstitialPlayer = djInterstitialPlayer
    )
    private val mixedOps = MixedPlaybackOps(
        media3PlaybackController = media3PlaybackController,
        spotifyPlaybackController = spotifyPlaybackController,
        audioCacheManager = audioCacheManager,
        spotifyOps = spotifyOps,
        context = context,
        isNearTrackEnd = ::isNearTrackEnd,
        applyNormalizedMedia3Volume = ::applyNormalizedMedia3Volume,
        persistStateAsync = ::persistStateAsync,
        djFillerScheduler = djFillerScheduler,
        djInterstitialPlayer = djInterstitialPlayer
    )

    init {
        // Local/provider-streamed mode has no per-transition hook for the scheduler to
        // pull a ready filler from, so it needs to know up front when it must instead push
        // a completed filler straight into the live ExoPlayer timeline (see
        // DjFillerScheduler.configureLocalModeProvider).
        djFillerScheduler.configureLocalModeProvider { !context.spotifyMode && !context.mixedMode }
    }

    /**
     * Gate that blocks [restorePersistedState] until the provider auth layer
     * is initialised. The
     * service layer completes this after [ProviderAuthRepository.restoreAll].
     */
    private val providerRestoreGate = CompletableDeferred<Unit>()

    private val mutableAudioNormalizationSettings = MutableStateFlow(AudioNormalizationSettings())
    private val mutableAiDjEnabled = MutableStateFlow(false)

    val status: StateFlow<PlaybackStatus> = context.mutableStatus.asStateFlow()
    val audioNormalizationSettings: StateFlow<AudioNormalizationSettings> =
        mutableAudioNormalizationSettings.asStateFlow()
    val aiDjEnabled: StateFlow<Boolean> = mutableAiDjEnabled.asStateFlow()

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
                djFillerScheduler.onStatusUpdated(context.mutableStatus.value)
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

            if (context.spotifyMode || currentTrackSource == SourceType.SPOTIFY) {
                spotifyPlaybackController.setVolume(requestedVolume)
            } else {
                applyNormalizedMedia3Volume(requestedVolume, currentTrackSource ?: SourceType.ALL)
            }
            persistStateAsync()
        }
    }

    /** Enabling never triggers a model download on its own - that only happens when the
     *  user explicitly taps "Download" in Settings (see DjModelManager.startDownload). */
    fun setAiDjEnabled(enabled: Boolean) {
        mutableAiDjEnabled.value = enabled
        djFillerScheduler.setEnabled(enabled)
        persistStateAsync()
    }

    /** Single owner for the "does this queue contain Spotify tracks, non-Spotify tracks, or
     *  both" check that [setQueue] and [addNextInQueue] each need to derive spotifyMode/
     *  mixedMode - was duplicated verbatim in both methods. */
    private fun classifySourceMix(tracks: List<Track>): Pair<Boolean, Boolean> {
        val hasSpotify = tracks.any { it.source == SourceType.SPOTIFY }
        val hasNonSpotify = tracks.any { it.source != SourceType.SPOTIFY }
        return hasSpotify to hasNonSpotify
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, autoPlay: Boolean = true) {
        CompatLog.i(
            TAG,
            "setQueue size=${tracks.size} startIndex=$startIndex autoPlay=$autoPlay spotifyMode=${tracks.isNotEmpty() && tracks.all { it.source == SourceType.SPOTIFY }} mixedMode=${tracks.any { it.source == SourceType.SPOTIFY } && tracks.any { it.source != SourceType.SPOTIFY }}"
        )
        val (hasSpotify, hasNonSpotify) = classifySourceMix(tracks)
        context.spotifyMode = tracks.isNotEmpty() && !hasNonSpotify
        context.mixedMode = hasSpotify && hasNonSpotify
        spotifyPlaybackController.setSpotifyPollingActive(context.spotifyMode || context.mixedMode)

        if (tracks.isEmpty()) {
            clearQueue()
            return
        }

        val index = QueueOrderingUtils.resolveInitialStartIndex(
            tracks = tracks,
            requestedStartIndex = startIndex,
            autoPlay = autoPlay,
            shuffleEnabled = context.mutableStatus.value.shuffle,
            spotifyMode = context.spotifyMode
        )

        when {
            context.spotifyMode -> spotifyOps.setQueue(tracks, index, autoPlay)
            context.mixedMode -> mixedOps.setQueue(tracks, index, autoPlay)
            else -> localOps.setQueue(tracks, index, autoPlay)
        }
    }

    /** Mode-agnostic empty-queue reset - not per-mode state-machine logic, so it stays
     *  here rather than in one of the Ops classes. */
    private fun clearQueue() {
        audioCacheManager.cancelPrefetch()
        context.lastPrefetchedForTrackId = null
        context.recovery.resetSpotifyAutoAdvanceState()
        context.recovery.resetSpotifyRecoveryState()
        context.recovery.resetSpotifyMidTrackStallState()
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
    }

    fun addNextInQueue(track: Track) {
        val state = context.mutableStatus.value
        if (state.queue.isEmpty()) {
            setQueue(listOf(track), startIndex = 0, autoPlay = false)
            return
        }
        // spotifyCurrentQueueIndex is a Spotify-only cursor (see QueueIndexCache doc) - using
        // it to disambiguate duplicate track ids in a local/mixed queue would bias toward a
        // stale index left over from a prior Spotify session. Only trust it in Spotify mode;
        // otherwise resolve the current track's exact position in this queue directly.
        val currentIndex = if (context.spotifyMode) {
            context.queueIndexCache.currentQueueIndex(state)
        } else {
            state.currentTrack?.id?.let { context.queueIndexCache.findQueueIndex(it) }?.takeIf { it >= 0 } ?: 0
        }
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
        val (hasSpotify, hasNonSpotify) = classifySourceMix(updatedQueue)
        context.spotifyMode = updatedQueue.isNotEmpty() && !hasNonSpotify
        context.mixedMode = hasSpotify && hasNonSpotify
        spotifyPlaybackController.setSpotifyPollingActive(context.spotifyMode || context.mixedMode)
        context.playableQueueIndices = if (!context.spotifyMode && !context.mixedMode) {
            updatedQueue.mapIndexedNotNull { queueIndex, queuedTrack ->
                queueIndex.takeIf { isLocallyPlayableTrack(queuedTrack) }
            }
        } else {
            emptyList()
        }
        context.queueIndexCache.rebuildQueueCaches(updatedQueue)
        context.mutableStatus.value = state.copy(
            queue = updatedQueue,
            orderedQueue = updatedOrdered
        )

        if (context.spotifyMode) {
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
        } else if (!context.mixedMode) {
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
            "playFromIndex index=$index queueSize=${state.queue.size} spotifyMode=${context.spotifyMode} mixedMode=${context.mixedMode} current=${state.currentTrack?.id}"
        )
        if (state.queue.isEmpty()) return
        val target = index.coerceIn(0, state.queue.lastIndex)

        if (context.spotifyMode) {
            spotifyOps.playFromIndex(state, target)
            return
        }
        if (context.mixedMode) {
            mixedOps.playMixedTrackAtIndex(target)
            return
        }
        localOps.playFromIndex(state, target)
    }

    fun togglePlayPause() {
        val initial = context.mutableStatus.value
        CompatLog.i(
            TAG,
            "togglePlayPause state=${initial.state} spotifyMode=${context.spotifyMode} mixedMode=${context.mixedMode} current=${initial.currentTrack?.id}"
        )
        if (context.mixedMode) {
            mixedOps.togglePlayPause()
            return
        }
        if (context.spotifyMode) {
            spotifyOps.togglePlayPause()
            return
        }
        localOps.togglePlayPause()
    }

    fun play() {
        val initial = context.mutableStatus.value
        CompatLog.i(
            TAG,
            "play state=${initial.state} spotifyMode=${context.spotifyMode} mixedMode=${context.mixedMode} current=${initial.currentTrack?.id}"
        )
        if (context.mixedMode) {
            mixedOps.play()
            return
        }
        if (context.spotifyMode) {
            spotifyOps.play()
            return
        }
        localOps.play()
    }

    fun pause() {
        val initial = context.mutableStatus.value
        CompatLog.i(
            TAG,
            "pause state=${initial.state} spotifyMode=${context.spotifyMode} mixedMode=${context.mixedMode} current=${initial.currentTrack?.id}"
        )
        if (context.mixedMode) {
            mixedOps.pause()
            return
        }
        if (context.spotifyMode) {
            spotifyOps.pause()
            return
        }
        localOps.pause()
    }

    fun seekTo(positionMs: Long) {
        if (context.mixedMode) {
            mixedOps.seekTo(positionMs)
            return
        }
        if (context.spotifyMode) {
            spotifyOps.seekTo(positionMs)
            return
        }
        localOps.seekTo(positionMs)
    }

    fun setVolume(volume: Int) {
        val requestedVolume = volume.coerceIn(0, 100)

        if (context.mixedMode) {
            mixedOps.setVolume(requestedVolume)
            return
        }
        if (context.spotifyMode) {
            spotifyOps.setVolume(requestedVolume)
            return
        }
        localOps.setVolume(requestedVolume)
    }

    fun setShuffle(enabled: Boolean) {
        if (context.mixedMode) {
            mixedOps.setShuffle(enabled)
            return
        }
        if (context.spotifyMode) {
            spotifyOps.setShuffle(enabled)
            return
        }
        localOps.setShuffle(enabled)
    }

    fun setRepeatMode(mode: RepeatMode) {
        if (context.spotifyMode) {
            spotifyOps.setRepeatMode(mode)
            return
        }
        localOps.setRepeatMode(mode)
    }

    fun next() {
        val state = context.mutableStatus.value
        CompatLog.i(
            TAG,
            "next state=${state.state} spotifyMode=${context.spotifyMode} mixedMode=${context.mixedMode} current=${state.currentTrack?.id}"
        )
        if (djInterstitialPlayer.isPlayingInterstitial) {
            media3PlaybackController.skipInterstitial()
            return
        }
        if (context.mixedMode) {
            mixedOps.next(state)
            return
        }
        if (context.spotifyMode) {
            spotifyOps.next(state)
            return
        }
        localOps.next()
    }

    fun previous() {
        val state = context.mutableStatus.value
        CompatLog.i(
            TAG,
            "previous state=${state.state} spotifyMode=${context.spotifyMode} mixedMode=${context.mixedMode} current=${state.currentTrack?.id}"
        )
        if (djInterstitialPlayer.isPlayingInterstitial) {
            media3PlaybackController.skipInterstitial()
            return
        }
        if (context.mixedMode) {
            mixedOps.previous(state)
            return
        }
        if (context.spotifyMode) {
            spotifyOps.previous(state)
            return
        }
        localOps.previous(state)
    }

    private suspend fun syncFromPlaybackEngine() {
        if (isRestoring) return
        if (context.mixedMode) {
            mixedOps.sync()
            return
        }
        if (context.spotifyMode) {
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
        setAiDjEnabled(persisted.aiDjEnabled)

        // Set shuffle flag BEFORE setQueue so buildOrderedQueue uses the
        // persisted value instead of the default (false). This prevents
        // generating a new random shuffle order on every restore.
        context.mutableStatus.value = context.mutableStatus.value.copy(shuffle = persisted.shuffle)

        val startIndex = persisted.currentQueueIndex?.coerceIn(0, persisted.queue.lastIndex) ?: 0
        val shouldAutoPlay = false

        setQueue(persisted.queue, startIndex = startIndex, autoPlay = shouldAutoPlay)

        // Restore the persisted orderedQueue if available and valid, so the
        // shuffled order is preserved across restarts instead of re-randomizing.
        // Only trust it when its track-id set matches the persisted queue - a stale
        // or corrupted persisted orderedQueue from an older app version must not be
        // adopted anywhere, including the Spotify Connect restore path below.
        val persistedOrdered = persisted.orderedQueue
        val persistedOrderedIsValid = persisted.shuffle && !persistedOrdered.isNullOrEmpty() &&
            persistedOrdered.map { it.id }.toSet() == persisted.queue.map { it.id }.toSet()
        if (persistedOrderedIsValid) {
            context.mutableStatus.value = context.mutableStatus.value.copy(orderedQueue = persistedOrdered!!)
        }

        setVolume(persisted.volume)
        setRepeatMode(persisted.repeatMode)
        if (persisted.positionMs > 0 && !context.spotifyMode) {
            seekTo(persisted.positionMs)
        }

        if (context.spotifyMode && persisted.queue.isNotEmpty()) {
            val restoreTrackIds: List<String>
            val restoreStartIndex: Int
            if (persistedOrderedIsValid) {
                restoreTrackIds = persistedOrdered!!.map { it.id }
                val expectedTrackId = persisted.queue.getOrNull(startIndex)?.id
                restoreStartIndex = if (expectedTrackId != null) {
                    restoreTrackIds.indexOfFirst { normalizeSpotifyTrackId(it) == normalizeSpotifyTrackId(expectedTrackId) }
                        .takeIf { it >= 0 } ?: 0
                } else 0
            } else {
                restoreTrackIds = context.queueIndexCache.cachedQueueTrackIds
                restoreStartIndex = startIndex
            }

            spotifyOps.restoreQueueAndPause(restoreTrackIds, restoreStartIndex, persisted.positionMs)
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
        fp = fp * 31 + (state.orderedQueue.firstOrNull()?.id?.hashCode()?.toLong() ?: 0L)
        fp = fp * 31 + (state.orderedQueue.lastOrNull()?.id?.hashCode()?.toLong() ?: 0L)
        fp = fp * 31 + state.orderedQueue.size
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
            // orderedQueue is only meaningful alongside a persisted queue - restore bails
            // out when persisted.queue is empty, so writing it while persistedQueue was
            // truncated (queue.size > maxPersistedQueueTracks) would defeat the size cap
            // above for zero benefit.
            orderedQueue = if (state.shuffle && persistedQueue.isNotEmpty() && state.orderedQueue.isNotEmpty()) {
                state.orderedQueue
            } else {
                null
            },
            currentQueueIndex = persistedCurrentQueueIndex,
            positionMs = state.position,
            shuffle = state.shuffle,
            repeatMode = state.repeatMode,
            volume = state.volume,
            audioNormalizationEnabled = audioNorm.enabled,
            audioNormalizationStrictMode = audioNorm.strictMode,
            aiDjEnabled = mutableAiDjEnabled.value,
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
