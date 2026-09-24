package com.anyplayer.android.feature.djfiller

import androidx.annotation.MainThread
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.djfiller.metadata.WikipediaFactClient
import com.anyplayer.android.feature.djfiller.model.DjModelDownloadState
import com.anyplayer.android.feature.djfiller.model.PreparedFiller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps one AI DJ voice-over ready for the track after the real song currently playing.
 * Generation starts on the first playback update after launch or after the previous
 * voice-over has finished. [consumeReadyFillerIfDue] never blocks a track transition.
 */
@Singleton
class DjFillerScheduler @Inject constructor(
    private val djScriptGenerator: DjScriptGenerator,
    private val djVoiceSynthesizer: DjVoiceSynthesizer,
    private val wikipediaFactClient: WikipediaFactClient,
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
        wikipediaFactClient: WikipediaFactClient,
        djFillerAudioCache: DjFillerAudioCache,
        djInterstitialPlayer: DjInterstitialPlayer,
        schedulerDispatcher: CoroutineDispatcher
    ) : this(
        djScriptGenerator,
        djVoiceSynthesizer,
        wikipediaFactClient,
        djFillerAudioCache,
        djInterstitialPlayer
    ) {
        scope = CoroutineScope(SupervisorJob() + schedulerDispatcher)
    }


    private val mutablePendingBreakSongsAway = MutableStateFlow<Int?>(null)

    /** How many more real songs will play before the next break, so the "up next" queue
     *  display can always show exactly where [AI_DJ_PRESENTATION_TRACK] will land (0 = right
     *  after the current track) - this reflects the *schedule*, not whether generation has
     *  actually finished, so it's visible the moment a threshold is rolled rather than
     *  popping in unpredictably once content happens to be ready. Null while disabled. */
    val pendingBreakSongsAway: StateFlow<Int?> = mutablePendingBreakSongsAway

    private fun updatePendingBreakOffset() {
        mutablePendingBreakSongsAway.value = if (enabled) 0 else null
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
        updatePendingBreakOffset()
    }

    private fun resetSeenTrackState() {
        lastSeenTrackId = null
        lastSeenPositionMs = -1L
        lastSeenIndex = -1
        expectedNextTrackId = null
    }

    private fun discardPendingFiller() {
        if (!djInterstitialPlayer.isPlayingInterstitial) {
            pendingFiller?.filler?.audioFile?.let(djFillerAudioCache::delete)
        }
        pendingFiller = null
    }

    private fun invalidateGeneration() {
        generationVersion.incrementAndGet()
        generationJob?.cancel()
        generationJob = null
    }

    @MainThread
    fun setEnabled(value: Boolean) {
        synchronized(stateLock) {
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
        }
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
        expectedNextTrackId = sequence.getOrNull(currentIndex + 1)?.id
        if (sameTrackId && currentIndex == previousIndex && positionStillAdvancing) {
            lastSeenPositionMs = status.position
            return
        }

        lastSeenTrackId = current.id
        lastSeenIndex = currentIndex
        lastSeenPositionMs = status.position
        updatePendingBreakOffset()
        prepareFillerFor(status, current)
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

    private fun prepareFillerFor(status: PlaybackStatus, preBreakTrack: Track) {
        pendingFiller?.takeIf { !it.filler.audioFile.exists() }?.let { pendingFiller = null }
        if (pendingFiller != null || generationJob?.isActive == true) return

        val sequence = sequenceOf(status)
        val currentIndex = lastSeenIndex.takeIf {
            it >= 0 && sequence.getOrNull(it)?.id == preBreakTrack.id
        } ?: nearestIndexOf(sequence, preBreakTrack.id, 0)
        if (currentIndex < 0) return
        val nextTrack = sequence.getOrNull(currentIndex + 1) ?: return
        val cachedFile = djFillerAudioCache.load(nextTrack.id)
        if (cachedFile == null) {
            startGenerationFor(status, preBreakTrack)
            return
        }

        val ready = PreparedFiller(nextTrack, audioFile = cachedFile)
        pendingFiller = PendingFiller(ready, nextTrack.id)
        if (isLocalModeActive()) djInterstitialPlayer.insertLocal(ready)
    }

    private fun startGenerationFor(status: PlaybackStatus, preBreakTrack: Track) {
        if (generationJob?.isActive == true) return
        val sequence = sequenceOf(status)
        // preBreakTrack.id is always the current lastSeenTrackId at this call site (only
        // ever invoked synchronously from onStatusUpdated), so its index was already
        // resolved duplicate-id-safely there - reuse it instead of re-searching by id.
        val currentIndex = lastSeenIndex.takeIf { it >= 0 && sequence.getOrNull(it)?.id == preBreakTrack.id }
            ?: nearestIndexOf(sequence, preBreakTrack.id, 0)
        if (currentIndex < 0) {
            CompatLog.w(TAG, "AI DJ: pre-break track ${preBreakTrack.id} not found in queue sequence, skipping this cycle")
            resetSchedulingState()
            return
        }
        val nextTrack = sequence.getOrNull(currentIndex + 1)
        if (nextTrack == null) {
            CompatLog.i(TAG, "AI DJ: no next track queued after ${preBreakTrack.id}, skipping this cycle")
            resetSchedulingState()
            return
        }
        if (nextTrack.isDjFiller) {
            resetSchedulingState()
            return
        }

        val generationId = generationVersion.incrementAndGet()
        expectedNextTrackId = nextTrack.id
        CompatLog.i(TAG, "AI DJ: generating break introducing '${nextTrack.title}' by ${nextTrack.artist}")
        generationJob = scope.launch {
            val ready = runCatching { generate(nextTrack) }.getOrElse {
                CompatLog.e(TAG, "AI DJ generation threw an exception", it)
                withContext(Dispatchers.Main.immediate) {
                    resetSchedulingState()
                }
                return@launch
            }

            if (ready == null) {
                CompatLog.w(TAG, "AI DJ: generation did not produce a filler this cycle (see preceding log line for why)")
                withContext(Dispatchers.Main.immediate) {
                    resetSchedulingState()
                }
                return@launch
            }

            CompatLog.i(TAG, "AI DJ: break ready for '${nextTrack.title}'")

            // generationVersion check + the resulting state mutation (splice, or stashing
            // pendingFiller) must happen atomically w.r.t. setEnabled(false) and
            // consumeReadyFillerIfDue, both of which can invalidate/consume state from
            // another thread between the check and the mutation.
            val stale = synchronized(stateLock) { generationId != generationVersion.get() }
            if (stale) {
                djFillerAudioCache.delete(ready.audioFile)
                return@launch
            }

            if (isLocalModeActive()) {
                // Push immediately: ExoPlayer's own auto-advance will carry playback into
                // the spliced item with zero gap once the current song ends, so there's
                // nothing left to "consume" later - reset scheduling state right away.
                try {
                    withContext(Dispatchers.Main.immediate) {
                        // Generation can take seconds; if the user manually skipped during that
                        // window, preBreakTrack is no longer current and insertLocal() would
                        // splice this (now stale) break after whatever is actually playing.
                        val stillPending = synchronized(stateLock) { generationId == generationVersion.get() }
                        if (stillPending && isLocalModeActive()) {
                            val cachedReady = ready.copy(audioFile = djFillerAudioCache.save(nextTrack.id, ready.audioFile))
                            synchronized(stateLock) {
                                if (
                                    enabled &&
                                    generationId == generationVersion.get() &&
                                    isLocalModeActive() &&
                                    lastSeenTrackId == preBreakTrack.id &&
                                    expectedNextTrackId == nextTrack.id
                                ) {
                                    pendingFiller = PendingFiller(cachedReady, nextTrack.id)
                                    djInterstitialPlayer.insertLocal(cachedReady)
                                } else {
                                    djFillerAudioCache.delete(cachedReady.audioFile)
                                }
                            }
                        } else {
                            CompatLog.i(TAG, "AI DJ: pre-break track ${preBreakTrack.id} no longer current after generation, discarding stale break")
                            djFillerAudioCache.delete(ready.audioFile)
                        }
                        resetSchedulingState()
                    }
                } finally {
                    // Cancellation can happen before the Main dispatcher runs this block.
                    // save() moves the file when playback takes ownership, so this only
                    // removes an unclaimed generation output.
                    djFillerAudioCache.delete(ready.audioFile)
                }
            } else {
                synchronized(stateLock) {
                    if (generationId == generationVersion.get()) {
                        pendingFiller = PendingFiller(
                            ready.copy(audioFile = djFillerAudioCache.save(nextTrack.id, ready.audioFile)),
                            nextTrack.id
                        )
                    } else {
                        djFillerAudioCache.delete(ready.audioFile)
                    }
                }
            }
        }
    }

    private suspend fun generate(nextTrack: Track): PreparedFiller? {
        if (!djVoiceSynthesizer.isAvailable()) {
            CompatLog.w(TAG, "AI DJ: no usable on-device TTS voice, skipping this cycle")
            return null
        }
        val fact = wikipediaFactClient.fetchArtistFact("${nextTrack.title} (${nextTrack.artist} song)")
        val script = djScriptGenerator.generateScript(nextTrack, fact)
        if (script == null) {
            CompatLog.w(TAG, "AI DJ: script generation returned null (model not downloaded/loaded, or inference failed)")
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
        return PreparedFiller(track = nextTrack, audioFile = outputFile)
    }

    /**
     * Returns a ready [PreparedFiller] only for [upcomingTrackId], otherwise leaves the
     * caller's normal advance logic unaffected.
     */
    fun consumeReadyFillerIfDue(upcomingTrackId: String?): PreparedFiller? {
        if (!enabled) return null
        // A late generation is for the old transition and must not survive into the next one.
        val pending = synchronized(stateLock) {
            invalidateGeneration()
            pendingFiller.also { pendingFiller = null }
        }
        resetSchedulingState()

        if (pending == null || upcomingTrackId == null || pending.forTrackId != upcomingTrackId) {
            pending?.filler?.audioFile?.let(djFillerAudioCache::delete)
            CompatLog.i(TAG, "AI DJ break due but no ready filler matched upcomingTrackId=$upcomingTrackId pending=${pending?.forTrackId}")
            return null
        }

        return pending.filler
    }
}
