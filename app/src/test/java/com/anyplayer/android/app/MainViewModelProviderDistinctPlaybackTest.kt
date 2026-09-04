package com.anyplayer.android.app

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.storage.repository.PlaylistStorageRepository
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
import com.anyplayer.android.feature.sync.SyncSnapshotClient
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * JVM unit tests verifying that both provider playback entry points
 * ([MainViewModel.playPlaylist] and [MainViewModel.playSelectedProviderPlaylist]) correctly
 * apply the distinct dedup gate using [PlaylistStorageRepository.getProviderPlaylistIsDistinct].
 *
 * Repository and PlaybackQueueManager are mocked. No UI or Hilt required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelProviderDistinctPlaybackTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val playbackQueueManager: PlaybackQueueManager = mock()
    private val providerCatalogRepository: ProviderCatalogRepository = mock()
    private val playlistStorageRepository: PlaylistStorageRepository = mock()
    private val authRepository: ProviderAuthRepository = mock()
    private val customPlaylistEngine: CustomPlaylistEngine = mock()
    private val startupResilienceManager: StartupResilienceManager = mock()
    private val stateTransferManager: StateTransferManager = mock()
    private val configFileImporter: ConfigFileImporter = mock()
    private val syncPreferencesStore: SyncPreferencesStore = mock()
    private val syncSnapshotClient: SyncSnapshotClient = mock()
    private val context: android.content.Context = mock()

    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Stub all init-time dependencies to prevent NPEs during construction
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
            whenever(startupResilienceManager.runStartup(any(), any())).thenReturn(
                StartupSnapshot(
                    providerStatuses = emptyList(),
                    providerPlaylists = emptyList(),
                    warnings = emptyList(),
                    usedFallback = false
                )
            )
            whenever(authRepository.readStoredConnection(any())).thenReturn(null)
            whenever(authRepository.updatePlaylistPageSize(any(), any())).thenReturn(true)
        }

        viewModel = MainViewModel(
            context = context,
            authRepository = authRepository,
            playbackQueueManager = playbackQueueManager,
            stateTransferManager = stateTransferManager,
            configFileImporter = configFileImporter,
            providerCatalogRepository = providerCatalogRepository,
            playlistStorageRepository = playlistStorageRepository,
            customPlaylistEngine = customPlaylistEngine,
            startupResilienceManager = startupResilienceManager,
            syncPreferencesStore = syncPreferencesStore,
            syncSnapshotClient = syncSnapshotClient,
            djModelManager = mock(),
            djInterstitialPlayer = mock()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── playPlaylist(sourceType, playlistId) ─────────────────────────────────

    @Test
    fun playPlaylist_isDistinctTrue_setQueueWithDedupedTracks() = runTest {
        val playlistId = "sp-playlist-1"
        val rawTracks = listOf(
            track("t-1", "Harmony", "Band A"),
            track("t-2", "Rhythm", "Band B"),
            track("t-3", "Harmony", "Band A") // duplicate of t-1
        )
        whenever(
            providerCatalogRepository.getPlaylistTracksWithCache(any(), any(), any(), anyOrNull(), anyOrNull(), any())
        ).doReturn(rawTracks)
        whenever(
            playlistStorageRepository.getProviderPlaylistIsDistinct(eq("SPOTIFY"), eq(playlistId))
        ).doReturn(true)

        viewModel.playPlaylist(SourceType.SPOTIFY, playlistId)
        advanceUntilIdle()

        val captor = argumentCaptor<List<Track>>()
        verify(playbackQueueManager).setQueue(captor.capture(), startIndex = eq(0), autoPlay = eq(true))

        assertEquals(2, captor.firstValue.size)
        assertEquals(listOf("t-1", "t-2"), captor.firstValue.map { it.id })
    }

    @Test
    fun playPlaylist_isDistinctFalse_setQueueWithAllTracks() = runTest {
        val playlistId = "sp-playlist-2"
        val rawTracks = listOf(
            track("t-1", "Harmony", "Band A"),
            track("t-2", "Harmony", "Band A") // duplicate — kept when distinct=false
        )
        whenever(
            providerCatalogRepository.getPlaylistTracksWithCache(any(), any(), any(), anyOrNull(), anyOrNull(), any())
        ).doReturn(rawTracks)
        whenever(
            playlistStorageRepository.getProviderPlaylistIsDistinct(eq("SPOTIFY"), eq(playlistId))
        ).doReturn(false)

        viewModel.playPlaylist(SourceType.SPOTIFY, playlistId)
        advanceUntilIdle()

        val captor = argumentCaptor<List<Track>>()
        verify(playbackQueueManager).setQueue(captor.capture(), startIndex = eq(0), autoPlay = eq(true))

        assertEquals(2, captor.firstValue.size)
        assertEquals(listOf("t-1", "t-2"), captor.firstValue.map { it.id })
    }

    @Test
    fun playPlaylist_distinctAppliesSameTitleArtistKey_caseInsensitive() = runTest {
        val playlistId = "sp-playlist-3"
        val rawTracks = listOf(
            track("t-1", "  Song  ", "  Artist  "),   // key: "song|artist"
            track("t-2", "Other", "Band"),
            track("t-3", "SONG", "ARTIST")              // same key after normalize
        )
        whenever(
            providerCatalogRepository.getPlaylistTracksWithCache(any(), any(), any(), anyOrNull(), anyOrNull(), any())
        ).doReturn(rawTracks)
        whenever(
            playlistStorageRepository.getProviderPlaylistIsDistinct(eq("SPOTIFY"), eq(playlistId))
        ).doReturn(true)

        viewModel.playPlaylist(SourceType.SPOTIFY, playlistId)
        advanceUntilIdle()

        val captor = argumentCaptor<List<Track>>()
        verify(playbackQueueManager).setQueue(captor.capture(), startIndex = eq(0), autoPlay = eq(true))

        assertEquals(2, captor.firstValue.size)
        assertEquals(listOf("t-1", "t-2"), captor.firstValue.map { it.id })
    }

    // ── playSelectedProviderPlaylist() ───────────────────────────────────────

    @Test
    fun playSelectedProviderPlaylist_isDistinctTrue_setQueueWithDedupedTracks() = runTest {
        val playlistId = "jf-playlist-1"
        val rawTracks = listOf(
            track("j-1", "Theme", "Orchestra"),
            track("j-2", "Outro", "Orchestra"),
            track("j-3", "Theme", "Orchestra") // duplicate of j-1
        )
        val playlist = Playlist(
            id = playlistId,
            name = "Test",
            owner = "owner",
            trackCount = rawTracks.size,
            source = SourceType.JELLYFIN,
            tracks = rawTracks
        )
        whenever(
            playlistStorageRepository.getProviderPlaylistIsDistinct("JELLYFIN", playlistId)
        ).doReturn(true)

        // Open summary so selectedProviderPlaylist + selectedProviderPlaylistTracks are populated
        viewModel.openProviderPlaylistSummary(playlist)
        viewModel.playSelectedProviderPlaylist()

        val captor = argumentCaptor<List<Track>>()
        verify(playbackQueueManager).setQueue(captor.capture(), startIndex = eq(0), autoPlay = eq(true))

        assertEquals(2, captor.firstValue.size)
        assertEquals(listOf("j-1", "j-2"), captor.firstValue.map { it.id })
    }

    @Test
    fun playSelectedProviderPlaylist_isDistinctFalse_setQueueWithAllTracks() = runTest {
        val playlistId = "jf-playlist-2"
        val rawTracks = listOf(
            track("j-1", "Theme", "Orchestra"),
            track("j-2", "Theme", "Orchestra") // duplicate — kept when distinct=false
        )
        val playlist = Playlist(
            id = playlistId,
            name = "Test",
            owner = "owner",
            trackCount = rawTracks.size,
            source = SourceType.JELLYFIN,
            tracks = rawTracks
        )
        whenever(
            playlistStorageRepository.getProviderPlaylistIsDistinct("JELLYFIN", playlistId)
        ).doReturn(false)

        viewModel.openProviderPlaylistSummary(playlist)
        viewModel.playSelectedProviderPlaylist()

        val captor = argumentCaptor<List<Track>>()
        verify(playbackQueueManager).setQueue(captor.capture(), startIndex = eq(0), autoPlay = eq(true))

        assertEquals(2, captor.firstValue.size)
        assertEquals(listOf("j-1", "j-2"), captor.firstValue.map { it.id })
    }

    @Test
    fun setSelectedProviderPlaylistDistinct_true_persistsAndPlaySelectedDedupsQueue() = runTest {
        val playlistId = "jf-playlist-toggle-on"
        val rawTracks = listOf(
            track("j-1", "Theme", "Orchestra"),
            track("j-2", "Outro", "Orchestra"),
            track("j-3", "Theme", "Orchestra") // duplicate of j-1
        )
        val playlist = Playlist(
            id = playlistId,
            name = "Toggle On",
            owner = "owner",
            trackCount = rawTracks.size,
            source = SourceType.JELLYFIN,
            tracks = rawTracks
        )
        whenever(
            playlistStorageRepository.getProviderPlaylistIsDistinct("JELLYFIN", playlistId)
        ).doReturn(false, true, true)

        viewModel.openProviderPlaylistSummary(playlist)
        viewModel.setSelectedProviderPlaylistDistinct(true)
        advanceUntilIdle()
        viewModel.playSelectedProviderPlaylist()

        verify(playlistStorageRepository).setProviderPlaylistIsDistinct("JELLYFIN", playlistId, true)
        val queueCaptor = argumentCaptor<List<Track>>()
        verify(playbackQueueManager).setQueue(queueCaptor.capture(), startIndex = eq(0), autoPlay = eq(true))
        assertEquals(listOf("j-1", "j-2"), queueCaptor.firstValue.map { it.id })
    }

    @Test
    fun setSelectedProviderPlaylistDistinct_false_persistsAndPlaySelectedRestoresFullQueue() = runTest {
        val playlistId = "jf-playlist-toggle-off"
        val rawTracks = listOf(
            track("j-1", "Theme", "Orchestra"),
            track("j-2", "Theme", "Orchestra")
        )
        val playlist = Playlist(
            id = playlistId,
            name = "Toggle Off",
            owner = "owner",
            trackCount = rawTracks.size,
            source = SourceType.JELLYFIN,
            tracks = rawTracks
        )
        whenever(
            playlistStorageRepository.getProviderPlaylistIsDistinct("JELLYFIN", playlistId)
        ).doReturn(true, false, false)

        viewModel.openProviderPlaylistSummary(playlist)
        viewModel.setSelectedProviderPlaylistDistinct(false)
        advanceUntilIdle()
        viewModel.playSelectedProviderPlaylist()

        verify(playlistStorageRepository).setProviderPlaylistIsDistinct("JELLYFIN", playlistId, false)
        val queueCaptor = argumentCaptor<List<Track>>()
        verify(playbackQueueManager).setQueue(queueCaptor.capture(), startIndex = eq(0), autoPlay = eq(true))
        assertEquals(listOf("j-1", "j-2"), queueCaptor.firstValue.map { it.id })
    }

    @Test
    fun playSelectedProviderPlaylist_respectsLatestPreferenceAfterToggleChanges() = runTest {
        val playlistId = "jf-playlist-toggle-cycle"
        val rawTracks = listOf(
            track("j-1", "Theme", "Orchestra"),
            track("j-2", "Outro", "Orchestra"),
            track("j-3", "Theme", "Orchestra")
        )
        val playlist = Playlist(
            id = playlistId,
            name = "Toggle Cycle",
            owner = "owner",
            trackCount = rawTracks.size,
            source = SourceType.JELLYFIN,
            tracks = rawTracks
        )
        whenever(
            playlistStorageRepository.getProviderPlaylistIsDistinct("JELLYFIN", playlistId)
        ).doReturn(false, true, true, false, false)

        viewModel.openProviderPlaylistSummary(playlist)
        viewModel.setSelectedProviderPlaylistDistinct(true)
        advanceUntilIdle()
        viewModel.playSelectedProviderPlaylist()

        viewModel.setSelectedProviderPlaylistDistinct(false)
        advanceUntilIdle()
        viewModel.playSelectedProviderPlaylist()

        val queueCaptor = argumentCaptor<List<Track>>()
        verify(playbackQueueManager, org.mockito.kotlin.times(2))
            .setQueue(queueCaptor.capture(), startIndex = eq(0), autoPlay = eq(true))
        assertEquals(listOf("j-1", "j-2"), queueCaptor.allValues[0].map { it.id })
        assertEquals(listOf("j-1", "j-2", "j-3"), queueCaptor.allValues[1].map { it.id })
    }

    // ── Both entry points apply same gate ────────────────────────────────────

    @Test
    fun bothEntryPoints_isDistinctTrue_produceSameDedupedResult() = runTest {
        val playlistId = "plex-playlist-1"
        val rawTracks = listOf(
            track("p-1", "Intro", "Composer"),
            track("p-2", "Middle", "Composer"),
            track("p-3", "Intro", "Composer") // duplicate
        )
        val playlist = Playlist(
            id = playlistId,
            name = "Plex Playlist",
            owner = "admin",
            trackCount = rawTracks.size,
            source = SourceType.PLEX,
            tracks = rawTracks
        )
        whenever(
            providerCatalogRepository.getPlaylistTracksWithCache(any(), any(), any(), anyOrNull(), anyOrNull(), any())
        ).doReturn(rawTracks)
        whenever(
            playlistStorageRepository.getProviderPlaylistIsDistinct(any(), any())
        ).doReturn(true)

        // Test via playPlaylist
        viewModel.playPlaylist(SourceType.PLEX, playlistId)
        advanceUntilIdle()
        val captorA = argumentCaptor<List<Track>>()
        verify(playbackQueueManager).setQueue(captorA.capture(), startIndex = eq(0), autoPlay = eq(true))
        val queueFromPlayPlaylist = captorA.firstValue

        // Reset mock and test via playSelectedProviderPlaylist
        clearInvocations(playbackQueueManager)
        viewModel.openProviderPlaylistSummary(playlist)
        viewModel.playSelectedProviderPlaylist()
        advanceUntilIdle()
        val captorB = argumentCaptor<List<Track>>()
        verify(playbackQueueManager).setQueue(captorB.capture(), startIndex = eq(0), autoPlay = eq(true))
        val queueFromSelected = captorB.firstValue

        // Both paths must produce identical deduped results
        assertEquals(2, queueFromPlayPlaylist.size)
        assertEquals(2, queueFromSelected.size)
        assertEquals(queueFromPlayPlaylist.map { it.id }, queueFromSelected.map { it.id })
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun track(id: String, title: String, artist: String): Track = Track(
        id = id,
        title = title,
        artist = artist,
        source = SourceType.SPOTIFY,
        durationMs = 200_000L,
        enriched = true
    )
}
