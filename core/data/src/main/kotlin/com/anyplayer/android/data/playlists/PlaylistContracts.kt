package com.anyplayer.android.data.playlists

import com.anyplayer.android.core.model.CustomPlaylist
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.UnionPlaylistSource
import kotlinx.coroutines.flow.Flow

/** Tracks to hand to the playback engine (`setQueue(tracks, startIndex)`). */
data class PlaybackSelection(val tracks: List<Track>, val startIndex: Int)

/**
 * Custom STANDARD/UNION playlists (legacy CustomPlaylistEngine semantics, without any playback
 * dependency). Every mutation is one transaction, serialized per playlist.
 */
interface PlaylistRepository {
    /** Ordered by updatedAt DESC. */
    val customPlaylists: Flow<List<CustomPlaylist>>

    fun observePlaylist(id: String): Flow<CustomPlaylist?>
    /** STANDARD: stored tracks. UNION: cached materialization (empty until materialized). */
    fun observeTracks(id: String): Flow<List<Track>>
    fun observeUnionSources(id: String): Flow<List<UnionPlaylistSource>>

    suspend fun create(name: String, type: PlaylistType): CustomPlaylist
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    suspend fun setDistinct(id: String, distinct: Boolean)

    suspend fun addTrack(playlistId: String, track: Track)
    suspend fun removeTrackAt(playlistId: String, index: Int)
    suspend fun reorderTracks(playlistId: String, orderedTrackIds: List<String>)

    suspend fun replaceUnionSources(unionId: String, sources: List<Pair<SourceType, String>>)
    /** Materializes a UNION playlist (parallel source fetch, order kept); updates cache + trackCount. */
    suspend fun materializeUnion(unionId: String, forceRefresh: Boolean = false, onProgress: (String) -> Unit = {}): List<Track>

    /** Tracks for "Play" (distinct applied when enabled); UNION materialized once. */
    suspend fun playbackSelection(id: String, fromIndex: Int? = null): PlaybackSelection
}
