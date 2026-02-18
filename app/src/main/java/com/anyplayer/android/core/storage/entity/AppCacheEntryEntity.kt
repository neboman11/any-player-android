package com.anyplayer.android.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_cache_entries")
data class AppCacheEntryEntity(
    @PrimaryKey val key: String,
    val valueJson: String,
    val version: Int,
    val updatedAt: String
)
