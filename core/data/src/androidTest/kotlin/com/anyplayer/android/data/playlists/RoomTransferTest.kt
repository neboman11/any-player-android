package com.anyplayer.android.data.playlists

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.auth.*
import com.anyplayer.android.data.db.AppDatabase
import com.anyplayer.android.data.db.entity.*
import com.anyplayer.android.data.transfer.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTransferTest {
    private lateinit var db: AppDatabase
    private val time = "2023-11-14T22:13:20Z"
    private val playlist = CustomPlaylist("p", "Local", createdAt = time, updatedAt = time, trackCount = 1, playlistType = PlaylistType.STANDARD)
    private val track = PlaylistTrack("row", "p", SourceType.PLEX, "song", 0, time, "Local", "Artist")
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
    }
    @After fun tearDown() { db.close() }
    private fun signed(data: AnyPlayerStateData): String {
        val seed = AnyPlayerStateEnvelope(createdAt = time, sourceApp = SourceAppInfo("android", "test"), data = data, integrity = Integrity(""))
        return AppJson.encodeToString(seed.copy(integrity = Integrity(StateTransferCrypto().sha256(AppJson.encodeToString(seed)))))
    }
    private class Connections : ConnectionStore {
        override val connections = MutableStateFlow<Map<SourceType, StoredConnection>>(emptyMap())
        override suspend fun awaitLoaded() = connections.value
        override suspend fun update(source: SourceType, transform: (StoredConnection?) -> StoredConnection?) {
            val next = transform(connections.value[source])
            connections.value = if (next == null) connections.value - source else connections.value + (source to next)
        }
    }

    @Test fun keepLocalImportsCloneWithoutStealingRowsAndWritesColumns() = runBlocking {
        val transfer = LegacyStateTransfer(PlaylistImporter(db), Connections(), "test")
        transfer.import(signed(AnyPlayerStateData(listOf(playlist), listOf(track))).byteInputStream(), MergePolicy.REPLACE_ALL, null, false)
        val incoming = AnyPlayerStateData(listOf(playlist.copy(name = "Imported")), listOf(track.copy(title = "Imported")), columnPreferences = mapOf("title" to ColumnPreferences("title", true, 2)))
        val summary = transfer.import(signed(incoming).byteInputStream(), MergePolicy.MERGE_KEEP_LOCAL, null, false)
        assertEquals(1, summary.playlistsAdded)
        assertEquals("Local", db.customPlaylistDao().tracks("p").single().title)
        val clone = db.customPlaylistDao().getAll().single { it.id != "p" }
        assertTrue(clone.id.startsWith("p_import_"))
        assertEquals("Imported", db.customPlaylistDao().tracks(clone.id).single().title)
        assertNotEquals("row", db.customPlaylistDao().tracks(clone.id).single().id)
        assertEquals(listOf(ColumnPreferenceEntity("title", true, 2)), db.customPlaylistDao().columns())
    }

    @Test fun importFailureRollsBackAllPlaylistWrites() = runBlocking {
        val transfer = LegacyStateTransfer(PlaylistImporter(db), Connections(), "test")
        transfer.import(signed(AnyPlayerStateData(listOf(playlist), listOf(track))).byteInputStream(), MergePolicy.REPLACE_ALL, null, false)
        val invalid = AnyPlayerStateData(listOf(playlist.copy(name = "Must rollback")), listOf(track, track.copy(position = 1, title = "Collision")))
        try {
            transfer.import(signed(invalid).byteInputStream(), MergePolicy.REPLACE_ALL, null, false)
            fail("Duplicate row id must abort import")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals("Local", db.customPlaylistDao().get("p")?.name)
        assertEquals(listOf("Local"), db.customPlaylistDao().tracks("p").map { it.title })
    }

    @Test fun positionCollisionsAreRenumberedInsteadOfAbortingTheImport() = runBlocking {
        val transfer = LegacyStateTransfer(PlaylistImporter(db), Connections(), "test")
        val incoming = AnyPlayerStateData(listOf(playlist), listOf(track, track.copy(id = "second", title = "Collision")))
        transfer.import(signed(incoming).byteInputStream(), MergePolicy.REPLACE_ALL, null, false)
        assertEquals(listOf("Local" to 0, "Collision" to 1), db.customPlaylistDao().tracks("p").map { it.title to it.position })
    }

    @Test fun importKeepsUnionMaterializationUnlessItsSourcesChanged() = runBlocking {
        val transfer = LegacyStateTransfer(PlaylistImporter(db), Connections(), "test")
        val same = CustomPlaylist("u", "Union", createdAt = time, updatedAt = time, trackCount = 1, playlistType = PlaylistType.UNION)
        val changed = same.copy(id = "u2", name = "Changed")
        val sameSource = UnionPlaylistSource("s", "u", SourceType.PLEX, "mix", 0, time)
        val changedSource = UnionPlaylistSource("s2", "u2", SourceType.PLEX, "mix", 0, time)
        transfer.import(signed(AnyPlayerStateData(listOf(same, changed), unionPlaylistSources = listOf(sameSource, changedSource))).byteInputStream(), MergePolicy.REPLACE_ALL, null, false)
        for (id in listOf("u", "u2")) db.unionCacheDao().replace(CachedTrackOwnerEntity("u:$id", 1, 1), listOf(CachedTrackEntity("u:$id", 0, "{}")))
        // Sync pull: same ordered sources for "u" (new row id, renamed), a different source for "u2".
        val pulled = AnyPlayerStateData(listOf(same.copy(name = "Renamed"), changed),
            unionPlaylistSources = listOf(sameSource.copy(id = "s-new"), changedSource.copy(sourcePlaylistId = "other")))
        transfer.import(signed(pulled).byteInputStream(), MergePolicy.REPLACE_ALL, null, false)
        assertEquals("Renamed", db.customPlaylistDao().get("u")?.name)
        assertEquals(1, db.unionCacheDao().tracks("u:u").size)
        assertNotNull(db.unionCacheDao().owner("u:u"))
        assertTrue(db.unionCacheDao().tracks("u:u2").isEmpty())
        assertNull(db.unionCacheDao().owner("u:u2"))
        assertEquals("other", db.customPlaylistDao().sources("u2").single().sourcePlaylistId)
    }

    @Test fun dryRunDoesNotMutateRoomOrConnections() = runBlocking {
        val connections = Connections()
        val transfer = LegacyStateTransfer(PlaylistImporter(db), connections, "test")
        val incoming = AnyPlayerStateData(listOf(playlist), listOf(track), connections = mapOf(SourceType.PLEX to ExportedConnection(SourceType.PLEX, "https://server")))
        val summary = transfer.import(signed(incoming).byteInputStream(), MergePolicy.REPLACE_ALL, null, true)
        assertEquals(1, summary.playlistsAdded)
        assertEquals(1, summary.connectionsImported)
        assertTrue(db.customPlaylistDao().getAll().isEmpty())
        assertTrue(db.customPlaylistDao().allTracks().isEmpty())
        assertTrue(connections.connections.value.isEmpty())
    }
}
