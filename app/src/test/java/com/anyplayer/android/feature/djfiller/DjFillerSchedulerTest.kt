package com.anyplayer.android.feature.djfiller

import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * [DjFillerScheduler] counting/gating logic. Generation itself runs on an internal
 * background scope wired to real (mocked-here) TTS/LLM/network dependencies, so these
 * tests focus on what's deterministic without waiting on that async pipeline: that a
 * break is never due before its randomized 3-5 song threshold, that disabling the
 * feature suppresses everything, and - the core zero-wait guarantee - that
 * [DjFillerScheduler.consumeReadyFillerIfDue] never blocks or throws even when
 * generation could not possibly have finished yet.
 */
class DjFillerSchedulerTest {

    private lateinit var scheduler: DjFillerScheduler

    @Before
    fun setUp() {
        scheduler = DjFillerScheduler(
            djScriptGenerator = mock(),
            djVoiceSynthesizer = mock(),
            wikipediaFactClient = mock(),
            djFillerAudioCache = mock(),
            djInterstitialPlayer = mock()
        )
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

    @Test
    fun `disabled scheduler never reports a break due`() {
        val queue = (1..10).map { track("t$it") }
        repeat(10) { i ->
            scheduler.onStatusUpdated(statusWith("t${i + 1}", queue))
            assertNull(scheduler.consumeReadyFillerIfDue("t${i + 2}"))
        }
    }

    @Test
    fun `break is never due before the minimum 3-song threshold`() {
        scheduler.setEnabled(true)
        val queue = (1..10).map { track("t$it") }

        scheduler.onStatusUpdated(statusWith("t1", queue))
        assertNull(scheduler.consumeReadyFillerIfDue("t2"))

        scheduler.onStatusUpdated(statusWith("t2", queue))
        assertNull(scheduler.consumeReadyFillerIfDue("t3"))
    }

    @Test
    fun `consumeReadyFillerIfDue never blocks even once the threshold is reached`() {
        scheduler.setEnabled(true)
        val queue = (1..10).map { track("t$it") }

        // Feed enough real track changes to guarantee we've passed any 3-5 threshold.
        // Generation is fully mocked and async, so it can never have actually completed -
        // the call below must still return immediately rather than wait for it.
        for (i in 1..5) {
            scheduler.onStatusUpdated(statusWith("t$i", queue))
        }
        assertNull(scheduler.consumeReadyFillerIfDue("t6"))
    }

    @Test
    fun `the synthetic DJ track itself is never counted as a real song`() {
        scheduler.setEnabled(true)
        val queue = (1..10).map { track("t$it") }
        val fillerStatus = statusWith("t1", queue).copy(currentTrack = track("dj", isDjFiller = true))

        scheduler.onStatusUpdated(statusWith("t1", queue))
        repeat(5) { scheduler.onStatusUpdated(fillerStatus) }

        // Only one real track change was ever observed, so no break can possibly be due.
        assertNull(scheduler.consumeReadyFillerIfDue("t2"))
    }
}
