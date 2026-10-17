package com.anyplayer.android.ui.playlists

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.CustomPlaylist
import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.UnionPlaylistSource
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A playlist that can be a UNION source; [key] is `"${source.name}:${normalizedId}"`. */
@Immutable
data class UnionSourceCandidate(val key: String, val name: String, val source: SourceType)

@Immutable
data class CustomDetailUi(
    val loaded: Boolean = false,
    val exists: Boolean = true,
    val name: String = "",
    val type: PlaylistType = PlaylistType.STANDARD,
    val distinct: Boolean = false,
    val duplicates: List<DuplicateGroupUi> = emptyList(),
    val table: TrackTableUi = TrackTableUi(),
    val refresh: RefreshUi = RefreshUi(),
    /** UNION only: "name (source)" per source, in source order. */
    val sourceLabels: List<String> = emptyList(),
    /** UNION only: keys of the current sources, in source order. */
    val selectedSourceKeys: List<String> = emptyList(),
    /** UNION only: provider playlists, then non-UNION custom playlists. */
    val sourceCandidates: List<UnionSourceCandidate> = emptyList(),
    val standardPlaylists: List<PlaylistChoice> = emptyList(),
)

private data class CustomHeader(val playlist: CustomPlaylist?, val loaded: Boolean)

private data class UnionUi(
    val labels: List<String> = emptyList(),
    val selectedKeys: List<String> = emptyList(),
    val candidates: List<UnionSourceCandidate> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CustomPlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val playlists: PlaylistRepository,
    private val engine: PlaybackEngine,
    @ComputeDispatcher private val computeDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle.get<String>(ARG_ID))

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val refresh = MutableStateFlow(RefreshUi())

    private val header: StateFlow<CustomHeader> = playlists.observePlaylist(playlistId)
        .map { CustomHeader(it, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CustomHeader(null, loaded = false))

    private val tracks: StateFlow<List<Track>> = playlists.observeTracks(playlistId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val tableController = TrackTableController(
        savedStateHandle, "playlist_custom_track", tracks, viewModelScope, computeDispatcher,
    )
    val filter: StateFlow<String> = tableController.filter

    private val distinct: Flow<Boolean> = header.map { it.playlist?.isDistinct == true }.distinctUntilChanged()

    private val duplicates: Flow<List<DuplicateGroupUi>> = combine(tracks, distinct) { list, d -> list to d }
        .map { (list, d) -> if (d) DuplicateGroups.build(list) else emptyList() }
        .flowOn(computeDispatcher)

    private val union: Flow<UnionUi> = header
        .map { it.playlist?.playlistType == PlaylistType.UNION }
        .distinctUntilChanged()
        .flatMapLatest { isUnion ->
            if (!isUnion) {
                flowOf(UnionUi())
            } else {
                combine(
                    playlists.observeUnionSources(playlistId),
                    catalog.playlists.onStart { emit(emptyList()) },
                    playlists.customPlaylists,
                ) { sources, providers, customs -> buildUnionUi(sources, providers, customs) }
                    .flowOn(computeDispatcher)
            }
        }

    private val choices: Flow<List<PlaylistChoice>> = playlists.standardPlaylistChoices()
        .map { list -> list.filter { it.id != playlistId } }

    val state: StateFlow<CustomDetailUi> = combine(
        combine(header, duplicates, refresh, ::Triple),
        tableController.table,
        union,
        choices,
    ) { (h, dups, r), table, u, c ->
        val playlist = h.playlist
        CustomDetailUi(
            loaded = h.loaded,
            exists = !h.loaded || playlist != null,
            name = playlist?.name.orEmpty(),
            type = playlist?.playlistType ?: PlaylistType.STANDARD,
            distinct = playlist?.isDistinct == true,
            duplicates = dups,
            table = table,
            refresh = r,
            sourceLabels = u.labels,
            selectedSourceKeys = u.selectedKeys,
            sourceCandidates = u.candidates,
            standardPlaylists = c,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CustomDetailUi())

    fun setFilter(value: String) = tableController.setFilter(value)

    fun toggleSort(column: TrackSortColumn) = tableController.toggleSort(column)

    fun setDistinct(value: Boolean) = launchCatching("Could not update playlist") {
        playlists.setDistinct(playlistId, value)
    }

    fun playAll() = launchCatching("Could not play playlist") {
        if (!playCustomPlaylist(playlists, engine, playlistId)) _messages.send(NOTHING_TO_PLAY)
    }

    fun playFrom(originalIndex: Int) = launchCatching("Could not play playlist") {
        if (!playCustomPlaylist(playlists, engine, playlistId, originalIndex)) _messages.send(NOTHING_TO_PLAY)
    }

    fun addToQueue(track: Track) {
        engine.playNext(track)
        _messages.trySend("Added to queue")
    }

    fun addToPlaylist(choice: PlaylistChoice, track: Track) = launchCatching("Could not add to ${choice.name}") {
        playlists.addTrack(choice.id, track)
        _messages.send("Added to ${choice.name}")
    }

    /** STANDARD only; [originalIndex] is the stored position (also used for duplicate removal). */
    fun removeTrackAt(originalIndex: Int) = launchCatching("Could not remove track") {
        playlists.removeTrackAt(playlistId, originalIndex)
    }

    /** UNION: materializes the sources with progress feedback. */
    fun materialize() {
        if (refresh.value.inProgress) return
        refresh.value = RefreshUi(inProgress = true, status = "Materializing union playlist...")
        viewModelScope.launch {
            val status = try {
                playlists.materializeUnion(playlistId, forceRefresh = true) { progress ->
                    refresh.update { it.copy(status = progress) }
                }
                "Union playlist materialized successfully!"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Failed to materialize union playlist."
            }
            refresh.value = RefreshUi(inProgress = false, status = status)
        }
    }

    /** UNION: replaces the sources with [orderedKeys] (selection order). */
    fun saveUnionSources(orderedKeys: List<String>) = launchCatching("Could not update union sources") {
        val sources = orderedKeys.mapNotNull { key ->
            val source = SourceType.entries.firstOrNull { it.name == key.substringBefore(':') } ?: return@mapNotNull null
            source to key.substringAfter(':')
        }
        playlists.replaceUnionSources(playlistId, sources)
    }

    suspend fun clipboardText(): String {
        val snapshot = tracks.value
        return withContext(computeDispatcher) { TrackTables.clipboardText(snapshot) }
    }

    private fun launchCatching(fallback: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.message ?: fallback)
            }
        }
    }

    private fun buildUnionUi(
        sources: List<UnionPlaylistSource>,
        providers: List<Playlist>,
        customs: List<CustomPlaylist>,
    ): UnionUi {
        val providerNames = HashMap<String, String>(providers.size)
        providers.forEach { providerNames[sourceKey(it.source, it.id)] = it.name }
        val customNames = customs.associate { it.id to it.name }
        val selected = sources.sortedBy { it.position }.map { source ->
            val key = sourceKey(source.sourceType, source.sourcePlaylistId)
            val name = if (source.sourceType == SourceType.CUSTOM) {
                customNames[source.sourcePlaylistId]
            } else {
                providerNames[key]
            } ?: source.sourcePlaylistId
            UnionSourceCandidate(key, name, source.sourceType)
        }
        val known = providers.map { UnionSourceCandidate(sourceKey(it.source, it.id), it.name, it.source) } +
            customs.filter { it.playlistType != PlaylistType.UNION && it.id != playlistId }
                .map { UnionSourceCandidate(sourceKey(SourceType.CUSTOM, it.id), it.name, SourceType.CUSTOM) }
        val knownKeys = known.mapTo(HashSet(known.size)) { it.key }
        return UnionUi(
            labels = selected.map { "${it.name} (${it.source.name.lowercase()})" },
            selectedKeys = selected.map { it.key },
            // Sources whose playlist is no longer listed stay visible so they can be unchecked.
            candidates = known + selected.filter { it.key !in knownKeys },
        )
    }

    companion object {
        const val ARG_ID = "id"

        fun sourceKey(source: SourceType, id: String): String = "${source.name}:${normalizePlaylistId(source, id)}"
    }
}
