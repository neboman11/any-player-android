package com.anyplayer.android.playback

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.AuthRepository
import com.anyplayer.android.data.settings.PlayerSettingsStore
import com.anyplayer.android.feature.playback.service.AnyPlayerMediaLibraryService
import com.anyplayer.android.playback.backend.*
import com.anyplayer.android.playback.persistence.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Main-confined intent reducer with a single, non-cancelling backend command consumer. */
@Singleton
class DefaultPlaybackEngine internal constructor(
    private val backends: Map<BackendKind, PlaybackBackend>,
    private val auth: AuthRepository,
    private val persistence: PlaybackPersistence,
    private val settings: PlayerSettingsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val startService: () -> Unit,
) : PlaybackEngine {
    @Inject constructor(
        @ApplicationContext context: Context,
        backends: Map<BackendKind, @JvmSuppressWildcards PlaybackBackend>,
        auth: AuthRepository,
        persistence: RoomPlaybackPersistence,
        settings: PlayerSettingsStore,
    ) : this(backends, auth, persistence, settings,
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), SystemClock::elapsedRealtime,
        { if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            try { context.startService(Intent(context, AnyPlayerMediaLibraryService::class.java)) }
            catch (_: IllegalStateException) { /* Media3 reports a resumable notification if FGS is denied. */ }
        } })

    private val mutableQueue = MutableStateFlow(QueueState())
    override val queue = mutableQueue.asStateFlow()
    private val mutablePlayer = MutableStateFlow(PlayerState())
    override val player = mutablePlayer.asStateFlow()
    private val mutableEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 128)
    override val events = mutableEvents.asSharedFlow()
    private val mutableChanges = MutableSharedFlow<EngineChange>(extraBufferCapacity = 128)
    override val changes = mutableChanges.asSharedFlow()
    // Mutation delivery cannot drop when a sync push or DJ consumer is temporarily slow.
    private val eventMessages = Channel<EngineEvent>(Channel.UNLIMITED)
    private val changeMessages = Channel<EngineChange>(Channel.UNLIMITED)
    private val restored = CompletableDeferred<Unit>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val cursors = Channel<QueueState>(Channel.CONFLATED)
    private var lastCursorVersion = -1L
    private var lastCursorUid: Long? = null
    private var uidMapVersion = -1L
    private var uidPositions: Map<Long, Int> = emptyMap()
    private var generation = 0L
    private var revision = 0L
    private var active: Applied? = null
    private var pending: PendingBreak? = null
    private var breakTarget: Long? = null
    private var breakItem: PendingBreak? = null
    private var seekSerial = 0L
    private var seekPosition = 0L
    private var strict = false
    private var lastGateUid: Long? = null
    private var contentWrite: Job? = null
    private var periodicSave: Job? = null
    private var prewarm: Job? = null
    private var warmedUid: Long? = null
    private data class Applied(val id: Long, val backend: PlaybackBackend, var run: Run,
        var key: ItemKey, var playing: Boolean, var seek: Long, var volume: Int = -1, var repeat: Boolean = false,
        var ended: Boolean = false)

    init {
        backends.values.forEach { backend -> scope.launch { backend.events.collect { event -> onBackendEvent(backend, event) } } }
        scope.launch {
            try {
                val saved = persistence.restore()
                val flags = settings.awaitLoaded()
                mutableQueue.value = saved.copy(shuffle = flags.shuffle, repeat = flags.repeat)
                mutablePlayer.value = PlayerState(status = if (saved.current == null) PlaybackStateType.IDLE else PlaybackStateType.PAUSED,
                    currentUid = saved.current?.uid, durationMs = saved.current?.track?.durationMs ?: 0,
                    anchorPositionMs = flags.positionMs, anchorElapsedRealtimeMs = clock(), volume = flags.volume,
                    normalization = flags.normalizationEnabled)
                strict = flags.normalizationStrict
                seekPosition = flags.positionMs
                changed(ChangeOrigin.RESTORE)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutablePlayer.value = mutablePlayer.value.copy(error = "Playback restore failed: ${e.message}")
                changed(ChangeOrigin.RESTORE)
            } finally { restored.complete(Unit) }
            auth.profiles.collect { enforceGate(); requestReconcile() }
        }
        scope.launch {
            restored.await()
            for (ignored in wake) {
                try { reconcile() } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    mutablePlayer.value = mutablePlayer.value.copy(status = PlaybackStateType.ERROR,
                        playRequested = false, error = e.message ?: "Playback failed")
                    changed(ChangeOrigin.USER)
                }
            }
        }
        scope.launch {
            restored.await()
            for (snapshot in cursors) persistence.writeCursor(snapshot)
        }
        scope.launch { for (event in eventMessages) mutableEvents.emit(event) }
        scope.launch { for (change in changeMessages) mutableChanges.emit(change) }
    }

    override suspend fun awaitRestored() = restored.await()
    override fun positionMs(): Long {
        val p = player.value
        val position = p.anchorPositionMs + if (p.status == PlaybackStateType.PLAYING) (clock() - p.anchorElapsedRealtimeMs).coerceAtLeast(0) else 0
        return if (p.durationMs > 0) position.coerceAtMost(p.durationMs) else position
    }
    private fun command(block: () -> Unit) { scope.launch { restored.await(); block() } }
    private fun requestReconcile() { revision++; wake.trySend(Unit) }
    private fun orderPosition(uid: Long): Int {
        val q = queue.value
        if (uidMapVersion != q.contentVersion) {
            uidPositions = q.order.mapIndexed { position, index -> q.entries[index].uid to position }.toMap()
            uidMapVersion = q.contentVersion
        }
        return uidPositions[uid] ?: -1
    }
    private fun invalidateLoad() { generation++; seekSerial++; requestReconcile() }
    private fun changed(origin: ChangeOrigin, content: Boolean = false) {
        changeMessages.trySend(EngineChange(origin, content))
        if (origin != ChangeOrigin.RESTORE) persist(content)
        periodicSave?.cancel()
        periodicSave = if (player.value.playRequested) scope.launch {
            while (isActive) { delay(15_000); saveFlags() }
        } else null
        schedulePrewarm()
    }
    private fun saveFlags() {
        val q = queue.value; val p = player.value; val position = positionMs()
        settings.update { it.copy(shuffle = q.shuffle, repeat = q.repeat, volume = p.volume,
            positionMs = position, normalizationEnabled = p.normalization, normalizationStrict = strict) }
    }
    private fun schedulePrewarm() {
        prewarm?.cancel()
        if (!player.value.playRequested || active?.backend?.kind != BackendKind.EXO) return
        val preparer = backends[BackendKind.SPOTIFY] as? BackendPreparer ?: return
        val q = queue.value
        val run = planRun(q, q.cursor, pending) ?: return
        val last = run.items.filterIsInstance<PlanItem.Song>().lastOrNull() ?: return
        val next = planRun(q, last.orderPos + 1, null) ?: return
        if (next.kind != BackendKind.SPOTIFY) return
        val target = (next.items.firstOrNull() as? PlanItem.Song)?.entry ?: return
        if (target.uid == warmedUid) return
        val untilBoundary = (player.value.durationMs - positionMs()).coerceAtLeast(0) +
            run.items.filterIsInstance<PlanItem.Song>().drop(1).sumOf { it.entry.track.durationMs ?: 0 }
        prewarm = scope.launch {
            delay((untilBoundary - 20_000).coerceAtLeast(0))
            warmedUid = target.uid
            scope.launch { preparer.prepare() }
        }
    }
    private fun persist(content: Boolean) {
        saveFlags()
        if (content) {
            contentWrite?.cancel()
            contentWrite = scope.launch {
                delay(500)
                // Once started, commit is never cancelled by a subsequent edit.
                val snapshot = queue.value
                withContext(NonCancellable) { persistence.writeContent(snapshot) }
                cursors.trySend(queue.value)
            }
        } else {
            val snapshot = queue.value
            if (snapshot.contentVersion != lastCursorVersion || snapshot.current?.uid != lastCursorUid) {
                lastCursorVersion = snapshot.contentVersion
                lastCursorUid = snapshot.current?.uid
                cursors.trySend(snapshot)
            }
        }
    }
    private fun anchor(position: Long = positionMs(), duration: Long = player.value.durationMs) {
        mutablePlayer.value = player.value.copy(anchorPositionMs = position.coerceAtLeast(0),
            anchorElapsedRealtimeMs = clock(), durationMs = duration.coerceAtLeast(0))
    }
    private fun gateReason(): String? {
        val source = queue.value.current?.track?.source ?: return null
        val connected = try { auth.isSourceConnected(source) } catch (_: Exception) { true }
        return if (connected) null else "Playback disabled: ${source.name.lowercase()} is not configured/authenticated. Reconnect it in Settings. Next/Previous still works."
    }
    private fun enforceGate(): Boolean {
        val reason = gateReason(); val p = player.value; val uid = queue.value.current?.uid
        if (reason == null) lastGateUid = null
        val pause = reason != null && p.playRequested && lastGateUid != uid
        if (pause) { lastGateUid = uid; anchor(); }
        if (p.disabledReason != reason || pause) {
            mutablePlayer.value = player.value.copy(disabledReason = reason,
                playRequested = if (pause) false else player.value.playRequested,
                status = if (pause) PlaybackStateType.PAUSED else player.value.status)
            changed(ChangeOrigin.USER)
        }
        return reason == null
    }
    private fun transport(play: Boolean, origin: ChangeOrigin) {
        val allowed = enforceGate()
        if (play && allowed && active?.ended == true) {
            seekPosition = 0
            anchor(0)
            invalidateLoad()
        }
        anchor()
        mutablePlayer.value = player.value.copy(playRequested = play && allowed,
            status = if (play && allowed) PlaybackStateType.BUFFERING else if (queue.value.current == null) PlaybackStateType.IDLE else PlaybackStateType.PAUSED,
            error = if (player.value.status == PlaybackStateType.ERROR) null else player.value.error)
        if (play && allowed) startService()
        requestReconcile(); changed(origin)
    }
    override fun setQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean, origin: ChangeOrigin) = command {
        dropBreak()
        mutableQueue.value = QueueOps.replace(queue.value, tracks, startIndex, Random.Default, autoPlay && origin == ChangeOrigin.USER)
        eventMessages.trySend(EngineEvent.QueueReplaced)
        selectCurrent(0); invalidateLoad(); transport(autoPlay, origin); changed(origin, true)
    }
    override fun playNext(track: Track) = command {
        mutableQueue.value = QueueOps.playNext(queue.value, track)
        if (player.value.currentUid == null) selectCurrent(0)
        validateBreak(); requestReconcile(); changed(ChangeOrigin.USER, true)
    }
    override fun playEntry(uid: Long) = command {
        val position = orderPosition(uid)
        if (position >= 0) {
            finishBreak()
            mutableQueue.value = queue.value.copy(cursor = position, playNextOffset = 0)
            validateBreak(); selectCurrent(0); invalidateLoad(); transport(true, ChangeOrigin.USER)
        }
    }
    override fun play(origin: ChangeOrigin) = command { transport(true, origin) }
    override fun pause(origin: ChangeOrigin) = command { transport(false, origin) }
    override fun togglePlayPause() = command { transport(!player.value.playRequested, ChangeOrigin.USER) }
    override fun next() = command { advance(true) }
    override fun previous() = command {
        if (breakItem != null) { dropBreak(); selectCurrent(0); invalidateLoad(); transport(true, ChangeOrigin.USER) }
        else if (positionMs() > 3000) seek(0, ChangeOrigin.USER)
        else {
            val q = queue.value
            val position = (q.cursor - 1 downTo 0).firstOrNull {
                backendFor(q.entryAt(it).track) != BackendKind.UNPLAYABLE
            }
            if (position == null) seek(0, ChangeOrigin.USER)
            else {
                mutableQueue.value = q.copy(cursor = position, playNextOffset = 0)
                validateBreak(); selectCurrent(0); invalidateLoad(); transport(true, ChangeOrigin.USER)
            }
        }
    }
    private fun advance(manual: Boolean) {
        val intent = manual || player.value.playRequested
        val target = if (breakItem != null) breakTarget?.let { QueueOps.jumpToUid(queue.value, it) }
            else QueueOps.advance(queue.value, true)?.let { next ->
                val run = planRun(next, next.cursor, null) ?: if (next.repeat == RepeatMode.ALL) planRun(next, 0, null) else null
                val song = run?.items?.filterIsInstance<PlanItem.Song>()?.firstOrNull()
                song?.let { QueueOps.jumpToUid(next, it.entry.uid) }
            }
        if (breakItem != null) finishBreak()
        else if (target != null && pending != null && target.current?.uid == pending?.beforeUid && target.cursor > queue.value.cursor) {
            breakItem = pending; pending = null; breakTarget = target.current?.uid
            mutablePlayer.value = player.value.copy(activeBreak = ActiveBreak(breakItem!!.breakId, 0),
                anchorPositionMs = 0, anchorElapsedRealtimeMs = clock(), durationMs = 0)
            seekPosition = 0; invalidateLoad(); transport(intent, ChangeOrigin.USER); return
        }
        if (target == null) {
            if (manual) return
            anchor(active?.backend?.positionMs() ?: positionMs())
            mutablePlayer.value = player.value.copy(playRequested = false, status = PlaybackStateType.PAUSED)
            active?.ended = true
            requestReconcile(); changed(ChangeOrigin.USER); return
        }
        mutableQueue.value = target
        validateBreak(); selectCurrent(0); invalidateLoad(); transport(intent, ChangeOrigin.USER)
    }
    private fun selectCurrent(position: Long) {
        val q = queue.value; val planned = planRun(q, q.cursor, null)
        val first = planned?.items?.filterIsInstance<PlanItem.Song>()?.firstOrNull()
        if (first != null && first.entry.uid != q.current?.uid) mutableQueue.value = QueueOps.jumpToUid(q, first.entry.uid)
        val current = queue.value.current
        seekPosition = position
        mutablePlayer.value = player.value.copy(currentUid = current?.uid, activeBreak = null,
            anchorPositionMs = position, anchorElapsedRealtimeMs = clock(), durationMs = current?.track?.durationMs ?: 0,
            error = when {
                q.current != null && first == null -> "Nothing playable in the queue"
                q.current != null && first?.entry?.uid != q.current?.uid -> "Can't play \"${q.current?.track?.title}\""
                else -> null
            })
    }
    override fun seekTo(ms: Long, origin: ChangeOrigin) = command { seek(ms, origin) }
    private fun seek(ms: Long, origin: ChangeOrigin) {
        seekPosition = ms.coerceAtLeast(0).let { if (player.value.durationMs > 0) it.coerceAtMost(player.value.durationMs) else it }
        anchor(seekPosition); seekSerial++; requestReconcile(); changed(origin)
    }
    override fun setVolume(percent: Int, origin: ChangeOrigin) = command {
        mutablePlayer.value = player.value.copy(volume = percent.coerceIn(0, 100)); requestReconcile(); changed(origin)
    }
    override fun setShuffle(on: Boolean, origin: ChangeOrigin) = command {
        val next = QueueOps.setShuffle(queue.value, on, Random.Default)
        if (next != queue.value) { mutableQueue.value = next; validateBreak(); requestReconcile(); changed(origin, true) }
    }
    override fun setRepeat(mode: RepeatMode, origin: ChangeOrigin) = command {
        mutableQueue.value = QueueOps.setRepeat(queue.value, mode); requestReconcile(); changed(origin)
    }
    override fun setNormalization(enabled: Boolean, strict: Boolean) = command {
        this.strict = strict; mutablePlayer.value = player.value.copy(normalization = enabled)
        requestReconcile(); changed(ChangeOrigin.USER)
    }
    override fun setPendingBreak(pendingBreak: PendingBreak?) = command {
        if (pending?.breakId != pendingBreak?.breakId) pending?.let { eventMessages.trySend(EngineEvent.BreakDropped(it.breakId)) }
        pending = pendingBreak
        validateBreak(); requestReconcile(); changed(ChangeOrigin.USER)
    }
    private fun validateBreak() {
        pending?.let { b ->
            val pos = orderPosition(b.beforeUid)
            if (pos <= queue.value.cursor || backendFor(queue.value.entryAt(pos).track) == BackendKind.UNPLAYABLE) {
                pending = null; eventMessages.trySend(EngineEvent.BreakDropped(b.breakId))
            }
        }
    }
    private fun finishBreak() {
        breakItem?.let { eventMessages.trySend(EngineEvent.BreakEnded(it.breakId)) }
        breakItem = null; breakTarget = null
        mutablePlayer.value = player.value.copy(activeBreak = null)
    }
    private fun dropBreak() {
        pending?.let { eventMessages.trySend(EngineEvent.BreakDropped(it.breakId)) }; pending = null
        finishBreak()
    }
    private fun desiredRun(): Run? {
        breakItem?.let { return breakRun(it) }
        return planRun(queue.value, queue.value.cursor, pending)
    }
    private suspend fun reconcile() {
        while (true) {
            val rev = revision
            val run = desiredRun()
            val key = breakItem?.let { ItemKey.Break(it.breakId) } ?: queue.value.current?.let { ItemKey.Song(it.uid) }
            val backend = run?.let { backends[it.kind] }
            val old = active
            if (backend == null || key == null || run == null) {
                active = null
                old?.backend?.stop()
                if (rev != revision) continue
                mutablePlayer.value = player.value.copy(playRequested = false,
                    status = if (queue.value.current == null) PlaybackStateType.IDLE else PlaybackStateType.ERROR,
                    error = when { queue.value.current == null -> null; run?.kind == BackendKind.SPOTIFY -> "Spotify playback is not available in this build"; else -> "Nothing playable in the queue" })
                changed(ChangeOrigin.USER); return
            }
            enforceGate()
            val requested = player.value.playRequested
            val seek = seekSerial
            var applied = active
            if (applied == null || applied.backend !== backend || applied.id != generation || applied.key != key) {
                if (applied?.backend !== backend) applied?.backend?.stop()
                if (rev != revision) continue
                // Restore is local-only: do not load even a paused Spotify run until first play.
                if (!requested && applied == null) return
                val id = ++generation
                applied = Applied(id, backend, run, key, requested, seek)
                active = applied
                val source = if (breakItem != null) SourceType.ALL else queue.value.current?.track?.source ?: SourceType.ALL
                val volume = VolumeNormalization.effective(player.value.volume, source, player.value.normalization)
                val repeat = queue.value.repeat == RepeatMode.ONE && breakItem == null
                backend.setVolume(volume)
                backend.setRepeatOne(repeat)
                applied.volume = volume
                applied.repeat = repeat
                backend.load(id, run, 0, seekPosition, requested)
                if (rev != revision) continue
            } else {
                if (applied.run != run) { backend.updateUpcoming(applied.id, run, key); applied.run = run }
                if (rev != revision) continue
                if (applied.seek != seek) { backend.seekTo(seekPosition); applied.seek = seek }
                if (rev != revision) continue
                if (applied.playing != requested) { if (requested) backend.play() else backend.pause(); applied.playing = requested }
            }
            if (rev != revision) continue
            val source = if (breakItem != null) SourceType.ALL else queue.value.current?.track?.source ?: SourceType.ALL
            val volume = VolumeNormalization.effective(player.value.volume, source, player.value.normalization)
            if (applied.volume != volume) { backend.setVolume(volume); applied.volume = volume }
            val repeat = queue.value.repeat == RepeatMode.ONE && breakItem == null
            if (applied.repeat != repeat) { backend.setRepeatOne(repeat); applied.repeat = repeat }
            if (rev == revision) return
        }
    }
    private fun onBackendEvent(backend: PlaybackBackend, event: BackendEvent) {
        val applied = active ?: return
        if (event.loadId != generation || event.loadId != applied.id || backend !== applied.backend) return
        when (event) {
            is BackendEvent.ItemStarted -> {
                when (val key = event.key) {
                    is ItemKey.Song -> {
                        val pos = orderPosition(key.uid)
                        if (pos < 0) return
                        val sameItem = player.value.currentUid == key.uid
                        val skipped = (queue.value.cursor + 1 until pos).firstOrNull {
                            backendFor(queue.value.entryAt(it).track) == BackendKind.UNPLAYABLE
                        }?.let { "Can't play \"${queue.value.entryAt(it).track.title}\"" }
                        if (breakItem != null) finishBreak()
                        if (queue.value.cursor != pos) mutableQueue.value = queue.value.copy(cursor = pos, playNextOffset = 0)
                        applied.key = key
                        // A skipped-track error is a notice, not a lasting error: in a gapless in-run
                        // transition the replacement is already PLAYING, so none remains.
                        val playing = player.value.status == PlaybackStateType.PLAYING
                        mutablePlayer.value = player.value.copy(currentUid = key.uid, activeBreak = null,
                            error = when { playing -> null; sameItem -> skipped ?: player.value.error; else -> skipped })
                        anchor(backend.positionMs(), backend.durationMs().takeIf { it > 0 } ?: (queue.value.current?.track?.durationMs ?: 0))
                        validateBreak(); eventMessages.trySend(EngineEvent.TrackStarted(key.uid, pos))
                    }
                    is ItemKey.Break -> {
                        if (breakItem == null && pending?.breakId == key.breakId) {
                            breakItem = pending; breakTarget = pending?.beforeUid; pending = null
                        }
                        if (breakItem?.breakId != key.breakId) return
                        val first = player.value.activeBreak == null
                        applied.key = key
                        mutablePlayer.value = player.value.copy(activeBreak = ActiveBreak(key.breakId, backend.durationMs()))
                        anchor(backend.positionMs(), backend.durationMs())
                        if (first || applied.run.items.size == 1) eventMessages.trySend(EngineEvent.BreakStarted(key.breakId))
                    }
                }
                requestReconcile()
            }
            is BackendEvent.PlayingChanged -> {
                anchor(backend.positionMs(), backend.durationMs())
                val intent = if (event.byPlayer) event.isPlaying || event.buffering else player.value.playRequested
                mutablePlayer.value = player.value.copy(playRequested = intent,
                    status = when {
                        player.value.status == PlaybackStateType.ERROR && !event.isPlaying && !event.buffering -> PlaybackStateType.ERROR
                        event.isPlaying -> PlaybackStateType.PLAYING
                        event.buffering || intent -> PlaybackStateType.BUFFERING
                        else -> PlaybackStateType.PAUSED
                    },
                    // Healthy playback supersedes any shown error (e.g. the skipped track's notice).
                    error = if (event.isPlaying) null else player.value.error)
                applied.playing = intent
                enforceGate(); requestReconcile()
            }
            is BackendEvent.PositionDiscontinuity -> anchor(event.positionMs, event.durationMs)
            is BackendEvent.RunEnded -> {
                if (applied.ended) return
                advance(false)
                return
            }
            is BackendEvent.Error -> {
                val hasNext = breakTarget != null || QueueOps.advance(queue.value, true)?.let {
                    planRun(it, it.cursor, null) ?: if (it.repeat == RepeatMode.ALL) planRun(it, 0, null) else null
                } != null
                if (backend.kind == BackendKind.EXO && hasNext) {
                    advance(false)
                } else {
                    applied.ended = true
                    mutablePlayer.value = player.value.copy(status = PlaybackStateType.ERROR, playRequested = false, error = event.message)
                    requestReconcile()
                }
            }
        }
        changed(ChangeOrigin.USER)
    }
}
