package com.anyplayer.android.app

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import com.anyplayer.android.feature.providers.ProviderCatalogRepository
import com.anyplayer.android.feature.search.SearchType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Owns MainViewModel's track/playlist search results and search-driven playback actions. */
internal class SearchStateHolder(
    private val viewModelScope: CoroutineScope,
    private val providerCatalogRepository: ProviderCatalogRepository,
    private val playbackQueueManager: PlaybackQueueManager,
    private val playPlaylist: (SourceType, String) -> Unit
) {
    val searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchPlaylistResults = MutableStateFlow<List<Playlist>>(emptyList())

    fun playFromSearch(index: Int) {
        val tracks = searchResults.value
        if (tracks.isEmpty()) return
        playbackQueueManager.setQueue(tracks, startIndex = index, autoPlay = true)
    }

    fun search(query: String, sourceType: SourceType, searchType: SearchType) {
        viewModelScope.launch {
            val result = providerCatalogRepository.search(query = query, source = sourceType)
            when (searchType) {
                SearchType.TRACKS -> {
                    searchResults.value = result.tracks
                    searchPlaylistResults.value = emptyList()
                }

                SearchType.PLAYLISTS -> {
                    searchPlaylistResults.value = result.playlists
                    searchResults.value = emptyList()
                }
            }
        }
    }

    fun playPlaylistFromSearch(index: Int) {
        val playlist = searchPlaylistResults.value.getOrNull(index) ?: return
        playPlaylist(playlist.source, playlist.id)
    }
}
