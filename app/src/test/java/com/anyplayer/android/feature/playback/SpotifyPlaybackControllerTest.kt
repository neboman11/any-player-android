package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.rust.RustBridge
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import com.anyplayer.android.feature.auth.SecureConnectionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpotifyPlaybackControllerTest {
    private lateinit var providerAuthRepository: ProviderAuthRepository
    private lateinit var secureConnectionStore: SecureConnectionStore
    private lateinit var rustBridge: RustBridge
    private lateinit var connectBridge: SpotifyConnectBridge
    private lateinit var controller: SpotifyPlaybackController

    @Before
    fun setUp() {
        providerAuthRepository = mock()
        secureConnectionStore = mock()
        rustBridge = mock()
        connectBridge = mock()
        controller = SpotifyPlaybackController(
            providerAuthRepository = providerAuthRepository,
            secureConnectionStore = secureConnectionStore,
            rustBridge = rustBridge,
            connectBridge = connectBridge
        )
    }

    @Test
    fun setVolume_sendsNormalizedSpotifyVolumeToConnect() = runTest {
        whenever(providerAuthRepository.refreshSpotifyTokenIfNeeded()).thenReturn(ACCESS_TOKEN)
        whenever(rustBridge.applyAudioNormalizationVolume(REQUESTED_VOLUME, SourceType.SPOTIFY))
            .thenReturn(NORMALIZED_VOLUME)
        whenever(connectBridge.setVolume(ACCESS_TOKEN, NORMALIZED_VOLUME)).thenReturn(true)
        whenever(connectBridge.setVolume(ACCESS_TOKEN, REQUESTED_VOLUME)).thenReturn(true)

        val succeeded = controller.setVolume(REQUESTED_VOLUME)

        assertTrue(succeeded)
        verify(rustBridge)
            .applyAudioNormalizationVolume(REQUESTED_VOLUME, SourceType.SPOTIFY)
        verify(connectBridge).setVolume(ACCESS_TOKEN, NORMALIZED_VOLUME)
        verify(connectBridge, never()).setVolume(ACCESS_TOKEN, REQUESTED_VOLUME)
    }

    private companion object {
        const val ACCESS_TOKEN = "spotify-access-token"
        const val REQUESTED_VOLUME = 80
        const val NORMALIZED_VOLUME = 43
    }
}
