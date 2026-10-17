package com.anyplayer.android.playback.backend

import com.anyplayer.android.core.model.BackendKind
import com.anyplayer.android.core.model.Run
import kotlinx.coroutines.flow.Flow

sealed interface BackendEvent {
    /** The item at [indexInRun] of the loaded run became current. */
    data class ItemStarted(val indexInRun: Int) : BackendEvent
    data class PlayingChanged(val isPlaying: Boolean, val buffering: Boolean) : BackendEvent
    /** Position jumped (seek, item change); the engine re-anchors. */
    data object PositionDiscontinuity : BackendEvent
    /** The last item of the run finished. */
    data object RunEnded : BackendEvent
    data class Error(val message: String) : BackendEvent
}

/** Plays one [Run] at a time; the engine owns the order. Main-thread confined. */
interface PlaybackBackend {
    val kind: BackendKind
    val events: Flow<BackendEvent>
    suspend fun load(run: Run, startIndex: Int, positionMs: Long, playWhenReady: Boolean)
    /** Moves to another item of the already loaded run without rebuilding it. */
    suspend fun seekToItem(indexInRun: Int, positionMs: Long)
    suspend fun play()
    suspend fun pause()
    suspend fun seekTo(ms: Long)
    suspend fun stop()
    fun positionMs(): Long
    fun durationMs(): Long
}
