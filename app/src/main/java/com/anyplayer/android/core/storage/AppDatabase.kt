package com.anyplayer.android.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.anyplayer.android.core.storage.dao.AppCacheEntryDao
import com.anyplayer.android.core.storage.dao.ColumnPreferenceDao
import com.anyplayer.android.core.storage.dao.CustomPlaylistDao
import com.anyplayer.android.core.storage.dao.PlaylistTrackDao
import com.anyplayer.android.core.storage.dao.UnionPlaylistSourceDao
import com.anyplayer.android.core.storage.entity.AppCacheEntryEntity
import com.anyplayer.android.core.storage.entity.ColumnPreferenceEntity
import com.anyplayer.android.core.storage.entity.CustomPlaylistEntity
import com.anyplayer.android.core.storage.entity.PlaylistTrackEntity
import com.anyplayer.android.core.storage.entity.UnionPlaylistSourceEntity

@Database(
    entities = [
        CustomPlaylistEntity::class,
        PlaylistTrackEntity::class,
        UnionPlaylistSourceEntity::class,
        ColumnPreferenceEntity::class,
        AppCacheEntryEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(RoomTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customPlaylistDao(): CustomPlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
    abstract fun unionPlaylistSourceDao(): UnionPlaylistSourceDao
    abstract fun columnPreferenceDao(): ColumnPreferenceDao
    abstract fun appCacheEntryDao(): AppCacheEntryDao
}
