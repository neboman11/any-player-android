package com.anyplayer.android.ui.playlists

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.normalizePlaylistId
import com.anyplayer.android.data.catalog.CatalogRepository
import com.anyplayer.android.data.playlists.PlaylistRepository
import com.anyplayer.android.playback.PlaybackEngine
import com.anyplayer.android.ui.components.ComputeDispatcher
import com.anyplayer.android.ui.components.PlaylistChoice
import com.anyplayer.android.ui.components.TrackSortColumn
import com.anyplayer.android.ui.components.TrackTableController
import com.anyplayer.android.ui.components.TrackTableUi
import com.anyplayer.android.ui.components.TrackTables
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class ProviderDetailUi(
    val name: String = "",
    val source: SourceType = SourceType.ALL,
    val loading: Boolean = true,
    val error: String? = null,
    val distinct: Boolean = false,
    val duplicates: List<DuplicateGroupUi> = emptyList(),
    val table: TrackTableUi = TrackTableUi(),
    val refresh: RefreshUi = RefreshUi(),
    val standardPlaylists: List<PlaylistChoice> = emptyList(),
)

private data class ProviderLoad(val loading: Boolean = true, val error: String? = null)

private data class ProviderMeta(val name: String, val trackCount: Int)

@HiltViewModel
class ProviderPlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val playlists: PlaylistRepository,
    private val engine: PlaybackEngine,
    @ComputeDispatcher private val computeDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val source: SourceType = SourceType.valueOf(checkNotNull(savedStateHandle.get<String>(ARG_SOURCE)))
    private val playlistId: String = checkNotNull(savedStateHandle.get<String>(ARG_ID))

    private val tracks = MutableStateFlow<List<Track>>(emptyList())
    private val load = MutableStateFlow(ProviderLoad())
    private val refresh = MutableStateFlow(RefreshUi())

    /** Null until the stored preference is read (or the user toggles first). */
    private val distinct = MutableStateFlow<Boolean?>(null)
    private val distinctWrites = MutableStateFlow<Boolean?>(null)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val tableController = TrackTableController(
        savedStateHandle, "playlist_provider_track", tracks, viewModelScope, computeDispatcher,
    )
    val filter: StateFlow<String> = tableController.filter

    private val meta: Flow<ProviderMeta> = catalog.playlists
        .map(::findMeta)
        .distinctUntilChanged()
        .onStart { emit(ProviderMeta(playlistId, 0)) }

    private val duplicates: Flow<List<DuplicateGroupUi>> = combine(tracks, distinct) { list, d -> list to (d == true) }
        .map { (list, d) -> if (d) DuplicateGroups.build(list) else emptyList() }
        .flowOn(computeDispatcher)

    private val header = combine(meta, load, distinct, refresh) { m, l, d, r ->
        ProviderDetailUi(name = m.name, source = source, loading = l.loading, error = l.error, distinct = d == true, refresh = r)
    }

    val state: StateFlow<ProviderDetailUi> = combine(
        header,
        duplicates,
        tableController.table,
        playlists.standardPlaylistChoices(),
    ) { h, dups, table, choices -> h.copy(duplicates = dups, table = table, standardPlaylists = choices) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProviderDetailUi(name = playlistId, source = source))

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val stored = runCatching { catalog.isDistinct(source, playlistId) }.getOrDefault(false)
            distinct.compareAndSet(null, stored)
        }
        viewModelScope.launch {
            distinctWrites.filterNotNull().collectLatest { value ->
                runCatching { catalog.setDistinct(source, playlistId, value) }
            }
        }
        loadTracks()
    }

    fun retry() = loadTracks()

    private fun loadTracks() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val cached = runCatching { catalog.cachedTracks(source, playlistId) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) tracks.value = cached
            load.value = ProviderLoad(loading = cached.isEmpty())
            load.value = try {
                val fresh = catalog.tracks(source, playlistId)
                tracks.value = fresh
                val expected = findMeta(catalog.playlists.first()).trackCount
                if (fresh.isEmpty() && expected > 0) {
                    ProviderLoad(false, "Unable to load tracks for this playlist. Try reconnecting the provider and retry.")
                } else {
                    ProviderLoad(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProviderLoad(false, e.message ?: "Unable to load tracks for this playlist.")
            }
        }
    }

    private fun findMeta(list: List<Playlist>): ProviderMeta {
        val normalized = normalizePlaylistId(source, playlistId)
        return list.firstOrNull { it.source == source && (it.id == playlistId || normalizePlaylistId(source, it.id) == normalized) }
            ?.let { ProviderMeta(it.name, it.trackCount) }
            ?: ProviderMeta(playlistId, 0)
    }

    fun setDistinct(value: Boolean) {
        distinct.value = value
        distinctWrites.value = value
    }

    fun setFilter(value: String) = tableController.setFilter(value)

    fun toggleSort(column: TrackSortColumn) = tableController.toggleSort(column)

    /** Re-fetches every provider playlist (legacy behaviour), then reloads this one. */
    fun refresh() {
        if (refresh.value.inProgress) return
        refresh.value = RefreshUi(inProgress = true, status = "Starting playlist refresh...")
        viewModelScope.launch {
            val status = try {
                val failures = catalog.refreshAll { progress -> refresh.update { it.copy(status = progress) } }
                refreshResultText(failures)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Playlist data refresh failed."
            }
            refresh.value = RefreshUi(inProgress = false, status = status)
            loadTracks()
        }
    }

    fun playAll() = play(fromIndex = null)

    fun playFrom(originalIndex: Int) = play(fromIndex = originalIndex)

    private fun play(fromIndex: Int?) {
        val snapshot = tracks.value
        val isDistinct = distinct.value == true
        viewModelScope.launch {
            val selection = withContext(computeDispatcher) { ProviderPlayback.selection(snapshot, isDistinct, fromIndex) }
            if (!engine.startSelection(selection)) _messages.send(NOTHING_TO_PLAY)
        }
    }

    fun addToQueue(track: Track) {
        engine.playNext(track)
        _messages.trySend("Added to queue")
    }

    fun addToPlaylist(choice: PlaylistChoice, track: Track) {
        viewModelScope.launch {
            val message = try {
                playlists.addTrack(choice.id, track)
                "Added to ${choice.name}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Could not add to ${choice.name}"
            }
            _messages.send(message)
        }
    }

    suspend fun clipboardText(): String {
        val snapshot = tracks.value
        return withContext(computeDispatcher) { TrackTables.clipboardText(snapshot) }
    }

    companion object {
        const val ARG_SOURCE = "source"
        const val ARG_ID = "id"
    }
}
