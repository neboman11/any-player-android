package com.anyplayer.android.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.SourceType

// These six entities must stay schema-identical to legacy v2 (see schemas/…/2.json).

@Entity(
    tableName = "custom_playlists",
    indices = [Index("name"), Index("playlistType")],
)
data class CustomPlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val trackCount: Int,
    val playlistType: PlaylistType,
    val isDistinct: Boolean = false,
)

@Entity(
    tableName = "playlist_tracks",
    foreignKeys = [
        ForeignKey(
            entity = CustomPlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index(value = ["playlistId", "position"], unique = true)],
)
data class PlaylistTrackEntity(
    @PrimaryKey val id: String,
    val playlistId: String,
    val trackSource: SourceType,
    val trackId: String,
    val position: Int,
    val addedAt: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val imageUrl: String? = null,
    val url: String? = null,
)

@Entity(
    tableName = "union_playlist_sources",
    foreignKeys = [
        ForeignKey(
            entity = CustomPlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["unionPlaylistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("unionPlaylistId"), Index(value = ["unionPlaylistId", "position"], unique = true)],
)
data class UnionPlaylistSourceEntity(
    @PrimaryKey val id: String,
    val unionPlaylistId: String,
    val sourceType: SourceType,
    val sourcePlaylistId: String,
    val position: Int,
    val addedAt: String,
)

/** App-owned flags for a provider playlist; `source` is `SourceType.name`. */
@Entity(
    tableName = "provider_playlist_preferences",
    primaryKeys = ["source", "playlistId"],
)
data class ProviderPlaylistPreferenceEntity(
    val source: String,
    val playlistId: String,
    val isDistinct: Boolean,
)

/** Legacy blob cache; kept so a background importer can migrate its rows. */
@Entity(tableName = "app_cache_entries")
data class AppCacheEntryEntity(
    @PrimaryKey val key: String,
    val valueJson: String,
    val version: Int,
    val updatedAt: String,
)

/** Legacy column preferences; no UI in v2, kept for export compatibility. */
@Entity(tableName = "column_preferences")
data class ColumnPreferenceEntity(
    @PrimaryKey val key: String,
    val visible: Boolean,
    val position: Int,
)
