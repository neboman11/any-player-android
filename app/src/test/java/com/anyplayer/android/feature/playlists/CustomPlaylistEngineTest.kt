package com.anyplayer.android.feature.playlists

import com.anyplayer.android.core.model.PlaylistTrack
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.UnionPlaylistSource
import com.anyplayer.android.core.storage.repository.PlaylistStorageRepository
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import com.anyplayer.android.feature.providers.ProviderCatalogRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CustomPlaylistEngineTest {
    private val storageRepository: PlaylistStorageRepository = mock()
    private val providerCatalogRepository: ProviderCatalogRepository = mock()
    private val playbackQueueManager: PlaybackQueueManager = mock()

    private val engine = CustomPlaylistEngine(
        storageRepository = storageRepository,
        providerCatalogRepository = providerCatalogRepository,
        playbackQueueManager = playbackQueueManager
    )

    @Test
    fun materializeUnionTracks_includesSpotifyTracks() = runTest {
        whenever(storageRepository.getUnionSources("union-1")).thenReturn(listOf(
            unionSource(id = "src-1", sourceType = SourceType.SPOTIFY, sourcePlaylistId = "sp-playlist", position = 0)
        ))
        whenever(
            providerCatalogRepository.getPlaylistTracksWithCache(
                eq(SourceType.SPOTIFY),
                eq("sp-playlist"),
                any(),
                any(),
                any()
            )
        ).thenReturn(listOf(sampleTrack(id = "sp-track-1", source = SourceType.SPOTIFY)))

        val result = engine.materializeUnionTracks("union-1")

        assertEquals(1, result.size)
        assertEquals("sp-track-1", result.first().id)
        assertEquals(SourceType.SPOTIFY, result.first().source)
    }

    @Test
    fun materializeUnionTracks_setsDurationAndEnrichedDefaults() = runTest {
        whenever(storageRepository.getUnionSources("union-2")).thenReturn(listOf(
            unionSource(id = "src-1", sourceType = SourceType.SPOTIFY, sourcePlaylistId = "sp-playlist", position = 0)
        ))
        whenever(
            providerCatalogRepository.getPlaylistTracksWithCache(
                eq(SourceType.SPOTIFY),
                eq("sp-playlist"),
                any(),
                any(),
                any()
            )
        ).thenReturn(listOf(
            Track(
                id = "sp-track-2",
                title = "Untimed",
                artist = "Artist",
                source = SourceType.SPOTIFY,
                durationMs = null,
                enriched = null
            )
        ))

        val result = engine.materializeUnionTracks("union-2")

        assertEquals(1, result.size)
        assertEquals(0L, result.first().durationMs)
        assertTrue(result.first().enriched == true)
    }

    @Test
    fun materializeUnionTracks_deduplicatesSameSourceAndTrackId() = runTest {
        whenever(storageRepository.getUnionSources("union-3")).thenReturn(listOf(
            unionSource(id = "src-1", sourceType = SourceType.SPOTIFY, sourcePlaylistId = "sp-a", position = 0),
            unionSource(id = "src-2", sourceType = SourceType.SPOTIFY, sourcePlaylistId = "sp-b", position = 1)
        ))
        val duplicate = sampleTrack(id = "dup-track", source = SourceType.SPOTIFY)

        whenever(
            providerCatalogRepository.getPlaylistTracksWithCache(
                eq(SourceType.SPOTIFY),
                eq("sp-a"),
                any(),
                any(),
                any()
            )
        ).thenReturn(listOf(duplicate))
        whenever(
            providerCatalogRepository.getPlaylistTracksWithCache(
                eq(SourceType.SPOTIFY),
                eq("sp-b"),
                any(),
                any(),
                any()
            )
        ).thenReturn(listOf(duplicate))

        val result = engine.materializeUnionTracks("union-3")

        assertEquals(1, result.size)
        assertEquals("dup-track", result.first().id)
    }

    @Test
    fun materializeUnionTracks_mergesCustomAndSpotifyInSourceOrder() = runTest {
        whenever(storageRepository.getUnionSources("union-4")).thenReturn(listOf(
            unionSource(id = "src-custom", sourceType = SourceType.CUSTOM, sourcePlaylistId = "custom-1", position = 0),
            unionSource(id = "src-spotify", sourceType = SourceType.SPOTIFY, sourcePlaylistId = "sp-1", position = 1)
        ))
        whenever(storageRepository.getPlaylistTracks("custom-1")).thenReturn(listOf(
            PlaylistTrack(
                id = "pt-1",
                playlistId = "custom-1",
                trackSource = SourceType.CUSTOM,
                trackId = "custom-track",
                position = 0,
                addedAt = "2026-02-19T00:00:00Z",
                title = "Custom Track",
                artist = "Custom Artist",
                durationMs = null,
                album = null,
                imageUrl = null,
                url = null
            )
        ))
        whenever(
            providerCatalogRepository.getPlaylistTracksWithCache(
                eq(SourceType.SPOTIFY),
                eq("sp-1"),
                any(),
                any(),
                any()
            )
        ).thenReturn(listOf(sampleTrack(id = "sp-track-4", source = SourceType.SPOTIFY)))

        val result = engine.materializeUnionTracks("union-4")

        assertEquals(2, result.size)
        assertEquals("custom-track", result[0].id)
        assertEquals(SourceType.CUSTOM, result[0].source)
        assertNull(result[0].album)
        assertEquals("sp-track-4", result[1].id)
        assertEquals(SourceType.SPOTIFY, result[1].source)
    }

    private fun sampleTrack(id: String, source: SourceType): Track = Track(
        id = id,
        title = "Track $id",
        artist = "Artist",
        source = source,
        durationMs = 123_000L,
        enriched = true
    )

    private fun unionSource(
        id: String,
        sourceType: SourceType,
        sourcePlaylistId: String,
        position: Int
    ): UnionPlaylistSource = UnionPlaylistSource(
        id = id,
        unionPlaylistId = "union",
        sourceType = sourceType,
        sourcePlaylistId = sourcePlaylistId,
        position = position,
        addedAt = "2026-02-19T00:00:00Z"
    )
}
