package com.anyplayer.android.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2To3_keepsAllLegacyDataAndAddsTables() {
        helper.createDatabase(DB, 1).use { db ->
            insertPlaylist(db, "p1", "Std", "STANDARD", withDistinct = false)
            insertPlaylist(db, "u1", "Union", "UNION", withDistinct = false)
            insertTrack(db, "t1", "p1", 0)
            insertUnionSource(db, "s1", "u1", 0)
            db.execSQL("INSERT INTO app_cache_entries(`key`, valueJson, version, updatedAt) VALUES('k', '{}', 1, 'now')")
            db.execSQL("INSERT INTO column_preferences(`key`, visible, position) VALUES('title', 1, 0)")
        }

        helper.runMigrationsAndValidate(DB, 2, true, AppDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT isDistinct FROM custom_playlists WHERE id = 'p1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            db.execSQL("INSERT INTO provider_playlist_preferences(source, playlistId, isDistinct) VALUES('SPOTIFY', 'x', 1)")
        }

        helper.runMigrationsAndValidate(DB, 3, true, AppDatabase.MIGRATION_2_3).use { db ->
            assertV3Content(db)
        }
    }

    @Test
    fun migrate2To3_keepsAllLegacyDataAndAddsTables() {
        helper.createDatabase(DB, 2).use { db ->
            insertPlaylist(db, "p1", "Std", "STANDARD", withDistinct = true)
            insertPlaylist(db, "u1", "Union", "UNION", withDistinct = true)
            insertTrack(db, "t1", "p1", 0)
            insertUnionSource(db, "s1", "u1", 0)
            db.execSQL("INSERT INTO provider_playlist_preferences(source, playlistId, isDistinct) VALUES('SPOTIFY', 'x', 1)")
            db.execSQL("INSERT INTO app_cache_entries(`key`, valueJson, version, updatedAt) VALUES('k', '{}', 1, 'now')")
            db.execSQL("INSERT INTO column_preferences(`key`, visible, position) VALUES('title', 1, 0)")
        }

        helper.runMigrationsAndValidate(DB, 3, true, AppDatabase.MIGRATION_2_3).use { db ->
            assertV3Content(db)
        }
    }

    @Test
    fun migratedDatabase_opensWithRoomBuilder() {
        helper.createDatabase(DB, 2).use { db ->
            insertPlaylist(db, "p1", "Std", "STANDARD", withDistinct = true)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = AppDatabase.create(context, DB)
        try {
            val all = kotlinx.coroutines.runBlocking { room.customPlaylistDao().getAll() }
            assertEquals(listOf("p1"), all.map { it.id })
        } finally {
            room.close()
        }
    }

    private fun assertV3Content(db: SupportSQLiteDatabase) {
        db.query("SELECT id, playlistType FROM custom_playlists ORDER BY id").use { c ->
            assertTrue(c.moveToNext()); assertEquals("p1", c.getString(0)); assertEquals("STANDARD", c.getString(1))
            assertTrue(c.moveToNext()); assertEquals("u1", c.getString(0)); assertEquals("UNION", c.getString(1))
            assertFalse(c.moveToNext())
        }
        db.query("SELECT COUNT(*) FROM playlist_tracks").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM union_playlist_sources").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM provider_playlist_preferences").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        // Legacy cache tables are kept (rows migrated later by a background importer).
        db.query("SELECT valueJson FROM app_cache_entries WHERE `key` = 'k'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("{}", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM column_preferences").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        listOf("queue_entries", "queue_meta", "cached_playlists", "cached_tracks", "cached_track_owners")
            .forEach { assertTrue(it, tableExists(db, it)) }
    }

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    private fun insertPlaylist(db: SupportSQLiteDatabase, id: String, name: String, type: String, withDistinct: Boolean) {
        val values = ContentValues().apply {
            put("id", id)
            put("name", name)
            put("createdAt", "2026-01-01T00:00:00Z")
            put("updatedAt", "2026-01-01T00:00:00Z")
            put("trackCount", 0)
            put("playlistType", type)
            if (withDistinct) put("isDistinct", 0)
        }
        db.insert("custom_playlists", SQLiteDatabase.CONFLICT_ABORT, values)
    }

    private fun insertTrack(db: SupportSQLiteDatabase, id: String, playlistId: String, position: Int) {
        val values = ContentValues().apply {
            put("id", id)
            put("playlistId", playlistId)
            put("trackSource", "JELLYFIN")
            put("trackId", "jf-$id")
            put("position", position)
            put("addedAt", "2026-01-01T00:00:00Z")
            put("title", "Title")
            put("artist", "Artist")
        }
        db.insert("playlist_tracks", SQLiteDatabase.CONFLICT_ABORT, values)
    }

    private fun insertUnionSource(db: SupportSQLiteDatabase, id: String, unionId: String, position: Int) {
        val values = ContentValues().apply {
            put("id", id)
            put("unionPlaylistId", unionId)
            put("sourceType", "SPOTIFY")
            put("sourcePlaylistId", "sp-1")
            put("position", position)
            put("addedAt", "2026-01-01T00:00:00Z")
        }
        db.insert("union_playlist_sources", SQLiteDatabase.CONFLICT_ABORT, values)
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
