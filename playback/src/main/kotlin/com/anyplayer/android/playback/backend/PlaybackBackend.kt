package com.anyplayer.android.playback.backend

import com.anyplayer.android.core.model.BackendKind
import com.anyplayer.android.core.model.Run
import kotlinx.coroutines.flow.Flow

/** Identifies a plan item independent of its index: a queue entry uid or a DJ break id. */
sealed interface ItemKey {
    data class Song(val uid: Long) : ItemKey
    data class Break(val breakId: String) : ItemKey
}

/** Every event carries the [loadId] of the load that produced it; the engine drops stale ones. */
sealed interface BackendEvent {
    val loadId: Long

    data class ItemStarted(override val loadId: Long, val key: ItemKey) : BackendEvent
    /** [isPlaying] false with [byPlayer] true = pause originated outside the engine (focus loss, noisy, external Spotify pause). */
    data class PlayingChanged(override val loadId: Long, val isPlaying: Boolean, val buffering: Boolean, val byPlayer: Boolean) : BackendEvent
    /** Position jumped or drifted; the engine re-anchors from [positionMs]. */
    data class PositionDiscontinuity(override val loadId: Long, val positionMs: Long, val durationMs: Long) : BackendEvent
    /** The last item of the run (or the stop point set by [PlaybackBackend.updateUpcoming]) finished. */
    data class RunEnded(override val loadId: Long) : BackendEvent
    data class Error(override val loadId: Long, val key: ItemKey?, val message: String) : BackendEvent
}

/**
 * Plays one [Run] at a time; the engine owns the order. Commands are serialized per backend and
 * never cancelled half-way; results of superseded loads are discarded via [loadId].
 */
interface PlaybackBackend {
    val kind: BackendKind
    val events: Flow<BackendEvent>

    suspend fun load(loadId: Long, run: Run, startIndex: Int, positionMs: Long, playWhenReady: Boolean)
    /**
     * The upcoming part of the run changed while [currentKey] keeps playing. EXO edits the
     * timeline after the current item; SPOTIFY sets a stop point or reloads (design §12.3).
     */
    suspend fun updateUpcoming(loadId: Long, run: Run, currentKey: ItemKey)
    /** Moves to another item of the already loaded run without rebuilding it. */
    suspend fun seekToItem(key: ItemKey, positionMs: Long)
    suspend fun play()
    suspend fun pause()
    suspend fun seekTo(ms: Long)
    suspend fun setVolume(effectivePercent: Int)
    suspend fun setRepeatOne(on: Boolean)
    suspend fun stop()
    fun positionMs(): Long
    fun durationMs(): Long
}
