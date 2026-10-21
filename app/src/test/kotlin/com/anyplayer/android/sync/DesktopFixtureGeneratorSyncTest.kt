package com.anyplayer.android.sync

import com.anyplayer.android.core.model.AppJson
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.settings.SyncPreferences
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Golden vectors for the desktop (Rust) port. Set `ANYPLAYER_FIXTURE_OUT` (env or system property) to
 * write `sync_signatures.json` into that directory; otherwise the vectors are only self-checked.
 */
class DesktopFixtureGeneratorSyncTest {
    private fun track(id: String, source: SourceType = SourceType.SPOTIFY, title: String = "Title $id", album: String? = null, url: String? = null) =
        Track(id = id, title = title, artist = "Artist", album = album, source = source, url = url)

    private data class AppCase(val name: String, val current: Track?, val queue: List<Track>, val shuffle: Boolean?, val repeat: String)

    private val appCases = listOf(
        AppCase("empty", null, emptyList(), null, "off"),
        AppCase("empty_shuffle_false", null, emptyList(), false, "off"),
        AppCase("simple", track("a"), listOf(track("a"), track("b", SourceType.JELLYFIN)), true, "all"),
        AppCase("current_missing_from_queue", track("z", SourceType.PLEX), listOf(track("a"), track("b")), false, "one"),
        AppCase("current_same_id_other_source", track("a", SourceType.CUSTOM), listOf(track("a")), false, "off"),
        AppCase("duplicates", track("a"), listOf(track("a"), track("a"), track("b"), track("a")), null, "all"),
        AppCase("no_current", null, listOf(track("x", SourceType.CUSTOM, url = "https://h/x.mp3"), track("y", SourceType.PLEX)), true, "off"),
        AppCase("unicode_ids", track("é🎵"), listOf(track("é🎵", title = "Café ☕"), track("曲-東京", SourceType.JELLYFIN), track("q\"uo\\te\n\t\u0001")), true, "one"),
        AppCase("metadata_ignored", track("a", album = "Album", title = "Other"), listOf(track("a")), false, "off"),
    )

    private val playlistCases: List<Pair<String, String?>> = listOf(
        "null" to null,
        "not_array" to """{"x":1}""",
        "empty" to "[]",
        "single" to """[{"playlist":{"id":"p1","name":"One","description":null,"image_url":null,"created_at":1700000000,"updated_at":1700000001,"track_count":1,"playlist_type":"standard","is_distinct":false},"tracks":[{"id":1,"playlist_id":"p1","track_source":"spotify","track_id":"abc","position":0,"added_at":1700000000,"title":"T","artist":"A","album":null,"duration_ms":null,"image_url":null}],"union_sources":[]}]""",
        "unsorted_positions_and_row_ids" to """[{"playlist":{"id":"p2","name":"Two"},"tracks":[{"id":7,"position":2,"track_id":"c"},{"id":3,"position":0,"track_id":"a"},{"id":99,"position":1,"track_id":"b"}],"union_sources":[{"id":5,"position":1,"source_playlist_id":"s2","source_type":"plex"},{"id":4,"position":0,"source_playlist_id":"s1","source_type":"custom"}]},{"playlist":{"id":"p1","name":"First"}}]""",
        "missing_tracks_and_non_objects" to """[{"playlist":{"id":"b"}},5,"x",null,{"playlist":{"id":"a"},"tracks":[1,{"position":0,"k":null}],"extra":{"z":1,"a":[{"y":null,"b":2}]}}]""",
        "unicode_and_escapes" to """[{"playlist":{"id":"ü","name":"Café 🎶 東京","description":"q\"uote\\back\nnl\u0001"},"tracks":[{"position":0,"title":"é","artist":"😀"}]}]""",
        "missing_playlist_id" to """[{"playlist":{"name":"no id"}},{"playlist":{"id":"a"}}]""",
        "numbers" to """[{"playlist":{"id":"n","n1":1.0,"n2":1e3,"n3":-0,"n4":12345678901234}}]""",
    )

    private val settingsCases: List<Pair<String, String>> = listOf(
        "both_false" to settingsJson(false, false).toString(),
        "both_true" to settingsJson(true, true).toString(),
        "enabled_only" to settingsJson(true, false).toString(),
        "unsorted_with_nulls" to """{"z":null,"b":true,"a":{"y":1,"x":null},"c":[{"k":null,"j":"é"}]}""",
        "empty" to "{}",
        "unicode" to """{"name":"東京 🎵","q":"a\"b\\c\n"}""",
    )

    private val targetCases = listOf(
        "" to "",
        "sync.example.com" to "secret",
        "https://sync.example.com/" to "Bearer secret",
        "  http://host:8080///  " to "  bearer   tok en  ",
        "HTTPS://Upper.example" to "BEARER\ttoken",
        "https://sync.example.com/base/path/" to "",
        "sync.example.com" to "Bearer",
        "ünï.example/路径" to "tök€n",
    )

    private fun parse(text: String?): JsonElement? = text?.let(AppJson::parseToJsonElement)

    @Test fun `sync signature vectors`() {
        val appState = appCases.map { c ->
            buildJsonObject {
                put("name", c.name)
                put("current", c.current?.let { AppJson.encodeToJsonElement(Track.serializer(), it) } ?: JsonNull)
                put("queue", JsonArray(c.queue.map { AppJson.encodeToJsonElement(Track.serializer(), it) }))
                put("shuffle", c.shuffle)
                put("repeat", c.repeat)
                put("expected", appStateSignature(c.current, c.queue, c.shuffle, c.repeat))
            }
        }
        val playlists = playlistCases.map { (name, input) ->
            buildJsonObject { put("name", name); put("input", parse(input) ?: JsonNull); put("expected", playlistsSignature(parse(input))) }
        }
        val settings = settingsCases.map { (name, input) ->
            buildJsonObject { put("name", name); put("input", parse(input)!!); put("expected", settingsSignature(parse(input)!!)) }
        }
        val targets = targetCases.map { (url, token) ->
            buildJsonObject {
                put("server_target", url); put("auth_token", token)
                put("expected", syncBaseTarget(SyncPreferences(serverTarget = url, authToken = token)))
            }
        }

        // Self-consistency: normalizations the desktop must reproduce.
        assertEquals(appStateSignature(null, emptyList(), null, "off"), appState[0]["expected"]!!.jsonPrimitive.content)
        assertNotEquals(appState[0]["expected"], appState[1]["expected"])
        assertEquals(playlistsSignature(null), playlistsSignature(parse("[]")))
        assertEquals(syncBaseTarget(SyncPreferences("https://sync.example.com/", "Bearer secret")),
            syncBaseTarget(SyncPreferences("sync.example.com", "secret")))
        assertEquals(settingsSignature(parse("""{"b":1,"a":null}""")!!), settingsSignature(parse("""{"b":1}""")!!))

        val root = buildJsonObject {
            put("app_state", JsonArray(appState)); put("playlists", JsonArray(playlists))
            put("settings", JsonArray(settings)); put("base_target", JsonArray(targets))
        }
        fixtureOut()?.let { File(it, "sync_signatures.json").writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), root) + "\n") }
    }

    private fun fixtureOut(): File? = (System.getProperty("ANYPLAYER_FIXTURE_OUT") ?: System.getenv("ANYPLAYER_FIXTURE_OUT"))
        ?.takeIf { it.isNotBlank() }?.let(::File)?.also { it.mkdirs() }
}
