package com.anyplayer.android.feature.auth

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.network.ProviderConnectionCheck
import com.anyplayer.android.core.rust.RustBridge
import com.anyplayer.android.feature.auth.spotify.SpotifyAuthClient
import com.anyplayer.android.feature.auth.spotify.SpotifyClientIds
import com.anyplayer.android.feature.auth.spotify.SpotifyTokenExchangeResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProviderAuthRepositoryImplTest {
    private lateinit var secureConnectionStore: SecureConnectionStore
    private lateinit var spotifyAuthClient: SpotifyAuthClient
    private lateinit var spotifyAuthSessionStore: SpotifyAuthSessionStore
    private lateinit var rustBridge: RustBridge
    private lateinit var repository: ProviderAuthRepositoryImpl

    @Before
    fun setUp() {
        secureConnectionStore = mock()
        spotifyAuthClient = mock()
        spotifyAuthSessionStore = mock()
        rustBridge = mock()
        val spotifyAuthFlow = SpotifyAuthFlow(
            secureConnectionStore = secureConnectionStore,
            spotifyAuthSessionStore = spotifyAuthSessionStore,
            spotifyAuthClient = spotifyAuthClient,
            rustBridge = rustBridge
        )
        repository = ProviderAuthRepositoryImpl(
            secureConnectionStore = secureConnectionStore,
            spotifyAuthClient = spotifyAuthClient,
            rustBridge = rustBridge,
            spotifyAuthFlow = spotifyAuthFlow
        )
    }

    @Test
    fun connect_spotifyPremiumAccount_isConnectedWithoutNativeSession() = runTest {
        whenever(spotifyAuthClient.validate(VALID_TOKEN)).thenReturn(premiumConnection())

        val status = repository.connect(AuthRequest.Spotify(accessToken = VALID_TOKEN))

        assertTrue(status.connected)
        assertTrue(status.playbackReady == true)
        assertTrue(status.isPremium == true)
        val savedConnection = argumentCaptor<StoredConnection>()
        verify(secureConnectionStore).save(savedConnection.capture())
        assertEquals(SourceType.SPOTIFY, savedConnection.firstValue.source)
        assertTrue(savedConnection.firstValue.playbackReady == true)
        verifyNoInteractions(rustBridge)
    }

    @Test
    fun completeSpotifyAuth_spotifyPremiumAccount_isConnectedWithoutNativeSession() = runTest {
        whenever(spotifyAuthSessionStore.readPending()).thenReturn(
            PendingSpotifyAuthSession(
                state = "expected-state",
                codeVerifier = "code-verifier",
                clientId = "spotify-client-id",
                redirectUri = REDIRECT_URI
            )
        )
        whenever(
            spotifyAuthClient.exchangeAuthorizationCode(
                clientId = "spotify-client-id",
                code = "authorization-code",
                codeVerifier = "code-verifier",
                redirectUri = REDIRECT_URI
            )
        ).thenReturn(SpotifyTokenExchangeResult(VALID_TOKEN, "refresh-token", 3600))
        whenever(spotifyAuthClient.validate(VALID_TOKEN)).thenReturn(premiumConnection())
        whenever(
            rustBridge.spotifyExchangeCode(
                code = "authorization-code",
                verifier = "code-verifier",
                redirect = REDIRECT_URI
            )
        ).thenReturn(null)

        val status = repository.completeSpotifyAuth(
            "$REDIRECT_URI?state=expected-state&code=authorization-code"
        )

        assertTrue(status.connected)
        assertTrue(status.playbackReady == true)
        assertTrue(status.isPremium == true)
        val savedConnection = argumentCaptor<StoredConnection>()
        verify(secureConnectionStore).save(savedConnection.capture())
        assertTrue(savedConnection.firstValue.playbackReady == true)
        verify(rustBridge).spotifyExchangeCode(
            code = "authorization-code",
            verifier = "code-verifier",
            redirect = REDIRECT_URI
        )
        verifyNoMoreInteractions(rustBridge)
    }

    @Test
    fun restoreAll_spotifyPremiumAccountWithLegacyUnavailablePlayback_isConnected() = runTest {
        whenever(secureConnectionStore.readAll()).thenReturn(
            listOf(
                StoredConnection(
                    source = SourceType.SPOTIFY,
                    token = VALID_TOKEN,
                    spotifyPremium = true,
                    playbackReady = false
                )
            )
        )
        whenever(spotifyAuthClient.validate(VALID_TOKEN)).thenReturn(premiumConnection())

        val statuses = repository.restoreAll()

        assertEquals(1, statuses.size)
        assertTrue(statuses.single().connected)
        assertTrue(statuses.single().playbackReady == true)
        val savedConnection = argumentCaptor<StoredConnection>()
        verify(secureConnectionStore).save(savedConnection.capture())
        assertTrue(savedConnection.firstValue.playbackReady == true)
        verifyNoInteractions(rustBridge)
        // Regression: playbackReady used to be recomputed via a second validate()
        // call on the same already-Connected token (finding #6, PR 33 review).
        verify(spotifyAuthClient, times(1)).validate(VALID_TOKEN)
    }

    @Test
    fun restoreAll_expiredSpotifyTokenWithRefreshToken_refreshesAndValidatesRefreshedTokenOnce() = runTest {
        whenever(secureConnectionStore.readAll()).thenReturn(
            listOf(
                StoredConnection(
                    source = SourceType.SPOTIFY,
                    token = EXPIRED_TOKEN,
                    refreshToken = FIXTURE_REFRESH_TOKEN,
                    spotifyPremium = true,
                    playbackReady = true
                )
            )
        )
        whenever(spotifyAuthClient.validate(EXPIRED_TOKEN))
            .thenReturn(ProviderConnectionCheck.Failed("token expired"))
        whenever(spotifyAuthClient.refreshAccessToken(SpotifyClientIds.ACTIVE, FIXTURE_REFRESH_TOKEN))
            .thenReturn(SpotifyTokenExchangeResult(VALID_TOKEN, "new-refresh-token", 3600))
        whenever(spotifyAuthClient.validate(VALID_TOKEN)).thenReturn(premiumConnection())

        val statuses = repository.restoreAll()

        assertTrue(statuses.single().connected)
        assertTrue(statuses.single().playbackReady == true)
        val savedConnection = argumentCaptor<StoredConnection>()
        verify(secureConnectionStore).save(savedConnection.capture())
        assertEquals(VALID_TOKEN, savedConnection.firstValue.token)
        assertTrue(savedConnection.firstValue.playbackReady == true)
        // Regression: playbackReady used to be recomputed via a second validate()
        // call on the same freshly-refreshed token (finding #7, PR 33 review).
        verify(spotifyAuthClient, times(1)).validate(VALID_TOKEN)
    }

    private fun premiumConnection() = ProviderConnectionCheck.Connected(
        username = "Premium user",
        metadata = mapOf("isPremium" to "true")
    )

    private companion object {
        const val VALID_TOKEN = "valid-spotify-token"
        const val EXPIRED_TOKEN = "test-fixture-expired-token"
        const val FIXTURE_REFRESH_TOKEN = "test-fixture-refresh-token"
        const val REDIRECT_URI = "anyplayer://spotify/callback"
    }
}
