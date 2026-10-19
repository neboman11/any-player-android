package com.anyplayer.android.ui.nowplaying

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.dj.DjController
import com.anyplayer.android.playback.PlaybackEngine
import com.anyplayer.android.playback.PlayerState
import com.anyplayer.android.ui.components.ComputeDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Immutable
data class NowPlayingUi(
    val title: String = "—",
    val artist: String = "—",
    val imageUrl: String? = null,
    /** True while an AI DJ break plays (title shows "AnyPlayer DJ"). */
    val djBreak: Boolean = false,
    val status: PlaybackStateType = PlaybackStateType.IDLE,
    val disabledReason: String? = null,
    val error: String? = null,
    val durationMs: Long = 0,
    /** Position anchor; the ticker re-reads the engine whenever it moves (seek, play/pause, track). */
    val anchorPositionMs: Long = 0,
    val anchorElapsedMs: Long = 0,
    val canSeek: Boolean = false,
    val volume: Int = 100,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val queue: QueueUi = QueueUi(),
) {
    val isPlaying: Boolean get() = status == PlaybackStateType.PLAYING
}

private data class QueueHeader(val track: Track?, val shuffle: Boolean, val repeat: RepeatMode)

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val engine: PlaybackEngine,
    dj: DjController,
    @ComputeDispatcher computeDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /** Rebuilt off-main only when the queue content or cursor (or the DJ hint) changes. */
    private val queueUi = combine(
        engine.queue.distinctUntilChanged { old, new ->
            old.contentVersion == new.contentVersion && old.cursor == new.cursor
        },
        combine(dj.showEntriesInQueue, dj.songsAway, dj.preparationStatus) { show, away, status ->
            DjQueueHint(show, away, status)
        },
    ) { queue, hint -> QueueUiBuilder.build(queue, hint) }
        .flowOn(computeDispatcher)

    private val queueHeader = engine.queue
        .map { QueueHeader(it.current?.track, it.shuffle, it.repeat) }
        .distinctUntilChanged()

    val state: StateFlow<NowPlayingUi> = combine(queueHeader, engine.player, queueUi) { header, player, queue ->
        buildUi(header, player, queue)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingUi())

    private fun buildUi(header: QueueHeader, player: PlayerState, queue: QueueUi): NowPlayingUi {
        val dj = player.activeBreak
        val track = header.track
        return NowPlayingUi(
            title = if (dj != null) DJ_TITLE else track?.title ?: "—",
            artist = if (dj != null) "—" else track?.artist?.ifBlank { null } ?: "—",
            imageUrl = if (dj != null) null else track?.imageUrl,
            djBreak = dj != null,
            status = player.status,
            disabledReason = player.disabledReason,
            error = player.error,
            durationMs = dj?.durationMs ?: player.durationMs,
            anchorPositionMs = player.anchorPositionMs,
            anchorElapsedMs = player.anchorElapsedRealtimeMs,
            canSeek = (dj != null || track != null) && (dj?.durationMs ?: player.durationMs) > 0,
            volume = player.volume,
            shuffle = header.shuffle,
            repeat = header.repeat,
            queue = queue,
        )
    }

    /** Extrapolated position; read by the position ticker, never stored in state. */
    fun positionMs(): Long = engine.positionMs()

    fun togglePlayPause() = engine.togglePlayPause()

    fun next() = engine.next()

    fun previous() = engine.previous()

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)

    fun setVolume(percent: Int) = engine.setVolume(percent.coerceIn(0, 100))

    fun toggleShuffle() = engine.setShuffle(!engine.queue.value.shuffle)

    fun setRepeat(mode: RepeatMode) = engine.setRepeat(mode)

    fun playEntry(uid: Long) = engine.playEntry(uid)
}
