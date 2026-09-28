package com.anyplayer.android.feature.djfiller

import androidx.annotation.MainThread
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.djfiller.metadata.DjPassageRepository
import com.anyplayer.android.feature.djfiller.model.DjModelDownloadState
import com.anyplayer.android.feature.djfiller.model.DjFillerPreparationStatus
import com.anyplayer.android.feature.djfiller.model.PreparedFiller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps one AI DJ voice-over ready for the next scheduled break.
 * Generation starts on the first playback update after launch or after the previous
 * voice-over has finished. [consumeReadyFillerIfDue] never blocks a track transition.
 */
@Singleton
class DjFillerScheduler @Inject constructor(
    private val djScriptGenerator: DjScriptGenerator,
    private val djVoiceSynthesizer: DjVoiceSynthesizer,
    private val djPassageRepository: DjPassageRepository,
    private val djFillerAudioCache: DjFillerAudioCache,
    private val djInterstitialPlayer: DjInterstitialPlayer
) {
    // Local/provider-streamed mode has no app-level "about to advance" hook to pull a
    // ready filler from (ExoPlayer auto-advances its whole preloaded queue with no call
    // site to intercept) - it must instead be pushed into the live timeline the instant
    // generation finishes. Spotify/Mixed modes explicitly drive every transition, so they
    // pull via consumeReadyFillerIfDue() at that exact point instead. PlaybackQueueManager
    // wires this once at startup since it alone knows which mode is active.
    private var isLocalModeActive: () -> Boolean = { false }

    fun configureLocalModeProvider(provider: () -> Boolean) {
        isLocalModeActive = provider
    }
    private companion object {
        const val TAG = "DjFillerScheduler"
    }

    @Volatile
    private var enabled = false

    @Volatile
    private var lastSeenTrackId: String? = null

    @Volatile
    private var lastSeenPositionMs: Long = -1L

    // Paired with lastSeenTrackId: a queue/playlist can contain the same track id more than
    // once, so re-deriving "where was that track" via indexOfFirst on a later tick can match
    // the wrong occurrence. Tracking the actual resolved position lets later lookups search
    // near it instead.
    @Volatile
    private var lastSeenIndex: Int = -1

    @Volatile
    private var expectedNextTrackId: String? = null

    private data class PendingFiller(
        val filler: PreparedFiller,
        val forTrackId: String
    )

    @Volatile
    private var pendingFiller: PendingFiller? = null
    private var localFillerInserted = false
    private var breakCommitted = false
    private var failedOnCurrentTrack = false
    private var lastObservedStatus: PlaybackStatus? = null

    private val generationVersion = AtomicLong(0L)

    // Guards generationVersion + pendingFiller mutations against the TOCTOU race between a
    // generation coroutine (runs on a Dispatchers.Default thread) finishing right as
    // setEnabled(false) on the main thread invalidates it - without this, a coroutine
    // that passed its version check just before invalidation could still stash a stale
    // pendingFiller afterward.
    private val stateLock = Any()

    private var generationJob: Job? = null
    private lateinit var scope: CoroutineScope

    init {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    constructor(
        djScriptGenerator: DjScriptGenerator,
        djVoiceSynthesizer: DjVoiceSynthesizer,
        djPassageRepository: DjPassageRepository,
        djFillerAudioCache: DjFillerAudioCache,
        djInterstitialPlayer: DjInterstitialPlayer,
        schedulerDispatcher: CoroutineDispatcher
    ) : this(
        djScriptGenerator,
        djVoiceSynthesizer,
        djPassageRepository,
        djFillerAudioCache,
        djInterstitialPlayer
    ) {
        scope = CoroutineScope(SupervisorJob() + schedulerDispatcher)
    }

    init {
        djInterstitialPlayer.onFillerStarted = { passages ->
            runCatching { djPassageRepository.recordPlayed(passages) }
                .onFailure { CompatLog.e(TAG, "could not save played DJ passages", it) }
            synchronized(stateLock) {
                invalidateGeneration()
                discardPendingFiller()
                breakCommitted = false
                failedOnCurrentTrack = false
                if (enabled) {
                    scheduleAfterPlayedBreak()
                    lastObservedStatus?.let(::prepareScheduledFiller)
                }
            }
        }
    }


    private val mutablePendingBreakSongsAway = MutableStateFlow<Int?>(null)
    private val mutablePreparationStatus = MutableStateFlow(DjFillerPreparationStatus.NOT_STARTED)
    val preparationStatus: StateFlow<DjFillerPreparationStatus> = mutablePreparationStatus
    private var songsUntilBreak = 0

    /** How many more real songs will play before the next break, so the "up next" queue
     *  display can always show exactly where [AI_DJ_PRESENTATION_TRACK] will land (0 = right
     *  after the current track) - this reflects the *schedule*, not whether generation has
     *  actually finished, so it's visible the moment a threshold is rolled rather than
     *  popping in unpredictably once content happens to be ready. Null while disabled. */
    val pendingBreakSongsAway: StateFlow<Int?> = mutablePendingBreakSongsAway

    private fun updatePendingBreakOffset() {
        mutablePendingBreakSongsAway.value = if (enabled) songsUntilBreak else null
    }

    val voiceModelDownloadState: StateFlow<DjModelDownloadState> = djVoiceSynthesizer.downloadState
    val voiceCatalogState: StateFlow<DjVoiceState> = djVoiceSynthesizer.voiceState
    val voiceGain: StateFlow<Float> = djVoiceSynthesizer.voiceGain
    val voiceGainRange: ClosedFloatingPointRange<Float> = djVoiceSynthesizer.voiceGainRange

    fun refreshVoiceCatalog() = djVoiceSynthesizer.refreshVoiceCatalog()

    fun selectVoice(id: String) = djVoiceSynthesizer.selectVoice(id)

    fun setVoiceGain(gain: Float) = djVoiceSynthesizer.setVoiceGain(gain)

    /** Only ever meant to be called from an explicit user button tap in Settings. */
    fun downloadVoiceModel() = djVoiceSynthesizer.downloadSelectedVoice()

    private fun resetSchedulingState() {
        songsUntilBreak = 3
        mutablePreparationStatus.value = DjFillerPreparationStatus.NOT_STARTED
        updatePendingBreakOffset()
    }

    private fun scheduleAfterPlayedBreak() {
        songsUntilBreak = Random.nextInt(3, 6)
        updatePendingBreakOffset()
    }

    private fun resetSeenTrackState() {
        lastSeenTrackId = null
        lastSeenPositionMs = -1L
        lastSeenIndex = -1
        expectedNextTrackId = null
        lastObservedStatus = null
        breakCommitted = false
        failedOnCurrentTrack = false
        localFillerInserted = false
    }

    private fun discardPendingFiller() {
        if (!djInterstitialPlayer.isPlayingInterstitial) {
            pendingFiller?.filler?.audioFile?.let(djFillerAudioCache::delete)
        }
        pendingFiller = null
        localFillerInserted = false
    }

    private fun invalidateGeneration() {
        generationVersion.incrementAndGet()
        generationJob?.cancel()
        generationJob = null
        mutablePreparationStatus.value = DjFillerPreparationStatus.NOT_STARTED
    }

    @MainThread
    fun setEnabled(value: Boolean) {
        synchronized(stateLock) {
            if (value && !enabled) songsUntilBreak = Random.nextInt(3, 6)
            enabled = value
            if (!value) {
                invalidateGeneration()
                djInterstitialPlayer.cancelPendingLocal()
                discardPendingFiller()
                if (!djInterstitialPlayer.isPlayingInterstitial) djFillerAudioCache.clear()
                resetSchedulingState()
                resetSeenTrackState()
            }
        }
        updatePendingBreakOffset()
    }

    @MainThread
    fun onQueueReplaced() {
        synchronized(stateLock) {
            invalidateGeneration()
            djInterstitialPlayer.onLocalInterstitialEnded = null
            djInterstitialPlayer.cancelPendingLocal()
            discardPendingFiller()
            resetSeenTrackState()
            if (enabled) songsUntilBreak = Random.nextInt(3, 6)
        }
        updatePendingBreakOffset()
    }

    /** Call once per real playback-status tick. Counts a new real song exactly once per
     *  track change, ignoring the synthetic DJ interstitial track itself so it never
     *  contributes to its own scheduling. */
    fun onStatusUpdated(status: PlaybackStatus) {
        if (!enabled) return

        val current = status.currentTrack ?: return
        if (current.isDjFiller) return
        val previousIndex = lastSeenIndex
        val previousTrackId = lastSeenTrackId

        // A run of very short tracks (or a burst of skips) can advance through more than
        // one real song between two ~500ms poll ticks; a flat +1 here would silently drop
        // the skipped ones and never count them. Use the queue-position delta between the
        // last observed track and the current one when it's resolvable (both present, in
        // forward order); fall back to +1 for the first tick, a shuffle reorder, or a
        // manual previous(), where position delta isn't meaningful. Resolved nearest the
        // last known index rather than indexOfFirst, since a queue/playlist can repeat the
        // same track id more than once.
        val sequence = sequenceOf(status)
        val sameTrackId = current.id == previousTrackId
        val positionStillAdvancing = positionIsStillAdvancing(status.position)
        val expectedIndex = if (sameTrackId && positionStillAdvancing) {
            previousIndex
        } else {
            (previousIndex + 1).coerceAtLeast(0)
        }
        val currentIndex = nearestIndexOf(sequence, current.id, expectedIndex)
        lastObservedStatus = status
        if (sameTrackId && currentIndex == previousIndex && positionStillAdvancing) {
            lastSeenPositionMs = status.position
            prepareScheduledFiller(status)
            return
        }

        lastSeenTrackId = current.id
        lastSeenIndex = currentIndex
        lastSeenPositionMs = status.position
        failedOnCurrentTrack = false
        if (!breakCommitted && songsUntilBreak > 0) songsUntilBreak--
        updatePendingBreakOffset()
        prepareScheduledFiller(status)
    }

    private fun positionIsStillAdvancing(positionMs: Long): Boolean =
        lastSeenPositionMs < 0L || positionMs >= lastSeenPositionMs

    private fun sequenceOf(status: PlaybackStatus): List<Track> =
        status.orderedQueue.ifEmpty { status.queue }

    /** Duplicate-id-safe replacement for `sequence.indexOfFirst { it.id == trackId }`: a
     *  queue/playlist can contain the same track id more than once, so the first match isn't
     *  necessarily the one actually reached - this picks whichever occurrence sits closest to
     *  [expectedIndex] (the last known position) instead. */
    private fun nearestIndexOf(sequence: List<Track>, trackId: String, expectedIndex: Int): Int {
        var best = -1
        var bestDistance = Int.MAX_VALUE
        sequence.forEachIndexed { idx, track ->
            if (track.id == trackId) {
                val distance = kotlin.math.abs(idx - expectedIndex)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = idx
                }
            }
        }
        return best
    }

    private fun prepareScheduledFiller(status: PlaybackStatus) {
        if (breakCommitted || failedOnCurrentTrack || lastSeenIndex < 0) return
        val sequence = sequenceOf(status)
        val target = sequence.getOrNull(lastSeenIndex + songsUntilBreak + 1)
            ?.takeUnless { it.isDjFiller } ?: return
        if (expectedNextTrackId != null && expectedNextTrackId != target.id) {
            invalidateGeneration()
            discardPendingFiller()
        }
        expectedNextTrackId = target.id
        if (pendingFiller?.filler?.audioFile?.exists() == false) discardPendingFiller()
        if (pendingFiller != null) {
            insertLocalIfDue()
            return
        }
        if (generationJob?.isActive != true) startGenerationFor(target)
    }

    private fun insertLocalIfDue() {
        if (songsUntilBreak != 0 || !isLocalModeActive() || localFillerInserted) return
        val pending = pendingFiller ?: return
        if (djInterstitialPlayer.insertLocal(pending.filler)) {
            localFillerInserted = true
            breakCommitted = true
        } else {
            discardPendingFiller()
            resetSchedulingState()
        }
    }

    private fun startGenerationFor(nextTrack: Track) {
        if (generationJob?.isActive == true) return

        val startedAtTrackId = lastSeenTrackId
        val startedAtIndex = lastSeenIndex
        val generationId = generationVersion.incrementAndGet()
        mutablePreparationStatus.value = DjFillerPreparationStatus.PROCESSING
        expectedNextTrackId = nextTrack.id
        CompatLog.i(TAG, "AI DJ: generating break introducing '${nextTrack.title}' by ${nextTrack.artist}")
        generationJob = scope.launch {
            val ready = runCatching { generate(nextTrack) }.getOrElse {
                if (it is CancellationException) throw it
                CompatLog.e(TAG, "AI DJ generation threw an exception", it)
                withContext(Dispatchers.Main.immediate) {
                    synchronized(stateLock) {
                        if (enabled && generationId == generationVersion.get()) {
                            resetSchedulingState()
                            failedOnCurrentTrack = lastSeenTrackId == startedAtTrackId && lastSeenIndex == startedAtIndex
                        }
                    }
                }
                return@launch
            }

            if (ready == null) {
                CompatLog.w(TAG, "AI DJ: preparation unavailable; waiting for the next song")
                withContext(Dispatchers.Main.immediate) {
                    synchronized(stateLock) {
                        if (enabled && generationId == generationVersion.get()) {
                            resetSchedulingState()
                            failedOnCurrentTrack = lastSeenTrackId == startedAtTrackId && lastSeenIndex == startedAtIndex
                        }
                    }
                }
                return@launch
            }

            val readyWasCached = djFillerAudioCache.load(nextTrack.id) == ready.audioFile
            try {
                withContext(Dispatchers.Main.immediate) {
                    synchronized(stateLock) {
                        if (enabled && generationId == generationVersion.get() && expectedNextTrackId == nextTrack.id) {
                            val cached = ready.copy(audioFile = djFillerAudioCache.save(nextTrack.id, ready.audioFile, ready.passages))
                            pendingFiller = PendingFiller(cached, nextTrack.id)
                            mutablePreparationStatus.value = DjFillerPreparationStatus.READY
                            insertLocalIfDue()
                            if (mutablePreparationStatus.value == DjFillerPreparationStatus.READY) {
                                CompatLog.i(TAG, "AI DJ: break ready for '${nextTrack.title}' trackId=${nextTrack.id}")
                            }
                        }
                    }
                }
            } finally {
                // save() moves fresh output; a cache hit remains owned by the cache.
                if (!readyWasCached) djFillerAudioCache.delete(ready.audioFile)
            }
        }
        generationJob?.invokeOnCompletion {
            synchronized(stateLock) {
                if (generationId == generationVersion.get() &&
                    mutablePreparationStatus.value == DjFillerPreparationStatus.PROCESSING
                ) mutablePreparationStatus.value = DjFillerPreparationStatus.NOT_STARTED
            }
        }
    }

    private suspend fun generate(nextTrack: Track): PreparedFiller? {
        val passages = djPassageRepository.findPassages(nextTrack)
        if (passages == null) {
            CompatLog.w(TAG, "AI DJ: no passages for '${nextTrack.title}' by ${nextTrack.artist}")
            djFillerAudioCache.load(nextTrack.id)?.let(djFillerAudioCache::delete)
            return null
        }
        val cachedFile = djFillerAudioCache.load(nextTrack.id)
        if (cachedFile != null) {
            // Reuse ready audio only if it was grounded in exactly the passages retrieved now.
            if (djFillerAudioCache.loadPassages(nextTrack.id)?.chunkIds == passages.chunkIds) {
                return PreparedFiller(nextTrack, cachedFile, passages)
            }
            djFillerAudioCache.delete(cachedFile)
        }
        if (!djVoiceSynthesizer.isAvailable()) {
            CompatLog.w(TAG, "AI DJ: no usable on-device TTS voice, skipping this cycle")
            return null
        }
        val script = djScriptGenerator.generateScript(nextTrack, passages)
        currentCoroutineContext().ensureActive()
        if (script == null) {
            CompatLog.w(TAG, "AI DJ: script generation returned null for trackId=${nextTrack.id}; see the AI DJ log above for the reason")
            return null
        }
        val outputFile = djFillerAudioCache.newOutputFile()
        val synthesized = runCatching {
            djVoiceSynthesizer.synthesizeToFile(script, outputFile)
        }.getOrElse {
            outputFile.delete()
            throw it
        }
        if (!synthesized) {
            outputFile.delete()
            CompatLog.w(TAG, "AI DJ: TTS synthesis failed for generated script")
            return null
        }
        return PreparedFiller(track = nextTrack, audioFile = outputFile, passages = passages)
    }

    /**
     * Returns a ready [PreparedFiller] only for [upcomingTrackId], otherwise leaves the
     * caller's normal advance logic unaffected.
     */
    fun consumeReadyFillerIfDue(upcomingTrackId: String?): PreparedFiller? {
        if (!enabled) return null
        if (songsUntilBreak != 0) return null
        // A late generation is for the old transition and must not survive into the next one.
        val pending = synchronized(stateLock) {
            invalidateGeneration()
            pendingFiller.also { pendingFiller = null }
        }
        if (pending == null || upcomingTrackId == null || pending.forTrackId != upcomingTrackId) resetSchedulingState()

        if (pending == null || upcomingTrackId == null || pending.forTrackId != upcomingTrackId) {
            pending?.filler?.audioFile?.let(djFillerAudioCache::delete)
            CompatLog.i(TAG, "AI DJ break due but no ready filler matched upcomingTrackId=$upcomingTrackId pending=${pending?.forTrackId}")
            return null
        }

        breakCommitted = true
        return pending.filler
    }
}
