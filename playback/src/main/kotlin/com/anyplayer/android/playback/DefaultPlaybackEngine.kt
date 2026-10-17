package com.anyplayer.android.playback

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.anyplayer.android.core.model.BackendKind
import com.anyplayer.android.core.model.PlanItem
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.QueueOps
import com.anyplayer.android.core.model.QueueState
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.Run
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.planRun
import com.anyplayer.android.feature.playback.service.AnyPlayerMediaLibraryService
import com.anyplayer.android.playback.backend.BackendEvent
import com.anyplayer.android.playback.backend.ExoBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Engine slice: every command applies the new [QueueState] synchronously, then reconciles the
 * backend in one `reconcileJob` that the next command cancels (latest wins). Only the ExoPlayer
 * backend exists so far; a SPOTIFY run surfaces an error instead of playing.
 */
@Singleton
class DefaultPlaybackEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exo: ExoBackend,
) : PlaybackEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val random: Random = Random.Default

    private val _queue = MutableStateFlow(QueueState())
    override val queue: StateFlow<QueueState> = _queue.asStateFlow()

    private val _player = MutableStateFlow(PlayerState())
    override val player: StateFlow<PlayerState> = _player.asStateFlow()

    /** Run currently loaded in [exo] and the queue lists it was planned from. */
    private var loaded: LoadedRun? = null
    private var reconcileJob: Job? = null

    private data class LoadedRun(val run: Run, val planned: QueueState, var ended: Boolean = false)

    init {
        scope.launch { exo.events.collect(::onBackendEvent) }
    }

    override fun positionMs(): Long {
        val p = _player.value
        if (p.status != PlaybackStateType.PLAYING) return p.anchorPositionMs
        val extrapolated = p.anchorPositionMs + (SystemClock.elapsedRealtime() - p.anchorElapsedRealtimeMs)
        return if (p.durationMs > 0) extrapolated.coerceAtMost(p.durationMs) else extrapolated
    }

    override fun setQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean) {
        // User-initiated play from the top with shuffle on starts at a random track.
        _queue.value = QueueOps.replace(_queue.value, tracks, startIndex, random, randomStart = autoPlay)
        reconcile(playWhenReady = autoPlay, positionMs = 0)
    }

    override fun playEntry(uid: Long) {
        val q = QueueOps.jumpToUid(_queue.value, uid)
        if (q === _queue.value) return
        _queue.value = q
        reconcile(playWhenReady = true, positionMs = 0)
    }

    override fun play() {
        val l = loaded
        if (l != null && !l.ended && isLoadedFor(_queue.value, l)) {
            ensureServiceStarted()
            launchReconcile { exo.play() }
        } else {
            reconcile(playWhenReady = true, positionMs = _player.value.anchorPositionMs)
        }
    }

    override fun pause() {
        launchReconcile { exo.pause() }
    }

    override fun togglePlayPause() {
        when (_player.value.status) {
            PlaybackStateType.PLAYING, PlaybackStateType.BUFFERING -> pause()
            else -> play()
        }
    }

    override fun next() {
        val q = QueueOps.advance(_queue.value, wrap = true) ?: return
        _queue.value = q
        reconcile(playWhenReady = true, positionMs = 0)
    }

    override fun previous() {
        if (positionMs() > RESTART_THRESHOLD_MS) return seekTo(0)
        val q = QueueOps.back(_queue.value) ?: return seekTo(0)
        _queue.value = q
        reconcile(playWhenReady = true, positionMs = 0)
    }

    override fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0)
        _player.update { it.copy(anchorPositionMs = target, anchorElapsedRealtimeMs = SystemClock.elapsedRealtime()) }
        launchReconcile { exo.seekTo(target) }
    }

    private fun isLoadedFor(q: QueueState, l: LoadedRun): Boolean =
        l.planned.entries === q.entries && l.planned.order === q.order

    /**
     * Brings the backend to the queue's current entry. Unplayable entries are skipped by
     * [planRun]; with repeat ALL the search wraps to the start once.
     */
    private fun reconcile(playWhenReady: Boolean, positionMs: Long) {
        var q = _queue.value
        val requested = q.current
        val run = planRun(q, q.cursor, null)
            ?: if (q.repeat == RepeatMode.ALL) planRun(q, 0, null) else null
        val first = run?.items?.firstNotNullOfOrNull { it as? PlanItem.Song }
        if (first != null && first.orderPos != q.cursor) q = QueueOps.jumpToUid(q, first.entry.uid)
        _queue.value = q
        val skippedError = requested?.takeIf { it.uid != first?.entry?.uid }?.let { "Can't play \"${it.track.title}\"" }

        if (run == null || first == null || run.kind != BackendKind.EXO) {
            // No playable entry, or a SPOTIFY run: the Spotify backend is not in this slice.
            loaded = null
            _player.value = PlayerState(
                status = if (requested == null) PlaybackStateType.IDLE else PlaybackStateType.ERROR,
                currentUid = q.current?.uid,
                error = when {
                    requested == null -> null
                    run?.kind == BackendKind.SPOTIFY -> "Spotify playback is not available in this build"
                    else -> skippedError ?: "Nothing playable in the queue"
                },
                volume = _player.value.volume,
                normalization = _player.value.normalization,
            )
            launchReconcile { exo.stop() }
            return
        }
        val current = first.entry
        _player.update {
            it.copy(
                currentUid = current.uid,
                durationMs = current.track.durationMs ?: 0,
                anchorPositionMs = positionMs,
                anchorElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                status = if (playWhenReady) PlaybackStateType.BUFFERING else PlaybackStateType.PAUSED,
                error = skippedError,
            )
        }
        if (playWhenReady) ensureServiceStarted()

        val l = loaded
        val indexInLoaded = l?.takeIf { !it.ended && isLoadedFor(q, it) }?.run?.items?.indexOfFirst {
            it is PlanItem.Song && it.orderPos == q.cursor
        } ?: -1
        if (indexInLoaded >= 0) {
            launchReconcile {
                exo.seekToItem(indexInLoaded, positionMs)
                if (playWhenReady) exo.play() else exo.pause()
            }
        } else {
            loaded = LoadedRun(run, q)
            launchReconcile { exo.load(run, 0, positionMs, playWhenReady) }
        }
    }

    private fun launchReconcile(block: suspend () -> Unit) {
        reconcileJob?.cancel()
        reconcileJob = scope.launch { block() }
    }

    private fun onBackendEvent(event: BackendEvent) {
        val l = loaded ?: return
        when (event) {
            is BackendEvent.ItemStarted -> {
                val song = l.run.items.getOrNull(event.indexInRun) as? PlanItem.Song ?: return
                val q = _queue.value
                if (q.cursor != song.orderPos && isLoadedFor(q, l)) {
                    _queue.value = QueueOps.jumpToUid(q, song.entry.uid)
                }
                _player.update {
                    it.copy(
                        currentUid = song.entry.uid,
                        durationMs = exo.durationMs().takeIf { d -> d > 0 } ?: (song.entry.track.durationMs ?: 0),
                        anchorPositionMs = exo.positionMs(),
                        anchorElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    )
                }
            }
            is BackendEvent.PlayingChanged -> _player.update {
                it.copy(
                    status = when {
                        event.isPlaying -> PlaybackStateType.PLAYING
                        event.buffering -> PlaybackStateType.BUFFERING
                        it.status == PlaybackStateType.ERROR -> PlaybackStateType.ERROR
                        else -> PlaybackStateType.PAUSED
                    },
                    durationMs = exo.durationMs().takeIf { d -> d > 0 } ?: it.durationMs,
                    anchorPositionMs = exo.positionMs(),
                    anchorElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    error = if (event.isPlaying) null else it.error,
                )
            }
            BackendEvent.PositionDiscontinuity -> _player.update {
                it.copy(anchorPositionMs = exo.positionMs(), anchorElapsedRealtimeMs = SystemClock.elapsedRealtime())
            }
            BackendEvent.RunEnded -> onRunEnded(l)
            is BackendEvent.Error -> {
                Log.w(TAG, "Exo error: ${event.message}")
                _player.update { it.copy(status = PlaybackStateType.ERROR, error = event.message) }
            }
        }
    }

    private fun onRunEnded(l: LoadedRun) {
        l.ended = true
        val lastSong = l.run.items.lastOrNull { it is PlanItem.Song } as? PlanItem.Song ?: return
        val q = _queue.value
        val atLast = if (q.cursor == lastSong.orderPos) q else QueueOps.jumpToUid(q, lastSong.entry.uid)
        val next = QueueOps.advance(atLast, wrap = true)
        if (next == null) {
            _queue.value = atLast
            _player.update {
                it.copy(
                    status = PlaybackStateType.PAUSED,
                    anchorPositionMs = 0,
                    anchorElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                )
            }
            return
        }
        _queue.value = next
        reconcile(playWhenReady = true, positionMs = 0)
    }

    /**
     * Starts the media service so Media3 can promote it to foreground with the notification.
     * Only called from user commands, while the app is in the foreground.
     */
    private fun ensureServiceStarted() {
        try {
            context.startService(Intent(context, AnyPlayerMediaLibraryService::class.java))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not start playback service", e)
        }
    }

    private companion object {
        const val TAG = "PlaybackEngine"
        const val RESTART_THRESHOLD_MS = 3_000L
    }
}
