package com.anyplayer.android.playback

import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.QueueState
import com.anyplayer.android.core.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport state. Position is not a field: it is extrapolated from the anchor
 * (`anchorPositionMs` at `anchorElapsedRealtimeMs`) so nothing needs to poll.
 */
data class PlayerState(
    val status: PlaybackStateType = PlaybackStateType.IDLE,
    val currentUid: Long? = null,
    val durationMs: Long = 0,
    val anchorPositionMs: Long = 0,
    val anchorElapsedRealtimeMs: Long = 0,
    val isAdvancing: Boolean = false,
    val volume: Int = 100,
    val normalization: Boolean = false,
    val error: String? = null,
    val activeBreakId: String? = null,
)

/** The single app-owned playback engine. Main-thread confined. */
interface PlaybackEngine {
    val queue: StateFlow<QueueState>
    val player: StateFlow<PlayerState>

    /** Current position extrapolated from the [PlayerState] anchor. */
    fun positionMs(): Long

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, autoPlay: Boolean = true)
    fun playEntry(uid: Long)
    fun play()
    fun pause()
    fun togglePlayPause()
    fun next()
    fun previous()
    fun seekTo(ms: Long)
}
