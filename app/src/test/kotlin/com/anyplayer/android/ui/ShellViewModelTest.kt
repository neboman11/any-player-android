package com.anyplayer.android.ui

import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.sync.AppStartup
import com.anyplayer.android.data.sync.StartupStatus
import com.anyplayer.android.playback.ActiveBreak
import com.anyplayer.android.playback.PlayerState
import com.anyplayer.android.ui.shell.ShellViewModel
import com.anyplayer.android.ui.shell.SpotifyRedirectHolder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var viewModel: ShellViewModel? = null
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { viewModel?.viewModelScope?.cancel(); Dispatchers.resetMain() }

    @Test fun callbackReplaysToBothShellAndSettingsAndRotationDoesNotRepublishHandledUri() = runTest(dispatcher) {
        val holder = SpotifyRedirectHolder()
        val callback = "anyplayer://spotify-callback?code=code&state=state"
        holder.publish(callback)
        assertEquals(callback, holder.redirects.first())
        assertEquals(callback, holder.redirects.first())
        holder.clearHandled()
        holder.publish(callback)
        assertTrue(holder.redirects.replayCache.isEmpty())
        holder.publish("anyplayer://spotify-callback?code=new&state=new")
        assertEquals(1, holder.redirects.replayCache.size)
    }

    @Test fun newestCallbackIsRetainedWhileSettingsHandlesEarlierCallback() = runTest(dispatcher) {
        val holder = SpotifyRedirectHolder()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.redirects.collect { awaitCancellation() }
        }
        holder.publish("first")
        runCurrent()
        holder.publish("second")
        holder.publish("latest")
        assertEquals(listOf("latest"), holder.redirects.replayCache)
    }

    @Test fun miniPlayerAndStartupBannerFollowTheirOwnStateAndRetryUsesCoordinator() = runTest(dispatcher) {
        var retries = 0
        val startup = object : AppStartup {
            override val status = MutableStateFlow(StartupStatus())
            override fun start() = Unit
            override fun retry() { retries++ }
        }
        val engine = RecordingEngine()
        val vm = ShellViewModel(engine, startup, SpotifyRedirectHolder()).also { viewModel = it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.miniPlayer.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.startupBanner.collect() }
        runCurrent()
        assertFalse(vm.miniPlayer.value.visible)
        engine.queue.value = QueueState(listOf(QueueEntry(1, track("song", "Track title"))), listOf(0), 0)
        engine.player.value = PlayerState(status = PlaybackStateType.PLAYING)
        startup.status.value = StartupStatus(false, "Startup complete with warnings", listOf("Offline"))
        runCurrent()
        assertEquals("Track title", vm.miniPlayer.value.title)
        assertTrue(vm.miniPlayer.value.playing)
        assertTrue(vm.startupBanner.value.visible)
        vm.retryStartup()
        assertEquals(1, retries)
        engine.player.value = engine.player.value.copy(activeBreak = ActiveBreak("break", 1000))
        runCurrent()
        assertEquals("AnyPlayer DJ", vm.miniPlayer.value.title)
    }
}
