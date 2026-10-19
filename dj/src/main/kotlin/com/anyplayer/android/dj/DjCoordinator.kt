package com.anyplayer.android.dj

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.settings.DjSettingsStore
import com.anyplayer.android.data.settings.PlayerSettingsStore
import com.anyplayer.android.playback.EngineEvent
import com.anyplayer.android.playback.PlaybackEngine
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import dagger.hilt.android.qualifiers.ApplicationContext

/** One actor owns scheduling, generation jobs and file ownership. Native and disk work are
 * dispatched away from the actor; queue observations never depend on a playback polling loop. */
@Singleton
class DjCoordinator internal constructor(
    private val context: Context,
    private val engine: PlaybackEngine,
    private val playerSettings: PlayerSettingsStore,
    private val djSettings: DjSettingsStore,
    private val passages: PassageRepository,
    private val remote: RemoteBreakGenerator,
    private val local: OnDeviceBreakGenerator,
    private val diagnostics: DjDiagnostics,
    private val scope: CoroutineScope,
) : ComponentCallbacks2 {
    @Inject constructor(
        @ApplicationContext context: Context, engine: PlaybackEngine, playerSettings: PlayerSettingsStore,
        djSettings: DjSettingsStore, passages: PassageRepository, remote: RemoteBreakGenerator,
        local: OnDeviceBreakGenerator, diagnostics: DjDiagnostics,
    ) : this(context, engine, playerSettings, djSettings, passages, remote, local, diagnostics,
        CoroutineScope(SupervisorJob() +
            Executors.newSingleThreadExecutor { Thread(it, "AnyPlayer-DJ-schedule") }.asCoroutineDispatcher() +
            CoroutineExceptionHandler { _, error -> diagnostics.log("DjCoordinator", "DJ operation failed", error) }))
    private val mutableAway = MutableStateFlow<Int?>(null)
    val songsAway = mutableAway.asStateFlow()
    private val mutablePreparation = MutableStateFlow(PreparationStatus.NOT_STARTED)
    val preparationStatus = mutablePreparation.asStateFlow()
    private var schedule = BreakSchedule()
    private var generation: Job? = null
    private var epoch = 0L
    private var pending: Delivery? = null
    private val deliveries = mutableMapOf<String, Delivery>()
    private data class Delivery(val breakId: String, val targetUid: Long, val passages: Passages)
    private val audio = DjAudioFiles()
    private val root get() = File(context.filesDir, "dj_filler")
    init {
        context.registerComponentCallbacks(this)
        scope.launch {
            withContext(Dispatchers.IO) {
                context.deleteSharedPreferences("dj_fact_repository")
                root.mkdirs()
                root.walkBottomUp().filter { it.isFile && (it.extension in setOf("wav", "part") || it.name in setOf(
                    "ready-track-id", "ready-passages.json", "ready-fact.json")) }.forEach(File::delete)
            }
            engine.awaitRestored()
            launch {
                playerSettings.settings.map { it.aiDjEnabled }.distinctUntilChanged().collect { enabled ->
                    transition(ScheduleEvent.Enabled(enabled))
                    if (!enabled) { cancelGeneration(); engine.setPendingBreak(null); pending = null; clearFinished(); local.release() }
                    else prepare()
                }
            }
            launch {
                engine.queue.map { it.cursor to it.contentVersion }.distinctUntilChanged().collect {
                    transition(ScheduleEvent.CursorMoved); prepare()
                }
            }
            launch { engine.events.collect(::onEvent) }
            launch {
                engine.player.map { it.playRequested }.distinctUntilChanged().collectLatest { playing ->
                    if (!playing) { delay(10 * 60_000L); local.release() }
                }
            }
            launch {
                djSettings.settings.map { it.remoteGeneration }.distinctUntilChanged().collect { if (it) local.release() }
            }
        }
    }
    private fun transition(event: ScheduleEvent) {
        schedule = reduce(schedule, event, engine.queue.value, Random.Default)
        mutableAway.value = schedule.songsAway(engine.queue.value)
    }
    private suspend fun onEvent(event: EngineEvent) {
        when (event) {
            EngineEvent.QueueReplaced -> {
                cancelGeneration(); engine.setPendingBreak(null); pending = null; clearFinished()
                transition(ScheduleEvent.QueueReplaced); prepare()
            }
            is EngineEvent.BreakStarted -> {
                val delivered = deliveries[event.breakId]
                withContext(Dispatchers.IO) { audio.started(event.breakId) }
                if (pending?.breakId == event.breakId) pending = null
                if (delivered != null) scope.launch(Dispatchers.IO) { passages.recordPlayed(delivered.passages) }
                transition(ScheduleEvent.BreakStarted); prepare()
            }
            is EngineEvent.BreakEnded -> deleteDelivery(event.breakId)
            is EngineEvent.BreakDropped -> {
                val wasPending = pending?.breakId == event.breakId
                if (!withContext(Dispatchers.IO) { audio.dropped(event.breakId) }) return
                deliveries.remove(event.breakId)
                if (wasPending) pending = null
                if (wasPending && schedule.songsUntilBreak == 0) transition(ScheduleEvent.GenerationFailed)
                prepare()
            }
            is EngineEvent.TrackStarted -> Unit // queue cursor is the single counting source
        }
    }
    private suspend fun prepare() {
        if (!schedule.enabled) return
        val q = engine.queue.value
        val target = schedule.targetUid?.let { uid -> q.entries.firstOrNull { it.uid == uid } }
        if (pending?.targetUid != target?.uid) {
            engine.setPendingBreak(null); pending = null
            mutablePreparation.value = PreparationStatus.NOT_STARTED
        }
        if (target == null || schedule.failedOnCurrent || pending?.targetUid == target.uid || generation?.isActive == true) return
        val startedEpoch = epoch
        mutablePreparation.value = PreparationStatus.PROCESSING
        generation = scope.launch {
            var output: File? = null
            var undelivered: File? = null
            try {
                val sourced = passages.findPassages(target.track)
                if (sourced == null) {
                    withContext(Dispatchers.IO) { audio.forgetTrack(target.track.id) }
                    failIfCurrent(target.uid); return@launch
                }
                val key = AudioKey(target.track.id, sourced.chunkIds)
                var cached = audio.cached(key)
                if (cached == null) {
                    output = withContext(Dispatchers.IO) { File(root, "cache/${UUID.randomUUID()}.wav").also { it.parentFile?.mkdirs() } }
                    val success = if (djSettings.awaitLoaded().remoteGeneration) remote.generate(target.track, sourced, output!!)
                        else local.generate(target.track, sourced, output!!)
                    if (!success) { failIfCurrent(target.uid); return@launch }
                    cached = CachedAudio(output!!)
                    if (startedEpoch != epoch) return@launch
                    withContext(Dispatchers.IO) { audio.remember(key, cached) }
                    output = null
                }
                if (startedEpoch == epoch && schedule.enabled && schedule.targetUid == target.uid) {
                    val breakId = UUID.randomUUID().toString()
                    val deliveryFile = File(root, "$breakId.wav")
                    undelivered = deliveryFile
                    withContext(Dispatchers.IO) { cached.file.copyTo(deliveryFile) }
                    if (startedEpoch != epoch || !schedule.enabled || schedule.targetUid != target.uid) {
                        withContext(Dispatchers.IO) { deliveryFile.delete() }
                        undelivered = null
                        return@launch
                    }
                    val delivery = Delivery(breakId, target.uid, sourced)
                    deliveries[breakId] = delivery; pending = delivery
                    audio.deliver(breakId, key, deliveryFile)
                    undelivered = null
                    engine.setPendingBreak(PendingBreak(target.uid, breakId, deliveryFile.toURI().toString()))
                    mutablePreparation.value = PreparationStatus.READY
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                diagnostics.log("DjCoordinator", "Break generation failed", e); failIfCurrent(target.uid)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { output?.delete(); undelivered?.delete() }
                if (startedEpoch == epoch) {
                    generation = null
                    // Let a changed target use the completed work without cancelling native/server inference.
                    if (schedule.targetUid != target.uid) scope.launch { prepare() }
                }
            }
        }
    }
    private fun failIfCurrent(uid: Long) {
        if (schedule.targetUid == uid) {
            transition(ScheduleEvent.GenerationFailed)
            mutablePreparation.value = PreparationStatus.NOT_STARTED
        }
    }
    private fun cancelGeneration() { epoch++; generation?.cancel(); generation = null; mutablePreparation.value = PreparationStatus.NOT_STARTED }
    private suspend fun deleteDelivery(id: String) {
        if (deliveries.remove(id) == null) return
        if (pending?.breakId == id) pending = null
        withContext(Dispatchers.IO) { audio.ended(id) }
    }
    private suspend fun clearFinished() {
        withContext(Dispatchers.IO) { audio.clearFinished() }
        // Started delivery files belong to the engine until BreakEnded.
    }
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) scope.launch { local.release() }
    }
    override fun onLowMemory() { scope.launch { local.release() } }
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
}
