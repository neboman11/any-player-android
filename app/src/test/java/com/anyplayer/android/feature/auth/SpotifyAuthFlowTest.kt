package com.anyplayer.android.feature.auth

import com.anyplayer.android.core.network.ProviderConnectionCheck
import com.anyplayer.android.core.rust.RustBridge
import com.anyplayer.android.feature.auth.spotify.SpotifyAuthClient
import com.anyplayer.android.feature.auth.spotify.SpotifyTokenExchangeResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * Covers completeAuth's error branches (state mismatch, missing code, provider error,
 * failed exchange, failed validation) that ProviderAuthRepositoryImplTest doesn't exercise.
 */
@RunWith(RobolectricTestRunner::class)
class SpotifyAuthFlowTest {

    private lateinit var secureConnectionStore: SecureConnectionStore
    private lateinit var spotifyAuthSessionStore: SpotifyAuthSessionStore
    private lateinit var spotifyAuthClient: SpotifyAuthClient
    private lateinit var rustBridge: RustBridge
    private lateinit var flow: SpotifyAuthFlow

    @Before
    fun setUp() {
        secureConnectionStore = mock()
        spotifyAuthSessionStore = mock()
        spotifyAuthClient = mock()
        rustBridge = mock()
        flow = SpotifyAuthFlow(
            secureConnectionStore = secureConnectionStore,
            spotifyAuthSessionStore = spotifyAuthSessionStore,
            spotifyAuthClient = spotifyAuthClient,
            rustBridge = rustBridge
        )
    }

    @Test
    fun completeAuth_noPendingSession_throwsWithoutClearingPending() = runTest {
        whenever(spotifyAuthSessionStore.readPending()).thenReturn(null)

        val error = runCatching {
            flow.completeAuth("$REDIRECT_URI?state=expected-state&code=authorization-code")
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("No pending Spotify auth session"))
    }

    @Test
    fun completeAuth_callbackContainsError_throwsAndClearsPending() = runTest {
        whenever(spotifyAuthSessionStore.readPending()).thenReturn(pendingSession())

        val error = runCatching {
            flow.completeAuth("$REDIRECT_URI?error=access_denied")
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("access_denied"))
        verify(spotifyAuthSessionStore).clearPending()
    }

    @Test
    fun completeAuth_stateMismatch_throwsAndClearsPending() = runTest {
        whenever(spotifyAuthSessionStore.readPending()).thenReturn(pendingSession())

        val error = runCatching {
            flow.completeAuth("$REDIRECT_URI?state=unexpected-state&code=authorization-code")
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("state mismatch"))
        verify(spotifyAuthSessionStore).clearPending()
    }

    @Test
    fun completeAuth_missingCode_throwsAndClearsPending() = runTest {
        whenever(spotifyAuthSessionStore.readPending()).thenReturn(pendingSession())

        val error = runCatching {
            flow.completeAuth("$REDIRECT_URI?state=expected-state")
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("authorization code"))
        verify(spotifyAuthSessionStore).clearPending()
    }

    @Test
    fun completeAuth_exchangeFails_throwsAndClearsPending() = runTest {
        whenever(spotifyAuthSessionStore.readPending()).thenReturn(pendingSession())
        whenever(
            spotifyAuthClient.exchangeAuthorizationCode(
                clientId = "spotify-client-id",
                code = "authorization-code",
                codeVerifier = "code-verifier",
                redirectUri = REDIRECT_URI
            )
        ).thenReturn(null)

        val error = runCatching {
            flow.completeAuth("$REDIRECT_URI?state=expected-state&code=authorization-code")
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("Unable to exchange"))
        verify(spotifyAuthSessionStore).clearPending()
    }

    @Test
    fun completeAuth_tokenValidationFails_throwsWithValidationMessageAndClearsPending() = runTest {
        whenever(spotifyAuthSessionStore.readPending()).thenReturn(pendingSession())
        whenever(
            spotifyAuthClient.exchangeAuthorizationCode(
                clientId = "spotify-client-id",
                code = "authorization-code",
                codeVerifier = "code-verifier",
                redirectUri = REDIRECT_URI
            )
        ).thenReturn(SpotifyTokenExchangeResult(VALID_TOKEN, "refresh-token", 3600))
        whenever(spotifyAuthClient.validate(VALID_TOKEN))
            .thenReturn(ProviderConnectionCheck.Failed("token rejected"))

        val error = runCatching {
            flow.completeAuth("$REDIRECT_URI?state=expected-state&code=authorization-code")
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("token rejected", error?.message)
        verify(spotifyAuthSessionStore).clearPending()
    }

    private fun pendingSession() = PendingSpotifyAuthSession(
        state = "expected-state",
        codeVerifier = "code-verifier",
        clientId = "spotify-client-id",
        redirectUri = REDIRECT_URI
    )

    private companion object {
        const val REDIRECT_URI = "anyplayer://spotify/callback"
        const val VALID_TOKEN = "valid-spotify-token"
    }
}
