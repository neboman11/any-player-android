package com.anyplayer.android.providers

import android.util.Base64
import com.anyplayer.android.testing.Recorded
import com.anyplayer.android.testing.TestServer
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

/** Wire fixtures intentionally use the providers' native schemas, not serialized app models. */
internal class FakeJellyfin(val server: TestServer) {
    val url = server.url("/jellyfin")
    val token = "jf-tok"
    @Volatile var online = true
    @Volatile var playlistFailure = false
    @Volatile var searchFailure = false
    @Volatile var names = listOf("Jellyfin Mix", "Jellyfin Other")

    init {
        server.route("GET", "/jellyfin") { request ->
            when {
                !online -> unavailable()
                !carriesToken(request) -> response(401, "{}")
                request.path == "/jellyfin/System/Info" -> response(body = """{"ServerName":"Integration Jellyfin"}""")
                request.path == "/jellyfin/Users" -> response(body = """[{"Id":"listener","Name":"Listener"},{"Id":"other","Name":"Other"}]""")
                request.path == "/jellyfin/Users/listener/Items" -> items(request)
                else -> response(404, "{}")
            }
        }
        // Long enough to navigate Settings and disconnect mid-track without an automatic advance.
        listOf("jf-1", "jf-duplicate", "jf-2").forEach { server.audio("/jellyfin/Audio/$it/universal", 60_000) }
        artwork(server, "/jellyfin/Items/")
    }

    /** The access token is the contract; Client/Device/Version fields are incidental. */
    fun carriesToken(request: Recorded) = request.headers["Authorization"].orEmpty().removePrefix("MediaBrowser ")
        .split(',').map { it.trim().split('=', limit = 2) }
        .any { it.size == 2 && it[0] == "Token" && it[1].removeSurrounding("\"") == token }

    private fun items(request: Recorded): MockResponse {
        val search = request.query("searchTerm")
        if (search != null && searchFailure || search == null && request.query("ParentId") == null && playlistFailure) return unavailable()
        val rows = if (request.query("ParentId") != null || request.query("IncludeItemTypes") == "Audio") tracks() else playlists()
        val filtered = if (search == null) rows else rows.filter { it.getString("Name").contains(search, ignoreCase = true) }
        val offset = request.query("StartIndex")?.toIntOrNull() ?: 0
        val size = request.query("Limit")?.toIntOrNull() ?: 300
        return response(body = JSONObject().put("TotalRecordCount", filtered.size).put("Items", JSONArray(filtered.drop(offset).take(size))).toString())
    }

    private fun playlists() = names.mapIndexed { i, name -> JSONObject()
        .put("Id", "jf-mix-$i").put("Name", name).put("Type", "Playlist").put("ChildCount", 3)
        .put("ImageTags", JSONObject().put("Primary", "cover")) }

    private fun tracks() = listOf("jf-1" to "Jellyfin Signal", "jf-duplicate" to "Jellyfin Signal", "jf-2" to "Jellyfin Finale").map { (id, title) ->
        JSONObject().put("Id", id).put("Type", "Audio").put("Name", title).put("Artists", JSONArray(listOf("Jellyfin Artist")))
            .put("Album", "Jellyfin Album").put("RunTimeTicks", 600_000_000).put("AlbumId", "album").put("AlbumPrimaryImageTag", "cover")
            .put("MediaSources", JSONArray().put(JSONObject().put("MediaStreams", JSONArray().put(JSONObject()
                .put("Type", "Audio").put("BitRate", 128000).put("SampleRate", 8000)))))
    }
}

internal class FakePlex(val server: TestServer) {
    val url = server.url("/plex")
    val token = "px-tok"
    @Volatile var online = true
    @Volatile var playlistFailure = false
    @Volatile var searchFailure = false
    @Volatile var names = listOf("Plex Mix", "Plex Other")

    init {
        server.route("GET", "/plex") { request ->
            when {
                !online -> unavailable()
                request.query("X-Plex-Token") != token -> response(401, "{}")
                request.path == "/plex/identity" -> container(emptyList())
                request.path == "/plex/playlists/all" -> if (playlistFailure) unavailable() else page(request, playlists())
                request.path.startsWith("/plex/playlists/") && request.path.endsWith("/items") -> page(request, tracks())
                request.path == "/plex/search" -> if (searchFailure) unavailable() else {
                    val query = request.query("query").orEmpty()
                    container((tracks() + playlists()).filter { it.getString("title").contains(query, ignoreCase = true) })
                }
                else -> response(404, "{}")
            }
        }
        listOf("px-1", "px-duplicate", "px-2").forEach { server.audio("/plex/library/parts/$it/file.wav", 60_000) }
        artwork(server, "/plex/art/")
    }

    private fun page(request: Recorded, rows: List<JSONObject>): MockResponse {
        val offset = request.query("containerStart")?.toIntOrNull() ?: 0
        val size = request.query("containerSize")?.toIntOrNull() ?: 300
        return container(rows.drop(offset).take(size), rows.size)
    }

    private fun playlists() = names.mapIndexed { i, name -> JSONObject()
        .put("ratingKey", "px-mix-$i").put("title", name).put("type", "playlist").put("leafCount", 3)
        .put("thumb", "/art/playlist-$i").put("summary", "Plex integration playlist") }

    private fun tracks() = listOf("px-1" to "Plex Signal", "px-duplicate" to "Plex Signal", "px-2" to "Plex Finale").map { (id, title) ->
        JSONObject().put("ratingKey", id).put("title", title).put("type", "track").put("grandparentTitle", "Plex Artist")
            .put("parentTitle", "Plex Album").put("duration", 60_000).put("thumb", "/art/$id")
            .put("Media", JSONArray().put(JSONObject().put("bitrate", 128).put("Part", JSONArray().put(JSONObject()
                .put("key", "/library/parts/$id/file.wav").put("Stream", JSONArray().put(JSONObject().put("streamType", 2).put("samplingRate", 8000)))))))
    }

    private fun container(rows: List<JSONObject>, total: Int = rows.size) = response(body = JSONObject()
        .put("MediaContainer", JSONObject().put("totalSize", total).put("Metadata", JSONArray(rows))).toString())
}

private fun response(status: Int = 200, body: String) = MockResponse().setResponseCode(status)
    .setHeader("Content-Type", "application/json").setBody(body)

private fun unavailable() = response(503, "{}").setHeader("Retry-After", "0")

private fun artwork(server: TestServer, path: String) {
    val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1sAAAAASUVORK5CYII=", Base64.DEFAULT)
    server.route("GET", path) { MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)) }
}
