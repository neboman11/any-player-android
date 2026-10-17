package com.anyplayer.android.ui.playlists

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.data.catalog.CatalogRepository
import com.anyplayer.android.data.catalog.ProviderRefreshFailure
import com.anyplayer.android.data.playlists.PlaylistRepository
import com.anyplayer.android.playback.PlaybackEngine
import com.anyplayer.android.ui.components.ComputeDispatcher
import com.anyplayer.android.ui.components.sourceLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ProviderPlaylistItem(
    val source: SourceType,
    val id: String,
    val name: String,
    val trackCount: Int,
    val imageUrl: String?,
) {
    val key: String get() = "${source.name}_$id"
}

@Immutable
data class CustomPlaylistItem(val id: String, val name: String, val type: PlaylistType, val trackCount: Int)

@Immutable
data class RefreshUi(val inProgress: Boolean = false, val status: String? = null)

@Immutable
data class PlaylistsUi(
    val providerPlaylists: List<ProviderPlaylistItem> = emptyList(),
    val customPlaylists: List<CustomPlaylistItem> = emptyList(),
    val refresh: RefreshUi = RefreshUi(),
    val loaded: Boolean = false,
)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val playlists: PlaylistRepository,
    private val engine: PlaybackEngine,
    @ComputeDispatcher private val computeDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val refresh = MutableStateFlow(RefreshUi())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val providerItems = catalog.playlists
        .map { list -> list.map { ProviderPlaylistItem(it.source, it.id, it.name, it.trackCount, it.imageUrl) } }
        .flowOn(computeDispatcher)
        .onStart { emit(emptyList()) }

    private val customItems = playlists.customPlaylists
        .map { list -> list.map { CustomPlaylistItem(it.id, it.name, it.playlistType, it.trackCount) } }
        .flowOn(computeDispatcher)

    val state: StateFlow<PlaylistsUi> = combine(providerItems, customItems, refresh) { provider, custom, r ->
        PlaylistsUi(provider, custom, r, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistsUi())

    /** Full refresh of provider lists and tracks with legacy progress strings. */
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
        }
    }

    /** Blank names are allowed; the repository picks a default name. */
    fun create(name: String, type: PlaylistType) {
        viewModelScope.launch {
            runCatchingMessage("Could not create playlist") { playlists.create(name.trim(), type) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatchingMessage("Could not delete playlist") { playlists.delete(id) }
        }
    }

    fun playCustom(id: String) {
        viewModelScope.launch {
            runCatchingMessage("Could not play playlist") {
                if (!playCustomPlaylist(playlists, engine, id)) _messages.send(NOTHING_TO_PLAY)
            }
        }
    }

    fun playProvider(item: ProviderPlaylistItem) {
        viewModelScope.launch {
            runCatchingMessage("Could not play playlist") {
                if (!playProviderPlaylist(catalog, engine, computeDispatcher, item.source, item.id)) {
                    _messages.send(NOTHING_TO_PLAY)
                }
            }
        }
    }

    private suspend inline fun runCatchingMessage(fallback: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.send(e.message ?: fallback)
        }
    }
}

internal fun refreshResultText(failures: List<ProviderRefreshFailure>): String =
    if (failures.isEmpty()) {
        "Playlist data refreshed successfully!"
    } else {
        "Refresh finished with errors: " + failures.joinToString(" | ") { "${sourceLabel(it.source)}: ${it.message}" }
    }
