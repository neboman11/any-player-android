package com.anyplayer.android.data.transfer

import com.anyplayer.android.core.model.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistImporterTest {
    private fun playlist(name: String = "local") = CustomPlaylist("p", name, createdAt = "2023-11-14T22:13:20Z", updatedAt = "2023-11-14T22:13:20Z", trackCount = 1, playlistType = PlaylistType.STANDARD)
    private fun track(parent: String = "p") = PlaylistTrack("row", parent, SourceType.PLEX, "song", 0, "2023-11-14T22:13:20Z", "Song", "Artist")

    @Test fun keepLocalRemapsChildrenAndCustomReferencesDeterministically() {
        val incoming = AnyPlayerStateData(listOf(playlist("incoming")), listOf(track()), listOf(UnionPlaylistSource("link", "p", SourceType.CUSTOM, "p", 0, "now")))
        val first = planPlaylistImport(incoming, listOf(playlist()), listOf(track()), emptyList(), MergePolicy.MERGE_KEEP_LOCAL, "import")
        val second = planPlaylistImport(incoming, listOf(playlist()), listOf(track()), emptyList(), MergePolicy.MERGE_KEEP_LOCAL, "import")
        assertEquals(first, second)
        assertTrue(first.data.customPlaylists.single().id.startsWith("p_import_"))
        assertNotEquals("row", first.data.playlistTracks.single().id)
        assertEquals(first.data.customPlaylists.single().id, first.data.playlistTracks.single().playlistId)
        assertEquals(first.data.customPlaylists.single().id, first.data.unionPlaylistSources.single().sourcePlaylistId)
        assertEquals(1, first.summary.tracksAdded)
    }

    @Test fun preferImportKeepsIdentityAndCountsUpdates() {
        val plan = planPlaylistImport(AnyPlayerStateData(listOf(playlist("incoming")), listOf(track())), listOf(playlist()), listOf(track()), emptyList(), MergePolicy.MERGE_PREFER_IMPORT, "import")
        assertEquals("p", plan.data.customPlaylists.single().id)
        assertEquals("incoming", plan.data.customPlaylists.single().name)
        assertEquals(1, plan.summary.playlistsUpdated)
        assertEquals(1, plan.summary.tracksUpdated)
    }

    @Test fun replaceAllAllowsEmptyUnion() {
        val plan = planPlaylistImport(AnyPlayerStateData(listOf(playlist().copy(playlistType = PlaylistType.UNION))), listOf(playlist()), emptyList(), emptyList(), MergePolicy.REPLACE_ALL, "import")
        assertEquals(PlaylistType.UNION, plan.data.customPlaylists.single().playlistType)
        assertTrue(plan.data.unionPlaylistSources.isEmpty())
    }

    @Test fun collidingAndGappedPositionsAreRenumberedInStableOrderInsteadOfAborting() {
        val tracks = listOf(track().copy(id = "a", position = 5), track().copy(id = "b", position = 2), track().copy(id = "c", position = 5))
        val sources = listOf(UnionPlaylistSource("s1", "p", SourceType.PLEX, "x", 3, "now"), UnionPlaylistSource("s2", "p", SourceType.PLEX, "y", 3, "now"),
            UnionPlaylistSource("s3", "p", SourceType.PLEX, "z", 0, "now"))
        val plan = planPlaylistImport(AnyPlayerStateData(listOf(playlist()), tracks, sources), emptyList(), emptyList(), emptyList(), MergePolicy.REPLACE_ALL, "import")
        assertEquals(listOf("b" to 0, "a" to 1, "c" to 2), plan.data.playlistTracks.map { it.id to it.position })
        assertEquals(listOf("s3" to 0, "s1" to 1, "s2" to 2), plan.data.unionPlaylistSources.map { it.id to it.position })
        assertEquals(3, plan.summary.tracksAdded)
    }
}
