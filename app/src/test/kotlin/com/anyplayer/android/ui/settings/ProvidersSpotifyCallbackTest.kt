package com.anyplayer.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
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
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { viewModels.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    private fun providers(redirects: SpotifyRedirectSource, auth: FakeAuth = FakeAuth()) =
        ProvidersSettingsViewModel(auth, FakeConnections(), FakeCatalog(), redirects).also { viewModels += it }

    @Test fun callbackAsksSettingsToRevealProvidersUntilAcknowledged() = runTest(dispatcher) {
        val callbacks = MutableSharedFlow<String>()
        val vm = providers(object : SpotifyRedirectSource { override val redirects = callbacks })
        runCurrent()
        assertFalse(vm.state.value.revealSpotifyResult)
        callbacks.emit("anyplayer://spotify-callback?code=code&state=state")
        assertTrue(vm.state.first { it.feedback == "Spotify connected successfully." }.revealSpotifyResult)
        vm.spotifyResultRevealed()
        assertFalse(vm.state.value.revealSpotifyResult)
        assertEquals("Spotify connected successfully.", vm.state.value.feedback)
    }
}
