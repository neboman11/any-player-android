package com.anyplayer.android.ui.settings

import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.settings.SyncPreferences
import com.anyplayer.android.data.transfer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { viewModels.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private fun general(settings: FakeSyncSettings = FakeSyncSettings(), sync: FakeSync = FakeSync(), transfer: FakeTransfer = FakeTransfer(), engine: FakeEngine = FakeEngine()): GeneralSettingsViewModel =
        GeneralSettingsViewModel(settings, FakePlayerSettings(), sync, engine, transfer, transfer, FakeDocuments()).also { viewModels += it }
    private fun providers(auth: FakeAuth, catalog: FakeCatalog): ProvidersSettingsViewModel = ProvidersSettingsViewModel(auth, FakeConnections(), catalog,
        object : SpotifyRedirectSource { override val redirects = MutableSharedFlow<String>() }).also { viewModels += it }

    @Test fun syncFieldsPersistOnlyOnCommit() = runTest(dispatcher) {
        val settings = FakeSyncSettings()
        val vm = general(settings)
        runCurrent()
        vm.editTarget("https://sync.test")
        vm.editToken("secret")
        runCurrent()
        assertEquals(SyncPreferences(), settings.preferences.value)
        assertTrue(settings.writes.tryReceive().isFailure)
        vm.commitFields()
        val saved = settings.writes.receive()
        assertEquals("https://sync.test", saved.serverTarget)
        assertEquals("secret", saved.authToken)
    }
    @Test fun connectCommitsDraftBeforeConnecting() = runTest(dispatcher) {
        val settings = FakeSyncSettings(); val sync = FakeSync(); val vm = general(settings, sync)
        runCurrent(); vm.editTarget("https://sync.test"); vm.connect()
        sync.connects.receive()
        assertEquals("https://sync.test", settings.preferences.value.serverTarget)
    }
    @Test fun domainChipsPersistImmediately() = runTest(dispatcher) {
        val settings = FakeSyncSettings(); val vm = general(settings)
        runCurrent(); vm.setDomain("playlists", false)
        assertFalse(settings.writes.receive().syncPlaylists)
    }
    @Test fun playlistOverwriteRequiresConfirmationAndCancelDoesNotPull() = runTest(dispatcher) {
        val sync = FakeSync(); val vm = general(sync = sync)
        runCurrent(); vm.requestPull()
        assertTrue(vm.state.value.confirmOverwrite); assertTrue(sync.pulls.isEmpty())
        vm.cancelPull(); assertFalse(vm.state.value.confirmOverwrite); assertTrue(sync.pulls.isEmpty())
        vm.requestPull(); vm.confirmPull()
        assertEquals(listOf(true), sync.pulls)
    }
    @Test fun disabledPlaylistDomainPullsWithoutConfirmation() = runTest(dispatcher) {
        val settings = FakeSyncSettings().apply { preferences.value = SyncPreferences(syncPlaylists = false) }
        val sync = FakeSync(); val vm = general(settings, sync)
        runCurrent(); vm.requestPull()
        assertFalse(vm.state.value.confirmOverwrite); assertEquals(listOf(false), sync.pulls)
    }
    @Test fun providerConnectUsesLegacyFeedback() = runTest(dispatcher) {
        val auth = FakeAuth(); val catalog = FakeCatalog().apply {
            playlists.value = listOf(Playlist("1", "List", "", 0, SourceType.JELLYFIN))
        }
        val vm = providers(auth, catalog)
        runCurrent(); vm.connect(SourceType.JELLYFIN)
        val connected = vm.state.first { !it.busy && it.feedback.isNotBlank() }
        assertEquals("Jellyfin connected. Loaded 1 provider playlist(s).", connected.feedback)
        auth.failure = "invalid token"
        vm.connect(SourceType.PLEX)
        assertEquals("Plex connection failed: invalid token", vm.state.first { !it.busy }.feedback)
    }
    @Test fun missingSpotifyClientIdUsesSetupMessage() = runTest(dispatcher) {
        val auth = FakeAuth().apply { failure = "Spotify client ID is required" }
        val vm = providers(auth, FakeCatalog())
        runCurrent(); vm.linkSpotify()
        assertEquals(ProvidersSettingsViewModel.MISSING_CLIENT_ID, vm.state.first { !it.busy }.feedback)
    }
    @Test fun importSummariesUseEveryLegacyPrefix() = runTest(dispatcher) {
        val transfer = FakeTransfer(); val vm = general(transfer = transfer)
        for ((config, dryRun, prefix) in listOf(Triple(false, false, "Import"), Triple(false, true, "Dry run"), Triple(true, false, "Config import"), Triple(true, true, "Config dry run"))) {
            vm.import("content://test", MergePolicy.MERGE_KEEP_LOCAL, null, dryRun, config)
            assertEquals(transfer.summary.format(prefix), vm.state.first { !it.transferBusy }.transferStatus)
        }
        assertEquals("Import complete. playlists +2/~0, tracks +0/~3, union +0/~0, connections +0/skip 0, warning", transfer.summary.format("Import"))
    }
    @Test fun transferErrorsAreVisible() = runTest(dispatcher) {
        val transfer = FakeTransfer().apply { failure = "bad file" }; val vm = general(transfer = transfer)
        vm.import("content://test", MergePolicy.REPLACE_ALL, null, true, false)
        assertEquals("Dry run failed: bad file", vm.state.first { !it.transferBusy }.transferStatus)
    }
    @Test fun exportIncludesPlaybackInInsertionAndPlayOrder() = runTest(dispatcher) {
        val a = Track("a", "A", "Artist", source = SourceType.PLEX)
        val b = a.copy(id = "b")
        val engine = FakeEngine().apply { queue.value = QueueState(listOf(QueueEntry(1, a), QueueEntry(2, b)), listOf(1, 0), 0, shuffle = true) }
        val transfer = FakeTransfer(); val vm = general(transfer = transfer, engine = engine)
        vm.export("content://out", ExportMode.PORTABLE, null)
        vm.state.first { !it.transferBusy }
        assertEquals(listOf(a, b), transfer.exported?.queue)
        assertEquals(listOf(b, a), transfer.exported?.orderedQueue)
        assertEquals(b, transfer.exported?.currentTrack)
        assertEquals(123L, transfer.exported?.position)
    }
    @Test fun voiceLevelPersistsOnlyWhenSliderFinishes() = runTest(dispatcher) {
        val dj = FakeDj(); val vm = AiDjSettingsViewModel(dj).also { viewModels += it }
        runCurrent(); vm.editVoiceLevel(1.2f); vm.editVoiceLevel(1.5f)
        assertTrue(dj.levelWrites.isEmpty()); assertEquals(1.5f, vm.state.value.voiceLevel)
        vm.commitVoiceLevel(); vm.commitVoiceLevel()
        assertEquals(listOf(1.5f), dj.levelWrites)
    }
    @Test fun catalogsRefreshOncePerEnabledEntry() = runTest(dispatcher) {
        val dj = FakeDj(); val vm = AiDjSettingsViewModel(dj).also { viewModels += it }
        runCurrent(); vm.enter(); assertEquals(0, dj.refreshes)
        vm.setEnabled(true); runCurrent(); vm.enter(); assertEquals(1, dj.refreshes)
        vm.leave(); vm.enter(); assertEquals(2, dj.refreshes)
        vm.setEnabled(false); runCurrent(); vm.setEnabled(true); runCurrent(); assertEquals(2, dj.refreshes)
    }
    @Test fun providerCatalogFailureIsNotReportedAsSuccessfulConnection() = runTest(dispatcher) {
        val catalog = FakeCatalog().apply {
            failures = listOf(com.anyplayer.android.data.catalog.ProviderRefreshFailure(SourceType.PLEX, "offline"))
        }
        val vm = providers(FakeAuth(), catalog)
        runCurrent(); vm.connect(SourceType.PLEX)
        assertEquals("Plex connection failed: offline", vm.state.first { !it.busy }.feedback)
    }
    @Test fun pageSizeSavedFeedbackFollowsRepositoryCompletion() = runTest(dispatcher) {
        val auth = FakeAuth(); val vm = providers(auth, FakeCatalog())
        runCurrent(); vm.updatePageSize(SourceType.JELLYFIN, "450")
        assertFalse(vm.state.value.jellyfin.pageSizeSaved)
        vm.state.first { it.jellyfin.pageSizeSaved }
        assertEquals(450, auth.pageSize)
    }
    @Test fun voiceLevelIsClampedBeforeCommit() = runTest(dispatcher) {
        val dj = FakeDj(); val vm = AiDjSettingsViewModel(dj).also { viewModels += it }
        runCurrent(); vm.editVoiceLevel(9f); vm.commitVoiceLevel()
        assertEquals(listOf(2f), dj.levelWrites)
    }
    @Test fun pickerAllowsSwitchAndRetryButNotConcurrentDownload() {
        val model = com.anyplayer.android.dj.ModelDescriptor("new", "New model", "1", 0, "")
        val catalog = com.anyplayer.android.dj.ArtifactCatalogState(items = listOf(model), selectedId = "new", activeId = "old")
        assertTrue(canDownload(catalog, com.anyplayer.android.dj.ArtifactDownloadState.Ready(java.io.File("old"))))
        assertTrue(canDownload(catalog, com.anyplayer.android.dj.ArtifactDownloadState.Failed("offline")))
        assertFalse(canDownload(catalog, com.anyplayer.android.dj.ArtifactDownloadState.Downloading(0.5f)))
    }
}
