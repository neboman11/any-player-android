package com.anyplayer.android.app

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.CustomPlaylist
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.UnionPlaylistSource
import com.anyplayer.android.core.model.normalizePlaylistId
import com.anyplayer.android.core.storage.repository.PlaylistStorageRepository
import com.anyplayer.android.feature.playlists.CustomPlaylistEngine
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Thin adapter over [CustomPlaylistEngine] owning MainViewModel's custom-playlist UI state. */
internal class CustomPlaylistStateHolder(
    private val viewModelScope: CoroutineScope,
    private val customPlaylistEngine: CustomPlaylistEngine,
    private val playlistStorageRepository: PlaylistStorageRepository,
    private val searchResults: StateFlow<List<Track>>,
    private val ensureProviderPlaylistMetadataForUnionSources: suspend (List<UnionPlaylistSource>) -> Unit
) {
    companion object {
        private const val TAG = "CustomPlaylistStateHolder"
    }

    val customPlaylists = MutableStateFlow<List<CustomPlaylist>>(emptyList())
    val activeCustomPlaylistTracks = MutableStateFlow<List<Track>>(emptyList())
    val selectedCustomPlaylistId = MutableStateFlow<String?>(null)
    val selectedCustomUnionSources = MutableStateFlow<List<UnionPlaylistSource>>(emptyList())
    val customPlaylistRefreshInProgress = MutableStateFlow(false)
    val customPlaylistRefreshStatus = MutableStateFlow<String?>(null)

    private suspend fun refreshedUnionSources(playlistId: String): List<UnionPlaylistSource> =
        customPlaylistEngine.getUnionSources(playlistId).also { unionSources ->
            ensureProviderPlaylistMetadataForUnionSources(unionSources)
        }

    // customPlaylists starts empty and is only populated once the Room-backed flow below
    // emits, so callers that need an accurate count (e.g. the sync overwrite-confirmation
    // guard) must await this rather than reading customPlaylists.value directly - otherwise
    // a fast-path caller can race the first emission and see a false "zero playlists".
    private val customPlaylistsLoaded = CompletableDeferred<Unit>()

    fun observeCustomPlaylists() {
        viewModelScope.launch {
            customPlaylistEngine.observeCustomPlaylists().collect { playlists ->
                customPlaylists.value = playlists.sortedByDescending { it.updatedAt }
                customPlaylistsLoaded.complete(Unit)
            }
        }
    }

    suspend fun awaitCustomPlaylistsLoaded() = customPlaylistsLoaded.await()

    private suspend fun refreshActiveCustomPlaylistTracks(playlistId: String) {
        activeCustomPlaylistTracks.value = customPlaylistEngine.getTracksForPlaylist(playlistId)
    }

    fun createStandardPlaylist(name: String) {
        viewModelScope.launch {
            customPlaylistEngine.createPlaylist(name, PlaylistType.STANDARD)
        }
    }

    fun createUnionPlaylist(name: String) {
        viewModelScope.launch {
            customPlaylistEngine.createPlaylist(name, PlaylistType.UNION)
        }
    }

    fun deleteCustomPlaylist(playlistId: String) {
        viewModelScope.launch {
            customPlaylistEngine.deletePlaylist(playlistId)
            if (selectedCustomPlaylistId.value == playlistId) {
                selectedCustomPlaylistId.value = null
                activeCustomPlaylistTracks.value = emptyList()
                selectedCustomUnionSources.value = emptyList()
            }
        }
    }

    fun renameCustomPlaylist(playlistId: String, name: String) {
        viewModelScope.launch {
            customPlaylistEngine.renamePlaylist(playlistId, name)
        }
    }

    fun selectCustomPlaylist(playlistId: String) {
        selectedCustomPlaylistId.value = playlistId
        viewModelScope.launch {
            val selectedPlaylist = customPlaylists.value.firstOrNull { it.id == playlistId }
            activeCustomPlaylistTracks.value = customPlaylistEngine.getCachedTracksForPlaylist(playlistId)
            selectedCustomUnionSources.value = if (selectedPlaylist?.playlistType == PlaylistType.UNION) {
                refreshedUnionSources(playlistId)
            } else {
                emptyList()
            }
            refreshActiveCustomPlaylistTracks(playlistId)
        }
    }

    fun closeCustomPlaylistDetails() {
        selectedCustomPlaylistId.value = null
        activeCustomPlaylistTracks.value = emptyList()
        selectedCustomUnionSources.value = emptyList()
    }

    fun playCustomPlaylist(playlistId: String) {
        viewModelScope.launch {
            val selectedPlaylist = customPlaylists.value.firstOrNull { it.id == playlistId }
            customPlaylistEngine.playPlaylist(playlistId)
            refreshActiveCustomPlaylistTracks(playlistId)
            selectedCustomPlaylistId.value = playlistId
            selectedCustomUnionSources.value = if (selectedPlaylist?.playlistType == PlaylistType.UNION) {
                customPlaylistEngine.getUnionSources(playlistId)
            } else {
                emptyList()
            }
        }
    }

    fun playFromCustomPlaylistTrack(playlistId: String, index: Int) {
        viewModelScope.launch {
            customPlaylistEngine.playFromTrack(playlistId, index)
        }
    }

    fun addSearchTrackToSelectedCustom(trackIndex: Int) {
        val playlistId = selectedCustomPlaylistId.value ?: return
        val track = searchResults.value.getOrNull(trackIndex) ?: return
        viewModelScope.launch {
            customPlaylistEngine.addTrack(playlistId, track)
            refreshActiveCustomPlaylistTracks(playlistId)
        }
    }

    fun removeTrackFromSelectedCustom(playlistTrackIndex: Int) {
        val playlistId = selectedCustomPlaylistId.value ?: return
        viewModelScope.launch {
            customPlaylistEngine.removeTrackAt(playlistId, playlistTrackIndex)
            refreshActiveCustomPlaylistTracks(playlistId)
        }
    }

    fun setSelectedCustomPlaylistDistinct(isDistinct: Boolean) {
        val playlistId = selectedCustomPlaylistId.value ?: return
        val selectedPlaylist = customPlaylists.value.firstOrNull { it.id == playlistId } ?: return
        viewModelScope.launch {
            playlistStorageRepository.upsertCustomPlaylists(
                listOf(
                    selectedPlaylist.copy(
                        isDistinct = isDistinct,
                        updatedAt = Instant.now().toString()
                    )
                )
            )

            // Refresh local selected state immediately so UI toggles and playback actions stay in sync.
            playlistStorageRepository.getCustomPlaylistById(playlistId)?.let { refreshedPlaylist ->
                customPlaylists.value = customPlaylists.value.map { playlist ->
                    if (playlist.id == playlistId) refreshedPlaylist else playlist
                }
            }
        }
    }

    fun addProviderPlaylistSourceToSelectedUnion(sourcePlaylistId: String, sourceType: SourceType) {
        val playlistId = selectedCustomPlaylistId.value ?: return
        viewModelScope.launch {
            val normalizedSourcePlaylistId = normalizePlaylistId(sourceType, sourcePlaylistId)
            customPlaylistEngine.addUnionSource(
                unionPlaylistId = playlistId,
                sourceType = sourceType,
                sourcePlaylistId = normalizedSourcePlaylistId
            )
            selectedCustomUnionSources.value = refreshedUnionSources(playlistId)
            refreshActiveCustomPlaylistTracks(playlistId)
        }
    }

    fun reorderSelectedCustomTracks(orderedTrackIds: List<String>) {
        val playlistId = selectedCustomPlaylistId.value ?: return
        viewModelScope.launch {
            customPlaylistEngine.reorderTracks(playlistId, orderedTrackIds)
            refreshActiveCustomPlaylistTracks(playlistId)
        }
    }

    fun reorderSelectedUnionSources(orderedSourceIds: List<String>) {
        val playlistId = selectedCustomPlaylistId.value ?: return
        viewModelScope.launch {
            customPlaylistEngine.reorderUnionSources(playlistId, orderedSourceIds)
            selectedCustomUnionSources.value = refreshedUnionSources(playlistId)
            refreshActiveCustomPlaylistTracks(playlistId)
        }
    }

    fun replaceSelectedUnionSources(sources: List<UnionPlaylistSource>) {
        val playlistId = selectedCustomPlaylistId.value ?: return
        viewModelScope.launch {
            try {
                val normalizedSources = sources.mapIndexed { index, source ->
                    source.copy(
                        unionPlaylistId = playlistId,
                        sourcePlaylistId = normalizePlaylistId(source.sourceType, source.sourcePlaylistId),
                        position = index
                    )
                }
                customPlaylistEngine.replaceUnionSources(playlistId, normalizedSources)
                selectedCustomUnionSources.value = refreshedUnionSources(playlistId)
                refreshActiveCustomPlaylistTracks(playlistId)
                customPlaylistRefreshStatus.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CompatLog.e(TAG, "Failed to replace union sources: ${e.message}", e)
                customPlaylistRefreshStatus.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "Failed to update union sources."
            }
        }
    }

    fun materializeSelectedUnion() {
        val playlistId = selectedCustomPlaylistId.value ?: return
        viewModelScope.launch {
            customPlaylistRefreshInProgress.value = true
            customPlaylistRefreshStatus.value = "Materializing union playlist..."

            try {
                val tracks = customPlaylistEngine.materializeUnionTracks(
                    playlistId,
                    onProgressUpdate = { status ->
                        customPlaylistRefreshStatus.value = status
                    },
                    forceRefresh = true
                )
                activeCustomPlaylistTracks.value = tracks
                customPlaylistRefreshStatus.value = "Union playlist materialized successfully!"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                customPlaylistRefreshStatus.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "Failed to materialize union playlist."
            }

            customPlaylistRefreshInProgress.value = false
        }
    }
}
