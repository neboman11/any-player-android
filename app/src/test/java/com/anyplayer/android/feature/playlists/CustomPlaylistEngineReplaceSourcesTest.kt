package com.anyplayer.android.feature.playlists

import com.anyplayer.android.core.model.CustomPlaylist
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.UnionPlaylistSource
import com.anyplayer.android.core.storage.repository.PlaylistStorageRepository
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import com.anyplayer.android.feature.providers.ProviderCatalogRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import java.time.Instant

class CustomPlaylistEngineReplaceSourcesTest {
    private lateinit var storageRepository: PlaylistStorageRepository
    private lateinit var providerCatalogRepository: ProviderCatalogRepository
    private lateinit var playbackQueueManager: PlaybackQueueManager
    private lateinit var engine: CustomPlaylistEngine

    @Before
    fun setUp() {
        storageRepository = mock()
        providerCatalogRepository = mock()
        playbackQueueManager = mock()
        engine = CustomPlaylistEngine(
            storageRepository = storageRepository,
            providerCatalogRepository = providerCatalogRepository,
            playbackQueueManager = playbackQueueManager
        )
    }

    @Test
    fun replaceUnionSources_persistsAndRefreshesTrackCount() = runTest {
        val playlistId = "union-replace"
        val playlist = CustomPlaylist(
            id = playlistId,
            name = "Union",
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString(),
            trackCount = 0,
            playlistType = PlaylistType.UNION,
            isDistinct = false
        )

        whenever(storageRepository.getCustomPlaylistById(playlistId)).doReturn(playlist)

        // After replace, refreshUnionTrackCount will call getUnionSources; stub it to return a source
        val source = UnionPlaylistSource(
            id = "src-1",
            unionPlaylistId = playlistId,
            sourceType = SourceType.SPOTIFY,
            sourcePlaylistId = "sp-1",
            position = 0,
            addedAt = Instant.now().toString()
        )
        whenever(storageRepository.getUnionSources(playlistId)).doReturn(listOf(source))

        // providerCatalogRepository returns one materialized track
        val track = Track(id = "sp-track-1", title = "T", artist = "A", source = SourceType.SPOTIFY, durationMs = 1000L, enriched = true)
        whenever(providerCatalogRepository.getPlaylistTracksWithCache(eq(SourceType.SPOTIFY), eq("sp-1"), any(), anyOrNull(), anyOrNull(), any())).doReturn(listOf(track))

        val newSources = listOf(source)

        engine.replaceUnionSources(playlistId, newSources)

        // Verify replace called
        verify(storageRepository).replaceUnionSources(eq(playlistId), eq(newSources))

        // Verify upsertCustomPlaylists was called with updated trackCount = 1
        val captor = argumentCaptor<List<com.anyplayer.android.core.model.CustomPlaylist>>()
        verify(storageRepository).upsertCustomPlaylists(captor.capture())
        assertEquals(1, captor.firstValue.first().trackCount)
    }
}
