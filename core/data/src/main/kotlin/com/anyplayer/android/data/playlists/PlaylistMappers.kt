package com.anyplayer.android.data.playlists

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.db.entity.*

internal fun CustomPlaylistEntity.toModel() = CustomPlaylist(id, name, description, imageUrl, createdAt, updatedAt, trackCount, playlistType, isDistinct)
internal fun CustomPlaylist.toEntity() = CustomPlaylistEntity(id, name, description, imageUrl, createdAt, updatedAt, trackCount, playlistType, isDistinct)
internal fun PlaylistTrackEntity.toModel() = PlaylistTrack(id, playlistId, trackSource, trackId, position, addedAt, title, artist, album, durationMs, imageUrl, url)
internal fun PlaylistTrack.toEntity() = PlaylistTrackEntity(id, playlistId, trackSource, trackId, position, addedAt, title, artist, album, durationMs, imageUrl, url)
internal fun PlaylistTrackEntity.toTrack() = Track(trackId, title, artist, album, durationMs, trackSource, url, imageUrl, enriched = true)
internal fun UnionPlaylistSourceEntity.toModel() = UnionPlaylistSource(id, unionPlaylistId, sourceType, sourcePlaylistId, position, addedAt)
internal fun UnionPlaylistSource.toEntity() = UnionPlaylistSourceEntity(id, unionPlaylistId, sourceType, sourcePlaylistId, position, addedAt)
