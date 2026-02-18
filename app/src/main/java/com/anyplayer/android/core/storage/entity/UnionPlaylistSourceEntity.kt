package com.anyplayer.android.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.anyplayer.android.core.model.SourceType

@Entity(
    tableName = "union_playlist_sources",
    foreignKeys = [
        ForeignKey(
            entity = CustomPlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["unionPlaylistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("unionPlaylistId"), Index(value = ["unionPlaylistId", "position"], unique = true)]
)
data class UnionPlaylistSourceEntity(
    @PrimaryKey val id: String,
    val unionPlaylistId: String,
    val sourceType: SourceType,
    val sourcePlaylistId: String,
    val position: Int,
    val addedAt: String
)
