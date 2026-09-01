package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mixed-mode (a queue containing both Spotify and local/Media3 tracks) branch
 * of [PlaybackQueueManager]'s per-operation mode dispatch, moved out verbatim
 * (Stage 3, the highest-regression-risk stage, of
 * docs/playback-queue-manager-decomposition-plan.md). [playMixedTrackAtIndex]
 * and [triggerPrefetch] are also called from [PlaybackQueueManager.setQueue]
 * (mode-agnostic, stays there), so they stay public; [playMixedTrackById] has
 * no callers outside this class and stays private.
 */
internal class MixedPlaybackOps(
    private val media3PlaybackController: Media3PlaybackController,
    private val spotifyPlaybackController: SpotifyPlaybackController,
    private val audioCacheManager: AudioCacheManager,
    private val spotifyOps: SpotifyPlaybackOps,
    private val context: PlaybackEngineContext,
    private val isSpotifyMode: () -> Boolean,
    private val isMixedMode: () -> Boolean,
    private val isNearTrackEnd: (positionMs: Long, durationMs: Long, toleranceMs: Long) -> Boolean,
    private val applyNormalizedMedia3Volume: suspend (Int, SourceType) -> Unit,
    private val persistStateAsync: () -> Unit
) {
    companion object {
        private const val TAG = "MixedPlaybackOps"
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

    fun triggerPrefetch() {
        val state = context.mutableStatus.value
        if (isSpotifyMode() || state.queue.isEmpty()) return
        val currentId = state.currentTrack?.id ?: return
        val queue = if (isMixedMode()) mixedPlaybackSequence(state) else state.orderedQueue
        val currentIdx = queue.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: return
        val upcoming = queue
            .drop(currentIdx + 1)
            .filter { !it.url.isNullOrBlank() && it.source != SourceType.SPOTIFY }
        audioCacheManager.prefetchTracks(upcoming)
    }

    fun togglePlayPause() {
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
        val currentIndex = sequence.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
        val nextTrack = sequence.getOrNull(currentIndex + 1) ?: return
        playMixedTrackById(nextTrack.id)
    }

    fun previous(state: PlaybackStatus) {
        if (state.queue.isEmpty()) return
        val sequence = mixedPlaybackSequence(state)
        if (sequence.isEmpty()) return
        val currentId = state.currentTrack?.id
        val currentIndex = sequence.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
        val prevTrack = sequence.getOrNull(currentIndex - 1) ?: return
        playMixedTrackById(prevTrack.id)
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
                spotifyOps.maybeRecoverSpotifyTrack(
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

    fun playMixedTrackAtIndex(index: Int) {
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
