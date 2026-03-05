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

    // ── Distinct mode: playPlaylist ──────────────────────────────────────────

    @Test
    fun playPlaylist_standardDistinct_dedupsBeforeQueue() = runTest {
        val playlistId = "std-distinct"
        whenever(storageRepository.getCustomPlaylistById(playlistId)).thenReturn(
            standardPlaylist(playlistId, isDistinct = true)
        )
        whenever(storageRepository.getPlaylistTracks(playlistId)).thenReturn(listOf(
            playlistTrack(id = "pt-1", trackId = "t-1", title = "Song A", artist = "Artist X"),
            playlistTrack(id = "pt-2", trackId = "t-2", title = "Song B", artist = "Artist Y"),
            playlistTrack(id = "pt-3", trackId = "t-1-dup", title = "Song A", artist = "Artist X") // dup of pt-1
        ))

        engine.playPlaylist(playlistId)

        val captor = org.mockito.kotlin.argumentCaptor<List<Track>>()
        val indexCaptor = org.mockito.kotlin.argumentCaptor<Int>()
        org.mockito.kotlin.verify(playbackQueueManager).setQueue(captor.capture(), startIndex = indexCaptor.capture(), autoPlay = eq(true))

        assertEquals(2, captor.firstValue.size)
        assertEquals(listOf("t-1", "t-2"), captor.firstValue.map { it.id })
        assertEquals(0, indexCaptor.firstValue)
    }

    @Test
    fun playPlaylist_standardNotDistinct_keepsDuplicates() = runTest {
        val playlistId = "std-nodistinct"
        whenever(storageRepository.getCustomPlaylistById(playlistId)).thenReturn(
            standardPlaylist(playlistId, isDistinct = false)
        )
        whenever(storageRepository.getPlaylistTracks(playlistId)).thenReturn(listOf(
            playlistTrack(id = "pt-1", trackId = "t-1", title = "Song A", artist = "Artist X"),
            playlistTrack(id = "pt-2", trackId = "t-1-dup", title = "Song A", artist = "Artist X")
        ))

        engine.playPlaylist(playlistId)

        val captor = org.mockito.kotlin.argumentCaptor<List<Track>>()
        org.mockito.kotlin.verify(playbackQueueManager).setQueue(captor.capture(), startIndex = eq(0), autoPlay = eq(true))

        assertEquals(2, captor.firstValue.size)
    }

    @Test
    fun playPlaylist_unionDistinct_twoPassDedup() = runTest {
        val playlistId = "union-distinct"
        whenever(storageRepository.getCustomPlaylistById(playlistId)).thenReturn(
            unionPlaylist(playlistId, isDistinct = true)
        )
        whenever(storageRepository.getUnionSources(playlistId)).thenReturn(listOf(
            unionSource(id = "src-1", sourceType = SourceType.SPOTIFY, sourcePlaylistId = "sp-a", position = 0)
        ))
        // Two tracks with same title+artist but different source IDs (pass 1 keeps both, pass 2 removes one)
        whenever(
            providerCatalogRepository.getPlaylistTracksWithCache(eq(SourceType.SPOTIFY), eq("sp-a"), any(), any(), any())
        ).thenReturn(listOf(
            sampleTrack(id = "sp-1", source = SourceType.SPOTIFY, title = "Tune", artist = "Band"),
            sampleTrack(id = "sp-2", source = SourceType.SPOTIFY, title = "Tune", artist = "Band") // same title+artist, different id
        ))

        engine.playPlaylist(playlistId)

        val captor = org.mockito.kotlin.argumentCaptor<List<Track>>()
        org.mockito.kotlin.verify(playbackQueueManager).setQueue(captor.capture(), startIndex = eq(0), autoPlay = eq(true))

        // Pass 1 (source:id dedup) keeps both since ids differ; pass 2 (title+artist) removes second
        assertEquals(1, captor.firstValue.size)
        assertEquals("sp-1", captor.firstValue.first().id)
    }

    // ── Distinct mode: playFromTrack ─────────────────────────────────────────

    @Test
    fun playFromTrack_standardDistinct_firstOccurrenceIndex_resolvedCorrectly() = runTest {
        val playlistId = "std-fromtrack"
        whenever(storageRepository.getCustomPlaylistById(playlistId)).thenReturn(
            standardPlaylist(playlistId, isDistinct = true)
        )
        // [Song A, Song B, Song C, Song A (dup)]
        whenever(storageRepository.getPlaylistTracks(playlistId)).thenReturn(listOf(
            playlistTrack(id = "pt-0", trackId = "t-0", title = "Song A", artist = "Artist"),
            playlistTrack(id = "pt-1", trackId = "t-1", title = "Song B", artist = "Artist"),
            playlistTrack(id = "pt-2", trackId = "t-2", title = "Song C", artist = "Artist"),
            playlistTrack(id = "pt-3", trackId = "t-0-dup", title = "Song A", artist = "Artist")
        ))

        // User clicks index 3 (the duplicate of Song A) — should resolve to index 0 in deduped queue
        engine.playFromTrack(playlistId, trackIndex = 3)

        val captor = org.mockito.kotlin.argumentCaptor<List<Track>>()
        val indexCaptor = org.mockito.kotlin.argumentCaptor<Int>()
        org.mockito.kotlin.verify(playbackQueueManager).setQueue(captor.capture(), startIndex = indexCaptor.capture(), autoPlay = eq(true))

        assertEquals(3, captor.firstValue.size)
        assertEquals(0, indexCaptor.firstValue) // resolved to first occurrence of Song A
    }

    @Test
    fun playFromTrack_standardDistinct_nonDuplicateTrack_usesCorrectDedupedIndex() = runTest {
        val playlistId = "std-fromtrack-2"
        whenever(storageRepository.getCustomPlaylistById(playlistId)).thenReturn(
            standardPlaylist(playlistId, isDistinct = true)
        )
        // [Song A, Song B (dup of Song A?), Song C] — Song B unique, clicking index 2 (Song C)
        // Deduped: [Song A, Song B, Song C] → index 2 stays 2
        whenever(storageRepository.getPlaylistTracks(playlistId)).thenReturn(listOf(
            playlistTrack(id = "pt-0", trackId = "t-0", title = "Song A", artist = "Artist"),
            playlistTrack(id = "pt-1", trackId = "t-1", title = "Song B", artist = "Artist"),
            playlistTrack(id = "pt-2", trackId = "t-2", title = "Song C", artist = "Artist")
        ))

        engine.playFromTrack(playlistId, trackIndex = 2)

        val indexCaptor = org.mockito.kotlin.argumentCaptor<Int>()
        org.mockito.kotlin.verify(playbackQueueManager).setQueue(any(), startIndex = indexCaptor.capture(), autoPlay = eq(true))

        assertEquals(2, indexCaptor.firstValue)
    }

    @Test
    fun playFromTrack_standardNotDistinct_usesRawIndex() = runTest {
        val playlistId = "std-fromtrack-nodistinct"
        whenever(storageRepository.getCustomPlaylistById(playlistId)).thenReturn(
            standardPlaylist(playlistId, isDistinct = false)
        )
        whenever(storageRepository.getPlaylistTracks(playlistId)).thenReturn(listOf(
            playlistTrack(id = "pt-0", trackId = "t-0", title = "Song A", artist = "Artist"),
            playlistTrack(id = "pt-1", trackId = "t-1", title = "Song A", artist = "Artist"), // dup
            playlistTrack(id = "pt-2", trackId = "t-2", title = "Song C", artist = "Artist")
        ))

        engine.playFromTrack(playlistId, trackIndex = 1)

        val indexCaptor = org.mockito.kotlin.argumentCaptor<Int>()
        org.mockito.kotlin.verify(playbackQueueManager).setQueue(any(), startIndex = indexCaptor.capture(), autoPlay = eq(true))

        assertEquals(1, indexCaptor.firstValue)
    }

    @Test
    fun playFromTrack_unionDistinct_duplicateRemappedToFirstOccurrence() = runTest {
        val playlistId = "union-fromtrack"
        whenever(storageRepository.getCustomPlaylistById(playlistId)).thenReturn(
            unionPlaylist(playlistId, isDistinct = true)
        )
        whenever(storageRepository.getUnionSources(playlistId)).thenReturn(listOf(
            unionSource(id = "src-1", sourceType = SourceType.SPOTIFY, sourcePlaylistId = "sp-1", position = 0)
        ))
        // sp-1 returns two tracks with same title+artist (different ids)
        val track1 = sampleTrack(id = "sp-a", source = SourceType.SPOTIFY, title = "Melody", artist = "Band")
        val track2 = sampleTrack(id = "sp-b", source = SourceType.SPOTIFY, title = "Other", artist = "Band")
        val track3 = sampleTrack(id = "sp-c", source = SourceType.SPOTIFY, title = "Melody", artist = "Band") // dup of sp-a
        whenever(
            providerCatalogRepository.getPlaylistTracksWithCache(eq(SourceType.SPOTIFY), eq("sp-1"), any(), any(), any())
        ).thenReturn(listOf(track1, track2, track3))

        // User clicks index 2 (track3 = duplicate of track1) — should resolve to index 0
        engine.playFromTrack(playlistId, trackIndex = 2)

        val captor = org.mockito.kotlin.argumentCaptor<List<Track>>()
        val indexCaptor = org.mockito.kotlin.argumentCaptor<Int>()
        org.mockito.kotlin.verify(playbackQueueManager).setQueue(captor.capture(), startIndex = indexCaptor.capture(), autoPlay = eq(true))

        assertEquals(2, captor.firstValue.size) // deduped: [sp-a, sp-b]
        assertEquals(0, indexCaptor.firstValue) // resolved to first occurrence of "melody|band"
    }

    private fun standardPlaylist(id: String, isDistinct: Boolean = false) = com.anyplayer.android.core.model.CustomPlaylist(
        id = id,
        name = "Test Playlist",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        trackCount = 0,
        playlistType = com.anyplayer.android.core.model.PlaylistType.STANDARD,
        isDistinct = isDistinct
    )

    private fun unionPlaylist(id: String, isDistinct: Boolean = false) = com.anyplayer.android.core.model.CustomPlaylist(
        id = id,
        name = "Union Playlist",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        trackCount = 0,
        playlistType = com.anyplayer.android.core.model.PlaylistType.UNION,
        isDistinct = isDistinct
    )

    private fun playlistTrack(
        id: String,
        trackId: String,
        title: String,
        artist: String
    ) = PlaylistTrack(
        id = id,
        playlistId = "test-playlist",
        trackSource = SourceType.CUSTOM,
        trackId = trackId,
        position = 0,
        addedAt = "2026-01-01T00:00:00Z",
        title = title,
        artist = artist
    )

    private fun sampleTrack(
        id: String,
        source: SourceType,
        title: String = "Track $id",
        artist: String = "Artist"
    ): Track = Track(
        id = id,
        title = title,
        artist = artist,
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
