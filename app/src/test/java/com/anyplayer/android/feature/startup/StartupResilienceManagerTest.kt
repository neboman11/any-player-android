package com.anyplayer.android.feature.startup

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.feature.auth.AuthRequest
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import com.anyplayer.android.feature.auth.StoredConnection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupResilienceManagerTest {
    @Test
    fun continueWithoutProviders_usesCachedPlaylistsAndFallback() = runTest {
        val authRepo = FakeAuthRepository(restored = listOf(sampleStatus(SourceType.JELLYFIN)))
        val gateway = FakeStartupCatalogGateway(
            cached = listOf(samplePlaylist("cached-1", SourceType.JELLYFIN)),
            remote = listOf(samplePlaylist("remote-1", SourceType.PLEX))
        )
        val manager = StartupResilienceManager(authRepo, gateway)

        val snapshot = manager.runStartup(continueWithoutProviders = true) { }

        assertTrue(snapshot.usedFallback)
        assertTrue(snapshot.providerStatuses.isEmpty())
        assertEquals(1, snapshot.providerPlaylists.size)
        assertEquals("cached-1", snapshot.providerPlaylists.first().id)
    }

    @Test
    fun startupFailure_fallsBackToCacheWithWarnings() = runTest {
        val authRepo = FakeAuthRepository(throwOnRestore = true)
        val gateway = FakeStartupCatalogGateway(
            cached = listOf(samplePlaylist("cached-1", SourceType.JELLYFIN)),
            remote = emptyList()
        )
        val manager = StartupResilienceManager(authRepo, gateway)

        val snapshot = manager.runStartup(continueWithoutProviders = false) { }

        assertTrue(snapshot.usedFallback)
        assertFalse(snapshot.warnings.isEmpty())
        assertEquals("cached-1", snapshot.providerPlaylists.first().id)
    }

    @Test
    fun startupSuccess_returnsRestoredStatusesAndRemotePlaylists() = runTest {
        val authRepo = FakeAuthRepository(restored = listOf(sampleStatus(SourceType.PLEX)))
        val gateway = FakeStartupCatalogGateway(
            cached = listOf(samplePlaylist("cached-1", SourceType.JELLYFIN)),
            remote = listOf(samplePlaylist("remote-1", SourceType.PLEX))
        )
        val manager = StartupResilienceManager(authRepo, gateway)

        val snapshot = manager.runStartup(continueWithoutProviders = false) { }

        assertFalse(snapshot.usedFallback)
        assertTrue(snapshot.warnings.isEmpty())
        assertEquals(1, snapshot.providerStatuses.size)
        assertEquals(SourceType.PLEX, snapshot.providerStatuses.first().source)
        assertEquals("remote-1", snapshot.providerPlaylists.first().id)
    }

    @Test
    fun startupRetry_transientRestoreFailureRecovers() = runTest {
        val authRepo = FakeAuthRepository(
            restored = listOf(sampleStatus(SourceType.JELLYFIN)),
            failRestoreAttempts = 1
        )
        val gateway = FakeStartupCatalogGateway(
            cached = listOf(samplePlaylist("cached-1", SourceType.JELLYFIN)),
            remote = listOf(samplePlaylist("remote-1", SourceType.JELLYFIN))
        )
        val manager = StartupResilienceManager(authRepo, gateway)

        val snapshot = manager.runStartup(continueWithoutProviders = false) { }

        assertEquals(2, authRepo.restoreCallCount)
        assertTrue(snapshot.warnings.isEmpty())
        assertFalse(snapshot.usedFallback)
        assertEquals("remote-1", snapshot.providerPlaylists.first().id)
    }

    private fun sampleStatus(source: SourceType) = ProviderConnectionProfile(
        source = source,
        connected = true,
        hasToken = true,
        playbackReady = true
    )

    private fun samplePlaylist(id: String, source: SourceType) = Playlist(
        id = id,
        name = "Playlist $id",
        owner = source.name,
        trackCount = 1,
        source = source
    )
}

private class FakeAuthRepository(
    private val restored: List<ProviderConnectionProfile> = emptyList(),
    private val throwOnRestore: Boolean = false,
    private val failRestoreAttempts: Int = 0
) : ProviderAuthRepository {
    var restoreCallCount: Int = 0

    override suspend fun connect(request: AuthRequest): ProviderConnectionProfile {
        throw UnsupportedOperationException("Not needed in test")
    }

    override suspend fun disconnect(sourceType: SourceType) {
        throw UnsupportedOperationException("Not needed in test")
    }

    override suspend fun beginSpotifyAuth(clientId: String, redirectUri: String): String {
        throw UnsupportedOperationException("Not needed in test")
    }

    override suspend fun completeSpotifyAuth(redirectUriWithCode: String): ProviderConnectionProfile {
        throw UnsupportedOperationException("Not needed in test")
    }

    override suspend fun restoreAll(): List<ProviderConnectionProfile> {
        restoreCallCount += 1
        if (restoreCallCount <= failRestoreAttempts) error("transient restore failure")
        if (throwOnRestore) error("restore failed")
        return restored
    }

    override suspend fun status(sourceType: SourceType): ProviderConnectionProfile {
        return restored.firstOrNull { it.source == sourceType }
            ?: ProviderConnectionProfile(source = sourceType, connected = false, hasToken = false)
    }

    override suspend fun readStoredConnection(sourceType: SourceType): StoredConnection? = null
}

private class FakeStartupCatalogGateway(
    private val cached: List<Playlist>,
    private val remote: List<Playlist>
) : StartupCatalogGateway {
    override suspend fun getCachedProviderPlaylists(): List<Playlist> = cached

    override suspend fun getAllProviderPlaylistsWithCache(offset: Int, limit: Int): List<Playlist> {
        return remote.ifEmpty { cached }
    }
}
