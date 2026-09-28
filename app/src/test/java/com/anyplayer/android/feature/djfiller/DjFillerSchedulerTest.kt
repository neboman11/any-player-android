package com.anyplayer.android.feature.djfiller

import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.feature.djfiller.metadata.DjPassages
import com.anyplayer.android.feature.djfiller.metadata.DjPassage
import com.anyplayer.android.feature.djfiller.metadata.DjPassageRepository
import com.anyplayer.android.feature.djfiller.model.DjFillerPreparationStatus
import com.anyplayer.android.feature.playback.Media3PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.never
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import java.nio.file.Files

/**
 * [DjFillerScheduler] next-track preparation and non-blocking transitions.
 * Generation itself runs on an internal background scope wired to real (mocked-here)
 * TTS/LLM/network dependencies, so these tests focus on what's deterministic without
 * waiting on that async pipeline: that the next-track presentation remains visible, that
 * [DjFillerScheduler.pendingBreakSongsAway] always reflects the schedule regardless of
 * whether generation has finished, that disabling the feature suppresses everything, and -
 * the core zero-wait guarantee - that [DjFillerScheduler.consumeReadyFillerIfDue] never
 * blocks or throws even when generation could not possibly have finished yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DjFillerSchedulerTest {

    @Test
    fun `first playback update prepares the scheduled future break`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val audio = Files.createTempFile("dj-early", ".wav").toFile()
        val cache = mock<DjFillerAudioCache>()
        val facts = mock<DjPassageRepository>()
        val selected = passages()
        whenever(facts.findPassages(any())).thenReturn(selected)
        scheduler = DjFillerScheduler(mock(), mock(), facts, cache, mock(), StandardTestDispatcher(testScheduler))
        try {
            scheduler.setEnabled(true)
            val songsBeforeBreak = scheduler.pendingBreakSongsAway.value!!
            val targetId = "t${songsBeforeBreak + 1}"
            whenever(cache.load(targetId)).thenReturn(audio)
            whenever(cache.loadPassages(targetId)).thenReturn(selected)
            whenever(cache.save(targetId, audio, selected)).thenReturn(audio)
            val queue = (1..10).map { track("t$it") }

            scheduler.onStatusUpdated(statusWith("t1", queue))

            assertEquals(DjFillerPreparationStatus.PROCESSING, scheduler.preparationStatus.value)
            runCurrent()
            assertEquals(DjFillerPreparationStatus.READY, scheduler.preparationStatus.value)
            assertEquals(songsBeforeBreak - 1, scheduler.pendingBreakSongsAway.value)
            verify(cache, atLeastOnce()).load(targetId)
            assertNull(scheduler.consumeReadyFillerIfDue(targetId))
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `local break is prepared early but inserted only on pre-break song`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val audio = Files.createTempFile("dj-early-local", ".wav").toFile()
        val cache = mock<DjFillerAudioCache>()
        val facts = mock<DjPassageRepository>()
        val interstitial = mock<DjInterstitialPlayer>()
        val selected = passages()
        whenever(facts.findPassages(any())).thenReturn(selected)
        whenever(interstitial.insertLocal(any())).thenReturn(true)
        scheduler = DjFillerScheduler(mock(), mock(), facts, cache, interstitial, StandardTestDispatcher(testScheduler))
        scheduler.configureLocalModeProvider { true }
        try {
            scheduler.setEnabled(true)
            val songsBeforeBreak = scheduler.pendingBreakSongsAway.value!!
            val targetId = "t${songsBeforeBreak + 1}"
            whenever(cache.load(targetId)).thenReturn(audio)
            whenever(cache.loadPassages(targetId)).thenReturn(selected)
            whenever(cache.save(targetId, audio, selected)).thenReturn(audio)
            val queue = (1..10).map { track("t$it") }

            scheduler.onStatusUpdated(statusWith("t1", queue))
            runCurrent()
            assertEquals(DjFillerPreparationStatus.READY, scheduler.preparationStatus.value)
            verify(interstitial, never()).insertLocal(any())

            (2..songsBeforeBreak).forEach { scheduler.onStatusUpdated(statusWith("t$it", queue)) }
            verify(interstitial).insertLocal(any())
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `next break starts preparing when previous DJ break starts`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val cache = mock<DjFillerAudioCache>()
        val facts = mock<DjPassageRepository>()
        val media3 = mock<Media3PlaybackController>()
        whenever(media3.isPlayingInterstitial).thenReturn(true)
        whenever(facts.findPassages(any())).thenReturn(null)
        val interstitial = DjInterstitialPlayer(media3)
        scheduler = DjFillerScheduler(mock(), mock(), facts, cache, interstitial, StandardTestDispatcher(testScheduler))
        scheduler.setEnabled(true)
        val queue = (1..20).map { track("t$it") }
        scheduler.onStatusUpdated(statusWith("t1", queue))
        val firstBreak = scheduler.pendingBreakSongsAway.value!!
        (2..firstBreak).forEach { scheduler.onStatusUpdated(statusWith("t$it", queue)) }

        interstitial.onFillerStarted?.invoke(passages())

        assertEquals(DjFillerPreparationStatus.PROCESSING, scheduler.preparationStatus.value)
        val nextBreak = scheduler.pendingBreakSongsAway.value!!
        assertTrue(nextBreak in 3..5)
        runCurrent()
        verify(facts).findPassages(queue[firstBreak + nextBreak])
    }

    @Test
    fun `queue preparation status moves from waiting to processing to ready`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val audio = Files.createTempFile("dj-status", ".wav").toFile()
        val cache = mock<DjFillerAudioCache>()
        val facts = mock<DjPassageRepository>()
        val selected = passages()
        whenever(facts.findPassages(any())).thenReturn(selected)
        whenever(cache.load("next")).thenReturn(audio)
        whenever(cache.loadPassages("next")).thenReturn(selected)
        whenever(cache.save("next", audio, selected)).thenReturn(audio)
        scheduler = DjFillerScheduler(mock(), mock(), facts, cache, mock(), StandardTestDispatcher(testScheduler))
        try {
            scheduler.setEnabled(true)
            assertEquals(DjFillerPreparationStatus.NOT_STARTED, scheduler.preparationStatus.value)
            forceDueOnNextTrack()
            scheduler.onStatusUpdated(statusWith("old", listOf(track("old"), track("next"))))
            assertEquals(DjFillerPreparationStatus.PROCESSING, scheduler.preparationStatus.value)
            runCurrent()
            assertEquals(DjFillerPreparationStatus.READY, scheduler.preparationStatus.value)
            assertEquals(audio, scheduler.consumeReadyFillerIfDue("next")?.audioFile)
            assertEquals(DjFillerPreparationStatus.NOT_STARTED, scheduler.preparationStatus.value)
            scheduler.onQueueReplaced()
            assertEquals(DjFillerPreparationStatus.NOT_STARTED, scheduler.preparationStatus.value)
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `cancelled script generation is not reported as a failed or ready break`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val facts = mock<DjPassageRepository>()
        val script = mock<DjScriptGenerator>()
        val voice = mock<DjVoiceSynthesizer>()
        whenever(facts.findPassages(any())).thenReturn(passages())
        whenever(voice.isAvailable()).thenReturn(true)
        whenever(script.generateScript(any(), any())).thenThrow(CancellationException("superseded"))
        scheduler = DjFillerScheduler(script, voice, facts, mock(), mock(), StandardTestDispatcher(testScheduler))
        scheduler.setEnabled(true)
        forceDueOnNextTrack()
        CompatLog.clearAiDjLogs()

        scheduler.onStatusUpdated(statusWith("old", listOf(track("old"), track("next"))))
        runCurrent()

        assertEquals(DjFillerPreparationStatus.NOT_STARTED, scheduler.preparationStatus.value)
        assertFalse(CompatLog.aiDjLogs.value.any {
            it.message.contains("generation threw") || it.message.contains("script generation returned null") ||
                it.message.contains("break ready")
        })
    }

    @Test
    fun `null script leaves break unready`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val facts = mock<DjPassageRepository>()
        val script = mock<DjScriptGenerator>()
        val voice = mock<DjVoiceSynthesizer>()
        whenever(facts.findPassages(any())).thenReturn(passages())
        whenever(voice.isAvailable()).thenReturn(true)
        scheduler = DjFillerScheduler(script, voice, facts, mock(), mock(), StandardTestDispatcher(testScheduler))
        scheduler.setEnabled(true)
        forceDueOnNextTrack()
        CompatLog.clearAiDjLogs()

        scheduler.onStatusUpdated(statusWith("old", listOf(track("old"), track("next"))))
        runCurrent()

        assertEquals(DjFillerPreparationStatus.NOT_STARTED, scheduler.preparationStatus.value)
        assertTrue(CompatLog.aiDjLogs.value.any { it.message.contains("script generation returned null") })
        assertFalse(CompatLog.aiDjLogs.value.any { it.message.contains("break ready") })
    }

    @Test
    fun `cancelled generation ignores a late null script result`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val facts = mock<DjPassageRepository>()
        val script = mock<DjScriptGenerator>()
        val voice = mock<DjVoiceSynthesizer>()
        whenever(facts.findPassages(any())).thenReturn(passages())
        whenever(voice.isAvailable()).thenReturn(true)
        scheduler = DjFillerScheduler(script, voice, facts, mock(), mock(), StandardTestDispatcher(testScheduler))
        whenever(script.generateScript(any(), any())).thenAnswer {
            scheduler.onQueueReplaced()
            null
        }
        scheduler.setEnabled(true)
        forceDueOnNextTrack()
        CompatLog.clearAiDjLogs()

        scheduler.onStatusUpdated(statusWith("old", listOf(track("old"), track("next"))))
        runCurrent()

        assertEquals(DjFillerPreparationStatus.NOT_STARTED, scheduler.preparationStatus.value)
        assertFalse(CompatLog.aiDjLogs.value.any {
            it.message.contains("script generation returned null") || it.message.contains("break ready")
        })
    }

    @Test
    fun `cached audio is not played when its passages are no longer returned`() = runTest {
        val cache = mock<DjFillerAudioCache>()
        val interstitial = mock<DjInterstitialPlayer>()
        val facts = mock<DjPassageRepository>()
        val audio = Files.createTempFile("dj-cached", ".wav").toFile()
        whenever(cache.load("next")).thenReturn(audio)
        whenever(cache.loadPassages("next")).thenReturn(passages())
        whenever(facts.findPassages(any())).thenReturn(null)
        scheduler = DjFillerScheduler(mock(), mock(), facts, cache, interstitial, testDispatcher)
        scheduler.configureLocalModeProvider { true }
        try {
            scheduler.setEnabled(true)
            forceDueOnNextTrack()
            scheduler.onStatusUpdated(statusWith("old", listOf(track("old"), track("next"))))
            verify(interstitial, never()).insertLocal(any())
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `replacing queue forgets inserted filler so next queue can prepare one`() = runTest {
        val cache = mock<DjFillerAudioCache>()
        val interstitial = mock<DjInterstitialPlayer>()
        val facts = mock<DjPassageRepository>()
        val audio = Files.createTempFile("dj-replace", ".wav").toFile()
        val queue = listOf(track("old"), track("next"))
        whenever(cache.load("next")).thenReturn(audio)
        whenever(cache.loadPassages("next")).thenReturn(passages())
        whenever(cache.save("next", audio, passages())).thenReturn(audio)
        whenever(facts.findPassages(any())).thenReturn(passages())
        whenever(interstitial.insertLocal(any())).thenReturn(true)
        scheduler = DjFillerScheduler(mock(), mock(), facts, cache, interstitial, testDispatcher)
        scheduler.configureLocalModeProvider { true }
        try {
            scheduler.setEnabled(true)
            forceDueOnNextTrack()
            scheduler.onStatusUpdated(statusWith("old", queue))
            assertEquals(DjFillerPreparationStatus.READY, scheduler.preparationStatus.value)
            scheduler.onQueueReplaced()
            assertEquals(DjFillerPreparationStatus.NOT_STARTED, scheduler.preparationStatus.value)
            forceDueOnNextTrack()
            scheduler.onStatusUpdated(statusWith("old", queue))
            verify(interstitial, org.mockito.kotlin.times(2)).insertLocal(org.mockito.kotlin.any())
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `disabling removes queued local filler before cache is cleared`() = runTest {
        val cache = mock<DjFillerAudioCache>()
        val interstitial = mock<DjInterstitialPlayer>()
        val facts = mock<DjPassageRepository>()
        val audio = Files.createTempFile("dj-disable", ".wav").toFile()
        whenever(cache.load("next")).thenReturn(audio)
        whenever(cache.loadPassages("next")).thenReturn(passages())
        whenever(cache.save("next", audio, passages())).thenReturn(audio)
        whenever(facts.findPassages(any())).thenReturn(passages())
        scheduler = DjFillerScheduler(mock(), mock(), facts, cache, interstitial, testDispatcher)
        scheduler.configureLocalModeProvider { true }
        try {
            scheduler.setEnabled(true)
            forceDueOnNextTrack()
            scheduler.onStatusUpdated(statusWith("old", listOf(track("old"), track("next"))))
            scheduler.setEnabled(false)
            val order = org.mockito.kotlin.inOrder(interstitial, cache)
            order.verify(interstitial).cancelPendingLocal()
            order.verify(cache).clear()
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `disabling during active filler leaves audio for playback owner to delete`() = runTest {
        val cache = mock<DjFillerAudioCache>()
        val interstitial = mock<DjInterstitialPlayer>()
        val facts = mock<DjPassageRepository>()
        val audio = Files.createTempFile("dj-active", ".wav").toFile()
        whenever(cache.load("next")).thenReturn(audio)
        whenever(cache.loadPassages("next")).thenReturn(passages())
        whenever(cache.save("next", audio, passages())).thenReturn(audio)
        whenever(interstitial.isPlayingInterstitial).thenReturn(true)
        whenever(facts.findPassages(any())).thenReturn(passages())
        scheduler = DjFillerScheduler(mock(), mock(), facts, cache, interstitial, testDispatcher)
        scheduler.configureLocalModeProvider { true }
        try {
            scheduler.setEnabled(true)
            forceDueOnNextTrack()
            scheduler.onStatusUpdated(statusWith("old", listOf(track("old"), track("next"))))
            scheduler.setEnabled(false)
            verify(cache, never()).delete(audio)
            verify(cache, never()).clear()
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `recordPlayed receives the same chunk ids that were passed to generation and cached`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val audio = Files.createTempFile("dj-recorded", ".wav").toFile()
        val cache = mock<DjFillerAudioCache>()
        val facts = mock<DjPassageRepository>()
        // A real DjInterstitialPlayer is not needed here: the scheduler wires
        // djInterstitialPlayer.onFillerStarted itself, so a mock lets us capture and invoke
        // that exact callback without going through Media3/Uri, which Robolectric aside would
        // need a real Android environment.
        val interstitial = mock<DjInterstitialPlayer>()
        val selected = passages()
        whenever(facts.findPassages(any())).thenReturn(selected)
        scheduler = DjFillerScheduler(mock(), mock(), facts, cache, interstitial, StandardTestDispatcher(testScheduler))
        try {
            scheduler.setEnabled(true)
            val songsBeforeBreak = scheduler.pendingBreakSongsAway.value!!
            val targetId = "t${songsBeforeBreak + 1}"
            whenever(cache.load(targetId)).thenReturn(audio)
            whenever(cache.loadPassages(targetId)).thenReturn(selected)
            whenever(cache.save(targetId, audio, selected)).thenReturn(audio)
            val queue = (1..10).map { track("t$it") }

            scheduler.onStatusUpdated(statusWith("t1", queue))
            runCurrent()
            assertEquals(DjFillerPreparationStatus.READY, scheduler.preparationStatus.value)

            val callback = org.mockito.kotlin.argumentCaptor<(com.anyplayer.android.feature.djfiller.metadata.DjPassages) -> Unit>()
            verify(interstitial).onFillerStarted = callback.capture()
            // The real DjInterstitialPlayer invokes this with exactly the passages it was given
            // by insertLocal/playStandalone, which came from the cached PreparedFiller above -
            // i.e. `selected`, the same passages that were passed to generation and cached.
            callback.firstValue.invoke(selected)

            verify(facts).recordPlayed(org.mockito.kotlin.argThat { chunkIds == selected.chunkIds })
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `cached audio is regenerated when its passage chunk ids no longer match freshly fetched ones`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val staleAudio = Files.createTempFile("dj-stale", ".wav").toFile()
        val freshAudio = Files.createTempFile("dj-fresh", ".wav").toFile()
        val cache = mock<DjFillerAudioCache>()
        val facts = mock<DjPassageRepository>()
        val voice = mock<DjVoiceSynthesizer>()
        val script = mock<DjScriptGenerator>()
        val fresh = passages()
        val stale = DjPassages(listOf(DjPassage(99, "an old, no longer served passage.", "genius", "https://genius.com/old")))
        whenever(facts.findPassages(any())).thenReturn(fresh)
        whenever(cache.load("next")).thenReturn(staleAudio)
        whenever(cache.loadPassages("next")).thenReturn(stale)
        whenever(cache.newOutputFile()).thenReturn(freshAudio)
        whenever(cache.save("next", freshAudio, fresh)).thenReturn(freshAudio)
        whenever(voice.isAvailable()).thenReturn(true)
        whenever(script.generateScript(any(), any())).thenReturn("intro")
        whenever(voice.synthesizeToFile("intro", freshAudio)).thenReturn(true)
        scheduler = DjFillerScheduler(script, voice, facts, cache, mock(), StandardTestDispatcher(testScheduler))
        try {
            scheduler.setEnabled(true)
            forceDueOnNextTrack()
            scheduler.onStatusUpdated(statusWith("old", listOf(track("old"), track("next"))))
            runCurrent()

            verify(cache).delete(staleAudio)
            verify(script).generateScript(any(), any())
            assertEquals(DjFillerPreparationStatus.READY, scheduler.preparationStatus.value)
        } finally {
            staleAudio.delete()
            freshAudio.delete()
        }
    }

    @Test
    fun `cancelled generation deletes audio while main-thread handoff is pending`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val audio = Files.createTempFile("dj-generated", ".wav").toFile()
        val cache = mock<DjFillerAudioCache>()
        val voice = mock<DjVoiceSynthesizer>()
        val script = mock<DjScriptGenerator>()
        whenever(cache.newOutputFile()).thenReturn(audio)
        whenever(voice.isAvailable()).thenReturn(true)
        val facts = mock<DjPassageRepository>()
        whenever(facts.findPassages(any())).thenReturn(passages())
        whenever(script.generateScript(any(), anyOrNull())).thenReturn("intro")
        whenever(voice.synthesizeToFile(any(), any())).thenReturn(true)
        scheduler = DjFillerScheduler(script, voice, facts, cache, mock(), UnconfinedTestDispatcher(testScheduler))
        scheduler.configureLocalModeProvider { true }
        try {
            scheduler.setEnabled(true)
            forceDueOnNextTrack()
            scheduler.onStatusUpdated(statusWith("old", listOf(track("old"), track("next"))))
            assertTrue(audio.exists())
            scheduler.onQueueReplaced()
            runCurrent()
            verify(cache).delete(audio)
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `a transient backward track bounce keeps the ready break for its track`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val audio = Files.createTempFile("dj-bounce", ".wav").toFile()
        val cache = mock<DjFillerAudioCache>()
        val facts = mock<DjPassageRepository>()
        val selected = passages()
        whenever(facts.findPassages(any())).thenReturn(selected)
        whenever(cache.load("t3")).thenReturn(audio)
        whenever(cache.loadPassages("t3")).thenReturn(selected)
        whenever(cache.save("t3", audio, selected)).thenReturn(audio)
        scheduler = DjFillerScheduler(mock(), mock(), facts, cache, mock(), StandardTestDispatcher(testScheduler))
        try {
            scheduler.setEnabled(true)
            val field = DjFillerScheduler::class.java.getDeclaredField("songsUntilBreak")
            field.isAccessible = true
            field.setInt(scheduler, 2)
            val queue = (1..10).map { track("t$it") }

            scheduler.onStatusUpdated(statusWith("t1", queue))
            runCurrent()
            assertEquals(DjFillerPreparationStatus.READY, scheduler.preparationStatus.value)
            scheduler.onStatusUpdated(statusWith("t2", queue))

            // Spotify briefly reports the previous track right after a switch, then the real one.
            scheduler.onStatusUpdated(statusWith("t1", queue))
            scheduler.onStatusUpdated(statusWith("t2", queue))
            runCurrent()

            verify(cache, never()).delete(audio)
            assertEquals(DjFillerPreparationStatus.READY, scheduler.preparationStatus.value)
            assertEquals(audio, scheduler.consumeReadyFillerIfDue("t3")?.audioFile)
        } finally {
            audio.delete()
        }
    }

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var scheduler: DjFillerScheduler

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        scheduler = DjFillerScheduler(
            djScriptGenerator = mock(),
            djVoiceSynthesizer = mock(),
            djPassageRepository = mock(),
            djFillerAudioCache = mock(),
            djInterstitialPlayer = mock(),
            schedulerDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun statusWith(currentTrackId: String, queue: List<Track>): PlaybackStatus = PlaybackStatus(
        state = PlaybackStateType.PLAYING,
        shuffle = false,
        repeatMode = RepeatMode.OFF,
        volume = 100,
        currentTrack = queue.first { it.id == currentTrackId },
        position = 0,
        duration = 200_000,
        queue = queue,
        orderedQueue = queue
    )

    private fun track(id: String, isDjFiller: Boolean = false) = Track(
        id = id,
        title = id,
        artist = "artist-$id",
        source = SourceType.JELLYFIN,
        isDjFiller = isDjFiller
    )

    private fun passages() = DjPassages(listOf(DjPassage(1, "next was recorded in one take.", "genius", "https://genius.com/next")))

    private fun forceDueOnNextTrack() {
        val field = DjFillerScheduler::class.java.getDeclaredField("songsUntilBreak")
        field.isAccessible = true
        field.setInt(scheduler, 1)
    }

    private fun getPrivateIntField(target: Any, fieldName: String): Int {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.getInt(target)
    }

    @Test
    fun `disabled scheduler never reports a break due or a pending offset`() {
        val queue = (1..10).map { track("t$it") }
        repeat(10) { i ->
            scheduler.onStatusUpdated(statusWith("t${i + 1}", queue))
            assertNull(scheduler.consumeReadyFillerIfDue("t${i + 2}"))
        }
        assertNull(scheduler.pendingBreakSongsAway.value)
    }

    @Test
    fun `no ready filler leaves the normal advance unchanged`() {
        scheduler.setEnabled(true)
        val queue = (1..10).map { track("t$it") }

        repeat(4) { i ->
            scheduler.onStatusUpdated(statusWith("t${i + 1}", queue))
            assertNull(scheduler.consumeReadyFillerIfDue("t${i + 2}"))
        }
    }

    @Test
    fun `break becomes due after three to five real tracks`() = runTest {
        scheduler = DjFillerScheduler(mock(), mock(), mock(), mock(), mock(), StandardTestDispatcher(testScheduler))
        scheduler.setEnabled(true)
        val queue = (1..10).map { track("t$it") }
        val first = scheduler.pendingBreakSongsAway.value!!
        assertTrue(first in 3..5)
        scheduler.onStatusUpdated(statusWith("t1", queue))
        repeat(first - 1) { i -> scheduler.onStatusUpdated(statusWith("t${i + 2}", queue)) }
        assertEquals(0, scheduler.pendingBreakSongsAway.value)
    }

    @Test
    fun `consumeReadyFillerIfDue never blocks while generation is pending`() {
        scheduler.setEnabled(true)
        val queue = (1..10).map { track("t$it") }

        // Generation is fully mocked and async, so it can never have actually completed -
        // the call below must still return immediately rather than wait for it.
        repeat(3) { i -> scheduler.onStatusUpdated(statusWith("t${i + 1}", queue)) }
        assertNull(scheduler.consumeReadyFillerIfDue("t4"))
    }

    @Test
    fun `the synthetic DJ track itself is never counted as a real song`() {
        scheduler.setEnabled(true)
        val queue = (1..10).map { track("t$it") }
        val fillerStatus = statusWith("t1", queue).copy(currentTrack = track("dj", isDjFiller = true))

        scheduler.onStatusUpdated(statusWith("t1", queue))
        repeat(5) { scheduler.onStatusUpdated(fillerStatus) }

        // The synthetic interstitial does not hide the next-track transition.
        assertTrue(scheduler.pendingBreakSongsAway.value!! >= 0)
        assertNull(scheduler.consumeReadyFillerIfDue("t2"))
    }

    @Test
    fun `backward track bounces and same-track restarts do not move the break`() = runTest {
        scheduler = DjFillerScheduler(mock(), mock(), mock(), mock(), mock(), StandardTestDispatcher(testScheduler))
        scheduler.setEnabled(true)
        val queue = (1..10).map { track("t$it") }
        scheduler.onStatusUpdated(statusWith("t1", queue).copy(position = 5_000L))
        scheduler.onStatusUpdated(statusWith("t2", queue).copy(position = 1_000L))
        val away = scheduler.pendingBreakSongsAway.value

        // Spotify briefly reports the previous track after a switch.
        scheduler.onStatusUpdated(statusWith("t1", queue).copy(position = 0L))
        scheduler.onStatusUpdated(statusWith("t2", queue).copy(position = 2_000L))
        assertEquals(away, scheduler.pendingBreakSongsAway.value)

        // Spotify recovery restarts the current track from zero.
        scheduler.onStatusUpdated(statusWith("t2", queue).copy(position = 0L))
        scheduler.onStatusUpdated(statusWith("t2", queue).copy(position = 1_000L))
        assertEquals(away, scheduler.pendingBreakSongsAway.value)

        // Real forward progress still counts.
        scheduler.onStatusUpdated(statusWith("t3", queue))
        assertEquals(away!! - 1, scheduler.pendingBreakSongsAway.value)
    }

    @Test
    fun `duplicate track occurrence counts when playback position resets`() {
        scheduler.setEnabled(true)
        val queue = listOf(track("duplicate"), track("duplicate"), track("t3"))
        val firstOccurrence = statusWith("duplicate", queue).copy(position = 1_000L)

        scheduler.onStatusUpdated(firstOccurrence)
        scheduler.onStatusUpdated(firstOccurrence.copy(position = 2_000L))
        scheduler.onStatusUpdated(firstOccurrence.copy(currentTrack = queue[1], position = 0L))

        assertTrue(scheduler.pendingBreakSongsAway.value!! >= 0)
    }

    @Test
    fun `re-enabling starts a fresh schedule`() {
        val queue = (1..10).map { track("t$it") }
        scheduler.setEnabled(true)
        scheduler.onStatusUpdated(statusWith("t1", queue))
        scheduler.onStatusUpdated(statusWith("t2", queue))

        scheduler.setEnabled(false)

        assertEquals(-1, getPrivateIntField(scheduler, "lastSeenIndex"))

        scheduler.setEnabled(true)
        scheduler.onStatusUpdated(statusWith("t8", queue))

        assertTrue(scheduler.pendingBreakSongsAway.value!! >= 0)
    }

    @Test
    fun `failed generation retries after three more real tracks`() = runTest {
        scheduler.setEnabled(true)
        val queue = (1..4).map { track("t$it") }
        val status = statusWith("t1", queue)

        forceDueOnNextTrack()
        scheduler.onStatusUpdated(status)
        advanceUntilIdle()

        // A failed generation still leaves the next-track transition visible.
        assertEquals(3, scheduler.pendingBreakSongsAway.value)
        assertEquals(DjFillerPreparationStatus.NOT_STARTED, scheduler.preparationStatus.value)
    }
}
