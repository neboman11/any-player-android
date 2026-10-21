package com.anyplayer.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.ui.shell.SpotifyRedirectHolder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProvidersSpotifyCallbackTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<ViewModel>()
    private val holder = SpotifyRedirectHolder()
    // Auth completion runs on Dispatchers.IO; each finished exchange is signalled here.
    private val completions = Channel<Unit>(Channel.UNLIMITED)
    private val auth = FakeAuth().apply { onCompleteSpotify = { completions.send(Unit) } }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { viewModels.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    private fun providers() = ProvidersSettingsViewModel(auth, FakeConnections(), FakeCatalog(), holder).also { viewModels += it }
    private suspend fun ProvidersSettingsViewModel.connected() = state.first { it.feedback == "Spotify connected successfully." }

    @Test fun callbackAsksSettingsToRevealProvidersUntilAcknowledged() = runTest(dispatcher) {
        val vm = providers()
        runCurrent()
        assertFalse(vm.state.value.revealSpotifyResult)
        holder.publish(CALLBACK)
        assertTrue(vm.connected().revealSpotifyResult)
        vm.spotifyResultRevealed()
        assertFalse(vm.state.value.revealSpotifyResult)
        assertEquals("Spotify connected successfully.", vm.state.value.feedback)
    }

    @Test fun callbackIsHandledOnceAcrossLiveAndLaterViewModels() = runTest(dispatcher) {
        // A saved Settings back-stack entry keeps its ViewModel collecting while a new one is created.
        val live = listOf(providers(), providers())
        runCurrent()
        holder.publish(CALLBACK)
        runCurrent()
        // Claiming is synchronous on Main: exactly one ViewModel started handling the callback.
        val handler = live.single { it.state.value.feedback.isNotEmpty() }
        handler.connected()
        val later = providers()
        advanceUntilIdle()
        assertEquals("", later.state.value.feedback)
        assertEquals("", live.single { it !== handler }.state.value.feedback)
        completions.receive()
        assertTrue(completions.tryReceive().isFailure)
    }

    @Test fun callbackPublishedBeforeSettingsExistsIsHandledByTheFirstViewModel() = runTest(dispatcher) {
        holder.publish(CALLBACK)
        providers().connected()
        completions.receive()
    }

    @Test fun newlyDeliveredCallbacksAreHandledEvenWhenTheirUrisMatch() = runTest(dispatcher) {
        val vm = providers()
        runCurrent()
        holder.publish(CALLBACK)
        completions.receive()
        vm.connected()
        // Restoration must be filtered by MainActivity, not an in-memory URI comparison.
        holder.publish(CALLBACK)
        completions.receive()
        vm.state.first { !it.busy }
        assertTrue(completions.tryReceive().isFailure)
    }

    private companion object {
        const val CALLBACK = "anyplayer://spotify-callback?code=code&state=state"
    }
}
