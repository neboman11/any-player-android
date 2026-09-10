package com.anyplayer.android.feature.djfiller

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
import kotlin.random.Random

/** Counts real songs played (fed by [onStatusUpdated], called once per ~500ms tick of
 *  [com.anyplayer.android.feature.playback.PlaybackQueueManager]'s own poll loop - see
 *  its `syncFromPlaybackEngine()` call site) and decides when an AI DJ break is due.
 *
 *  Generation (fact lookup + LLM script + TTS render) starts as soon as the last
 *  pre-break song becomes current, using nearly that whole song's duration as budget.
 *  [consumeReadyFillerIfDue] is the only way a caller ever learns a break is due, and it
 *  is designed to NEVER block a transition: if generation isn't finished in time, or the
 *  queue changed underneath it, it simply returns null and the caller proceeds exactly as
 *  if AI DJ were disabled for that one cycle. */
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
        const val MIN_SONGS_BETWEEN_BREAKS = 3
        const val MAX_SONGS_BETWEEN_BREAKS_EXCLUSIVE = 6
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

    @Volatile
    private var songsSinceLastBreak = 0

    @Volatile
    private var nextBreakThreshold = rollThreshold()

    private data class PendingFiller(
        val filler: PreparedFiller,
        val forTrackId: String
    )

    @Volatile
    private var pendingFiller: PendingFiller? = null

    private val generationVersion = AtomicLong(0L)

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

    private fun rollThreshold(): Int = Random.nextInt(MIN_SONGS_BETWEEN_BREAKS, MAX_SONGS_BETWEEN_BREAKS_EXCLUSIVE)

    private val mutablePendingBreakSongsAway = MutableStateFlow<Int?>(null)

    /** How many more real songs will play before the next break, so the "up next" queue
     *  display can always show exactly where [AI_DJ_PRESENTATION_TRACK] will land (0 = right
     *  after the current track) - this reflects the *schedule*, not whether generation has
     *  actually finished, so it's visible the moment a threshold is rolled rather than
     *  popping in unpredictably once content happens to be ready. Null while disabled. */
    val pendingBreakSongsAway: StateFlow<Int?> = mutablePendingBreakSongsAway

    private fun updatePendingBreakOffset() {
        mutablePendingBreakSongsAway.value = if (enabled) {
            (nextBreakThreshold - songsSinceLastBreak).coerceAtLeast(0)
        } else {
            null
        }
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
        songsSinceLastBreak = 0
        nextBreakThreshold = rollThreshold()
        updatePendingBreakOffset()
    }

    private fun discardPendingFiller() {
        pendingFiller?.filler?.audioFile?.delete()
        pendingFiller = null
    }

    private fun invalidateGeneration() {
        generationVersion.incrementAndGet()
        generationJob?.cancel()
        generationJob = null
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            invalidateGeneration()
            discardPendingFiller()
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
        expectedNextTrackId = sequence.getOrNull(currentIndex + 1)?.id
        if (sameTrackId && currentIndex == previousIndex && positionStillAdvancing) {
            lastSeenPositionMs = status.position
            return
        }

        lastSeenTrackId = current.id
        lastSeenIndex = currentIndex
        val advance = if (previousIndex >= 0 && currentIndex >= 0) {
            (currentIndex - previousIndex).takeIf { it > 0 } ?: 1
        } else {
            1
        }
        lastSeenPositionMs = status.position

        songsSinceLastBreak += advance
        updatePendingBreakOffset()
        if (songsSinceLastBreak >= nextBreakThreshold) {
            startGenerationFor(status, current)
        }
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

            if (generationId != generationVersion.get()) {
                ready.audioFile.delete()
                return@launch
            }

            if (isLocalModeActive()) {
                // Push immediately: ExoPlayer's own auto-advance will carry playback into
                // the spliced item with zero gap once the current song ends, so there's
                // nothing left to "consume" later - reset scheduling state right away.
                withContext(Dispatchers.Main.immediate) {
                    // Generation can take seconds; if the user manually skipped during that
                    // window, preBreakTrack is no longer current and insertLocal() would
                    // splice this (now stale) break after whatever is actually playing.
                    if (lastSeenTrackId == preBreakTrack.id && expectedNextTrackId == nextTrack.id) {
                        djInterstitialPlayer.insertLocal(ready)
                    } else {
                        CompatLog.i(TAG, "AI DJ: pre-break track ${preBreakTrack.id} no longer current after generation, discarding stale break")
                        ready.audioFile.delete()
                    }
                    resetSchedulingState()
                }
            } else {
                pendingFiller = PendingFiller(ready, nextTrack.id)
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
        return PreparedFiller(track = nextTrack, scriptText = script, audioFile = outputFile)
    }

    /** [upcomingTrackId] is the id of whatever the caller is about to advance into. Returns
     *  a ready [PreparedFiller] only when a break is due AND generation finished for
     *  exactly that track; otherwise resets nothing extra and returns null so the caller's
     *  normal advance logic is completely unaffected. */
    fun consumeReadyFillerIfDue(upcomingTrackId: String?): PreparedFiller? {
        if (!enabled) return null
        if (songsSinceLastBreak < nextBreakThreshold) return null

        // Reset unconditionally, whether or not a ready filler is actually consumed below -
        // otherwise a single cycle where generation didn't finish in time would leave
        // songsSinceLastBreak permanently >= nextBreakThreshold, and since onStatusUpdated
        // only (re)starts generation on an *exact* equality match, the scheduler would never
        // trigger generation again for the rest of the session. This also rolls a fresh
        // threshold and updates the "songs away" display to the *next* break immediately.
        invalidateGeneration()
        resetSchedulingState()

        val pending = pendingFiller
        pendingFiller = null
        if (pending == null || upcomingTrackId == null || pending.forTrackId != upcomingTrackId) {
            pending?.filler?.audioFile?.delete()
            CompatLog.i(TAG, "AI DJ break due but no ready filler matched upcomingTrackId=$upcomingTrackId pending=${pending?.forTrackId}")
            return null
        }

        return pending.filler
    }
}
