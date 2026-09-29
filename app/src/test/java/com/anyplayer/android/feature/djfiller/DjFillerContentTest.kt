package com.anyplayer.android.feature.djfiller

import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.feature.djfiller.metadata.DjPassageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class DjFillerContentTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun `no sourced fact prevents script and audio synthesis and schedules retry`() = runTest {
        val facts = mock<DjPassageRepository>()
        val script = mock<DjScriptGenerator>()
        val voice = mock<DjVoiceSynthesizer>()
        val scheduler = DjFillerScheduler(script, voice, facts, mock(), mock(), dispatcher)
        whenever(voice.isAvailable()).thenReturn(true)
        val queue = (1..10).map { track("t$it") }
        val current = queue.first()
        val status = PlaybackStatus(
            state = PlaybackStateType.PLAYING, shuffle = false, repeatMode = RepeatMode.OFF,
            volume = 100, currentTrack = current, position = 0, duration = 1000,
            queue = queue, orderedQueue = queue
        )
        scheduler.setEnabled(true)
        CompatLog.clearAiDjLogs()
        val target = queue[scheduler.pendingBreakSongsAway.value!!]
        scheduler.onStatusUpdated(status)
        advanceUntilIdle()
        verify(facts).findPassages(target)
        verify(script, never()).generateScript(any(), any())
        verify(voice, never()).synthesizeToFile(any(), any())
        assertEquals(3, scheduler.pendingBreakSongsAway.value)
        assertTrue(CompatLog.aiDjLogs.value.any { it.message.contains("no passages") })

        repeat(3) { scheduler.onStatusUpdated(status) }
        advanceUntilIdle()
        verify(facts, times(1)).findPassages(any())

        scheduler.onStatusUpdated(status.copy(currentTrack = queue[1]))
        advanceUntilIdle()
        verify(facts, times(2)).findPassages(any())
    }

    private fun track(id: String) = Track(id, id, "Artist", source = SourceType.JELLYFIN)
}
