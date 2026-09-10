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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * [DjFillerScheduler] counting/gating logic and the "songs away" queue-display schedule.
 * Generation itself runs on an internal background scope wired to real (mocked-here)
 * TTS/LLM/network dependencies, so these tests focus on what's deterministic without
 * waiting on that async pipeline: that the randomized 3-5 song threshold is honored, that
 * [DjFillerScheduler.pendingBreakSongsAway] always reflects the schedule regardless of
 * whether generation has finished, that disabling the feature suppresses everything, and -
 * the core zero-wait guarantee - that [DjFillerScheduler.consumeReadyFillerIfDue] never
 * blocks or throws even when generation could not possibly have finished yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DjFillerSchedulerTest {

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

    private fun setThreshold(value: Int) {
        val field = DjFillerScheduler::class.java.getDeclaredField("nextBreakThreshold")
        field.isAccessible = true
        field.setInt(scheduler, value)
    }

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
    fun `break is never due before the 3-5 song threshold`() {
        scheduler.setEnabled(true)
        setThreshold(5)
        val queue = (1..10).map { track("t$it") }

        repeat(4) { i ->
            scheduler.onStatusUpdated(statusWith("t${i + 1}", queue))
            assertNull(scheduler.consumeReadyFillerIfDue("t${i + 2}"))
        }
    }

    @Test
    fun `pendingBreakSongsAway reflects the schedule immediately, independent of generation`() {
        setThreshold(3)
        scheduler.setEnabled(true)
        val queue = (1..10).map { track("t$it") }

        // Visible as soon as the threshold exists, before any real song has even played.
        assertEquals(3, scheduler.pendingBreakSongsAway.value)

        scheduler.onStatusUpdated(statusWith("t1", queue))
        assertEquals(2, scheduler.pendingBreakSongsAway.value)

        scheduler.onStatusUpdated(statusWith("t2", queue))
        assertEquals(1, scheduler.pendingBreakSongsAway.value)

        // Third song change reaches the threshold and kicks off generation - with a mocked,
        // unavailable TTS voice it fails synchronously under the test dispatcher and rolls a
        // fresh 3-5 threshold immediately, which is itself proof the offset never depends on
        // generation succeeding: there's always a valid value, never null or stuck.
        scheduler.onStatusUpdated(statusWith("t3", queue))
        val songsAway = scheduler.pendingBreakSongsAway.value
        assertTrue("expected a non-null offset in [0, 5], was $songsAway", songsAway != null && songsAway in 0..5)
    }

    @Test
    fun `consumeReadyFillerIfDue never blocks even once the threshold is reached`() {
        scheduler.setEnabled(true)
        setThreshold(3)
        val queue = (1..10).map { track("t$it") }

        // Generation is fully mocked and async, so it can never have actually completed -
        // the call below must still return immediately rather than wait for it.
        repeat(3) { i -> scheduler.onStatusUpdated(statusWith("t${i + 1}", queue)) }
        assertNull(scheduler.consumeReadyFillerIfDue("t4"))
    }

    @Test
    fun `the synthetic DJ track itself is never counted as a real song`() {
        scheduler.setEnabled(true)
        setThreshold(5)
        val queue = (1..10).map { track("t$it") }
        val fillerStatus = statusWith("t1", queue).copy(currentTrack = track("dj", isDjFiller = true))

        scheduler.onStatusUpdated(statusWith("t1", queue))
        repeat(5) { scheduler.onStatusUpdated(fillerStatus) }

        // Only one real track change was ever observed - still 4 songs away from the
        // 5-song threshold, not due yet.
        assertEquals(4, scheduler.pendingBreakSongsAway.value)
        assertNull(scheduler.consumeReadyFillerIfDue("t2"))
    }

    @Test
    fun `duplicate track occurrence counts when playback position resets`() {
        scheduler.setEnabled(true)
        setThreshold(5)
        val queue = listOf(track("duplicate"), track("duplicate"), track("t3"))
        val firstOccurrence = statusWith("duplicate", queue).copy(position = 1_000L)

        scheduler.onStatusUpdated(firstOccurrence)
        scheduler.onStatusUpdated(firstOccurrence.copy(position = 2_000L))
        scheduler.onStatusUpdated(firstOccurrence.copy(currentTrack = queue[1], position = 0L))

        assertEquals(3, scheduler.pendingBreakSongsAway.value)
    }

    @Test
    fun `failed generation resets the scheduling state and rolls a fresh offset`() = runTest {
        scheduler.setEnabled(true)
        setThreshold(1)
        val queue = (1..4).map { track("t$it") }
        val status = statusWith("t1", queue)

        val songsField = DjFillerScheduler::class.java.getDeclaredField("songsSinceLastBreak")
        songsField.isAccessible = true
        songsField.setInt(scheduler, 1)

        invokeStartGenerationFor(status, queue.first())
        advanceUntilIdle()

        assertEquals(0, getPrivateIntField(scheduler, "songsSinceLastBreak"))
        // A fresh (randomized 3-5) threshold was rolled, so the offset is showing again
        // rather than being left null.
        assertEquals(getPrivateIntField(scheduler, "nextBreakThreshold"), scheduler.pendingBreakSongsAway.value)
    }
}
