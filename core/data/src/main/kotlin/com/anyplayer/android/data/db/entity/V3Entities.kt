package com.anyplayer.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anyplayer.android.core.model.SourceType

/** Playback queue entries; `seq` is insertion order, `trackJson` a `Track` encoded with `AppJson`. */
@Entity(tableName = "queue_entries")
data class QueueEntryEntity(
    @PrimaryKey val uid: Long,
    val seq: Int,
    val trackJson: String,
)

/**
 * Single-row queue metadata (always `id = 0`): `orderUids` is the comma-separated uids in play
 * order, `cursorUid` the current entry.
 */
@Entity(tableName = "queue_meta")
data class QueueMetaEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val orderUids: String,
    val cursorUid: Long?,
    val contentVersion: Long,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

@Entity(tableName = "cached_playlists", primaryKeys = ["source", "id"])
data class CachedPlaylistEntity(
    val source: SourceType,
    val id: String,
    val name: String,
    val owner: String,
    val trackCount: Int,
    val imageUrl: String?,
    val description: String?,
    val position: Int,
    val updatedAtMs: Long,
)

/** `ownerKey` = `p:<source>:<playlistId>` (provider) or `u:<unionId>` (union materialization). */
@Entity(tableName = "cached_tracks", primaryKeys = ["ownerKey", "position"])
data class CachedTrackEntity(
    val ownerKey: String,
    val position: Int,
    val trackJson: String,
)

@Entity(tableName = "cached_track_owners")
data class CachedTrackOwnerEntity(
    @PrimaryKey val ownerKey: String,
    val updatedAtMs: Long,
    val trackCount: Int,
)
