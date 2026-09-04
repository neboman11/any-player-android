package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.djfiller.DjFillerScheduler
import com.anyplayer.android.feature.djfiller.DjInterstitialPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Spotify (Connect) branch of [PlaybackQueueManager]'s per-operation mode
 * dispatch, moved out verbatim (Stage 2 of
 * docs/playback-queue-manager-decomposition-plan.md). [maybeRecoverSpotifyTrack]
 * and [spotifyErrorOrDefault] are also called from mixed-mode code that still
 * lives in [PlaybackQueueManager] (not yet extracted to MixedPlaybackOps),
 * so they stay public; the rest of the Spotify-only helpers
 * (spotifyPlaybackQueue/spotifyPlaybackTrackIds/currentSpotifyQueueIndex/
 * awaitSpotifyAdvance/fallbackAdvanceSpotifyQueue/startSpotifyAtQueueIndex)
 * have no callers outside this class and stay private.
 */
internal class SpotifyPlaybackOps(
    private val media3PlaybackController: Media3PlaybackController,
    private val spotifyPlaybackController: SpotifyPlaybackController,
    private val audioCacheManager: AudioCacheManager,
    private val context: PlaybackEngineContext,
    private val isNearTrackEnd: (positionMs: Long, durationMs: Long, toleranceMs: Long) -> Boolean,
    private val persistStateAsync: () -> Unit,
    private val djFillerScheduler: DjFillerScheduler,
    private val djInterstitialPlayer: DjInterstitialPlayer
) {
    companion object {
        private const val TAG = "SpotifyPlaybackOps"

        // Any wait for a fresh SpotifyConnectBridge snapshot must exceed its background
        // poll cadence with margin, since the wait can start at any point within that
        // poll's own cycle - not just right after a poll fires.
        private val SPOTIFY_SNAPSHOT_AWAIT_TIMEOUT_MS = SpotifyConnectBridge.POLL_INTERVAL_MS + 1000L

        // A mid-track pause reported by a single poll is ambiguous - it could be a
        // legitimate pause from another Connect client, or the poll simply landing
        // right after a track started before the bridge caught up. Requiring the same
        // (track, position) to persist across several poll cycles before treating it
        // as a genuine stuck session filters out that single-poll race while still
        // recovering from a real stall within a bounded time.
        private val MID_TRACK_STALL_THRESHOLD_MS = SpotifyConnectBridge.POLL_INTERVAL_MS * 3
    }

    private fun spotifyPlaybackQueue(state: PlaybackStatus): List<Track> =
        if (state.shuffle && state.orderedQueue.isNotEmpty()) state.orderedQueue else state.queue

    private fun spotifyPlaybackTrackIds(state: PlaybackStatus): List<String> =
        spotifyPlaybackQueue(state).map { it.id }

    private suspend fun currentSpotifyQueueIndex(state: PlaybackStatus): Int =
        context.queueIndexCache.resolveSpotifyQueueIndex(spotifyPlaybackQueue(state), state.currentTrack?.id)

    fun spotifyErrorOrDefault(defaultMessage: String): String =
        spotifyPlaybackController.lastError?.takeIf { it.isNotBlank() } ?: defaultMessage

    /** Spotify-mode branch of [PlaybackQueueManager.setQueue]: [tracks] is entirely
     *  Spotify-sourced, per [PlaybackEngineContext.spotifyMode] having already been
     *  computed by the caller. [startIndex] is pre-resolved (shuffle-aware). */
    fun setQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean) {
        audioCacheManager.cancelPrefetch()
        context.lastPrefetchedForTrackId = null
        context.recovery.resetSpotifyAutoAdvanceState()
        context.recovery.resetSpotifyRecoveryState()
        context.recovery.resetSpotifyMidTrackStallState()
        context.recovery.resetMixedMediaEndStallState()
        context.queueIndexCache.rebuildQueueCaches(tracks)
        context.queueIndexCache.spotifyCurrentQueueIndex = startIndex
        media3PlaybackController.setQueue(emptyList(), 0, false)
        context.mutableStatus.value = context.mutableStatus.value.copy(
            queue = tracks,
            orderedQueue = tracks,
            currentTrack = tracks[startIndex],
            state = if (autoPlay) PlaybackStateType.PLAYING else PlaybackStateType.PAUSED,
            position = 0,
            duration = tracks[startIndex].durationMs ?: 0
        )
        // Only start the Spotify queue when autoPlay is true. startQueue() begins playback
        // immediately; when autoPlay is false we defer until play()/togglePlayPause() is called,
        // which falls back to startQueue() when resume fails.
        context.spotifyQueueRequiresReload = !autoPlay
        if (autoPlay) {
            context.scope.launch {
                var started = spotifyPlaybackController.startQueue(context.queueIndexCache.cachedQueueTrackIds, startIndex)
                if (!started) {
                    delay(350)
                    started = spotifyPlaybackController.startQueue(context.queueIndexCache.cachedQueueTrackIds, startIndex)
                }
                if (started) {
                    context.spotifyQueueRequiresReload = false
                    spotifyPlaybackController.setVolume(context.mutableStatus.value.volume)
                } else {
                    context.spotifyQueueRequiresReload = true
                    context.mutableStatus.value = context.mutableStatus.value.copy(
                        state = PlaybackStateType.ERROR,
                        errorMessage = spotifyErrorOrDefault("Spotify failed to start playback")
                    )
                }
            }
        }
        persistStateAsync()
    }

    fun playFromIndex(state: PlaybackStatus, target: Int) {
        context.scope.launch {
            val activeTrackIds = spotifyPlaybackTrackIds(state)
            if (activeTrackIds.isEmpty()) {
                context.mutableStatus.value = state.copy(
                    state = PlaybackStateType.ERROR,
                    errorMessage = spotifyErrorOrDefault("Spotify queue is empty")
                )
                persistStateAsync()
                return@launch
            }
            val targetTrack = state.queue[target]
            val activeIndex = activeTrackIds.indexOfFirst { trackIdsMatch(it, targetTrack.id) }
                .takeIf { it >= 0 }
                ?: target.coerceIn(0, activeTrackIds.lastIndex)
            val started = spotifyPlaybackController.startQueue(activeTrackIds, activeIndex)
            if (started) {
                context.spotifyQueueRequiresReload = false
                context.queueIndexCache.spotifyCurrentQueueIndex = activeIndex
                spotifyPlaybackController.setVolume(context.mutableStatus.value.volume)
            }
            context.mutableStatus.value = if (started) {
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
    }

    fun togglePlayPause() {
        val state = context.mutableStatus.value
        // An explicit play/pause command is unambiguous user intent - it can't be
        // mistaken for a stall, so it always cancels any in-progress stall watch.
        context.recovery.resetSpotifyMidTrackStallState()
        context.scope.launch {
            val success = if (state.state == PlaybackStateType.PLAYING) {
                spotifyPlaybackController.pause()
            } else {
                resumeOrReloadSpotifyQueue(state)
            }
            val nextState = if (!success) PlaybackStateType.ERROR else if (state.state == PlaybackStateType.PLAYING) PlaybackStateType.PAUSED else PlaybackStateType.PLAYING
            if (success && nextState == PlaybackStateType.PLAYING) {
                context.spotifyQueueRequiresReload = false
            }
            context.mutableStatus.value = state.copy(
                state = nextState,
                errorMessage = if (!success) spotifyErrorOrDefault("Spotify command failed") else null
            )
            persistStateAsync()
        }
    }

    fun play() {
        val state = context.mutableStatus.value
        context.recovery.resetSpotifyMidTrackStallState()
        context.scope.launch {
            val success = resumeOrReloadSpotifyQueue(state)
            if (success) {
                context.spotifyQueueRequiresReload = false
            }
            context.mutableStatus.value = context.mutableStatus.value.copy(
                state = if (success) PlaybackStateType.PLAYING else PlaybackStateType.ERROR,
                errorMessage = if (success) null else spotifyErrorOrDefault("Spotify failed to resume playback")
            )
            persistStateAsync()
        }
    }

    /** Shared "try to resume first (works if track is already loaded/paused); if that
     *  fails (e.g. player is in Stopped state after app restart) or the queue is known
     *  to need it, reload the queue and play from the current track" logic for [play]
     *  and [togglePlayPause]. After a fresh [SpotifyPlaybackController.startQueue] call
     *  the device needs a moment to actually load the target track - seeking
     *  immediately races that load and gets silently dropped, so the track audibly
     *  restarts from 0 instead of resuming at [PlaybackStatus.position]. */
    private suspend fun resumeOrReloadSpotifyQueue(state: PlaybackStatus): Boolean {
        var ok = if (context.spotifyQueueRequiresReload && state.queue.isNotEmpty()) {
            val activeTrackIds = spotifyPlaybackTrackIds(state)
            val currentIndex = currentSpotifyQueueIndex(state)
            val expectedTrackId = activeTrackIds.getOrNull(currentIndex)
            val started = spotifyPlaybackController.startQueue(activeTrackIds, currentIndex)
            if (started && state.position > 0L && expectedTrackId != null) {
                awaitSpotifyTrackLoaded(expectedTrackId, SPOTIFY_SNAPSHOT_AWAIT_TIMEOUT_MS)
                spotifyPlaybackController.seekTo(state.position)
            }
            started
        } else {
            spotifyPlaybackController.play()
        }
        if (!ok && state.queue.isNotEmpty()) {
            val activeTrackIds = spotifyPlaybackTrackIds(state)
            val currentIndex = currentSpotifyQueueIndex(state)
            ok = spotifyPlaybackController.startQueue(activeTrackIds, currentIndex)
        }
        return ok
    }

    private suspend fun awaitSpotifyTrackLoaded(expectedTrackId: String, timeoutMs: Long) {
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadlineMs) {
            val snap = spotifyPlaybackController.snapshot()
            if (snap?.currentTrackId != null && trackIdsMatch(snap.currentTrackId, expectedTrackId)) return
            delay(100)
        }
    }

    fun pause() {
        context.recovery.resetSpotifyMidTrackStallState()
        context.scope.launch {
            val success = spotifyPlaybackController.pause()
            context.mutableStatus.value = context.mutableStatus.value.copy(
                state = if (success) PlaybackStateType.PAUSED else PlaybackStateType.ERROR,
                errorMessage = if (success) null else spotifyErrorOrDefault("Spotify failed to pause")
            )
            persistStateAsync()
        }
    }

    fun seekTo(positionMs: Long) {
        context.scope.launch {
            spotifyPlaybackController.seekTo(positionMs)
            val state = context.mutableStatus.value
            val duration = if (state.duration <= 0) Long.MAX_VALUE else state.duration
            context.mutableStatus.value = state.copy(position = positionMs.coerceIn(0, duration))
            persistStateAsync()
        }
    }

    fun setVolume(requestedVolume: Int) {
        context.scope.launch {
            spotifyPlaybackController.setVolume(requestedVolume)
            context.mutableStatus.value = context.mutableStatus.value.copy(volume = requestedVolume)
            persistStateAsync()
        }
    }

    fun setShuffle(enabled: Boolean) {
        val state = context.mutableStatus.value
        val updatedOrderedQueue = QueueOrderingUtils.buildOrderedQueue(
            queue = state.queue,
            currentTrackId = state.currentTrack?.id,
            shuffleEnabled = enabled
        )
        context.mutableStatus.value = state.copy(
            shuffle = enabled,
            orderedQueue = updatedOrderedQueue
        )
        context.scope.launch {
            val shuffleApplied = spotifyPlaybackController.setShuffle(enabled)
            val latestState = context.mutableStatus.value
            val activeTrackIds = spotifyPlaybackTrackIds(latestState)
            val currentTrackId = state.currentTrack?.id
            val activeIndex = currentTrackId
                ?.let { trackId -> activeTrackIds.indexOfFirst { trackIdsMatch(it, trackId) } }
                ?.takeIf { it >= 0 }
                ?: 0
            val wasPlaying = state.state == PlaybackStateType.PLAYING
            if (wasPlaying && activeTrackIds.isNotEmpty()) {
                val started = spotifyPlaybackController.startQueue(activeTrackIds, activeIndex)
                if (started) {
                    context.spotifyQueueRequiresReload = false
                    context.queueIndexCache.spotifyCurrentQueueIndex = activeIndex
                    spotifyPlaybackController.setVolume(latestState.volume)
                } else {
                    context.spotifyQueueRequiresReload = true
                    context.mutableStatus.value = context.mutableStatus.value.copy(
                        state = PlaybackStateType.ERROR,
                        errorMessage = spotifyErrorOrDefault("Spotify failed to apply shuffled queue")
                    )
                }
            } else if (!shuffleApplied) {
                context.mutableStatus.value = context.mutableStatus.value.copy(
                    errorMessage = spotifyErrorOrDefault("Spotify failed to change shuffle mode")
                )
            }
            if (!wasPlaying) {
                context.spotifyQueueRequiresReload = true
            }
            persistStateAsync()
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        context.scope.launch {
            spotifyPlaybackController.setRepeatMode(mode)
            context.mutableStatus.value = context.mutableStatus.value.copy(repeatMode = mode)
            persistStateAsync()
        }
    }

    fun next(state: PlaybackStatus) {
        context.recovery.manualSkipInFlight = true
        context.recovery.resetSpotifyRecoveryState()
        context.recovery.resetSpotifyMidTrackStallState()
        context.scope.launch {
            try {
                val activeQueue = spotifyPlaybackQueue(state)
                if (activeQueue.isEmpty()) return@launch
                val currentIndex = currentSpotifyQueueIndex(state)
                val targetIndex = (currentIndex + 1).coerceAtMost(activeQueue.lastIndex)
                if (targetIndex == currentIndex && currentIndex == activeQueue.lastIndex) {
                    return@launch
                }
                val targetTrack = activeQueue.getOrNull(targetIndex)
                if (targetTrack == null) {
                    context.mutableStatus.value = context.mutableStatus.value.copy(
                        errorMessage = "No track available at target index"
                    )
                    return@launch
                }
                val success = startSpotifyAtQueueIndex(targetIndex)
                if (!success) {
                    context.recovery.spotifyAutoAdvanceInFlight = false
                    CompatLog.w(TAG, "Spotify next failed: ${spotifyErrorOrDefault("unknown error")}")
                } else {
                    context.queueIndexCache.spotifyCurrentQueueIndex = targetIndex
                }
                context.addToQueueInsertionOffset = 0
                context.mutableStatus.value = context.mutableStatus.value.copy(
                    currentTrack = if (success) targetTrack else state.currentTrack,
                    state = if (success) PlaybackStateType.PLAYING else PlaybackStateType.ERROR,
                    position = if (success) 0L else state.position,
                    duration = if (success) (targetTrack.durationMs ?: state.duration) else state.duration,
                    errorMessage = if (success) null else spotifyErrorOrDefault("Spotify failed to skip to next track")
                )
                persistStateAsync()
            } finally {
                context.recovery.manualSkipInFlight = false
            }
        }
    }

    fun previous(state: PlaybackStatus) {
        context.recovery.manualSkipInFlight = true
        context.recovery.resetSpotifyRecoveryState()
        context.recovery.resetSpotifyMidTrackStallState()
        context.scope.launch {
            try {
                val activeQueue = spotifyPlaybackQueue(state)
                if (activeQueue.isEmpty()) return@launch
                val currentIndex = currentSpotifyQueueIndex(state)
                val targetIndex = (currentIndex - 1).coerceAtLeast(0)
                if (targetIndex == currentIndex && currentIndex == 0) {
                    return@launch
                }
                val targetTrack = activeQueue.getOrNull(targetIndex)
                if (targetTrack == null) {
                    context.mutableStatus.value = context.mutableStatus.value.copy(
                        errorMessage = "No track available at target index"
                    )
                    return@launch
                }
                val success = startSpotifyAtQueueIndex(targetIndex)
                if (success) {
                    context.queueIndexCache.spotifyCurrentQueueIndex = targetIndex
                }
                context.mutableStatus.value = context.mutableStatus.value.copy(
                    currentTrack = if (success) targetTrack else state.currentTrack,
                    state = if (success) PlaybackStateType.PLAYING else PlaybackStateType.ERROR,
                    position = if (success) 0L else state.position,
                    duration = if (success) (targetTrack.durationMs ?: state.duration) else state.duration,
                    errorMessage = if (success) null else spotifyErrorOrDefault("Spotify failed to go to previous track")
                )
                persistStateAsync()
            } finally {
                context.recovery.manualSkipInFlight = false
            }
        }
    }

    suspend fun sync() {
        val spotifySnapshot = spotifyPlaybackController.snapshot()
        val state = context.mutableStatus.value
        if (spotifySnapshot == null) {
            if ((state.state == PlaybackStateType.PLAYING || state.state == PlaybackStateType.ERROR) && state.queue.isNotEmpty()) {
                val currentIndex = context.queueIndexCache.currentQueueIndex(state)
                CompatLog.w(TAG, "Spotify snapshot unavailable; attempting queue recovery at index=$currentIndex state=${state.state}")
                maybeRecoverSpotifyTrack(
                    queueTrackIds = context.queueIndexCache.cachedQueueTrackIds,
                    startIndex = currentIndex,
                    failureMessage = "Spotify snapshot unavailable after interruption"
                )
            }
            return
        }
        val duration = state.currentTrack?.durationMs ?: state.duration
        val nearTrackEnd = isNearTrackEnd(spotifySnapshot.progressMs, duration, 1500L)
        if (spotifySnapshot.endOfTrackCount > context.recovery.lastAcknowledgedEndOfTrackCount && !context.recovery.manualSkipInFlight) {
            val previousTrackId = state.currentTrack?.id
            CompatLog.d(
                TAG,
                "Spotify endOfTrack detected; advancing queue immediately trackId=$previousTrackId count=${spotifySnapshot.endOfTrackCount}"
            )
            context.recovery.lastAcknowledgedEndOfTrackCount = spotifySnapshot.endOfTrackCount
            if (!context.recovery.spotifyAutoAdvanceInFlight) {
                context.recovery.spotifyAutoAdvanceInFlight = true

                // AI DJ hook: the shared Media3 ExoPlayer is guaranteed idle whenever a
                // Spotify track is current, so a ready break plays there standalone before
                // we resume the normal next()-driven advance. If no break is due,
                // upcomingTrackId simply won't match anything pending and this is a no-op -
                // behavior is byte-for-byte unchanged from before this hook existed.
                val activeQueue = spotifyPlaybackQueue(state)
                val currentIndex = currentSpotifyQueueIndex(state)
                val upcomingTrackId = activeQueue.getOrNull(currentIndex + 1)?.id
                val filler = djFillerScheduler.consumeReadyFillerIfDue(upcomingTrackId)
                if (filler != null) {
                    djInterstitialPlayer.playStandalone(filler) {
                        context.scope.launch { performSpotifyAutoAdvance(previousTrackId, state) }
                    }
                } else {
                    context.scope.launch { performSpotifyAutoAdvance(previousTrackId, state) }
                }
                return
            }
        }
        if (nearTrackEnd && state.state == PlaybackStateType.PLAYING && !spotifySnapshot.isPlaying &&
            !context.recovery.manualSkipInFlight
        ) {
            return
        }
        // A mid-track pause (state PLAYING, snapshot not playing, not near the end) is
        // reflected as PAUSED by the sync below on its own, since a single poll can't
        // tell a legitimate remote pause apart from a genuine stuck Connect session.
        // Only recover once the same (track, position) has persisted across several
        // poll cycles - see MID_TRACK_STALL_THRESHOLD_MS. The state write below flips
        // state.state to PAUSED after the first stalled poll, so continuing to watch an
        // already-detected stall across later polls can't gate on state.state staying
        // PLAYING - it uses spotifyMidTrackStallTrackId being already set instead. A
        // fresh watch still only ever starts from an unexpected PLAYING->not-playing
        // transition, never from an explicit pause (pause()/togglePlayPause() clear the
        // watch directly), so a deliberate pause can't be mistaken for a stall.
        val startingNewStallWatch = state.state == PlaybackStateType.PLAYING && !spotifySnapshot.isPlaying
        val continuingKnownStallWatch = context.recovery.spotifyMidTrackStallTrackId != null && !spotifySnapshot.isPlaying
        if ((startingNewStallWatch || continuingKnownStallWatch) &&
            !context.recovery.manualSkipInFlight && state.queue.isNotEmpty()
        ) {
            val nowMs = System.currentTimeMillis()
            val currentTrackId = state.currentTrack?.id
            val sameTrack = context.recovery.spotifyMidTrackStallTrackId == currentTrackId
            val samePosition = context.recovery.spotifyMidTrackStallPositionMs == spotifySnapshot.progressMs
            if (!sameTrack || !samePosition) {
                context.recovery.spotifyMidTrackStallTrackId = currentTrackId
                context.recovery.spotifyMidTrackStallPositionMs = spotifySnapshot.progressMs
                context.recovery.spotifyMidTrackStallSinceMs = nowMs
            } else {
                val stalledMs = nowMs - context.recovery.spotifyMidTrackStallSinceMs
                if (stalledMs >= MID_TRACK_STALL_THRESHOLD_MS) {
                    val currentIndex = context.queueIndexCache.currentQueueIndex(state)
                    CompatLog.w(TAG, "Spotify mid-track stall persisted ${stalledMs}ms; attempting recovery at index=$currentIndex")
                    context.recovery.resetSpotifyMidTrackStallState()
                    maybeRecoverSpotifyTrack(
                        queueTrackIds = context.queueIndexCache.cachedQueueTrackIds,
                        startIndex = currentIndex,
                        failureMessage = "Spotify playback stalled mid-track"
                    )
                    return
                }
            }
        } else {
            context.recovery.resetSpotifyMidTrackStallState()
        }
        if (state.state == PlaybackStateType.ERROR && !spotifySnapshot.isPlaying && state.queue.isNotEmpty()) {
            val currentIndex = context.queueIndexCache.currentQueueIndex(state)
            CompatLog.w(TAG, "Spotify in ERROR state; attempting recovery at index=$currentIndex attempts=${context.recovery.spotifyRecoveryAttempts}")
            maybeRecoverSpotifyTrack(
                queueTrackIds = context.queueIndexCache.cachedQueueTrackIds,
                startIndex = currentIndex,
                failureMessage = "Spotify failed to recover from error state"
            )
            return
        }
        if (context.recovery.spotifyAutoAdvanceInFlight) {
            context.recovery.spotifyAutoAdvanceInFlight = false
            context.recovery.spotifyAutoAdvanceTrackId = null
        }
        val mappedTrackIndex = spotifySnapshot.currentTrackId?.let { id ->
            context.queueIndexCache.findQueueIndexNear(id, context.queueIndexCache.spotifyCurrentQueueIndex, state.queue).takeIf { it >= 0 }
        }
        if (mappedTrackIndex != null) {
            context.queueIndexCache.spotifyCurrentQueueIndex = mappedTrackIndex
        }
        val mappedTrack = mappedTrackIndex?.let { state.queue[it] }
        val effectiveShuffle = state.shuffle
        context.mutableStatus.value = state.copy(
            state = if (spotifySnapshot.isPlaying) PlaybackStateType.PLAYING else PlaybackStateType.PAUSED,
            position = spotifySnapshot.progressMs,
            duration = mappedTrack?.durationMs ?: state.duration,
            currentTrack = mappedTrack ?: state.currentTrack,
            volume = state.volume,
            shuffle = effectiveShuffle,
            repeatMode = spotifySnapshot.repeatMode,
            orderedQueue = state.orderedQueue.ifEmpty { state.queue }
        )
    }

    fun maybeRecoverSpotifyTrack(
        queueTrackIds: List<String>,
        startIndex: Int,
        failureMessage: String
    ): Boolean {
        if (queueTrackIds.isEmpty()) return false
        val nowMs = System.currentTimeMillis()
        val cooldownMs = if (context.recovery.spotifyRecoveryAttempts >= 1) 4000L else 2500L
        val recoveryInFlight = context.recovery.spotifyRecoveryInFlight
        val inCooldown = (nowMs - context.recovery.spotifyRecoveryLastAttemptMs) < cooldownMs
        if (recoveryInFlight || inCooldown) {
            CompatLog.d(
                TAG,
                "Skipping Spotify recovery attempt inFlight=$recoveryInFlight cooldownMs=${nowMs - context.recovery.spotifyRecoveryLastAttemptMs} attempts=${context.recovery.spotifyRecoveryAttempts}"
            )
            return recoveryInFlight
        }
        if (context.recovery.spotifyRecoveryAttempts >= 3) {
            CompatLog.w(TAG, "Spotify recovery exhausted after ${context.recovery.spotifyRecoveryAttempts} attempts")
            return false
        }
        context.recovery.spotifyRecoveryInFlight = true
        context.recovery.spotifyRecoveryLastAttemptMs = nowMs
        context.recovery.spotifyRecoveryAttempts++
        val attempt = context.recovery.spotifyRecoveryAttempts
        CompatLog.i(TAG, "Attempting Spotify recovery (attempt $attempt) startIndex=$startIndex queueSize=${queueTrackIds.size}")
        context.scope.launch {
            val recovered = spotifyPlaybackController.startQueue(queueTrackIds, startIndex)
            if (recovered) {
                context.queueIndexCache.spotifyCurrentQueueIndex = startIndex.coerceIn(0, (queueTrackIds.size - 1).coerceAtLeast(0))
                spotifyPlaybackController.setVolume(context.mutableStatus.value.volume)
                CompatLog.i(TAG, "Spotify recovery succeeded on attempt $attempt")
                context.recovery.spotifyRecoveryAttempts = 0
            } else {
                val state = context.mutableStatus.value
                CompatLog.w(TAG, "Spotify recovery attempt $attempt failed: ${spotifyErrorOrDefault("unknown error")}")
                context.mutableStatus.value = state.copy(
                    state = PlaybackStateType.ERROR,
                    errorMessage = spotifyErrorOrDefault(failureMessage)
                )
                persistStateAsync()
            }
            context.recovery.spotifyRecoveryInFlight = false
        }
        return true
    }

    /** The immediate `next()`-driven auto-advance path, extracted out of [sync] so the AI
     *  DJ hook there can either run it directly or defer it until a standalone
     *  interstitial finishes playing on the shared (Spotify-idle) ExoPlayer. */
    private suspend fun performSpotifyAutoAdvance(previousTrackId: String?, state: PlaybackStatus) {
        val success = spotifyPlaybackController.next()
        context.recovery.spotifyAutoAdvanceInFlight = false
        if (success) {
            val advancedTrackIndex = awaitSpotifyAdvance(
                previousTrackId = previousTrackId,
                state = context.mutableStatus.value,
                timeoutMs = SPOTIFY_SNAPSHOT_AWAIT_TIMEOUT_MS
            )
            if (advancedTrackIndex != null) {
                context.queueIndexCache.spotifyCurrentQueueIndex = advancedTrackIndex
                context.addToQueueInsertionOffset = 0
            } else {
                CompatLog.w(TAG, "Immediate spotifyNext() returned success without advancing; falling back to startQueue")
                fallbackAdvanceSpotifyQueue(previousTrackId, context.mutableStatus.value)
                context.addToQueueInsertionOffset = 0
            }
        } else {
            CompatLog.w(TAG, "Immediate spotifyNext() failed; falling back to startQueue")
            fallbackAdvanceSpotifyQueue(previousTrackId, state)
            context.addToQueueInsertionOffset = 0
        }
    }

    private suspend fun awaitSpotifyAdvance(
        previousTrackId: String?,
        state: PlaybackStatus,
        timeoutMs: Long
    ): Int? {
        val activeQueue = spotifyPlaybackQueue(state)
        if (previousTrackId.isNullOrBlank() || activeQueue.isEmpty()) return null
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadlineMs) {
            val snapshotTrackId = spotifyPlaybackController.snapshot()?.currentTrackId
            val advancedTrackId = snapshotTrackId
                ?.takeIf { !trackIdsMatch(it, previousTrackId) }
            if (advancedTrackId != null) {
                val advancedIndex = context.queueIndexCache.findQueueIndexNear(advancedTrackId, context.queueIndexCache.spotifyCurrentQueueIndex, activeQueue)
                if (advancedIndex >= 0) {
                    return advancedIndex
                }
            }
            delay(100)
        }
        return null
    }

    private suspend fun fallbackAdvanceSpotifyQueue(endedTrackId: String?, state: PlaybackStatus) {
        val activeQueue = spotifyPlaybackQueue(state)
        if (activeQueue.isEmpty()) return
        val fallbackSnapshotTrackId = spotifyPlaybackController.snapshot()?.currentTrackId
        if (!fallbackSnapshotTrackId.isNullOrBlank() && !trackIdsMatch(
                fallbackSnapshotTrackId,
                endedTrackId.orEmpty()
            )
        ) {
            val advancedIndex = context.queueIndexCache.findQueueIndexNear(fallbackSnapshotTrackId, context.queueIndexCache.spotifyCurrentQueueIndex, activeQueue)
            if (advancedIndex >= 0) {
                context.queueIndexCache.spotifyCurrentQueueIndex = advancedIndex
            }
            CompatLog.i(TAG, "Spotify track already advanced; skipping fallback restart")
            return
        }

        val endedIndex = endedTrackId
            ?.let { context.queueIndexCache.findQueueIndexNear(it, context.queueIndexCache.spotifyCurrentQueueIndex, activeQueue) }
            ?.takeIf { it >= 0 }
            ?: context.queueIndexCache.spotifyCurrentQueueIndex.coerceIn(0, activeQueue.lastIndex)
        val nextIdx = endedIndex + 1
        if (nextIdx in activeQueue.indices && nextIdx > endedIndex) {
            startSpotifyAtQueueIndex(nextIdx)
            context.queueIndexCache.spotifyCurrentQueueIndex = nextIdx
        } else {
            CompatLog.i(TAG, "Skipping Spotify fallback restart; endedIndex=$endedIndex queueSize=${activeQueue.size}")
        }
    }

    private suspend fun startSpotifyAtQueueIndex(targetIndex: Int): Boolean {
        val state = context.mutableStatus.value
        val activeTrackIds = spotifyPlaybackTrackIds(state)
        if (!context.spotifyMode || activeTrackIds.isEmpty()) return false
        val safeIndex = targetIndex.coerceIn(0, activeTrackIds.lastIndex)
        val started = spotifyPlaybackController.startQueue(activeTrackIds, safeIndex)
        if (started) {
            context.spotifyQueueRequiresReload = false
            context.queueIndexCache.spotifyCurrentQueueIndex = safeIndex
            spotifyPlaybackController.setVolume(state.volume)
        }
        return started
    }

    /**
     * Cold-launch restore path: starts Spotify playback of [trackIds] at [startIndex],
     * waits (bounded, 10 x 200ms) for the target track to become the reported current
     * track, seeks to [positionMs] if positive, then pauses - so the app resumes to the
     * persisted position without audibly playing from 0 first. Single owner for this
     * "start and wait" sequence so it can't drift out of sync with [play]/[togglePlayPause]'s
     * own reload-and-resume handling.
     */
    suspend fun restoreQueueAndPause(trackIds: List<String>, startIndex: Int, positionMs: Long): Boolean {
        if (trackIds.isEmpty()) return false
        val expectedTrackId = trackIds.getOrNull(startIndex)
        val started = spotifyPlaybackController.startQueue(trackIds, startIndex)
        if (!started) return false
        spotifyPlaybackController.setVolume(context.mutableStatus.value.volume)
        if (expectedTrackId != null && positionMs > 0) {
            var readyForSeek = false
            val deadlineMs = System.currentTimeMillis() + SPOTIFY_SNAPSHOT_AWAIT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadlineMs) {
                delay(200)
                val snap = spotifyPlaybackController.snapshot()
                if (snap?.currentTrackId != null && trackIdsMatch(snap.currentTrackId, expectedTrackId)) {
                    readyForSeek = true
                    break
                }
            }
            if (readyForSeek) {
                spotifyPlaybackController.seekTo(positionMs)
                delay(100)
            }
        }
        context.mutableStatus.value = context.mutableStatus.value.copy(position = positionMs)
        // This always ends paused, so Spotify's own "what's next in this context" tracking
        // can't be trusted yet by the time the user resumes - force that resume through
        // resumeOrReloadSpotifyQueue's full reload path instead of a bare resume, so the
        // device has a freshly (re-)established queue for its own auto-advance to work from.
        context.spotifyQueueRequiresReload = true
        spotifyPlaybackController.pause()
        return true
    }
}
