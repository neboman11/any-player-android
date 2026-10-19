package com.anyplayer.android.ui

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.catalog.*
import com.anyplayer.android.data.playlists.*
import com.anyplayer.android.playback.*
import com.anyplayer.android.ui.playlists.ProviderPlayback
import com.anyplayer.android.ui.settings.FakeEngine
import kotlinx.coroutines.flow.*

internal fun track(id: String = "song", title: String = id, artist: String = "Artist") =
    Track(id, title, artist, source = SourceType.PLEX, durationMs = 60_000)

internal class RecordingEngine : PlaybackEngine by FakeEngine() {
    override val queue = MutableStateFlow(QueueState())
    override val player = MutableStateFlow(PlayerState())
    var selection: PlaybackSelection? = null
    var playedUid: Long? = null
    val queued = mutableListOf<Track>()
    val seeks = mutableListOf<Long>()
    val volumes = mutableListOf<Int>()
    override fun setQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean, origin: ChangeOrigin) {
        selection = PlaybackSelection(tracks, startIndex)
    }
    override fun playEntry(uid: Long) { playedUid = uid }
    override fun playNext(track: Track) { queued += track }
    override fun seekTo(ms: Long, origin: ChangeOrigin) { seeks += ms }
    override fun setVolume(percent: Int, origin: ChangeOrigin) { volumes += percent }
}

internal class UiCatalog : CatalogRepository {
    override val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    var tracks = emptyList<Track>()
    var distinct = false
    var failure: String? = null
    var search: suspend (String, SourceType, SearchType) -> SearchResult = { _, _, _ -> SearchResult() }
    override suspend fun refreshPlaylists() = emptyList<ProviderRefreshFailure>()
    override suspend fun tracks(source: SourceType, playlistId: String, forceRefresh: Boolean): List<Track> {
        failure?.let { error(it) }
        return tracks
    }
    override suspend fun cachedTracks(source: SourceType, playlistId: String) = tracks
    override suspend fun refreshAll(onProgress: (String) -> Unit): List<ProviderRefreshFailure> {
        onProgress("Fetching playlists from providers...")
        return emptyList()
    }
    override suspend fun search(query: String, source: SourceType, type: SearchType) = search.invoke(query, source, type)
    override suspend fun clearCache() = Unit
    override suspend fun isDistinct(source: SourceType, playlistId: String) = distinct
    override suspend fun setDistinct(source: SourceType, playlistId: String, distinct: Boolean) { this.distinct = distinct }
}

internal class UiPlaylists : PlaylistRepository {
    override val customPlaylists = MutableStateFlow<List<CustomPlaylist>>(emptyList())
    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val sources = MutableStateFlow<List<UnionPlaylistSource>>(emptyList())
    val additions = mutableListOf<Pair<String, Track>>()
    val removals = mutableListOf<Int>()
    var replacementSources: List<Pair<SourceType, String>>? = null
    override fun observePlaylist(id: String) = customPlaylists.map { list -> list.firstOrNull { it.id == id } }
    override fun observeTracks(id: String) = tracks
    override fun observeUnionSources(id: String) = sources
    override suspend fun create(name: String, type: PlaylistType): CustomPlaylist {
        val item = CustomPlaylist("p${customPlaylists.value.size}", name.ifBlank { "Untitled Playlist" },
            createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z", trackCount = 0, playlistType = type)
        customPlaylists.value += item
        return item
    }
    override suspend fun rename(id: String, name: String) { customPlaylists.value = customPlaylists.value.map { if (it.id == id) it.copy(name = name) else it } }
    override suspend fun delete(id: String) { customPlaylists.value = customPlaylists.value.filterNot { it.id == id } }
    override suspend fun setDistinct(id: String, distinct: Boolean) { customPlaylists.value = customPlaylists.value.map { if (it.id == id) it.copy(isDistinct = distinct) else it } }
    override suspend fun addTrack(playlistId: String, track: Track) { additions += playlistId to track }
    override suspend fun removeTrackAt(playlistId: String, index: Int) { removals += index }
    override suspend fun reorderTracks(playlistId: String, orderedTrackIds: List<String>) = Unit
    override suspend fun replaceUnionSources(unionId: String, sources: List<Pair<SourceType, String>>) { replacementSources = sources }
    override suspend fun materializeUnion(unionId: String, forceRefresh: Boolean, onProgress: (String) -> Unit): List<Track> {
        onProgress("Finalizing...")
        return tracks.value
    }
    override suspend fun playbackSelection(id: String, fromIndex: Int?): PlaybackSelection =
        ProviderPlayback.selection(tracks.value, customPlaylists.value.first { it.id == id }.isDistinct, fromIndex)
}
