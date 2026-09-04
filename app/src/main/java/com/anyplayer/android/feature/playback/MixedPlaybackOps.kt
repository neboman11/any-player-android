package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.djfiller.DjFillerScheduler
import com.anyplayer.android.feature.djfiller.DjInterstitialPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mixed-mode (a queue containing both Spotify and local/Media3 tracks) branch
 * of [PlaybackQueueManager]'s per-operation mode dispatch, moved out verbatim
 * (Stage 3, the highest-regression-risk stage, of
 * docs/playback-queue-manager-decomposition-plan.md). [playMixedTrackAtIndex]
 * is also called from [PlaybackQueueManager.playFromIndex], so it stays public;
 * [playMixedTrackById] has no callers outside this class and stays private.
 */
internal class MixedPlaybackOps(
    private val media3PlaybackController: Media3PlaybackController,
    private val spotifyPlaybackController: SpotifyPlaybackController,
    private val audioCacheManager: AudioCacheManager,
    private val spotifyOps: SpotifyPlaybackOps,
    private val context: PlaybackEngineContext,
    private val isNearTrackEnd: (positionMs: Long, durationMs: Long, toleranceMs: Long) -> Boolean,
    private val applyNormalizedMedia3Volume: suspend (Int, SourceType) -> Unit,
    private val persistStateAsync: () -> Unit,
    private val djFillerScheduler: DjFillerScheduler,
    private val djInterstitialPlayer: DjInterstitialPlayer
) {
    companion object {
        private const val TAG = "MixedPlaybackOps"
    }

    private fun mixedPlaybackSequence(state: PlaybackStatus): List<Track> =
        state.orderedQueue.takeIf { it.isNotEmpty() } ?: state.queue

    /** Index of [trackId] within [sequence], via the same normalized-id comparison
     *  [QueueIndexCache] uses elsewhere - a queue can contain the same Spotify track
     *  more than once under different raw ID formats, and a plain `it.id == trackId`
     *  comparison would miss that. [sequence] is the shuffle-aware playback order
     *  (from [mixedPlaybackSequence]), which is why this can't just delegate to
     *  [QueueIndexCache.findQueueIndex] - that cache is keyed to the unshuffled queue. */
    private fun sequenceIndexOf(sequence: List<Track>, trackId: String?): Int {
        if (trackId == null) return -1
        return sequence.indexOfFirst { trackIdsMatch(it.id, trackId) }
    }

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

    /** Mixed-mode branch of [PlaybackQueueManager.setQueue]: [tracks] contains both
     *  Spotify and non-Spotify tracks, per [PlaybackEngineContext.mixedMode] having
     *  already been computed by the caller. [startIndex] is pre-resolved. */
    fun setQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean) {
        context.recovery.resetSpotifyAutoAdvanceState()
        context.recovery.resetSpotifyRecoveryState()
        context.recovery.resetSpotifyMidTrackStallState()
        context.recovery.resetMixedMediaEndStallState()
        context.queueIndexCache.rebuildQueueCaches(tracks)
        media3PlaybackController.setQueue(emptyList(), 0, false)
        val clampedIndex = startIndex.coerceIn(0, tracks.lastIndex)
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
    }

    fun triggerPrefetch() {
        val state = context.mutableStatus.value
        if (context.spotifyMode || state.queue.isEmpty()) return
        val currentId = state.currentTrack?.id ?: return
        val queue = if (context.mixedMode) mixedPlaybackSequence(state) else state.orderedQueue
        val currentIdx = sequenceIndexOf(queue, currentId).takeIf { it >= 0 } ?: return
        val upcoming = queue
            .drop(currentIdx + 1)
            .filter { !it.url.isNullOrBlank() && it.source != SourceType.SPOTIFY }
        audioCacheManager.prefetchTracks(upcoming)
    }

    fun togglePlayPause() {
        val state = context.mutableStatus.value
        val currentTrack = state.currentTrack
        if (currentTrack == null) return
        // An explicit play/pause command is unambiguous user intent - it can't be
        // mistaken for a stall, so it always cancels any in-progress stall watch.
        context.recovery.resetSpotifyMidTrackStallState()
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
        val state = context.mutableStatus.value
        val currentTrack = state.currentTrack ?: return
        context.recovery.resetSpotifyMidTrackStallState()
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
        val state = context.mutableStatus.value
        val currentTrack = state.currentTrack ?: return
        context.recovery.resetSpotifyMidTrackStallState()
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

    fun setVolume(requestedVolume: Int) {
        val currentTrack = context.mutableStatus.value.currentTrack
        context.scope.launch {
            if (currentTrack?.source == SourceType.SPOTIFY) {
                spotifyPlaybackController.setVolume(requestedVolume)
            } else {
                applyNormalizedMedia3Volume(requestedVolume, currentTrack?.source ?: SourceType.ALL)
            }
            context.mutableStatus.value = context.mutableStatus.value.copy(volume = requestedVolume)
            persistStateAsync()
        }
    }

    fun setShuffle(enabled: Boolean) {
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

    fun next(state: PlaybackStatus) {
        if (state.queue.isEmpty()) return
        val sequence = mixedPlaybackSequence(state)
        if (sequence.isEmpty()) return
        val currentId = state.currentTrack?.id
        val currentIndex = sequenceIndexOf(sequence, currentId).takeIf { it >= 0 } ?: 0
        val nextTrack = sequence.getOrNull(currentIndex + 1) ?: return
        // Mirrors SpotifyPlaybackOps.next(): an explicit skip command can't be mistaken
        // for a stall by sync()'s stall-detection while the async track switch is in flight.
        context.recovery.resetSpotifyRecoveryState()
        context.recovery.resetSpotifyMidTrackStallState()
        playMixedTrackById(nextTrack.id, manualSkip = true)
    }

    fun previous(state: PlaybackStatus) {
        if (state.queue.isEmpty()) return
        val sequence = mixedPlaybackSequence(state)
        if (sequence.isEmpty()) return
        val currentId = state.currentTrack?.id
        val currentIndex = sequenceIndexOf(sequence, currentId).takeIf { it >= 0 } ?: 0
        val prevTrack = sequence.getOrNull(currentIndex - 1) ?: return
        context.recovery.resetSpotifyRecoveryState()
        context.recovery.resetSpotifyMidTrackStallState()
        playMixedTrackById(prevTrack.id, manualSkip = true)
    }

    suspend fun sync() {
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
            val nearTrackEnd = isNearTrackEnd(spotifySnapshot.progressMs, duration, 1500L)
            context.mutableStatus.value = state.copy(
                state = if (spotifySnapshot.isPlaying) PlaybackStateType.PLAYING else PlaybackStateType.PAUSED,
                position = spotifySnapshot.progressMs,
                duration = duration,
                volume = state.volume,
                shuffle = state.shuffle,
                repeatMode = spotifySnapshot.repeatMode,
                orderedQueue = mixedPlaybackSequence(state)
            )
            if (spotifySnapshot.endOfTrackCount > context.recovery.lastAcknowledgedEndOfTrackCount &&
                !context.recovery.manualSkipInFlight
            ) {
                context.recovery.lastAcknowledgedEndOfTrackCount = spotifySnapshot.endOfTrackCount
                val sequence = mixedPlaybackSequence(state)
                val currentIndex = sequenceIndexOf(sequence, currentTrack.id).takeIf { it >= 0 } ?: 0
                val nextTrack = sequence.getOrNull(currentIndex + 1)
                if (nextTrack != null) {
                    // AI DJ hook: natural end-of-track only (not the stall/error recovery
                    // fallbacks below) - the shared ExoPlayer is idle here (Spotify leg), so
                    // a ready break plays standalone before handing off to the real next track.
                    val filler = djFillerScheduler.consumeReadyFillerIfDue(nextTrack.id)
                    if (filler != null) {
                        djInterstitialPlayer.playStandalone(filler) { playMixedTrackById(nextTrack.id) }
                    } else {
                        playMixedTrackById(nextTrack.id)
                    }
                    return
                }
            }
            val startingNewStallWatch = state.state == PlaybackStateType.PLAYING && !spotifySnapshot.isPlaying &&
                !context.recovery.manualSkipInFlight
            if (startingNewStallWatch) {
                val sequence = mixedPlaybackSequence(state)
                val currentIndex = sequenceIndexOf(sequence, currentTrack.id).takeIf { it >= 0 } ?: 0
                val nextTrack = sequence.getOrNull(currentIndex + 1)
                if (nearTrackEnd && nextTrack != null) {
                    playMixedTrackById(nextTrack.id)
                    return
                }
            }
            // A mid-track pause is reflected as PAUSED by the state copy above on its own,
            // since a single poll can't tell a legitimate remote pause apart from a genuine
            // stuck Connect session. Only recover once the same (track, position) has
            // persisted across several poll cycles, mirroring SpotifyPlaybackOps.sync()'s
            // dwell check - including why this can't gate on state.state staying PLAYING
            // across polls (the copy above already flips it to PAUSED after the first
            // stalled poll, so a continuing watch is tracked via spotifyMidTrackStallTrackId
            // instead).
            val continuingKnownStallWatch =
                context.recovery.spotifyMidTrackStallTrackId == currentTrack.id && !spotifySnapshot.isPlaying
            if (startingNewStallWatch || continuingKnownStallWatch) {
                val nowMs = System.currentTimeMillis()
                val samePosition = context.recovery.spotifyMidTrackStallPositionMs == spotifySnapshot.progressMs
                if (!continuingKnownStallWatch || !samePosition) {
                    context.recovery.spotifyMidTrackStallTrackId = currentTrack.id
                    context.recovery.spotifyMidTrackStallPositionMs = spotifySnapshot.progressMs
                    context.recovery.spotifyMidTrackStallSinceMs = nowMs
                } else {
                    val stalledMs = nowMs - context.recovery.spotifyMidTrackStallSinceMs
                    if (stalledMs >= SpotifyConnectBridge.POLL_INTERVAL_MS * 3) {
                        CompatLog.w(TAG, "Mixed-mode Spotify mid-track stall persisted ${stalledMs}ms; attempting recovery")
                        context.recovery.resetSpotifyMidTrackStallState()
                        spotifyOps.maybeRecoverSpotifyTrack(
                            queueTrackIds = listOf(currentTrack.id),
                            startIndex = 0,
                            failureMessage = "Spotify playback stalled mid-track"
                        )
                        return
                    }
                }
            } else {
                context.recovery.resetSpotifyMidTrackStallState()
            }
        } else {
            val snapshot = media3PlaybackController.snapshot()
            val sequence = mixedPlaybackSequence(state)
            val currentIndex = sequenceIndexOf(sequence, currentTrack.id)
            val effectiveDuration = snapshot.durationMs.takeIf { it > 0 } ?: (currentTrack.durationMs ?: state.duration)
            val nearTrackEnd = isNearTrackEnd(snapshot.positionMs, effectiveDuration, 1200L)
            val reachedEnd = snapshot.durationMs > 0L && snapshot.positionMs >= (snapshot.durationMs - 1000L)
            val transitionedOutOfPlaying =
                state.state == PlaybackStateType.PLAYING && snapshot.state != PlaybackStateType.PLAYING
            if (transitionedOutOfPlaying && reachedEnd) {
                if (context.recovery.mixedAutoAdvanceTrackId != currentTrack.id) {
                    context.recovery.mixedAutoAdvanceTrackId = currentTrack.id
                    context.recovery.resetMixedMediaEndStallState()
                    val nextTrack = sequence.getOrNull(currentIndex + 1)
                    if (nextTrack != null) {
                        // AI DJ hook: natural end-of-track only, mirroring the Spotify-leg
                        // hook above.
                        val filler = djFillerScheduler.consumeReadyFillerIfDue(nextTrack.id)
                        if (filler != null) {
                            djInterstitialPlayer.playStandalone(filler) { playMixedTrackById(nextTrack.id) }
                        } else {
                            playMixedTrackById(nextTrack.id)
                        }
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

            // ExoPlayer stops responding to play()/pause()/skip once it hits a fatal error
            // until re-prepared - mirrors the recovery in LocalPlaybackOps.sync() so a local
            // track in a mixed queue doesn't get stuck the same way.
            if (snapshot.state == PlaybackStateType.ERROR) {
                val errorTrackId = currentTrack.id
                if (context.recovery.media3ErrorRecoveryTrackId != errorTrackId) {
                    context.recovery.media3ErrorRecoveryTrackId = errorTrackId
                    context.recovery.media3ErrorRecoveryAttempts = 0
                    context.recovery.media3ErrorRecoveryLastAttemptMs = 0L
                }
                val nowMs = System.currentTimeMillis()
                if (nowMs - context.recovery.media3ErrorRecoveryLastAttemptMs >= 1500L) {
                    context.recovery.media3ErrorRecoveryLastAttemptMs = nowMs
                    context.recovery.media3ErrorRecoveryAttempts++
                    if (context.recovery.media3ErrorRecoveryAttempts <= 3) {
                        CompatLog.w(TAG, "Mixed-mode media3 playback error; retrying (attempt ${context.recovery.media3ErrorRecoveryAttempts}) trackId=$errorTrackId")
                        media3PlaybackController.retryAfterError()
                    } else {
                        CompatLog.w(TAG, "Mixed-mode media3 playback error persisted after ${context.recovery.media3ErrorRecoveryAttempts} attempts; skipping track trackId=$errorTrackId")
                        context.recovery.media3ErrorRecoveryTrackId = null
                        context.recovery.media3ErrorRecoveryAttempts = 0
                        val nextTrack = sequence.getOrNull(currentIndex + 1)
                        if (nextTrack != null) {
                            playMixedTrackById(nextTrack.id)
                            return
                        }
                    }
                }
            } else if (context.recovery.media3ErrorRecoveryTrackId != null) {
                context.recovery.media3ErrorRecoveryTrackId = null
                context.recovery.media3ErrorRecoveryAttempts = 0
            }

            if (currentTrack.id != context.lastPrefetchedForTrackId) {
                context.lastPrefetchedForTrackId = currentTrack.id
                triggerPrefetch()
            }
        }
    }

    fun playMixedTrackAtIndex(index: Int, manualSkip: Boolean = false) {
        val state = context.mutableStatus.value
        if (state.queue.isEmpty()) return
        val target = index.coerceIn(0, state.queue.lastIndex)
        val track = state.queue[target]
        val previousTrack = state.currentTrack

        if (manualSkip) {
            context.recovery.manualSkipInFlight = true
        }
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
            if (manualSkip) {
                context.recovery.manualSkipInFlight = false
            }
        }
    }

    private fun playMixedTrackById(trackId: String, manualSkip: Boolean = false) {
        val state = context.mutableStatus.value
        val target = context.queueIndexCache.findQueueIndex(trackId)
        if (target < 0) return
        playMixedTrackAtIndex(target, manualSkip)
    }
}
