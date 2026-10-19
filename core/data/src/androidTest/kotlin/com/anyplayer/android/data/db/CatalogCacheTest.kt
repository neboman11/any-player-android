package com.anyplayer.android.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.db.dao.providerOwnerKey
import com.anyplayer.android.data.db.entity.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.anyplayer.android.data.auth.ConnectionStore
import com.anyplayer.android.data.auth.SpotifyTokenProvider
import com.anyplayer.android.data.auth.StoredConnection
import com.anyplayer.android.data.catalog.RoomCatalogRepository
import com.anyplayer.android.data.providers.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(AndroidJUnit4::class)
class CatalogCacheTest {
    private fun database() = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
    @Test fun replacingProviderListRemovesOnlyDisappearedTrackOwners() = runBlocking {
        val db = database()
        try {
            val dao = db.catalogCacheDao()
            fun playlist(id: String) = CachedPlaylistEntity(SourceType.PLEX, id, id, "Plex", 1, null, null, 0, 100)
            dao.replacePlaylists(SourceType.PLEX, listOf(playlist("keep"), playlist("gone")))
            for (id in listOf("keep", "gone", "unlisted")) {
                val key = providerOwnerKey(SourceType.PLEX, id)
                dao.replaceTracks(CachedTrackOwnerEntity(key, 100, 1), listOf(CachedTrackEntity(key, 0, "{}")))
            }
            dao.replaceTracks(CachedTrackOwnerEntity("u:union", 100, 1), listOf(CachedTrackEntity("u:union", 0, "{}")))
            dao.replacePlaylists(SourceType.PLEX, listOf(playlist("keep")))
            assertEquals(listOf("keep"), dao.playlists().map { it.id })
            assertNull(dao.owner(providerOwnerKey(SourceType.PLEX, "gone")))
            assertNull(dao.owner(providerOwnerKey(SourceType.PLEX, "unlisted")))
            assertTrue(dao.tracks(providerOwnerKey(SourceType.PLEX, "gone")).isEmpty())
            assertEquals(1, dao.tracks(providerOwnerKey(SourceType.PLEX, "keep")).size)
            assertEquals(1, dao.tracks("u:union").size)
            dao.replaceTracks(CachedTrackOwnerEntity(providerOwnerKey(SourceType.PLEX, "keep"), 200, 0), emptyList())
            assertTrue(dao.tracks(providerOwnerKey(SourceType.PLEX, "keep")).isEmpty())
            assertEquals(0, dao.owner(providerOwnerKey(SourceType.PLEX, "keep"))!!.trackCount)
        } finally { db.close() }
    }
    @Test fun emptyRemoteResultKeepsCachedTracksLikeLegacy() = runBlocking {
        val db = database()
        try {
            val dao = db.catalogCacheDao()
            var body = """{"MediaContainer":{"totalSize":0,"Metadata":[]}}"""
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val store = object : ConnectionStore {
                override val connections = MutableStateFlow(mapOf(SourceType.PLEX to StoredConnection(SourceType.PLEX, "https://plex.invalid", token = "t")))
                override suspend fun awaitLoaded() = connections.value
                override suspend fun update(source: SourceType, transform: (StoredConnection?) -> StoredConnection?) = Unit
            }
            val tokens = object : SpotifyTokenProvider {
                override suspend fun accessToken(): String? = null
                override suspend fun refreshAfterUnauthorized(): String? = null
            }
            val repo = RoomCatalogRepository(db, store, ProviderRegistry(JellyfinClient(http, store), PlexClient(http), SpotifyClient(http, tokens, "https://spotify.invalid/")))
            val key = providerOwnerKey(SourceType.PLEX, "mix")
            val cached = Track("1", "Cached", "Artist", source = SourceType.PLEX)
            dao.replaceTracks(CachedTrackOwnerEntity(key, 100, 1), listOf(CachedTrackEntity(key, 0, AppJson.encodeToString(cached))))
            assertEquals(listOf(cached), repo.tracks(SourceType.PLEX, "mix", true))
            assertEquals(1, dao.tracks(key).size)
            assertEquals(1, dao.owner(key)!!.trackCount)
            assertTrue(dao.owner(key)!!.updatedAtMs > 100)
            body = """{"MediaContainer":{"totalSize":1,"Metadata":[{"ratingKey":"2","title":"Second"}]}}"""
            assertEquals(listOf("2"), repo.tracks(SourceType.PLEX, "mix", true).map { it.id })
            assertEquals(1, dao.tracks(key).size)
        } finally { db.close() }
    }
    @Test fun legacyImporterConvertsAllFormatsAndIsIdempotent() = runBlocking {
        val db = database()
        try {
            val dao = db.catalogCacheDao()
            val track = Track("song", "Song", "Artist", source = SourceType.JELLYFIN)
            val encoded = AppJson.encodeToString(listOf(track))
            val playlist = Playlist("mix", "Mix", "Jellyfin", 1, SourceType.JELLYFIN, tracks = listOf(track))
            val time = "2026-01-01T00:00:00Z"
            dao.putLegacy(AppCacheEntryEntity("provider_playlists", "{\"playlists\":${AppJson.encodeToString(listOf(playlist))}}", 1, time))
            dao.putLegacy(AppCacheEntryEntity("provider_playlist_tracks_jellyfin_mix", "{\"source\":\"jellyfin\",\"playlistId\":\"mix\",\"tracks\":$encoded}", 2, time))
            dao.putLegacy(AppCacheEntryEntity("union_playlist_tracks_union", "{\"unionPlaylistId\":\"union\",\"tracks\":$encoded}", 1, time))
            dao.putLegacy(AppCacheEntryEntity("unrelated", "{}", 1, time))
            val importer = LegacyCacheImporter(db)
            importer.runOnce()
            importer.runOnce()
            assertTrue(dao.legacyEntries().isEmpty())
            assertEquals("Mix", dao.playlists().single().name)
            assertEquals(track, AppJson.decodeFromString<Track>(dao.tracks(providerOwnerKey(SourceType.JELLYFIN, "mix")).single().trackJson))
            assertEquals(track, AppJson.decodeFromString<Track>(dao.tracks("u:union").single().trackJson))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM app_cache_entries WHERE `key`='unrelated'").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.getInt(0))
            }
        } finally { db.close() }
    }
}
