package com.anyplayer.android.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.*
import com.anyplayer.android.ui.components.PlaylistChoice
import com.anyplayer.android.ui.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var viewModel: SearchViewModel? = null
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { viewModel?.viewModelScope?.cancel(); Dispatchers.resetMain() }

    @Test fun newerSubmissionCancelsStaleSearchAndPublishesLoadingAndResults() = runTest(dispatcher) {
        val oldCancelled = CompletableDeferred<Unit>()
        val catalog = UiCatalog().apply {
            search = { query, _, _ ->
                if (query == "old") {
                    try { awaitCancellation() } finally { oldCancelled.complete(Unit) }
                } else {
                    delay(50)
                    SearchResult(tracks = listOf(track("new")))
                }
            }
        }
        val vm = SearchViewModel(SavedStateHandle(), catalog, UiPlaylists(), RecordingEngine(), dispatcher).also { viewModel = it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        assertEquals(SearchPhase.IDLE, vm.state.value.phase)
        vm.setQuery("old"); vm.submit(); runCurrent()
        assertEquals(SearchPhase.LOADING, vm.state.value.phase)
        vm.setQuery("new"); vm.submit(); runCurrent()
        assertTrue(oldCancelled.isCompleted)
        assertEquals(SearchPhase.LOADING, vm.state.value.phase)
        advanceTimeBy(51); runCurrent()
        assertEquals(SearchPhase.RESULTS, vm.state.value.phase)
        assertEquals(listOf("new"), vm.state.value.table.rows.map { it.track.id })
    }

    @Test fun emptyBlankAndErrorStatesAreExplicitAndCanRecover() = runTest(dispatcher) {
        val catalog = UiCatalog()
        val vm = SearchViewModel(SavedStateHandle(), catalog, UiPlaylists(), RecordingEngine(), dispatcher).also { viewModel = it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        vm.setQuery("missing"); vm.submit(); runCurrent()
        assertEquals(SearchPhase.EMPTY, vm.state.value.phase)
        catalog.search = { _, _, _ -> error("offline") }
        vm.submit(); runCurrent()
        assertEquals(SearchPhase.ERROR, vm.state.value.phase)
        assertEquals("offline", vm.state.value.error)
        vm.setQuery("   "); vm.submit(); runCurrent()
        assertEquals(SearchPhase.IDLE, vm.state.value.phase)
        assertNull(vm.state.value.error)
        catalog.search = { _, _, _ -> SearchResult(tracks = listOf(track())) }
        vm.setQuery("song"); vm.submit(); runCurrent()
        assertEquals(SearchPhase.RESULTS, vm.state.value.phase)
    }

    @Test fun restoredQuerySourceAndTypeRunOnlyRequestedSearchAndPlaylistCanPlay() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf(
            SearchViewModel.KEY_QUERY to "saved",
            SearchViewModel.KEY_SOURCE to SourceType.JELLYFIN.name,
            SearchViewModel.KEY_TYPE to SearchType.PLAYLISTS.name,
        ))
        var requested: Triple<String, SourceType, SearchType>? = null
        val catalog = UiCatalog().apply {
            tracks = listOf(track())
            search = { query, source, type ->
                requested = Triple(query, source, type)
                SearchResult(playlists = listOf(Playlist("id", "Saved playlist", "", 1, SourceType.JELLYFIN)))
            }
        }
        val engine = RecordingEngine()
        val vm = SearchViewModel(handle, catalog, UiPlaylists(), engine, dispatcher).also { viewModel = it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        assertEquals(Triple("saved", SourceType.JELLYFIN, SearchType.PLAYLISTS), requested)
        assertEquals(SourceType.JELLYFIN, vm.state.value.source)
        assertEquals(SearchType.PLAYLISTS, vm.state.value.type)
        assertTrue(vm.state.value.table.rows.isEmpty())
        vm.playPlaylist(vm.state.value.playlists.single()); runCurrent()
        assertEquals(catalog.tracks, engine.selection?.tracks)
    }

    @Test fun trackActionsUseOriginalResultIndexAndRealPlaylistPickerChoice() = runTest(dispatcher) {
        val repeated = track()
        val catalog = UiCatalog().apply { search = { _, _, _ -> SearchResult(tracks = listOf(repeated, repeated)) } }
        val playlists = UiPlaylists()
        val engine = RecordingEngine()
        val vm = SearchViewModel(SavedStateHandle(), catalog, playlists, engine, dispatcher).also { viewModel = it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        vm.setQuery("song"); vm.submit(); runCurrent()
        assertEquals(listOf(0, 1), vm.state.value.table.rows.map { it.originalIndex })
        vm.playTrack(1)
        assertEquals(1, engine.selection?.startIndex)
        assertEquals(2, engine.selection?.tracks?.size)
        vm.addToQueue(repeated)
        assertEquals(listOf(repeated), engine.queued)
        vm.addToPlaylist(PlaylistChoice("p", "Standard", 0), repeated); runCurrent()
        assertEquals(listOf("p" to repeated), playlists.additions)
    }
}
