package com.anyplayer.android.feature.djfiller

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.djfiller.metadata.WikipediaFactClient
import com.anyplayer.android.feature.djfiller.model.AI_DJ_PRESENTATION_TRACK
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
    private var songsSinceLastBreak = 0

    @Volatile
    private var nextBreakThreshold = rollThreshold()

    private data class PendingFiller(
        val filler: PreparedFiller,
        val forTrackId: String
    )

    @Volatile
    private var pendingFiller: PendingFiller? = null

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

    private val mutablePendingQueueDisplayTrack = MutableStateFlow<Track?>(null)

    /** Non-null while a break is ready but not yet reached in playback - the "up next"
     *  queue display prepends [AI_DJ_PRESENTATION_TRACK] right after the current track
     *  when the user has the "show DJ entries" setting on. */
    val pendingQueueDisplayTrack: StateFlow<Track?> = mutablePendingQueueDisplayTrack

    private fun resetSchedulingState() {
        songsSinceLastBreak = 0
        nextBreakThreshold = rollThreshold()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            generationJob?.cancel()
            generationJob = null
            pendingFiller = null
            mutablePendingQueueDisplayTrack.value = null
        }
    }

    /** Call once per real playback-status tick. Counts a new real song exactly once per
     *  track change, ignoring the synthetic DJ interstitial track itself so it never
     *  contributes to its own scheduling. */
    fun onStatusUpdated(status: PlaybackStatus) {
        if (!enabled) return

        // Local-mode splice: once ExoPlayer has actually carried playback into the
        // pending break, it's no longer "upcoming" - stop showing it in the queue.
        if (mutablePendingQueueDisplayTrack.value != null && djInterstitialPlayer.isPlayingInterstitial) {
            mutablePendingQueueDisplayTrack.value = null
        }

        val current = status.currentTrack ?: return
        if (current.isDjFiller) return
        if (current.id == lastSeenTrackId) return
        lastSeenTrackId = current.id

        songsSinceLastBreak++
        if (songsSinceLastBreak == nextBreakThreshold) {
            startGenerationFor(status, current)
        }
    }

    private fun sequenceOf(status: PlaybackStatus): List<Track> =
        status.orderedQueue.ifEmpty { status.queue }

    private fun startGenerationFor(status: PlaybackStatus, preBreakTrack: Track) {
        if (generationJob?.isActive == true) return
        val sequence = sequenceOf(status)
        val currentIndex = sequence.indexOfFirst { it.id == preBreakTrack.id }
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
            withContext(Dispatchers.Main.immediate) {
                mutablePendingQueueDisplayTrack.value = AI_DJ_PRESENTATION_TRACK
            }

            if (isLocalModeActive()) {
                // Push immediately: ExoPlayer's own auto-advance will carry playback into
                // the spliced item with zero gap once the current song ends, so there's
                // nothing left to "consume" later - reset scheduling state right away.
                withContext(Dispatchers.Main.immediate) {
                    djInterstitialPlayer.insertLocal(ready)
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
        val fact = wikipediaFactClient.fetchArtistFact(nextTrack.artist)
        val script = djScriptGenerator.generateScript(nextTrack, fact)
        if (script == null) {
            CompatLog.w(TAG, "AI DJ: script generation returned null (model not downloaded/loaded, or inference failed)")
            return null
        }
        val outputFile = djFillerAudioCache.newOutputFile()
        val synthesized = djVoiceSynthesizer.synthesizeToFile(script, outputFile)
        if (!synthesized) {
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
        // trigger generation again for the rest of the session.
        songsSinceLastBreak = 0
        nextBreakThreshold = rollThreshold()
        mutablePendingQueueDisplayTrack.value = null

        val pending = pendingFiller
        pendingFiller = null
        if (pending == null || upcomingTrackId == null || pending.forTrackId != upcomingTrackId) {
            CompatLog.i(TAG, "AI DJ break due but no ready filler matched upcomingTrackId=$upcomingTrackId pending=${pending?.forTrackId}")
            return null
        }

        return pending.filler
    }
}
