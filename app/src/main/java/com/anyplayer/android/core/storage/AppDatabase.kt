package com.anyplayer.android.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.anyplayer.android.core.storage.dao.AppCacheEntryDao
import com.anyplayer.android.core.storage.dao.ColumnPreferenceDao
import com.anyplayer.android.core.storage.dao.CustomPlaylistDao
import com.anyplayer.android.core.storage.dao.PlaylistTrackDao
import com.anyplayer.android.core.storage.dao.ProviderPlaylistPreferenceDao
import com.anyplayer.android.core.storage.dao.UnionPlaylistSourceDao
import com.anyplayer.android.core.storage.entity.AppCacheEntryEntity
import com.anyplayer.android.core.storage.entity.ColumnPreferenceEntity
import com.anyplayer.android.core.storage.entity.CustomPlaylistEntity
import com.anyplayer.android.core.storage.entity.PlaylistTrackEntity
import com.anyplayer.android.core.storage.entity.ProviderPlaylistPreferenceEntity
import com.anyplayer.android.core.storage.entity.UnionPlaylistSourceEntity

@Database(
    entities = [
        CustomPlaylistEntity::class,
        PlaylistTrackEntity::class,
        UnionPlaylistSourceEntity::class,
        ColumnPreferenceEntity::class,
        AppCacheEntryEntity::class,
        ProviderPlaylistPreferenceEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(RoomTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customPlaylistDao(): CustomPlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
    abstract fun unionPlaylistSourceDao(): UnionPlaylistSourceDao
    abstract fun columnPreferenceDao(): ColumnPreferenceDao
    abstract fun appCacheEntryDao(): AppCacheEntryDao
    abstract fun providerPlaylistPreferenceDao(): ProviderPlaylistPreferenceDao

    companion object {
        /**
         * Migrates v1 → v2:
         *  1. Adds `isDistinct INTEGER NOT NULL DEFAULT 0` to `custom_playlists`.
         *  2. Creates the `provider_playlist_preferences` table keyed by (source, playlistId).
         *
         * Existing rows in `custom_playlists` will have isDistinct = 0 (false), which is the
         * correct safe default — deduplication is off until the user explicitly enables it.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `custom_playlists` ADD COLUMN `isDistinct` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `provider_playlist_preferences` (
                        `source` TEXT NOT NULL,
                        `playlistId` TEXT NOT NULL,
                        `isDistinct` INTEGER NOT NULL,
                        PRIMARY KEY(`source`, `playlistId`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
