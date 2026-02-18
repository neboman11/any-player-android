package com.anyplayer.android.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.anyplayer.android.core.model.PlaylistType

@Entity(
    tableName = "custom_playlists",
    indices = [Index("name"), Index("playlistType")]
)
data class CustomPlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val trackCount: Int,
    val playlistType: PlaylistType
)
