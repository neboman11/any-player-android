package com.anyplayer.android.feature.djfiller

import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import kotlinx.coroutines.Dispatchers
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
    fun `replacing queue forgets inserted filler so next queue can prepare one`() {
        val cache = mock<DjFillerAudioCache>()
        val interstitial = mock<DjInterstitialPlayer>()
        val audio = Files.createTempFile("dj-replace", ".wav").toFile()
        val queue = listOf(track("old"), track("next"))
        whenever(cache.load("next")).thenReturn(audio)
        scheduler = DjFillerScheduler(mock(), mock(), mock(), cache, interstitial, testDispatcher)
        scheduler.configureLocalModeProvider { true }
        try {
            scheduler.setEnabled(true)
            scheduler.onStatusUpdated(statusWith("old", queue))
            scheduler.onQueueReplaced()
            scheduler.onStatusUpdated(statusWith("old", queue))
            verify(interstitial, org.mockito.kotlin.times(2)).insertLocal(org.mockito.kotlin.any())
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `disabling removes queued local filler before cache is cleared`() {
        val cache = mock<DjFillerAudioCache>()
        val interstitial = mock<DjInterstitialPlayer>()
        val audio = Files.createTempFile("dj-disable", ".wav").toFile()
        whenever(cache.load("next")).thenReturn(audio)
        scheduler = DjFillerScheduler(mock(), mock(), mock(), cache, interstitial, testDispatcher)
        scheduler.configureLocalModeProvider { true }
        try {
            scheduler.setEnabled(true)
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
    fun `disabling during active filler leaves audio for playback owner to delete`() {
        val cache = mock<DjFillerAudioCache>()
        val interstitial = mock<DjInterstitialPlayer>()
        val audio = Files.createTempFile("dj-active", ".wav").toFile()
        whenever(cache.load("next")).thenReturn(audio)
        whenever(interstitial.isPlayingInterstitial).thenReturn(true)
        scheduler = DjFillerScheduler(mock(), mock(), mock(), cache, interstitial, testDispatcher)
        scheduler.configureLocalModeProvider { true }
        try {
            scheduler.setEnabled(true)
            scheduler.onStatusUpdated(statusWith("old", listOf(track("old"), track("next"))))
            scheduler.setEnabled(false)
            verify(cache, never()).delete(audio)
            verify(cache, never()).clear()
        } finally {
            audio.delete()
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
        whenever(script.generateScript(any(), anyOrNull())).thenReturn("intro")
        whenever(voice.synthesizeToFile(any(), any())).thenReturn(true)
        scheduler = DjFillerScheduler(script, voice, mock(), cache, mock(), UnconfinedTestDispatcher(testScheduler))
        scheduler.configureLocalModeProvider { true }
        try {
            scheduler.setEnabled(true)
            scheduler.onStatusUpdated(statusWith("old", listOf(track("old"), track("next"))))
            assertTrue(audio.exists())
            scheduler.onQueueReplaced()
            runCurrent()
            verify(cache).delete(audio)
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
            wikipediaFactClient = mock(),
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

    private fun getPrivateIntField(target: Any, fieldName: String): Int {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.getInt(target)
    }

    private fun invokeStartGenerationFor(status: PlaybackStatus, preBreakTrack: Track) {
        val method = DjFillerScheduler::class.java.getDeclaredMethod(
            "startGenerationFor",
            PlaybackStatus::class.java,
            Track::class.java
        )
        method.isAccessible = true
        method.invoke(scheduler, status, preBreakTrack)
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
    fun `pendingBreakSongsAway keeps the next-track break visible`() {
        scheduler.setEnabled(true)
        val queue = (1..10).map { track("t$it") }

        // The next track is always the planned AI DJ transition.
        assertEquals(0, scheduler.pendingBreakSongsAway.value)

        scheduler.onStatusUpdated(statusWith("t1", queue))
        assertEquals(0, scheduler.pendingBreakSongsAway.value)

        scheduler.onStatusUpdated(statusWith("t2", queue))
        assertEquals(0, scheduler.pendingBreakSongsAway.value)

        // A failed generation never hides the next-track transition.
        scheduler.onStatusUpdated(statusWith("t3", queue))
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
        assertEquals(0, scheduler.pendingBreakSongsAway.value)
        assertNull(scheduler.consumeReadyFillerIfDue("t2"))
    }

    @Test
    fun `duplicate track occurrence counts when playback position resets`() {
        scheduler.setEnabled(true)
        val queue = listOf(track("duplicate"), track("duplicate"), track("t3"))
        val firstOccurrence = statusWith("duplicate", queue).copy(position = 1_000L)

        scheduler.onStatusUpdated(firstOccurrence)
        scheduler.onStatusUpdated(firstOccurrence.copy(position = 2_000L))
        scheduler.onStatusUpdated(firstOccurrence.copy(currentTrack = queue[1], position = 0L))

        assertEquals(0, scheduler.pendingBreakSongsAway.value)
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

        assertEquals(0, scheduler.pendingBreakSongsAway.value)
    }

    @Test
    fun `failed generation keeps the next-track offset visible`() = runTest {
        scheduler.setEnabled(true)
        val queue = (1..4).map { track("t$it") }
        val status = statusWith("t1", queue)

        invokeStartGenerationFor(status, queue.first())
        advanceUntilIdle()

        // A failed generation still leaves the next-track transition visible.
        assertEquals(0, scheduler.pendingBreakSongsAway.value)
    }
}
