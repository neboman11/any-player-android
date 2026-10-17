package com.anyplayer.android.ui

import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.*
import com.anyplayer.android.dj.PreparationStatus
import com.anyplayer.android.playback.ActiveBreak
import com.anyplayer.android.playback.PlayerState
import com.anyplayer.android.ui.nowplaying.*
import com.anyplayer.android.ui.settings.FakeDj
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var viewModel: NowPlayingViewModel? = null
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { viewModel?.viewModelScope?.cancel(); Dispatchers.resetMain() }

    @Test fun duplicateTrackIdsKeepUidIdentityAndDjInsertionUsesPlayOrder() = runTest(dispatcher) {
        val repeated = track("same")
        val engine = RecordingEngine().apply {
            queue.value = QueueState(
                entries = listOf(QueueEntry(10, track("current")), QueueEntry(11, repeated), QueueEntry(12, repeated), QueueEntry(13, track("last"))),
                order = listOf(0, 2, 1, 3), cursor = 0, contentVersion = 1,
            )
        }
        val dj = FakeDj().apply {
            showEntriesInQueue.value = true
            songsAway.value = 1
            preparationStatus.value = PreparationStatus.READY
        }
        val vm = NowPlayingViewModel(engine, dj, dispatcher).also { viewModel = it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        val upcoming = vm.state.value.queue.upcoming
        assertEquals(listOf(12L, null, 11L, 13L), upcoming.map { it.uid })
        assertEquals(upcoming.size, upcoming.map { it.key }.toSet().size)
        assertEquals("AnyPlayer DJ", upcoming[1].title)
        assertEquals(PreparationStatus.READY, upcoming[1].djStatus)
        vm.playEntry(11)
        assertEquals(11L, engine.playedUid)

        // Player-only changes must not rebuild every queue row.
        val before = vm.state.value.queue
        engine.player.value = PlayerState(volume = 31)
        runCurrent()
        assertSame(before, vm.state.value.queue)
        engine.queue.value = engine.queue.value.copy(cursor = 2)
        runCurrent()
        assertEquals(listOf(10L, 12L), vm.state.value.queue.history.map { it.uid })
    }

    @Test fun breakPresentationAndGuardComeFromPlayerState() = runTest(dispatcher) {
        val engine = RecordingEngine().apply {
            queue.value = QueueState(listOf(QueueEntry(1, track())), listOf(0), 0)
            player.value = PlayerState(activeBreak = ActiveBreak("break", 4000), disabledReason = "Playback disabled", error = "Network error")
        }
        val vm = NowPlayingViewModel(engine, FakeDj(), dispatcher).also { viewModel = it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        assertEquals("AnyPlayer DJ", vm.state.value.title)
        assertEquals(4000L, vm.state.value.durationMs)
        assertTrue(vm.state.value.canSeek)
        assertEquals("Playback disabled", vm.state.value.disabledReason)
        assertEquals("Network error", vm.state.value.error)
        assertFalse(vm.state.value.queue.isEmpty) // A single current song is still a loaded queue.
    }

    @Test fun emptyQueueNeverShowsSyntheticDjOnlyQueue() {
        assertTrue(QueueUiBuilder.build(QueueState(), DjQueueHint(true, 0, PreparationStatus.READY)).isEmpty)
    }

    @Test fun sliderDragsCommitOnlyOnceOnFinishToEngine() = runTest(dispatcher) {
        val engine = RecordingEngine()
        val vm = NowPlayingViewModel(engine, FakeDj(), dispatcher).also { viewModel = it }
        val seek = SliderDragState()
        seek.onValueChange(1200f)
        seek.onValueChange(2500f)
        assertTrue(engine.seeks.isEmpty())
        assertEquals(2500f, seek.displayValue(100f))
        seek.onValueChangeFinished { vm.seekTo(it.toLong()) }
        seek.onValueChangeFinished { vm.seekTo(it.toLong()) }
        assertEquals(listOf(2500L), engine.seeks)
        assertEquals(3000f, seek.displayValue(3000f))
        val volume = SliderDragState()
        volume.onValueChange(20f)
        volume.onValueChange(45f)
        assertTrue(engine.volumes.isEmpty())
        volume.onValueChangeFinished { vm.setVolume(it.toInt()) }
        assertEquals(listOf(45), engine.volumes)
    }
}
