package com.anyplayer.android.playback

import com.anyplayer.android.core.model.PendingBreak
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.QueueState
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.Track
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport state. Position is not a field: it is extrapolated from the anchor
 * (`anchorPositionMs` at `anchorElapsedRealtimeMs`, advancing only while [status] is PLAYING)
 * so nothing needs to poll.
 */
data class PlayerState(
    val status: PlaybackStateType = PlaybackStateType.IDLE,
    /** True while the engine intends to play (play requested), even across run boundaries. */
    val playRequested: Boolean = false,
    val currentUid: Long? = null,
    val durationMs: Long = 0,
    val anchorPositionMs: Long = 0,
    val anchorElapsedRealtimeMs: Long = 0,
    val volume: Int = 100,
    val normalization: Boolean = false,
    val error: String? = null,
    /** Legacy "Playback disabled: … Next/Previous still works." guard; null when allowed. */
    val disabledReason: String? = null,
    /** Non-null while a DJ break plays (cursor stays on the pre-break song). */
    val activeBreak: ActiveBreak? = null,
)

data class ActiveBreak(val breakId: String, val durationMs: Long)

/** Who caused a state change; sync never pushes SYNC/RESTORE changes. */
enum class ChangeOrigin { USER, SYNC, RESTORE }

sealed interface EngineEvent {
    data class TrackStarted(val uid: Long, val orderPos: Int) : EngineEvent
    data class BreakStarted(val breakId: String) : EngineEvent
    data class BreakEnded(val breakId: String) : EngineEvent
    /** Pending break discarded (target passed/skipped, queue replaced); the owner deletes its file. */
    data class BreakDropped(val breakId: String) : EngineEvent
    data object QueueReplaced : EngineEvent
}

/** Emitted for every queue/player mutation with its origin (for sync push decisions). */
data class EngineChange(val origin: ChangeOrigin, val contentChanged: Boolean)

/**
 * The single app-owned playback engine. Methods may be called from any thread; they are applied
 * on the main thread. Every mutation applies the new queue state synchronously and reconciles
 * the backends afterwards (level-triggered).
 */
interface PlaybackEngine {
    val queue: StateFlow<QueueState>
    val player: StateFlow<PlayerState>
    val events: SharedFlow<EngineEvent>
    val changes: SharedFlow<EngineChange>

    /** Current position extrapolated from the [PlayerState] anchor. */
    fun positionMs(): Long

    /** Suspends until the persisted queue was restored (or there was nothing to restore). */
    suspend fun awaitRestored()

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, autoPlay: Boolean = true, origin: ChangeOrigin = ChangeOrigin.USER)
    fun playNext(track: Track)
    fun playEntry(uid: Long)
    fun play(origin: ChangeOrigin = ChangeOrigin.USER)
    fun pause(origin: ChangeOrigin = ChangeOrigin.USER)
    fun togglePlayPause()
    fun next()
    fun previous()
    fun seekTo(ms: Long, origin: ChangeOrigin = ChangeOrigin.USER)
    fun setVolume(percent: Int, origin: ChangeOrigin = ChangeOrigin.USER)
    fun setShuffle(on: Boolean, origin: ChangeOrigin = ChangeOrigin.USER)
    fun setRepeat(mode: RepeatMode, origin: ChangeOrigin = ChangeOrigin.USER)
    fun setNormalization(enabled: Boolean, strict: Boolean = false)

    /** DJ host: the break plays right before `beforeUid` when the cursor advances into it. */
    fun setPendingBreak(pendingBreak: PendingBreak?)
}
