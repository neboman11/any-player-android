package com.anyplayer.android.feature.providers

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.network.SpotifyClient
import com.anyplayer.android.core.rust.RustBridge
import com.anyplayer.android.core.storage.dao.AppCacheEntryDao
import com.anyplayer.android.feature.auth.PROVIDER_DEFAULT_PAGE_SIZE
import com.anyplayer.android.feature.auth.SecureConnectionStore
import com.anyplayer.android.feature.auth.StoredConnection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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

    companion object {
        // Matches PROVIDER_DEFAULT_PAGE_SIZE from AuthModels
        private const val PROVIDER_PAGE_SIZE = PROVIDER_DEFAULT_PAGE_SIZE
    }

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

    @Test
    fun getPlaylistTracks_jellyfinPaginatesAcrossMultiplePages() = runTest {
        val page1 = (1..PROVIDER_PAGE_SIZE).map { sampleTrack("jelly-$it", SourceType.JELLYFIN) }
        val page2 = (PROVIDER_PAGE_SIZE + 1..450).map { sampleTrack("jelly-$it", SourceType.JELLYFIN) }

        whenever(rustBridge.isAvailable()).thenReturn(true)
        whenever(secureConnectionStore.read(SourceType.JELLYFIN)).thenReturn(
            StoredConnection(source = SourceType.JELLYFIN, serverUrl = "http://jf", token = "jf-token")
        )
        whenever(
            rustBridge.providerGetPlaylistTracks(
                source = eq(SourceType.JELLYFIN),
                session = any(),
                playlistId = eq("jf-playlist"),
                offset = eq(0),
                limit = eq(PROVIDER_PAGE_SIZE)
            )
        ).thenReturn(page1)
        whenever(
            rustBridge.providerGetPlaylistTracks(
                source = eq(SourceType.JELLYFIN),
                session = any(),
                playlistId = eq("jf-playlist"),
                offset = eq(PROVIDER_PAGE_SIZE),
                limit = eq(PROVIDER_PAGE_SIZE)
            )
        ).thenReturn(page2)

        val result = repository.getPlaylistTracks(
            sourceType = SourceType.JELLYFIN,
            playlistId = "jf-playlist"
        )

        assertEquals(450, result.size)
        assertEquals(page1 + page2, result)
    }

    @Test
    fun getPlaylistTracks_jellyfinStopsWhenPageSmallerThanPageSize() = runTest {
        val onlyPage = (1..150).map { sampleTrack("jelly-$it", SourceType.JELLYFIN) }

        whenever(rustBridge.isAvailable()).thenReturn(true)
        whenever(secureConnectionStore.read(SourceType.JELLYFIN)).thenReturn(
            StoredConnection(source = SourceType.JELLYFIN, serverUrl = "http://jf", token = "jf-token")
        )
        whenever(
            rustBridge.providerGetPlaylistTracks(
                source = eq(SourceType.JELLYFIN),
                session = any(),
                playlistId = eq("jf-playlist"),
                offset = eq(0),
                limit = eq(PROVIDER_PAGE_SIZE)
            )
        ).thenReturn(onlyPage)

        val result = repository.getPlaylistTracks(
            sourceType = SourceType.JELLYFIN,
            playlistId = "jf-playlist"
        )

        assertEquals(150, result.size)
        assertEquals(onlyPage, result)
        verify(rustBridge, times(1)).providerGetPlaylistTracks(
            source = any(), session = any(), playlistId = any(), offset = any(), limit = any()
        )
    }

    @Test
    fun getPlaylistTracks_plexPaginatesAcrossMultiplePages() = runTest {
        val page1 = (1..PROVIDER_PAGE_SIZE).map { sampleTrack("plex-$it", SourceType.PLEX) }
        val page2 = (PROVIDER_PAGE_SIZE + 1..500).map { sampleTrack("plex-$it", SourceType.PLEX) }

        whenever(rustBridge.isAvailable()).thenReturn(true)
        whenever(secureConnectionStore.read(SourceType.PLEX)).thenReturn(
            StoredConnection(source = SourceType.PLEX, serverUrl = "http://plex", token = "plex-token")
        )
        whenever(
            rustBridge.providerGetPlaylistTracks(
                source = eq(SourceType.PLEX),
                session = any(),
                playlistId = eq("plex-playlist"),
                offset = eq(0),
                limit = eq(PROVIDER_PAGE_SIZE)
            )
        ).thenReturn(page1)
        whenever(
            rustBridge.providerGetPlaylistTracks(
                source = eq(SourceType.PLEX),
                session = any(),
                playlistId = eq("plex-playlist"),
                offset = eq(PROVIDER_PAGE_SIZE),
                limit = eq(PROVIDER_PAGE_SIZE)
            )
        ).thenReturn(page2)

        val result = repository.getPlaylistTracks(
            sourceType = SourceType.PLEX,
            playlistId = "plex-playlist"
        )

        assertEquals(500, result.size)
        assertEquals(page1 + page2, result)
    }

    @Test
    fun getPlaylistTracks_plexStopsWhenPageSmallerThanPageSize() = runTest {
        val onlyPage = (1..200).map { sampleTrack("plex-$it", SourceType.PLEX) }

        whenever(rustBridge.isAvailable()).thenReturn(true)
        whenever(secureConnectionStore.read(SourceType.PLEX)).thenReturn(
            StoredConnection(source = SourceType.PLEX, serverUrl = "http://plex", token = "plex-token")
        )
        whenever(
            rustBridge.providerGetPlaylistTracks(
                source = eq(SourceType.PLEX),
                session = any(),
                playlistId = eq("plex-playlist"),
                offset = eq(0),
                limit = eq(PROVIDER_PAGE_SIZE)
            )
        ).thenReturn(onlyPage)

        val result = repository.getPlaylistTracks(
            sourceType = SourceType.PLEX,
            playlistId = "plex-playlist"
        )

        assertEquals(200, result.size)
        assertEquals(onlyPage, result)
        verify(rustBridge, times(1)).providerGetPlaylistTracks(
            source = any(), session = any(), playlistId = any(), offset = any(), limit = any()
        )
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
