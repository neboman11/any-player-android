package com.anyplayer.android.feature.djfiller

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.djfiller.metadata.WikipediaFactClient
import com.anyplayer.android.feature.djfiller.model.PreparedFiller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    private var lastSeenTrackId: String? = null
    private var songsSinceLastBreak = 0
    private var nextBreakThreshold = rollThreshold()

    @Volatile
    private var pendingFiller: PreparedFiller? = null

    @Volatile
    private var pendingForTrackId: String? = null

    private var generationJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun rollThreshold(): Int = Random.nextInt(MIN_SONGS_BETWEEN_BREAKS, MAX_SONGS_BETWEEN_BREAKS_EXCLUSIVE)

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            generationJob?.cancel()
            generationJob = null
            pendingFiller = null
            pendingForTrackId = null
        }
    }

    /** Call once per real playback-status tick. Counts a new real song exactly once per
     *  track change, ignoring the synthetic DJ interstitial track itself so it never
     *  contributes to its own scheduling. */
    fun onStatusUpdated(status: PlaybackStatus) {
        if (!enabled) return
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
        if (currentIndex < 0) return
        val nextTrack = sequence.getOrNull(currentIndex + 1) ?: return
        if (nextTrack.isDjFiller) return

        generationJob = scope.launch {
            val ready = runCatching { generate(nextTrack) }
                .onFailure { CompatLog.e(TAG, "AI DJ generation failed", it) }
                .getOrNull() ?: return@launch

            if (isLocalModeActive()) {
                // Push immediately: ExoPlayer's own auto-advance will carry playback into
                // the spliced item with zero gap once the current song ends, so there's
                // nothing left to "consume" later - reset scheduling state right away.
                djInterstitialPlayer.insertLocal(ready)
                songsSinceLastBreak = 0
                nextBreakThreshold = rollThreshold()
            } else {
                pendingFiller = ready
                pendingForTrackId = nextTrack.id
            }
        }
    }

    private suspend fun generate(nextTrack: Track): PreparedFiller? {
        if (!djVoiceSynthesizer.isAvailable()) return null
        val fact = wikipediaFactClient.fetchArtistFact(nextTrack.artist)
        val script = djScriptGenerator.generateScript(nextTrack, fact) ?: return null
        val outputFile = djFillerAudioCache.newOutputFile()
        val synthesized = djVoiceSynthesizer.synthesizeToFile(script, outputFile)
        if (!synthesized) return null
        return PreparedFiller(track = nextTrack, scriptText = script, audioFile = outputFile)
    }

    /** [upcomingTrackId] is the id of whatever the caller is about to advance into. Returns
     *  a ready [PreparedFiller] only when a break is due AND generation finished for
     *  exactly that track; otherwise resets nothing extra and returns null so the caller's
     *  normal advance logic is completely unaffected. */
    fun consumeReadyFillerIfDue(upcomingTrackId: String?): PreparedFiller? {
        if (!enabled) return null
        if (songsSinceLastBreak < nextBreakThreshold) return null

        songsSinceLastBreak = 0
        nextBreakThreshold = rollThreshold()

        val filler = pendingFiller
        pendingFiller = null
        val forId = pendingForTrackId
        pendingForTrackId = null

        if (filler == null || upcomingTrackId == null || forId != upcomingTrackId) {
            return null
        }
        return filler
    }
}
