package com.anyplayer.android.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.*
import com.anyplayer.android.ui.components.*
import com.anyplayer.android.ui.playlists.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<ViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { viewModels.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    @Test fun providerDuplicateGroupsKeepOriginalIndicesAndDistinctPlaySelectsFirstOccurrence() = runTest(dispatcher) {
        val first = track("first", "Song", "Artist")
        val tracks = listOf(first, track("other"), first.copy(id = "duplicate", title = " song ", artist = "artist"))
        val catalog = UiCatalog().apply { this.tracks = tracks; distinct = true }
        val engine = RecordingEngine()
        val vm = ProviderPlaylistViewModel(
            SavedStateHandle(mapOf("source" to SourceType.PLEX.name, "id" to "provider")),
            catalog, UiPlaylists(), engine, dispatcher,
        ).also { viewModels += it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        val group = vm.state.value.duplicates.single()
        assertEquals(0, group.kept.originalIndex)
        assertEquals(listOf(2), group.duplicates.map { it.originalIndex })
        vm.playFrom(2); runCurrent()
        assertEquals(listOf(first, tracks[1]), engine.selection?.tracks)
        assertEquals(0, engine.selection?.startIndex)
        vm.setDistinct(false); runCurrent()
        assertTrue(vm.state.value.duplicates.isEmpty())
        vm.playFrom(2); runCurrent()
        assertEquals(tracks, engine.selection?.tracks)
        assertEquals(2, engine.selection?.startIndex)
    }

    @Test fun customDetailsRemoveUsingUnsortedIndexAndFilterSortPersistInSavedState() = runTest(dispatcher) {
        val playlists = UiPlaylists()
        val item = playlists.create("Standard", PlaylistType.STANDARD)
        playlists.setDistinct(item.id, true)
        playlists.tracks.value = listOf(track("z", "Zulu"), track("a", "Alpha"), track("a", "Alpha"))
        val handle = SavedStateHandle(mapOf("id" to item.id))
        val vm = CustomPlaylistViewModel(handle, UiCatalog(), playlists, RecordingEngine(), dispatcher).also { viewModels += it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        assertEquals(listOf(2), vm.state.value.duplicates.single().duplicates.map { it.originalIndex })
        vm.toggleSort(TrackSortColumn.TITLE); vm.setFilter("Alpha"); runCurrent()
        assertEquals(listOf(1, 2), vm.state.value.table.rows.map { it.originalIndex })
        assertEquals("Alpha", handle.get<String>("playlist_custom_track_filter"))
        vm.removeTrackAt(vm.state.value.table.rows.last().originalIndex); runCurrent()
        assertEquals(listOf(2), playlists.removals)
        vm.toggleSort(TrackSortColumn.TITLE); runCurrent()
        assertFalse(vm.state.value.table.sort.ascending)
        vm.toggleSort(TrackSortColumn.TITLE); runCurrent()
        assertNull(vm.state.value.table.sort.column)
    }

    @Test fun unionSourcesAreNormalizedOrderedAndSavedViaRepository() = runTest(dispatcher) {
        val playlists = UiPlaylists()
        val union = playlists.create("Union", PlaylistType.UNION)
        val standard = playlists.create("Local source", PlaylistType.STANDARD)
        val catalog = UiCatalog().apply {
            this.playlists.value = listOf(Playlist("remote", "Remote source", "", 2, SourceType.PLEX))
        }
        playlists.sources.value = listOf(UnionPlaylistSource("s", union.id, SourceType.PLEX, "remote", 0, "now"))
        val vm = CustomPlaylistViewModel(SavedStateHandle(mapOf("id" to union.id)), catalog, playlists, RecordingEngine(), dispatcher)
            .also { viewModels += it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        assertEquals(listOf("Remote source (plex)"), vm.state.value.sourceLabels)
        assertEquals(setOf("PLEX:remote", "CUSTOM:${standard.id}"), vm.state.value.sourceCandidates.map { it.key }.toSet())
        vm.saveUnionSources(listOf("CUSTOM:${standard.id}", "PLEX:remote")); runCurrent()
        assertEquals(listOf(SourceType.CUSTOM to standard.id, SourceType.PLEX to "remote"), playlists.replacementSources)
        vm.materialize(); runCurrent()
        assertFalse(vm.state.value.refresh.inProgress)
        assertEquals("Union playlist materialized successfully!", vm.state.value.refresh.status)
    }

    @Test fun tableSortIsStableAndClipboardSanitizesEmbeddedSeparators() {
        val tracks = listOf(track("a", "Same"), track("a", "Same"), track("b", "Beta"))
        assertEquals(listOf(2, 0, 1), TrackTables.buildRows(tracks, TrackSort(TrackSortColumn.TITLE), "").map { it.originalIndex })
        assertEquals(listOf(0, 1, 2), TrackTables.buildRows(tracks, TrackSort(TrackSortColumn.TITLE, false), "").map { it.originalIndex })
        assertEquals("Title line\tArtist name\tAlbum", TrackTables.clipboardText(listOf(track(title = "Title\nline", artist = "Artist\tname").copy(album = "Album"))))
    }

    @Test fun listCreateDeleteAndRefreshUseRepositoryFlows() = runTest(dispatcher) {
        val playlists = UiPlaylists()
        val vm = PlaylistsViewModel(UiCatalog(), playlists, RecordingEngine(), dispatcher).also { viewModels += it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        vm.create("", PlaylistType.STANDARD); vm.create("Union", PlaylistType.UNION); runCurrent()
        assertEquals(listOf("Untitled Playlist", "Union"), vm.state.value.customPlaylists.map { it.name })
        vm.delete(vm.state.value.customPlaylists.first().id); vm.refresh(); runCurrent()
        assertEquals(listOf("Union"), vm.state.value.customPlaylists.map { it.name })
        assertFalse(vm.state.value.refresh.inProgress)
        assertEquals("Playlist data refreshed successfully!", vm.state.value.refresh.status)
    }
}
