package com.anyplayer.android.core.storage.mapper

import com.anyplayer.android.core.model.ColumnPreferences
import com.anyplayer.android.core.model.CustomPlaylist
import com.anyplayer.android.core.model.PlaylistTrack
import com.anyplayer.android.core.model.UnionPlaylistSource
import com.anyplayer.android.core.storage.entity.ColumnPreferenceEntity
import com.anyplayer.android.core.storage.entity.CustomPlaylistEntity
import com.anyplayer.android.core.storage.entity.PlaylistTrackEntity
import com.anyplayer.android.core.storage.entity.UnionPlaylistSourceEntity

fun CustomPlaylistEntity.toModel(): CustomPlaylist =
    CustomPlaylist(
        id = id,
        name = name,
        description = description,
        imageUrl = imageUrl,
        createdAt = createdAt,
        updatedAt = updatedAt,
        trackCount = trackCount,
        playlistType = playlistType
    )

fun CustomPlaylist.toEntity(): CustomPlaylistEntity =
    CustomPlaylistEntity(
        id = id,
        name = name,
        description = description,
        imageUrl = imageUrl,
        createdAt = createdAt,
        updatedAt = updatedAt,
        trackCount = trackCount,
        playlistType = playlistType
    )

fun PlaylistTrackEntity.toModel(): PlaylistTrack =
    PlaylistTrack(
        id = id,
        playlistId = playlistId,
        trackSource = trackSource,
        trackId = trackId,
        position = position,
        addedAt = addedAt,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        imageUrl = imageUrl,
        url = url
    )

fun PlaylistTrack.toEntity(): PlaylistTrackEntity =
    PlaylistTrackEntity(
        id = id,
        playlistId = playlistId,
        trackSource = trackSource,
        trackId = trackId,
        position = position,
        addedAt = addedAt,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        imageUrl = imageUrl,
        url = url
    )

fun UnionPlaylistSourceEntity.toModel(): UnionPlaylistSource =
    UnionPlaylistSource(
        id = id,
        unionPlaylistId = unionPlaylistId,
        sourceType = sourceType,
        sourcePlaylistId = sourcePlaylistId,
        position = position,
        addedAt = addedAt
    )

fun UnionPlaylistSource.toEntity(): UnionPlaylistSourceEntity =
    UnionPlaylistSourceEntity(
        id = id,
        unionPlaylistId = unionPlaylistId,
        sourceType = sourceType,
        sourcePlaylistId = sourcePlaylistId,
        position = position,
        addedAt = addedAt
    )

fun ColumnPreferenceEntity.toModel(): ColumnPreferences =
    ColumnPreferences(
        key = key,
        visible = visible,
        position = position
    )

fun ColumnPreferences.toEntity(): ColumnPreferenceEntity =
    ColumnPreferenceEntity(
        key = key,
        visible = visible,
        position = position
    )
