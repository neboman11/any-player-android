package com.anyplayer.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.anyplayer.android.data.db.dao.CustomPlaylistDao
import com.anyplayer.android.data.db.dao.CatalogCacheDao
import com.anyplayer.android.data.db.entity.AppCacheEntryEntity
import com.anyplayer.android.data.db.entity.CachedPlaylistEntity
import com.anyplayer.android.data.db.entity.CachedTrackEntity
import com.anyplayer.android.data.db.entity.CachedTrackOwnerEntity
import com.anyplayer.android.data.db.entity.ColumnPreferenceEntity
import com.anyplayer.android.data.db.entity.CustomPlaylistEntity
import com.anyplayer.android.data.db.entity.PlaylistTrackEntity
import com.anyplayer.android.data.db.entity.ProviderPlaylistPreferenceEntity
import com.anyplayer.android.data.db.entity.QueueEntryEntity
import com.anyplayer.android.data.db.entity.QueueMetaEntity
import com.anyplayer.android.data.db.entity.UnionPlaylistSourceEntity

@Database(
    entities = [
        CustomPlaylistEntity::class,
        PlaylistTrackEntity::class,
        UnionPlaylistSourceEntity::class,
        ColumnPreferenceEntity::class,
        AppCacheEntryEntity::class,
        ProviderPlaylistPreferenceEntity::class,
        QueueEntryEntity::class,
        QueueMetaEntity::class,
        CachedPlaylistEntity::class,
        CachedTrackEntity::class,
        CachedTrackOwnerEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(RoomTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customPlaylistDao(): CustomPlaylistDao
    abstract fun unionCacheDao(): com.anyplayer.android.data.db.dao.UnionCacheDao
    abstract fun catalogCacheDao(): CatalogCacheDao
    abstract fun queueDao(): com.anyplayer.android.data.db.dao.QueueDao

    companion object {
        /** File name shared with the legacy app; existing installs upgrade in place. */
        const val NAME = "any_player.db"

        fun create(context: Context, name: String = NAME): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /**
         * v1 → v2 (legacy, verbatim):
         *  1. Adds `isDistinct INTEGER NOT NULL DEFAULT 0` to `custom_playlists`.
         *  2. Creates the `provider_playlist_preferences` table keyed by (source, playlistId).
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

        /**
         * v2 → v3: only adds tables (persisted queue + normalized provider caches). Legacy
         * tables, including `app_cache_entries` and `column_preferences`, are untouched.
         * Statements are the `createSql` Room exports in schemas/…/3.json.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `queue_entries` (`uid` INTEGER NOT NULL, `seq` INTEGER NOT NULL, " +
                        "`trackJson` TEXT NOT NULL, PRIMARY KEY(`uid`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `queue_meta` (`id` INTEGER NOT NULL, `orderUids` TEXT NOT NULL, " +
                        "`cursorUid` INTEGER, `contentVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cached_playlists` (`source` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `owner` TEXT NOT NULL, `trackCount` INTEGER NOT NULL, " +
                        "`imageUrl` TEXT, `description` TEXT, `position` INTEGER NOT NULL, " +
                        "`updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`source`, `id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cached_tracks` (`ownerKey` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                        "`trackJson` TEXT NOT NULL, PRIMARY KEY(`ownerKey`, `position`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cached_track_owners` (`ownerKey` TEXT NOT NULL, " +
                        "`updatedAtMs` INTEGER NOT NULL, `trackCount` INTEGER NOT NULL, PRIMARY KEY(`ownerKey`))"
                )
            }
        }
    }
}
