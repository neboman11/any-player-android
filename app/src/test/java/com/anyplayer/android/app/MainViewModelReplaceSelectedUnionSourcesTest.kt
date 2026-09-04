package com.anyplayer.android.app

import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import com.anyplayer.android.feature.playlists.CustomPlaylistEngine
import com.anyplayer.android.feature.providers.ProviderCatalogRepository
import com.anyplayer.android.feature.startup.StartupResilienceManager
import com.anyplayer.android.feature.startup.StartupSnapshot
import com.anyplayer.android.feature.state.transfer.ConfigFileImporter
import com.anyplayer.android.feature.state.transfer.StateTransferManager
import com.anyplayer.android.feature.sync.SyncPreferences
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelReplaceSelectedUnionSourcesTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private val playbackQueueManager: PlaybackQueueManager = mock()
    private val providerCatalogRepository: ProviderCatalogRepository = mock()
    private val authRepository: ProviderAuthRepository = mock()
    private val customPlaylistEngine: CustomPlaylistEngine = mock()
    private val startupResilienceManager: StartupResilienceManager = mock()
    private val stateTransferManager: StateTransferManager = mock()
    private val configFileImporter: ConfigFileImporter = mock()
    private val syncPreferencesStore: SyncPreferencesStore = mock()
    private val context: android.content.Context = mock()

    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        whenever(syncPreferencesStore.read()).doReturn(SyncPreferences())
        whenever(customPlaylistEngine.observeCustomPlaylists()).doReturn(flowOf(emptyList()))
        whenever(playbackQueueManager.status).doReturn(
            MutableStateFlow(
                PlaybackStatus(
                    state = PlaybackStateType.IDLE,
                    shuffle = false,
                    repeatMode = RepeatMode.OFF,
                    volume = 100,
                    currentTrack = null,
                    position = 0L,
                    duration = 0L,
                    queue = emptyList()
                )
            )
        )

        runBlocking {
            whenever(startupResilienceManager.runStartup(any(), any())).doReturn(
                StartupSnapshot(
                    providerStatuses = emptyList(),
                    providerPlaylists = emptyList(),
                    warnings = emptyList(),
                    usedFallback = false
                )
            )
            whenever(authRepository.readStoredConnection(any())).doReturn(null)
            whenever(authRepository.updatePlaylistPageSize(any(), any())).doReturn(true)
        }

        viewModel = MainViewModel(
            context = context,
            authRepository = authRepository,
            playbackQueueManager = playbackQueueManager,
            stateTransferManager = stateTransferManager,
            configFileImporter = configFileImporter,
            providerCatalogRepository = providerCatalogRepository,
            playlistStorageRepository = mock(),
            customPlaylistEngine = customPlaylistEngine,
            startupResilienceManager = startupResilienceManager,
            syncPreferencesStore = syncPreferencesStore,
            syncSnapshotClient = mock(),
            djModelManager = mock(),
            djInterstitialPlayer = mock()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun replaceSelectedUnionSources_callsEngineAndUpdatesState() = runTest {
        val playlistId = "union-1"
        val track = Track(id = "t-1", title = "One", artist = "A", source = SourceType.SPOTIFY, durationMs = 1000L, enriched = true)
        val newSource = com.anyplayer.android.core.model.UnionPlaylistSource(
            id = "s-1",
            unionPlaylistId = playlistId,
            sourceType = SourceType.SPOTIFY,
            sourcePlaylistId = "sp-1",
            position = 0,
            addedAt = Instant.now().toString()
        )

        // When selected, engine should return sources and tracks
        whenever(customPlaylistEngine.getUnionSources(playlistId)).doReturn(listOf(newSource))
        whenever(customPlaylistEngine.getTracksForPlaylist(playlistId)).doReturn(listOf(track))

        // Select playlist so viewModel.selectedCustomPlaylistId is set and initial loads happen
        viewModel.selectCustomPlaylist(playlistId)
        advanceUntilIdle()

        // Now call replaceSelectedUnionSources
        viewModel.replaceSelectedUnionSources(listOf(newSource))
        advanceUntilIdle()

        // Engine method should be invoked
        verify(customPlaylistEngine).replaceUnionSources(playlistId, listOf(newSource))

        // Verify ViewModel refreshed sources and tracks by calling engine getters
        verify(customPlaylistEngine, atLeastOnce()).getUnionSources(playlistId)
        verify(customPlaylistEngine, atLeastOnce()).getTracksForPlaylist(playlistId)
    }

    @Test
    fun replaceSelectedUnionSources_normalizesSpotifyIdsBeforeSaving() = runTest {
        val playlistId = "union-spotify"
        val normalizedSource = com.anyplayer.android.core.model.UnionPlaylistSource(
            id = "s-spotify",
            unionPlaylistId = playlistId,
            sourceType = SourceType.SPOTIFY,
            sourcePlaylistId = "abc123",
            position = 0,
            addedAt = Instant.now().toString()
        )
        whenever(customPlaylistEngine.getUnionSources(playlistId)).doReturn(listOf(normalizedSource))
        whenever(customPlaylistEngine.getTracksForPlaylist(playlistId)).doReturn(emptyList())
        whenever(customPlaylistEngine.getCachedTracksForPlaylist(playlistId)).doReturn(emptyList())

        viewModel.selectCustomPlaylist(playlistId)
        advanceUntilIdle()

        val rawSource = normalizedSource.copy(sourcePlaylistId = "spotify:playlist:abc123")
        viewModel.replaceSelectedUnionSources(listOf(rawSource))
        advanceUntilIdle()

        val captor = argumentCaptor<List<com.anyplayer.android.core.model.UnionPlaylistSource>>()
        verify(customPlaylistEngine).replaceUnionSources(org.mockito.kotlin.eq(playlistId), captor.capture())
        assertEquals("abc123", captor.firstValue.single().sourcePlaylistId)
    }

    @Test
    fun selectCustomPlaylist_loadsCachedProviderMetadataForUnionSources() = runTest {
        val playlistId = "union-provider-label"
        whenever(customPlaylistEngine.observeCustomPlaylists()).doReturn(
            flowOf(
                listOf(
                    com.anyplayer.android.core.model.CustomPlaylist(
                        id = playlistId,
                        name = "Union Playlist",
                        createdAt = Instant.now().toString(),
                        updatedAt = Instant.now().toString(),
                        trackCount = 0,
                        playlistType = com.anyplayer.android.core.model.PlaylistType.UNION
                    )
                )
            )
        )
        viewModel = MainViewModel(
            context = context,
            authRepository = authRepository,
            playbackQueueManager = playbackQueueManager,
            stateTransferManager = stateTransferManager,
            configFileImporter = configFileImporter,
            providerCatalogRepository = providerCatalogRepository,
            playlistStorageRepository = mock(),
            customPlaylistEngine = customPlaylistEngine,
            startupResilienceManager = startupResilienceManager,
            syncPreferencesStore = syncPreferencesStore,
            syncSnapshotClient = mock(),
            djModelManager = mock(),
            djInterstitialPlayer = mock()
        )
        val source = com.anyplayer.android.core.model.UnionPlaylistSource(
            id = "s-provider",
            unionPlaylistId = playlistId,
            sourceType = SourceType.SPOTIFY,
            sourcePlaylistId = "sp-42",
            position = 0,
            addedAt = Instant.now().toString()
        )
        val cachedProviderPlaylist = com.anyplayer.android.core.model.Playlist(
            id = "sp-42",
            name = "Cached Spotify Playlist",
            owner = "owner",
            trackCount = 0,
            source = SourceType.SPOTIFY
        )

        whenever(customPlaylistEngine.getUnionSources(playlistId)).doReturn(listOf(source))
        whenever(customPlaylistEngine.getTracksForPlaylist(playlistId)).doReturn(emptyList())
        whenever(customPlaylistEngine.getCachedTracksForPlaylist(playlistId)).doReturn(emptyList())
        whenever(providerCatalogRepository.getCachedProviderPlaylists()).doReturn(listOf(cachedProviderPlaylist))

        advanceUntilIdle()

        viewModel.selectCustomPlaylist(playlistId)
        advanceUntilIdle()

        verify(providerCatalogRepository).getCachedProviderPlaylists()
        verify(providerCatalogRepository, never()).getAllProviderPlaylistsWithCache(any(), any())
    }

    @Test
    fun selectCustomPlaylist_fetchesProviderMetadataByIdWhenCacheMisses() = runTest {
        val playlistId = "union-provider-direct"
        whenever(customPlaylistEngine.observeCustomPlaylists()).doReturn(
            flowOf(
                listOf(
                    com.anyplayer.android.core.model.CustomPlaylist(
                        id = playlistId,
                        name = "Union Playlist",
                        createdAt = Instant.now().toString(),
                        updatedAt = Instant.now().toString(),
                        trackCount = 0,
                        playlistType = com.anyplayer.android.core.model.PlaylistType.UNION
                    )
                )
            )
        )
        viewModel = MainViewModel(
            context = context,
            authRepository = authRepository,
            playbackQueueManager = playbackQueueManager,
            stateTransferManager = stateTransferManager,
            configFileImporter = configFileImporter,
            providerCatalogRepository = providerCatalogRepository,
            playlistStorageRepository = mock(),
            customPlaylistEngine = customPlaylistEngine,
            startupResilienceManager = startupResilienceManager,
            syncPreferencesStore = syncPreferencesStore,
            syncSnapshotClient = mock(),
            djModelManager = mock(),
            djInterstitialPlayer = mock()
        )

        val source = com.anyplayer.android.core.model.UnionPlaylistSource(
            id = "s-provider-2",
            unionPlaylistId = playlistId,
            sourceType = SourceType.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:sp-99",
            position = 0,
            addedAt = Instant.now().toString()
        )
        val resolvedPlaylist = com.anyplayer.android.core.model.Playlist(
            id = "sp-99",
            name = "Resolved By Id",
            owner = "owner",
            trackCount = 7,
            source = SourceType.SPOTIFY
        )

        whenever(customPlaylistEngine.getUnionSources(playlistId)).doReturn(listOf(source))
        whenever(customPlaylistEngine.getTracksForPlaylist(playlistId)).doReturn(emptyList())
        whenever(customPlaylistEngine.getCachedTracksForPlaylist(playlistId)).doReturn(emptyList())
        whenever(providerCatalogRepository.getCachedProviderPlaylists()).doReturn(emptyList())
        whenever(providerCatalogRepository.getProviderPlaylist(SourceType.SPOTIFY, "spotify:playlist:sp-99")).doReturn(resolvedPlaylist)

        advanceUntilIdle()

        viewModel.selectCustomPlaylist(playlistId)
        advanceUntilIdle()

        verify(providerCatalogRepository).getProviderPlaylist(SourceType.SPOTIFY, "spotify:playlist:sp-99")
        verify(providerCatalogRepository, never()).getAllProviderPlaylistsWithCache(any(), any())
    }
}
