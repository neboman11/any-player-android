package com.anyplayer.android.spotify

import android.os.SystemClock
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.testing.Recorded
import com.anyplayer.android.testing.TestServer
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject

/** Spotify's HTTP boundary only: production auth, catalog, device resolver and backend remain real. */
class FakeSpotify(val server: TestServer) {
    @Volatile var premium = true
    @Volatile var devices = listOf(
        Device("desktop", "Studio", "Computer", active = true),
        Device("speaker", "Kitchen", "Speaker"),
        Device("restricted", "Restricted TV", "TV", restricted = true),
    )
    @Volatile var rejectedToken: String? = null
    @Volatile var tokenResponseGate: CountDownLatch? = null
    val songs = (1..3).map { track(it) }

    private var refreshCount = 0
    private var loaded = emptyList<String>()
    private var index = 0
    private var position = 0L
    private var anchoredAt = SystemClock.elapsedRealtime()
    private var playing = false
    private var volume = 100
    private var shuffle = false
    private var repeat = "off"
    private val durations = songs.associate { it.id to it.durationMs!! }.toMutableMap()

    init {
        server.route("POST", TOKEN_PATH) { request ->
            val form = form(request.body)
            check(tokenResponseGate?.await(30, TimeUnit.SECONDS) != false) { "Token response was not released" }
            synchronized(this) {
                val token = if (form["grant_type"] == "refresh_token") "refreshed-${++refreshCount}" else "linked-access"
                json(JSONObject().put("access_token", token).put("refresh_token", "rotated-refresh")
                    .put("token_type", "Bearer").put("expires_in", 3600))
            }
        }
        server.route(pathPrefix = API) { request -> synchronized(this) { handle(request) } }
    }

    private fun handle(request: Recorded): MockResponse {
        if (rejectedToken != null && request.headers["Authorization"] == "Bearer $rejectedToken")
            return MockResponse().setResponseCode(401).setBody("{\"error\":{\"status\":401}}")
        val path = request.path.removePrefix(API)
        if (request.method == "GET") return when (path) {
            "me" -> json(JSONObject().put("id", "integration-user").put("display_name", "Spotify Listener")
                .put("product", if (premium) "premium" else "free"))
            "me/playlists" -> json(page(listOf(playlist())))
            "playlists/mixtape/tracks" -> json(page(songs.map { JSONObject().put("track", trackJson(it)) }))
            "search" -> json(JSONObject().put(if (request.query("type") == "playlist") "playlists" else "tracks",
                page(if (request.query("type") == "playlist") listOf(playlist()) else songs.map(::trackJson))))
            "me/player/devices" -> json(JSONObject().put("devices", JSONArray(devices.map { it.json() })))
            "me/player" -> snapshot()
            else -> MockResponse().setResponseCode(404)
        }
        if (request.method != "PUT") return MockResponse().setResponseCode(405)
        updatePosition()
        when (path) {
            "me/player/play" -> {
                val body = JSONObject(request.body.ifBlank { "{}" })
                body.optJSONArray("uris")?.let { uris ->
                    loaded = (0 until uris.length()).map { uris.getString(it).removePrefix("spotify:track:") }
                    index = body.getJSONObject("offset").getInt("position")
                    position = body.optLong("position_ms", 0)
                }
                // A resume after natural completion starts the loaded run again. Do not let
                // updatePosition immediately erase a stray play command at the old duration.
                if (loaded.isNotEmpty() && position >= durations.getValue(loaded[index])) {
                    index = 0
                    position = 0
                }
                playing = true
            }
            "me/player/pause" -> playing = false
            "me/player/seek" -> position = request.query("position_ms")!!.toLong()
            "me/player/volume" -> volume = request.query("volume_percent")!!.toInt()
            "me/player/shuffle" -> shuffle = request.query("state").toBoolean()
            "me/player/repeat" -> repeat = request.query("state")!!
            else -> return MockResponse().setResponseCode(404)
        }
        anchoredAt = SystemClock.elapsedRealtime()
        return MockResponse().setResponseCode(204)
    }

    @Synchronized fun advanceTo(track: Track, positionMs: Long = 500) {
        require(track.id in loaded) { "Track must already be in Spotify's loaded run" }
        index = loaded.indexOf(track.id)
        position = positionMs
        anchoredAt = SystemClock.elapsedRealtime()
        playing = true
    }

    /** End only when requested by the test, after it has observed PLAYING over the real poller. */
    @Synchronized fun finishCurrent() {
        check(loaded.isNotEmpty()) { "No Spotify run loaded" }
        position = durations.getValue(loaded[index])
        anchoredAt = SystemClock.elapsedRealtime()
        playing = false
    }

    @Synchronized fun register(track: Track) { durations[track.id] = requireNotNull(track.durationMs) }
    @Synchronized fun enableDeviceShuffle() { shuffle = true }

    private fun snapshot(): MockResponse {
        if (loaded.isEmpty()) return if (shuffle) json(JSONObject().put("is_playing", false)
            .put("progress_ms", 0).put("item", JSONObject.NULL).put("shuffle_state", true))
            else MockResponse().setResponseCode(204)
        updatePosition()
        val id = loaded[index]
        return json(JSONObject().put("is_playing", playing).put("progress_ms", position)
            .put("item", JSONObject().put("id", id).put("duration_ms", durations.getValue(id)))
            .put("device", JSONObject().put("volume_percent", volume))
            .put("shuffle_state", shuffle).put("repeat_state", repeat))
    }

    /** Real elapsed progress prevents the backend's ten-second frozen-player recovery from firing. */
    private fun updatePosition() {
        val now = SystemClock.elapsedRealtime()
        if (playing && loaded.isNotEmpty()) {
            position += now - anchoredAt
            while (position >= durations.getValue(loaded[index])) {
                val duration = durations.getValue(loaded[index])
                when {
                    repeat == "track" -> position %= duration
                    index < loaded.lastIndex -> { position -= duration; index++ }
                    else -> { position = duration; playing = false; break }
                }
            }
        }
        anchoredAt = now
    }

    private fun playlist() = JSONObject().put("id", "mixtape").put("name", "Spotify Road Mix")
        .put("owner", JSONObject().put("display_name", "Spotify Listener"))
        .put("tracks", JSONObject().put("total", songs.size)).put("images", JSONArray())

    private fun trackJson(track: Track) = JSONObject().put("id", track.id).put("name", track.title)
        .put("duration_ms", track.durationMs).put("artists", JSONArray().put(JSONObject().put("name", track.artist)))
        .put("album", JSONObject().put("name", track.album).put("images", JSONArray()))

    private fun page(items: List<JSONObject>) = JSONObject().put("items", JSONArray(items)).put("total", items.size)
        .put("next", JSONObject.NULL)

    data class Device(val id: String, val name: String, val type: String, val active: Boolean = false, val restricted: Boolean = false) {
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("type", type)
            .put("is_active", active).put("is_restricted", restricted)
    }

    companion object {
        const val API = "/spotify-api/v1/"
        const val TOKEN_PATH = "/spotify-accounts/api/token"
        const val PLAYER = "${API}me/player"
        fun track(n: Int, durationMs: Long = 120_000): Track {
            val id = "song" + n.toString().padStart(18, '0')
            return Track(id = id, title = "Spotify Song $n", artist = "Test Artist", album = "Test Album",
                durationMs = durationMs, source = SourceType.SPOTIFY, url = "spotify:track:$id", enriched = true)
        }
        fun form(body: String): Map<String, String> = body.split('&').associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        private fun json(body: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }
}
