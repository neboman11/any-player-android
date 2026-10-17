package com.anyplayer.android.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.SearchType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.catalog.CatalogRepository
import com.anyplayer.android.data.playlists.PlaylistRepository
import com.anyplayer.android.playback.PlaybackEngine
import com.anyplayer.android.ui.components.ComputeDispatcher
import com.anyplayer.android.ui.components.PlaylistChoice
import com.anyplayer.android.ui.components.TrackSortColumn
import com.anyplayer.android.ui.components.TrackTableController
import com.anyplayer.android.ui.components.TrackTableUi
import com.anyplayer.android.ui.playlists.NOTHING_TO_PLAY
import com.anyplayer.android.ui.playlists.playProviderPlaylist
import com.anyplayer.android.ui.playlists.standardPlaylistChoices
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchPhase { IDLE, LOADING, RESULTS, EMPTY, ERROR }

@Immutable
data class PlaylistResultUi(
    val source: SourceType,
    val id: String,
    val name: String,
    val trackCount: Int,
    val imageUrl: String?,
) {
    val key: String get() = "${source.name}_$id"
}

@Immutable
data class SearchUi(
    val source: SourceType = SourceType.ALL,
    val type: SearchType = SearchType.TRACKS,
    val phase: SearchPhase = SearchPhase.IDLE,
    val error: String? = null,
    val table: TrackTableUi = TrackTableUi(),
    val playlists: List<PlaylistResultUi> = emptyList(),
    val standardPlaylists: List<PlaylistChoice> = emptyList(),
)

/** Chip order: all, spotify, jellyfin, plex. */
val SearchSources: List<SourceType> = listOf(SourceType.ALL, SourceType.SPOTIFY, SourceType.JELLYFIN, SourceType.PLEX)

private data class SearchRequest(val query: String, val source: SourceType, val type: SearchType, val seq: Long)

private data class SearchOutcome(
    val phase: SearchPhase = SearchPhase.IDLE,
    val error: String? = null,
    val type: SearchType = SearchType.TRACKS,
    val playlists: List<PlaylistResultUi> = emptyList(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val playlists: PlaylistRepository,
    private val engine: PlaybackEngine,
    @ComputeDispatcher private val computeDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /** Read synchronously by the query text field. */
    val query: StateFlow<String> = savedStateHandle.getStateFlow(KEY_QUERY, "")
    private val source = savedStateHandle.getStateFlow(KEY_SOURCE, SourceType.ALL.name)
        .map { name -> SourceType.entries.firstOrNull { it.name == name } ?: SourceType.ALL }
    private val type = savedStateHandle.getStateFlow(KEY_TYPE, SearchType.TRACKS.name)
        .map { name -> SearchType.entries.firstOrNull { it.name == name } ?: SearchType.TRACKS }

    private val requests = MutableStateFlow<SearchRequest?>(null)
    private var seq = 0L
    private val outcome = MutableStateFlow(SearchOutcome())
    private val trackResults = MutableStateFlow<List<Track>>(emptyList())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val tableController = TrackTableController(
        savedStateHandle, "search_track", trackResults, viewModelScope, computeDispatcher,
    )

    val state: StateFlow<SearchUi> = combine(
        combine(source, type, ::Pair),
        outcome,
        tableController.table,
        playlists.standardPlaylistChoices(),
    ) { (s, t), o, table, choices ->
        SearchUi(
            source = s,
            type = t,
            phase = o.phase,
            error = o.error,
            table = table,
            playlists = o.playlists,
            standardPlaylists = choices,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUi())

    init {
        viewModelScope.launch {
            // collectLatest cancels an in-flight search as soon as a newer request arrives.
            requests.filterNotNull().collectLatest(::runSearch)
        }
        if (query.value.isNotBlank()) submit()
    }

    fun setQuery(value: String) {
        savedStateHandle[KEY_QUERY] = value
    }

    fun setSource(value: SourceType) {
        savedStateHandle[KEY_SOURCE] = value.name
        submit()
    }

    fun setType(value: SearchType) {
        savedStateHandle[KEY_TYPE] = value.name
        submit()
    }

    fun submit() {
        val source = savedStateHandle.get<String>(KEY_SOURCE)?.let { name -> SourceType.entries.firstOrNull { it.name == name } }
            ?: SourceType.ALL
        val type = savedStateHandle.get<String>(KEY_TYPE)?.let { name -> SearchType.entries.firstOrNull { it.name == name } }
            ?: SearchType.TRACKS
        requests.value = SearchRequest(query.value.trim(), source, type, ++seq)
    }

    fun toggleSort(column: TrackSortColumn) = tableController.toggleSort(column)

    private suspend fun runSearch(request: SearchRequest) {
        if (request.query.isEmpty()) {
            trackResults.value = emptyList()
            outcome.value = SearchOutcome(type = request.type)
            return
        }
        outcome.value = outcome.value.copy(phase = SearchPhase.LOADING, error = null)
        try {
            val result = catalog.search(request.query, request.source, request.type)
            val playlistResults = withContext(computeDispatcher) {
                result.playlists.map { PlaylistResultUi(it.source, it.id, it.name, it.trackCount, it.imageUrl) }
            }
            trackResults.value = if (request.type == SearchType.TRACKS) result.tracks else emptyList()
            val empty = if (request.type == SearchType.TRACKS) result.tracks.isEmpty() else playlistResults.isEmpty()
            outcome.value = SearchOutcome(
                phase = if (empty) SearchPhase.EMPTY else SearchPhase.RESULTS,
                type = request.type,
                playlists = if (request.type == SearchType.PLAYLISTS) playlistResults else emptyList(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trackResults.value = emptyList()
            outcome.value = SearchOutcome(SearchPhase.ERROR, e.message ?: "Search failed", request.type)
        }
    }

    /** Replaces the queue with every track result, starting at [originalIndex]. */
    fun playTrack(originalIndex: Int) {
        val tracks = trackResults.value
        if (originalIndex !in tracks.indices) return
        engine.setQueue(tracks, originalIndex, autoPlay = true)
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

    fun playPlaylist(result: PlaylistResultUi) {
        viewModelScope.launch {
            try {
                if (!playProviderPlaylist(catalog, engine, computeDispatcher, result.source, result.id)) {
                    _messages.send(NOTHING_TO_PLAY)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.message ?: "Could not play playlist")
            }
        }
    }

    companion object {
        const val KEY_QUERY = "search_query"
        const val KEY_SOURCE = "search_source"
        const val KEY_TYPE = "search_type"
    }
}
