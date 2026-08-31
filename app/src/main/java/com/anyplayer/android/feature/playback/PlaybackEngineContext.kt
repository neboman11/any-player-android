package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared mutable playback state and coroutine scope used by
 * [PlaybackQueueManager] and its per-mode Ops classes ([LocalPlaybackOps] and,
 * in later decomposition stages, SpotifyPlaybackOps/MixedPlaybackOps). Moved
 * out of [PlaybackQueueManager] verbatim so those classes can read/write it
 * without each holding their own copy — same pattern as the existing
 * [PlaybackRecoveryState] extraction.
 */
internal class PlaybackEngineContext(spotifyPlaybackController: SpotifyPlaybackController) {
    companion object {
        private const val TAG = "PlaybackEngineContext"
    }

    val errorHandler = CoroutineExceptionHandler { _, throwable ->
        CompatLog.e(TAG, "Unhandled coroutine exception", throwable)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + errorHandler)

    val mutableStatus = MutableStateFlow(
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

    val queueIndexCache = QueueIndexCache(spotifyPlaybackController)
    val recovery = PlaybackRecoveryState()
    var playableQueueIndices: List<Int> = emptyList()
    var spotifyQueueRequiresReload = false
    var addToQueueInsertionOffset: Int = 0
    var lastPrefetchedForTrackId: String? = null
}
