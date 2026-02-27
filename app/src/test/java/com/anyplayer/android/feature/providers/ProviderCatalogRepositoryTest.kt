package com.anyplayer.android.feature.providers

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.network.SpotifyClient
import com.anyplayer.android.core.rust.RustBridge
import com.anyplayer.android.core.storage.dao.AppCacheEntryDao
import com.anyplayer.android.feature.auth.SecureConnectionStore
import com.anyplayer.android.feature.auth.StoredConnection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProviderCatalogRepositoryTest {
    private val secureConnectionStore: SecureConnectionStore = mock()
    private val spotifyClient: SpotifyClient = mock()
    private val rustBridge: RustBridge = mock()
    private val appCacheEntryDao: AppCacheEntryDao = mock()
    private val json = Json { ignoreUnknownKeys = true }

    private val repository = ProviderCatalogRepository(
        secureConnectionStore = secureConnectionStore,
        spotifyClient = spotifyClient,
        rustBridge = rustBridge,
        appCacheEntryDao = appCacheEntryDao,
        json = json
    )

    @Test
    fun getAllProviderPlaylists_usesSpotifyTokenWithoutPlaybackReadyOrPremium() = runTest {
        val spotifyPlaylist = samplePlaylist(id = "sp-pl-1", source = SourceType.SPOTIFY)

        whenever(secureConnectionStore.read(SourceType.JELLYFIN)).thenReturn(null)
        whenever(secureConnectionStore.read(SourceType.PLEX)).thenReturn(null)
        whenever(secureConnectionStore.read(SourceType.SPOTIFY)).thenReturn(
            StoredConnection(
                source = SourceType.SPOTIFY,
                token = "spotify-token",
                spotifyPremium = false,
                playbackReady = false
            )
        )
        whenever(spotifyClient.getPlaylists("spotify-token", 0, 50)).thenReturn(listOf(spotifyPlaylist))

        val result = repository.getAllProviderPlaylists(offset = 0, limit = 100)

        assertEquals(listOf(spotifyPlaylist), result)
        verify(spotifyClient).getPlaylists("spotify-token", 0, 50)
    }

    @Test
    fun getPlaylistTracks_spotifyLoadsWithoutPlaybackReadyOrPremium() = runTest {
        val spotifyTrack = sampleTrack(id = "sp-track-1", source = SourceType.SPOTIFY)

        whenever(secureConnectionStore.read(SourceType.SPOTIFY)).thenReturn(
            StoredConnection(
                source = SourceType.SPOTIFY,
                token = "spotify-token",
                spotifyPremium = false,
                playbackReady = false
            )
        )
        whenever(
            spotifyClient.getPlaylistTracks(
                accessToken = "spotify-token",
                playlistId = "sp-playlist",
                offset = 0,
                limit = 100
            )
        ).thenReturn(listOf(spotifyTrack))

        val result = repository.getPlaylistTracks(
            sourceType = SourceType.SPOTIFY,
            playlistId = "sp-playlist"
        )

        assertEquals(listOf(spotifyTrack), result)
        verify(spotifyClient).getPlaylistTracks("spotify-token", "sp-playlist", 0, 100)
    }

    @Test
    fun search_spotifyQueriesRunWithTokenOnly() = runTest {
        val spotifyTrack = sampleTrack(id = "s-1", source = SourceType.SPOTIFY)
        val spotifyPlaylist = samplePlaylist(id = "p-1", source = SourceType.SPOTIFY)

        whenever(secureConnectionStore.read(SourceType.JELLYFIN)).thenReturn(null)
        whenever(secureConnectionStore.read(SourceType.PLEX)).thenReturn(null)
        whenever(secureConnectionStore.read(SourceType.SPOTIFY)).thenReturn(
            StoredConnection(
                source = SourceType.SPOTIFY,
                token = "spotify-token",
                spotifyPremium = false,
                playbackReady = false
            )
        )
        whenever(spotifyClient.searchTracks("spotify-token", "focus", 0, 50)).thenReturn(listOf(spotifyTrack))
        whenever(spotifyClient.searchPlaylists("spotify-token", "focus", 0, 50)).thenReturn(listOf(spotifyPlaylist))

        val result = repository.search(
            query = "focus",
            source = SourceType.SPOTIFY,
            offset = 0,
            limit = 100
        )

        assertEquals(listOf(spotifyTrack), result.tracks)
        assertEquals(listOf(spotifyPlaylist), result.playlists)
        verify(spotifyClient).searchTracks("spotify-token", "focus", 0, 50)
        verify(spotifyClient).searchPlaylists("spotify-token", "focus", 0, 50)
    }

    @Test
    fun getPlaylistTracksWithCache_returnsCachedWhenRemoteEmpty() = runTest {
        whenever(appCacheEntryDao.get(any())).thenReturn(null)

        whenever(secureConnectionStore.read(SourceType.SPOTIFY)).thenReturn(
            StoredConnection(
                source = SourceType.SPOTIFY,
                token = "spotify-token",
                spotifyPremium = true,
                playbackReady = true
            )
        )
        whenever(
            spotifyClient.getPlaylistTracks(
                accessToken = eq("spotify-token"),
                playlistId = eq("sp-playlist"),
                offset = any(),
                limit = any()
            )
        ).thenReturn(emptyList())

        val result = repository.getPlaylistTracksWithCache(
            sourceType = SourceType.SPOTIFY,
            playlistId = "sp-playlist"
        )

        assertTrue(result.isEmpty())
        verify(appCacheEntryDao, never()).upsert(any())
    }

    private fun sampleTrack(id: String, source: SourceType): Track = Track(
        id = id,
        title = "Track $id",
        artist = "Artist",
        source = source,
        durationMs = 180_000L,
        enriched = true
    )

    private fun samplePlaylist(id: String, source: SourceType): Playlist = Playlist(
        id = id,
        name = "Playlist $id",
        owner = "owner",
        trackCount = 1,
        source = source
    )
}
