package com.anyplayer.android.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "column_preferences")
data class ColumnPreferenceEntity(
    @PrimaryKey val key: String,
    val visible: Boolean,
    val position: Int
)
