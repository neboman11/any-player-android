package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import kotlinx.coroutines.launch

/**
 * Local/Media3 (Jellyfin, Plex, local-file) branch of [PlaybackQueueManager]'s
 * per-operation mode dispatch, moved out verbatim (Stage 1 of
 * docs/playback-queue-manager-decomposition-plan.md). [audioCacheManager] is
 * not used directly yet — kept as a constructor dependency per the plan for
 * when Stage 3 re-homes prefetch-queue selection here.
 */
internal class LocalPlaybackOps(
    private val media3PlaybackController: Media3PlaybackController,
    private val audioCacheManager: AudioCacheManager,
    private val context: PlaybackEngineContext,
    private val applyNormalizedMedia3Volume: suspend (Int, SourceType) -> Unit,
    private val triggerPrefetch: () -> Unit,
    private val persistStateAsync: () -> Unit
) {
    companion object {
        private const val TAG = "LocalPlaybackOps"
    }

    fun playFromIndex(state: PlaybackStatus, target: Int) {
        val mediaIndex = context.playableQueueIndices.indexOf(target)
        if (mediaIndex >= 0) {
            media3PlaybackController.playFromIndex(mediaIndex)
        }
        context.mutableStatus.value = state.copy(
            currentTrack = state.queue[target],
            state = PlaybackStateType.PLAYING,
            position = 0,
            duration = state.queue[target].durationMs ?: 0
        )
        context.lastPrefetchedForTrackId = state.queue[target].id
        triggerPrefetch()
        persistStateAsync()
    }

    fun togglePlayPause() {
        media3PlaybackController.togglePlayPause()
        val state = context.mutableStatus.value
        val next = when (state.state) {
            PlaybackStateType.PLAYING -> PlaybackStateType.PAUSED
            PlaybackStateType.PAUSED,
            PlaybackStateType.IDLE,
            PlaybackStateType.BUFFERING,
            PlaybackStateType.ERROR -> PlaybackStateType.PLAYING
        }
        context.mutableStatus.value = state.copy(state = next)
        persistStateAsync()
    }

    fun play() {
        media3PlaybackController.play()
        context.mutableStatus.value = context.mutableStatus.value.copy(state = PlaybackStateType.PLAYING)
        persistStateAsync()
    }

    fun pause() {
        media3PlaybackController.pause()
        context.mutableStatus.value = context.mutableStatus.value.copy(state = PlaybackStateType.PAUSED)
        persistStateAsync()
    }

    fun seekTo(positionMs: Long) {
        media3PlaybackController.seekTo(positionMs)
        val state = context.mutableStatus.value
        val duration = if (state.duration <= 0) Long.MAX_VALUE else state.duration
        context.mutableStatus.value = state.copy(position = positionMs.coerceIn(0, duration))
        persistStateAsync()
    }

    fun setVolume(requestedVolume: Int) {
        context.scope.launch {
            val currentTrackSource = context.mutableStatus.value.currentTrack?.source
            applyNormalizedMedia3Volume(requestedVolume, currentTrackSource ?: SourceType.ALL)
            context.mutableStatus.value = context.mutableStatus.value.copy(volume = requestedVolume)
            persistStateAsync()
        }
    }

    fun setShuffle(enabled: Boolean) {
        media3PlaybackController.setShuffle(enabled)
        context.mutableStatus.value = context.mutableStatus.value.copy(shuffle = enabled)
        persistStateAsync()
    }

    fun setRepeatMode(mode: RepeatMode) {
        media3PlaybackController.setRepeatMode(mode)
        context.mutableStatus.value = context.mutableStatus.value.copy(repeatMode = mode)
        persistStateAsync()
    }

    fun next() {
        media3PlaybackController.next()
        context.mutableStatus.value = context.mutableStatus.value.copy(state = PlaybackStateType.PLAYING)
        persistStateAsync()
    }

    fun previous(state: PlaybackStatus) {
        // Only call previous if there's a queue and we're not at the first track
        if (state.queue.isEmpty()) return
        val moved = media3PlaybackController.previous()
        if (moved) {
            context.mutableStatus.value = context.mutableStatus.value.copy(state = PlaybackStateType.PLAYING)
            persistStateAsync()
        }
    }

    suspend fun sync() {
        val snapshot = media3PlaybackController.snapshot()
        val state = context.mutableStatus.value
        if (state.queue.isEmpty()) return

        val queueIndex = context.playableQueueIndices.getOrNull(snapshot.currentMediaIndex)
        val mappedTrack = queueIndex?.let { state.queue.getOrNull(it) }
        val orderedQueue: List<Track> = if (snapshot.shuffle && snapshot.shuffledMediaIndices.isNotEmpty()) {
            snapshot.shuffledMediaIndices.mapNotNull { mediaIdx ->
                context.playableQueueIndices.getOrNull(mediaIdx)?.let { queueIdx ->
                    state.queue.getOrNull(queueIdx)
                }
            }
        } else state.queue

        context.mutableStatus.value = state.copy(
            state = snapshot.state,
            position = snapshot.positionMs,
            duration = snapshot.durationMs.takeIf { it > 0 } ?: (mappedTrack?.durationMs ?: 0),
            currentTrack = mappedTrack ?: state.currentTrack,
            volume = state.volume,
            shuffle = snapshot.shuffle,
            repeatMode = snapshot.repeatMode,
            orderedQueue = orderedQueue
        )

        // ExoPlayer stops responding to play()/pause()/skip once it hits a fatal error
        // (e.g. a stalled/broken stream) until re-prepared - without this, playback gets
        // stuck mid-track and every transport control becomes a no-op.
        if (snapshot.state == PlaybackStateType.ERROR) {
            val errorTrackId = mappedTrack?.id ?: state.currentTrack?.id
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
                    CompatLog.w(TAG, "Media3 playback error; retrying (attempt ${context.recovery.media3ErrorRecoveryAttempts}) trackId=$errorTrackId")
                    media3PlaybackController.retryAfterError()
                } else {
                    CompatLog.w(TAG, "Media3 playback error persisted after ${context.recovery.media3ErrorRecoveryAttempts} attempts; skipping track trackId=$errorTrackId")
                    context.recovery.media3ErrorRecoveryTrackId = null
                    context.recovery.media3ErrorRecoveryAttempts = 0
                    media3PlaybackController.retryAfterError()
                    media3PlaybackController.next()
                }
            }
        } else if (context.recovery.media3ErrorRecoveryTrackId != null) {
            context.recovery.media3ErrorRecoveryTrackId = null
            context.recovery.media3ErrorRecoveryAttempts = 0
        }

        val syncedTrackId = context.mutableStatus.value.currentTrack?.id
        if (syncedTrackId != null && syncedTrackId != context.lastPrefetchedForTrackId) {
            context.lastPrefetchedForTrackId = syncedTrackId
            triggerPrefetch()
        }
    }
}
