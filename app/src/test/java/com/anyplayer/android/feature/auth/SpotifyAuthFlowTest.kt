package com.anyplayer.android.feature.auth

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.network.ProviderConnectionCheck
import com.anyplayer.android.core.rust.RustBridge
import com.anyplayer.android.feature.auth.spotify.SpotifyAuthClient
import com.anyplayer.android.feature.auth.spotify.SpotifyClientIds
import com.anyplayer.android.feature.auth.spotify.SpotifyTokenExchangeResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
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
        ).thenReturn(SpotifyTokenExchangeResult(VALID_TOKEN, FIXTURE_REFRESH_TOKEN, 3600))
        whenever(spotifyAuthClient.validate(VALID_TOKEN))
            .thenReturn(ProviderConnectionCheck.Failed("token rejected"))

        val error = runCatching {
            flow.completeAuth("$REDIRECT_URI?state=expected-state&code=authorization-code")
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("token rejected", error?.message)
        verify(spotifyAuthSessionStore).clearPending()
    }

    @Test
    fun refreshTokenIfNeeded_tokenFarFromExpiry_returnsCurrentTokenWithoutNetworkCall() = runTest {
        whenever(secureConnectionStore.read(SourceType.SPOTIFY)).thenReturn(
            storedConnection(token = VALID_TOKEN, tokenExpiresAt = farFutureMs())
        )

        val result = flow.refreshTokenIfNeeded()

        assertEquals(VALID_TOKEN, result)
        verify(spotifyAuthClient, times(0)).refreshAccessToken(SpotifyClientIds.ACTIVE, FIXTURE_REFRESH_TOKEN)
    }

    @Test
    fun refreshTokenIfNeeded_expiredWithRefreshToken_refreshesSavesAndReturnsNewToken() = runTest {
        whenever(secureConnectionStore.read(SourceType.SPOTIFY)).thenReturn(
            storedConnection(token = EXPIRING_TOKEN, tokenExpiresAt = System.currentTimeMillis())
        )
        whenever(spotifyAuthClient.refreshAccessToken(SpotifyClientIds.ACTIVE, FIXTURE_REFRESH_TOKEN))
            .thenReturn(SpotifyTokenExchangeResult(VALID_TOKEN, NEW_REFRESH_TOKEN, 3600))
        whenever(spotifyAuthClient.validate(VALID_TOKEN)).thenReturn(
            ProviderConnectionCheck.Connected(username = "u", metadata = emptyMap())
        )

        val result = flow.refreshTokenIfNeeded()

        assertEquals(VALID_TOKEN, result)
        verify(secureConnectionStore).save(
            argThatToken { it.token == VALID_TOKEN && it.refreshToken == NEW_REFRESH_TOKEN }
        )
    }

    @Test
    fun refreshTokenIfNeeded_refreshCallThrows_fallsBackToCurrentTokenWhileStillValid() = runTest {
        // Not yet past its real expiry, just inside the refresh window - a transient
        // network failure here should still leave the caller with a usable token.
        val stillValidExpiry = System.currentTimeMillis() + 60_000L
        whenever(secureConnectionStore.read(SourceType.SPOTIFY)).thenReturn(
            storedConnection(token = EXPIRING_TOKEN, tokenExpiresAt = stillValidExpiry)
        )
        whenever(spotifyAuthClient.refreshAccessToken(SpotifyClientIds.ACTIVE, FIXTURE_REFRESH_TOKEN))
            .thenThrow(RuntimeException("network down"))

        val result = flow.refreshTokenIfNeeded()

        assertEquals(EXPIRING_TOKEN, result)
        verify(secureConnectionStore, times(0)).save(org.mockito.kotlin.any())
    }

    @Test
    fun refreshTokenIfNeeded_pastExpiryWithNoRefreshToken_returnsNull() = runTest {
        whenever(secureConnectionStore.read(SourceType.SPOTIFY)).thenReturn(
            StoredConnection(
                source = SourceType.SPOTIFY,
                token = EXPIRED_TOKEN,
                refreshToken = null,
                tokenExpiresAt = System.currentTimeMillis() - 1_000L
            )
        )

        val result = flow.refreshTokenIfNeeded()

        assertNull(result)
    }

    @Test
    fun refreshTokenIfNeeded_concurrentCallers_onlyOneNetworkRefreshHappens() = runTest {
        // Regression for the SpotifyConnectBridge poll loop / SpotifyPlaybackController
        // command path racing on this exact method (finding #1, PR 33 review): without
        // refreshMutex serializing the read-check-refresh-save section, both callers
        // would independently refresh and the loser's save() could persist a
        // stale/rotated-out token.
        val expiring = storedConnection(token = EXPIRING_TOKEN, tokenExpiresAt = System.currentTimeMillis())
        val refreshed = storedConnection(token = VALID_TOKEN, tokenExpiresAt = farFutureMs())
        whenever(secureConnectionStore.read(SourceType.SPOTIFY)).thenReturn(expiring, refreshed)
        whenever(spotifyAuthClient.refreshAccessToken(SpotifyClientIds.ACTIVE, FIXTURE_REFRESH_TOKEN))
            .thenReturn(SpotifyTokenExchangeResult(VALID_TOKEN, NEW_REFRESH_TOKEN, 3600))
        whenever(spotifyAuthClient.validate(VALID_TOKEN)).thenReturn(
            ProviderConnectionCheck.Connected(username = "u", metadata = emptyMap())
        )

        val results = listOf(
            async { flow.refreshTokenIfNeeded() },
            async { flow.refreshTokenIfNeeded() }
        ).awaitAll()

        assertTrue(results.all { it == VALID_TOKEN })
        verify(spotifyAuthClient, times(1)).refreshAccessToken(SpotifyClientIds.ACTIVE, FIXTURE_REFRESH_TOKEN)
        verify(secureConnectionStore, times(1)).save(org.mockito.kotlin.any())
    }

    private fun storedConnection(token: String, tokenExpiresAt: Long) = StoredConnection(
        source = SourceType.SPOTIFY,
        token = token,
        refreshToken = FIXTURE_REFRESH_TOKEN,
        tokenExpiresAt = tokenExpiresAt
    )

    private fun farFutureMs() = System.currentTimeMillis() + 60 * 60 * 1000L

    private fun argThatToken(predicate: (StoredConnection) -> Boolean): StoredConnection =
        org.mockito.kotlin.argThat { predicate(this) }

    private fun pendingSession() = PendingSpotifyAuthSession(
        state = "expected-state",
        codeVerifier = "code-verifier",
        clientId = "spotify-client-id",
        redirectUri = REDIRECT_URI
    )

    private companion object {
        const val REDIRECT_URI = "anyplayer://spotify/callback"
        const val VALID_TOKEN = "valid-spotify-token"
        const val EXPIRING_TOKEN = "test-fixture-expiring-token"
        const val EXPIRED_TOKEN = "test-fixture-expired-token"
        const val FIXTURE_REFRESH_TOKEN = "test-fixture-refresh-token"
        const val NEW_REFRESH_TOKEN = "test-fixture-new-refresh-token"
    }
}
