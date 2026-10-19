package com.anyplayer.android.data.playlists

import com.anyplayer.android.core.model.SourceType

/** Incremental union edits not represented by the shared replace-only repository contract. */
interface PlaylistSourceEditor {
    suspend fun addUnionSource(unionId: String, source: SourceType, sourcePlaylistId: String)
    suspend fun removeUnionSource(unionId: String, sourceId: String)
    suspend fun reorderUnionSources(unionId: String, orderedSourceIds: List<String>)
}
