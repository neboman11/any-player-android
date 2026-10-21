package com.anyplayer.android.sync

import com.anyplayer.android.core.model.AppJson
import com.anyplayer.android.data.transfer.MergePolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SyncFixturesTest {
    @Test fun `fake importer models lossy production import instead of echoing wire JSON`() = runTest {
        val config = FakeConfig()
        config.importJson(AppJson.parseToJsonElement("""{"custom_playlists":[{
            "playlist":{"id":"p1","name":"Phone","created_at":1,"updated_at":1,"track_count":2,"playlist_type":"future","unknown":true},
            "tracks":[{"id":9,"playlist_id":"p1","track_source":"custom","track_id":"t","position":8,"added_at":1,"title":"Track","artist":"Artist","unknown":true},
                      {"id":10,"playlist_id":"p1","track_source":"future","track_id":"x","position":1,"added_at":1,"title":"Unknown","artist":"Artist"}],
            "union_sources":[]}]}"""), MergePolicy.REPLACE_ALL, false)
        val imported = config.exportPlaylistsJson().jsonArray.single().jsonObject
        assertEquals("standard", imported.getValue("playlist").jsonObject.getValue("playlist_type").jsonPrimitive.content)
        assertFalse(imported.getValue("playlist").jsonObject.containsKey("unknown"))
        val track = imported.getValue("tracks").jsonArray.single().jsonObject
        assertEquals("t", track.getValue("track_id").jsonPrimitive.content)
        assertEquals(0, track.getValue("position").jsonPrimitive.int)
        assertFalse(track.containsKey("unknown"))
    }
}
