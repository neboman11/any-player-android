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
    private val login = SpotifyLoginStatus()
    // Auth completion runs on Dispatchers.IO; each finished exchange is signalled here.
    private val completions = Channel<Unit>(Channel.UNLIMITED)
    private val auth = FakeAuth().apply { onCompleteSpotify = { completions.send(Unit) } }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { viewModels.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    private fun providers() = ProvidersSettingsViewModel(auth, FakeConnections(), FakeCatalog(), holder, login).also { viewModels += it }
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

    @Test fun revealedSuccessDoesNotReplayWhenSettingsIsReopened() = runTest(dispatcher) {
        val old = providers()
        runCurrent()
        holder.publish(CALLBACK)
        old.connected()
        old.spotifyResultRevealed()
        old.viewModelScope.cancel()

        val reopened = providers()
        runCurrent()
        assertFalse(reopened.state.value.revealSpotifyResult)
        assertEquals("", reopened.state.value.feedback)
        assertFalse(reopened.state.value.busy)
    }

    @Test fun revealedFailureDoesNotReplayWhenSettingsIsReopened() = runTest(dispatcher) {
        auth.onCompleteSpotify = { error("exchange rejected") }
        val old = providers()
        runCurrent()
        holder.publish(CALLBACK)
        old.state.first { it.feedback == "Spotify login failed: exchange rejected" }
        old.spotifyResultRevealed()
        old.viewModelScope.cancel()

        val reopened = providers()
        runCurrent()
        assertFalse(reopened.state.value.revealSpotifyResult)
        assertEquals("", reopened.state.value.feedback)
    }

    @Test fun revealingProgressDoesNotAcknowledgeLaterSuccess() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val started = Channel<Unit>(Channel.UNLIMITED)
        auth.onCompleteSpotify = { started.send(Unit); release.await(); completions.send(Unit) }
        val old = providers()
        runCurrent()
        holder.publish(CALLBACK)
        started.receive()
        old.state.first { it.feedback == "Finishing Spotify login..." }
        old.spotifyResultRevealed()
        old.viewModelScope.cancel()
        release.complete(Unit)

        val reopened = providers()
        assertTrue(reopened.connected().revealSpotifyResult)
    }

    @Test fun callbackIsHandledOnceAcrossLiveAndLaterViewModels() = runTest(dispatcher) {
        // A saved Settings back-stack entry keeps its ViewModel collecting while a new one is created.
        val live = listOf(providers(), providers())
        runCurrent()
        holder.publish(CALLBACK)
        runCurrent()
        // The exchange is claimed once, but its process-owned outcome is visible to every observer.
        live.first().connected()
        val later = providers()
        assertEquals("Spotify connected successfully.", later.connected().feedback)
        live.last().connected()
        completions.receive()
        assertTrue(completions.tryReceive().isFailure)
    }

    @Test fun callbackPublishedBeforeSettingsExistsIsHandledByTheFirstViewModel() = runTest(dispatcher) {
        holder.publish(CALLBACK)
        providers().connected()
        completions.receive()
    }

    @Test fun claimedCallbackCompletesAfterItsViewModelIsCancelledAndNewSettingsSeesSuccess() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val started = Channel<Unit>(Channel.UNLIMITED)
        auth.onCompleteSpotify = { started.send(Unit); release.await(); completions.send(Unit) }
        val old = providers()
        runCurrent()
        holder.publish(CALLBACK)
        started.receive()
        old.viewModelScope.cancel()
        val reopened = providers()
        runCurrent()
        assertEquals("Finishing Spotify login...", reopened.state.value.feedback)
        release.complete(Unit)
        reopened.connected()
        assertFalse(reopened.state.value.busy)
        assertTrue(reopened.state.value.revealSpotifyResult)
        completions.receive()
        assertTrue(completions.tryReceive().isFailure)
        assertFalse(holder.consume(CALLBACK))
    }

    @Test fun cancelledSettingsStillPublishesExchangeFailureForNextViewModel() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val started = Channel<Unit>(Channel.UNLIMITED)
        auth.onCompleteSpotify = { started.send(Unit); release.await(); error("exchange rejected") }
        val old = providers()
        runCurrent()
        holder.publish(CALLBACK)
        started.receive()
        old.viewModelScope.cancel()
        release.complete(Unit)
        val reopened = providers()
        val result = reopened.state.first { it.feedback == "Spotify login failed: exchange rejected" }
        assertFalse(result.busy)
        assertTrue(result.revealSpotifyResult)
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
