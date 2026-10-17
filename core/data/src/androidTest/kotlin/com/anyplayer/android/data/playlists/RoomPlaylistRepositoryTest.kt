package com.anyplayer.android.data.playlists

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.catalog.*
import com.anyplayer.android.data.db.AppDatabase
import com.anyplayer.android.data.db.entity.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomPlaylistRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var catalog: FakeCatalog
    private lateinit var repo: RoomPlaylistRepository
    private fun song(id: String, title: String = id, source: SourceType = SourceType.PLEX) = Track(id, title, "Artist", source = source)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        catalog = FakeCatalog()
        repo = RoomPlaylistRepository(db, catalog)
    }
    @After fun tearDown() { db.close() }

    @Test fun appendUsesMaximumPositionAndConcurrentAddsAreNotLost() = runBlocking {
        val p = repo.create("", PlaylistType.STANDARD)
        assertEquals("Untitled Playlist", p.name)
        db.customPlaylistDao().insertTracks(listOf(PlaylistTrackEntity("row", p.id, SourceType.PLEX, "a", 7, p.createdAt, "A", "Artist")))
        repo.addTrack(p.id, song("b"))
        assertEquals(listOf(7, 8), db.customPlaylistDao().tracks(p.id).map { it.position })
        coroutineScope { (1..20).map { async(Dispatchers.IO) { repo.addTrack(p.id, song("song-$it")) } }.awaitAll() }
        assertEquals(22, db.customPlaylistDao().get(p.id)?.trackCount)
        assertEquals(22, db.customPlaylistDao().tracks(p.id).size)
    }

    @Test fun removeReindexesAndReorderIgnoresUnknownIdsAndKeepsUnlistedOrder() = runBlocking {
        val p = repo.create("List", PlaylistType.STANDARD)
        listOf("a", "b", "c", "d").forEach { repo.addTrack(p.id, song(it)) }
        repo.removeTrackAt(p.id, 1)
        var tracks = db.customPlaylistDao().tracks(p.id)
        assertEquals(listOf("a", "c", "d"), tracks.map { it.trackId })
        assertEquals(listOf(0, 1, 2), tracks.map { it.position })
        repo.reorderTracks(p.id, listOf("missing", tracks[2].id, tracks[2].id))
        tracks = db.customPlaylistDao().tracks(p.id)
        assertEquals(listOf("d", "a", "c"), tracks.map { it.trackId })
        assertEquals(listOf(0, 1, 2), tracks.map { it.position })
        assertEquals(3, db.customPlaylistDao().get(p.id)?.trackCount)
    }

    @Test fun unionMaterializationKeepsSourceOrderIdentityDedupeAndNormalizedCache() = runBlocking {
        val standard = repo.create("Local", PlaylistType.STANDARD)
        repo.addTrack(standard.id, song("local"))
        val union = repo.create("Union", PlaylistType.UNION)
        catalog.values[SourceType.SPOTIFY to "abc"] = listOf(song("a", source = SourceType.SPOTIFY), song("same", "Same", SourceType.SPOTIFY))
        catalog.values[SourceType.PLEX to "remote"] = listOf(song("same", "Same"), song("local"))
        repo.replaceUnionSources(union.id, listOf(SourceType.SPOTIFY to "spotify:playlist:abc", SourceType.CUSTOM to standard.id, SourceType.PLEX to "remote"))
        assertEquals("abc", db.customPlaylistDao().sources(union.id).first().sourcePlaylistId)
        val tracks = repo.observeTracks(union.id).first()
        assertEquals(listOf("a", "same", "local", "same"), tracks.map { it.id })
        assertEquals(4, db.unionCacheDao().owner("u:${union.id}")?.trackCount)
        assertEquals(4, db.customPlaylistDao().get(union.id)?.trackCount)
        assertTrue(db.unionCacheDao().tracks("u:${union.id}").all { it.ownerKey == "u:${union.id}" })
        val progress = mutableListOf<String>()
        assertEquals(tracks, repo.materializeUnion(union.id, true, progress::add))
        assertEquals("Finalizing...", progress.last())
        assertTrue(catalog.requests.any { it.third })
    }

    @Test fun nestedUnionResolvesExactlyOneLevelAndSourceFailuresDoNotDiscardOthers() = runBlocking {
        val local = repo.create("Local", PlaylistType.STANDARD)
        repo.addTrack(local.id, song("a"))
        val nested = repo.create("Nested", PlaylistType.UNION)
        repo.replaceUnionSources(nested.id, listOf(SourceType.CUSTOM to local.id))
        val outer = repo.create("Outer", PlaylistType.UNION)
        repo.replaceUnionSources(outer.id, listOf(SourceType.CUSTOM to nested.id, SourceType.PLEX to "fail"))
        assertEquals(listOf("a"), repo.materializeUnion(outer.id).map { it.id })
        val tooDeep = repo.create("Too deep", PlaylistType.UNION)
        repo.replaceUnionSources(tooDeep.id, listOf(SourceType.CUSTOM to outer.id))
        assertTrue(repo.materializeUnion(tooDeep.id).isEmpty())
    }

    @Test fun deleteCascadesChildrenAndCacheButKeepsOtherUnionReferences() = runBlocking {
        val local = repo.create("Local", PlaylistType.STANDARD)
        repo.addTrack(local.id, song("a"))
        val union = repo.create("Union", PlaylistType.UNION)
        repo.replaceUnionSources(union.id, listOf(SourceType.CUSTOM to local.id))
        repo.delete(local.id)
        assertTrue(db.customPlaylistDao().tracks(local.id).isEmpty())
        assertEquals(local.id, db.customPlaylistDao().sources(union.id).single().sourcePlaylistId)
        repo.delete(union.id)
        assertTrue(db.customPlaylistDao().sources(union.id).isEmpty())
        assertNull(db.unionCacheDao().owner("u:${union.id}"))
        assertTrue(db.unionCacheDao().tracks("u:${union.id}").isEmpty())
    }

    @Test fun distinctPlaybackMapsClickedDuplicateToFirstOccurrenceForBothTypes() = runBlocking {
        val p = repo.create("List", PlaylistType.STANDARD)
        repo.addTrack(p.id, song("a", " First "))
        repo.addTrack(p.id, song("b", "Other"))
        repo.addTrack(p.id, song("c", "first"))
        assertEquals(2, repo.playbackSelection(p.id, 2).startIndex)
        repo.setDistinct(p.id, true)
        assertEquals(listOf("a", "b"), repo.playbackSelection(p.id, 2).tracks.map { it.id })
        assertEquals(0, repo.playbackSelection(p.id, 2).startIndex)
        assertEquals(1, repo.playbackSelection(p.id, 1).startIndex)
        assertEquals(0, repo.playbackSelection(p.id, 200).startIndex)
        val union = repo.create("Union", PlaylistType.UNION)
        repo.replaceUnionSources(union.id, listOf(SourceType.CUSTOM to p.id))
        repo.setDistinct(union.id, true)
        assertEquals(listOf("a", "b"), repo.playbackSelection(union.id, 2).tracks.map { it.id })
        assertEquals(0, repo.playbackSelection(union.id, 2).startIndex)
    }

    @Test fun parentUpsertAndRenameNeverCascadeDeleteChildren() = runBlocking {
        val p = repo.create("Original", PlaylistType.STANDARD)
        repo.addTrack(p.id, song("a"))
        db.customPlaylistDao().upsert(db.customPlaylistDao().get(p.id)!!.copy(name = "Updated"))
        repo.rename(p.id, "\t\n")
        assertEquals("Updated", db.customPlaylistDao().get(p.id)?.name)
        assertEquals(1, db.customPlaylistDao().tracks(p.id).size)
    }

    @Test fun unionSourceEditsUseMaximumPositionAndReindexWithoutChangingIds() = runBlocking {
        val union = repo.create("Union", PlaylistType.UNION)
        db.customPlaylistDao().insertSources(listOf(UnionPlaylistSourceEntity("source", union.id, SourceType.PLEX, "one", 7, union.createdAt)))
        catalog.values[SourceType.PLEX to "one"] = listOf(song("one"))
        catalog.values[SourceType.PLEX to "two"] = listOf(song("two"))
        repo.addUnionSource(union.id, SourceType.PLEX, "two")
        val rows = db.customPlaylistDao().sources(union.id)
        assertEquals(listOf(7, 8), rows.map { it.position })
        repo.reorderUnionSources(union.id, listOf("missing", rows[1].id, rows[1].id))
        assertEquals(listOf("two", "one"), repo.observeTracks(union.id).first().map { it.id })
        assertEquals(listOf(rows[1].id, "source"), db.customPlaylistDao().sources(union.id).map { it.id })
        repo.removeUnionSource(union.id, rows[1].id)
        assertEquals(0, db.customPlaylistDao().sources(union.id).single().position)
        assertEquals(listOf("one"), repo.observeTracks(union.id).first().map { it.id })
        assertEquals(1, db.customPlaylistDao().get(union.id)?.trackCount)
    }

    @Test fun failedChildReplacementRollsBackDeletion() = runBlocking {
        val p = repo.create("List", PlaylistType.STANDARD)
        repo.addTrack(p.id, song("original"))
        val original = db.customPlaylistDao().tracks(p.id).single()
        try {
            db.customPlaylistDao().replaceTracks(p.id, listOf(original, original))
            fail("Duplicate primary key must abort the whole transaction")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(listOf(original), db.customPlaylistDao().tracks(p.id))
        assertEquals(1, db.customPlaylistDao().get(p.id)?.trackCount)
    }

    private class FakeCatalog : CatalogRepository {
        val values = mutableMapOf<Pair<SourceType, String>, List<Track>>()
        val requests = java.util.Collections.synchronizedList(mutableListOf<Triple<SourceType, String, Boolean>>())
        override val playlists = flowOf(emptyList<Playlist>())
        override suspend fun refreshPlaylists() = emptyList<ProviderRefreshFailure>()
        override suspend fun tracks(source: SourceType, playlistId: String, forceRefresh: Boolean): List<Track> {
            requests.add(Triple(source, playlistId, forceRefresh))
            if (playlistId == "fail") error("source unavailable")
            if (source == SourceType.SPOTIFY) delay(20)
            return values[source to playlistId].orEmpty()
        }
        override suspend fun cachedTracks(source: SourceType, playlistId: String) = values[source to playlistId].orEmpty()
        override suspend fun refreshAll(onProgress: (String) -> Unit) = emptyList<ProviderRefreshFailure>()
        override suspend fun search(query: String, source: SourceType, type: SearchType) = SearchResult()
        override suspend fun clearCache() { values.clear() }
        override suspend fun isDistinct(source: SourceType, playlistId: String) = false
        override suspend fun setDistinct(source: SourceType, playlistId: String, distinct: Boolean) = Unit
    }
}
